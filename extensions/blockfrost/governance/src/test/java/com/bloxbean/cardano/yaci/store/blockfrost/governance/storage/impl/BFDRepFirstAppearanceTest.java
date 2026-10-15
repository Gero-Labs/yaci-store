package com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl;

import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.util.BFDRepIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.ExecuteContext;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BFDRepFirstAppearanceTest {
    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String BEFORE_REGISTRATION = "11".repeat(28);
    private static final String NORMAL = "22".repeat(28);
    private static final String UNREGISTERED = "33".repeat(28);
    private static final String TWIN = "44".repeat(28);

    @Test
    void pagesByFirstReferenceIncludingDelegationsVotesAndUnregisteredCredentials() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DATABASE_TO_LOWER=TRUE")) {
            exerciseFirstReference(DSL.using(connection, SQLDialect.H2));
        }
    }

    @Test
    void postgresUsesExactFirstReferenceOrderingBeforePaging() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
            exerciseFirstReference(DSL.using(connection,SQLDialect.POSTGRES));
        }
    }

    private void exerciseFirstReference(DSLContext dsl) throws Exception {
        dsl.execute("CREATE TABLE drep (drep_hash varchar, drep_id varchar, status varchar, epoch int, "
                + "registration_slot bigint, slot bigint, tx_index int, cert_index int)");
        dsl.execute("CREATE TABLE drep_registration (drep_id varchar, drep_hash varchar, cred_type varchar, "
                + "slot bigint, tx_index int, cert_index int, type varchar, epoch int, anchor_url varchar, anchor_hash varchar)");
        dsl.execute("CREATE TABLE delegation_vote (drep_id varchar, drep_hash varchar, drep_type varchar, "
                + "slot bigint, tx_index int, cert_index int)");
        dsl.execute("CREATE TABLE voting_procedure (voter_hash varchar, voter_type varchar, slot bigint, tx_index int, idx int, epoch int)");
        dsl.execute("CREATE TABLE drep_dist (drep_id varchar, drep_type varchar, amount bigint, epoch int)");
        dsl.execute("CREATE TABLE local_drep_dist (drep_hash varchar, drep_type varchar, amount bigint, epoch int)");
        dsl.execute("CREATE TABLE epoch_param (epoch int, params json)");
        dsl.execute("INSERT INTO epoch_param VALUES (2,'{\"drep_activity\":20}')");

        // This DRep was delegated to first, then registered after NORMAL.
        delegation(dsl, BEFORE_REGISTRATION, "ADDR_KEYHASH", 10, 0);
        registration(dsl, BEFORE_REGISTRATION, "ADDR_KEYHASH", 40, 0);
        registration(dsl, NORMAL, "ADDR_KEYHASH", 20, 0);
        // A voter-only script remains listed even without a DRep state/registration row.
        dsl.execute("INSERT INTO voting_procedure VALUES (?, 'DREP_SCRIPT_HASH', 30, 0, 0, 2)", UNREGISTERED);
        // Protocol-defined identities are positioned by their first delegation.
        dsl.execute("INSERT INTO delegation_vote VALUES (null,null,'ABSTAIN',15,0,0)");
        // Same credential hash, different type: each identity has its own first appearance.
        delegation(dsl, TWIN, "SCRIPTHASH", 50, 0);
        registration(dsl, TWIN, "ADDR_KEYHASH", 50, 1);
        registration(dsl, TWIN, "SCRIPTHASH", 80, 0);
        dsl.execute("INSERT INTO local_drep_dist VALUES (?, 'SCRIPTHASH', 123, 2)", UNREGISTERED);
        dsl.execute("INSERT INTO local_drep_dist VALUES (?, 'ADDR_KEYHASH', 999, 2)", UNREGISTERED);
        // A later positive registration resets active_epoch to this registration.
        registration(dsl, NORMAL, "ADDR_KEYHASH", 90, 0);
        dsl.execute("UPDATE drep_registration SET epoch=3 WHERE drep_hash=? AND slot=90", NORMAL);
        List<Integer> statementTimeouts=new ArrayList<>();
        dsl.configuration().set(new DefaultExecuteListenerProvider(new DefaultExecuteListener() {
            @Override public void executeStart(ExecuteContext context) {
                if (context.sql().stripLeading().toLowerCase().startsWith("select")) {
                    try { statementTimeouts.add(context.statement().getQueryTimeout()); }
                    catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
                }
            }
        }));
        BFGovernanceStorageReaderImpl reader = new BFGovernanceStorageReaderImpl(dsl, new ObjectMapper());
        List<String> expected = List.of(id(BEFORE_REGISTRATION, false), "drep_always_abstain",
                id(NORMAL, false), id(UNREGISTERED, true), id(TWIN, true), id(TWIN, false));
        assertThat(reader.findAllDReps(0, 100, Order.asc)).extracting("drepId").containsExactlyElementsOf(expected);
        assertThat(reader.findAllDReps(0, 2, Order.asc)).extracting("drepId").containsExactlyElementsOf(expected.subList(0, 2));
        assertThat(reader.findAllDReps(1, 2, Order.asc)).extracting("drepId").containsExactlyElementsOf(expected.subList(2, 4));
        assertThat(reader.findAllDReps(0, 100, Order.desc)).extracting("drepId")
                .containsExactlyElementsOf(expected.reversed());
        var listed = reader.findAllDReps(0, 100, Order.asc).get(3);
        assertThat(listed.getHasScript()).isTrue();
        assertThat(listed.getEpoch()).isEqualTo(2);
        assertThat(listed.getAmount()).isEqualTo(123);
        var referenceDetail = reader.findDRepById(BFDRepIdentity.parse(id(UNREGISTERED, true))).orElseThrow();
        assertThat(referenceDetail.getDrepId()).isEqualTo(id(UNREGISTERED, true));
        assertThat(referenceDetail.getStatus()).isEqualTo("REGISTERED");
        assertThat(referenceDetail.getEpoch()).isEqualTo(2);
        assertThat(referenceDetail.getActiveEpoch()).isNull();
        assertThat(referenceDetail.getAmount()).isEqualTo(123);
        assertThat(reader.findDRepById(BFDRepIdentity.parse(id(UNREGISTERED, false)))).isEmpty();
        assertThat(reader.findDRepIdsByHash(UNREGISTERED)).containsExactly(id(UNREGISTERED, true));
        assertThat(reader.findDRepById(BFDRepIdentity.parse(id(NORMAL, false))).orElseThrow()
                .getActiveEpoch()).isEqualTo(3);

        // Canonical queried_epoch comes from current epoch parameters, not max(distribution).
        // A missing credential in the current distribution must not inherit an older local row.
        dsl.execute("INSERT INTO epoch_param VALUES (3,'{\"drep_activity\":20}')");
        dsl.execute("INSERT INTO drep_dist VALUES (?,'ADDR_KEYHASH',77,3)",id(NORMAL,false));
        dsl.execute("INSERT INTO drep_dist VALUES ('drep_always_abstain','ABSTAIN',88,2)");
        assertThat(reader.findAllDReps(0,100,Order.asc).get(3).getAmount()).isZero();
        assertThat(reader.findDRepById(BFDRepIdentity.parse(id(UNREGISTERED,true))).orElseThrow().getAmount()).isZero();
        assertThat(reader.findDRepById(BFDRepIdentity.parse("drep_always_abstain")).orElseThrow().getAmount()).isZero();
        dsl.execute("INSERT INTO epoch_param VALUES (4,'{\"drep_activity\":20}')");
        assertThat(reader.findDRepById(BFDRepIdentity.parse(id(NORMAL,false))).orElseThrow().getAmount()).isZero();
        dsl.execute("INSERT INTO local_drep_dist VALUES (?,'SCRIPTHASH',321,4)",UNREGISTERED);
        dsl.execute("INSERT INTO local_drep_dist VALUES (?,'ADDR_KEYHASH',999,4)",UNREGISTERED);
        assertThat(reader.findAllDReps(0,100,Order.asc).get(3).getAmount()).isEqualTo(321);
        assertThat(reader.findDRepById(BFDRepIdentity.parse(id(UNREGISTERED,true))).orElseThrow().getAmount()).isEqualTo(321);

        // Hosted list/detail compare registration transaction ids only. A positive
        // certificate later in the same transaction cannot outrank the negative TX.
        dsl.execute("INSERT INTO drep_registration VALUES (?,?, 'ADDR_KEYHASH',100,0,0,'UNREG_DREP_CERT',4,null,null)",id(NORMAL,false),NORMAL);
        registration(dsl,NORMAL,"ADDR_KEYHASH",100,1);
        assertThat(reader.findAllDReps(0,100,Order.asc).get(2).getStatus()).isEqualTo("RETIRED");
        var retired=reader.findDRepById(BFDRepIdentity.parse(id(NORMAL,false))).orElseThrow();
        assertThat(retired.getStatus()).isEqualTo("RETIRED");
        assertThat(retired.getActiveEpoch()).isNull();
        assertThat(statementTimeouts).isNotEmpty().allSatisfy(timeout -> assertThat(timeout).isBetween(1,8));
        // Delay after a statement models time already consumed by the request. No
        // later enrichment statement may receive a fresh 8-second allowance.
        dsl.configuration().set(new DefaultExecuteListenerProvider(new DefaultExecuteListener() {
            private boolean delayed;
            @Override public void executeEnd(ExecuteContext context) {
                if (!delayed) {
                    delayed=true;
                    try { Thread.sleep(8100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
            }
        }));
        long started=System.nanoTime();
        assertThatThrownBy(() -> reader.findAllDReps(0,100,Order.asc))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DRep read deadline");
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(10));

    }

    private static void registration(DSLContext dsl, String hash, String type, long slot, int index) {
        String id = id(hash, "SCRIPTHASH".equals(type));
        dsl.execute("INSERT INTO drep_registration VALUES (?,?,?, ?,0,?,'REG_DREP_CERT',2,null,null)", id, hash, type, slot, index);
        dsl.execute("INSERT INTO drep VALUES (?,?,'REGISTERED',2, ?,?,0,?)", hash, id, slot, slot, index);
    }

    private static void delegation(DSLContext dsl, String hash, String type, long slot, int index) {
        dsl.execute("INSERT INTO delegation_vote VALUES (?,?,?, ?,0,?)",
                id(hash, "SCRIPTHASH".equals(type)), hash, type, slot, index);
    }

    private static String id(String hash, boolean script) {
        return script ? GovId.drepFromScriptHash(HexUtil.decodeHexString(hash)) : GovId.drepFromKeyHash(HexUtil.decodeHexString(hash));
    }
}
