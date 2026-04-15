package org.hyperledger.besu.plugin.cache;

import org.hyperledger.besu.plugin.cache.naming.ContractNameResolver;
import org.hyperledger.besu.plugin.cache.rocksdb.RocksDBStatsProvider;
import org.hyperledger.besu.plugin.cache.rpc.CacheAnalysisRpcMethods;
import org.hyperledger.besu.plugin.cache.store.BlockResultStore;
import org.hyperledger.besu.plugin.cache.tracer.SloadTracerProvider;
import org.hyperledger.besu.plugin.cache.web.WebUiServer;

import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.BlockImportTracerProvider;
import org.hyperledger.besu.plugin.services.RpcEndpointService;

import com.google.auto.service.AutoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Besu plugin that captures SLOAD operations during block import,
 * classifies them as warm/cold (EVM) and HIT/MISS/MEMTABLE/ACCUMULATOR (RocksDB),
 * resolves contract names, and exposes results via JSON-RPC and a web dashboard.
 *
 * <p>Configuration via environment variables or system properties:
 * <ul>
 *   <li>CACHE_PLUGIN_ETHERSCAN_KEY / cache.plugin.etherscan.key</li>
 *   <li>CACHE_PLUGIN_WEB_PORT / cache.plugin.web.port (default: 8548)</li>
 *   <li>CACHE_PLUGIN_MAX_BLOCKS / cache.plugin.max.blocks (default: 1000)</li>
 * </ul>
 */
@AutoService(BesuPlugin.class)
public class CacheAnalysisPlugin implements BesuPlugin {

  private static final Logger LOG = LoggerFactory.getLogger(CacheAnalysisPlugin.class);

  private static final int DEFAULT_WEB_PORT = 8548;
  private static final int DEFAULT_MAX_BLOCKS = 1000;

  private ServiceManager serviceManager;
  private BlockResultStore store;
  private ContractNameResolver nameResolver;
  private RocksDBStatsProvider statsProvider;
  private WebUiServer webServer;

  @Override
  public void register(final ServiceManager serviceManager) {
    LOG.info("Registering Cache Analysis Plugin");
    this.serviceManager = serviceManager;

    int maxBlocks = getConfigInt("CACHE_PLUGIN_MAX_BLOCKS", "cache.plugin.max.blocks",
        DEFAULT_MAX_BLOCKS);
    this.store = new BlockResultStore(maxBlocks);
    this.nameResolver = new ContractNameResolver();
    this.statsProvider = new RocksDBStatsProvider();

    SloadTracerProvider tracerProvider = new SloadTracerProvider(store, nameResolver, statsProvider);
    serviceManager.addService(BlockImportTracerProvider.class, tracerProvider);
    LOG.info("Registered BlockImportTracerProvider for SLOAD tracing");

    serviceManager.getService(RpcEndpointService.class)
        .ifPresentOrElse(
            rpcService -> {
              CacheAnalysisRpcMethods rpcMethods =
                  new CacheAnalysisRpcMethods(store, nameResolver, statsProvider);
              rpcMethods.register(rpcService);
            },
            () -> LOG.warn("RpcEndpointService not available, cache_* RPC methods not registered"));
  }

  @Override
  public void start() {
    LOG.info("Starting Cache Analysis Plugin");

    String apiKey = getConfigString("CACHE_PLUGIN_ETHERSCAN_KEY", "cache.plugin.etherscan.key");
    nameResolver.start(apiKey);

    boolean statsOk = statsProvider.init(serviceManager);
    if (!statsOk) {
      LOG.warn("RocksDB stats not available - will fall back to EVM warm/cold heuristic. "
          + "Stats may become available after the first block is imported.");
    }

    int webPort = getConfigInt("CACHE_PLUGIN_WEB_PORT", "cache.plugin.web.port", DEFAULT_WEB_PORT);
    webServer = new WebUiServer(store, nameResolver, statsProvider, webPort);
    webServer.start();

    LOG.info("Cache Analysis Plugin started (web UI port: {}, max blocks: {}, "
            + "rocksdb stats: {}, etherscan: {})",
        webPort,
        getConfigInt("CACHE_PLUGIN_MAX_BLOCKS", "cache.plugin.max.blocks", DEFAULT_MAX_BLOCKS),
        statsOk ? "active" : "unavailable",
        nameResolver.isActive() ? "active" : "inactive");
  }

  @Override
  public void stop() {
    LOG.info("Stopping Cache Analysis Plugin");
    if (webServer != null) webServer.stop();
    if (nameResolver != null) nameResolver.stop();
  }

  /** Lazy init for RocksDB stats (called from tracer on first block if not already initialized). */
  public void retryStatsInit() {
    if (!statsProvider.isAvailable()) {
      statsProvider.init(serviceManager);
    }
  }

  private static String getConfigString(final String envName, final String propName) {
    String val = System.getenv(envName);
    if (val != null && !val.isBlank()) return val;
    val = System.getProperty(propName);
    if (val != null && !val.isBlank()) return val;
    return null;
  }

  private static int getConfigInt(final String envName, final String propName,
      final int defaultValue) {
    String val = getConfigString(envName, propName);
    if (val == null) return defaultValue;
    try {
      return Integer.parseInt(val.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
