package org.hyperledger.besu.plugin.cache.web;

import org.hyperledger.besu.plugin.cache.analyzer.AccountStats;
import org.hyperledger.besu.plugin.cache.analyzer.BlockAnalysisResult;
import org.hyperledger.besu.plugin.cache.analyzer.BlockMetadata;
import org.hyperledger.besu.plugin.cache.analyzer.SloadRecord;
import org.hyperledger.besu.plugin.cache.naming.ContractNameResolver;
import org.hyperledger.besu.plugin.cache.rocksdb.RocksDBStatsProvider;
import org.hyperledger.besu.plugin.cache.store.BlockResultStore;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Embedded HTTP server providing the web UI and JSON API. */
public class WebUiServer {

  private static final Logger LOG = LoggerFactory.getLogger(WebUiServer.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final BlockResultStore store;
  private final ContractNameResolver nameResolver;
  private final RocksDBStatsProvider statsProvider;
  private final int port;

  private Vertx vertx;
  private HttpServer server;

  public WebUiServer(
      final BlockResultStore store,
      final ContractNameResolver nameResolver,
      final RocksDBStatsProvider statsProvider,
      final int port) {
    this.store = store;
    this.nameResolver = nameResolver;
    this.statsProvider = statsProvider;
    this.port = port;
  }

  public void start() {
    vertx = Vertx.vertx();
    Router router = Router.router(vertx);

    router.get("/").handler(this::serveIndex);
    router.get("/api/block/:blockNumber").handler(this::apiBlockAnalysis);
    router.get("/api/block/:blockNumber/sloads").handler(this::apiBlockSloads);
    router.get("/api/recent").handler(this::apiRecentBlocks);
    router.get("/api/status").handler(this::apiStatus);

    try {
      server = vertx.createHttpServer()
          .requestHandler(router)
          .listen(port, "0.0.0.0")
          .toCompletionStage()
          .toCompletableFuture()
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
      LOG.info("Cache analysis UI available at http://0.0.0.0:{}", server.actualPort());
    } catch (Exception e) {
      LOG.error("Failed to start web UI server on port {}", port, e);
    }
  }

  public void stop() {
    if (server != null) server.close();
    if (vertx != null) vertx.close();
  }

  private void serveIndex(final RoutingContext ctx) {
    try (InputStream is = getClass().getResourceAsStream("/web/index.html")) {
      if (is == null) {
        ctx.response().setStatusCode(404).end("index.html not found");
        return;
      }
      String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      ctx.response()
          .putHeader("Content-Type", "text/html; charset=utf-8")
          .end(html);
    } catch (Exception e) {
      ctx.response().setStatusCode(500).end("Error: " + e.getMessage());
    }
  }

  private void apiBlockAnalysis(final RoutingContext ctx) {
    long blockNumber = parseBlockParam(ctx);
    Optional<BlockAnalysisResult> opt = blockNumber == -1
        ? store.getLatest()
        : store.getByBlockNumber(blockNumber);

    if (opt.isEmpty()) {
      jsonResponse(ctx, 404, Map.of("error", "Block not found"));
      return;
    }
    jsonResponse(ctx, 200, serializeBlock(opt.get()));
  }

  private void apiBlockSloads(final RoutingContext ctx) {
    long blockNumber = parseBlockParam(ctx);
    String filterAddress = ctx.queryParams().get("address");

    Optional<BlockAnalysisResult> opt = blockNumber == -1
        ? store.getLatest()
        : store.getByBlockNumber(blockNumber);

    if (opt.isEmpty()) {
      jsonResponse(ctx, 404, Map.of("error", "Block not found"));
      return;
    }

    BlockAnalysisResult result = opt.get();
    List<Map<String, Object>> sloads = new ArrayList<>();
    for (SloadRecord r : result.sloads()) {
      String addr = r.contractAddress().toHexString().toLowerCase();
      if (filterAddress != null && !addr.equalsIgnoreCase(filterAddress)) continue;
      sloads.add(serializeSload(r));
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("blockNumber", result.blockNumber());
    response.put("count", sloads.size());
    response.put("sloads", sloads);
    jsonResponse(ctx, 200, response);
  }

  private void apiRecentBlocks(final RoutingContext ctx) {
    String countParam = ctx.queryParams().get("count");
    int count = countParam != null ? Math.min(Integer.parseInt(countParam), 200) : 20;

    List<Map<String, Object>> blocks = new ArrayList<>();
    for (BlockAnalysisResult r : store.getRecent(count)) {
      blocks.add(serializeBlockSummary(r));
    }
    jsonResponse(ctx, 200, blocks);
  }

  private void apiStatus(final RoutingContext ctx) {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("blocksStored", store.size());
    status.put("contractNamesCached", nameResolver.cacheSize());
    status.put("pendingNameResolutions", nameResolver.pendingSize());
    status.put("etherscanActive", nameResolver.isActive());
    status.put("rocksdbStatsAvailable", statsProvider.isAvailable());
    store.getLatest().ifPresent(latest -> {
      status.put("latestBlock", latest.blockNumber());
      status.put("latestTotalSloads", latest.totalSloads());
    });
    jsonResponse(ctx, 200, status);
  }

  private Map<String, Object> serializeBlockSummary(final BlockAnalysisResult r) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("blockNumber", r.blockNumber());
    entry.put("txCount", r.transactionCount());
    entry.put("totalSloads", r.totalSloads());
    entry.put("totalSstores", r.totalSstores());
    entry.put("accumulator", r.accumulator());
    entry.put("memtable", r.memtable());
    entry.put("blockCache", r.blockCache());
    entry.put("disk", r.disk());
    entry.put("notFound", r.notFound());
    entry.put("coldSloads", r.coldSloads());
    entry.put("warmSloads", r.warmSloads());
    entry.put("contracts", r.accountStats().size());
    entry.put("totalSloadTimeUs", r.totalSloadTimeUs());
    entry.put("maxSloadLatencyUs", r.maxSloadLatencyUs());
    addMetadata(entry, r.metadata());
    if (r.rocksdbStatsAvailable()) {
      entry.put("blockDataCacheHit", r.blockDataCacheHit());
      entry.put("blockDataCacheMiss", r.blockDataCacheMiss());
      entry.put("blockMemtableHit", r.blockMemtableHit());
    }
    entry.put("rocksdbStats", r.rocksdbStatsAvailable());
    return entry;
  }

  private Map<String, Object> serializeBlock(final BlockAnalysisResult r) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("blockNumber", r.blockNumber());
    response.put("blockHash", r.blockHash());
    response.put("timestamp", r.timestamp());
    response.put("transactionCount", r.transactionCount());
    response.put("totalSloads", r.totalSloads());
    response.put("totalSstores", r.totalSstores());
    response.put("accumulator", r.accumulator());
    response.put("memtable", r.memtable());
    response.put("blockCache", r.blockCache());
    response.put("disk", r.disk());
    response.put("notFound", r.notFound());
    response.put("coldSloads", r.coldSloads());
    response.put("warmSloads", r.warmSloads());
    response.put("coldPercent", Math.round(r.coldPercent() * 10.0) / 10.0);
    addMetadata(response, r.metadata());
    if (r.rocksdbStatsAvailable()) {
      response.put("blockDataCacheHit", r.blockDataCacheHit());
      response.put("blockDataCacheMiss", r.blockDataCacheMiss());
      response.put("blockMemtableHit", r.blockMemtableHit());
    }
    response.put("rocksdbStats", r.rocksdbStatsAvailable());
    response.put("totalSloadTimeUs", r.totalSloadTimeUs());
    response.put("maxSloadLatencyUs", r.maxSloadLatencyUs());
    response.put("avgAccumUs", r.avgAccumUs());
    response.put("avgMemtableUs", r.avgMemtableUs());
    response.put("avgBlockCacheUs", r.avgBlockCacheUs());
    response.put("avgDiskUs", r.avgDiskUs());
    response.put("uniqueSlots", r.uniqueSlots());
    Map<String, Object> slowest = serializeSload(r.slowestSload());
    if (slowest != null) {
      response.put("slowestSload", slowest);
    }

    List<Map<String, Object>> accounts = new ArrayList<>();
    for (AccountStats a : r.accountStats()) {
      Map<String, Object> acc = new LinkedHashMap<>();
      acc.put("address", a.address());
      acc.put("contractName", a.contractName());
      acc.put("total", a.totalReads());
      acc.put("accumulator", a.accumulator());
      acc.put("memtable", a.memtable());
      acc.put("blockCache", a.blockCache());
      acc.put("disk", a.disk());
      acc.put("notFound", a.notFound());
      acc.put("accumulatorPercent", Math.round(a.accumulatorPercent() * 10.0) / 10.0);
      acc.put("memtablePercent", Math.round(a.memtablePercent() * 10.0) / 10.0);
      acc.put("blockCachePercent", Math.round(a.blockCachePercent() * 10.0) / 10.0);
      acc.put("diskPercent", Math.round(a.diskPercent() * 10.0) / 10.0);
      acc.put("notFoundPercent", Math.round(a.notFoundPercent() * 10.0) / 10.0);
      acc.put("cold", a.coldReads());
      acc.put("warm", a.warmReads());
      acc.put("coldPercent", Math.round(a.coldPercent() * 10.0) / 10.0);
      acc.put("warmPercent", Math.round(a.warmPercent() * 10.0) / 10.0);
      acc.put("totalTimeUs", a.totalTimeUs());
      acc.put("maxTimeUs", a.maxTimeUs());
      acc.put("avgTimeUs", a.avgTimeUs());
      accounts.add(acc);
    }
    response.put("accounts", accounts);
    return response;
  }

