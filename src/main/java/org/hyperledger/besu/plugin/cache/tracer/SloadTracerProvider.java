package org.hyperledger.besu.plugin.cache.tracer;

import org.hyperledger.besu.plugin.cache.naming.ContractNameResolver;
import org.hyperledger.besu.plugin.cache.rocksdb.RocksDBStatsProvider;
import org.hyperledger.besu.plugin.cache.store.BlockResultStore;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.services.BlockImportTracerProvider;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

/**
 * Provides a fresh SloadTracer for each block import.
 * Registered as a BesuService so AbstractBlockProcessor picks it up automatically.
 */
public class SloadTracerProvider implements BlockImportTracerProvider {

  private final BlockResultStore store;
  private final ContractNameResolver nameResolver;
  private final RocksDBStatsProvider statsProvider;

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
    return new SloadTracer(store, nameResolver, statsProvider);
  }
}
