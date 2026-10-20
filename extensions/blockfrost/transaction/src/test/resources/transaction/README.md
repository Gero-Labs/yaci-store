The input outpoints and flags are retained Nexus 200 response sequences from the
2026-10-12, 2026-10-15 and 2026-10-16 paired integration runs. These fixtures
exercise collateral inputs appearing before reference inputs. Their paired
Blockfrost responses place collateral last; the regression preserves the
existing relative regular/reference order and duplicate outpoints.

The canonical public Blockfrost RYO SQL at commit
e05d67339e6b0fd818c0efa0c70f96d3849b99cb orders inputs by
`collateral ASC, (tx_in_id, output_index) ASC` and outputs by
`(output_index, collateral) ASC`:

- https://github.com/blockfrost/blockfrost-backend-ryo/blob/e05d67339e6b0fd818c0efa0c70f96d3849b99cb/src/sql/txs/txs_hash_utxos_1.sql
- https://github.com/blockfrost/blockfrost-backend-ryo/blob/e05d67339e6b0fd818c0efa0c70f96d3849b99cb/src/sql/txs/txs_hash_utxos_2.sql

Only the source-backed collateral-last input invariant is enforced here.
The independent db-sync tx_in/reference_tx_in allocator IDs are unavailable
in Yaci, so this does not establish exact relative regular/reference parity.
The constructed interleaved regression makes preserving that order explicit;
it is not presented as a captured hosted response.
