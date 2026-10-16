package com.bloxbean.cardano.yaci.store.blockfrost.asset.service;

import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.BFAssetStorageReader;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.BFAssetQueryDeadline;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl.model.BFAssetUtxo;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BFAssetUtxoServiceTest {
    private static final String UNIT = "aa".repeat(28);
    private final BFAssetStorageReader reader = mock(BFAssetStorageReader.class);
    private final BFAssetService service = new BFAssetService(reader);

    @Test
    void populatedPageSkipsExistenceAndAggregateQueriesAndIgnoresBoundIndices() {
        when(reader.findAssetUtxos(eq(UNIT), eq(0), eq(100), eq(Order.asc), eq(100L), eq(100L), any(BFAssetQueryDeadline.class))).thenReturn(List.of(
                new BFAssetUtxo("address", "tx", 0, 42L,
                        "[{\"unit\":\"lovelace\",\"quantity\":\"99\"}]", "block", 100L, 1000L,
                        "datum-hash", null, null)));
        var result = service.getAssetUtxos(UNIT, 0, 100, Order.asc, "100:2147483647", "100:0");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getAmount().getFirst().quantity()).isEqualTo("42");
        assertThat(result.getFirst().getDataHash()).isEqualTo("datum-hash");
        assertThat(result.getFirst().getInlineDatumJson()).isNull();
        verify(reader).findAssetUtxos(eq(UNIT), eq(0), eq(100), eq(Order.asc), eq(100L), eq(100L), any(BFAssetQueryDeadline.class));
        verifyNoMoreInteractions(reader);
    }

    @Test
    void emptyPageOfMintedAssetReturnsEmptyWithoutLoadingAssetInfo() {
        when(reader.assetExists(eq(UNIT), any(BFAssetQueryDeadline.class))).thenReturn(true);
        assertThat(service.getAssetUtxos(UNIT, 999, 100, Order.asc, null, null)).isEmpty();
        var deadline = ArgumentCaptor.forClass(BFAssetQueryDeadline.class);
        verify(reader).findAssetUtxos(eq(UNIT), eq(999), eq(100), eq(Order.asc), isNull(), isNull(), deadline.capture());
        verify(reader).assetExists(eq(UNIT), same(deadline.getValue()));
        verifyNoMoreInteractions(reader);
    }

    @Test
    void neverMintedAssetReturnsNotFoundAfterTheEmptyPage() {
        assertThatThrownBy(() -> service.getAssetUtxos(UNIT, 0, 100, Order.asc, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(reader).findAssetUtxos(eq(UNIT), eq(0), eq(100), eq(Order.asc), isNull(), isNull(), any(BFAssetQueryDeadline.class));
        verify(reader).assetExists(eq(UNIT), any(BFAssetQueryDeadline.class));
        verifyNoMoreInteractions(reader);
    }

    @Test
    void malformedIndexedDatumDoesNotReturnAResponseWithFabricatedNullJson() {
        when(reader.findAssetUtxos(eq(UNIT), eq(0), eq(100), eq(Order.asc), isNull(), isNull(), any(BFAssetQueryDeadline.class))).thenReturn(List.of(
                new BFAssetUtxo("address", "tx", 0, 42L, null, "block", 100L, 1000L,
                        "datum-hash", "ff", null)));
        assertThatThrownBy(() -> service.getAssetUtxos(UNIT, 0, 100, Order.asc, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void cancelledQueryStopsWithoutAnExistenceLookup() {
        when(reader.findAssetUtxos(eq(UNIT), eq(0), eq(100), eq(Order.asc), isNull(), isNull(), any(BFAssetQueryDeadline.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Asset query deadline exceeded"));
        assertThatThrownBy(() -> service.getAssetUtxos(UNIT, 0, 100, Order.asc, null, null))
                .isInstanceOf(ResponseStatusException.class);
        verify(reader).findAssetUtxos(eq(UNIT), eq(0), eq(100), eq(Order.asc), isNull(), isNull(), any(BFAssetQueryDeadline.class));
        verifyNoMoreInteractions(reader);
    }

    @Test
    void boundsAcceptSignedDecimalButRejectOverflowNegativeAndReversedHeights() {
        when(reader.assetExists(eq(UNIT), any(BFAssetQueryDeadline.class))).thenReturn(true);
        assertThat(service.getAssetUtxos(UNIT, 0, 100, Order.asc, "+100:+2", "2147483647:-0")).isEmpty();
        verify(reader).findAssetUtxos(eq(UNIT), eq(0), eq(100), eq(Order.asc), eq(100L), eq(2147483647L), any(BFAssetQueryDeadline.class));
        reset(reader);
        for (String bound : List.of("-1", "2147483648", "1:2147483648", "1:-1", "1.0", "1e1")) {
            assertThatThrownBy(() -> service.getAssetUtxos(UNIT, 0, 100, Order.asc, bound, null))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThatThrownBy(() -> service.getAssetUtxos(UNIT, 0, 100, Order.asc, "101", "100"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(reader);
    }

    @Test
    void requiredMetadataIsNeverReplacedByNullOrZero() {
        var invalid = new ArrayList<BFAssetUtxo>();
        invalid.add(new BFAssetUtxo(null, "tx", 0, 42L, null, "block", 100L, 1000L, null, null, null));
        invalid.add(new BFAssetUtxo("address", null, 0, 42L, null, "block", 100L, 1000L, null, null, null));
        invalid.add(new BFAssetUtxo("address", "tx", null, 42L, null, "block", 100L, 1000L, null, null, null));
        invalid.add(new BFAssetUtxo("address", "tx", 0, null, null, "block", 100L, 1000L, null, null, null));
        invalid.add(new BFAssetUtxo("address", "tx", 0, 42L, null, null, 100L, 1000L, null, null, null));
        invalid.add(new BFAssetUtxo("address", "tx", 0, 42L, null, "block", null, 1000L, null, null, null));
        invalid.add(new BFAssetUtxo("address", "tx", 0, 42L, null, "block", 100L, null, null, null, null));
        for (var output : invalid) {
            when(reader.findAssetUtxos(eq(UNIT), eq(0), eq(100), eq(Order.asc), isNull(), isNull(), any(BFAssetQueryDeadline.class)))
                    .thenReturn(List.of(output));
            assertThatThrownBy(() -> service.getAssetUtxos(UNIT, 0, 100, Order.asc, null, null))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }
    }
}
