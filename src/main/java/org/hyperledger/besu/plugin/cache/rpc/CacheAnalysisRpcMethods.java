package org.hyperledger.besu.plugin.cache.rpc;

import org.hyperledger.besu.plugin.cache.analyzer.AccountStats;
import org.hyperledger.besu.plugin.cache.analyzer.BlockAnalysisResult;
import org.hyperledger.besu.plugin.cache.analyzer.BlockMetadata;
import org.hyperledger.besu.plugin.cache.analyzer.SloadRecord;
import org.hyperledger.besu.plugin.cache.naming.ContractNameResolver;
import org.hyperledger.besu.plugin.cache.rocksdb.RocksDBStatsProvider;
import org.hyperledger.besu.plugin.cache.store.BlockResultStore;
import org.hyperledger.besu.plugin.services.RpcEndpointService;
import org.hyperledger.besu.plugin.services.rpc.PluginRpcRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers JSON-RPC endpoints under the "cache" namespace. */
public class CacheAnalysisRpcMethods {

  private static final Logger LOG = LoggerFactory.getLogger(CacheAnalysisRpcMethods.class);
  private static final String NAMESPACE = "cache";

  private final BlockResultStore store;
  private final ContractNameResolver nameResolver;
  private final RocksDBStatsProvider statsProvider;

  public CacheAnalysisRpcMethods(
      final BlockResultStore store,
      final ContractNameResolver nameResolver,
      final RocksDBStatsProvider statsProvider) {
    this.store = store;
    this.nameResolver = nameResolver;
    this.statsProvider = statsProvider;
  }

  public void register(final RpcEndpointService rpcService) {
    rpcService.registerRPCEndpoint(NAMESPACE, "getBlockAnalysis", this::getBlockAnalysis);
    rpcService.registerRPCEndpoint(NAMESPACE, "getBlockSloads", this::getBlockSloads);
    rpcService.registerRPCEndpoint(NAMESPACE, "getRecentBlocks", this::getRecentBlocks);
    rpcService.registerRPCEndpoint(NAMESPACE, "getContractName", this::getContractName);
    rpcService.registerRPCEndpoint(NAMESPACE, "getStatus", this::getStatus);
    LOG.info("Registered cache_* RPC endpoints");
  }

  private Map<String, Object> getBlockAnalysis(final PluginRpcRequest request) {
    Object[] params = request.getParams();
    long blockNumber = parseBlockNumber(params);

    Optional<BlockAnalysisResult> opt = store.getByBlockNumber(blockNumber);
    if (opt.isEmpty()) {
      return Map.of("error", "Block " + blockNumber + " not found in store");
    }
    return serializeBlockAnalysis(opt.get());
  }

  private Map<String, Object> getBlockSloads(final PluginRpcRequest request) {
    Object[] params = request.getParams();
    long blockNumber = parseBlockNumber(params);
    String filterAddress = params.length > 1 && params[1] != null
        ? params[1].toString().toLowerCase() : null;

    Optional<BlockAnalysisResult> opt = store.getByBlockNumber(blockNumber);
    if (opt.isEmpty()) {
      return Map.of("error", "Block " + blockNumber + " not found in store");
    }

    BlockAnalysisResult result = opt.get();
    List<Map<String, Object>> sloads = new ArrayList<>();
    for (SloadRecord r : result.sloads()) {
      String addr = r.contractAddress().toHexString().toLowerCase();
      if (filterAddress != null && !addr.equals(filterAddress)) continue;
      sloads.add(serializeSload(r));
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("blockNumber", blockNumber);
    response.put("count", sloads.size());
    response.put("sloads", sloads);
    return response;
  }

  private List<Map<String, Object>> getRecentBlocks(final PluginRpcRequest request) {
    Object[] params = request.getParams();
    int count = params.length > 0 ? Integer.parseInt(params[0].toString()) : 10;
    count = Math.min(count, 100);

    List<Map<String, Object>> blocks = new ArrayList<>();
    for (BlockAnalysisResult r : store.getRecent(count)) {
      blocks.add(serializeBlockSummary(r));
    }
    return blocks;
  }

  private Map<String, Object> getContractName(final PluginRpcRequest request) {
    Object[] params = request.getParams();
    if (params.length == 0) return Map.of("error", "address required");
    String addr = params[0].toString().toLowerCase();
    return Map.of("address", addr, "name", nameResolver.getName(addr));
  }

  private Map<String, Object> getStatus(final PluginRpcRequest request) {
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
    return status;
  }

  private Map<String, Object> serializeBlockAnalysis(final BlockAnalysisResult r) {
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

  private long parseBlockNumber(final Object[] params) {
    if (params.length == 0) {
      return store.getLatest().map(BlockAnalysisResult::blockNumber).orElse(-1L);
    }
    String val = params[0].toString();
    if ("latest".equalsIgnoreCase(val)) {
      return store.getLatest().map(BlockAnalysisResult::blockNumber).orElse(-1L);
    }
    if (val.startsWith("0x")) {
      return Long.parseLong(val.substring(2), 16);
    }
    return Long.parseLong(val);
  }
}
