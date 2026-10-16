package com.bloxbean.cardano.yaci.store.dbutils.utxo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real SQL characterization for resumable bounded backfill, including the genesis slot. */
@Testcontainers(disabledWithoutDocker = true)
class UnspentUtxoBackfillServiceTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private Object service;

    @BeforeEach
    void setUp() throws Exception {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop function if exists delay_holder_copy() cascade");
        jdbc.execute("drop sequence if exists copy_attempts");
        jdbc.execute("drop table if exists copy_audit, address_utxo_unspent, address_utxo, tx_input");
        jdbc.execute("create table address_utxo (tx_hash varchar, output_index smallint, slot bigint, "
                + "block_hash varchar, epoch int, lovelace_amount bigint, amounts jsonb, data_hash varchar, "
                + "inline_datum text, owner_addr varchar, owner_addr_full text, owner_stake_addr varchar, "
                + "owner_payment_credential varchar, owner_stake_credential varchar, script_ref text, "
                + "reference_script_hash varchar, is_collateral_return boolean, block bigint, block_time bigint, "
                + "update_datetime timestamp, tx_index int, primary key(output_index, tx_hash))");
        jdbc.execute("create table address_utxo_unspent (like address_utxo including all)");
        jdbc.execute("create table tx_input (tx_hash varchar, output_index smallint, spent_at_slot bigint, "
                + "primary key(output_index, tx_hash))");
        // Reflective loading keeps the RED a missing implementation behavior, not an unresolved Java symbol.
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.register(Class.forName("com.bloxbean.cardano.yaci.store.dbutils.utxo.UnspentUtxoBackfillService"));
            context.refresh();
            service = context.getBean("unspentUtxoBackfillService");
        }
    }

    private long backfill(long from, long to, long chunk) throws Exception {
        return backfill(from, to, chunk, 2000, 250);
    }

    private long backfill(long from, long to, long chunk, int statementBudget, int lockBudget) throws Exception {
        return ((Number) service.getClass().getMethod("backfill", long.class, long.class, long.class,
                int.class, int.class).invoke(service, from, to, chunk, statementBudget, lockBudget)).longValue();
    }

    private void output(String hash, long slot) {
        jdbc.update("insert into address_utxo(tx_hash, output_index, slot, owner_addr, amounts) "
                + "values (?, 0, ?, ?, '[{\"unit\":\"lovelace\",\"quantity\":\"7\"}]'::jsonb)", hash, slot, hash);
    }

    private List<String> current() {
        return jdbc.queryForList("select tx_hash from address_utxo_unspent order by tx_hash", String.class);
    }

    @Test
    void boundsChunksIncludesGenesisAndIsSafeToResume() throws Exception {
        output("genesis", -1);
        output("a", 10);
        output("b", 20);
        output("c", 30);
        jdbc.update("insert into tx_input values ('b', 0, 40)");
        assertThat(backfill(-1, 20, 7)).isEqualTo(2);
        assertThat(backfill(-1, 30, 7)).isEqualTo(1);
        assertThat(backfill(-1, 30, 7)).isZero();
        assertThat(current()).containsExactly("a", "c", "genesis");
    }

    @Test
    void concurrentOverlappingBackfillsRemainIdempotent() throws Exception {
        for (int i = 0; i < 50; i++) output("t" + i, i);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> backfill(-1, 50, 7));
            var second = executor.submit(() -> backfill(-1, 50, 7));
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(50);
        }
        assertThat(current()).hasSize(50);
    }

    @Test
    void aTimedOutChunkDoesNotReportCompletionOrPartiallyCommit() throws Exception {
        output("a", 10);
        jdbc.execute("create function delay_holder_copy() returns trigger language plpgsql as $$ "
                + "begin perform pg_sleep(3); return new; end $$");
        jdbc.execute("create trigger delay_holder_copy before insert on address_utxo_unspent "
                + "for each row execute function delay_holder_copy()");
        try (var completion = new CompletionCapture()) {
            assertThatThrownBy(() -> backfill(10, 10, 1))
                    .rootCause().hasMessageContaining("canceling statement due to statement timeout");
            assertThat(completion.messages).isEmpty();
        }
        assertThat(current()).isEmpty();
        jdbc.execute("drop function delay_holder_copy() cascade");
    }

    @Test
    void aCopyTimeoutSplitsIntoCommittedSubrangesWithoutChangingPayload() throws Exception {
        jdbc.execute("insert into address_utxo values "
                + "('same-hash',0,10,'block-a',1,7,'[{\"unit\":\"a\",\"quantity\":\"7\"}]',"
                + "'data-a','datum-a','owner-a','full-a','stake-a','payment-a','credential-a','script-a',"
                + "'reference-a',false,100,1000,'2026-01-01 01:02:03',null),"
                + "('same-hash',1,11,'block-b',2,9,'[{\"unit\":\"b\",\"quantity\":\"9\"}]',"
                + "null,null,'owner-b','full-b','stake-b','payment-b','credential-b',null,"
                + "null,true,101,1001,'2026-01-02 01:02:03',4)");
        jdbc.execute("create table copy_audit(slot bigint, transaction_id bigint, statement_budget text, lock_budget text)");
        jdbc.execute("create function delay_holder_copy() returns trigger language plpgsql as $$ begin "
                + "insert into copy_audit values (new.slot,txid_current(),current_setting('statement_timeout'),"
                + "current_setting('lock_timeout')); perform pg_sleep(1.3); return new; end $$");
        jdbc.execute("create trigger delay_holder_copy before insert on address_utxo_unspent "
                + "for each row execute function delay_holder_copy()");
        try (var completion = new CompletionCapture()) {
            assertThat(backfill(10, 11, 2)).isEqualTo(2);
            assertThat(completion.messages).containsExactly(
                    "Current UTxO backfill chunk complete: fromSlot=10, toSlot=10, copied=1, totalCopied=1",
                    "Current UTxO backfill chunk complete: fromSlot=11, toSlot=11, copied=1, totalCopied=2");
            assertThat(completion.visibleRows).containsExactly(1L, 2L);
        }
        assertThat(jdbc.queryForList("select to_jsonb(u)::text from address_utxo_unspent u order by output_index", String.class))
                .containsExactlyElementsOf(jdbc.queryForList(
                        "select to_jsonb(a)::text from address_utxo a order by output_index", String.class));
        assertThat(jdbc.queryForObject("select count(*) from copy_audit", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(distinct transaction_id) from copy_audit", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("select distinct statement_budget || '/' || lock_budget from copy_audit", String.class))
                .containsExactly("2s/250ms");
        assertThat(backfill(10, 11, 2)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from copy_audit", Long.class)).isEqualTo(2);
    }

    @Test
    void anAmbientTransactionIsRejectedBeforeCopying() {
        output("a", 10);
        var ambient = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        ambient.executeWithoutResult(status -> {
            assertThatThrownBy(() -> backfill(10, 10, 1)).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ambient transaction");
            assertThat(current()).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(current()).isEmpty();
    }

    @Test
    void aLaterIrreducibleTimeoutPreservesTheCommittedPrefixForResume() throws Exception {
        output("a", 10);
        output("b", 11);
        jdbc.execute("create function delay_holder_copy() returns trigger language plpgsql as $$ begin "
                + "perform pg_sleep(case when new.slot=10 then 1.3 else 3 end); return new; end $$");
        jdbc.execute("create trigger delay_holder_copy before insert on address_utxo_unspent "
                + "for each row execute function delay_holder_copy()");
        try (var completion = new CompletionCapture()) {
            assertThatThrownBy(() -> backfill(10, 11, 2)).rootCause()
                    .hasMessageContaining("canceling statement due to statement timeout");
            assertThat(completion.messages).containsExactly(
                    "Current UTxO backfill chunk complete: fromSlot=10, toSlot=10, copied=1, totalCopied=1");
            assertThat(completion.visibleRows).containsExactly(1L);
        }
        assertThat(current()).containsExactly("a");
        jdbc.execute("drop function delay_holder_copy() cascade");
        assertThat(backfill(11, 11, 1)).isEqualTo(1);
        assertThat(backfill(10, 11, 2)).isZero();
        assertThat(current()).containsExactly("a", "b");
    }

    @Test
    void userCancellationIsNotRetriedAsAStatementTimeout() throws Exception {
        assertCopyErrorIsNotRetried("57014", "canceling statement due to user request");
    }

    @Test
    void aNonTimeoutSqlStateIsNotRetriedEvenWithTheTimeoutMessage() throws Exception {
        assertCopyErrorIsNotRetried("23514", "canceling statement due to statement timeout");
    }

    @Test
    void aConnectionFailureSqlStateIsNotRetriedEvenWithTheTimeoutMessage() throws Exception {
        assertCopyErrorIsNotRetried("08006", "canceling statement due to statement timeout");
    }

    @Test
    void aCommitFailureWithTheTimeoutSignalDoesNotSplit() throws Exception {
        output("a", 10);
        output("b", 11);
        jdbc.execute("create sequence copy_attempts");
        jdbc.execute("create function delay_holder_copy() returns trigger language plpgsql as $$ begin "
                + "perform nextval('copy_attempts'); "
                + "raise exception 'canceling statement due to statement timeout' using errcode='57014'; end $$");
        jdbc.execute("create constraint trigger delay_holder_copy after insert on address_utxo_unspent "
                + "deferrable initially deferred for each row execute function delay_holder_copy()");
        try (var completion = new CompletionCapture()) {
            assertThatThrownBy(() -> backfill(10, 11, 2)).rootCause()
                    .hasMessageContaining("canceling statement due to statement timeout");
            assertThat(completion.messages).isEmpty();
        }
        assertThat(jdbc.queryForObject("select last_value from copy_attempts", Long.class)).isEqualTo(1);
        assertThat(current()).isEmpty();
    }

    @Test
    void aPrimaryLockFailureIsNotRetriedBecauseOfANestedTimeout() {
        var lockFailure = new SQLException("canceling statement due to lock timeout", "55P03");
        lockFailure.setNextException(new SQLException("canceling statement due to statement timeout", "57014"));
        var failure = new DataAccessResourceFailureException("COPY failed", lockFailure);
        var attempts = new AtomicInteger();
        var failingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                attempts.incrementAndGet();
                throw failure;
            }
        };
        assertThatThrownBy(() -> new UnspentUtxoBackfillService(failingJdbc).backfill(10, 11, 2, 2000, 250))
                .isSameAs(failure);
        assertThat(attempts).hasValue(1);
    }

    private void assertCopyErrorIsNotRetried(String sqlState, String message) throws Exception {
        output("a", 10);
        output("b", 11);
        jdbc.execute("create sequence copy_attempts");
        jdbc.execute("create function delay_holder_copy() returns trigger language plpgsql as $$ begin "
                + "perform nextval('copy_attempts'); raise exception '" + message + "' using errcode='" + sqlState + "'; end $$");
        jdbc.execute("create trigger delay_holder_copy before insert on address_utxo_unspent "
                + "for each row execute function delay_holder_copy()");
        try (var completion = new CompletionCapture()) {
            assertThatThrownBy(() -> backfill(10, 11, 2)).rootCause().hasMessageContaining(message);
            assertThat(completion.messages).isEmpty();
        }
        // A sequence survives rollback, so a split/retry would visibly increment this again.
        assertThat(jdbc.queryForObject("select last_value from copy_attempts", Long.class)).isEqualTo(1);
        assertThat(current()).isEmpty();
    }

    @Test
    void anAdvisoryLockTimeoutDoesNotRetryOrCopy() throws Exception {
        assertAdvisoryLockFailureDoesNotCopy(2000, 250, "canceling statement due to lock timeout");
    }

    @Test
    void aStatementTimeoutWhileAcquiringTheAdvisoryLockDoesNotSplit() throws Exception {
        assertAdvisoryLockFailureDoesNotCopy(100, 500, "canceling statement due to statement timeout");
    }

    private void assertAdvisoryLockFailureDoesNotCopy(int statementBudget, int lockBudget, String message) throws Exception {
        output("a", 10);
        output("b", 11);
        var lockAttempts = new AtomicInteger();
        var observedJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public void execute(String sql) {
                if (sql.startsWith("select pg_advisory_xact_lock(")) lockAttempts.incrementAndGet();
                super.execute(sql);
            }
        };
        var observedService = new UnspentUtxoBackfillService(observedJdbc);
        try (var lockHolder = jdbc.getDataSource().getConnection(); var completion = new CompletionCapture()) {
            lockHolder.setAutoCommit(false);
            try (var statement = lockHolder.createStatement()) {
                statement.execute("select pg_advisory_xact_lock(6431495725105043535)");
            }
            assertThatThrownBy(() -> observedService.backfill(10, 11, 2, statementBudget, lockBudget)).rootCause()
                    .hasMessageContaining(message);
            assertThat(lockAttempts).hasValue(1);
            assertThat(completion.messages).isEmpty();
            assertThat(current()).isEmpty();
            lockHolder.rollback();
        }
    }

    private final class CompletionCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(UnspentUtxoBackfillService.class);
        private final List<String> messages = new ArrayList<>();
        private final List<Long> visibleRows = new ArrayList<>();

        private CompletionCapture() {
            start();
            logger.addAppender(this);
        }

        @Override
        protected void append(ILoggingEvent event) {
            String message = event.getFormattedMessage();
            if (message.startsWith("Current UTxO backfill chunk complete:")) {
                messages.add(message);
                // DriverManagerDataSource supplies a different connection from the completed chunk.
                var independent = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
                visibleRows.add(independent.queryForObject("select count(*) from address_utxo_unspent", Long.class));
            }
        }

        @Override
        public void close() {
            logger.detachAppender(this);
            stop();
        }
    }

    @Test
    void reconciliationRemovesSpendsCommittedDuringTheFill() throws Exception {
        output("a", 10);
        output("b", 10);
        backfill(-1, 10, 20);
        jdbc.update("insert into tx_input values ('a', 0, 100)");
        long removed = ((Number) service.getClass().getMethod("reconcileSpentAfter", long.class,
                int.class, int.class).invoke(service, 10L, 2000, 250)).longValue();
        assertThat(removed).isEqualTo(1);
        assertThat(current()).containsExactly("b");
    }
}
