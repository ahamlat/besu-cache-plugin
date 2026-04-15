package org.hyperledger.besu.plugin.cache.tracer;

import org.hyperledger.besu.plugin.cache.analyzer.BlockAnalysisResult;
import org.hyperledger.besu.plugin.cache.analyzer.SloadRecord;
import org.hyperledger.besu.plugin.cache.naming.ContractNameResolver;
import org.hyperledger.besu.plugin.cache.store.BlockResultStore;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.worldstate.WorldView;
import org.hyperledger.besu.plugin.data.BlockBody;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures every SLOAD during block execution and classifies as warm/cold
 * based on the gas cost returned by SLoadOperation.
 */
public class SloadTracer implements BlockAwareOperationTracer {

  private static final Logger LOG = LoggerFactory.getLogger(SloadTracer.class);
  private static final int SLOAD_OPCODE = 0x54;

  private final BlockResultStore store;
  private final ContractNameResolver nameResolver;

  private long currentBlockNumber;
  private String currentBlockHash;
  private long currentBlockTimestamp;
  private int currentTxCount;
  private int txIndex = -1;
  private final List<SloadRecord> sloads = new ArrayList<>();

  // Per-SLOAD tracking: address captured in pre, cold classification in post
  private Address pendingAddress;
  private UInt256 pendingSlot;

  public SloadTracer(final BlockResultStore store, final ContractNameResolver nameResolver) {
    this.store = store;
    this.nameResolver = nameResolver;
  }

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final BlockHeader blockHeader,
      final BlockBody blockBody,
      final Address miningBeneficiary) {
    resetBlock(blockHeader.getNumber(), blockHeader.getBlockHash().toHexString(),
        blockHeader.getTimestamp());
  }

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final ProcessableBlockHeader processableBlockHeader,
      final Address miningBeneficiary) {
    resetBlock(processableBlockHeader.getNumber(), "", processableBlockHeader.getTimestamp());
  }

  private void resetBlock(final long blockNum, final String blockHash, final long timestamp) {
    sloads.clear();
    txIndex = -1;
    pendingAddress = null;
    pendingSlot = null;
    currentBlockNumber = blockNum;
    currentBlockHash = blockHash;
    currentBlockTimestamp = timestamp;
    currentTxCount = 0;
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
      // Cold SLOAD costs 2100 gas, warm costs 100 gas (post EIP-2929)
      boolean isCold = gasCost > 200;
      sloads.add(new SloadRecord(pendingAddress, pendingSlot, isCold, txIndex));

      nameResolver.enqueue(pendingAddress);

      pendingAddress = null;
      pendingSlot = null;
    }
  }

  @Override
  public void traceEndBlock(final BlockHeader blockHeader, final BlockBody blockBody) {
    if (currentBlockHash.isEmpty()) {
      currentBlockHash = blockHeader.getBlockHash().toHexString();
    }
    BlockAnalysisResult result = BlockAnalysisResult.build(
        currentBlockNumber,
        currentBlockHash,
        currentBlockTimestamp,
        currentTxCount,
        sloads,
        addr -> nameResolver.getName(addr));

    store.store(result);
    LOG.info("Block {} analyzed: {} SLOADs ({} cold, {} warm) across {} contracts",
        currentBlockNumber, result.totalSloads(), result.coldSloads(), result.warmSloads(),
        result.accountStats().size());
  }
}
