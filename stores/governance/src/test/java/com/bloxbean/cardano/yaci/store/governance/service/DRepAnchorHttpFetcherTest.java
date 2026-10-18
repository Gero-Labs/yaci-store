package com.bloxbean.cardano.yaci.store.governance.service;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.store.governance.storage.impl.DRepAnchorResolutionStorageImpl.Binding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** All HTTP is a disposable loopback listener with a package-only pinned DNS test seam. */
class DRepAnchorHttpFetcherTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService workers;
    private final DnsResolver localDns = new DnsResolver() {
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!host.equals("anchor.test")) throw new UnknownHostException("Unconfigured offline host");
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
        }
        public String resolveCanonicalHostname(String host) { return host; }
    };

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.start();
    }
    @AfterEach void stop() { server.stop(0); workers.shutdownNow(); }
    private String url(String path) { return "http://anchor.test:" + server.getAddress().getPort() + path; }
    private Binding binding(byte[] raw) {
        return new Binding("aa".repeat(32), 0, "drep", "bb".repeat(28), "ADDR_KEYHASH", 100,
                url("/good"), HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(raw)));
    }
    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
    private DRepAnchorHttpFetcher fetcher(int max) { return new DRepAnchorHttpFetcher(new ObjectMapper(), max, localDns); }

    @Test void rawDocumentAboveOldCeilingAndOrderedObservationsArePreserved() throws Exception {
        byte[] raw = ("{ \"content\":\"" + "a".repeat(600000) + "\" }\n").getBytes(StandardCharsets.UTF_8);
        server.createContext("/unavailable", e -> send(e, 503, "fail".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/wrong", e -> send(e, 200, "{}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/good", e -> send(e, 200, raw));
        try (var fetcher = fetcher(3000000)) {
            var result = fetcher.fetch(binding(raw), List.of(url("/unavailable"), url("/wrong"), url("/good")), 15000);
            assertThat(result.raw()).isEqualTo(raw);
            assertThat(result.code()).isNull();
            assertThat(result.attempts()).extracting(a -> a.kind()).containsExactly("HTTP_RESPONSE_ERROR", "HASH_MISMATCH", "SUCCESS");
            assertThat(result.attempts()).extracting(a -> a.status()).containsExactly(503, 200, 200);
            assertThat(result.attempts().getFirst().size()).isNull();
            assertThat(result.attempts().getLast().size()).isEqualTo(raw.length);
            assertThat(result.attempts().getLast().observedHash()).isEqualTo(binding(raw).hash());
            for (var attempt : result.attempts()) {
                assertThat(attempt.source()).isEqualTo("NATIVE_FETCH");
                assertThat(attempt.receipt()).isNull();
                assertThat(attempt.completed()).isAfterOrEqualTo(attempt.started());
            }
        }
    }

    @Test void hashValidMalformedAndTrailingJsonAreRefused() throws Exception {
        server.createContext("/good", e -> send(e, 200, new byte[0]));
        for (String body : List.of("{\"x\":1} {\"x\":2}", "{\"x\":1} garbage")) {
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            server.removeContext("/good");
            server.createContext("/good", e -> send(e, 200, raw));
            try (var fetcher = fetcher(3000000)) {
                var result = fetcher.fetch(binding(raw), List.of(url("/good")), 15000);
                assertThat(result.raw()).isNull();
                assertThat(result.code()).isEqualTo("INVALID_JSON");
                assertThat(result.attempts().getFirst().observedHash()).isEqualTo(binding(raw).hash());
            }
        }
    }

    @Test void oversizeStopsWithoutDrainingRemainingBody() throws Exception {
        byte[] raw = ("{\"x\":\"" + "a".repeat(3000000) + "\"}").getBytes(StandardCharsets.UTF_8);
        server.createContext("/good", e -> {
            e.sendResponseHeaders(200, 0);
            try (var out = e.getResponseBody()) {
                out.write(raw); out.flush();
                Thread.sleep(4000);
                out.write('x');
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try (var fetcher = fetcher(3000000)) {
            long started = System.nanoTime();
            var result = fetcher.fetch(binding(raw), List.of(url("/good")), 15000);
            assertThat(result.code()).isEqualTo("SIZE_EXCEEDED");
            assertThat(result.attempts().getFirst().size()).isBetween(3000001L, 3008192L);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(3000);
        }
    }

    @Test void errorHeadersAreRecordedWithoutDrainingSlowBody() throws Exception {
        server.createContext("/good", e -> {
            e.getResponseHeaders().set("Content-Type", "text/plain");
            e.sendResponseHeaders(503, 0);
            try (var out = e.getResponseBody()) {
                out.write('x'); out.flush(); Thread.sleep(4000); out.write('x');
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try (var fetcher = fetcher(3000000)) {
            long started = System.nanoTime();
            var result = fetcher.fetch(binding("{}".getBytes(StandardCharsets.UTF_8)), List.of(url("/good")), 15000);
            assertThat(result.code()).isEqualTo("HTTP_RESPONSE_ERROR");
            assertThat(result.attempts().getFirst().status()).isEqualTo(503);
            assertThat(result.attempts().getFirst().contentType()).isEqualTo("text/plain");
            assertThat(result.attempts().getFirst().size()).isNull();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(3000);
        }
    }

    @Test void wholeExchangeDeadlineStopsStreamingAndDoesNotStartAnotherCandidate() throws Exception {
        AtomicInteger late = new AtomicInteger();
        server.createContext("/good", e -> {
            e.sendResponseHeaders(200, 0);
            try (var out = e.getResponseBody()) {
                for (int i = 0; i < 100; i++) { out.write(' '); out.flush(); Thread.sleep(50); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.createContext("/late", e -> { late.incrementAndGet(); send(e, 200, "{}".getBytes(StandardCharsets.UTF_8)); });
        try (var fetcher = fetcher(3000000)) {
            long started = System.nanoTime();
            var result = fetcher.fetch(binding("{}".getBytes(StandardCharsets.UTF_8)), List.of(url("/good"), url("/late")), 200);
            assertThat(result.code()).isEqualTo("NETWORK_ERROR");
            assertThat(result.attempts()).hasSize(1);
            assertThat(result.attempts().getFirst().message()).contains("deadline");
            assertThat(result.attempts().getFirst().status()).isEqualTo(200);
            assertThat(late).hasValue(0);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(2000);
        }
    }

    @Test void actualRedirectRepeatsDnsGuardAndNeverFetchesPrivateHost() throws Exception {
        AtomicInteger privateResolutions = new AtomicInteger();
        DnsResolver redirects = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (host.equals("anchor.test")) return localDns.resolve(host);
                privateResolutions.incrementAndGet();
                var privateDns = new DRepAnchorHttpFetcher.GuardedDns(new DnsResolver() {
                    public InetAddress[] resolve(String target) throws UnknownHostException {
                        return new InetAddress[]{InetAddress.getByAddress(new byte[]{(byte)169, (byte)254, (byte)169, (byte)254})};
                    }
                    public String resolveCanonicalHostname(String target) { return target; }
                });
                return privateDns.resolve(host);
            }
            public String resolveCanonicalHostname(String host) { return host; }
        };
        server.createContext("/good", e -> {
            e.getResponseHeaders().set("Location", "http://private.test/metadata");
            e.sendResponseHeaders(302, -1); e.close();
        });
        try (var fetcher = new DRepAnchorHttpFetcher(new ObjectMapper(), 3000000, redirects)) {
            var result = fetcher.fetch(binding("{}".getBytes(StandardCharsets.UTF_8)), List.of(url("/good")), 15000);
            assertThat(result.code()).isEqualTo("NETWORK_ERROR");
            assertThat(privateResolutions).hasValue(1);
        }
    }

    @Test void redirectCountIsBoundedAndAutomaticRetriesAreDisabled() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/good", e -> {
            int number = calls.incrementAndGet();
            e.getResponseHeaders().set("Location", url("/good?hop=" + number));
            e.sendResponseHeaders(302, -1); e.close();
        });
        try (var fetcher = fetcher(3000000)) {
            var result = fetcher.fetch(binding("{}".getBytes(StandardCharsets.UTF_8)), List.of(url("/good")), 15000);
            assertThat(result.code()).isEqualTo("NETWORK_ERROR");
            assertThat(calls).hasValue(4);
        }
    }

    @Test void dnsResolutionIsInsideWholeCandidateBudget() throws Exception {
        DnsResolver stalled = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try { Thread.sleep(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return localDns.resolve(host);
            }
            public String resolveCanonicalHostname(String host) { return host; }
        };
        try (var fetcher = new DRepAnchorHttpFetcher(new ObjectMapper(), 3000000, new DRepAnchorHttpFetcher.GuardedDns(stalled))) {
            long started = System.nanoTime();
            var result = fetcher.fetch(binding("{}".getBytes(StandardCharsets.UTF_8)), List.of(url("/good")), 200);
            assertThat(result.code()).isEqualTo("NETWORK_ERROR");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(2000);
        }
    }

    @Test void productionConstructorRejectsLiteralLoopbackWithoutOpeningSocket() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/guard", e -> { calls.incrementAndGet(); send(e, 200, "{}".getBytes(StandardCharsets.UTF_8)); });
        try (var fetcher = new DRepAnchorHttpFetcher(new ObjectMapper(), 3000000)) {
            var result = fetcher.fetch(binding("{}".getBytes(StandardCharsets.UTF_8)), List.of("http://127.0.0.1:" + server.getAddress().getPort() + "/guard"), 15000);
            assertThat(result.code()).isEqualTo("DESTINATION_BLOCKED");
            assertThat(calls).hasValue(0);
        }
    }

    @Test void blockedIpv4Ipv6AndMappedLiteralsNeverReachClientDnsOrListener() throws Exception {
        AtomicInteger dnsCalls = new AtomicInteger(), socketCalls = new AtomicInteger();
        DnsResolver never = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                dnsCalls.incrementAndGet(); return localDns.resolve("anchor.test");
            }
            public String resolveCanonicalHostname(String host) { return host; }
        };
        server.createContext("/literal", e -> { socketCalls.incrementAndGet(); send(e, 200, "{}".getBytes(StandardCharsets.UTF_8)); });
        int port = server.getAddress().getPort();
        try (var fetcher = new DRepAnchorHttpFetcher(new ObjectMapper(), 3000000, never)) {
            for (String host : List.of("127.0.0.1", "169.254.169.254", "[::1]", "[::ffff:127.0.0.1]", "[::ffff:169.254.169.254]", "[fc00::1]")) {
                var result = fetcher.fetch(binding("{}".getBytes(StandardCharsets.UTF_8)), List.of("http://" + host + ":" + port + "/literal"), 15000);
                assertThat(result.code()).isEqualTo("DESTINATION_BLOCKED");
            }
            assertThat(dnsCalls).hasValue(0); assertThat(socketCalls).hasValue(0);
        }
    }

    @Test void actualRedirectRejectsLiteralWithoutFetchingSecondHop() throws Exception {
        AtomicInteger secondHop = new AtomicInteger();
        server.createContext("/good", e -> {
            e.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/literal");
            e.sendResponseHeaders(302, -1); e.close();
        });
        server.createContext("/literal", e -> { secondHop.incrementAndGet(); send(e, 200, "{}".getBytes(StandardCharsets.UTF_8)); });
        try (var fetcher = fetcher(3000000)) {
            var result = fetcher.fetch(binding("{}".getBytes(StandardCharsets.UTF_8)), List.of(url("/good")), 15000);
            assertThat(result.code()).isEqualTo("NETWORK_ERROR");
            assertThat(secondHop).hasValue(0);
        }
    }

    @Test void uriRedirectAndDnsGuardsFailClosed() throws Exception {
        for (String value : List.of("file:///etc/passwd", "http://user@public.example/a", "http://public.example/a#fragment", "ftp://public.example")) {
            assertThat(DRepAnchorHttpFetcher.allowed(URI.create(value))).isFalse();
            var response = new BasicHttpResponse(302);
            response.addHeader("Location", value);
            assertThatThrownBy(() -> new DRepAnchorHttpFetcher.GuardedRedirect().getLocationURI(
                    new BasicHttpRequest("GET", "https://public.example/"), response, new BasicHttpContext())).isInstanceOf(org.apache.hc.core5.http.HttpException.class);
        }
        for (String ip : List.of("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1", "224.0.0.1"))
            assertThat(DRepAnchorHttpFetcher.blocked(InetAddress.getByName(ip))).isTrue();
        DnsResolver mixed = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("169.254.169.254")};
            }
            public String resolveCanonicalHostname(String host) { return host; }
        };
        assertThatThrownBy(() -> new DRepAnchorHttpFetcher.GuardedDns(mixed).resolve("offline.test")).isInstanceOf(UnknownHostException.class);
        assertThat(DRepAnchorHttpFetcher.allowed(URI.create("https://public.example/a"))).isTrue();
        assertThat(DRepAnchorHttpFetcher.blocked(InetAddress.getByName("8.8.8.8"))).isFalse();
    }
}
