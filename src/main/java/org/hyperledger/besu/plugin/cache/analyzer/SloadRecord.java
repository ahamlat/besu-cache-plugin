package org.hyperledger.besu.plugin.cache.analyzer;

import org.hyperledger.besu.datatypes.Address;

import org.apache.tuweni.units.bigints.UInt256;

/** A single SLOAD observation captured during block execution. */
public record SloadRecord(
    Address contractAddress,
    UInt256 slotKey,
    boolean isCold,
    int transactionIndex) {}
