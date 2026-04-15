package org.hyperledger.besu.plugin.cache.store;

import org.hyperledger.besu.plugin.cache.analyzer.BlockAnalysisResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Bounded in-memory store of per-block SLOAD analysis results (ring buffer). */
public class BlockResultStore {

  private final int maxBlocks;
  private final ConcurrentLinkedDeque<BlockAnalysisResult> recentBlocks = new ConcurrentLinkedDeque<>();
  private final ConcurrentHashMap<Long, BlockAnalysisResult> byBlockNumber = new ConcurrentHashMap<>();

  public BlockResultStore(final int maxBlocks) {
    this.maxBlocks = maxBlocks;
  }

  public void store(final BlockAnalysisResult result) {
    recentBlocks.addFirst(result);
    byBlockNumber.put(result.blockNumber(), result);

    while (recentBlocks.size() > maxBlocks) {
      BlockAnalysisResult evicted = recentBlocks.pollLast();
      if (evicted != null) {
        byBlockNumber.remove(evicted.blockNumber());
      }
    }
  }

  public Optional<BlockAnalysisResult> getByBlockNumber(final long blockNumber) {
    return Optional.ofNullable(byBlockNumber.get(blockNumber));
  }

  public List<BlockAnalysisResult> getRecent(final int count) {
    List<BlockAnalysisResult> result = new ArrayList<>();
    int i = 0;
    for (BlockAnalysisResult r : recentBlocks) {
      if (i++ >= count) break;
      result.add(r);
    }
    return result;
  }

  public Optional<BlockAnalysisResult> getLatest() {
    return Optional.ofNullable(recentBlocks.peekFirst());
  }

  public int size() {
    return recentBlocks.size();
  }
}
