package org.hyperledger.besu.plugin.cache.analyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete SLOAD analysis for a single block. */
public record BlockAnalysisResult(
    long blockNumber,
    String blockHash,
    long timestamp,
    int transactionCount,
    List<SloadRecord> sloads,
    int totalSloads,
    int coldSloads,
    int warmSloads,
    List<AccountStats> accountStats) {

  public double coldPercent() {
    return totalSloads > 0 ? coldSloads * 100.0 / totalSloads : 0;
  }

  /** Build aggregated account stats from raw SLOAD records. */
  public static BlockAnalysisResult build(
      final long blockNumber,
      final String blockHash,
      final long timestamp,
      final int transactionCount,
      final List<SloadRecord> sloads,
      final java.util.function.Function<String, String> nameResolver) {

    int cold = 0;
    int warm = 0;
    Map<String, int[]> perAccount = new LinkedHashMap<>();

    for (SloadRecord r : sloads) {
      String addr = r.contractAddress().toHexString().toLowerCase();
      int[] counts = perAccount.computeIfAbsent(addr, k -> new int[2]);
      if (r.isCold()) {
        cold++;
        counts[0]++;
      } else {
        warm++;
        counts[1]++;
      }
    }

    List<AccountStats> stats = new ArrayList<>();
    for (var entry : perAccount.entrySet()) {
      int[] c = entry.getValue();
      String name = nameResolver.apply(entry.getKey());
      stats.add(new AccountStats(
          entry.getKey(),
          name != null ? name : "",
          c[0] + c[1], c[0], c[1]));
    }
    stats.sort(Comparator.comparingInt(AccountStats::totalReads).reversed());

    return new BlockAnalysisResult(
        blockNumber, blockHash, timestamp, transactionCount,
        List.copyOf(sloads), sloads.size(), cold, warm,
        List.copyOf(stats));
  }
}
