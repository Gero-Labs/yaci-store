The label-721 bytes are retained responses for mainnet transaction
`10d3d8ffd9faf14ebfa57e999a9c448972f21ccbae0bcf9f3cd0ab1240870a6e`.
Both responses contain 5,587 bytes and have the same metadata values. Their map
pair order differs. The Blockfrost bytes preserve the ordered metadata encoding
used by db-sync; the Nexus bytes show the existing canonicalized storage result.

Raw-byte SHA-256:
- Blockfrost: `ece9902a40b398c8c17f3a785b0987fefb6ee71a51c88e293513f78532ab398c`
- Nexus: `c76d7e8152445133f07c631b624d79b3541de0184bb4c39999306f8262694748`

The regression wraps the retained Blockfrost metadata in a valid Alonzo auxiliary
map. This is a constructed input fixture, not the original transaction's full
auxiliary-data bytes. The independently retained Nexus metadata field reproduces
the lossy input boundary.

Reference: cardano-db-sync `ca434ecdfbbf04ed94e424ddbdf0b32bcec403ec`,
`Cardano.DbSync.Util.Cbor.serialiseTxMetadataToCbor` and singleton insertion in
`Cardano.DbSync.Era.Universal.Insert.Tx`. Shelley metadata encoding uses definite
list/map lengths and preserves recursive pair order, rather than sorting keys.
