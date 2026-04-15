package org.hyperledger.besu.plugin.cache.analyzer;

import org.hyperledger.besu.datatypes.Address;

import org.apache.tuweni.units.bigints.UInt256;

/**
 * A single SLOAD observation captured during block execution.
 *
 * @param storageType RocksDB-level classification: HIT, MISS, MEMTABLE, or ACCUMULATOR
 */
public record SloadRecord(
    Address contractAddress,
    UInt256 slotKey,
    boolean isCold,
    int transactionIndex,
    String storageType) {}
