package com.bloxbean.cardano.yaci.store.dbutils.utxo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        jdbc.execute("drop table if exists address_utxo_unspent, address_utxo, tx_input");
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
        return ((Number) service.getClass().getMethod("backfill", long.class, long.class, long.class,
                int.class, int.class).invoke(service, from, to, chunk, 2000, 250)).longValue();
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
        assertThatThrownBy(() -> backfill(-1, 10, 20))
                .rootCause().hasMessageContaining("canceling statement due to statement timeout");
        assertThat(current()).isEmpty();
        jdbc.execute("drop function delay_holder_copy() cascade");
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
