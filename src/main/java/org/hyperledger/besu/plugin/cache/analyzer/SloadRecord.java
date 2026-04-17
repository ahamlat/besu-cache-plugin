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
 */
public record SloadRecord(
    Address contractAddress,
    UInt256 slotKey,
    boolean isCold,
    int transactionIndex,
    String storageType) {}
