package com.bloxbean.cardano.yaci.store.governance.storage.impl;

import com.bloxbean.cardano.yaci.store.governance.service.DRepAnchorResolverWork;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Disposable local PostgreSQL or Testcontainers only; the discovery SQL is actually executed. */
class DRepAnchorResolverDiscoveryTest {
    static org.testcontainers.containers.PostgreSQLContainer<?> postgres;
    Connection connection;
    DSLContext dsl;
    DRepAnchorResolverWork work;
    DRepAnchorResolutionStorageImpl storage;
    String schema;
    @BeforeAll static void start() {
        if (System.getenv("YACI_DREP_TEST_JDBC_URL") == null) {
            postgres = new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine"); postgres.start();
        }
    }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }
    @BeforeEach void setup() throws Exception {
        String url = System.getenv("YACI_DREP_TEST_JDBC_URL");
        connection = DriverManager.getConnection(url == null ? postgres.getJdbcUrl() : url,
                url == null ? postgres.getUsername() : "postgres", url == null ? postgres.getPassword() : "");
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
        schema = "resolver_" + UUID.randomUUID().toString().replace("-", "");
        dsl.execute("CREATE SCHEMA " + schema); dsl.execute("SET search_path TO " + schema);
        dsl.execute("CREATE TABLE drep_registration(tx_hash varchar(64),cert_index integer,drep_id varchar(255),drep_hash varchar(56),cred_type varchar(40),slot bigint,tx_index integer,anchor_url text,anchor_hash varchar(64),PRIMARY KEY(tx_hash,cert_index))");
        String sql = new String(getClass().getResourceAsStream("/db/governance-anchor-resolution/postgresql.sql").readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) if (!statement.isBlank()) dsl.execute(statement);
        storage = new DRepAnchorResolutionStorageImpl(dsl, new ObjectMapper(), 3000000);
        work = new DRepAnchorResolverWork(dsl);
    }
    @AfterEach void close() throws Exception { connection.close(); }
    private DRepAnchorResolutionStorageImpl.Binding insert(int number, String hash) {
        String tx = String.format("%064x", number);
        var binding = new DRepAnchorResolutionStorageImpl.Binding(tx, 0, "drep" + number, "bb".repeat(28), "ADDR_KEYHASH", 100 + number, "https://example.com/" + number, hash);
        dsl.execute("INSERT INTO drep_registration VALUES(?,0,?,?,'ADDR_KEYHASH',?,0,?,?)", tx, binding.drepId(), binding.drepHash(), binding.slot(), binding.url(), hash);
        return binding;
    }
    @Test void boundedDiscoverySkipsInvalidAnchorsSuccessAndBusyOrNonDueRows() {
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);
        var hash = java.util.HexFormat.of().formatHex(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(raw));
        var due = insert(1, hash); var busy = insert(2, hash); var delayed = insert(3, hash); var success = insert(4, hash);
        insert(5, "bad"); insert(6, null);
        for (var binding : java.util.List.of(due, busy, delayed, success)) storage.ensurePending(binding);
        storage.claim(busy, UUID.randomUUID().toString(), 60).orElseThrow();
        publishFailure(delayed);
        work.retryAfter(delayed, 1, 60);
        var successLease = storage.claim(success, UUID.randomUUID().toString(), 60).orElseThrow();
        var now = java.time.Instant.now();
        var attempt = new DRepAnchorResolutionStorageImpl.Attempt(success.url(), "SUCCESS", null, null, 200, hash, "application/json", (long)raw.length, now, now, "NATIVE_FETCH", null);
        storage.completeSuccess(success, successLease, raw, DRepAnchorResolutionStorageImpl.Provenance.nativeFetch(now), java.util.List.of(attempt));
        assertThat(work.discover(16)).containsExactly(due);
        dsl.execute("UPDATE drep_anchor_resolution SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE tx_hash=?", busy.txHash());
        assertThat(work.discover(1)).containsExactly(busy);
        assertThatThrownBy(() -> work.discover(17)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retryDelayUsesDatabaseClockAndOnlyPublishedGeneration() {
        var binding = insert(1, "cc".repeat(32)); storage.ensurePending(binding);
        publishFailure(binding); publishFailure(binding); publishFailure(binding);
        work.retryAfter(binding, 2, 60);
        assertThat(dsl.fetchOne("SELECT next_attempt_at FROM drep_anchor_resolution").get(0)).isNull();
        work.retryAfter(binding, 3, 60);
        assertThat(dsl.fetchOne("SELECT next_attempt_at>clock_timestamp()+INTERVAL '59 seconds' FROM drep_anchor_resolution").get(0, Boolean.class)).isTrue();
        assertThat(work.discover(16)).isEmpty();
        assertThatThrownBy(() -> work.retryAfter(binding, 3, 1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void networkRunsOutsideTransactionsAndRollbackDuringFetchCannotPublish() {
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);
        var hash = java.util.HexFormat.of().formatHex(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(raw));
        var binding = insert(1, hash);
        var fetcher = org.mockito.Mockito.mock(com.bloxbean.cardano.yaci.store.governance.service.DRepAnchorHttpFetcher.class);
        org.mockito.Mockito.when(fetcher.fetch(org.mockito.ArgumentMatchers.eq(binding), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            assertThat(connection.getAutoCommit()).isTrue();
            assertThat(dsl.fetchOne("SELECT lease_token IS NOT NULL FROM drep_anchor_resolution").get(0, Boolean.class)).isTrue();
            dsl.execute("DELETE FROM drep_registration WHERE tx_hash=?", binding.txHash());
            var now = java.time.Instant.now();
            var attempt = new DRepAnchorResolutionStorageImpl.Attempt(binding.url(), "SUCCESS", null, null, 200, hash, "application/json", (long)raw.length, now, now, "NATIVE_FETCH", null);
            return new com.bloxbean.cardano.yaci.store.governance.service.DRepAnchorHttpFetcher.Result(raw, java.util.List.of(attempt), null, null);
        });
        new com.bloxbean.cardano.yaci.store.governance.service.DRepAnchorResolver(storage, fetcher, work, new com.bloxbean.cardano.yaci.store.governance.service.DRepAnchorResolverProperties()).resolveBatch();
        org.mockito.Mockito.verify(fetcher).fetch(org.mockito.ArgumentMatchers.eq(binding), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(dsl.fetchOne("SELECT count(*) FROM drep_anchor_resolution").get(0, Integer.class)).isZero();
        assertThat(dsl.fetchOne("SELECT count(*) FROM drep_anchor_fetch_attempt").get(0, Integer.class)).isZero();
    }
    private void publishFailure(DRepAnchorResolutionStorageImpl.Binding binding) {
        var lease = storage.claim(binding, UUID.randomUUID().toString(), 60).orElseThrow();
        var now = java.time.Instant.now();
        var attempt = new DRepAnchorResolutionStorageImpl.Attempt(binding.url(), "HTTP_RESPONSE_ERROR", "HTTP_RESPONSE_ERROR", "HTTP status 503", 503, null, "text/plain", null, now, now, "NATIVE_FETCH", null);
        assertThat(storage.completeFailure(binding, lease, java.util.List.of(attempt), DRepAnchorResolutionStorageImpl.Provenance.nativeFetch(now), attempt.code(), attempt.message())).isTrue();
    }
    @Test void discoveryDdlContentionStopsAtTransactionLocalLockBudget() throws Exception {
        insert(1, "cc".repeat(32));
        String url = System.getenv("YACI_DREP_TEST_JDBC_URL");
        try (var blocking = DriverManager.getConnection(url == null ? postgres.getJdbcUrl() : url,
                url == null ? postgres.getUsername() : "postgres", url == null ? postgres.getPassword() : "")) {
            var lock = DSL.using(blocking, SQLDialect.POSTGRES);
            lock.execute("SET search_path TO " + schema); blocking.setAutoCommit(false);
            lock.execute("LOCK TABLE drep_registration IN ACCESS EXCLUSIVE MODE");
            long started = System.nanoTime();
            assertThatThrownBy(() -> work.discover(16)).isInstanceOf(org.jooq.exception.DataAccessException.class).hasMessageContaining("lock timeout");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(150L, 2000L);
            blocking.rollback();
        }
        assertThat(connection.getAutoCommit()).isTrue();
        assertThat(dsl.fetchOne("SHOW lock_timeout").get(0, String.class)).isEqualTo("0");
        assertThat(work.discover(16)).hasSize(1);
    }
    @Test void nonPostgresWorkRefusesStartup() {
        assertThatThrownBy(() -> new DRepAnchorResolverWork(DSL.using(SQLDialect.H2))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PostgreSQL");
    }
}
