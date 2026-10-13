package com.bloxbean.cardano.yaci.store.dbutils.index.service;

import com.bloxbean.cardano.yaci.store.dbutils.index.model.RollbackConfig;
import com.bloxbean.cardano.yaci.store.dbutils.index.model.RollbackContext;
import com.bloxbean.cardano.yaci.store.dbutils.index.util.DatabaseUtils;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The offline rollback must restore current outputs before source spends disappear. */
@Testcontainers(disabledWithoutDocker = true)
class CurrentUtxoRollbackTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private RollbackService service;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("drop table if exists address_utxo_unspent, address_utxo, tx_input, cursor_");
        jdbc.execute("create table cursor_ (id bigint, block_hash varchar, slot bigint, block_number bigint, era int)");
        jdbc.execute("create table address_utxo (tx_hash varchar, output_index smallint, slot bigint, "
                + "block_hash varchar, epoch int, lovelace_amount bigint, amounts jsonb, data_hash varchar, "
                + "inline_datum text, owner_addr varchar, owner_addr_full text, owner_stake_addr varchar, "
                + "owner_payment_credential varchar, owner_stake_credential varchar, script_ref text, "
                + "reference_script_hash varchar, is_collateral_return boolean, block bigint, block_time bigint, "
                + "update_datetime timestamp, tx_index int, primary key(output_index, tx_hash))");
        jdbc.execute("create table address_utxo_unspent (like address_utxo including all)");
        jdbc.execute("create table tx_input (tx_hash varchar, output_index smallint, spent_at_slot bigint, "
                + "primary key(output_index, tx_hash))");
        jdbc.execute("insert into address_utxo(tx_hash,output_index,slot) values ('old',0,100),('new',0,200)");
        jdbc.execute("insert into tx_input values ('old',0,200)");
        jdbc.execute("insert into address_utxo_unspent select * from address_utxo where tx_hash='new'");
        service = new RollbackService(new NamedParameterJdbcTemplate(ds), new DatabaseUtils(jdbc, DSL.using(ds, SQLDialect.POSTGRES)));
    }

    private RollbackContext point(boolean ledgerOnly) {
        return RollbackContext.builder().epoch(1).eventPublisherId(1).rollbackLedgerState(ledgerOnly)
                .rollbackPointSlot(150L).rollbackPointBlock(1L).rollbackPointBlockHash("hash").rollbackPointEra(5).build();
    }

    private static RollbackConfig.TableRollbackDefinition delete(String table, String column) {
        return RollbackConfig.TableRollbackDefinition.builder().name(table).operation("DELETE")
                .condition(RollbackConfig.TableRollbackDefinition.Condition.builder()
                        .type("slot").column(column).operator(">").build()).build();
    }

    private List<String> current() {
        return jdbc.queryForList("select tx_hash from address_utxo_unspent order by tx_hash", String.class);
    }

    @Test
    void sourceRollbackRestoresCurrentRowsAndDropsRolledBackOutputs() {
        // Source spends intentionally deleted first: restoration must precede the generic YAML loop.
        var config = new RollbackConfig(List.of(delete("tx_input", "spent_at_slot"), delete("address_utxo", "slot")));
        transaction.executeWithoutResult(status -> assertThat(service.executeRollback(config, point(false)).getSecond()).isTrue());
        assertThat(current()).containsExactly("old");
        assertThat(jdbc.queryForList("select tx_hash from address_utxo order by tx_hash", String.class)).containsExactly("old");
        assertThat(jdbc.queryForObject("select count(*) from tx_input", Integer.class)).isZero();
    }

    @Test
    void ledgerOnlyRollbackLeavesCurrentOutputsAlone() {
        transaction.executeWithoutResult(status -> service.executeRollback(new RollbackConfig(List.of()), point(true)));
        assertThat(current()).containsExactly("new");
    }

    @Test
    void outerTransactionFailureUndoesCurrentAndSourceRollback() {
        var config = new RollbackConfig(List.of(delete("tx_input", "spent_at_slot"), delete("address_utxo", "slot")));
        transaction.executeWithoutResult(status -> {
            service.executeRollback(config, point(false));
            status.setRollbackOnly();
        });
        assertThat(current()).containsExactly("new");
        assertThat(jdbc.queryForObject("select count(*) from tx_input", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from address_utxo", Integer.class)).isEqualTo(2);
    }

    @Test
    void incompleteSourceRollbackIsRejectedBeforeAnyMutation() {
        var config = new RollbackConfig(List.of(delete("tx_input", "spent_at_slot")));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.executeRollback(config, point(false))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both address_utxo and tx_input");
        assertThat(current()).containsExactly("new");
        assertThat(jdbc.queryForObject("select count(*) from tx_input", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from cursor_", Integer.class)).isZero();
    }

    @Test
    void mismatchedSourceBoundaryIsRejectedBeforeAnyMutation() {
        var inputs = delete("tx_input", "spent_at_slot");
        inputs.getCondition().setOffset(1);
        var config = new RollbackConfig(List.of(inputs, delete("address_utxo", "slot")));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.executeRollback(config, point(false))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DELETE at the same slot boundary");
        assertThat(current()).containsExactly("new");
        assertThat(jdbc.queryForObject("select count(*) from address_utxo", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from tx_input", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from cursor_", Integer.class)).isZero();
    }

    @Test
    void sourceSqlFailurePropagatesAndUndoesCurrentAndSourceChanges() {
        jdbc.execute("create table retained_output (tx_hash varchar, output_index smallint, "
                + "foreign key (output_index,tx_hash) references address_utxo(output_index,tx_hash))");
        try {
            jdbc.execute("insert into retained_output values ('new',0)");
            var config = new RollbackConfig(List.of(delete("tx_input", "spent_at_slot"), delete("address_utxo", "slot")));
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.executeRollback(config, point(false))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("source rollback failed");
            assertThat(current()).containsExactly("new");
            assertThat(jdbc.queryForObject("select count(*) from tx_input", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from address_utxo", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from cursor_", Integer.class)).isZero();
        } finally {
            jdbc.execute("drop table retained_output");
        }
    }
}
