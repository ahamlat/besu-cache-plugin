package org.hyperledger.besu.plugin.cache.tracer;

import org.hyperledger.besu.plugin.cache.naming.ContractNameResolver;
import org.hyperledger.besu.plugin.cache.rocksdb.RocksDBStatsProvider;
import org.hyperledger.besu.plugin.cache.store.BlockResultStore;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.services.BlockImportTracerProvider;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides a fresh SloadTracer for each block import.
 * Owns the shared pending-timings map used to correlate traceEndBlock
 * with the BlockAddedListener for state-root timing.
 */
public class SloadTracerProvider implements BlockImportTracerProvider {

  private final BlockResultStore store;
  private final ContractNameResolver nameResolver;
  private final RocksDBStatsProvider statsProvider;
  private final ConcurrentHashMap<Long, long[]> pendingTimings = new ConcurrentHashMap<>();

  public SloadTracerProvider(
      final BlockResultStore store,
      final ContractNameResolver nameResolver,
      final RocksDBStatsProvider statsProvider) {
    this.store = store;
    this.nameResolver = nameResolver;
    this.statsProvider = statsProvider;
  }

  @Override
  public BlockAwareOperationTracer getBlockImportTracer(final BlockHeader blockHeader) {
    return new SloadTracer(store, nameResolver, statsProvider, pendingTimings);
  }

  /**
   * Remove and return the [blockStartNanos, blockEndNanos] for the given block,
   * or null if not found.
   */
  public long[] consumePendingTiming(final long blockNumber) {
    return pendingTimings.remove(blockNumber);
  }
}
