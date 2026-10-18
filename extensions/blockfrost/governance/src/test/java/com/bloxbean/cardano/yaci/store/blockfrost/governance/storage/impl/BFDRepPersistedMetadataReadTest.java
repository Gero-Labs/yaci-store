package com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl;

import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.mapper.BFDRepMapper;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.util.BFDRepIdentity;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.governance.storage.impl.DRepAnchorResolutionStorageImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class BFDRepPersistedMetadataReadTest {
    static org.testcontainers.containers.PostgreSQLContainer<?> postgres;
    Connection connection; DSLContext dsl;
    ObjectMapper json = new ObjectMapper();
    DRepAnchorResolutionStorageImpl storage;
    BFGovernanceStorageReaderImpl reader;
    static final String HASH="11".repeat(28), ID=GovId.drepFromKeyHash(HexUtil.decodeHexString(HASH));
    static final byte[] RAW="{ \"number\" : 1.00 }\n".getBytes(StandardCharsets.UTF_8);
    static final String BODY_HASH="4cfa503d02e94541f3511c2e2322248be7abe3564420294c17a857c7bc0649ce";
    @BeforeAll static void start() {
        if (System.getenv("YACI_DREP_TEST_JDBC_URL")==null) {
            postgres=new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");postgres.start();
        }
    }
    @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("YACI_DREP_TEST_JDBC_URL");
        connection=DriverManager.getConnection(url==null?postgres.getJdbcUrl():url,url==null?postgres.getUsername():"postgres",url==null?postgres.getPassword():"");
        dsl=DSL.using(connection, SQLDialect.POSTGRES);
        String schema="bf_anchor_"+UUID.randomUUID().toString().replace("-","");
        dsl.execute("CREATE SCHEMA "+schema);dsl.execute("SET search_path TO "+schema);
        dsl.execute("CREATE TABLE drep_registration(tx_hash varchar(64),cert_index int,drep_id varchar(255),drep_hash varchar(56),cred_type varchar(40),slot bigint,anchor_url text,anchor_hash varchar(64),tx_index int,type varchar,epoch int,deposit bigint,PRIMARY KEY(tx_hash,cert_index))");
        String sql=new String(getClass().getResourceAsStream("/db/governance-anchor-resolution/postgresql.sql").readAllBytes(),StandardCharsets.UTF_8);
        for(String statement:sql.split(";"))if(!statement.isBlank())dsl.execute(statement);
        dsl.execute("CREATE TABLE drep(drep_hash varchar,drep_id varchar,status varchar,epoch int,slot bigint,tx_index int,cert_index int)");
        dsl.execute("CREATE TABLE delegation_vote(drep_id varchar,drep_hash varchar,drep_type varchar,slot bigint,tx_index int,cert_index int)");
        dsl.execute("CREATE TABLE voting_procedure(voter_hash varchar,voter_type varchar,slot bigint,tx_index int,idx int,epoch int)");
        dsl.execute("CREATE TABLE drep_dist(drep_id varchar,drep_type varchar,amount bigint,epoch int)");
        dsl.execute("CREATE TABLE local_drep_dist(drep_hash varchar,drep_type varchar,amount bigint,epoch int)");
        dsl.execute("CREATE TABLE epoch_param(epoch int,params json)");
        dsl.execute("INSERT INTO epoch_param VALUES(2,'{\"drep_activity\":20}')");
        storage=new DRepAnchorResolutionStorageImpl(dsl,json,524288);
        reader=new BFGovernanceStorageReaderImpl(dsl,json);reader.setAnchorResolutionStorage(storage);
    }
    @AfterEach void close() throws Exception{connection.close();}
    private DRepAnchorResolutionStorageImpl.Binding anchor(String tx,long slot,int txIndex,int cert,String url) {
        dsl.execute("INSERT INTO drep_registration VALUES(?,?,?,?,?,?,?, ?,?,'REG_DREP_CERT',2,0)",tx,cert,ID,HASH,"ADDR_KEYHASH",slot,url,BODY_HASH,txIndex);
        return new DRepAnchorResolutionStorageImpl.Binding(tx,cert,ID,HASH,"ADDR_KEYHASH",slot,url,BODY_HASH);
    }
    private void success(DRepAnchorResolutionStorageImpl.Binding b) {
        storage.ensurePending(b);var lease=storage.claim(b,UUID.randomUUID().toString(),60).orElseThrow();
        assertThat(storage.completeSuccess(b,lease,RAW,nativeProvenance(),nativeSuccess(b))).isTrue();
    }
    @Test void detailAndListUseTheExactLatestAnchoredCertificateAndOriginalBytes() throws Exception {
        var first=anchor("aa".repeat(32),100,1,9,"https://example.com/old");success(first);
        var latest=anchor("bb".repeat(32),100,2,0,"https://example.com/current");success(latest);
        // Later unanchored certificate preserves the selected anchor.
        anchor("cc".repeat(32),101,0,0,null);
        var detail=BFDRepMapper.INSTANCE.toMetadataDto(reader.findDRepMetadata(BFDRepIdentity.parse(ID)).orElseThrow());
        var list=BFDRepMapper.INSTANCE.toListItemDto(reader.findAllDReps(0,100,Order.asc).getFirst()).getMetadata();
        assertThat(detail.getUrl()).isEqualTo(latest.url());assertThat(list.getUrl()).isEqualTo(latest.url());
        assertThat(detail.getBytes()).isEqualTo("\\x"+java.util.HexFormat.of().formatHex(RAW));assertThat(list.getBytes()).isEqualTo(detail.getBytes());
        assertThat(json.valueToTree(detail).has("error")).isFalse();assertThat(json.valueToTree(list).has("error")).isFalse();
        // No current URL/hash binding may borrow a previously selected raw document.
        dsl.execute("UPDATE drep_registration SET anchor_url='https://example.com/changed' WHERE tx_hash=?",latest.txHash());
        assertThat(reader.findDRepMetadata(BFDRepIdentity.parse(ID)).orElseThrow().getAnchorMetadata()).isNull();
        assertThat(reader.findAllDReps(0,100,Order.asc).getFirst().getAnchorMetadata()).isNull();
    }
    @Test void exactHistoricalErrorIsSharedAcrossDetailAndListAndDisabledReaderNeedsNoTables() {
        var current=anchor("aa".repeat(32),100,0,0,"https://example.com/error");storage.ensurePending(current);
        var lease=storage.claim(current,UUID.randomUUID().toString(),60).orElseThrow();
        var error=DRepAnchorResolutionStorageImpl.Attempt.reference("HASH_MISMATCH","original mismatch; gateway 429; gateway 504","ff".repeat(32));
        storage.completeFailure(current,lease,List.of(error),retainedProvenance(),error.code(),error.message());
        var detail=BFDRepMapper.INSTANCE.toMetadataDto(reader.findDRepMetadata(BFDRepIdentity.parse(ID)).orElseThrow());
        var list=BFDRepMapper.INSTANCE.toListItemDto(reader.findAllDReps(0,100,Order.asc).getFirst()).getMetadata();
        assertThat(detail.getError().message()).isEqualTo(error.message());assertThat(list.getError()).isEqualTo(detail.getError());
        assertThat(detail.getJsonMetadata()).isNull();assertThat(detail.getBytes()).isNull();
        assertThat(list.getJsonMetadata()).isNull();assertThat(list.getBytes()).isNull();
        dsl.execute("DROP TABLE drep_anchor_fetch_attempt");dsl.execute("DROP TABLE drep_anchor_resolution");
        var disabled=new BFGovernanceStorageReaderImpl(dsl,json);
        assertThat(disabled.findDRepMetadata(BFDRepIdentity.parse(ID)).orElseThrow().getAnchorMetadata()).isNull();
        assertThat(disabled.findAllDReps(0,100,Order.asc).getFirst().getAnchorMetadata()).isNull();
    }
    private static DRepAnchorResolutionStorageImpl.Provenance nativeProvenance() {return DRepAnchorResolutionStorageImpl.Provenance.nativeFetch(java.time.Instant.parse("2026-10-18T07:00:00Z"));}
    private static DRepAnchorResolutionStorageImpl.Provenance retainedProvenance() {return DRepAnchorResolutionStorageImpl.Provenance.retained("ff".repeat(32),0,java.time.Instant.parse("2026-10-18T06:00:00Z"));}
    private static List<DRepAnchorResolutionStorageImpl.Attempt> nativeSuccess(DRepAnchorResolutionStorageImpl.Binding b) {return List.of(new DRepAnchorResolutionStorageImpl.Attempt(b.url(),"SUCCESS",null,null,200,BODY_HASH,"application/json",(long)RAW.length,java.time.Instant.parse("2026-10-18T06:59:59Z"),java.time.Instant.parse("2026-10-18T07:00:00Z"),"NATIVE_FETCH",null));}
}
