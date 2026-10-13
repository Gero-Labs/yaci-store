package com.bloxbean.cardano.yaci.store.utxo.storage.impl;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps current outputs equal to address_utxo minus tx_input at each committed batch. */
@Service
@RequiredArgsConstructor
public class UnspentUtxoTableService {
    // Shared with the bounded admin backfill; held through the caller's source rollback transaction.
    private static final long MUTATION_LOCK = 0x594143495554584fL;
    private static final String COLUMNS = "tx_hash, output_index, slot, block_hash, epoch, lovelace_amount, amounts, "
            + "data_hash, inline_datum, owner_addr, owner_addr_full, owner_stake_addr, owner_payment_credential, "
            + "owner_stake_credential, script_ref, reference_script_hash, is_collateral_return, block, block_time, "
            + "update_datetime, tx_index";
    private final DSLContext dsl;

    /** Copy persisted output values only after all writers finished; exclude spends already in this batch. */
    @Transactional
    public int[] applyCommit(long fromSlot, long toSlot) {
        lock();
        // Source upserts and pointer-resolution retries may replace copied values on replay.
        // Rebuild only this batch interval in the same transaction; readers keep their committed snapshot.
        dsl.execute("delete from address_utxo_unspent where slot between ? and ?", fromSlot, toSlot);
        int copied = dsl.execute("insert into address_utxo_unspent (" + COLUMNS + ") select " + COLUMNS
                + " from address_utxo a where a.slot between ? and ? "
                + "and not exists(select 1 from tx_input i where i.tx_hash=a.tx_hash and i.output_index=a.output_index) "
                + "and not exists(select 1 from address_utxo_unspent u where u.tx_hash=a.tx_hash and u.output_index=a.output_index)"
                + conflictClause(), fromSlot, toSlot);
        int deleted = dsl.execute("delete from address_utxo_unspent where (tx_hash,output_index) in "
                + "(select tx_hash,output_index from tx_input where spent_at_slot between ? and ?)", fromSlot, toSlot);
        return new int[]{copied, deleted};
    }

    /** Must run inside source rollback, before tx_input and address_utxo rows are deleted. */
    @Transactional
    public int[] rollbackTo(long slot) {
        lock();
        int deleted = dsl.execute("delete from address_utxo_unspent where slot > ?", slot);
        int restored = dsl.execute("insert into address_utxo_unspent (" + COLUMNS + ") select " + COLUMNS
                + " from address_utxo a where a.slot <= ? "
                + "and exists(select 1 from tx_input i where i.tx_hash=a.tx_hash and i.output_index=a.output_index and i.spent_at_slot > ?) "
                + "and not exists(select 1 from address_utxo_unspent u where u.tx_hash=a.tx_hash and u.output_index=a.output_index)"
                + conflictClause(), slot, slot);
        return new int[]{deleted, restored};
    }

    private void lock() {
        if (dsl.dialect().family() == SQLDialect.POSTGRES)
            dsl.execute("select pg_advisory_xact_lock(" + MUTATION_LOCK + ")");
    }

    private String conflictClause() {
        return dsl.dialect().family() == SQLDialect.POSTGRES
                ? " on conflict (output_index, tx_hash) do nothing" : "";
    }
}
