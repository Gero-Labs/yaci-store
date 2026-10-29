package com.bloxbean.cardano.yaci.store.blockfrost.epoch.service;

import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.yaci.store.adapot.domain.EpochStake;
import com.bloxbean.cardano.yaci.store.adapot.storage.EpochStakeStorageReader;
import com.bloxbean.cardano.yaci.store.api.epochaggr.service.EpochReadService;
import com.bloxbean.cardano.yaci.store.blockfrost.epoch.storage.impl.BFEpochStorageReaderImpl;
import com.bloxbean.cardano.yaci.store.common.config.StoreProperties;
import com.bloxbean.cardano.yaci.store.common.util.PoolUtil;
import com.bloxbean.cardano.yaci.store.core.configuration.GenesisConfig;
import com.bloxbean.cardano.yaci.store.epochaggr.domain.Epoch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BFEpochStakeOrderTest {
    private static final String POOL_A = "0f292fcaa02b8b2f9b3c8f9fd8e0bb21abedb692a6d5058df3ef2735";
    private static final String POOL_B = "02".repeat(28);
    private final ObjectMapper json = new ObjectMapper();
    private Connection connection;
    private DSLContext dsl;
    private BFEpochService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
        dsl = DSL.using(connection, SQLDialect.H2);
        dsl.execute("create table epoch_stake (epoch int, active_epoch int, address varchar, "
                + "amount numeric(38), pool_id varchar, delegation_epoch int, create_datetime timestamp, "
                + "primary key (epoch, address))");
        EpochReadService epochs = mock(EpochReadService.class);
        when(epochs.getEpochByNumber(anyInt())).thenAnswer(call ->
                Optional.of(Epoch.builder().number((Integer) call.getArgument(0)).build()));
        ObjectProvider<EpochStakeStorageReader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(EpochStakeStorageReader.class));
        service = new BFEpochService(epochs, new BFEpochStorageReaderImpl(dsl), provider,
                mock(GenesisConfig.class), new StoreProperties());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void reproducesEverySavedPoolBodyAndBothPageBoundaries() throws Exception {
        loadSavedInput();
        ArrayNode expected = savedBody("blockfrost", 1).deepCopy();
        expected.addAll((ArrayNode) savedBody("blockfrost", 2));
        assertThat(expected).hasSize(189);
        assertThat(json.<JsonNode>valueToTree(service.getEpochStakesByPool(633, POOL_A, 0, 200)))
                .isEqualTo(expected);
        assertThat(json.<JsonNode>valueToTree(service.getEpochStakesByPool(633, POOL_A, 0, 100)))
                .isEqualTo(savedBody("blockfrost", 1));
        assertThat(json.<JsonNode>valueToTree(service.getEpochStakesByPool(633, POOL_A, 1, 100)))
                .isEqualTo(savedBody("blockfrost", 2));
        assertThat(service.getEpochStakesByPool(633, POOL_A, 2, 100)).isEmpty();
    }

    @Test
    void usesTheSameCompleteCredentialOrderWithoutAPoolFilter() throws Exception {
        loadSavedInput();
        ArrayNode expected = savedBody("blockfrost", 1).deepCopy();
        expected.addAll((ArrayNode) savedBody("blockfrost", 2));
        expected.forEach(row -> ((com.fasterxml.jackson.databind.node.ObjectNode) row)
                .put("pool_id", PoolUtil.getBech32PoolId(POOL_A)));
        assertThat(json.<JsonNode>valueToTree(service.getEpochStakes(633, 0, 200))).isEqualTo(expected);
        assertThat(json.<JsonNode>valueToTree(service.getEpochStakes(633, 0, 100)))
                .isEqualTo(json.<JsonNode>valueToTree(json.convertValue(expected, List.class).subList(0, 100)));
        assertThat(json.<JsonNode>valueToTree(service.getEpochStakes(633, 1, 100)))
                .isEqualTo(json.<JsonNode>valueToTree(json.convertValue(expected, List.class).subList(100, 189)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "preprod", "preview"})
    void ordersMultipleScriptsThenKeysByRawHashBeforeFilteringAndPaging(String network) {
        // Literal hash order exercises all four first-two-bit groups, Bech32 alphabet
        // inversions, and a tie between script/key hashes. Expectations never use a sorter.
        int networkId = network.equals("mainnet") ? 1 : 0;
        String scriptLow = reward(true, networkId, "00");
        String scriptHigh = reward(true, networkId, "20");
        String keyLow = reward(false, networkId, "00");
        String keyMiddle = reward(false, networkId, "58");
        String keyHigh = reward(false, networkId, "80");
        String keyLast = reward(false, networkId, "ff");
        // Insertion order deliberately interleaves types and pools and opposes hash order.
        insert(631, keyLast, "18446744073709551616", POOL_A);
        insert(631, keyHigh, "7", POOL_A);
        insert(631, scriptHigh, "7", POOL_A);
        insert(631, reward(true, networkId, "40"), "9", POOL_B);
        insert(631, keyMiddle, "11", POOL_A);
        insert(631, scriptLow, "2", POOL_A);
        insert(631, reward(true, networkId, "01"), "0", POOL_A);
        insert(631, keyLow, "3", POOL_A);
        insert(630, reward(true, networkId, "02"), "99", POOL_A);
        insert(632, reward(true, networkId, "03"), "99", POOL_A);

        assertThat(service.getEpochStakesByPool(633, POOL_A, 0, 2)).extracting("stakeAddress")
                .containsExactly(scriptLow, scriptHigh);
        assertThat(service.getEpochStakesByPool(633, POOL_A, 1, 2)).extracting("stakeAddress")
                .containsExactly(keyLow, keyMiddle);
        assertThat(service.getEpochStakesByPool(633, POOL_A, 2, 2)).extracting("stakeAddress")
                .containsExactly(keyHigh, keyLast);
        assertThat(service.getEpochStakesByPool(633, POOL_A, 2, 2)).extracting("amount")
                .containsExactly("7", "18446744073709551616");
        assertThat(service.getEpochStakesByPool(633, POOL_A, 0, 100)).extracting("amount")
                .containsExactly("2", "7", "3", "11", "7", "18446744073709551616");
        assertThat(service.getEpochStakes(633, 0, 100)).extracting("stakeAddress")
                .containsExactly(scriptLow, scriptHigh, reward(true, networkId, "40"),
                        keyLow, keyMiddle, keyHigh, keyLast);
        assertThat(service.getEpochStakesByPool(633, POOL_A, -1, 1)).extracting("stakeAddress")
                .containsExactly(scriptLow);
        assertThat(service.getEpochStakesByPool(633, POOL_A, 5, 1)).extracting("stakeAddress")
                .containsExactly(keyLast);
        assertThat(service.getEpochStakesByPool(633, POOL_A, 6, 1)).isEmpty();
        assertThat(service.getEpochStakesByPool(633, POOL_A, Integer.MAX_VALUE, 2)).isEmpty();
        assertThat(service.getEpochStakes(633, Integer.MAX_VALUE, 2)).isEmpty();
        assertThat(service.getEpochStakes(633, 0, 0)).isEmpty();
        assertThat(service.getEpochStakesByPool(633, "03".repeat(28), 0, 100)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "preprod", "preview"})
    void ordersWithinScriptCredentialsAndSeparatesEqualScriptAndKeyHashes(String network) {
        int networkId = network.equals("mainnet") ? 1 : 0;
        String script18 = reward(true, networkId, "18");
        String script20 = reward(true, networkId, "20");
        String key18 = reward(false, networkId, "18");
        String key20 = reward(false, networkId, "20");
        insert(631, key20, "7", POOL_A);
        insert(631, script20, "7", POOL_A);
        insert(631, key18, "7", POOL_A);
        insert(631, script18, "7", POOL_A);
        assertThat(service.getEpochStakesByPool(633, POOL_A, 0, 3)).extracting("stakeAddress")
                .containsExactly(script18, script20, key18);
        assertThat(service.getEpochStakesByPool(633, POOL_A, 1, 3)).extracting("stakeAddress")
                .containsExactly(key20);
        assertThat(service.getEpochStakes(633, 0, 100)).extracting("stakeAddress")
                .containsExactly(script18, script20, key18, key20);
        // Execute the real MySQL/MariaDB rendering against the same local H2 table.
        // This covers the no-TRANSLATE branch; it is not a MySQL server integration test.
        for (SQLDialect dialect : List.of(SQLDialect.MYSQL, SQLDialect.MARIADB)) {
            var reader = new BFEpochStorageReaderImpl(DSL.using(connection, dialect));
            assertThat(reader.findEpochStakesByPool(633, POOL_A, 0, 3)).extracting(EpochStake::getAddress)
                    .containsExactly(script18, script20, key18);
            assertThat(reader.findEpochStakes(633, 1, 3)).extracting(EpochStake::getAddress)
                    .containsExactly(key20);
        }
    }

    @Test
    void retainsDistinctAddressRowsWhenTheirCredentialSortKeysTie() {
        String lower = reward(false, 1, "20");
        String upper = lower.toUpperCase(java.util.Locale.ROOT);
        insert(631, lower, "7", POOL_A);
        insert(631, upper, "9", POOL_A);
        assertThat(service.getEpochStakesByPool(633, POOL_A, 0, 1)).extracting("stakeAddress", "amount")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(upper, "9"));
        assertThat(service.getEpochStakesByPool(633, POOL_A, 1, 1)).extracting("stakeAddress", "amount")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(lower, "7"));
        assertThat(service.getEpochStakes(633, 0, 100)).extracting("stakeAddress")
                .containsExactly(upper, lower);
    }

    private void loadSavedInput() throws Exception {
        for (int page = 1; page <= 2; page++) {
            for (JsonNode row : savedBody("nexus", page)) {
                insert(631, row.get("stake_address").textValue(), row.get("amount").textValue(), POOL_A);
            }
        }
        assertThat(dsl.fetchCount(DSL.table("epoch_stake"))).isEqualTo(195);
    }

    private ArrayNode savedBody(String side, int page) throws Exception {
        try (var input = getClass().getResourceAsStream("/epoch-stake-order/" + side + "-page-" + page + ".json")) {
            return (ArrayNode) json.readTree(input).get("body");
        }
    }

    private void insert(int epoch, String address, String amount, String pool) {
        dsl.execute("insert into epoch_stake (epoch, active_epoch, address, amount, pool_id, delegation_epoch) "
                + "values (?, ?, ?, ?, ?, ?)", epoch, epoch + 2, address, new BigInteger(amount), pool, epoch);
    }

    private static String reward(boolean script, int network, String hashByte) {
        byte[] bytes = HexFormat.of().parseHex((script ? "f" : "e") + network + hashByte.repeat(28));
        return Bech32.encode(bytes, network == 1 ? "stake" : "stake_test");
    }
}
