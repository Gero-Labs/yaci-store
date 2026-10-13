package com.bloxbean.cardano.yaci.store.utxo.storage.impl;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class UnspentUtxoTableServiceTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private Object service;

    @EnableTransactionManagement
    static class Transactions {}

    @BeforeEach
    void setUp() throws Exception {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        transactions = new DataSourceTransactionManager(ds);
        jdbc = new JdbcTemplate(ds);
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
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DSLContext.class, () -> DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES));
            context.registerBean(DataSourceTransactionManager.class, () -> transactions);
            context.register(Transactions.class,
                    Class.forName("com.bloxbean.cardano.yaci.store.utxo.storage.impl.UnspentUtxoTableService"));
            context.refresh();
            service = context.getBean("unspentUtxoTableService");
        }
    }

    private int[] apply(long from, long to) throws Exception {
        return (int[]) service.getClass().getMethod("applyCommit", long.class, long.class).invoke(service, from, to);
    }

    private int[] rollback(long slot) throws Exception {
        return (int[]) service.getClass().getMethod("rollbackTo", long.class).invoke(service, slot);
    }

    private void output(String hash, int index, long slot) {
        jdbc.update("insert into address_utxo(tx_hash, output_index, slot, owner_addr, owner_stake_addr, amounts, tx_index) "
                + "values (?, ?, ?, ?, 'stake', '[{\"unit\":\"a\",\"quantity\":\"7\"}]'::jsonb, null)", hash, index, slot, hash);
    }

    private void spend(String hash, int index, long slot) {
        jdbc.update("insert into tx_input values (?, ?, ?)", hash, index, slot);
    }

    private List<String> current() {
        return jdbc.queryForList("select tx_hash || '#' || output_index from address_utxo_unspent order by 1", String.class);
    }

    private List<String> expected() {
        return jdbc.queryForList("select a.tx_hash || '#' || a.output_index from address_utxo a "
                + "where not exists(select 1 from tx_input i where i.tx_hash=a.tx_hash and i.output_index=a.output_index) order by 1", String.class);
    }

    @Test
    void spendBeforeOutputInOneBatchAndReplayKeepExactlyTheCurrentSet() throws Exception {
        spend("a", 0, 110);
        output("a", 0, 100);
        output("a", 1, 100);
        apply(100, 110);
        apply(100, 110);
        assertThat(current()).containsExactly("a#1").isEqualTo(expected());
        spend("a", 1, 200);
        output("b", 0, 200);
        apply(200, 200);
        assertThat(current()).containsExactly("b#0").isEqualTo(expected());
    }

    @Test
    void rollbackRestoresOldOutputsAndDeletesNewOutputsWithinTheSourceTransaction() throws Exception {
        output("a", 0, 100);
        apply(100, 100);
        spend("a", 0, 200);
        output("b", 0, 200);
        apply(200, 200);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            try { rollback(150); } catch (Exception e) { throw new RuntimeException(e); }
            jdbc.update("delete from tx_input where spent_at_slot > 150");
            jdbc.update("delete from address_utxo where slot > 150");
        });
        assertThat(current()).containsExactly("a#0").isEqualTo(expected());
    }

    @Test
    void aFailedSourceRollbackRollsBackCurrentRestorationToo() throws Exception {
        output("a", 0, 100);
        apply(100, 100);
        spend("a", 0, 200);
        output("b", 0, 200);
        apply(200, 200);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            try { rollback(150); } catch (Exception e) { throw new RuntimeException(e); }
            status.setRollbackOnly();
        });
        assertThat(current()).containsExactly("b#0").isEqualTo(expected());
    }

    @Test
    void genesisNegativeSlotAndNullTransactionIndexAreCopiedWithoutLoss() throws Exception {
        output("genesis", 0, -1);
        apply(-1, -1);
        assertThat(current()).containsExactly("genesis#0").isEqualTo(expected());
        assertThat(jdbc.queryForMap("select amounts::text, tx_index, owner_stake_addr from address_utxo_unspent"))
                .isEqualTo(jdbc.queryForMap("select amounts::text, tx_index, owner_stake_addr from address_utxo"));
    }

    @Test
    void replayRefreshesTheFullPersistedOutputPayload() throws Exception {
        output("replayed", 0, 100);
        jdbc.update("update address_utxo set owner_stake_addr=null, owner_stake_credential=null");
        apply(100, 100);
        jdbc.update("update address_utxo set owner_stake_addr='resolved', owner_stake_credential='credential', "
                + "owner_addr_full='full-address', lovelace_amount=9, tx_index=3, inline_datum='datum', "
                + "amounts='[{\"unit\":\"a\",\"quantity\":\"11\"}]'::jsonb, "
                + "update_datetime=timestamp '2026-10-13 12:00:00'");
        apply(100, 100);
        assertThat(current()).containsExactly("replayed#0");
        assertThat(jdbc.queryForObject("select to_jsonb(u)::text from address_utxo_unspent u", String.class))
                .isEqualTo(jdbc.queryForObject("select to_jsonb(a)::text from address_utxo a", String.class));
    }
}
