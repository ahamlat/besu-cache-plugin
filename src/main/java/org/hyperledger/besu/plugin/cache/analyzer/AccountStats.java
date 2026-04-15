package org.hyperledger.besu.plugin.cache.analyzer;

/** Aggregated SLOAD statistics for a single contract within one block. */
public record AccountStats(
    String address,
    String contractName,
    int totalReads,
    int coldReads,
    int warmReads,
    int cacheHits,
    int cacheMisses,
    int memtableHits,
    int accumulatorHits) {

  public double coldPercent() {
    return totalReads > 0 ? coldReads * 100.0 / totalReads : 0;
  }

  public double warmPercent() {
    return totalReads > 0 ? warmReads * 100.0 / totalReads : 0;
  }

  public double cacheHitPercent() {
    return totalReads > 0 ? cacheHits * 100.0 / totalReads : 0;
  }

  public double cacheMissPercent() {
    return totalReads > 0 ? cacheMisses * 100.0 / totalReads : 0;
  }
}
