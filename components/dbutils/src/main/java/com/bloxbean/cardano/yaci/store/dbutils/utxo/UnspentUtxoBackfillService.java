package com.bloxbean.cardano.yaci.store.dbutils.utxo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Bounded, resumable PostgreSQL fill for the opt-in current UTxO table. Never enables readers. */
@Service
@Slf4j
public class UnspentUtxoBackfillService {
    // Shared with UnspentUtxoTableService. Serialize copy/reconcile against commit and source rollback.
    private static final long MUTATION_LOCK = 0x594143495554584fL;
    private static final String COLUMNS = "tx_hash, output_index, slot, block_hash, epoch, lovelace_amount, amounts, "
            + "data_hash, inline_datum, owner_addr, owner_addr_full, owner_stake_addr, owner_payment_credential, "
            + "owner_stake_credential, script_ref, reference_script_hash, is_collateral_return, block, block_time, "
            + "update_datetime, tx_index";
    private static final String COPY = "insert into address_utxo_unspent (" + COLUMNS + ") "
            + "select " + COLUMNS + " from address_utxo a where a.slot between ? and ? "
            + "and not exists(select 1 from tx_input i where i.tx_hash=a.tx_hash and i.output_index=a.output_index) "
            + "and not exists(select 1 from address_utxo_unspent u where u.tx_hash=a.tx_hash and u.output_index=a.output_index) "
            + "on conflict (output_index, tx_hash) do nothing";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate chunks;

    public UnspentUtxoBackfillService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.chunks = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    public long tipSlot() {
        Long tip = jdbc.queryForObject("select max(slot) from address_utxo", Long.class);
        return tip == null ? -1 : tip;
    }

    /** One transaction per chunk; any timeout aborts the call and does not report completion. */
    public long backfill(long fromSlot, long toSlot, long chunkSlots, int statementTimeoutMs, int lockTimeoutMs) {
        validate(chunkSlots, statementTimeoutMs, lockTimeoutMs);
        if (fromSlot < -1 || toSlot < fromSlot) throw new IllegalArgumentException("Invalid backfill interval");
        long copied = 0;
        for (long first = fromSlot; first <= toSlot;) {
            long last = first > Long.MAX_VALUE - chunkSlots + 1
                    ? toSlot : Math.min(first + chunkSlots - 1, toSlot);
            final long start = first;
            Integer rows = chunks.execute(status -> {
                lock(statementTimeoutMs, lockTimeoutMs);
                return jdbc.update(COPY, start, last);
            });
            copied += rows == null ? 0 : rows;
            log.info("Current UTxO backfill chunk complete: fromSlot={}, toSlot={}, copied={}, totalCopied={}",
                    first, last, rows, copied);
            if (last == toSlot) break;
            first = last + 1;
        }
        return copied;
    }

    /** Final check of spends that may have committed while older chunks were copied. */
    public int reconcileSpentAfter(long slot, int statementTimeoutMs, int lockTimeoutMs) {
        validate(1, statementTimeoutMs, lockTimeoutMs);
        Integer removed = chunks.execute(status -> {
            lock(statementTimeoutMs, lockTimeoutMs);
            return jdbc.update("delete from address_utxo_unspent where (tx_hash,output_index) in "
                    + "(select tx_hash,output_index from tx_input where spent_at_slot > ?)", slot);
        });
        return removed == null ? 0 : removed;
    }

    private void lock(int statementTimeoutMs, int lockTimeoutMs) {
        jdbc.queryForObject("select set_config('statement_timeout', ?, true)", String.class, statementTimeoutMs + "ms");
        jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class, lockTimeoutMs + "ms");
        jdbc.execute("select pg_advisory_xact_lock(" + MUTATION_LOCK + ")");
    }

    private static void validate(long chunkSlots, int statementTimeoutMs, int lockTimeoutMs) {
        if (chunkSlots <= 0 || statementTimeoutMs <= 0 || lockTimeoutMs <= 0)
            throw new IllegalArgumentException("Chunk size and query budgets must be positive");
    }
}
