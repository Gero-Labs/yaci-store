package com.bloxbean.cardano.yaci.store.script.processor;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.TransactionEvent;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.bloxbean.cardano.yaci.store.script.domain.Datum;
import com.bloxbean.cardano.yaci.store.script.domain.DatumEvent;
import com.bloxbean.cardano.yaci.store.script.storage.DatumStorage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputDatumProcessorTest {
    @Test
    void persistsDistinctInlineEncodingsWithoutCollapsingTheirLedgerHashes() {
        var storage = new CapturingDatumStorage();
        List<Object> events = new ArrayList<>();
        var processor = new OutputDatumProcessor(storage, events::add);

        processor.handleOutputDatumInTransaction(transactionEvent(List.of("00", "1800")));

        assertThat(storage.saved).hasSize(2);
        assertThat(storage.saved).extracting(Datum::getHash).containsExactlyInAnyOrder(
                "03170a2e7597b7b7e3d84c05391d139a62b157e78786d8c082f29dcf4c111314",
                "d6151a4af5dbab50abc915c1a41e5bee160989792ae1ca5d8dd7c6294ad04bc0");
        assertThat(storage.saved).extracting(Datum::getDatum).containsExactlyInAnyOrder("00", "1800");
        assertThat(events).hasSize(1);
        var event = (DatumEvent) events.getFirst();
        assertThat(event.getOutputDatums()).hasSize(2);
        assertThat(event.getOutputDatums().get(1).getDatum().getHash())
                .isEqualTo("d6151a4af5dbab50abc915c1a41e5bee160989792ae1ca5d8dd7c6294ad04bc0");
    }

    @Test
    void persistsAndPublishesOriginalMainnetInlineDatumWithLedgerIdentity() throws IOException {
        String cbor;
        try (var input = getClass().getResourceAsStream("/datum/mainnet-frwif-inline.hex")) {
            cbor = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        var storage = new CapturingDatumStorage();
        List<Object> events = new ArrayList<>();
        var processor = new OutputDatumProcessor(storage, events::add);

        processor.handleOutputDatumInTransaction(transactionEvent(List.of(cbor)));

        assertThat(storage.saved).hasSize(1);
        var stored = storage.saved.getFirst();
        assertThat(stored.getHash())
                .isEqualTo("8de29d8a97e996b439468ab227db54961fe4585bc20b2a2d6938767e4612c0d7");
        assertThat(stored.getDatum()).isEqualTo(cbor);
        assertThat(stored.getSlot()).isEqualTo(123154760L);
        assertThat(stored.getCreatedAtTx()).isEqualTo("source-tx");
        assertThat(events).hasSize(1);
        var event = (DatumEvent) events.getFirst();
        assertThat(event.getOutputDatums()).hasSize(1);
        assertThat(event.getOutputDatums().getFirst().getDatum()).isEqualTo(stored);
    }

    private TransactionEvent transactionEvent(List<String> inlineDatums) {
        return TransactionEvent.builder()
                .metadata(EventMetadata.builder().slot(123154760).block(100).build())
                .transactions(List.of(Transaction.builder()
                        .txHash("source-tx")
                        .body(TransactionBody.builder()
                                .outputs(inlineDatums.stream().map(cbor -> TransactionOutput.builder()
                                        .address("source-address").inlineDatum(cbor).build()).toList())
                                .build())
                        .witnesses(Witnesses.builder().build())
                        .build()))
                .build();
    }

    private static class CapturingDatumStorage implements DatumStorage {
        private final List<Datum> saved = new ArrayList<>();

        @Override
        public void saveAll(Collection<Datum> datumList) {
            saved.addAll(datumList);
        }

        @Override
        public void handleCommit(CommitEvent commitEvent) {
        }
    }
}
