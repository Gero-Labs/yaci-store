package com.bloxbean.cardano.yaci.store.dbutils.index.service;

import com.bloxbean.cardano.yaci.store.dbutils.index.model.RollbackConfig;
import com.bloxbean.cardano.yaci.store.dbutils.index.model.RollbackContext;
import com.bloxbean.cardano.yaci.store.dbutils.index.util.DatabaseUtils;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H2 does not abort a whole transaction after one failed SQL statement. */
class CurrentUtxoRollbackAtomicityTest {
    @Test
    void sourceFailureAlsoUndoesCurrentRestorationAndCursorOnH2() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table cursor_ (id bigint, block_hash varchar, slot bigint, block_number bigint, era int)");
        jdbc.execute("create table address_utxo (tx_hash varchar, output_index smallint, slot bigint, "
                + "block_hash varchar, epoch int, lovelace_amount bigint, amounts varchar, data_hash varchar, "
                + "inline_datum varchar, owner_addr varchar, owner_addr_full varchar, owner_stake_addr varchar, "
                + "owner_payment_credential varchar, owner_stake_credential varchar, script_ref varchar, "
                + "reference_script_hash varchar, is_collateral_return boolean, block bigint, block_time bigint, "
                + "update_datetime timestamp, tx_index int, primary key(output_index, tx_hash))");
        jdbc.execute("create table address_utxo_unspent as select * from address_utxo");
        jdbc.execute("create table tx_input (tx_hash varchar, output_index smallint, spent_at_slot bigint, "
                + "primary key(output_index, tx_hash))");
        jdbc.execute("insert into address_utxo(tx_hash,output_index,slot) values ('old',0,100),('new',0,200)");
        jdbc.execute("insert into tx_input values ('old',0,200)");
        jdbc.execute("insert into address_utxo_unspent select * from address_utxo where tx_hash='new'");
        jdbc.execute("create table retained_output (tx_hash varchar, output_index smallint, "
                + "foreign key (output_index,tx_hash) references address_utxo(output_index,tx_hash))");
        jdbc.execute("insert into retained_output values ('new',0)");
        var service = new RollbackService(new NamedParameterJdbcTemplate(ds), new DatabaseUtils(jdbc, DSL.using(ds, SQLDialect.H2)));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var point = RollbackContext.builder().epoch(1).eventPublisherId(1).rollbackLedgerState(false)
                .rollbackPointSlot(150L).rollbackPointBlock(1L).rollbackPointBlockHash("hash").rollbackPointEra(5).build();
        var config = new RollbackConfig(List.of(delete("tx_input", "spent_at_slot"), delete("address_utxo", "slot")));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.executeRollback(config, point)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("source rollback failed");
        assertThat(jdbc.queryForList("select tx_hash from address_utxo_unspent", String.class)).containsExactly("new");
        assertThat(jdbc.queryForObject("select count(*) from tx_input", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from address_utxo", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from cursor_", Integer.class)).isZero();
    }

    private static RollbackConfig.TableRollbackDefinition delete(String table, String column) {
        return RollbackConfig.TableRollbackDefinition.builder().name(table).operation("DELETE")
                .condition(RollbackConfig.TableRollbackDefinition.Condition.builder()
                        .type("slot").column(column).operator(">").build()).build();
    }
}
