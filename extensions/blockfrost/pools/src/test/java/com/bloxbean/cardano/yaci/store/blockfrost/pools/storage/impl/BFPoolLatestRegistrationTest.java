package com.bloxbean.cardano.yaci.store.blockfrost.pools.storage.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BFPoolLatestRegistrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static String pool(int id) {
        return String.format("%056x", id);
    }

    @BeforeEach
    void setup() throws Exception {
        try (var connection = connection()) {
            var dsl = DSL.using(connection, SQLDialect.POSTGRES);
            dsl.execute("drop schema public cascade; create schema public");
            dsl.execute("create table pool_registration(pool_id text, slot bigint, tx_index int, cert_index int, "
                    + "tx_hash text, vrf_key text, pledge numeric(20), cost numeric(20), margin_numerator numeric(20), "
                    + "margin_denominator numeric(20), reward_account text, pool_owners json, relays json, "
                    + "metadata_url text, metadata_hash text)");
            dsl.execute("create table pool_retirement(pool_id text, slot bigint, tx_hash text)");
            dsl.execute("create table block(slot_leader text, epoch int)");
            // Insert the older row first so an incomplete slot-only order selects it.
            registration(dsl, 1, 100, 0, 0, "older");
            registration(dsl, 1, 100, 0, 1, "latest");
            // Transaction position takes precedence over certificate index within a slot.
            registration(dsl, 2, 100, 0, 9, "older");
            registration(dsl, 2, 100, 1, 0, "latest");
            // A later slot still takes precedence over both remaining positions.
            registration(dsl, 3, 99, 9, 9, "older");
            registration(dsl, 3, 100, 0, 0, "latest");
        }
    }

    @Test
    void detailUsesTheLatestFullCertificatePosition() throws Exception {
        try (var connection = connection()) {
            var reader = reader(connection);
            for (int id : new int[]{1, 2, 3}) {
                var detail = reader.getPoolDetail(pool(id)).orElseThrow();
                assertThat(detail.vrfKey()).isEqualTo("latest");
                assertThat(detail.pledge()).isEqualTo(BigInteger.valueOf(2));
                assertThat(detail.rewardAccount()).isEqualTo("latest-reward");
                assertThat(detail.owners()).containsExactly("latest-owner");
            }
        }
    }

    @Test
    void metadataUsesTheLatestFullCertificatePosition() throws Exception {
        try (var connection = connection()) {
            var reader = reader(connection);
            for (int id : new int[]{1, 2, 3}) {
                var metadata = reader.getPoolMetadata(pool(id)).orElseThrow();
                assertThat(metadata.metadataUrl()).isEqualTo("https://latest.example/pool.json");
                assertThat(metadata.metadataHash()).isEqualTo("latest");
            }
        }
    }

    @Test
    void relaysUseTheLatestFullCertificatePosition() throws Exception {
        try (var connection = connection()) {
            var reader = reader(connection);
            for (int id : new int[]{1, 2, 3}) {
                assertThat(reader.getPoolRelays(pool(id))).singleElement()
                        .satisfies(relay -> assertThat(relay.getDns()).isEqualTo("latest.example"));
            }
        }
    }

    @Test
    void vrfUsesTheLatestFullCertificatePosition() throws Exception {
        try (var connection = connection()) {
            var reader = reader(connection);
            for (int id : new int[]{1, 2, 3}) {
                assertThat(reader.getVrfKeyByPoolId(pool(id))).contains("latest");
            }
        }
    }

    private static void registration(DSLContext dsl, int id, long slot, int transaction, int certificate, String label) {
        dsl.execute("insert into pool_registration values(?,?,?,?,?,?,?,1,1,10,?,?::json,?::json,?,?)",
                pool(id), slot, transaction, certificate, label + id, label,
                "latest".equals(label) ? 2 : 1, label + "-reward", "[\"" + label + "-owner\"]",
                "[{\"dnsName\":\"" + label + ".example\",\"port\":3001}]",
                "https://" + label + ".example/pool.json", label);
    }

    private static BFPoolsStorageReaderImpl reader(Connection connection) {
        return new BFPoolsStorageReaderImpl(DSL.using(connection, SQLDialect.POSTGRES), new ObjectMapper());
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
