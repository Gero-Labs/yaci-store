package com.bloxbean.cardano.yaci.store.blockfrost.asset.storage;

import org.jooq.DSLContext;
import org.jooq.ExecuteListener;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One bounded operation, shared by the page query, optional existence lookup, and mapping. */
public final class BFAssetQueryDeadline {
    private static final Duration MAX_BUDGET = Duration.ofSeconds(7);
    private final long end;

    public BFAssetQueryDeadline() {
        this(MAX_BUDGET);
    }

    public BFAssetQueryDeadline(Duration budget) {
        if (budget.isNegative() || budget.isZero() || budget.compareTo(MAX_BUDGET) > 0) {
            throw new IllegalArgumentException("Asset query budget must be greater than zero and at most seven seconds");
        }
        end = System.nanoTime() + budget.toNanos();
    }

    public void check() {
        if (end - System.nanoTime() <= 0 || Thread.currentThread().isInterrupted()) throw exceeded(null);
    }

    public int timeoutSeconds() {
        check();
        // JDBC timeouts have whole-second precision. Never round up beyond the shared deadline.
        long remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(end - System.nanoTime());
        if (remainingSeconds < 1) throw exceeded(null);
        return (int) remainingSeconds;
    }

    public DSLContext context(DSLContext source) {
        return DSL.using(source.configuration().deriveAppending(ExecuteListener.onExecuteStart(context -> {
            // Pool acquisition and statement preparation can consume the original timeout. Recheck after
            // those steps, immediately before execution, without replacing the configured listeners.
            int timeout = timeoutSeconds();
            try {
                context.statement().setQueryTimeout(timeout);
            } catch (SQLException e) {
                throw new DataAccessException("Unable to apply asset statement timeout", e);
            }
        })));
    }

    public <T> T execute(Supplier<T> query) {
        check();
        try {
            T result = query.get();
            check();
            return result;
        } catch (DataAccessException e) {
            if ("57014".equals(e.sqlState())) throw exceeded(e);
            throw e;
        }
    }

    private ResponseStatusException exceeded(Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Asset query deadline exceeded", cause);
    }
}
