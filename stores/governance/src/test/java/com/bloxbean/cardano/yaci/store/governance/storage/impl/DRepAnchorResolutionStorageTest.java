package com.bloxbean.cardano.yaci.store.governance.storage.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Uses either an explicit disposable local PostgreSQL or Testcontainers, never production. */
class DRepAnchorResolutionStorageTest {
    static org.testcontainers.containers.PostgreSQLContainer<?> postgres;
    Connection connection; DSLContext dsl; String schema;
    DRepAnchorResolutionStorageImpl storage;
    static final String TX = "aa".repeat(32), ID = "drep1native", HASH = "11".repeat(28);
    static final byte[] RAW = "{ \"number\" : 1.00 }\n".getBytes(StandardCharsets.UTF_8);
    static final String BODY_HASH = "4cfa503d02e94541f3511c2e2322248be7abe3564420294c17a857c7bc0649ce";
    DRepAnchorResolutionStorageImpl.Binding binding;
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
        schema = "anchor_" + UUID.randomUUID().toString().replace("-", "");
        dsl.execute("CREATE SCHEMA " + schema);
        dsl.execute("SET search_path TO " + schema);
        dsl.execute("CREATE TABLE drep_registration(tx_hash varchar(64),cert_index integer,drep_id varchar(255),drep_hash varchar(56),cred_type varchar(40),slot bigint,anchor_url text,anchor_hash varchar(64),PRIMARY KEY(tx_hash,cert_index))");
        String sql = new String(getClass().getResourceAsStream("/db/governance-anchor-resolution/postgresql.sql").readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) if (!statement.isBlank()) dsl.execute(statement);
        dsl.execute("INSERT INTO drep_registration VALUES(?,0,?,?,'ADDR_KEYHASH',100,'https://example.com/m',?)",TX,ID,HASH,BODY_HASH);
        storage = new DRepAnchorResolutionStorageImpl(dsl, new ObjectMapper(),524288);
        binding = new DRepAnchorResolutionStorageImpl.Binding(TX,0,ID,HASH,"ADDR_KEYHASH",100,"https://example.com/m",BODY_HASH);
    }
    @AfterEach void close() throws Exception { connection.close(); }
    @Test void verifiedRawBytesRoundtripAndFailuresNeverDowngradeSuccess() {
        assertThat(storage.ensurePending(binding)).isTrue();
        assertThat(storage.read(binding)).isEmpty();
        var lease = storage.claim(binding, UUID.randomUUID().toString(), 60).orElseThrow();
        assertThat(storage.completeSuccess(binding, lease, RAW, nativeProvenance(), nativeSuccess(binding))).isTrue();
        var result = storage.read(binding).orElseThrow();
        assertThat(result.bytes()).isEqualTo("\\x" + java.util.HexFormat.of().formatHex(RAW));
        assertThat(result.json().path("number").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(result.error()).isNull();
        assertThat(storage.claim(binding, UUID.randomUUID().toString(),60)).isEmpty();
        assertThat(storage.completeFailure(binding, lease,List.of(),nativeProvenance(),"HASH_MISMATCH","later failure")).isFalse();
        assertThat(storage.read(binding).orElseThrow().bytes()).isEqualTo(result.bytes());
    }
    @Test void badHashCannotPublishAndRollbackCannotResurrectResolution() {
        storage.ensurePending(binding);var lease=storage.claim(binding, UUID.randomUUID().toString(),60).orElseThrow();
        assertThatThrownBy(() -> storage.completeSuccess(binding,lease,"{}".getBytes(StandardCharsets.UTF_8),nativeProvenance(),nativeSuccess(binding))).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.read(binding)).isEmpty();
        dsl.execute("DELETE FROM drep_registration WHERE tx_hash=?",TX);
        assertThat(storage.completeSuccess(binding,lease,RAW,nativeProvenance(),nativeSuccess(binding))).isFalse();
        assertThat(dsl.fetchOne("SELECT count(*) FROM drep_anchor_resolution").get(0,Integer.class)).isZero();
        assertThat(dsl.fetchOne("SELECT count(*) FROM drep_anchor_fetch_attempt").get(0,Integer.class)).isZero();
    }
    @Test void historicalReferenceRetainsOpaqueProvenanceAndRetryKeepsVisibleGeneration() {
        storage.ensurePending(binding);var lease=storage.claim(binding, UUID.randomUUID().toString(),60).orElseThrow();
        var attempt=DRepAnchorResolutionStorageImpl.Attempt.reference("HASH_MISMATCH","exact historical429/504 message","ff".repeat(32));
        assertThat(storage.completeFailure(binding,lease,List.of(attempt),retainedProvenance(),"HASH_MISMATCH",attempt.message())).isTrue();
        assertThat(storage.read(binding).orElseThrow().error().message()).isEqualTo(attempt.message());
        var retry=storage.claim(binding,UUID.randomUUID().toString(),60).orElseThrow();
        assertThat(retry.generation()).isEqualTo(2);
        assertThat(storage.read(binding).orElseThrow().error().message()).isEqualTo(attempt.message());
        assertThat(storage.completeSuccess(binding,retry,RAW,nativeProvenance(),nativeSuccess(binding))).isTrue();
        assertThat(storage.read(binding).orElseThrow().error()).isNull();
        var retained=dsl.fetchOne("SELECT result_kind,candidate_url,observed_http_status,started_at,source_receipt_sha256 FROM drep_anchor_fetch_attempt WHERE resolution_generation=1");
        assertThat(retained.get("result_kind")).isEqualTo("REFERENCE_OUTCOME");
        assertThat(retained.get("candidate_url")).isNull();assertThat(retained.get("observed_http_status")).isNull();assertThat(retained.get("started_at")).isNull();
        assertThat(retained.get("source_receipt_sha256")).isEqualTo("ff".repeat(32));
    }
    @Test void changedAnchorOrCredentialAndForeignLeaseCannotPublish() {
        storage.ensurePending(binding);var lease=storage.claim(binding, UUID.randomUUID().toString(),60).orElseThrow();
        assertThat(storage.completeSuccess(binding,new DRepAnchorResolutionStorageImpl.Lease(lease.generation(),UUID.randomUUID().toString()),RAW,nativeProvenance(),nativeSuccess(binding))).isFalse();
        dsl.execute("UPDATE drep_registration SET anchor_url='https://example.com/changed'");
        assertThat(storage.completeSuccess(binding,lease,RAW,nativeProvenance(),nativeSuccess(binding))).isFalse();
        assertThat(storage.read(binding)).isEmpty();
    }
    @Test void hashValidMalformedTrailingRootsAndOversizeCannotPublish() {
        for (String body : List.of("{\"x\":1} garbage", "{\"x\":1} {\"x\":2}")) {
            byte[] raw=body.getBytes(StandardCharsets.UTF_8);
            String hash=java.util.HexFormat.of().formatHex(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(raw));
            dsl.execute("UPDATE drep_registration SET anchor_hash=?",hash);
            var current=new DRepAnchorResolutionStorageImpl.Binding(TX,0,ID,HASH,"ADDR_KEYHASH",100,binding.url(),hash);
            storage.ensurePending(current);var lease=storage.claim(current,UUID.randomUUID().toString(),60).orElseThrow();
            assertThatThrownBy(()->storage.completeSuccess(current,lease,raw,nativeProvenance(),nativeSuccess(current)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Valid original JSON");
            assertThat(storage.read(current)).isEmpty();
            dsl.execute("DELETE FROM drep_anchor_resolution");
        }
        dsl.execute("UPDATE drep_registration SET anchor_hash=?",BODY_HASH);
        var limited=new DRepAnchorResolutionStorageImpl(dsl,new ObjectMapper(),RAW.length-1);
        limited.ensurePending(binding);var lease=limited.claim(binding,UUID.randomUUID().toString(),60).orElseThrow();
        assertThatThrownBy(()->limited.completeSuccess(binding,lease,RAW,nativeProvenance(),nativeSuccess(binding)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Bounded original");
        assertThat(limited.read(binding)).isEmpty();
    }
    @Test void importedSuccessPreservesCaptureTimeAndNativeSuccessRetainsActualAttempt() {
        storage.ensurePending(binding);var lease=storage.claim(binding,UUID.randomUUID().toString(),60).orElseThrow();
        var imported=DRepAnchorResolutionStorageImpl.Attempt.reference(null,null,"ff".repeat(32));
        assertThat(storage.completeSuccess(binding,lease,RAW,retainedProvenance(),List.of(imported))).isTrue();
        var resolution=dsl.fetchOne("SELECT observed_at,source_receipt_sha256,source_item_ordinal FROM drep_anchor_resolution");
        assertThat(resolution.get("observed_at",java.sql.Timestamp.class).toInstant()).isEqualTo(retainedProvenance().observed());
        assertThat(resolution.get("source_receipt_sha256")).isEqualTo(retainedProvenance().receipt());
        assertThat(resolution.get("source_item_ordinal",Integer.class)).isZero();
        assertThat(dsl.fetchOne("SELECT observed_http_status,started_at FROM drep_anchor_fetch_attempt").get("observed_http_status")).isNull();
        dsl.execute("DELETE FROM drep_anchor_resolution");
        storage.ensurePending(binding);lease=storage.claim(binding,UUID.randomUUID().toString(),60).orElseThrow();
        storage.completeSuccess(binding,lease,RAW,nativeProvenance(),nativeSuccess(binding));
        var nativeRow=dsl.fetchOne("SELECT candidate_url,result_kind,observed_hash,observed_size,started_at,completed_at FROM drep_anchor_fetch_attempt");
        assertThat(nativeRow.get("candidate_url")).isEqualTo(binding.url());assertThat(nativeRow.get("result_kind")).isEqualTo("SUCCESS");
        assertThat(nativeRow.get("observed_hash")).isEqualTo(BODY_HASH);assertThat(nativeRow.get("observed_size",Long.class)).isEqualTo(RAW.length);
        assertThat(nativeRow.get("started_at",java.sql.Timestamp.class).toInstant()).isEqualTo(nativeSuccess(binding).getFirst().started());
    }
    @Test void attemptInsertFailureRollsBackTheEntireSelectedOutcome() {
        storage.ensurePending(binding);var lease=storage.claim(binding,UUID.randomUUID().toString(),60).orElseThrow();
        var reference=DRepAnchorResolutionStorageImpl.Attempt.reference("HASH_MISMATCH","first failure","ff".repeat(32));
        dsl.execute("ALTER TABLE drep_anchor_fetch_attempt ADD CHECK(attempt_ordinal<1)");
        assertThatThrownBy(()->storage.completeFailure(binding,lease,List.of(reference,reference),retainedProvenance(),reference.code(),reference.message()))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(dsl.fetchOne("SELECT count(*) FROM drep_anchor_fetch_attempt").get(0,Integer.class)).isZero();
        assertThat(dsl.fetchOne("SELECT state FROM drep_anchor_resolution").get(0,String.class)).isEqualTo("PENDING");
        assertThat(storage.completeFailure(binding,lease,List.of(reference),retainedProvenance(),reference.code(),reference.message())).isTrue();
    }
    @Test void competingReplicasClaimOnceAndExpiredLeaseCannotPublish() throws Exception {
        assertThat(storage.ensurePending(binding)).isTrue();assertThat(storage.ensurePending(binding)).isTrue();
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<java.util.Optional<DRepAnchorResolutionStorageImpl.Lease>>>();
            for(int i=0;i<2;i++)tasks.add(executor.submit(()->{
                String url=System.getenv("YACI_DREP_TEST_JDBC_URL");
                try(var c=DriverManager.getConnection(url==null?postgres.getJdbcUrl():url,url==null?postgres.getUsername():"postgres",url==null?postgres.getPassword():"")) {
                    var other=DSL.using(c,SQLDialect.POSTGRES);other.execute("SET search_path TO "+schema);
                    var replica=new DRepAnchorResolutionStorageImpl(other,new ObjectMapper(),524288);
                    ready.countDown();start.await(5,java.util.concurrent.TimeUnit.SECONDS);
                    return replica.claim(binding,UUID.randomUUID().toString(),60);
                }
            }));
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=List.of(tasks.get(0).get(5,java.util.concurrent.TimeUnit.SECONDS),tasks.get(1).get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
            var first=results.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
            dsl.execute("UPDATE drep_anchor_resolution SET lease_until=clock_timestamp()-INTERVAL '1 second'");
            var replacement=storage.claim(binding,UUID.randomUUID().toString(),60).orElseThrow();
            assertThat(replacement.generation()).isEqualTo(first.generation()+1);
            assertThat(storage.completeSuccess(binding,first,RAW,nativeProvenance(),nativeSuccess(binding))).isFalse();
            assertThat(storage.completeSuccess(binding,replacement,RAW,nativeProvenance(),nativeSuccess(binding))).isTrue();
            assertThat(dsl.fetchOne("SELECT count(*) FROM drep_anchor_fetch_attempt").get(0,Integer.class)).isEqualTo(1);
        } finally {start.countDown();executor.shutdownNow();}
    }
    @Test void validDocumentAboveOldHalfMegabyteCapWorksAndAboveBlockfrostCeilingFails() {
        var compatible=new DRepAnchorResolutionStorageImpl(dsl,new ObjectMapper(),3000000);
        byte[] raw=("{\"content\":\""+"a".repeat(600000)+"\"}").getBytes(StandardCharsets.UTF_8);
        String hash=java.util.HexFormat.of().formatHex(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(raw));
        dsl.execute("UPDATE drep_registration SET anchor_hash=?",hash);
        var current=new DRepAnchorResolutionStorageImpl.Binding(TX,0,ID,HASH,"ADDR_KEYHASH",100,binding.url(),hash);
        compatible.ensurePending(current);var lease=compatible.claim(current,UUID.randomUUID().toString(),60).orElseThrow();
        var observed=new DRepAnchorResolutionStorageImpl.Attempt(binding.url(),"SUCCESS",null,null,200,hash,"application/json",(long)raw.length,nativeProvenance().observed(),nativeProvenance().observed(),"NATIVE_FETCH",null);
        assertThat(compatible.completeSuccess(current,lease,raw,nativeProvenance(),List.of(observed))).isTrue();
        assertThat(compatible.read(current).orElseThrow().bytes()).isEqualTo("\\x"+java.util.HexFormat.of().formatHex(raw));
        byte[] oversize=("{\"content\":\""+"a".repeat(3000000)+"\"}").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->compatible.completeSuccess(current,lease,oversize,nativeProvenance(),List.of(observed))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Bounded original");
    }
    @Test void retainedIntervalDoesNotInventExactObservationOrNativeAttemptTime() {
        storage.ensurePending(binding);var lease=storage.claim(binding,UUID.randomUUID().toString(),60).orElseThrow();
        var interval=DRepAnchorResolutionStorageImpl.Provenance.retainedInterval("ff".repeat(32),0,java.time.Instant.parse("2026-10-18T07:29:19.979722Z"),java.time.Instant.parse("2026-10-18T07:29:37.522775Z"));
        var imported=DRepAnchorResolutionStorageImpl.Attempt.reference(null,null,interval.receipt());
        assertThat(storage.completeSuccess(binding,lease,RAW,interval,List.of(imported))).isTrue();
        var row=dsl.fetchOne("SELECT observed_at,source_capture_started_at,source_capture_completed_at FROM drep_anchor_resolution");
        assertThat(row.get("observed_at")).isNull();
        assertThat(row.get("source_capture_started_at",java.sql.Timestamp.class).toInstant()).isEqualTo(interval.captureStarted());
        assertThat(row.get("source_capture_completed_at",java.sql.Timestamp.class).toInstant()).isEqualTo(interval.captureCompleted());
        assertThat(dsl.fetchOne("SELECT started_at,completed_at FROM drep_anchor_fetch_attempt").get("started_at")).isNull();
    }
    @Test void lockContentionTimesOutWithoutPublishingOrHoldingChainRollback() throws Exception {
        storage.ensurePending(binding);var lease=storage.claim(binding,UUID.randomUUID().toString(),60).orElseThrow();
        String url=System.getenv("YACI_DREP_TEST_JDBC_URL");
        try(var blocking=DriverManager.getConnection(url==null?postgres.getJdbcUrl():url,url==null?postgres.getUsername():"postgres",url==null?postgres.getPassword():"")) {
            var lock=DSL.using(blocking,SQLDialect.POSTGRES);lock.execute("SET search_path TO "+schema);blocking.setAutoCommit(false);
            lock.fetch("SELECT * FROM drep_registration WHERE tx_hash=? FOR UPDATE",TX);
            long started=System.nanoTime();
            assertThatThrownBy(()->storage.completeSuccess(binding,lease,RAW,nativeProvenance(),nativeSuccess(binding))).isInstanceOf(org.jooq.exception.DataAccessException.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofSeconds(2));
            assertThat(dsl.fetchOne("SELECT count(*) FROM drep_anchor_fetch_attempt").get(0,Integer.class)).isZero();
            assertThat(dsl.fetchOne("SELECT state FROM drep_anchor_resolution").get(0,String.class)).isEqualTo("PENDING");
            blocking.rollback();
        }
        dsl.execute("DELETE FROM drep_registration WHERE tx_hash=?",TX);
        assertThat(storage.completeSuccess(binding,lease,RAW,nativeProvenance(),nativeSuccess(binding))).isFalse();
    }
    @Test void featureRejectsUnsupportedDatabaseBeforeAnyWrites() {
        var mysql=DSL.using(org.jooq.SQLDialect.MYSQL);
        assertThatThrownBy(()->new DRepAnchorResolutionStorageImpl(mysql,new ObjectMapper(),3000000)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PostgreSQL");
    }
    private static DRepAnchorResolutionStorageImpl.Provenance nativeProvenance() {return DRepAnchorResolutionStorageImpl.Provenance.nativeFetch(java.time.Instant.parse("2026-10-18T07:00:00Z"));}
    private static DRepAnchorResolutionStorageImpl.Provenance retainedProvenance() {return DRepAnchorResolutionStorageImpl.Provenance.retained("ff".repeat(32),0,java.time.Instant.parse("2026-10-18T06:00:00Z"));}
    private static List<DRepAnchorResolutionStorageImpl.Attempt> nativeSuccess(DRepAnchorResolutionStorageImpl.Binding b) {return List.of(new DRepAnchorResolutionStorageImpl.Attempt(b.url(),"SUCCESS",null,null,200,BODY_HASH,"application/json",(long)RAW.length,java.time.Instant.parse("2026-10-18T06:59:59Z"),java.time.Instant.parse("2026-10-18T07:00:00Z"),"NATIVE_FETCH",null));}
}
