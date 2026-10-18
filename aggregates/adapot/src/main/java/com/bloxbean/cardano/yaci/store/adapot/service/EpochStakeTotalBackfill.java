package com.bloxbean.cardano.yaci.store.adapot.service;

import com.bloxbean.cardano.yaci.store.core.annotation.ReadOnly;
import lombok.extern.slf4j.Slf4j;
import org.jooq.SQLDialect;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/** One snapshot per bounded transaction; never runs on an API request. */
@Component
@ReadOnly(false)
@ConditionalOnProperty(value = "store.adapot.epoch-stake-total-backfill-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class EpochStakeTotalBackfill {
    private final EpochStakeTotalService totals;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final boolean postgres;
    private boolean complete;
    private long lastFailureLog;

    public EpochStakeTotalBackfill(EpochStakeTotalService totals, NamedParameterJdbcTemplate jdbc,
                                  PlatformTransactionManager transactionManager, DSLContext dsl,
                                  @Value("${store.adapot.epoch-stake-total-backfill-timeout-seconds:3}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("epoch-stake-total-backfill-timeout-seconds must be between 1 and 30");
        }
        this.totals = totals;
        this.jdbc = jdbc;
        this.postgres = dsl.dialect().family() == SQLDialect.POSTGRES;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(timeoutSeconds);
    }

    @Scheduled(fixedDelayString = "${store.adapot.epoch-stake-total-backfill-interval-ms:1000}", initialDelay = 30000)
    public void backfillNext() {
        try {
            Integer snapshot = transaction.execute(status -> {
                if (postgres) {
                    // JDBC transaction timeouts alone may not bound a blocked
                    // statement; server limits also cancel lock/scan work.
                    jdbc.queryForObject("SELECT set_config('statement_timeout', :timeout, true)",
                            Map.of("timeout", transaction.getTimeout() + "s"), String.class);
                    jdbc.queryForObject("SELECT set_config('lock_timeout', '250ms', true)", Map.of(), String.class);
                }
                totals.lock();
                var next = totals.findNextMissingSnapshot();
                next.ifPresent(totals::refresh);
                return next.orElse(null);
            });
            if (snapshot == null) {
                if (!complete) log.info("Epoch stake total backfill ready: no missing snapshots");
                complete = true;
            } else {
                complete = false;
                log.info("Published epoch stake total for snapshot epoch {}", snapshot);
            }
        } catch (RuntimeException failure) {
            complete = false;
            long now = System.currentTimeMillis();
            if (now - lastFailureLog >= 60000) {
                lastFailureLog = now;
                log.warn("Epoch stake total backfill will retry after bounded transaction failure: {}",
                        failure.getClass().getSimpleName());
            }
        }
    }
}
