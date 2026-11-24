# Musashi transaction-store replay

These are Yaci's unmodified Musashi w42 BlockFetch and LeiosFetch captures and the independent db-sync transaction order. Their source is [bloxbean/yaci at 613c6ec4](https://github.com/bloxbean/yaci/tree/613c6ec4a41762597b7d81713369285d35e8e08b/core/src/test/resources/leios/w42).

Run the regression on JDK 25 as a non-root user from the repository root.

```sh
./gradlew :stores:transaction:test --tests '*MusashiPostgresReplayTest' -PskipSigning=true
```

The test creates a disposable PostgreSQL 16.16 database on loopback. It writes the 354 ordinary transactions in block 9,091, then uses the header of 10,026 to verify the announced EB. That parent's own certificate refers to an older EB outside the capture. The test processes all 237 certified transactions in 10,027 through the Store's transaction and UTxO listeners, rolls back to 10,026 and processes 10,027 again. It checks the stored IDs and order against db-sync and compares the body, witness and auxiliary-data bytes to the original network messages.

Each certified transaction creates one output for the same enterprise address and spends one input. Of the 237 outputs, 187 are spent inside the block. The test checks that the 50 remaining outputs hold a combined 29,960,818,600,000 lovelace after both passes. This subtotal covers outputs created in the captured block. The test can run after the public testnet resets.
