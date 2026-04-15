package org.hyperledger.besu.plugin.cache.analyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete SLOAD analysis for a single block, including RocksDB cache statistics. */
public record BlockAnalysisResult(
    long blockNumber,
    String blockHash,
    long timestamp,
    int transactionCount,
    List<SloadRecord> sloads,
    int totalSloads,
    int coldSloads,
    int warmSloads,
    List<AccountStats> accountStats,
    int cacheHits,
    int cacheMisses,
    int memtableHits,
    int notFound,
    int accumulatorHits,
    boolean rocksdbStatsAvailable) {

  public double coldPercent() {
    return totalSloads > 0 ? coldSloads * 100.0 / totalSloads : 0;
  }

  public double cacheHitPercent() {
    return totalSloads > 0 ? cacheHits * 100.0 / totalSloads : 0;
  }

  /** Build aggregated account stats from raw SLOAD records. */
  public static BlockAnalysisResult build(
      final long blockNumber,
      final String blockHash,
      final long timestamp,
      final int transactionCount,
      final List<SloadRecord> sloads,
      final java.util.function.Function<String, String> nameResolver,
      final boolean rocksdbStatsAvailable) {

    int cold = 0, warm = 0;
    int totalHit = 0, totalMiss = 0, totalMem = 0, totalNf = 0, totalAcc = 0;
    // perAccount: [cold, warm, cacheHit, cacheMiss, memtable, notFound, accumulator]
    Map<String, int[]> perAccount = new LinkedHashMap<>();

    for (SloadRecord r : sloads) {
      String addr = r.contractAddress().toHexString().toLowerCase();
      int[] counts = perAccount.computeIfAbsent(addr, k -> new int[7]);
      if (r.isCold()) { cold++; counts[0]++; } else { warm++; counts[1]++; }

      switch (r.storageType()) {
        case "HIT" -> { totalHit++; counts[2]++; }
        case "MISS" -> { totalMiss++; counts[3]++; }
        case "MEMTABLE" -> { totalMem++; counts[4]++; }
        case "NOT_FOUND" -> { totalNf++; counts[5]++; }
        default -> { totalAcc++; counts[6]++; }
      }
    }

    List<AccountStats> stats = new ArrayList<>();
    for (var entry : perAccount.entrySet()) {
      int[] c = entry.getValue();
      String name = nameResolver.apply(entry.getKey());
      stats.add(new AccountStats(
          entry.getKey(),
          name != null ? name : "",
          c[0] + c[1], c[0], c[1],
          c[2], c[3], c[4], c[5], c[6]));
    }
    stats.sort(Comparator.comparingInt(AccountStats::totalReads).reversed());

    return new BlockAnalysisResult(
        blockNumber, blockHash, timestamp, transactionCount,
        List.copyOf(sloads), sloads.size(), cold, warm,
        List.copyOf(stats),
        totalHit, totalMiss, totalMem, totalNf, totalAcc,
        rocksdbStatsAvailable);
  }
}
