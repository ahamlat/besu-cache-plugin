package org.hyperledger.besu.plugin.cache.tracer;

import org.hyperledger.besu.plugin.cache.analyzer.BlockAnalysisResult;
import org.hyperledger.besu.plugin.cache.analyzer.BlockMetadata;
import org.hyperledger.besu.plugin.cache.analyzer.SloadRecord;
import org.hyperledger.besu.plugin.cache.naming.ContractNameResolver;
import org.hyperledger.besu.plugin.cache.rocksdb.RocksDBStatsProvider;
import org.hyperledger.besu.plugin.cache.store.BlockResultStore;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.worldstate.WorldView;
import org.hyperledger.besu.plugin.data.BlockBody;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures every SLOAD during block execution and classifies each as:
 * <ul>
 *   <li>EVM-level: warm/cold (based on gas cost, EIP-2929)</li>
 *   <li>Storage-level: CACHED / NOT_FOUND / STORAGE_READ</li>
 * </ul>
 *
 * Captures block metadata and measures wall-clock EVM execution time.
 * Stores pending nanos so the BlockAddedListener can compute state-root time.
 */
public class SloadTracer implements BlockAwareOperationTracer {

  private static final Logger LOG = LoggerFactory.getLogger(SloadTracer.class);
  private static final int SLOAD_OPCODE = 0x54;
  private static final int SSTORE_OPCODE = 0x55;

  private final BlockResultStore store;
  private final ContractNameResolver nameResolver;
  private final RocksDBStatsProvider statsProvider;
  private final ConcurrentHashMap<Long, long[]> pendingTimings;

  private long currentBlockNumber;
  private String currentBlockHash;
  private long currentBlockTimestamp;
  private int currentTxCount;
  private int txIndex = -1;
  private final List<SloadRecord> sloads = new ArrayList<>();
  private final Set<SlotKey> seenSlots = new HashSet<>();

  private Address pendingAddress;
  private UInt256 pendingSlot;
  private int sstoreCount;

  private RocksDBStatsProvider.Snapshot blockStartSnapshot;
  private long blockStartNanos;
  private long currentGasLimit;
  private long currentBaseFeeWei;

  /**
   * @param pendingTimings shared map where traceEndBlock stores [blockStartNanos, blockEndNanos]
   *                       keyed by block number, for the BlockAddedListener to consume.
   */
  public SloadTracer(
      final BlockResultStore store,
      final ContractNameResolver nameResolver,
      final RocksDBStatsProvider statsProvider,
      final ConcurrentHashMap<Long, long[]> pendingTimings) {
    this.store = store;
    this.nameResolver = nameResolver;
    this.statsProvider = statsProvider;
    this.pendingTimings = pendingTimings;
  }

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final BlockHeader blockHeader,
      final BlockBody blockBody,
      final Address miningBeneficiary) {
    resetBlock(blockHeader.getNumber(), blockHeader.getBlockHash().toHexString(),
        blockHeader.getTimestamp(), blockHeader.getGasLimit(),
        blockHeader.getBaseFee().map(q -> q.getAsBigInteger().longValueExact()).orElse(0L));
  }

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final ProcessableBlockHeader processableBlockHeader,
      final Address miningBeneficiary) {
    resetBlock(processableBlockHeader.getNumber(), "", processableBlockHeader.getTimestamp(),
        processableBlockHeader.getGasLimit(),
        processableBlockHeader.getBaseFee().map(q -> q.getAsBigInteger().longValueExact()).orElse(0L));
  }

  private void resetBlock(final long blockNum, final String blockHash, final long timestamp,
      final long gasLimit, final long baseFeeWei) {
    sloads.clear();
    seenSlots.clear();
    txIndex = -1;
    pendingAddress = null;
    pendingSlot = null;
    sstoreCount = 0;
    currentBlockNumber = blockNum;
    currentBlockHash = blockHash;
    currentBlockTimestamp = timestamp;
    currentTxCount = 0;
    currentGasLimit = gasLimit;
    currentBaseFeeWei = baseFeeWei;
    blockStartSnapshot = statsProvider.snapshot();
    blockStartNanos = System.nanoTime();
  }

  @Override
  public void traceStartTransaction(final WorldView worldView, final Transaction transaction) {
    txIndex++;
    currentTxCount++;
  }

  @Override
  public void tracePreExecution(final MessageFrame frame) {
    int opcode = frame.getCurrentOperation().getOpcode();
    if (opcode == SLOAD_OPCODE) {
      pendingAddress = frame.getRecipientAddress();
      Bytes raw = frame.getStackItem(0);
      pendingSlot = UInt256.fromBytes(raw);
    } else if (opcode == SSTORE_OPCODE) {
      sstoreCount++;
    }
  }

  @Override
  public void tracePostExecution(final MessageFrame frame, final OperationResult operationResult) {
    if (pendingAddress != null) {
      long gasCost = operationResult.getGasCost();
      boolean isCold = gasCost > 200;

      SlotKey key = new SlotKey(pendingAddress, pendingSlot);
      String storageType;

      if (!seenSlots.add(key)) {
        storageType = "CACHED";
      } else {
        Bytes loadedValue = frame.getStackItem(0);
        boolean isZero = loadedValue.isZero();
        storageType = isZero ? "NOT_FOUND" : "STORAGE_READ";
      }

      sloads.add(new SloadRecord(pendingAddress, pendingSlot, isCold, txIndex, storageType));
      nameResolver.enqueue(pendingAddress);

      pendingAddress = null;
      pendingSlot = null;
    }
  }

  @Override
  public void traceEndBlock(final BlockHeader blockHeader, final BlockBody blockBody) {
    long blockEndNanos = System.nanoTime();
    long evmExecutionMs = (blockEndNanos - blockStartNanos) / 1_000_000;

    if (currentBlockHash.isEmpty()) {
      currentBlockHash = blockHeader.getBlockHash().toHexString();
    }

    long gasUsed = blockHeader.getGasUsed();
    long blobGasUsed = blockHeader.getBlobGasUsed().map(Long::longValue).orElse(0L);
    int blobTxCount = 0;
    for (var tx : blockBody.getTransactions()) {
      if (tx.getType() == TransactionType.BLOB) {
        blobTxCount++;
      }
    }

    BlockMetadata metadata = new BlockMetadata(
        evmExecutionMs, 0, 0,
        gasUsed, currentGasLimit, currentBaseFeeWei,
        blobGasUsed, blobTxCount);

    RocksDBStatsProvider.Snapshot blockEndSnapshot = statsProvider.snapshot();
    RocksDBStatsProvider.Snapshot blockDelta = blockEndSnapshot.delta(blockStartSnapshot);

    BlockAnalysisResult result = BlockAnalysisResult.build(
        currentBlockNumber,
        currentBlockHash,
        currentBlockTimestamp,
        currentTxCount,
        sloads,
        sstoreCount,
        addr -> nameResolver.getName(addr),
        statsProvider.isAvailable(),
        blockDelta,
        metadata);

    store.store(result);

    pendingTimings.put(currentBlockNumber, new long[]{blockStartNanos, blockEndNanos});

    LOG.info("Block {} EVM done in {}ms: {} SLOADs {} SSTOREs ({} read, {} notfound, {} cached) "
            + "gas {}/{} across {} contracts",
        currentBlockNumber, evmExecutionMs, result.totalSloads(), sstoreCount,
        result.storageReads(), result.notFound(), result.cached(),
        gasUsed, currentGasLimit, result.accountStats().size());
  }

  private record SlotKey(Address address, UInt256 slot) {}
}
