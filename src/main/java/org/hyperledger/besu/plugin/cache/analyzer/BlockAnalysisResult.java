package org.hyperledger.besu.plugin.cache.analyzer;

import org.hyperledger.besu.plugin.cache.rocksdb.RocksDBStatsProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    BlockMetadata metadata,
    long totalSloadTimeUs,
    long maxSloadLatencyUs,
    long avgAccumUs,
    long avgMemtableUs,
    long avgBlockCacheUs,
    long avgDiskUs,
    int uniqueSlots) {

  public double coldPercent() {
    return totalSloads > 0 ? coldSloads * 100.0 / totalSloads : 0;
  }

  /**
   * Return the slowest SLOAD captured for this block, or {@code null} when the block
   * does not contain any SLOAD operation.
   */
  public SloadRecord slowestSload() {
    SloadRecord slowest = null;
    for (SloadRecord sload : sloads) {
      if (slowest == null || sload.latencyUs() > slowest.latencyUs()) {
        slowest = sload;
      }
    }
    return slowest;
  }

  /** Return a copy with updated metadata (used for post-listener timing update). */
  public BlockAnalysisResult withMetadata(final BlockMetadata newMetadata) {
    return new BlockAnalysisResult(
        blockNumber, blockHash, timestamp, transactionCount,
        sloads, totalSloads, totalSstores, coldSloads, warmSloads, accountStats,
        accumulator, memtable, blockCache, disk, notFound,
        blockDataCacheHit, blockDataCacheMiss, blockMemtableHit,
        rocksdbStatsAvailable, newMetadata,
        totalSloadTimeUs, maxSloadLatencyUs,
        avgAccumUs, avgMemtableUs, avgBlockCacheUs, avgDiskUs, uniqueSlots);
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
    long totalTimeUs = 0, maxLatencyUs = 0;
    long accumTimeSum = 0, memTimeSum = 0, bCacheTimeSum = 0, diskTimeSum = 0;

    // perAccount: [cold, warm, accum, mem, bcache, disk, nf, totalTimeUs, maxTimeUs]
    Map<String, long[]> perAccount = new LinkedHashMap<>();
    Set<String> uniqueSlotKeys = new HashSet<>();

    for (SloadRecord r : sloads) {
      String addr = r.contractAddress().toHexString().toLowerCase();
      String slotId = addr + ":" + r.slotKey().toHexString();

      long[] counts = perAccount.computeIfAbsent(addr, k -> new long[9]);
      if (r.isCold()) { cold++; counts[0]++; } else { warm++; counts[1]++; }

      switch (r.storageType()) {
        case "ACCUMULATOR" -> { totalAccum++; counts[2]++; accumTimeSum += r.latencyUs(); }
        case "MEMTABLE"    -> {
          totalMem++;
          counts[3]++;
          memTimeSum += r.latencyUs();
          uniqueSlotKeys.add(slotId);
        }
        case "BLOCK_CACHE" -> {
          totalBCache++;
          counts[4]++;
          bCacheTimeSum += r.latencyUs();
          uniqueSlotKeys.add(slotId);
        }
        case "DISK"        -> {
          totalDisk++;
          counts[5]++;
          diskTimeSum += r.latencyUs();
          uniqueSlotKeys.add(slotId);
        }
        default            -> { totalAccum++; counts[2]++; accumTimeSum += r.latencyUs(); }
      }

      if (r.notFound()) { totalNf++; counts[6]++; }

      totalTimeUs += r.latencyUs();
      if (r.latencyUs() > maxLatencyUs) maxLatencyUs = r.latencyUs();

      counts[7] += r.latencyUs();
      if (r.latencyUs() > counts[8]) counts[8] = r.latencyUs();
    }

    long avgAccum = totalAccum > 0 ? accumTimeSum / totalAccum : 0;
    long avgMem = totalMem > 0 ? memTimeSum / totalMem : 0;
    long avgBCache = totalBCache > 0 ? bCacheTimeSum / totalBCache : 0;
    long avgDisk = totalDisk > 0 ? diskTimeSum / totalDisk : 0;

    List<AccountStats> stats = new ArrayList<>();
    for (var entry : perAccount.entrySet()) {
      long[] c = entry.getValue();
      String name = nameResolver.apply(entry.getKey());
      stats.add(new AccountStats(
          entry.getKey(),
          name != null ? name : "",
          (int)(c[0] + c[1]), (int) c[0], (int) c[1],
          (int) c[2], (int) c[3], (int) c[4], (int) c[5], (int) c[6],
          c[7], c[8]));
    }
    stats.sort(Comparator.comparingInt(AccountStats::totalReads).reversed());

    return new BlockAnalysisResult(
        blockNumber, blockHash, timestamp, transactionCount,
        List.copyOf(sloads), sloads.size(), totalSstores, cold, warm,
        List.copyOf(stats),
        totalAccum, totalMem, totalBCache, totalDisk, totalNf,
        blockDelta.dataCacheHit(), blockDelta.dataCacheMiss(), blockDelta.memtableHit(),
        rocksdbStatsAvailable,
        metadata,
        totalTimeUs, maxLatencyUs,
        avgAccum, avgMem, avgBCache, avgDisk,
        uniqueSlotKeys.size());
  }
}
