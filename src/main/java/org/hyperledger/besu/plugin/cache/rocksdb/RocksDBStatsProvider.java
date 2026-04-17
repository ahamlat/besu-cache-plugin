package org.hyperledger.besu.plugin.cache.rocksdb;

import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.StorageService;

import java.lang.reflect.Field;

import org.rocksdb.Statistics;
import org.rocksdb.TickerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads RocksDB {@link Statistics} tickers that quantify where each get() was served:
 * memtable, block cache or SST disk read.
 *
 * <p>Reflection is used only at {@link #init} time to fish the private {@code Statistics}
 * instance out of Besu's {@code RocksDBColumnarKeyValueStorage}. From then on, every
 * per-SLOAD call goes through direct typed {@link Statistics#getTickerCount(TickerType)}
 * invocations - no {@link java.lang.reflect.Method#invoke}, no {@code LambdaForm}, no
 * {@code itable stub} on the hot path. JIT can inline these down to a JNI volatile read.
 *
 * <p>Two snapshots are exposed: {@link Snapshot} (5 tickers, for block-level aggregates)
 * and {@link MiniSnapshot} (4 tickers, bracketing individual SLOADs for layer classification).
 */
public class RocksDBStatsProvider {

  private static final Logger LOG = LoggerFactory.getLogger(RocksDBStatsProvider.class);

  // Cached on init so the hot path does a plain invokevirtual on a Statistics reference.
  private Statistics statistics;
  private boolean available;

  public boolean init(final ServiceManager serviceManager) {
    try {
      StorageService storageService =
          serviceManager.getService(StorageService.class).orElse(null);
      if (storageService == null) {
        LOG.warn("RocksDBStatsProvider: StorageService not available");
        return false;
      }

      Object factory = storageService.getByName("rocksdb").orElse(null);
      if (factory == null) {
        LOG.warn("RocksDBStatsProvider: rocksdb factory not found");
        return false;
      }

      Field segStorageField = factory.getClass().getDeclaredField("segmentedStorage");
      segStorageField.setAccessible(true);
      Object segStorage = segStorageField.get(factory);
      if (segStorage == null) {
        LOG.warn("RocksDBStatsProvider: segmentedStorage is null (DB not yet opened?)");
        return false;
      }

      Field statsField = findField(segStorage.getClass(), "stats");
      if (statsField == null) {
        LOG.warn("RocksDBStatsProvider: 'stats' field not found on {}", segStorage.getClass());
        return false;
      }
      statsField.setAccessible(true);
      Object statsObj = statsField.get(segStorage);
      if (statsObj == null) {
        LOG.warn("RocksDBStatsProvider: stats field is null");
        return false;
      }
      if (!(statsObj instanceof Statistics s)) {
        LOG.warn("RocksDBStatsProvider: stats field is {} (expected org.rocksdb.Statistics)",
            statsObj.getClass().getName());
        return false;
      }
      statistics = s;

      // Sanity check: one direct typed call through the JNI path.
      long probe = statistics.getTickerCount(TickerType.MEMTABLE_HIT);
      available = true;
      LOG.info("RocksDBStatsProvider initialized (direct typed API) - MEMTABLE_HIT={}", probe);
      return true;
    } catch (Exception e) {
      LOG.warn("RocksDBStatsProvider init failed: {} - {}", e.getClass().getSimpleName(),
          e.getMessage());
      return false;
    }
  }

  public boolean isAvailable() {
    return available;
  }

  /** Full 5-ticker snapshot (used for block-level aggregates). */
  public Snapshot snapshot() {
    if (!available) return Snapshot.EMPTY;
    // Direct typed invokevirtual on a cached reference -> JIT-inlinable.
    return new Snapshot(
        statistics.getTickerCount(TickerType.BLOCK_CACHE_DATA_HIT),
        statistics.getTickerCount(TickerType.BLOCK_CACHE_DATA_MISS),
        statistics.getTickerCount(TickerType.MEMTABLE_HIT),
        statistics.getTickerCount(TickerType.MEMTABLE_MISS),
        statistics.getTickerCount(TickerType.BLOOM_FILTER_USEFUL));
  }

  /**
   * 4-ticker snapshot bracketing a single SLOAD for layer classification.
   *
   * <p>This is the hot path: called twice per SLOAD (~8 JNI calls / SLOAD,
   * ~6k / block at typical load). Kept direct-typed so JIT can inline
   * all the way to the native {@code Statistics::getTickerCount} entry.
   */
  public MiniSnapshot miniSnapshot() {
    if (!available) return MiniSnapshot.EMPTY;
    return new MiniSnapshot(
        statistics.getTickerCount(TickerType.MEMTABLE_HIT),
        statistics.getTickerCount(TickerType.MEMTABLE_MISS),
        statistics.getTickerCount(TickerType.BLOCK_CACHE_DATA_HIT),
        statistics.getTickerCount(TickerType.BLOCK_CACHE_DATA_MISS));
  }

  /** Immutable snapshot of ticker counters at a point in time. */
  public record Snapshot(long dataCacheHit, long dataCacheMiss,
                         long memtableHit, long memtableMiss,
                         long bloomFilterUseful) {
    public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0);

    public Snapshot delta(final Snapshot before) {
      return new Snapshot(
          dataCacheHit - before.dataCacheHit,
          dataCacheMiss - before.dataCacheMiss,
          memtableHit - before.memtableHit,
          memtableMiss - before.memtableMiss,
          bloomFilterUseful - before.bloomFilterUseful);
    }
  }

  /**
   * Lightweight 4-ticker snapshot for bracketing individual SLOAD operations.
   *
   * <p>Every RocksDB get() increments either MEMTABLE_HIT or MEMTABLE_MISS.
   * When both deltas are 0, no RocksDB call was made (true accumulator hit).
   * When MEMTABLE_MISS increases but no data block is accessed, the bloom filter
   * rejected the key at every SST level (slot doesn't exist in DB).
   */
  public record MiniSnapshot(long memtableHit, long memtableMiss,
                              long blockCacheHit, long blockCacheMiss) {
    public static final MiniSnapshot EMPTY = new MiniSnapshot(0, 0, 0, 0);

    /**
     * Classify which storage layer served an SLOAD based on ticker deltas.
     * Cost is dominated by the slowest layer touched, so DISK wins over BLOCK_CACHE
     * when a multi-level lookup hits both cached and uncached data blocks.
     * <ul>
     *   <li>ACCUMULATOR: no memtable hit/miss -- no RocksDB get() called</li>
     *   <li>MEMTABLE: memtableHit increased -- found in RocksDB write buffer</li>
     *   <li>DISK: blockCacheMiss increased -- at least one data block read from SST file</li>
     *   <li>BLOCK_CACHE: blockCacheHit increased, no miss -- all data blocks from cache</li>
     * </ul>
     */
    public String classifyLayer(final MiniSnapshot before) {
      long dMemHit  = memtableHit - before.memtableHit;
      long dMemMiss = memtableMiss - before.memtableMiss;
      long dHit     = blockCacheHit - before.blockCacheHit;
      long dMiss    = blockCacheMiss - before.blockCacheMiss;

      if (dMemHit == 0 && dMemMiss == 0) return "ACCUMULATOR";
      if (dMemHit > 0) return "MEMTABLE";
      if (dMiss > 0) return "DISK";
      if (dHit > 0) return "BLOCK_CACHE";
      // memtableMiss > 0 but no data block access: bloom filter rejected at all levels.
      return "BLOCK_CACHE";
    }
  }

  private static Field findField(Class<?> clazz, final String name) {
    while (clazz != null) {
      try {
        return clazz.getDeclaredField(name);
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    return null;
  }
}
