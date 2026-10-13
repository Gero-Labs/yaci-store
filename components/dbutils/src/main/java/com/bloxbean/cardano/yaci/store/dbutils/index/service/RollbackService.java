package com.bloxbean.cardano.yaci.store.dbutils.index.service;

import com.bloxbean.cardano.yaci.store.dbutils.index.model.RollbackBlock;
import com.bloxbean.cardano.yaci.store.dbutils.index.model.RollbackConfig;
import com.bloxbean.cardano.yaci.store.dbutils.index.model.RollbackContext;
import com.bloxbean.cardano.yaci.store.dbutils.index.model.TableRollbackAction;
import com.bloxbean.cardano.yaci.store.dbutils.index.util.DatabaseUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RollbackService {
    private static final long CURRENT_UTXO_MUTATION_LOCK = 0x594143495554584fL;
    private static final String CURRENT_UTXO_COLUMNS = "tx_hash, output_index, slot, block_hash, epoch, lovelace_amount, amounts, "
            + "data_hash, inline_datum, owner_addr, owner_addr_full, owner_stake_addr, owner_payment_credential, "
            + "owner_stake_credential, script_ref, reference_script_hash, is_collateral_return, block, block_time, "
            + "update_datetime, tx_index";
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DatabaseUtils databaseUtils;

    @Transactional
    public Pair<List<TableRollbackAction>, Boolean> executeRollback(RollbackConfig config, RollbackContext context) {
        int epoch = context.getEpoch();
        long eventPublisherId = context.getEventPublisherId();

        RollbackBlock rollbackBlock = getRollbackBlockByRollbackContext(context);

        if (rollbackBlock == null) {
            log.error("Failed to get rollback block for epoch: {}", epoch);
            return Pair.of(new ArrayList<>(), false);
        }

        if (databaseUtils.tableExists("epoch_stake_total_state")) {
            // Serialize with snapshot replacement, backfill and batched pruning.
            // This table is optional for deployments without the AdaPot store.
            jdbcTemplate.queryForObject("SELECT id FROM epoch_stake_total_state WHERE id = 1 FOR UPDATE",
                    new MapSqlParameterSource(), Integer.class);
        }

        boolean currentUtxoRollback = !context.isRollbackLedgerState() && requiresCurrentUtxoRollback(config);
        if (!context.isRollbackLedgerState()) {
            if (currentUtxoRollback)
                rollbackCurrentUtxos(rollbackBlock.getSlot());
            rollbackCursor(rollbackBlock, eventPublisherId, currentUtxoRollback);
            rollbackAccountConfig(rollbackBlock, currentUtxoRollback);
        }

        var params = new MapSqlParameterSource();
        params.addValue("epoch", rollbackBlock.getEpoch());
        params.addValue("slot", rollbackBlock.getSlot());

        List<TableRollbackAction> failedRollbackActions = new ArrayList<>();

        for (RollbackConfig.TableRollbackDefinition tableDef : config.getTables()) {
            String tableName = tableDef.getName();
            if (!databaseUtils.tableExists(tableName)) {
                continue;
            }

            String sql;

            if (context.isRollbackLedgerState()) {
                // Todo: Special-case handling for ledger-state-only rollback.
                //  For now this is implemented in code for a couple of tables (reward, adapot_jobs)
                //  to keep the YAML rollback config simple. In the future, this behaviour could be
                //  expressed via a small template DSL on top of RollbackConfig so that patterns like
                //  "delete by slot with extra filters" or "update by epoch with extra filters" are
                //  configurable without adding more hard-coded branches here. We should consider this.

                // For reward table, keep 'refund' rewards and only delete non-refund rows
                if ("reward".equalsIgnoreCase(tableName) && "DELETE".equalsIgnoreCase(tableDef.getOperation())) {
                    sql = "DELETE FROM reward WHERE slot > :slot AND type <> 'refund'";
                }
                // For adapot_jobs, reset status to STARTED for REWARD_CALC jobs from the rollback epoch onward
                else if ("adapot_jobs".equalsIgnoreCase(tableName) && "UPDATE".equalsIgnoreCase(tableDef.getOperation())) {
                    sql = "UPDATE adapot_jobs SET status = 'STARTED' WHERE epoch >= :epoch AND type = 'REWARD_CALC'";
                } else if ("UPDATE".equalsIgnoreCase(tableDef.getOperation())) {
                    sql = buildUpdateSql(tableDef);
                } else if ("DELETE".equalsIgnoreCase(tableDef.getOperation())) {
                    sql = buildDeleteSql(tableDef);
                } else {
                    throw new IllegalArgumentException("Invalid operation: " + tableDef.getOperation());
                }
            } else {
                if ("UPDATE".equalsIgnoreCase(tableDef.getOperation())) {
                    sql = buildUpdateSql(tableDef);
                } else if ("DELETE".equalsIgnoreCase(tableDef.getOperation())) {
                    sql = buildDeleteSql(tableDef);
                } else {
                    throw new IllegalArgumentException("Invalid operation: " + tableDef.getOperation());
                }
            }

            log.info("Executing rollback on table '{}': {}", tableName, sql);
            try {
                jdbcTemplate.update(sql, params);
            } catch (Exception e) {
                log.error("Failed to execute rollback on table '{}': {}", tableName, e.getMessage());
                if (currentUtxoRollback && ("address_utxo".equalsIgnoreCase(tableName) || "tx_input".equalsIgnoreCase(tableName)))
                    throw new IllegalStateException("Current UTxO source rollback failed for " + tableName, e);
                failedRollbackActions.add(new TableRollbackAction(tableName, sql));
            }
        }

        boolean rollbackSuccess = failedRollbackActions.isEmpty();
        return Pair.of(failedRollbackActions, rollbackSuccess);
    }



    private boolean requiresCurrentUtxoRollback(RollbackConfig config) {
        var outputs = config.getTables().stream().filter(table -> "address_utxo".equalsIgnoreCase(table.getName())).toList();
        var spends = config.getTables().stream().filter(table -> "tx_input".equalsIgnoreCase(table.getName())).toList();
        if ((outputs.isEmpty() && spends.isEmpty()) || !databaseUtils.tableExists("address_utxo_unspent"))
            return false;
        if (outputs.size() != 1 || spends.size() != 1)
            throw new IllegalArgumentException("Current UTxO rollback requires both address_utxo and tx_input exactly once");
        if (!isSourceSlotDelete(outputs.getFirst(), "slot") || !isSourceSlotDelete(spends.getFirst(), "spent_at_slot"))
            throw new IllegalArgumentException("Current UTxO source tables require DELETE at the same slot boundary");
        return true;
    }

    private boolean isSourceSlotDelete(RollbackConfig.TableRollbackDefinition table, String column) {
        var condition = table.getCondition();
        return "DELETE".equalsIgnoreCase(table.getOperation()) && condition != null
                && "slot".equalsIgnoreCase(condition.getType()) && column.equalsIgnoreCase(condition.getColumn())
                && ">".equals(condition.getOperator()) && (condition.getOffset() == null || condition.getOffset() == 0);
    }

    /** The transaction also owns the source deletes below, so a concurrent backfill cannot resurrect them. */
    private void rollbackCurrentUtxos(long slot) {
        var params = new MapSqlParameterSource().addValue("slot", slot);
        var jdbc = jdbcTemplate.getJdbcTemplate();
        var dbType = DatabaseUtils.getDbType(jdbc.getDataSource()).orElse(null);
        if (dbType == DatabaseUtils.DbType.postgres)
            jdbc.execute("select pg_advisory_xact_lock(" + CURRENT_UTXO_MUTATION_LOCK + ")");
        jdbcTemplate.update("delete from address_utxo_unspent where slot > :slot", params);
        String conflict = dbType == DatabaseUtils.DbType.postgres
                ? " on conflict (output_index, tx_hash) do nothing" : "";
        jdbcTemplate.update("insert into address_utxo_unspent (" + CURRENT_UTXO_COLUMNS + ") select " + CURRENT_UTXO_COLUMNS
                + " from address_utxo a where a.slot <= :slot "
                + "and exists(select 1 from tx_input i where i.tx_hash=a.tx_hash and i.output_index=a.output_index and i.spent_at_slot > :slot) "
                + "and not exists(select 1 from address_utxo_unspent u where u.tx_hash=a.tx_hash and u.output_index=a.output_index)"
                + conflict, params);
    }

    private RollbackBlock getRollbackBlockByRollbackContext(RollbackContext context) {
        if (!databaseUtils.tableExists("block")) {
            if (context.getRollbackPointBlock() == null
                    || context.getRollbackPointBlockHash() == null
                    || context.getRollbackPointEra() == null
                    || context.getRollbackPointSlot() == null) {
                String errorMsg = String.format("Block table not available and manual rollback point not provided for epoch %d. " +
                        "Please provide slot, era, block_number and block_hash using CLI options (--slot, --block, --era --block-hash) " +
                        "or configure them in application properties", context.getEpoch());
                log.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            return RollbackBlock.builder()
                    .hash(context.getRollbackPointBlockHash())
                    .slot(context.getRollbackPointSlot())
                    .epoch(context.getEpoch())
                    .number(context.getRollbackPointBlock())
                    .era(context.getRollbackPointEra())
                    .build();
        }

        String sql = "SELECT hash, slot, number, epoch_slot, era FROM block WHERE epoch = :epoch ORDER BY slot DESC LIMIT 1";
        var params = new MapSqlParameterSource()
                .addValue("epoch", context.getEpoch() - 1);

        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) ->
                RollbackBlock.builder()
                        .hash(rs.getString("hash"))
                        .slot(rs.getLong("slot"))
                        .epoch(context.getEpoch())
                        .epochSlot(rs.getInt("epoch_slot"))
                        .number(rs.getLong("number"))
                        .era(Integer.valueOf(rs.getString("era")))
                        .build()
        );
    }

    public Pair<List<String>, List<String>> verifyRollbackActions(List<String> tableNames) {
        List<String> tableExists = new ArrayList<>();
        List<String> tableNotExists = new ArrayList<>();

        for (String tableName: tableNames) {
            if (databaseUtils.tableExists(tableName)) {
                tableExists.add(tableName);
            } else {
                tableNotExists.add(tableName);
            }
        }

        return Pair.of(tableExists, tableNotExists);
    }

    public boolean isValidRollbackEpoch(int epoch) {
        return epoch >= 1;
    }

    private String buildDeleteSql(RollbackConfig.TableRollbackDefinition tableDef) {
        validateCondition(tableDef.getCondition());
        String deleteFilter = buildDeleteFilter(tableDef.getCondition());
        return "DELETE FROM " + tableDef.getName() + " WHERE " + deleteFilter;
    }

    private String buildUpdateSql(RollbackConfig.TableRollbackDefinition tableDef) {
        validateCondition(tableDef.getCondition());
        
        if (tableDef.getUpdateSet() == null || tableDef.getUpdateSet().isEmpty()) {
            throw new IllegalArgumentException("UPDATE operation requires 'update_set' clause for table: " + tableDef.getName());
        }
        
        StringBuilder updateSetClause = new StringBuilder();
        for (int i = 0; i < tableDef.getUpdateSet().size(); i++) {
            RollbackConfig.TableRollbackDefinition.UpdateSet update = tableDef.getUpdateSet().get(i);
            updateSetClause.append(update.getColumn()).append(" = ").append(update.getValue());
            if (i < tableDef.getUpdateSet().size() - 1) {
                updateSetClause.append(", ");
            }
        }
        String conditionFilter = buildUpdateConditionFilter(tableDef.getCondition());
        return String.format("UPDATE %s SET %s WHERE %s", tableDef.getName(), updateSetClause.toString(), conditionFilter);
    }

    private String buildDeleteFilter(RollbackConfig.TableRollbackDefinition.Condition condition) {
        String column = condition.getColumn();
        String operator = condition.getOperator();
        String type = condition.getType();
        Integer offset = condition.getOffset();

        if ("epoch".equalsIgnoreCase(type)) {
            if (offset != null && offset != 0) {
                return String.format("%s %s (:epoch + %d)", column, operator, offset);
            } else {
                return String.format("%s %s :epoch", column, operator);
            }
        } else if ("slot".equalsIgnoreCase(type)) {
            if (offset != null && offset != 0) {
                return String.format("%s %s (:slot + %d)", column, operator, offset);
            } else {
                return String.format("%s %s :slot", column, operator);
            }
        } else {
            return "1=1"; // Should not happen with proper config
        }
    }

    private String buildUpdateConditionFilter(RollbackConfig.TableRollbackDefinition.Condition condition) {
        String column = condition.getColumn();
        String operator = condition.getOperator();
        String type = condition.getType();
        Integer offset = condition.getOffset();

        if ("epoch".equalsIgnoreCase(type)) {
            if (offset != null) {
                return String.format("%s %s (:epoch + %d)", column, operator, offset);
            } else {
                return String.format("%s %s :epoch", column, operator);
            }
        } else if ("slot".equalsIgnoreCase(type)) {
            if (offset != null) {
                return String.format("%s %s (:slot + %d)", column, operator, offset);
            } else {
                return String.format("%s %s :slot", column, operator);
            }
        } else {
            return "1=1"; // Should not happen for update conditions
        }
    }

    private void validateCondition(RollbackConfig.TableRollbackDefinition.Condition condition) {
        String column = condition.getColumn();
        String operator = condition.getOperator();
        
        if (column == null || column.trim().isEmpty()) {
            throw new IllegalArgumentException("Column name cannot be null or empty");
        }
        
        if (operator == null || operator.trim().isEmpty()) {
            throw new IllegalArgumentException("Operator cannot be null or empty");
        }
    }

    private void rollbackCursor(RollbackBlock rollbackBlock, long eventPublisherId, boolean transactionSafe) {
        // TRUNCATE implicitly commits on H2/MySQL. Current and source restoration must stay atomic.
        String clearCursor = transactionSafe ? "DELETE FROM cursor_" : "TRUNCATE TABLE cursor_";
        log.info("Clearing cursor_ table: {}", clearCursor);
        jdbcTemplate.getJdbcTemplate().update(clearCursor);

        String insertCursor =
                "INSERT INTO cursor_ (id, block_hash, slot, block_number, era) " +
                        "VALUES (:id, :block_hash, :slot, :block_number, :era)";
        log.info("Inserting into cursor_: {}", insertCursor);

        var params = new MapSqlParameterSource();
        params.addValue("id", eventPublisherId);
        params.addValue("slot", rollbackBlock.getSlot());
        params.addValue("block_number", rollbackBlock.getNumber());
        params.addValue("block_hash", rollbackBlock.getHash());
        params.addValue("era", rollbackBlock.getEra());

        jdbcTemplate.update(insertCursor, params);
    }

    private void rollbackAccountConfig(RollbackBlock rollbackBlock, boolean transactionSafe) {
        if (databaseUtils.tableExists("account_config")) {

            String truncateAccCfg = transactionSafe ? "DELETE FROM account_config" : "TRUNCATE TABLE account_config";
            log.info("Clearing account_config: {}", truncateAccCfg);

            jdbcTemplate.getJdbcTemplate().update(truncateAccCfg);

            String insertAccCfg =
                    "INSERT INTO account_config (config_id, status, slot, block, block_hash) " +
                            "VALUES ('last_account_balance_processed_block', null, :slot, :block_number, :block_hash)";
            log.info("Inserting into account_config: {}", insertAccCfg);

            jdbcTemplate.update(insertAccCfg, new MapSqlParameterSource()
                    .addValue("slot", rollbackBlock.getSlot())
                    .addValue("block_number", rollbackBlock.getNumber())
                    .addValue("block_hash", rollbackBlock.getHash()));
        }
    }
}
