package com.bloxbean.cardano.yaci.store.blockfrost.pools.storage.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the exact operator SQL, including rollback and held-writer refusal cases. */
@Testcontainers(disabledWithoutDocker = true)
class BFClosedEpochRepairGuardsTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("repair_test").withUsername("repair_test").withPassword("repair_test")
            .withStartupAttempts(3);

    @Test void reviewedRepairsAndAllSourceMutationGuards() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("docs/runbooks/closed-epoch-total-repair"))) {
            repository = repository.getParent();
            if (repository == null) throw new IllegalStateException("Repair SQL is missing");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(PG.getJdbcUrl()); config.setUsername(PG.getUsername()); config.setPassword(PG.getPassword());
        config.setMaximumPoolSize(2); config.setMinimumIdle(0);
        try (var pool = new HikariDataSource(config); var connection = pool.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE public.codex_repair_test_fixture(token text PRIMARY KEY)");
            }
            try (var statement = connection.prepareStatement("INSERT INTO public.codex_repair_test_fixture VALUES(?)")) {
                statement.setString(1, token); statement.executeUpdate();
            }
        }
        Path evidence = Files.createTempDirectory("yaci-epoch-repair-proof-");
        var builder = new ProcessBuilder("python3", repository.resolve("docs/runbooks/closed-epoch-total-repair/tests/validate.py").toString(),
                "--testcontainer", PG.getContainerId(), "--output-directory", evidence.toString());
        builder.environment().put("YACI_REPAIR_FIXTURE_TOKEN", token);
        builder.environment().put("YACI_REPAIR_FIXTURE_PASSWORD", PG.getPassword());
        builder.directory(repository.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("runner.log").toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Repair guard deadline; evidence %s", evidence).isTrue();
        assertThat(process.exitValue()).withFailMessage("Repair guard failure; evidence %s%n%s", evidence,
                Files.readString(evidence.resolve("runner.log"))).isZero();
        var results = new com.fasterxml.jackson.databind.ObjectMapper().readTree(evidence.resolve("results.json").toFile());
        assertThat(results.path("failed").asInt()).isZero();
        assertThat(results.path("defects").asInt()).isZero();
        assertThat(results.path("passed").asInt()).isGreaterThan(40);
        System.out.println("Closed epoch repair guard evidence: " + evidence);
    }
}
