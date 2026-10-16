package com.bloxbean.cardano.yaci.store.dbutils.utxo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.ArrayDeque;
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

    /** A COPY timeout rolls back its attempt and halves the range; successful ranges commit independently. */
    public long backfill(long fromSlot, long toSlot, long chunkSlots, int statementTimeoutMs, int lockTimeoutMs) {
        validate(chunkSlots, statementTimeoutMs, lockTimeoutMs);
        if (fromSlot < -1 || toSlot < fromSlot) throw new IllegalArgumentException("Invalid backfill interval");
        // Joining a caller transaction would postpone rollback/commit and retain its advisory lock during recovery.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Current UTxO backfill cannot run inside an ambient transaction");
        long copied = 0;
        for (long first = fromSlot; first <= toSlot;) {
            long last = first > Long.MAX_VALUE - chunkSlots + 1
                    ? toSlot : Math.min(first + chunkSlots - 1, toSlot);
            var pending = new ArrayDeque<SlotRange>();
            pending.push(new SlotRange(first, last));
            while (!pending.isEmpty()) {
                SlotRange range = pending.pop();
                Integer rows;
                try {
                    rows = chunks.execute(status -> {
                        lock(statementTimeoutMs, lockTimeoutMs);
                        try {
                            return jdbc.update(COPY, range.first(), range.last());
                        } catch (DataAccessException failure) {
                            // Only this marker can recover after execute has completed rollback. Lock, commit,
                            // and rollback errors must propagate even if they contain a canceled-query cause.
                            throw new CopyFailure(failure);
                        }
                    });
                } catch (CopyFailure attempt) {
                    if (range.first() == range.last() || !isStatementTimeout(attempt.failure))
                        throw attempt.failure;
                    // Signed floor average also handles the valid [-1, Long.MAX_VALUE] interval.
                    long middle = (range.first() & range.last()) + ((range.first() ^ range.last()) >> 1);
                    pending.push(new SlotRange(middle + 1, range.last()));
                    pending.push(new SlotRange(range.first(), middle));
                    log.warn("Current UTxO COPY statement timed out; retrying smaller ranges: fromSlot={}, toSlot={}",
                            range.first(), range.last());
                    continue;
                }
                copied += rows == null ? 0 : rows;
                log.info("Current UTxO backfill chunk complete: fromSlot={}, toSlot={}, copied={}, totalCopied={}",
                        range.first(), range.last(), rows, copied);
            }
            if (last == toSlot) break;
            first = last + 1;
        }
        return copied;
    }

    private static boolean isStatementTimeout(DataAccessException failure) {
        if (!(failure.getCause() instanceof SQLException sql)) return false;
        // 57014 also covers operator cancellation. Unknown/localized primary messages fail closed;
        // a later chained exception must never override a primary lock or connection failure.
        return "57014".equals(sql.getSQLState()) && sql.getMessage() != null
                && sql.getMessage().contains("canceling statement due to statement timeout");
    }

    private record SlotRange(long first, long last) {}

    private static final class CopyFailure extends RuntimeException {
        private final DataAccessException failure;

        private CopyFailure(DataAccessException failure) {
            super(failure);
            this.failure = failure;
        }
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
