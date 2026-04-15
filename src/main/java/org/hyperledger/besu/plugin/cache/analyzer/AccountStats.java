package org.hyperledger.besu.plugin.cache.analyzer;

/** Aggregated SLOAD statistics for a single contract within one block. */
public record AccountStats(
    String address,
    String contractName,
    int totalReads,
    int coldReads,
    int warmReads,
    int storageReads,
    int notFound,
    int cached) {

  public double coldPercent() {
    return totalReads > 0 ? coldReads * 100.0 / totalReads : 0;
  }

  public double warmPercent() {
    return totalReads > 0 ? warmReads * 100.0 / totalReads : 0;
  }

  public double storageReadPercent() {
    return totalReads > 0 ? storageReads * 100.0 / totalReads : 0;
  }

  public double notFoundPercent() {
    return totalReads > 0 ? notFound * 100.0 / totalReads : 0;
  }

  public double cachedPercent() {
    return totalReads > 0 ? cached * 100.0 / totalReads : 0;
  }
}
