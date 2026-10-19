package com.bloxbean.cardano.yaci.store.metadata.storage.impl;

import com.bloxbean.cardano.yaci.store.metadata.MetadataStoreConfiguration;
import com.bloxbean.cardano.yaci.store.metadata.domain.TxMetadataLabel;
import com.bloxbean.cardano.yaci.store.metadata.storage.TxMetadataStorage;
import com.bloxbean.cardano.yaci.store.metadata.storage.impl.mapper.MetadataMapper;
import com.bloxbean.cardano.yaci.store.metadata.storage.impl.model.TxMetadataLabelEntity;
import com.bloxbean.cardano.yaci.store.metadata.storage.impl.repository.TxMetadataLabelRepository;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TxMetadataStorageReplayTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupAttempts(3);
    // Ordered and repeated inner keys are payload, never row identity.
    private static final String BODY = "{\"map\":[{\"k\":{\"string\":\"b\"},\"v\":{\"int\":1}},"
            + "{\"k\":{\"string\":\"a\"},\"v\":{\"int\":1}},"
            + "{\"k\":{\"string\":\"a\"},\"v\":{\"int\":2}}]}";
    private static final String CBOR = "a11902d1a3616201616101616102";
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TxMetadataStorage storage;
    private TransactionTemplate transaction;

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = TxMetadataLabelRepository.class)
    static class Persistence {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source,
                org.springframework.core.env.Environment environment) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(TxMetadataLabelEntity.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", environment.getProperty("metadata-test.ddl", "validate")));
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        MetadataMapper metadataMapper() {
            return Mappers.getMapper(MetadataMapper.class);
        }

        @Bean
        TxMetadataStorage storage(TxMetadataLabelRepository repository, MetadataMapper mapper, DSLContext dsl) {
            return new MetadataStoreConfiguration().txMetadataStorage(repository, mapper, dsl);
        }
    }

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/store/postgresql/V0_600_1__init.sql"),
                new ClassPathResource("db/store/postgresql/V0_600_2__add_tx_index_to_transaction_metadata.sql"))
                .execute(source);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(DSLContext.class,
                () -> DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES));
        context.register(Persistence.class);
        context.refresh();
        storage = context.getBean(TxMetadataStorage.class);
        transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    private static TxMetadataLabel label() {
        return TxMetadataLabel.builder().txHash("a".repeat(64)).slot(100L).label("721")
                .txIndex(3).blockNumber(20L).blockTime(12345L).body(BODY).cbor(CBOR).build();
    }

    private void save(TxMetadataLabel... labels) {
        storage.saveAll(List.of(labels));
    }

    private Map<String, Object> onlyRow() {
        assertThat(jdbc.queryForObject("select count(*) from transaction_metadata", Integer.class)).isEqualTo(1);
        return jdbc.queryForMap("select * from transaction_metadata");
    }

    private UUID legacy(TxMetadataLabel label) {
        var id = UUID.randomUUID();
        jdbc.update("insert into transaction_metadata(id, tx_hash, slot, label, body, cbor, tx_index, block, "
                        + "block_time, update_datetime) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, label.getTxHash(), label.getSlot(), label.getLabel(), label.getBody(), label.getCbor(),
                label.getTxIndex(), label.getBlockNumber(), label.getBlockTime(), Timestamp.valueOf("2020-01-01 00:00:00"));
        return id;
    }

    @Test
    void repeatedCommittedDeliveryKeepsOneUuidAndExactOrderedPayload() {
        save(label());
        var original = onlyRow();
        save(label()); // Metadata committed before the cursor: startup delivers the same field again.
        assertThat(onlyRow()).isEqualTo(original);
        assertThat(original).containsEntry("body", BODY).containsEntry("cbor", CBOR);
    }

    @Test
    void repeatedNaturalKeyInsideOneDeliveryKeepsMultiplicityInTheResultButOneRow() {
        var result = storage.saveAll(List.of(label(), label()));
        assertThat(result).hasSize(2).allSatisfy(value -> {
            assertThat(value.getBody()).isEqualTo(BODY);
            assertThat(value.getCbor()).isEqualTo(CBOR);
        });
        onlyRow();
    }

    @Test
    void aFailedCallerTransactionRollsBackTheMetadataWriteToo() {
        transaction.executeWithoutResult(status -> {
            save(label());
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from transaction_metadata", Integer.class)).isZero();
    }

    @Test
    void replayAdoptsTheExistingRandomUuidInsteadOfAddingADeterministicSibling() {
        var id = legacy(label());
        var original = onlyRow();
        save(label());
        assertThat(onlyRow()).isEqualTo(original).containsEntry("id", id);
    }

    @Test
    void simultaneousTransactionsSerializeFirstDeliveryAndAdoptTheWinner() throws Exception {
        var firstSaved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var writers = Executors.newFixedThreadPool(2)) {
            var first = writers.submit(() -> transaction.executeWithoutResult(status -> {
                save(label());
                firstSaved.countDown();
                await(release);
            }));
            assertThat(firstSaved.await(5, TimeUnit.SECONDS)).isTrue();
            var second = writers.submit(() -> save(label()));
            try {
                // Poll PostgreSQL's actual lock wait, so the test cannot pass by serial scheduling.
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(180);
                boolean waiting;
                do {
                    waiting = jdbc.queryForObject("select exists(select 1 from pg_stat_activity "
                            + "where datname=current_database() and wait_event='advisory')", Boolean.class);
                    if (!waiting) Thread.sleep(5);
                } while (!waiting && System.nanoTime() < until);
                assertThat(waiting).isTrue();
                assertThat(second.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(onlyRow()).containsEntry("body", BODY).containsEntry("cbor", CBOR);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("writer was not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rollbackRetainsBoundaryAndReplayRecreatesOnlyLaterSlotsOnce() {
        save(label(), label().toBuilder().slot(101L).build());
        var boundary = jdbc.queryForMap("select * from transaction_metadata where slot=100");
        transaction.executeWithoutResult(status -> assertThat(storage.deleteBySlotGreaterThan(100)).isEqualTo(1));
        save(label(), label().toBuilder().slot(101L).build());
        save(label().toBuilder().slot(101L).build());
        assertThat(jdbc.queryForObject("select count(*) from transaction_metadata", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForMap("select * from transaction_metadata where slot=100")).isEqualTo(boundary);
    }

    @Test
    void distinctOuterLabelsSlotsAndTransactionHashesRemainDistinct() {
        save(label(), label().toBuilder().label("722").build(), label().toBuilder().slot(101L).build(),
                label().toBuilder().txHash("b".repeat(64)).build());
        save(label());
        assertThat(jdbc.queryForObject("select count(*) from transaction_metadata", Integer.class)).isEqualTo(4);
    }

    @Test
    void oppositeInputOrdersDoNotDeadlockOverlappingMultiKeyDeliveries() throws Exception {
        var start = new CountDownLatch(1);
        var other = label().toBuilder().label("722").build();
        try (var writers = Executors.newFixedThreadPool(2)) {
            var first = writers.submit(() -> { await(start); save(label(), other); });
            var second = writers.submit(() -> { await(start); save(other, label()); });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from transaction_metadata", Integer.class)).isEqualTo(2);
    }

    @Test
    void enrichesOnlyMissingValuesAndNeverErasesKnownValues() {
        var sparse = label().toBuilder().txIndex(null).cbor(null).blockNumber(null).blockTime(null).build();
        var id = legacy(sparse);
        save(label());
        var enriched = onlyRow();
        assertThat(enriched).containsEntry("id", id).containsEntry("tx_index", 3).containsEntry("cbor", CBOR)
                .containsEntry("block", 20L).containsEntry("block_time", 12345L);
        save(sparse);
        assertThat(onlyRow()).isEqualTo(enriched);
    }

    @Test
    void aLaterConflictRollsBackEarlierNullEnrichment() {
        var sparse = label().toBuilder().label("720").cbor(null).txIndex(null).build();
        legacy(sparse);
        legacy(label());
        var original = jdbc.queryForList("select * from transaction_metadata order by id");
        assertThatThrownBy(() -> save(sparse.toBuilder().cbor(CBOR).txIndex(3).build(),
                label().toBuilder().body(BODY + " ").build())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("select * from transaction_metadata order by id")).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "cbor", "tx_index", "block", "block_time"})
    void conflictingCanonicalValueFailsTheWholeDelivery(String field) {
        legacy(label());
        var original = onlyRow();
        var changed = label().toBuilder();
        switch (field) {
            case "body" -> changed.body(BODY + " ");
            case "cbor" -> changed.cbor("a11902d1a3616101616102616201");
            case "tx_index" -> changed.txIndex(4);
            case "block" -> changed.blockNumber(21L);
            case "block_time" -> changed.blockTime(12346L);
        }
        assertThatThrownBy(() -> save(label().toBuilder().label("720").build(), changed.build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(field);
        assertThat(onlyRow()).isEqualTo(original); // No earlier label may commit.
    }

    @Test
    void nullBodyCannotEraseAnExistingCanonicalBody() {
        legacy(label());
        var original = onlyRow();
        assertThatThrownBy(() -> save(label().toBuilder().body(null).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("body");
        assertThat(onlyRow()).isEqualTo(original);
    }

    @Test
    void severalLegacyUuidsRefuseReplayWithoutDeletingOrChoosingARow() {
        legacy(label());
        legacy(label());
        var original = jdbc.queryForList("select * from transaction_metadata order by id");
        assertThatThrownBy(() -> save(label())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple");
        assertThat(jdbc.queryForList("select * from transaction_metadata order by id")).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "10s", "50ms"})
    void restoresTheCallersLockTimeoutAfterSuccessfulWrites(String timeout) {
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class, timeout);
            save(label());
            assertThat(jdbc.queryForObject("select current_setting('lock_timeout')", String.class)).isEqualTo(timeout);
        });
        onlyRow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "50ms"})
    void contendedRowLockFailsWithinTheMetadataOrStricterCallerBudget(String timeout) throws Exception {
        legacy(label());
        var original = onlyRow();
        try (var writers = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from transaction_metadata for update", UUID.class);
                var started = System.nanoTime();
                var blocked = writers.submit(() -> transaction.executeWithoutResult(other -> {
                    jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class, timeout);
                    save(label());
                }));
                assertThatThrownBy(() -> blocked.get(2, TimeUnit.SECONDS)).rootCause().hasMessageContaining("canceling statement due to lock timeout");
                var elapsed = Duration.ofNanos(System.nanoTime() - started);
                assertThat(elapsed).isLessThan(Duration.ofMillis(900));
                if (timeout.equals("50ms")) assertThat(elapsed).isLessThan(Duration.ofMillis(220));
            });
        }
        assertThat(onlyRow()).isEqualTo(original);
    }
}

/** Other dialects continue through generated-ID JPA persistence, without PostgreSQL lock SQL. */
class TxMetadataStorageDialectTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SQLDialect.class, names = {"H2", "MYSQL"})
    void existingJpaFallbackPersistsTheFullPayload(SQLDialect dialect) {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/store/h2/V0_600_1__init.sql"),
                new ClassPathResource("db/store/h2/V0_600_2__add_tx_index_to_transaction_metadata.sql"))
                .execute(source);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> source);
            context.registerBean(DSLContext.class,
                    () -> DSL.using(new TransactionAwareDataSourceProxy(source), dialect));
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "fixture", Map.of("metadata-test.ddl", "none")));
            context.register(TxMetadataStorageReplayTest.Persistence.class);
            context.refresh();
            var storage = context.getBean(TxMetadataStorage.class);
            var value = TxMetadataLabel.builder().txHash("b".repeat(64)).slot(100L).label("721")
                    .txIndex(3).blockNumber(20L).blockTime(12345L).body("{\"map\":[]}").cbor("a11902d1a0").build();
            assertThat(storage.saveAll(List.of(value))).singleElement().satisfies(saved -> {
                assertThat(saved.getBody()).isEqualTo("{\"map\":[]}");
                assertThat(saved.getCbor()).isEqualTo("a11902d1a0");
                assertThat(saved.getTxIndex()).isEqualTo(3);
            });
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("select count(*) from transaction_metadata", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select id from transaction_metadata", UUID.class)).isNotNull();
            assertThat(jdbc.queryForObject("select body from transaction_metadata", String.class)).isEqualTo("{\"map\":[]}");
            assertThat(jdbc.queryForObject("select cbor from transaction_metadata", String.class)).isEqualTo("a11902d1a0");
        } finally {
            new JdbcTemplate(source).execute("shutdown");
        }
    }
}
