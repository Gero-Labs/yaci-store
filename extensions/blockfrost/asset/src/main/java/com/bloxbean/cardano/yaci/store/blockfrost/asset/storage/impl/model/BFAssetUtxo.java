package com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl.model;

public record BFAssetUtxo(String address, String txHash, Integer outputIndex,
                          Long lovelaceAmount, String amountsJson, String blockHash,
                          Long blockHeight, Long blockTime, String dataHash,
                          String inlineDatum, String referenceScriptHash) {
}
