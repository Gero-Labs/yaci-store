Captured mainnet transaction
`1d84b853fa642d53068a8b1ba9d1bb53bbc966bdd4c155fbcddafe3e2e91fe4e`
from the 2026-10-29 full Nexus/Blockfrost parity run. `provenance.json` records the
source response file hash, record positions and fixture hashes. Complete input
objects agree as a multiset and outputs agree; the ordinary-input order differs.

Blockfrost RYO's [pinned input query](https://github.com/blockfrost/blockfrost-backend-ryo/blob/a762667d9d1346075f533f7f753c13ceef6fe59b/src/sql/txs/txs_hash_utxos_1.sql)
orders regular input rows by their db-sync insertion IDs. db-sync's
[Conway conversion](https://github.com/IntersectMBO/cardano-db-sync/blob/dd177206b46de71e3111192a82fdf6144302d314/cardano-db-sync/src/Cardano/DbSync/Era/Shelley/Generic/Tx/Conway.hs)
enumerates regular inputs from `Map.elems` of a map keyed by ledger `TxIn`.
Its [grouped input insertion](https://github.com/IntersectMBO/cardano-db-sync/blob/dd177206b46de71e3111192a82fdf6144302d314/cardano-db-sync/src/Cardano/DbSync/Era/Universal/Insert/Grouped.hs)
preserves that order. Ledger `TxIn` compares the previous transaction hash, then
the numeric output index. The captured hosted ordinary inputs follow that order.

The regression loads the captured Nexus insertion order into H2 and compares the
complete rendered inputs and outputs with the saved Blockfrost response. A second
case inserts two outpoints with the same hash in reverse output-index order.
Reference and valid collateral behavior are checked by the complete fixture.
Invalid-contract control flow and flags are unchanged by the query ordering fix;
this fixture is a valid-contract transaction.
