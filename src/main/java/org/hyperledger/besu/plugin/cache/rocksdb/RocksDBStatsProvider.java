package org.hyperledger.besu.plugin.cache.rocksdb;

import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.StorageService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accesses RocksDB Statistics via reflection to read block-cache ticker counters.
 * No compile-time dependency on internal Besu or RocksDB classes.
 *
 * <p>Provides both full {@link Snapshot} (for block-level aggregates) and lightweight
 * {@link MiniSnapshot} (for per-SLOAD layer classification with minimal overhead).
 */
public class RocksDBStatsProvider {

  private static final Logger LOG = LoggerFactory.getLogger(RocksDBStatsProvider.class);

  private Object statistics;
  private Method getTickerCountMethod;
  private Object tickerDataHit;
  private Object tickerDataMiss;
  private Object tickerMemtableHit;
  private Object tickerMemtableMiss;
  private Object tickerBloomUseful;
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
      statistics = statsField.get(segStorage);
      if (statistics == null) {
        LOG.warn("RocksDBStatsProvider: stats field is null");
        return false;
      }

      @SuppressWarnings("unchecked")
      Class<? extends Enum<?>> tickerTypeClass =
          (Class<? extends Enum<?>>) Class.forName("org.rocksdb.TickerType");
      getTickerCountMethod = statistics.getClass().getMethod("getTickerCount", tickerTypeClass);

      tickerDataHit = enumValueOf(tickerTypeClass, "BLOCK_CACHE_DATA_HIT");
      tickerDataMiss = enumValueOf(tickerTypeClass, "BLOCK_CACHE_DATA_MISS");
      tickerMemtableHit = enumValueOf(tickerTypeClass, "MEMTABLE_HIT");
      tickerMemtableMiss = enumValueOf(tickerTypeClass, "MEMTABLE_MISS");
      tickerBloomUseful = enumValueOf(tickerTypeClass, "BLOOM_FILTER_USEFUL");

      available = true;
      LOG.info("RocksDBStatsProvider initialized - real cache stats available");
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

  /** Full snapshot of all 5 tickers (used for block-level aggregates). */
  public Snapshot snapshot() {
    if (!available) return Snapshot.EMPTY;
    try {
      long dh = (long) getTickerCountMethod.invoke(statistics, tickerDataHit);
      long dm = (long) getTickerCountMethod.invoke(statistics, tickerDataMiss);
      long mh = (long) getTickerCountMethod.invoke(statistics, tickerMemtableHit);
      long mm = (long) getTickerCountMethod.invoke(statistics, tickerMemtableMiss);
      long bf = (long) getTickerCountMethod.invoke(statistics, tickerBloomUseful);
      return new Snapshot(dh, dm, mh, mm, bf);
    } catch (Exception e) {
      return Snapshot.EMPTY;
    }
  }

  /**
   * Lightweight snapshot of 4 tickers for per-SLOAD layer classification.
   *
   * <p>Tracks MEMTABLE_MISS in addition to the 3 hit/miss tickers because every
   * RocksDB get() increments either MEMTABLE_HIT or MEMTABLE_MISS. Without it,
   * bloom-filter-rejected lookups (where no data block is accessed) would be
   * falsely classified as ACCUMULATOR.
   */
  public MiniSnapshot miniSnapshot() {
    if (!available) return MiniSnapshot.EMPTY;
    try {
      long mh = (long) getTickerCountMethod.invoke(statistics, tickerMemtableHit);
      long mm = (long) getTickerCountMethod.invoke(statistics, tickerMemtableMiss);
      long dh = (long) getTickerCountMethod.invoke(statistics, tickerDataHit);
      long dm = (long) getTickerCountMethod.invoke(statistics, tickerDataMiss);
      return new MiniSnapshot(mh, mm, dh, dm);
    } catch (Exception e) {
      return MiniSnapshot.EMPTY;
    }
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

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object enumValueOf(final Class<?> enumClass, final String name) {
    return Enum.valueOf((Class<Enum>) enumClass, name);
  }
}
