package com.bloxbean.cardano.yaci.store.blockfrost.epoch.service;

import com.bloxbean.cardano.yaci.store.adapot.domain.EpochStake;
import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.yaci.store.adapot.storage.EpochStakeStorageReader;
import com.bloxbean.cardano.yaci.store.api.epochaggr.service.EpochReadService;
import com.bloxbean.cardano.yaci.store.blockfrost.epoch.dto.BFEpochStakeDto;
import com.bloxbean.cardano.yaci.store.blockfrost.epoch.dto.BFEpochStakePoolDto;
import com.bloxbean.cardano.yaci.store.blockfrost.epoch.storage.impl.BFEpochStorageReaderImpl;
import com.bloxbean.cardano.yaci.store.common.config.StoreProperties;
import com.bloxbean.cardano.yaci.store.common.util.PoolUtil;
import com.bloxbean.cardano.yaci.store.core.configuration.GenesisConfig;
import com.bloxbean.cardano.yaci.store.epochaggr.domain.Epoch;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BFEpochPositiveStakeTest {
    private static final String POOL_A = "01".repeat(28);
    private static final String POOL_B = "02".repeat(28);
    private static final String ONE = reward("01");
    private static final String TWO = reward("02");
    private static final String THREE = reward("03");
    private static final String LARGE = reward("04");
    private Connection connection;
    private DSLContext dsl;
    private BFEpochService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DATABASE_TO_LOWER=TRUE");
        dsl = DSL.using(connection, SQLDialect.H2);
        dsl.execute("create table epoch_stake (epoch int, active_epoch int, address varchar, "
                + "amount numeric(38), pool_id varchar, delegation_epoch int, create_datetime timestamp, "
                + "primary key (epoch, address))");
        insert(631, reward("00"), "0", POOL_A);
        insert(631, ONE, "1", POOL_A);
        insert(631, reward("05"), "0", POOL_A);
        insert(631, TWO, "2", POOL_B);
        insert(631, reward("06"), "0", POOL_B);
        insert(631, THREE, "3", POOL_A);
        insert(631, LARGE, "18446744073709551616", POOL_A);
        insert(630, reward("07"), "99", POOL_A);
        insert(632, reward("08"), "99", POOL_A);

        EpochReadService epochReadService = mock(EpochReadService.class);
        when(epochReadService.getEpochByNumber(anyInt())).thenAnswer(call ->
                Optional.of(Epoch.builder().number((Integer) call.getArgument(0)).build()));
        // The generic ledger reader deliberately retains raw snapshot rows, including zeros.
        // Keep its current paging behavior available so falling back to that path fails this test.
        EpochStakeStorageReader ledgerReader = mock(EpochStakeStorageReader.class);
        when(ledgerReader.getAllActiveStakesByEpoch(anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> rawPage(call.getArgument(0), null, call.getArgument(1), call.getArgument(2)));
        when(ledgerReader.getAllActiveStakesByEpochAndPool(anyInt(), anyString(), anyInt(), anyInt()))
                .thenAnswer(call -> rawPage(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3)));
        ObjectProvider<EpochStakeStorageReader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ledgerReader);
        service = new BFEpochService(epochReadService, new BFEpochStorageReaderImpl(dsl), provider,
                mock(GenesisConfig.class), new StoreProperties());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void filtersZerosBeforePagingAndUsesTheActiveEpochSnapshot() {
        assertThat(service.getEpochStakes(633, 0, 2)).extracting(BFEpochStakeDto::getStakeAddress)
                .containsExactly(ONE, TWO);
        assertThat(service.getEpochStakes(633, 1, 2)).extracting(BFEpochStakeDto::getStakeAddress)
                .containsExactly(THREE, LARGE);
        assertThat(service.getEpochStakes(633, 2, 2)).isEmpty();
        assertThat(service.getEpochStakes(633, 1, 2)).extracting(BFEpochStakeDto::getAmount)
                .containsExactly("3", "18446744073709551616");
    }

    @Test
    void filtersZerosBeforePagingWithinTheRequestedPool() {
        String poolId = PoolUtil.getBech32PoolId(POOL_A);
        assertThat(service.getEpochStakesByPool(633, poolId, 0, 2))
                .extracting(BFEpochStakePoolDto::getStakeAddress).containsExactly(ONE, THREE);
        assertThat(service.getEpochStakesByPool(633, poolId, 1, 2))
                .extracting(BFEpochStakePoolDto::getStakeAddress).containsExactly(LARGE);
        assertThat(service.getEpochStakesByPool(633, poolId, 2, 2)).isEmpty();
        assertThat(service.getEpochStakesByPool(633, POOL_B, 0, 2))
                .extracting(BFEpochStakePoolDto::getStakeAddress).containsExactly(TWO);
    }

    @Test
    void preservesLargePageOffsetsWithoutIntegerOverflow() {
        assertThat(service.getEpochStakes(633, Integer.MAX_VALUE, 2)).isEmpty();
        assertThat(service.getEpochStakesByPool(633, POOL_A, Integer.MAX_VALUE, 2)).isEmpty();
    }

    private void insert(int epoch, String address, String amount, String pool) {
        dsl.execute("insert into epoch_stake (epoch, active_epoch, address, amount, pool_id, delegation_epoch) "
                        + "values (?, ?, ?, ?, ?, ?)",
                epoch, epoch + 2, address, new BigInteger(amount), pool, epoch);
    }

    private static String reward(String hashByte) {
        return Bech32.encode(HexFormat.of().parseHex("e1" + hashByte.repeat(28)), "stake");
    }

    private List<EpochStake> rawPage(int activeEpoch, String pool, int page, int count) {
        var query = dsl.selectFrom(DSL.table("epoch_stake"))
                .where(DSL.field("epoch", Integer.class).eq(activeEpoch - 2));
        if (pool != null) query.and(DSL.field("pool_id", String.class).eq(pool));
        return query.orderBy(DSL.field("address").asc()).limit(count).offset((long) page * count)
                .fetch(row -> EpochStake.builder().epoch(row.get("epoch", Integer.class))
                        .activeEpoch(row.get("active_epoch", Integer.class))
                        .address(row.get("address", String.class))
                        .amount(row.get("amount", BigInteger.class)).poolId(row.get("pool_id", String.class))
                        .delegationEpoch(row.get("delegation_epoch", Integer.class)).build());
    }
}
