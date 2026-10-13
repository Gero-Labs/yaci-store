package com.bloxbean.cardano.yaci.store.utxo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UtxoStoreProperties {

    private boolean saveAddress;

    @Builder.Default
    private boolean addressCacheEnabled = false;

    @Builder.Default
    private int addressCacheSize = 50000;

    @Builder.Default
    private int addressCacheExpiryAfterAccess = 15;

    @Builder.Default
    private boolean pruningEnabled = false;

    @Builder.Default
    private int pruningInterval = 86400;

    @Builder.Default
    private int pruningSafeBlocks = 2160;

    @Builder.Default
    private boolean contentAwareRollback = false;

    /** Maintain the complete current UTxO table at commit time. Existing stores require a backfill. */
    @Builder.Default
    private boolean unspentTableEnabled = false;

    /** Read Blockfrost asset holders from the current table only after backfill and verification. */
    @Builder.Default
    private boolean unspentTableReadEnabled = false;
}
