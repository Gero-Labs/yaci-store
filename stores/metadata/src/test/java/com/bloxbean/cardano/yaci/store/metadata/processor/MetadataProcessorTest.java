package com.bloxbean.cardano.yaci.store.metadata.processor;

import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.store.events.AuxDataEvent;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.domain.TxAuxData;
import com.bloxbean.cardano.yaci.store.metadata.domain.TxMetadataLabel;
import com.bloxbean.cardano.yaci.store.metadata.storage.TxMetadataStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MetadataProcessorTest {
    @Test
    void storesOrderedSingletonLabelFromOriginalAuxiliaryDataInsteadOfCanonicalizedMetadata() throws IOException {
        String expected = fixture("10d3d8ff-label721-blockfrost.hex");
        String canonicalized = fixture("10d3d8ff-label721-nexus.hex");
        TxMetadataStorage storage = mock(TxMetadataStorage.class);
        MetadataProcessor processor = new MetadataProcessor(storage, mock(ApplicationEventPublisher.class));
        AuxData auxData = AuxData.builder()
                .cbor("d90103a100" + expected)
                .metadataCbor(canonicalized)
                .metadataJson("{\"721\":{}}")
                .build();

        processor.handleAuxDataEvent(AuxDataEvent.builder()
                .metadata(EventMetadata.builder().slot(200677554).build())
                .txAuxDataList(List.of(TxAuxData.builder()
                        .txHash("10d3d8ffd9faf14ebfa57e999a9c448972f21ccbae0bcf9f3cd0ab1240870a6e")
                        .txIndex(0).auxData(auxData).build()))
                .build());

        ArgumentCaptor<List<TxMetadataLabel>> labels = ArgumentCaptor.forClass(List.class);
        verify(storage).saveAll(labels.capture());
        assertThat(labels.getValue()).singleElement().satisfies(label -> {
            assertThat(label.getLabel()).isEqualTo("721");
            assertThat(label.getCbor()).isEqualTo(expected);
        });
    }

    @Test
    void retainsJsonButDoesNotStoreCanonicalFallbackForMalformedOriginalBytes() {
        TxMetadataStorage storage = mock(TxMetadataStorage.class);
        MetadataProcessor processor = new MetadataProcessor(storage, mock(ApplicationEventPublisher.class));
        AuxData auxData = AuxData.builder().cbor("a1019f01")
                .metadataCbor("a10101").metadataJson("{\"1\":1}").build();
        processor.handleAuxDataEvent(AuxDataEvent.builder()
                .metadata(EventMetadata.builder().slot(1).build())
                .txAuxDataList(List.of(TxAuxData.builder().txHash("test").auxData(auxData).build()))
                .build());

        ArgumentCaptor<List<TxMetadataLabel>> labels = ArgumentCaptor.forClass(List.class);
        verify(storage).saveAll(labels.capture());
        assertThat(labels.getValue()).singleElement().satisfies(label -> {
            assertThat(label.getBody()).isEqualTo("1");
            assertThat(label.getCbor()).isNull();
        });
    }

    private static String fixture(String name) throws IOException {
        try (var input = MetadataProcessorTest.class.getResourceAsStream("/metadata/" + name)) {
            if (input == null) throw new IOException("Missing metadata fixture: " + name);
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
    }
}
