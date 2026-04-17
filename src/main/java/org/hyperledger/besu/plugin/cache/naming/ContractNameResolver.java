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
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves contract names via Etherscan V2 API with in-memory caching.
 * Rate-limited to 5 calls/second (free tier). Resolution is async.
 *
 * <p>API key is read from (in order):
 * <ol>
 *   <li>Env var {@code CACHE_PLUGIN_ETHERSCAN_KEY}</li>
 *   <li>System property {@code cache.plugin.etherscan.key}</li>
 * </ol>
 */
public class ContractNameResolver {

  private static final Logger LOG = LoggerFactory.getLogger(ContractNameResolver.class);
  private static final String UNKNOWN = "";

  private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<String> pendingQueue = new ConcurrentLinkedQueue<>();
  private final ConcurrentHashMap<String, Boolean> pendingSet = new ConcurrentHashMap<>();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicInteger resolvedCount = new AtomicInteger(0);
  private final AtomicInteger failedCount = new AtomicInteger(0);

  private volatile String apiKey;
  private ScheduledExecutorService executor;

  public ContractNameResolver() {}

  /**
   * Resolves the API key from the given argument, then env var, then system property.
   */
  public void start(final String etherscanApiKey) {
    this.apiKey = resolveApiKey(etherscanApiKey);
    if (apiKey != null && !apiKey.isBlank()) {
      running.set(true);
      executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "contract-name-resolver");
        t.setDaemon(true);
        return t;
      });
      executor.scheduleWithFixedDelay(this::processOne, 1000, 210, TimeUnit.MILLISECONDS);
      LOG.info("ContractNameResolver started with Etherscan API key (key length: {})",
          apiKey.length());
    } else {
      LOG.warn("ContractNameResolver: no API key found. Set env CACHE_PLUGIN_ETHERSCAN_KEY "
          + "or system property cache.plugin.etherscan.key, or pass "
          + "-Dcache.plugin.etherscan.key=YOUR_KEY to the JVM.");
    }
  }

  private static String resolveApiKey(final String explicit) {
    if (explicit != null && !explicit.isBlank()) return explicit;
    String env = System.getenv("CACHE_PLUGIN_ETHERSCAN_KEY");
    if (env != null && !env.isBlank()) return env;
    String prop = System.getProperty("cache.plugin.etherscan.key");
    if (prop != null && !prop.isBlank()) return prop;
    return null;
  }

  public void stop() {
    running.set(false);
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  public void enqueue(final Address address) {
    String addr = address.toHexString().toLowerCase();
    if (apiKey == null || apiKey.isBlank()) return;
    if (cache.containsKey(addr)) return;
    if (pendingSet.putIfAbsent(addr, Boolean.TRUE) == null) {
      pendingQueue.offer(addr);
    }
  }

  public String getName(final String address) {
    return cache.getOrDefault(address.toLowerCase(), UNKNOWN);
  }

  public ConcurrentHashMap<String, String> getCache() {
    return cache;
  }

  public int cacheSize() {
    return cache.size();
  }

  public int pendingSize() {
    return pendingQueue.size();
  }

  public boolean isActive() {
    return running.get();
  }

  private void processOne() {
    if (!running.get()) return;
    String addr = pendingQueue.poll();
    if (addr == null || cache.containsKey(addr)) {
      if (addr != null) pendingSet.remove(addr);
      return;
    }

    try {
      String name = lookupContractName(addr);
      pendingSet.remove(addr);
      if (name != null && !name.isBlank()) {
        cache.put(addr, name);
        int resolved = resolvedCount.incrementAndGet();
        if (resolved <= 5 || resolved % 50 == 0) {
          LOG.info("Resolved contract {} -> {} (total resolved: {}, pending: {})",
              addr, name, resolved, pendingQueue.size());
        }
      } else {
        cache.put(addr, UNKNOWN);
      }
    } catch (Exception e) {
      pendingSet.remove(addr);
      int failed = failedCount.incrementAndGet();
      if (failed <= 5 || failed % 20 == 0) {
        LOG.warn("Failed to resolve contract {}: {} (total failures: {})",
            addr, e.getMessage(), failed);
      }
      pendingQueue.offer(addr);
      pendingSet.putIfAbsent(addr, Boolean.TRUE);
    }
  }

  private String lookupContractName(final String address) throws Exception {
    String url =
        "https://api.etherscan.io/v2/api?chainid=1&module=contract&action=getsourcecode&address="
            + address + "&apikey=" + apiKey;
    HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(10000);

    int code = conn.getResponseCode();
    if (code != 200) {
      throw new RuntimeException("HTTP " + code + " from Etherscan");
    }

    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    String json = sb.toString();

    if (json.contains("\"status\":\"0\"")) {
      if (json.contains("rate limit") || json.contains("Max rate")) {
        LOG.debug("Etherscan rate limit for {}, re-queuing", address);
        pendingQueue.offer(address);
        return null;
      }
      LOG.debug("Etherscan returned status 0 for {}: {}", address,
          json.length() > 200 ? json.substring(0, 200) : json);
      return null;
    }

    int idx = json.indexOf("\"ContractName\":\"");
    if (idx < 0) return null;
    int start = idx + "\"ContractName\":\"".length();
    int end = json.indexOf('"', start);
    if (end <= start) return null;
    return json.substring(start, end).trim();
  }
}
