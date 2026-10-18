package com.bloxbean.cardano.yaci.store.metadata.processor;

import com.bloxbean.cardano.yaci.store.common.aspect.EnableIf;
import com.bloxbean.cardano.yaci.store.common.util.StringUtil;
import com.bloxbean.cardano.yaci.store.events.AuxDataEvent;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.metadata.domain.TxMetadataEvent;
import com.bloxbean.cardano.yaci.store.metadata.domain.TxMetadataLabel;
import com.bloxbean.cardano.yaci.store.metadata.storage.TxMetadataStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.yaci.store.metadata.MetadataStoreConfiguration.STORE_METADATA_ENABLED;

@Component
@RequiredArgsConstructor
@EnableIf(STORE_METADATA_ENABLED)
@Slf4j
public class MetadataProcessor {
    private final TxMetadataStorage metadataStorage;
    private final ApplicationEventPublisher publisher;

    private ObjectMapper objectMapper = new ObjectMapper();

    @EventListener
    @Transactional
    public void handleAuxDataEvent(AuxDataEvent auxDataEvent) {
        if (log.isDebugEnabled())
            log.debug("Received AuxDataEvent");

        EventMetadata eventMetadata = auxDataEvent.getMetadata();
        List<TxMetadataLabel> txMetadataLabelList = auxDataEvent.getTxAuxDataList().stream()
                .filter(txAuxDataEvent -> txAuxDataEvent.getAuxData() != null
                        && !StringUtil.isEmpty(txAuxDataEvent.getAuxData().getMetadataJson()))
                .map(txAuxData -> {
                    String json = txAuxData.getAuxData().getMetadataJson();
                    JsonNode jsonNode;
                    try {
                        jsonNode = objectMapper.readTree(json);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }

                    final Map<String, String> labelToCborMap = getLabelToCborMap(txAuxData.getTxHash(),
                            txAuxData.getAuxData().getCbor(), txAuxData.getAuxData().getMetadataCbor());

                    List<TxMetadataLabel> txMetadataLabels = new ArrayList<>();
                    jsonNode.fieldNames().forEachRemaining(fieldName -> {
                        String labelCbor = labelToCborMap.get(fieldName);
                        TxMetadataLabel txMetadataLabel = TxMetadataLabel.builder()
                                .slot(eventMetadata.getSlot())
                                .txHash(txAuxData.getTxHash())
                                .txIndex(txAuxData.getTxIndex())
                                .blockNumber(eventMetadata.getBlock())
                                .blockTime(eventMetadata.getBlockTime())
                                .label(fieldName)
                                .body((jsonNode.get(fieldName)).toString())
                                .cbor(labelCbor)
                                .build();

                        txMetadataLabels.add(txMetadataLabel);
                    });

                    return txMetadataLabels;
                })
                .flatMap(txMetadataLabels -> txMetadataLabels.stream())
                .collect(Collectors.toList());

        if (txMetadataLabelList.size() > 0) {
            if (log.isDebugEnabled())
                log.debug("Saving metadata >> Length : " + txMetadataLabelList.size());
            metadataStorage.saveAll(txMetadataLabelList);

            //biz event
            publisher.publishEvent(new TxMetadataEvent(eventMetadata, txMetadataLabelList));
        }
    }

    private Map<String, String> getLabelToCborMap(String txHash, String auxiliaryCbor, String metadataCbor) {
        try {
            // Older imported events may lack original bytes. Never use that fallback when
            // original bytes are present but malformed: their ordering cannot be recovered.
            return MetadataCbor.labels(auxiliaryCbor, metadataCbor);
        } catch (Exception e) {
            log.error("Error getting label to cbor map for tx: " + txHash);
            return Collections.emptyMap();
        }
    }
}
