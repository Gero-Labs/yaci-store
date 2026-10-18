package com.bloxbean.cardano.yaci.store.governance.service;

import com.bloxbean.cardano.yaci.store.governance.storage.impl.DRepAnchorResolutionStorageImpl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DRepAnchorResolverTest {
    private final DRepAnchorResolutionStorageImpl.Binding binding = new DRepAnchorResolutionStorageImpl.Binding(
            "aa".repeat(32), 0, "drep", "bb".repeat(28), "ADDR_KEYHASH", 100, "https://example.com/m", "cc".repeat(32));
    private final DRepAnchorResolutionStorageImpl.Lease lease = new DRepAnchorResolutionStorageImpl.Lease(1, "owned");
    private final DRepAnchorResolutionStorageImpl storage = mock(DRepAnchorResolutionStorageImpl.class);
    private final DRepAnchorResolverWork work = mock(DRepAnchorResolverWork.class);
    private final DRepAnchorHttpFetcher fetcher = mock(DRepAnchorHttpFetcher.class);
    private final DRepAnchorResolverProperties properties = new DRepAnchorResolverProperties();
    private DRepAnchorResolver resolver() { return new DRepAnchorResolver(storage, fetcher, work, properties); }
    private void due() {
        when(work.discover(16)).thenReturn(List.of(binding));
        when(storage.ensurePending(binding)).thenReturn(true);
        when(storage.claim(eq(binding), anyString(), eq(30))).thenReturn(Optional.of(lease));
    }
    private DRepAnchorHttpFetcher.Result failed() {
        var now = Instant.now();
        var attempt = new DRepAnchorResolutionStorageImpl.Attempt(binding.url(), "HTTP_RESPONSE_ERROR", "HTTP_RESPONSE_ERROR",
                "HTTP status 503", 503, null, null, null, now, now, "NATIVE_FETCH", null);
        return new DRepAnchorHttpFetcher.Result(null, List.of(attempt), attempt.code(), attempt.message());
    }

    @Test void defaultsAreOffAndPublicationUsesOwnedLeaseAfterNetwork() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBatchSize()).isEqualTo(16);
        due(); var failure = failed();
        when(fetcher.fetch(eq(binding), anyList(), eq(15000L))).thenReturn(failure);
        when(storage.completeFailure(eq(binding), eq(lease), eq(failure.attempts()), any(), eq(failure.code()), eq(failure.message()))).thenReturn(true);
        resolver().resolveBatch();
        var order = inOrder(work, storage, fetcher);
        order.verify(work).discover(16);
        order.verify(storage).ensurePending(binding);
        order.verify(storage).claim(eq(binding), anyString(), eq(30));
        order.verify(fetcher).fetch(eq(binding), eq(List.of(binding.url())), eq(15000L));
        order.verify(storage).completeFailure(eq(binding), eq(lease), eq(failure.attempts()), any(), eq(failure.code()), eq(failure.message()));
        order.verify(work).retryAfter(binding, lease.generation(), 3600);
    }
    @Test void failedOrRolledBackClaimNeverFetches() {
        due(); when(storage.claim(eq(binding), anyString(), eq(30))).thenReturn(Optional.empty());
        resolver().resolveBatch(); verifyNoInteractions(fetcher);
        reset(storage); when(storage.ensurePending(binding)).thenReturn(false);
        resolver().resolveBatch(); verifyNoInteractions(fetcher);
    }
    @Test void stalePublicationNeverSchedulesRetryAndSuccessUsesOriginalBytes() {
        due(); var failure = failed();
        when(fetcher.fetch(eq(binding), anyList(), anyLong())).thenReturn(failure);
        resolver().resolveBatch(); verify(work, never()).retryAfter(any(), anyLong(), anyInt());
        var raw = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(fetcher.fetch(eq(binding), anyList(), anyLong())).thenReturn(new DRepAnchorHttpFetcher.Result(raw, failure.attempts(), null, null));
        resolver().resolveBatch();
        verify(storage).completeSuccess(eq(binding), eq(lease), same(raw), any(), eq(failure.attempts()));
        verify(work, never()).retryAfter(any(), anyLong(), anyInt());
    }
    @Test void unavailableStorageStopsBatchAndNextScheduleCanRun() {
        when(work.discover(16)).thenReturn(List.of(binding, binding));
        when(storage.ensurePending(binding)).thenThrow(new org.jooq.exception.DataAccessException("offline"));
        var resolver = resolver(); resolver.resolveBatch(); resolver.resolveBatch();
        verify(storage, times(2)).ensurePending(binding); verifyNoInteractions(fetcher);
    }
    @Test void overlappingScheduleDoesNotDiscoverOrClaimTwice() throws Exception {
        CountDownLatch fetching = new CountDownLatch(1), release = new CountDownLatch(1);
        due();
        when(fetcher.fetch(eq(binding), anyList(), anyLong())).thenAnswer(call -> {
            fetching.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return failed();
        });
        var resolver = resolver(); var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(resolver::resolveBatch);
            assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();
            resolver.resolveBatch(); release.countDown(); first.get(5, TimeUnit.SECONDS);
            verify(work).discover(16); verify(storage).claim(eq(binding), anyString(), eq(30));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void configuredHashAddressedFallbacksAreFiniteAndOrdered() {
        properties.setIpfsGateways(List.of("https://gateway.example", "https://other.example/base/"));
        assertThat(resolver().candidates(binding)).containsExactly(binding.url(),
                "https://gateway.example/ipfs/f0155a0e40220" + binding.hash(),
                "https://other.example/base/ipfs/f0155a0e40220" + binding.hash());
        properties.setBatchSize(17);
        assertThatThrownBy(this::resolver).isInstanceOf(IllegalArgumentException.class);
        properties.setBatchSize(16); properties.setFetchBudgetMillis(15001);
        assertThatThrownBy(this::resolver).isInstanceOf(IllegalArgumentException.class);
        properties.setFetchBudgetMillis(15000); properties.setIpfsGateways(List.of("file:///tmp"));
        assertThatThrownBy(this::resolver).isInstanceOf(IllegalArgumentException.class);
    }
}
