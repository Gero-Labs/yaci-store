package com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl;

import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.util.BFDRepIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BFDRepMetadataAnchorOrderTest {
    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String HASH = "11".repeat(28);
    private static final String KEY_ID = GovId.drepFromKeyHash(HexUtil.decodeHexString(HASH));
    private static final String SCRIPT_ID = GovId.drepFromScriptHash(HexUtil.decodeHexString(HASH));
    private Connection connection;
    private DSLContext dsl;
    private BFGovernanceStorageReaderImpl reader;

    @BeforeEach
    void setup() throws Exception {
        connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
        String schema = "metadata_" + UUID.randomUUID().toString().replace("-", "");
        dsl.execute("CREATE SCHEMA " + schema);
        dsl.execute("SET search_path TO " + schema);
        dsl.execute("CREATE TABLE drep_registration (tx_hash varchar, drep_id varchar, drep_hash varchar, "
                + "cred_type varchar, slot bigint, tx_index int, cert_index int, type varchar, "
                + "epoch int, anchor_url varchar, anchor_hash varchar, deposit bigint)");
        reader = new BFGovernanceStorageReaderImpl(dsl, new ObjectMapper());
    }

    @AfterEach
    void close() throws Exception { connection.close(); }

    @Test
    void latestTransactionInSameSlotWinsBeforeCertificateIndexOrHash() {
        anchor(KEY_ID, "ADDR_KEYHASH", "ff".repeat(32), 100, 1, 9, "earlier-tx");
        anchor(KEY_ID, "ADDR_KEYHASH", "00".repeat(32), 100, 2, 0, "latest-tx");
        var metadata = reader.findDRepMetadata(BFDRepIdentity.parse(KEY_ID)).orElseThrow();
        assertThat(metadata.getAnchorUrl()).isEqualTo("https://example.com/latest-tx");
        assertThat(metadata.getTxHash()).isEqualTo("00".repeat(32));
        assertThat(metadata.getCertIndex()).isZero();
    }

    @Test
    void latestAnchoredCertificateInSameTransactionWins() {
        anchor(KEY_ID, "ADDR_KEYHASH", "aa".repeat(32), 100, 2, 1, "earlier-cert");
        anchor(KEY_ID, "ADDR_KEYHASH", "aa".repeat(32), 100, 2, 2, "latest-cert");
        var metadata = reader.findDRepMetadata(BFDRepIdentity.parse(KEY_ID)).orElseThrow();
        assertThat(metadata.getAnchorUrl()).isEqualTo("https://example.com/latest-cert");
        assertThat(metadata.getCertIndex()).isEqualTo(2);
    }

    @Test
    void laterUnanchoredAndScriptTwinKeepTheLatestKeyAnchor() {
        anchor(KEY_ID, "ADDR_KEYHASH", "aa".repeat(32), 100, 0, 0, "key-anchor");
        anchor(KEY_ID, "ADDR_KEYHASH", "bb".repeat(32), 101, 0, 0, null);
        anchor(SCRIPT_ID, "SCRIPTHASH", "cc".repeat(32), 102, 0, 0, "script-anchor");
        assertThat(reader.findDRepMetadata(BFDRepIdentity.parse(KEY_ID)).orElseThrow().getAnchorUrl())
                .isEqualTo("https://example.com/key-anchor");
        assertThat(reader.findDRepMetadata(BFDRepIdentity.parse(SCRIPT_ID)).orElseThrow().getAnchorUrl())
                .isEqualTo("https://example.com/script-anchor");
        assertThat(reader.findDRepMetadata(BFDRepIdentity.parse(
                GovId.drepFromKeyHash(HexUtil.decodeHexString("22".repeat(28)))))).isEmpty();
    }

    private void anchor(String id, String type, String txHash, long slot, int txIndex, int certIndex, String name) {
        dsl.execute("INSERT INTO drep_registration VALUES (?,?,?,?,?,?,?,'REG_DREP_CERT',2,?,?,0)",
                txHash, id, HASH, type, slot, txIndex, certIndex,
                name == null ? null : "https://example.com/" + name,
                name == null ? null : "dd".repeat(32));
    }
}
