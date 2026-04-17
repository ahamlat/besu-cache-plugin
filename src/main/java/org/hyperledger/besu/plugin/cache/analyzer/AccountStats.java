package org.hyperledger.besu.plugin.cache.analyzer;

/** Aggregated SLOAD statistics for a single contract within one block. */
public record AccountStats(
    String address,
    String contractName,
    int totalReads,
    int coldReads,
    int warmReads,
    int accumulator,
    int memtable,
    int blockCache,
    int disk,
    int notFound,
    long totalTimeNs,
    long maxTimeNs) {

  public double coldPercent() {
    return totalReads > 0 ? coldReads * 100.0 / totalReads : 0;
  }

  public double warmPercent() {
    return totalReads > 0 ? warmReads * 100.0 / totalReads : 0;
  }

  public double accumulatorPercent() {
    return totalReads > 0 ? accumulator * 100.0 / totalReads : 0;
  }

  public double memtablePercent() {
    return totalReads > 0 ? memtable * 100.0 / totalReads : 0;
  }

  public double blockCachePercent() {
    return totalReads > 0 ? blockCache * 100.0 / totalReads : 0;
  }

  public double diskPercent() {
    return totalReads > 0 ? disk * 100.0 / totalReads : 0;
  }

  public double notFoundPercent() {
    return totalReads > 0 ? notFound * 100.0 / totalReads : 0;
  }

  public long avgTimeNs() {
    return totalReads > 0 ? totalTimeNs / totalReads : 0;
  }
}
