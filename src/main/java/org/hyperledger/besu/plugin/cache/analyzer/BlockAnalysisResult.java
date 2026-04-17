package org.hyperledger.besu.plugin.cache.analyzer;

import org.hyperledger.besu.plugin.cache.rocksdb.RocksDBStatsProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete SLOAD analysis for a single block, including block metadata
 * and RocksDB cache statistics.
 */
public record BlockAnalysisResult(
    long blockNumber,
    String blockHash,
    long timestamp,
    int transactionCount,
    List<SloadRecord> sloads,
    int totalSloads,
    int totalSstores,
    int coldSloads,
    int warmSloads,
    List<AccountStats> accountStats,
    int accumulator,
    int memtable,
    int blockCache,
    int disk,
    int notFound,
    long blockDataCacheHit,
    long blockDataCacheMiss,
    long blockMemtableHit,
    boolean rocksdbStatsAvailable,
    BlockMetadata metadata) {

  public double coldPercent() {
    return totalSloads > 0 ? coldSloads * 100.0 / totalSloads : 0;
  }

  /** Return a copy with updated metadata (used for post-listener timing update). */
  public BlockAnalysisResult withMetadata(final BlockMetadata newMetadata) {
    return new BlockAnalysisResult(
        blockNumber, blockHash, timestamp, transactionCount,
        sloads, totalSloads, totalSstores, coldSloads, warmSloads, accountStats,
        accumulator, memtable, blockCache, disk, notFound,
        blockDataCacheHit, blockDataCacheMiss, blockMemtableHit,
        rocksdbStatsAvailable, newMetadata);
  }

  public static BlockAnalysisResult build(
      final long blockNumber,
      final String blockHash,
      final long timestamp,
      final int transactionCount,
      final List<SloadRecord> sloads,
      final int totalSstores,
      final java.util.function.Function<String, String> nameResolver,
      final boolean rocksdbStatsAvailable,
      final RocksDBStatsProvider.Snapshot blockDelta,
      final BlockMetadata metadata) {

    int cold = 0, warm = 0;
    int totalAccum = 0, totalMem = 0, totalBCache = 0, totalDisk = 0, totalNf = 0;
    Map<String, int[]> perAccount = new LinkedHashMap<>();

    for (SloadRecord r : sloads) {
      String addr = r.contractAddress().toHexString().toLowerCase();
      int[] counts = perAccount.computeIfAbsent(addr, k -> new int[7]);
      if (r.isCold()) { cold++; counts[0]++; } else { warm++; counts[1]++; }

      switch (r.storageType()) {
        case "ACCUMULATOR" -> { totalAccum++; counts[2]++; }
        case "MEMTABLE"    -> { totalMem++;   counts[3]++; }
        case "BLOCK_CACHE" -> { totalBCache++; counts[4]++; }
        case "DISK"        -> { totalDisk++;  counts[5]++; }
        default            -> { totalAccum++; counts[2]++; }
      }

      if (r.notFound()) { totalNf++; counts[6]++; }
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
        List.copyOf(sloads), sloads.size(), totalSstores, cold, warm,
        List.copyOf(stats),
        totalAccum, totalMem, totalBCache, totalDisk, totalNf,
        blockDelta.dataCacheHit(), blockDelta.dataCacheMiss(), blockDelta.memtableHit(),
        rocksdbStatsAvailable,
        metadata);
  }
}
