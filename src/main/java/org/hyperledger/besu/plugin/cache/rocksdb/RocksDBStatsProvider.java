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
 * <p>Reflection path:
 * ServiceManager → StorageService → getByName("rocksdb") →
 * RocksDBKeyValueStorageFactory.segmentedStorage →
 * RocksDBColumnarKeyValueStorage.stats → org.rocksdb.Statistics
 */
public class RocksDBStatsProvider {

  private static final Logger LOG = LoggerFactory.getLogger(RocksDBStatsProvider.class);

  private Object statistics;
  private Method getTickerCountMethod;
  private Object tickerDataHit;
  private Object tickerDataMiss;
  private Object tickerMemtableHit;
  private Object tickerMemtableMiss;
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

  public Snapshot snapshot() {
    if (!available) return Snapshot.EMPTY;
    try {
      long dh = (long) getTickerCountMethod.invoke(statistics, tickerDataHit);
      long dm = (long) getTickerCountMethod.invoke(statistics, tickerDataMiss);
      long mh = (long) getTickerCountMethod.invoke(statistics, tickerMemtableHit);
      long mm = (long) getTickerCountMethod.invoke(statistics, tickerMemtableMiss);
      return new Snapshot(dh, dm, mh, mm);
    } catch (Exception e) {
      return Snapshot.EMPTY;
    }
  }

  /** Immutable snapshot of ticker counters at a point in time. */
  public record Snapshot(long dataCacheHit, long dataCacheMiss,
                         long memtableHit, long memtableMiss) {
    public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0);

    public Snapshot delta(final Snapshot before) {
      return new Snapshot(
          dataCacheHit - before.dataCacheHit,
          dataCacheMiss - before.dataCacheMiss,
          memtableHit - before.memtableHit,
          memtableMiss - before.memtableMiss);
    }

    /**
     * Classify an SLOAD based on delta counters.
     * <ul>
     *   <li>MEMTABLE: memtable was hit, no data block reads</li>
     *   <li>HIT: at least one data block cache hit, no data block miss</li>
     *   <li>MISS: at least one data block cache miss</li>
     *   <li>ACCUMULATOR: no RocksDB reads at all (value from Bonsai accumulator)</li>
     * </ul>
     */
    public String classify() {
      if (memtableHit > 0 && dataCacheHit == 0 && dataCacheMiss == 0) return "MEMTABLE";
      if (dataCacheHit > 0 && dataCacheMiss == 0) return "HIT";
      if (dataCacheMiss > 0) return "MISS";
      return "ACCUMULATOR";
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
