This offline Conway fixture spends the repository's PlutusV3 always-true script
(`GovernanceTxHelper.ALWAYS_TRUE_SCRIPT`, CBOR `46450101002499`) and mints one
token under native `all[]` supplied only as a reference input. Spend, reference
and collateral inputs are all provided, so the tests require no chain access.

The fixed cost models and protocol limits are test inputs, captured at protocol
major version 11. The expected real Scalus budget is spend:0, memory 500,
CPU 64100; native policies produce no execution budgets.
