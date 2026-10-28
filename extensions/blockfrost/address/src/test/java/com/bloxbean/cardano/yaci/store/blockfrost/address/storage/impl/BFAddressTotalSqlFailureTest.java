package com.bloxbean.cardano.yaci.store.blockfrost.address.storage.impl;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.jooq.ExceptionTranslatorExecuteListener;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLTimeoutException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BFAddressTotalSqlFailureTest {
    private static final String ADDRESS = "addr1qy";
    private static final String ASSET = "asset";

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void sqlFailureAtAnyTotalsReadPropagates(int failingRead) throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (MockConnection connection = new MockConnection(context -> {
            int read = reads.incrementAndGet();
            if (read == failingRead) {
                throw new SQLTimeoutException("canceling statement due to statement timeout", "57014");
            }
            return successfulResult(read);
        })) {
            BFAddressStorageReaderImpl reader = new BFAddressStorageReaderImpl(translatedDsl(connection));

            assertThatThrownBy(() -> reader.getAddressTotal(ADDRESS))
                    .isInstanceOf(DataAccessException.class)
                    .hasRootCauseInstanceOf(SQLTimeoutException.class)
                    .hasRootCauseMessage("canceling statement due to statement timeout");
        }
    }

    @Test
    void healthyEmptyReadsReturnZeroTotalsForAbsentAddress() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (MockConnection connection = new MockConnection(context ->
                emptyResult(reads.incrementAndGet()))) {
            BFAddressStorageReaderImpl reader = new BFAddressStorageReaderImpl(translatedDsl(connection));

            var total = reader.getAddressTotal(ADDRESS).orElseThrow();

            assertThat(total.receivedSum()).isEmpty();
            assertThat(total.sentSum()).isEmpty();
            assertThat(total.txCount()).isZero();
        }
    }

    @Test
    void healthyReadsPreserveReceivedSentAndTransactionTotals() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (MockConnection connection = new MockConnection(context ->
                successfulResult(reads.incrementAndGet()))) {
            BFAddressStorageReaderImpl reader = new BFAddressStorageReaderImpl(translatedDsl(connection));

            var total = reader.getAddressTotal(ADDRESS).orElseThrow();

            assertThat(total.receivedSum()).isEqualTo(Map.of(
                    "lovelace", BigInteger.valueOf(300), ASSET, BigInteger.valueOf(7)));
            assertThat(total.sentSum()).isEqualTo(Map.of(
                    "lovelace", BigInteger.valueOf(100), ASSET, BigInteger.valueOf(3)));
            assertThat(total.txCount()).isEqualTo(3);
        }
    }

    private DSLContext translatedDsl(MockConnection connection) {
        // Use the production Spring Boot translator, so JDBC failures reach the
        // same Spring DataAccessException boundary as the application's DSLContext.
        return DSL.using(new DefaultConfiguration()
                .set(connection)
                .set(SQLDialect.POSTGRES)
                .set(new DefaultExecuteListenerProvider(ExceptionTranslatorExecuteListener.DEFAULT)));
    }

    private MockResult[] successfulResult(int read) {
        DSLContext records = DSL.using(SQLDialect.POSTGRES);
        if (read == 3) {
            var count = DSL.field("count", Long.class);
            var result = records.newResult(count);
            result.add(records.newRecord(count).value1(3L));
            return new MockResult[]{new MockResult(1, result)};
        }
        var unit = DSL.field("unit", String.class);
        var quantity = DSL.field("quantity", BigDecimal.class);
        var result = records.newResult(unit, quantity);
        result.add(records.newRecord(unit, quantity).values("lovelace", BigDecimal.valueOf(read == 1 ? 300 : 100)));
        result.add(records.newRecord(unit, quantity).values(ASSET, BigDecimal.valueOf(read == 1 ? 7 : 3)));
        return new MockResult[]{new MockResult(2, result)};
    }

    private MockResult[] emptyResult(int read) {
        DSLContext records = DSL.using(SQLDialect.POSTGRES);
        if (read == 3) {
            var count = DSL.field("count", Long.class);
            var result = records.newResult(count);
            result.add(records.newRecord(count).value1(0L));
            return new MockResult[]{new MockResult(1, result)};
        }
        var result = records.newResult(DSL.field("unit", String.class), DSL.field("quantity", BigDecimal.class));
        return new MockResult[]{new MockResult(0, result)};
    }
}
