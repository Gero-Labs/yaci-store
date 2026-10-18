package com.bloxbean.cardano.yaci.store.governance.service;

import com.bloxbean.cardano.yaci.store.governance.storage.impl.DRepAnchorResolutionStorageImpl;
import org.jooq.exception.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTP runs after discovery and lease transactions, before a separate publication transaction. */
public class DRepAnchorResolver {
    private final DRepAnchorResolutionStorageImpl storage;
    private final DRepAnchorHttpFetcher fetcher;
    private final DRepAnchorResolverWork work;
    private final DRepAnchorResolverProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public DRepAnchorResolver(DRepAnchorResolutionStorageImpl storage, DRepAnchorHttpFetcher fetcher,
                              DRepAnchorResolverWork work, DRepAnchorResolverProperties properties) {
        properties.validate();
        this.storage = storage;
        this.fetcher = fetcher;
        this.work = work;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${store.governance.anchor-resolver-delay-ms:30000}", scheduler = "drepAnchorTaskScheduler")
    public void resolveBatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (var binding : work.discover(properties.getBatchSize())) {
                try {
                    if (!storage.ensurePending(binding)) continue;
                    var lease = storage.claim(binding, UUID.randomUUID().toString(), 30);
                    if (lease.isEmpty()) continue;
                    var result = fetcher.fetch(binding, candidates(binding), properties.getFetchBudgetMillis());
                    var provenance = DRepAnchorResolutionStorageImpl.Provenance.nativeFetch(result.attempts().getLast().completed());
                    if (result.raw() != null) {
                        storage.completeSuccess(binding, lease.get(), result.raw(), provenance, result.attempts());
                    } else if (storage.completeFailure(binding, lease.get(), result.attempts(), provenance, result.code(), result.message())) {
                        work.retryAfter(binding, lease.get().generation(), properties.getRetrySeconds());
                    }
                } catch (DataAccessException unavailable) {
                    // Leave this owned lease to expire; do not amplify unavailable storage with more work.
                    break;
                }
            }
        } finally {
            running.set(false);
        }
    }

    List<String> candidates(DRepAnchorResolutionStorageImpl.Binding binding) {
        List<String> result = new ArrayList<>();
        result.add(binding.url());
        // CIDv1 + raw codec + blake2b-256 multihash, encoded as base16 multibase.
        String cid = "f0155a0e40220" + binding.hash();
        for (String gateway : properties.getIpfsGateways()) {
            String base = gateway.endsWith("/") ? gateway.substring(0, gateway.length() - 1) : gateway;
            String candidate = base + "/ipfs/" + cid;
            if (!result.contains(candidate)) result.add(candidate);
        }
        return List.copyOf(result);
    }
}
