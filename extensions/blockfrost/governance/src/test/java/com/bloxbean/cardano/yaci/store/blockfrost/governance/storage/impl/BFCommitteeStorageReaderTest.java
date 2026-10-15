package com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.util.BFCommitteeCredential;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.*;

class BFCommitteeStorageReaderTest {
    private static final String COLD = "11".repeat(28);
    private static final String HOT = "22".repeat(28);
    private static final String ROTATED = "33".repeat(28);
    private static final String PROPOSAL = "44".repeat(32);

    @Test
    void retainsLastSeatedCommitteeWhileDissolvedAndOrdersSameTxLifecycle() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE")) {
            var dsl = DSL.using(connection, SQLDialect.H2);
            schema(dsl);
            dsl.execute("INSERT INTO committee (gov_action_tx_hash,gov_action_index,threshold_numerator,threshold_denominator,epoch,slot) VALUES ('genesis.conway',null,2,3,1,10)");
            dsl.execute("INSERT INTO committee_member (hash,cred_type,expired_epoch,epoch,slot) VALUES (?, 'ADDR_KEYHASH', 580,1,10)", COLD);
            var cold = new BFCommitteeCredential(COLD, false, true);
            registration(dsl, "aa".repeat(32), 20, 0, COLD, HOT, false, true, false);
            dsl.execute("INSERT INTO committee_deregistration (tx_hash,cert_index,tx_index,cold_key,cred_type,slot) VALUES (?,1,0,?, 'ADDR_KEYHASH',20)", "aa".repeat(32), COLD);
            var reader = new BFCommitteeStorageReader(dsl, new ObjectMapper());
            var first = reader.committee().orElseThrow();
            assertThat(first.get("proposal_tx_hash").isNull()).isTrue();
            assertThat(first.at("/members/0/status").asText()).isEqualTo("resigned");
            assertThat(first.at("/members/0/cc_hot_id").isNull()).isTrue();
            // Reauthorization later in the same transaction restores the exact script hot type.
            registration(dsl, "aa".repeat(32), 20, 2, COLD, ROTATED, false, true, true);
            dsl.execute("INSERT INTO committee_state VALUES (2,'NO_CONFIDENCE')");
            var dissolved = reader.committee().orElseThrow();
            assertThat(dissolved.get("is_dissolved").asBoolean()).isTrue();
            assertThat(dissolved.at("/members/0/cc_cold_id").asText()).isEqualTo(cold.id());
            assertThat(dissolved.at("/members/0/status").asText()).isEqualTo("authorized");
            assertThat(dissolved.at("/members/0/cc_hot_has_script").asBoolean()).isTrue();
            assertThat(dissolved.at("/members/0/cc_hot_id").asText())
                    .isEqualTo(new BFCommitteeCredential(ROTATED, true, false).id());
            // A seated committee with no members must remain empty.
            dsl.execute("INSERT INTO committee (gov_action_tx_hash,gov_action_index,threshold_numerator,threshold_denominator,epoch,slot) VALUES (?,0,1,2,3,30)", PROPOSAL);
            assertThat(reader.committee().orElseThrow().get("members")).isEmpty();
        }
    }

    @Test
    void coldVotesIncludeAllHotRotationsWithoutConflatingCredentialTwins() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE")) {
            var dsl = DSL.using(connection, SQLDialect.H2);
            schema(dsl);
            dsl.execute("INSERT INTO gov_action_proposal VALUES (?,0,'INFO_ACTION')", PROPOSAL);
            registration(dsl, "aa".repeat(32), 10, 0, COLD, HOT, false, false, false);
            registration(dsl, "bb".repeat(32), 20, 0, COLD, ROTATED, false, true, true);
            vote(dsl, "c1".repeat(32), HOT, false, 15, "YES");
            vote(dsl, "c2".repeat(32), ROTATED, true, 25, "NO");
            vote(dsl, "c3".repeat(32), ROTATED, false, 25, "ABSTAIN");
            var reader = new BFCommitteeStorageReader(dsl, new ObjectMapper());
            var cold = new BFCommitteeCredential(COLD, false, true);
            assertThat(reader.votes(cold, 0, 100, Order.asc)).hasSize(2);
            assertThat(reader.votes(cold, 1, 1, Order.asc).get(0).get("tx_hash").asText()).isEqualTo("c2".repeat(32));
            assertThat(reader.votes(cold, 0, 100, Order.desc).get(0).get("tx_hash").asText()).isEqualTo("c2".repeat(32));
            assertThat(reader.votes(new BFCommitteeCredential(COLD, true, true), 0, 100, Order.asc)).isEmpty();
            var all = reader.votes(null, 0, 100, Order.asc);
            assertThat(all).hasSize(3);
            assertThat(all.get(1).get("vote").asText()).isEqualTo("no");
            assertThat(all.get(2).get("vote").asText()).isEqualTo("abstain");
            assertThat(all.get(0).get("metadata_url").isNull()).isTrue();
            assertThat(all.get(0).get("block_height").asLong()).isEqualTo(100);
            assertThat(all.get(0).get("governance_type").asText()).isEqualTo("info_action");
        }
    }

    @Test
    void decodesBodyAndFullEnvelopeAndRejectsIndexedCredentialMismatch() throws Exception {
        var cold = new BFCommitteeCredential(COLD, false, true);
        byte[] body = body(0, COLD, HOT, false, true);
        assertThat(BFCommitteeStorageReader.decodeHotCredential(body, 0, cold, HOT).script()).isTrue();
        var map = CborSerializationUtil.deserializeOne(body);
        byte[] envelope = CborSerializationUtil.serialize(new Array().add(map).add(new co.nstant.in.cbor.model.Map()));
        assertThat(BFCommitteeStorageReader.decodeHotCredential(envelope, 0, cold, HOT).script()).isTrue();
        assertThatThrownBy(() -> BFCommitteeStorageReader.decodeHotCredential(body, 0,
                new BFCommitteeCredential(COLD, true, true), HOT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BFCommitteeStorageReader.decodeHotCredential(body, 0, cold, ROTATED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reproducesRecordedBlockfrostVoteBodiesAcrossAllNetworks() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String network : new String[]{"mainnet","preprod","preview"}) {
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE")) {
                var dsl = DSL.using(connection, SQLDialect.H2);
                schema(dsl);
                var expected = mapper.readTree(getClass().getResourceAsStream("/committee/"+network+"-committee-votes.json"));
                var inserted = new HashSet<String>();
                int position = 0;
                for (var row : expected) {
                    var hot = BFCommitteeCredential.parse(row.get("voter_hot_id").asText());
                    String actionHash = row.get("proposal_tx_hash").asText();
                    int actionIndex = row.get("proposal_index").asInt();
                    if (inserted.add(actionHash+"#"+actionIndex)) {
                        String type = switch (row.get("governance_type").asText()) {
                            case "parameter_change" -> "PARAMETER_CHANGE_ACTION";
                            case "hard_fork_initiation" -> "HARD_FORK_INITIATION_ACTION";
                            case "treasury_withdrawals" -> "TREASURY_WITHDRAWALS_ACTION";
                            case "new_committee" -> "UPDATE_COMMITTEE";
                            default -> row.get("governance_type").asText().toUpperCase();
                        };
                        dsl.execute("INSERT INTO gov_action_proposal VALUES (?,?,?)",actionHash,actionIndex,type);
                    }
                    dsl.execute("INSERT INTO voting_procedure (tx_hash,idx,tx_index,voter_type,voter_hash,"
                                    + "gov_action_tx_hash,gov_action_index,vote,anchor_url,anchor_hash,epoch,slot,block,block_time) "
                                    + "VALUES (?,0,?,?,?,?,?,?,?,?,1,?,?,?)",
                            row.get("tx_hash").asText(),position++,hot.voterType(),hot.hash(),actionHash,actionIndex,
                            row.get("vote").asText().toUpperCase(),
                            row.get("metadata_url").isNull() ? null : row.get("metadata_url").asText(),
                            row.get("metadata_hash").isNull() ? null : row.get("metadata_hash").asText(),
                            row.get("block_height").asLong(),row.get("block_height").asLong(),row.get("block_time").asLong());
                }
                assertThat(mapper.readTree(mapper.writeValueAsBytes(
                        new BFCommitteeStorageReader(dsl,mapper).votes(null,0,100,Order.asc)))).isEqualTo(expected);
            }
        }
    }

    @Test
    void reproducesRecordedPreviewCommitteeUsingRealAuthorizationCbor() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var expected = mapper.readTree(getClass().getResourceAsStream("/committee/preview-committee.json"));
        var certificates = mapper.readTree(getClass().getResourceAsStream("/committee/preview-authorization-certificates.json"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE")) {
            var dsl = DSL.using(connection, SQLDialect.H2);
            schema(dsl);
            dsl.execute("INSERT INTO committee (gov_action_tx_hash,gov_action_index,threshold_numerator,threshold_denominator,epoch,slot) "
                            + "VALUES (?,?,?,?,1370,118368081)",expected.get("proposal_tx_hash").asText(),
                    expected.get("proposal_index").asInt(),expected.at("/quorum/numerator").asLong(),
                    expected.at("/quorum/denominator").asLong());
            for (var member : expected.get("members")) {
                dsl.execute("INSERT INTO committee_member (hash,cred_type,expired_epoch,epoch,slot) VALUES (?,?,?,1370,118368081)",
                        member.get("cc_cold_hex").asText(), member.get("cc_cold_has_script").asBoolean() ? "SCRIPTHASH" : "ADDR_KEYHASH",
                        member.get("expiration_epoch").asInt());
            }
            for (var row : certificates) {
                dsl.execute("INSERT INTO committee_registration (tx_hash,cert_index,tx_index,cold_key,hot_key,cred_type,slot) VALUES (?,?,0,?,?,?,?)",
                        row.get("tx_hash").asText(),row.get("cert_index").asInt(),row.get("cold_hash").asText(),
                        row.get("hot_hash").asText(),row.get("cold_type").asText(),row.get("slot").asLong());
                dsl.execute("INSERT INTO transaction_cbor VALUES (?,?,?)",row.get("tx_hash").asText(),row.get("slot").asLong(),
                        HexUtil.decodeHexString(row.get("cbor").asText()));
            }
            assertThat(mapper.readTree(mapper.writeValueAsBytes(new BFCommitteeStorageReader(dsl,mapper).committee().orElseThrow())))
                    .isEqualTo(expected);
        }
    }

    private void schema(DSLContext dsl) {
        dsl.execute("CREATE TABLE committee (gov_action_tx_hash varchar, gov_action_index int, threshold_numerator bigint, "
                + "threshold_denominator bigint, epoch int, slot bigint,threshold double,update_datetime timestamp)");
        dsl.execute("CREATE TABLE committee_state (epoch int,state varchar)");
        dsl.execute("CREATE TABLE committee_member (hash varchar,cred_type varchar,expired_epoch int,epoch int,slot bigint,start_epoch int,update_datetime timestamp)");
        dsl.execute("CREATE TABLE committee_registration (tx_hash varchar,cert_index int,tx_index int,cold_key varchar, "
                + "hot_key varchar,cred_type varchar,slot bigint,epoch int,block bigint,block_time bigint,update_datetime timestamp)");
        dsl.execute("CREATE TABLE committee_deregistration (tx_hash varchar,cert_index int,tx_index int,cold_key varchar,cred_type varchar,slot bigint,anchor_url varchar,anchor_hash varchar,epoch int,block bigint,block_time bigint,update_datetime timestamp)");
        dsl.execute("CREATE TABLE transaction_cbor (tx_hash varchar, slot bigint, cbor_data blob)");
        dsl.execute("CREATE TABLE voting_procedure (tx_hash varchar,idx int,tx_index int,voter_type varchar,voter_hash varchar, "
                + "gov_action_tx_hash varchar,gov_action_index int,vote varchar,anchor_url varchar,anchor_hash varchar,epoch int,slot bigint,block bigint,block_time bigint,id uuid,update_datetime timestamp)");
        dsl.execute("CREATE TABLE gov_action_proposal (tx_hash varchar,idx int,type varchar)");
    }

    private void registration(DSLContext dsl, String hash, long slot, int index, String cold, String hot,
                              boolean coldScript, boolean hotScript, boolean envelope) throws Exception {
        dsl.execute("INSERT INTO committee_registration (tx_hash,cert_index,tx_index,cold_key,hot_key,cred_type,slot) VALUES (?, ?,0,?,?,?,?)", hash,index,cold,hot,
                coldScript ? "SCRIPTHASH" : "ADDR_KEYHASH",slot);
        byte[] bytes = body(index,cold,hot,coldScript,hotScript);
        if (envelope) bytes = CborSerializationUtil.serialize(new Array().add(CborSerializationUtil.deserializeOne(bytes))
                .add(new co.nstant.in.cbor.model.Map()));
        dsl.execute("DELETE FROM transaction_cbor WHERE tx_hash=?",hash);
        dsl.execute("INSERT INTO transaction_cbor VALUES (?,?,?)",hash,slot,bytes);
    }

    private static byte[] body(int index, String cold, String hot, boolean coldScript, boolean hotScript) throws Exception {
        var certificates = new Array();
        for (int i=0; i<index; i++) certificates.add(new Array().add(new UnsignedInteger(0)));
        certificates.add(new Array().add(new UnsignedInteger(14))
                .add(new Array().add(new UnsignedInteger(coldScript ? 1 : 0)).add(new ByteString(HexUtil.decodeHexString(cold))))
                .add(new Array().add(new UnsignedInteger(hotScript ? 1 : 0)).add(new ByteString(HexUtil.decodeHexString(hot)))));
        return CborSerializationUtil.serialize(new co.nstant.in.cbor.model.Map().put(new UnsignedInteger(4),certificates));
    }

    private void vote(DSLContext dsl, String hash, String hot, boolean script, long slot, String vote) {
        dsl.execute("INSERT INTO voting_procedure (tx_hash,idx,tx_index,voter_type,voter_hash,gov_action_tx_hash,gov_action_index,vote,anchor_url,anchor_hash,epoch,slot,block,block_time) VALUES (?,0,0,?,?,?,0,?,null,null,1,?,100,200)", hash,
                script ? "CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH" : "CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH",hot,PROPOSAL,vote,slot);
    }
}
