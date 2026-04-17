package org.hyperledger.besu.plugin.cache.analyzer;

import org.hyperledger.besu.datatypes.Address;

import org.apache.tuweni.units.bigints.UInt256;

/**
 * A single SLOAD observation captured during block execution.
 *
 * @param storageType per-SLOAD cache layer classification:
 *   ACCUMULATOR (served from in-memory accumulator, no RocksDB call),
 *   MEMTABLE (served from RocksDB write buffer),
 *   BLOCK_CACHE (served from RocksDB block cache),
 *   DISK (read from SST files on disk)
 * @param notFound true when the loaded value is zero (slot empty / not in state)
 * @param latencyUs wall-clock microseconds for this SLOAD (pre- to post-execution)
 * @param dMemHit raw MEMTABLE_HIT ticker delta during this SLOAD
 * @param dMemMiss raw MEMTABLE_MISS ticker delta during this SLOAD
 * @param dCacheHit raw BLOCK_CACHE_DATA_HIT ticker delta during this SLOAD
 * @param dCacheMiss raw BLOCK_CACHE_DATA_MISS ticker delta during this SLOAD
 */
public record SloadRecord(
    Address contractAddress,
    UInt256 slotKey,
    boolean isCold,
    int transactionIndex,
    String storageType,
    boolean notFound,
    long latencyUs,
    long dMemHit,
    long dMemMiss,
    long dCacheHit,
    long dCacheMiss) {}
