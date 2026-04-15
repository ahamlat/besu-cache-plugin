package org.hyperledger.besu.plugin.cache.analyzer;

import org.hyperledger.besu.datatypes.Address;

import org.apache.tuweni.units.bigints.UInt256;

/**
 * A single SLOAD observation captured during block execution.
 *
 * @param storageType per-SLOAD classification:
 *   CACHED (slot already read in this block),
 *   NOT_FOUND (first read, value is zero),
 *   STORAGE_READ (first read, value is non-zero)
 */
public record SloadRecord(
    Address contractAddress,
    UInt256 slotKey,
    boolean isCold,
    int transactionIndex,
    String storageType) {}
