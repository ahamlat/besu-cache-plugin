package org.hyperledger.besu.plugin.cache;

import org.hyperledger.besu.plugin.cache.naming.ContractNameResolver;
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
 * classifies them as warm/cold, resolves contract names, and
 * exposes results via JSON-RPC and an embedded web dashboard.
 *
 * <p>Configuration via environment variables:
 * <ul>
 *   <li>CACHE_PLUGIN_ETHERSCAN_KEY - Etherscan API key for contract name resolution</li>
 *   <li>CACHE_PLUGIN_WEB_PORT - Web UI port (default: 8547)</li>
 *   <li>CACHE_PLUGIN_MAX_BLOCKS - Max blocks to keep in memory (default: 1000)</li>
 * </ul>
 */
@AutoService(BesuPlugin.class)
public class CacheAnalysisPlugin implements BesuPlugin {

  private static final Logger LOG = LoggerFactory.getLogger(CacheAnalysisPlugin.class);

  private static final int DEFAULT_WEB_PORT = 8547;
  private static final int DEFAULT_MAX_BLOCKS = 1000;

  private ServiceManager serviceManager;
  private BlockResultStore store;
  private ContractNameResolver nameResolver;
  private WebUiServer webServer;

  @Override
  public void register(final ServiceManager serviceManager) {
    LOG.info("Registering Cache Analysis Plugin");
    this.serviceManager = serviceManager;

    int maxBlocks = getEnvInt("CACHE_PLUGIN_MAX_BLOCKS", DEFAULT_MAX_BLOCKS);
    this.store = new BlockResultStore(maxBlocks);
    this.nameResolver = new ContractNameResolver();

    // Register the block import tracer so AbstractBlockProcessor uses our SloadTracer
    SloadTracerProvider tracerProvider = new SloadTracerProvider(store, nameResolver);
    serviceManager.addService(BlockImportTracerProvider.class, tracerProvider);
    LOG.info("Registered BlockImportTracerProvider for SLOAD tracing");

    // Register JSON-RPC endpoints (must happen during register phase)
    serviceManager.getService(RpcEndpointService.class)
        .ifPresentOrElse(
            rpcService -> {
              CacheAnalysisRpcMethods rpcMethods = new CacheAnalysisRpcMethods(store, nameResolver);
              rpcMethods.register(rpcService);
            },
            () -> LOG.warn("RpcEndpointService not available, cache_* RPC methods not registered"));
  }

  @Override
  public void start() {
    LOG.info("Starting Cache Analysis Plugin");

    // Start contract name resolver with Etherscan API key
    String apiKey = System.getenv("CACHE_PLUGIN_ETHERSCAN_KEY");
    nameResolver.start(apiKey);

    // Start embedded web UI
    int webPort = getEnvInt("CACHE_PLUGIN_WEB_PORT", DEFAULT_WEB_PORT);
    webServer = new WebUiServer(store, nameResolver, webPort);
    webServer.start();

    LOG.info("Cache Analysis Plugin started (web UI port: {}, max blocks: {})",
        webPort, getEnvInt("CACHE_PLUGIN_MAX_BLOCKS", DEFAULT_MAX_BLOCKS));
  }

  @Override
  public void stop() {
    LOG.info("Stopping Cache Analysis Plugin");
    if (webServer != null) webServer.stop();
    if (nameResolver != null) nameResolver.stop();
  }

  private static int getEnvInt(final String name, final int defaultValue) {
    String val = System.getenv(name);
    if (val == null || val.isBlank()) return defaultValue;
    try {
      return Integer.parseInt(val);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
