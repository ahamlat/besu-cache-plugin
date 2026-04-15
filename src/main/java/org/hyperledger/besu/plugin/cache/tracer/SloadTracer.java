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
 * Also captures block metadata (gas, base fee, blob info) and
 * measures wall-clock execution time between traceStartBlock and traceEndBlock.
 */
public class SloadTracer implements BlockAwareOperationTracer {

  private static final Logger LOG = LoggerFactory.getLogger(SloadTracer.class);
  private static final int SLOAD_OPCODE = 0x54;

  private final BlockResultStore store;
  private final ContractNameResolver nameResolver;
  private final RocksDBStatsProvider statsProvider;

  private long currentBlockNumber;
  private String currentBlockHash;
  private long currentBlockTimestamp;
  private int currentTxCount;
  private int txIndex = -1;
  private final List<SloadRecord> sloads = new ArrayList<>();
  private final Set<SlotKey> seenSlots = new HashSet<>();

  private Address pendingAddress;
  private UInt256 pendingSlot;

  private RocksDBStatsProvider.Snapshot blockStartSnapshot;
  private long blockStartNanos;
  private long currentGasLimit;
  private long currentBaseFeeWei;

  public SloadTracer(
      final BlockResultStore store,
      final ContractNameResolver nameResolver,
      final RocksDBStatsProvider statsProvider) {
    this.store = store;
    this.nameResolver = nameResolver;
    this.statsProvider = statsProvider;
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
    if (frame.getCurrentOperation().getOpcode() == SLOAD_OPCODE) {
      pendingAddress = frame.getRecipientAddress();
      Bytes raw = frame.getStackItem(0);
      pendingSlot = UInt256.fromBytes(raw);
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
    long executionTimeMs = (System.nanoTime() - blockStartNanos) / 1_000_000;

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
        executionTimeMs, gasUsed, currentGasLimit, currentBaseFeeWei,
        blobGasUsed, blobTxCount);

    RocksDBStatsProvider.Snapshot blockEndSnapshot = statsProvider.snapshot();
    RocksDBStatsProvider.Snapshot blockDelta = blockEndSnapshot.delta(blockStartSnapshot);

    BlockAnalysisResult result = BlockAnalysisResult.build(
        currentBlockNumber,
        currentBlockHash,
        currentBlockTimestamp,
        currentTxCount,
        sloads,
        addr -> nameResolver.getName(addr),
        statsProvider.isAvailable(),
        blockDelta,
        metadata);

    store.store(result);
    LOG.info("Block {} analyzed in {}ms: {} SLOADs ({} read, {} notfound, {} cached) "
            + "gas {}/{} across {} contracts",
        currentBlockNumber, executionTimeMs, result.totalSloads(),
        result.storageReads(), result.notFound(), result.cached(),
        gasUsed, currentGasLimit, result.accountStats().size());
  }

  private record SlotKey(Address address, UInt256 slot) {}
}