  private static void addMetadata(final Map<String, Object> map, final BlockMetadata m) {
    if (m == null) return;
    map.put("evmExecutionMs", m.evmExecutionMs());
    map.put("stateRootMs", m.stateRootMs());
    map.put("totalBlockMs", m.totalBlockMs());
    map.put("gasUsed", m.gasUsed());
    map.put("gasLimit", m.gasLimit());
    map.put("gasUsedPercent", Math.round(m.gasUsedPercent() * 10.0) / 10.0);
    map.put("baseFeeGwei", Math.round(m.baseFeeGwei() * 1000.0) / 1000.0);
    map.put("blobGasUsed", m.blobGasUsed());
    map.put("blobTxCount", m.blobTxCount());
  }

  private Map<String, Object> serializeSload(final SloadRecord r) {
    if (r == null) {
      return null;
    }
    String addr = r.contractAddress().toHexString().toLowerCase();
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("address", addr);
    entry.put("contractName", nameResolver.getName(addr));
    entry.put("slot", r.slotKey().toHexString());
    entry.put("cold", r.isCold());
    entry.put("storageType", r.storageType());
    entry.put("notFound", r.notFound());
    entry.put("txIndex", r.transactionIndex());
    entry.put("latencyUs", r.latencyUs());
    entry.put("dMemHit", r.dMemHit());
    entry.put("dMemMiss", r.dMemMiss());
    entry.put("dCacheHit", r.dCacheHit());
    entry.put("dCacheMiss", r.dCacheMiss());
    return entry;
  }

  private long parseBlockParam(final RoutingContext ctx) {
    String param = ctx.pathParam("blockNumber");
    if (param == null || "latest".equalsIgnoreCase(param)) return -1;
    if (param.startsWith("0x")) return Long.parseLong(param.substring(2), 16);
    return Long.parseLong(param);
  }

  private void jsonResponse(final RoutingContext ctx, final int status, final Object body) {
    try {
      ctx.response()
          .setStatusCode(status)
          .putHeader("Content-Type", "application/json")
          .putHeader("Access-Control-Allow-Origin", "*")
          .end(MAPPER.writeValueAsString(body));
    } catch (Exception e) {
      ctx.response().setStatusCode(500).end("{\"error\":\"" + e.getMessage() + "\"}");
    }
  }
}
