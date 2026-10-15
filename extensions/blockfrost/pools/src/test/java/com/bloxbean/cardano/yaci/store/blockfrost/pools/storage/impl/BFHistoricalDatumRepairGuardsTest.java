package com.bloxbean.cardano.yaci.store.blockfrost.pools.storage.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Real SQL proof; the Python runner cannot reset an unowned database. */
@Testcontainers(disabledWithoutDocker = true)
class BFHistoricalDatumRepairGuardsTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("datum_repair_test").withUsername("datum_repair_test").withPassword("datum_repair_test")
            .withStartupAttempts(3);

    @Test void retainedBytesAndProvenanceRepairGuards() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("docs/runbooks/historical-datum-repair"))) {
            repository = repository.getParent();
            if (repository == null) throw new IllegalStateException("Datum repair tooling is missing");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(PG.getJdbcUrl()); config.setUsername(PG.getUsername()); config.setPassword(PG.getPassword());
        config.setMaximumPoolSize(2); config.setMinimumIdle(0);
        try (var pool = new HikariDataSource(config); var connection = pool.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE public.codex_datum_repair_test_fixture(token text PRIMARY KEY)");
            }
            try (var statement = connection.prepareStatement("INSERT INTO public.codex_datum_repair_test_fixture VALUES(?)")) {
                statement.setString(1, token); statement.executeUpdate();
            }
        }
        Path evidence = Files.createTempDirectory("yaci-datum-repair-proof-");
        var rejected = run(repository, evidence, "0".repeat(32), "wrong-marker.log");
        assertThat(rejected.exitValue()).as("Wrong fixture token must fail before DROP").isNotZero();
        try (var pool = new HikariDataSource(config); var connection = pool.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM pg_namespace WHERE nspname='yaci_store'")) {
            rows.next(); assertThat(rows.getInt(1)).isZero();
        }
        var process = run(repository, evidence, token, "runner.log");
        assertThat(process.exitValue()).withFailMessage("Datum guard failure; evidence %s%n%s", evidence,
                Files.readString(evidence.resolve("runner.log"))).isZero();
        var results = new ObjectMapper().readTree(evidence.resolve("results.json").toFile());
        assertThat(results.path("failures").asInt()).isZero();
        assertThat(results.path("errors").asInt()).isZero();
        assertThat(results.path("tests").asInt()).isGreaterThanOrEqualTo(14);
        System.out.println("Historical datum repair guard evidence: " + evidence);
    }

    private Process run(Path repository, Path evidence, String token, String log) throws Exception {
        var builder = new ProcessBuilder("python3", repository.resolve("docs/runbooks/historical-datum-repair/tests/validate.py").toString(),
                "--container", PG.getContainerId(), "--evidence", evidence.toString());
        builder.environment().put("YACI_DATUM_REPAIR_FIXTURE_TOKEN", token);
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.directory(repository.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve(log).toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Datum guard deadline; evidence %s", evidence).isTrue();
        return process;
    }
}
