package org.hyperledger.besu.plugin.cache.analyzer;

/** Block-level metadata captured from BlockHeader/BlockBody during tracing. */
public record BlockMetadata(
    long evmExecutionMs,
    long stateRootMs,
    long totalBlockMs,
    long gasUsed,
    long gasLimit,
    long baseFeeWei,
    long blobGasUsed,
    int blobTxCount) {

  public double gasUsedPercent() {
    return gasLimit > 0 ? gasUsed * 100.0 / gasLimit : 0;
  }

  public double baseFeeGwei() {
    return baseFeeWei / 1_000_000_000.0;
  }

  /** Create a copy with state root and total timings filled in. */
  public BlockMetadata withTimings(final long stateRootMs, final long totalBlockMs) {
    return new BlockMetadata(
        evmExecutionMs, stateRootMs, totalBlockMs,
        gasUsed, gasLimit, baseFeeWei, blobGasUsed, blobTxCount);
  }
}
