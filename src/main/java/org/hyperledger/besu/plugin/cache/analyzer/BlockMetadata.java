package org.hyperledger.besu.plugin.cache.analyzer;

/** Block-level metadata captured from BlockHeader/BlockBody during tracing. */
public record BlockMetadata(
    long executionTimeMs,
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
}
