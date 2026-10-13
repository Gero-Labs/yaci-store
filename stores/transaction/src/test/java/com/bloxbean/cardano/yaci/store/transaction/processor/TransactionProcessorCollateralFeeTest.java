package com.bloxbean.cardano.yaci.store.transaction.processor;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.store.client.utxo.UtxoClient;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.UtxoKey;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.RollbackEvent;
import com.bloxbean.cardano.yaci.store.events.TransactionEvent;
import com.bloxbean.cardano.yaci.store.events.internal.PreCommitEvent;
import com.bloxbean.cardano.yaci.store.transaction.TransactionStoreProperties;
import com.bloxbean.cardano.yaci.store.transaction.domain.Txn;
import com.bloxbean.cardano.yaci.store.transaction.domain.event.TxnEvent;
import com.bloxbean.cardano.yaci.store.transaction.storage.InvalidTransactionStorage;
import com.bloxbean.cardano.yaci.store.transaction.storage.TransactionCborStorage;
import com.bloxbean.cardano.yaci.store.transaction.storage.TransactionStorage;
import com.bloxbean.cardano.yaci.store.transaction.storage.TransactionWitnessStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionProcessorCollateralFeeTest {
    @Mock private TransactionStorage transactionStorage;
    @Mock private TransactionCborStorage transactionCborStorage;
    @Mock private TransactionWitnessStorage transactionWitnessStorage;
    @Mock private InvalidTransactionStorage invalidTransactionStorage;
    @Mock private UtxoClient utxoClient;
    @Mock private ApplicationEventPublisher publisher;
    @Captor private ArgumentCaptor<List<Txn>> savedTransactions;

    private TransactionProcessor processor;
    private final EventMetadata metadata = EventMetadata.builder()
            .slot(100).block(10).blockHash("block").epochNumber(1).blockTime(1000).build();

    @BeforeEach
    void setUp() {
        processor = new TransactionProcessor(transactionStorage, transactionCborStorage, transactionWitnessStorage,
                invalidTransactionStorage, new ObjectMapper(), new FeeResolver(utxoClient), publisher,
                TransactionStoreProperties.builder().saveCbor(false).build());
    }

    @Test
    void unresolvedFeeMustNotSaveNullTransactionOrPublishIt() {
        when(utxoClient.getUtxosByIds(any())).thenReturn(List.of());
        queue(invalidTransaction("first"));

        assertThatThrownBy(() -> processor.handleCollateralFee(new PreCommitEvent(metadata)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("first");

        verifyNoInteractions(transactionStorage, publisher);
    }

    @Test
    void resolveWholeBatchBeforeSavingAndRetryRetainedTransactionsOnce() {
        when(utxoClient.getUtxosByIds(any())).thenReturn(List.of());
        queue(invalidTransaction("first"), invalidTransaction("second"));
        doAnswer(invocation -> {
            List<UtxoKey> keys = invocation.getArgument(0);
            return keys.getFirst().getTxHash().equals("first-collateral") ? collateral("first", 8_000_000) : List.of();
        }).when(utxoClient).getUtxosByIds(any());

        assertThatThrownBy(() -> processor.handleCollateralFee(new PreCommitEvent(metadata)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("second");
        verifyNoInteractions(transactionStorage, publisher);

        doAnswer(invocation -> {
            List<UtxoKey> keys = invocation.getArgument(0);
            boolean first = keys.getFirst().getTxHash().equals("first-collateral");
            return collateral(first ? "first" : "second", first ? 8_000_000 : 3_000_000);
        }).when(utxoClient).getUtxosByIds(any());
        processor.handleCollateralFee(new PreCommitEvent(metadata));
        processor.handleCollateralFee(new PreCommitEvent(metadata));

        verify(transactionStorage).saveAll(savedTransactions.capture());
        assertThat(savedTransactions.getValue()).extracting(Txn::getFee)
                .containsExactly(BigInteger.valueOf(8_000_000), BigInteger.valueOf(3_000_000));
        var published = ArgumentCaptor.forClass(TxnEvent.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue().getTxnList()).hasSize(2);
    }

    @Test
    void storageFailurePropagatesWithoutPublishingTransactions() {
        when(utxoClient.getUtxosByIds(any())).thenReturn(List.of());
        queue(invalidTransaction("first"));
        when(utxoClient.getUtxosByIds(any())).thenReturn(collateral("first", 8_000_000));
        doThrow(new IllegalStateException("storage failed")).when(transactionStorage).saveAll(any());

        assertThatThrownBy(() -> processor.handleCollateralFee(new PreCommitEvent(metadata)))
                .isInstanceOf(IllegalStateException.class).hasMessage("storage failed");
        verifyNoInteractions(publisher);
    }

    @Test
    void rollbackDiscardsPendingTransactionsBeforeReplay() {
        when(utxoClient.getUtxosByIds(any())).thenReturn(List.of());
        queue(invalidTransaction("first"));

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionProcessor.class, () -> processor);
            context.refresh();
            context.publishEvent(RollbackEvent.builder()
                    .rollbackTo(new Point(90, "before")).currentPoint(new Point(100, "block")).build());
        }
        processor.handleCollateralFee(new PreCommitEvent(metadata));

        verifyNoInteractions(transactionStorage, publisher);
    }

    @Test
    void unresolvedTransactionFeesStopPrecommitBeforeDefaultPluginListeners() {
        when(utxoClient.getUtxosByIds(any())).thenReturn(List.of());
        queue(invalidTransaction("first"));
        var plugin = new PrecommitPlugin();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PrecommitPlugin.class, () -> plugin);
            context.registerBean(TransactionProcessor.class, () -> processor);
            context.refresh();

            assertThatThrownBy(() -> context.publishEvent(new PreCommitEvent(metadata)))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(plugin.called).isFalse();
        verifyNoInteractions(transactionStorage, publisher);
    }

    static class PrecommitPlugin {
        boolean called;

        @EventListener
        public void onPrecommit(PreCommitEvent event) {
            called = true;
        }
    }

    private void queue(Transaction... transactions) {
        processor.handleTransactionEvent(TransactionEvent.builder()
                .metadata(metadata).transactions(List.of(transactions)).build());
    }

    private Transaction invalidTransaction(String id) {
        return Transaction.builder().txHash(id).slot(100).blockNumber(10).invalid(true)
                .body(TransactionBody.builder().txHash(id).inputs(Set.of()).outputs(List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .collateralInputs(Set.of(new TransactionInput(id + "-collateral", 0))).build()).build();
    }

    private List<AddressUtxo> collateral(String id, long lovelace) {
        return List.of(AddressUtxo.builder().txHash(id + "-collateral").outputIndex(0)
                .lovelaceAmount(BigInteger.valueOf(lovelace)).build());
    }
}
