# Saved preview transaction control

`preview.json` contains the unmodified Blockfrost transaction rows from preview
`live-populated-GET004-001`, retained by the 2026-10-26 diagnosis. Each direction concatenates
the two populated pages (100 and 8 rows). The original `account-responses.json`
SHA-256 is `d679f1e549dbc18cfd216ff8e3b4adf71bfa6186a0d1d94d5d41c9ce856e6717`.

The DAO test compares all exposed fields and the exact page bodies. Its database
reconstruction uses one receive-only output per saved row because the HTTP
response does not expose input references or output positions. Slot, output
index, and amounts in that reconstruction are synthetic. It proves transaction
ordering for the saved control, rather than reconstruction of the chain database.

Separate populated synthetic DAO fixtures prove receive/spend membership,
earliest-output address ordering, duplicate-row removal, populated page
boundaries, inclusive `block:index` filters, and unchanged amount aggregation.
The saved mainnet response cannot independently reveal earliest output positions
and is not reconstructed here.

H2 2.2.224 runs these DAO tests with `STATIC_STATEMENT`: its parameterized repeated
CTE returns empty results while the identical inlined SQL returns populated rows.
This setting exists only in the test DSLContext. Prepared PostgreSQL execution
and query cost require separate validation.
