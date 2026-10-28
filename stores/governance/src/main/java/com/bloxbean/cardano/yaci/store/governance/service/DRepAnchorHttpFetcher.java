package com.bloxbean.cardano.yaci.store.governance.service;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.store.governance.storage.impl.DRepAnchorResolutionStorageImpl.Attempt;
import com.bloxbean.cardano.yaci.store.governance.storage.impl.DRepAnchorResolutionStorageImpl.Binding;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Each configured candidate has guarded DNS/TLS, bounded redirects, body and whole-exchange deadline. */
public class DRepAnchorHttpFetcher implements AutoCloseable {
    public record Result(byte[] raw, List<Attempt> attempts, String code, String message) { }

    private final CloseableHttpClient client;
    private final ObjectMapper json;
    private final int maxBytes;
    private final DnsResolver dns;
    private final ScheduledExecutorService canceller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "drep-anchor-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public DRepAnchorHttpFetcher(ObjectMapper json, int maxBytes) {
        this(json, maxBytes, new GuardedDns(new SystemDefaultDnsResolver()));
    }

    /** Package-only test seam. Production always installs GuardedDns on the actual connection manager. */
    DRepAnchorHttpFetcher(ObjectMapper json, int maxBytes, DnsResolver dns) {
        if (maxBytes < 1 || maxBytes > 3000000)
            throw new IllegalArgumentException("Blockfrost body ceiling exceeded");
        this.json = json;
        this.maxBytes = maxBytes;
        this.dns = dns;
        var connections = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                .setMaxConnTotal(2).setMaxConnPerRoute(2).build();
        var config = RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(5)).setConnectionRequestTimeout(Timeout.ofSeconds(1))
                .setMaxRedirects(3).setCircularRedirectsAllowed(false).build();
        client = HttpClientBuilder.create().setConnectionManager(connections).setDefaultRequestConfig(config)
                .setRedirectStrategy(new GuardedRedirect()).disableCookieManagement().disableAutomaticRetries().build();
    }

    public Result fetch(Binding binding, List<String> candidates, long budgetMillis) {
        if (candidates == null || candidates.isEmpty() || candidates.size() > 16
                || budgetMillis < 1 || budgetMillis > 15000)
            throw new IllegalArgumentException("Finite fetch budget/candidates required");
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        List<Attempt> attempts = new ArrayList<>();
        for (String candidate : candidates) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) break;
            Instant started = Instant.now();
            URI uri;
            try {
                uri = URI.create(candidate);
                if (!allowed(uri)) throw new IllegalArgumentException("Disallowed destination");
            } catch (RuntimeException invalid) {
                attempts.add(failure(candidate, "DESTINATION_BLOCKED", "Destination scheme/host rejected",
                        null, null, null, null, started));
                continue;
            }
            HttpGet request = new HttpGet(uri);
            var cancellation = canceller.schedule(request::cancel, remaining, TimeUnit.NANOSECONDS);
            Integer status = null;
            String content = null;
            org.apache.hc.client5.http.impl.classic.CloseableHttpResponse response = null;
            try {
                if (dns instanceof GuardedDns guard) guard.deadline.set(end);
                response = client.execute(request);
                status = response.getCode();
                content = response.getFirstHeader("Content-Type") == null ? null : response.getFirstHeader("Content-Type").getValue();
                if (status < 200 || status >= 300) {
                    // Abort without draining an attacker-controlled error body.
                    request.cancel();
                    attempts.add(failure(candidate, "HTTP_RESPONSE_ERROR", "HTTP status " + status,
                            status, null, content, null, started));
                    continue;
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
                long total = 0;
                boolean oversized = false;
                if (response.getEntity() != null) {
                    var in = response.getEntity().getContent();
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        total += read;
                        if (total > maxBytes) { oversized = true; request.cancel(); break; }
                        bytes.write(chunk, 0, read);
                    }
                }
                if (oversized) {
                    attempts.add(failure(candidate, "SIZE_EXCEEDED", "Response exceeded " + maxBytes + " bytes",
                            status, null, content, total, started));
                    continue;
                }
                byte[] raw = bytes.toByteArray();
                String hash = HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(raw));
                if (!hash.equals(binding.hash())) {
                    attempts.add(failure(candidate, "HASH_MISMATCH", "Expected " + binding.hash() + " but got " + hash,
                            status, hash, content, total, started));
                    continue;
                }
                try {
                    if (json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw) == null)
                        throw new IllegalArgumentException("Empty JSON");
                } catch (Exception invalid) {
                    attempts.add(failure(candidate, "INVALID_JSON", "Response is not one complete JSON value",
                            status, hash, content, total, started));
                    continue;
                }
                attempts.add(new Attempt(candidate, "SUCCESS", null, null, status, hash, content, total,
                        started, Instant.now(), "NATIVE_FETCH", null));
                return new Result(raw, List.copyOf(attempts), null, null);
            } catch (Exception failed) {
                attempts.add(failure(candidate, "NETWORK_ERROR", request.isCancelled()
                        ? "Anchor fetch deadline exceeded" : "HTTP exchange failed",
                        status, null, content, null, started));
            } finally {
                if (dns instanceof GuardedDns guard) guard.deadline.remove();
                if (response != null) try { response.close(); } catch (java.io.IOException ignored) { /* Aborted bodies need no drain. */ }
                cancellation.cancel(false);
            }
        }
        if (attempts.isEmpty()) throw new IllegalStateException("Fetch budget ended without observation");
        return new Result(null, List.copyOf(attempts), attempts.getFirst().code(),
                attempts.stream().map(Attempt::message).collect(java.util.stream.Collectors.joining("; ")));
    }

    private static Attempt failure(String url, String code, String message, Integer status, String hash,
                                   String content, Long size, Instant started) {
        return new Attempt(url, code, code, message, status, hash, content, size,
                started, Instant.now(), "NATIVE_FETCH", null);
    }

    static boolean allowed(URI uri) {
        if (uri.getHost() == null || blockedLiteral(uri.getHost())) return false;
        return uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static boolean blockedLiteral(String host) {
        // Address literals are parsed without DNS, including IPv4-mapped IPv6 addresses.
        if (host.indexOf(':') < 0 && !host.matches("[0-9.]+")) return false;
        try { return blocked(InetAddress.getByName(host)); }
        catch (UnknownHostException invalid) { return true; }
    }

    static boolean blocked(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? (bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127
                : (bytes[0] & 0xfe) == 0xfc;
    }

    static final class GuardedDns implements DnsResolver {
        private final DnsResolver delegate;
        private final ThreadLocal<Long> deadline = new ThreadLocal<>();
        private final ThreadPoolExecutor lookups = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), r -> {
                    Thread thread = new Thread(r, "drep-anchor-dns"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        GuardedDns(DnsResolver delegate) { this.delegate = delegate; }
        public InetAddress[] resolve(String host) throws UnknownHostException {
            long remaining = deadline.get() == null ? TimeUnit.SECONDS.toNanos(5)
                    : Math.min(TimeUnit.SECONDS.toNanos(5), deadline.get() - System.nanoTime());
            if (remaining <= 0) throw new UnknownHostException("DNS deadline exceeded");
            java.util.concurrent.Future<InetAddress[]> lookup;
            try { lookup = lookups.submit(() -> delegate.resolve(host)); }
            catch (RejectedExecutionException saturated) { throw new UnknownHostException("DNS lookup budget occupied"); }
            InetAddress[] addresses;
            try { addresses = lookup.get(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); lookup.cancel(true); throw new UnknownHostException("DNS lookup interrupted");
            } catch (ExecutionException | TimeoutException failed) {
                lookup.cancel(true); throw new UnknownHostException("DNS resolution unavailable within budget");
            }
            if (addresses == null || addresses.length == 0) throw new UnknownHostException("No address");
            for (var address : addresses)
                if (address == null || blocked(address)) throw new UnknownHostException("Blocked address");
            return addresses;
        }
        public String resolveCanonicalHostname(String host) { return host.toLowerCase(Locale.ROOT); }
        void close() { lookups.shutdownNow(); }
    }

    static final class GuardedRedirect extends DefaultRedirectStrategy {
        public URI getLocationURI(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException {
            URI uri = super.getLocationURI(request, response, context);
            if (!allowed(uri)) throw new ProtocolException("Redirect destination rejected");
            return uri;
        }
        public boolean isRedirectAllowed(HttpHost current, HttpHost next, HttpRequest request, HttpContext context) {
            return ("http".equalsIgnoreCase(next.getSchemeName()) || "https".equalsIgnoreCase(next.getSchemeName()))
                    && super.isRedirectAllowed(current, next, request, context);
        }
    }

    public void close() throws Exception {
        canceller.shutdownNow();
        if (dns instanceof GuardedDns guard) guard.close();
        client.close();
    }
}
