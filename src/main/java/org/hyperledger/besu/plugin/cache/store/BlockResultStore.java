package org.hyperledger.besu.plugin.cache.store;

import org.hyperledger.besu.plugin.cache.analyzer.BlockAnalysisResult;
import org.hyperledger.besu.plugin.cache.analyzer.BlockMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Bounded in-memory store of per-block SLOAD analysis results (ring buffer). */
public class BlockResultStore {

  private final int maxBlocks;
  private final ConcurrentLinkedDeque<Long> recentBlockNumbers = new ConcurrentLinkedDeque<>();
  private final ConcurrentHashMap<Long, BlockAnalysisResult> byBlockNumber = new ConcurrentHashMap<>();

  public BlockResultStore(final int maxBlocks) {
    this.maxBlocks = maxBlocks;
  }

  public void store(final BlockAnalysisResult result) {
    recentBlockNumbers.addFirst(result.blockNumber());
    byBlockNumber.put(result.blockNumber(), result);

    while (recentBlockNumbers.size() > maxBlocks) {
      Long evicted = recentBlockNumbers.pollLast();
      if (evicted != null) {
        byBlockNumber.remove(evicted);
      }
    }
  }

  /**
   * Update the timing fields for a block after the BlockAddedListener fires.
   * Since byBlockNumber is the single source of truth, getRecent/getLatest
   * always return the updated data.
   */
  public void updateTimings(final long blockNumber, final long stateRootMs, final long totalBlockMs) {
    byBlockNumber.computeIfPresent(blockNumber, (k, existing) -> {
      BlockMetadata updated = existing.metadata().withTimings(stateRootMs, totalBlockMs);
      return existing.withMetadata(updated);
    });
  }

  public Optional<BlockAnalysisResult> getByBlockNumber(final long blockNumber) {
    return Optional.ofNullable(byBlockNumber.get(blockNumber));
  }

  public List<BlockAnalysisResult> getRecent(final int count) {
    List<BlockAnalysisResult> result = new ArrayList<>();
    int i = 0;
    for (Long blockNum : recentBlockNumbers) {
      if (i++ >= count) break;
      BlockAnalysisResult r = byBlockNumber.get(blockNum);
      if (r != null) result.add(r);
    }
    return result;
  }

  public Optional<BlockAnalysisResult> getLatest() {
    Long latest = recentBlockNumbers.peekFirst();
    if (latest == null) return Optional.empty();
    return Optional.ofNullable(byBlockNumber.get(latest));
  }

  public int size() {
    return recentBlockNumbers.size();
  }
}
