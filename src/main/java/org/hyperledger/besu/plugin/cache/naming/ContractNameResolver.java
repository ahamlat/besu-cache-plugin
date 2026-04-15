package org.hyperledger.besu.plugin.cache.naming;

import org.hyperledger.besu.datatypes.Address;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves contract names via Etherscan V2 API with in-memory caching.
 * Rate-limited to 5 calls/second (free tier). Resolution is async.
 */
public class ContractNameResolver {

  private static final Logger LOG = LoggerFactory.getLogger(ContractNameResolver.class);
  private static final String UNKNOWN = "";

  private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<String> pendingQueue = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean running = new AtomicBoolean(false);

  private volatile String apiKey;
  private ScheduledExecutorService executor;

  public ContractNameResolver() {}

  public void start(final String etherscanApiKey) {
    this.apiKey = etherscanApiKey;
    if (apiKey != null && !apiKey.isBlank()) {
      running.set(true);
      executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "contract-name-resolver");
        t.setDaemon(true);
        return t;
      });
      // Process queue at 5 calls/sec -> 1 every 210ms
      executor.scheduleWithFixedDelay(this::processOne, 1000, 210, TimeUnit.MILLISECONDS);
      LOG.info("ContractNameResolver started with Etherscan API key");
    } else {
      LOG.info("ContractNameResolver: no API key, contract names will not be resolved");
    }
  }

  public void stop() {
    running.set(false);
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  /** Queue an address for async name resolution. */
  public void enqueue(final Address address) {
    String addr = address.toHexString().toLowerCase();
    if (!cache.containsKey(addr) && apiKey != null && !apiKey.isBlank()) {
      pendingQueue.offer(addr);
    }
  }

  /** Get the cached name for an address, or empty string if unknown. */
  public String getName(final String address) {
    return cache.getOrDefault(address.toLowerCase(), UNKNOWN);
  }

  /** Get the full cache for serialization. */
  public ConcurrentHashMap<String, String> getCache() {
    return cache;
  }

  public int cacheSize() {
    return cache.size();
  }

  public int pendingSize() {
    return pendingQueue.size();
  }

  private void processOne() {
    if (!running.get()) return;
    String addr = pendingQueue.poll();
    if (addr == null || cache.containsKey(addr)) return;

    try {
      String name = lookupContractName(addr);
      if (name != null && !name.isBlank()) {
        cache.put(addr, name);
        LOG.debug("Resolved {} -> {}", addr, name);
      } else {
        cache.put(addr, UNKNOWN);
      }
    } catch (Exception e) {
      LOG.debug("Failed to resolve {}: {}", addr, e.getMessage());
    }
  }

  private String lookupContractName(final String address) {
    try {
      String url =
          "https://api.etherscan.io/v2/api?chainid=1&module=contract&action=getsourcecode&address="
              + address + "&apikey=" + apiKey;
      HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(10000);
      conn.setReadTimeout(10000);
      int code = conn.getResponseCode();
      if (code != 200) return null;

      StringBuilder sb = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
      }
      String json = sb.toString();

      if (json.contains("\"status\":\"0\"") && json.contains("rate limit")) {
        LOG.debug("Etherscan rate limit, re-queuing {}", address);
        pendingQueue.offer(address);
        return null;
      }

      int idx = json.indexOf("\"ContractName\":\"");
      if (idx < 0) return null;
      int start = idx + "\"ContractName\":\"".length();
      int end = json.indexOf('"', start);
      if (end <= start) return null;
      return json.substring(start, end).trim();
    } catch (Exception e) {
      return null;
    }
  }
}
