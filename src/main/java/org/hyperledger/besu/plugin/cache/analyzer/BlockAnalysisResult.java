package org.hyperledger.besu.plugin.cache.analyzer;

import org.hyperledger.besu.plugin.cache.rocksdb.RocksDBStatsProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete SLOAD analysis for a single block.
 *
 * <p>Per-SLOAD classification (storageReads / notFound / cached) is based on
 * tracking unique slot accesses within the block:
 * <ul>
 *   <li>STORAGE_READ — first read of a slot in this block, non-zero value returned</li>
 *   <li>NOT_FOUND — first read of a slot in this block, value is zero</li>
 *   <li>CACHED — slot was already read earlier in this block (accumulator cache)</li>
 * </ul>
 *
 * <p>Block-level RocksDB stats (blockDataCacheHit, blockDataCacheMiss, blockMemtableHit)
 * are aggregate ticker deltas across the entire block execution.
 */
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
    int storageReads,
    int notFound,
    int cached,
    long blockDataCacheHit,
    long blockDataCacheMiss,
    long blockMemtableHit,
    boolean rocksdbStatsAvailable) {

  public double coldPercent() {
    return totalSloads > 0 ? coldSloads * 100.0 / totalSloads : 0;
  }

  /** Build from raw SLOAD records and block-level RocksDB deltas. */
  public static BlockAnalysisResult build(
      final long blockNumber,
      final String blockHash,
      final long timestamp,
      final int transactionCount,
      final List<SloadRecord> sloads,
      final java.util.function.Function<String, String> nameResolver,
      final boolean rocksdbStatsAvailable,
      final RocksDBStatsProvider.Snapshot blockDelta) {

    int cold = 0, warm = 0;
    int totalStorageRead = 0, totalNotFound = 0, totalCached = 0;
    // perAccount: [cold, warm, storageRead, notFound, cached]
    Map<String, int[]> perAccount = new LinkedHashMap<>();

    for (SloadRecord r : sloads) {
      String addr = r.contractAddress().toHexString().toLowerCase();
      int[] counts = perAccount.computeIfAbsent(addr, k -> new int[5]);
      if (r.isCold()) { cold++; counts[0]++; } else { warm++; counts[1]++; }

      switch (r.storageType()) {
        case "STORAGE_READ" -> { totalStorageRead++; counts[2]++; }
        case "NOT_FOUND" -> { totalNotFound++; counts[3]++; }
        default -> { totalCached++; counts[4]++; }
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
          c[2], c[3], c[4]));
    }
    stats.sort(Comparator.comparingInt(AccountStats::totalReads).reversed());

    return new BlockAnalysisResult(
        blockNumber, blockHash, timestamp, transactionCount,
        List.copyOf(sloads), sloads.size(), cold, warm,
        List.copyOf(stats),
        totalStorageRead, totalNotFound, totalCached,
        blockDelta.dataCacheHit(), blockDelta.dataCacheMiss(), blockDelta.memtableHit(),
        rocksdbStatsAvailable);
  }
}
