package org.hyperledger.besu.plugin.cache.rpc;

import org.hyperledger.besu.plugin.cache.analyzer.AccountStats;
import org.hyperledger.besu.plugin.cache.analyzer.BlockAnalysisResult;
import org.hyperledger.besu.plugin.cache.analyzer.SloadRecord;
import org.hyperledger.besu.plugin.cache.naming.ContractNameResolver;
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

  public CacheAnalysisRpcMethods(
      final BlockResultStore store, final ContractNameResolver nameResolver) {
    this.store = store;
    this.nameResolver = nameResolver;
  }

  public void register(final RpcEndpointService rpcService) {
    rpcService.registerRPCEndpoint(NAMESPACE, "getBlockAnalysis", this::getBlockAnalysis);
    rpcService.registerRPCEndpoint(NAMESPACE, "getBlockSloads", this::getBlockSloads);
    rpcService.registerRPCEndpoint(NAMESPACE, "getRecentBlocks", this::getRecentBlocks);
    rpcService.registerRPCEndpoint(NAMESPACE, "getContractName", this::getContractName);
    rpcService.registerRPCEndpoint(NAMESPACE, "getStatus", this::getStatus);
    LOG.info("Registered cache_* RPC endpoints");
  }

  /** cache_getBlockAnalysis(blockNumber) -> per-account breakdown */
  private Map<String, Object> getBlockAnalysis(final PluginRpcRequest request) {
    Object[] params = request.getParams();
    long blockNumber = parseBlockNumber(params);

    Optional<BlockAnalysisResult> opt = store.getByBlockNumber(blockNumber);
    if (opt.isEmpty()) {
      return Map.of("error", "Block " + blockNumber + " not found in store");
    }
    return serializeBlockAnalysis(opt.get());
  }

  /** cache_getBlockSloads(blockNumber, [filterAddress]) -> per-SLOAD detail */
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
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("address", addr);
      entry.put("contractName", nameResolver.getName(addr));
      entry.put("slot", r.slotKey().toHexString());
      entry.put("cold", r.isCold());
      entry.put("txIndex", r.transactionIndex());
      sloads.add(entry);
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("blockNumber", blockNumber);
    response.put("count", sloads.size());
    response.put("sloads", sloads);
    return response;
  }

  /** cache_getRecentBlocks(count) -> summary of last N blocks */
  private List<Map<String, Object>> getRecentBlocks(final PluginRpcRequest request) {
    Object[] params = request.getParams();
    int count = params.length > 0 ? Integer.parseInt(params[0].toString()) : 10;
    count = Math.min(count, 100);

    List<Map<String, Object>> blocks = new ArrayList<>();
    for (BlockAnalysisResult r : store.getRecent(count)) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("blockNumber", r.blockNumber());
      entry.put("totalSloads", r.totalSloads());
      entry.put("coldSloads", r.coldSloads());
      entry.put("warmSloads", r.warmSloads());
      entry.put("coldPercent", Math.round(r.coldPercent() * 10.0) / 10.0);
      entry.put("contracts", r.accountStats().size());
      entry.put("txCount", r.transactionCount());
      blocks.add(entry);
    }
    return blocks;
  }

  /** cache_getContractName(address) */
  private Map<String, Object> getContractName(final PluginRpcRequest request) {
    Object[] params = request.getParams();
    if (params.length == 0) return Map.of("error", "address required");
    String addr = params[0].toString().toLowerCase();
    return Map.of("address", addr, "name", nameResolver.getName(addr));
  }

  /** cache_getStatus() -> plugin status */
  private Map<String, Object> getStatus(final PluginRpcRequest request) {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("blocksStored", store.size());
    status.put("contractNamesCached", nameResolver.cacheSize());
    status.put("pendingNameResolutions", nameResolver.pendingSize());
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
    response.put("coldSloads", r.coldSloads());
    response.put("warmSloads", r.warmSloads());
    response.put("coldPercent", Math.round(r.coldPercent() * 10.0) / 10.0);

    List<Map<String, Object>> accounts = new ArrayList<>();
    for (AccountStats a : r.accountStats()) {
      Map<String, Object> acc = new LinkedHashMap<>();
      acc.put("address", a.address());
      acc.put("contractName", a.contractName());
      acc.put("total", a.totalReads());
      acc.put("cold", a.coldReads());
      acc.put("warm", a.warmReads());
      acc.put("coldPercent", Math.round(a.coldPercent() * 10.0) / 10.0);
      acc.put("warmPercent", Math.round(a.warmPercent() * 10.0) / 10.0);
      accounts.add(acc);
    }
    response.put("accounts", accounts);
    return response;
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
