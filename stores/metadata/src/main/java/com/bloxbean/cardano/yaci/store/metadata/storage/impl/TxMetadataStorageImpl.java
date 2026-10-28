package com.bloxbean.cardano.yaci.store.metadata.storage.impl;

import com.bloxbean.cardano.yaci.store.metadata.domain.TxMetadataLabel;
import com.bloxbean.cardano.yaci.store.metadata.storage.TxMetadataStorage;
import com.bloxbean.cardano.yaci.store.metadata.storage.impl.mapper.MetadataMapper;
import com.bloxbean.cardano.yaci.store.metadata.storage.impl.model.TxMetadataLabelEntity;
import com.bloxbean.cardano.yaci.store.metadata.storage.impl.repository.TxMetadataLabelRepository;
import com.bloxbean.cardano.yaci.store.plugin.aspect.Plugin;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class TxMetadataStorageImpl implements TxMetadataStorage {
    private final static String PLUGIN_METADATA_SAVE = "metadata.save";

    private final TxMetadataLabelRepository metadataLabelRepository;
    private final MetadataMapper metadataMapper;
    private final DSLContext dsl;

    @Override
    @Plugin(key = PLUGIN_METADATA_SAVE)
    @Transactional
    public List<TxMetadataLabel> saveAll(@NonNull List<TxMetadataLabel> txMetadataLabelList) {
        if (dsl.dialect().family() == SQLDialect.POSTGRES)
            return savePostgres(txMetadataLabelList);

        // Preserve the existing persistence path for other supported dialects.
        List<TxMetadataLabelEntity> txMetadataLabelEntities = txMetadataLabelList.stream()
                .map(metadataMapper::toTxMetadataLabelEntity)
                .toList();
        List<TxMetadataLabelEntity> savedEntities = metadataLabelRepository.saveAll(txMetadataLabelEntities);
        return savedEntities.stream().map(metadataMapper::toTxMetadataLabel).toList();
    }

    private List<TxMetadataLabel> savePostgres(List<TxMetadataLabel> labels) {
        if (labels.isEmpty()) return List.of();
        var grouped = labels.stream().collect(Collectors.groupingBy(MetadataKey::of,
                LinkedHashMap::new, Collectors.toList()));
        var keys = grouped.keySet().stream().sorted(Comparator.comparingLong(MetadataKey::lockId)
                .thenComparing(MetadataKey::txHash).thenComparing(MetadataKey::slot)
                .thenComparing(MetadataKey::label)).toList();
        String previousTimeout = dsl.fetchOne("select current_setting('lock_timeout')").get(0, String.class);
        int previousMillis = dsl.fetchOne("select setting from pg_settings where name='lock_timeout'")
                .get(0, Integer.class);
        boolean changedTimeout = previousMillis == 0 || previousMillis > 250;
        if (changedTimeout) dsl.fetchOne("select set_config('lock_timeout', '250ms', true)");

        // All writers must use this guard. It adopts legacy random UUIDs without a table migration.
        // Acquire all guards in lock-id order, including hash collisions, to avoid batch deadlocks.
        for (var key : keys) dsl.fetchOne("select pg_advisory_xact_lock(?)", key.lockId());
        Map<MetadataKey, TxMetadataLabelEntity> saved = new LinkedHashMap<>();
        for (var key : keys) {
            var rows = dsl.fetch("select * from transaction_metadata where tx_hash=? and slot=? and label=? for update",
                    key.txHash(), key.slot(), key.label());
            if (rows.size() > 1)
                throw new IllegalStateException("Metadata replay refused: multiple existing UUIDs for " + key);
            boolean exists = !rows.isEmpty();
            var incoming = grouped.get(key);
            var entity = exists ? entity(rows.get(0)) : metadataMapper.toTxMetadataLabelEntity(incoming.get(0));
            if (!exists) entity.setId(key.uuid());
            boolean enriched = false;
            for (var label : incoming) enriched |= reconcile(entity, label, key);
            if (!exists) {
                entity.setUpdateDateTime(LocalDateTime.now());
                dsl.execute("insert into transaction_metadata(id, tx_hash, slot, label, body, cbor, tx_index, "
                                + "block, block_time, update_datetime) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        entity.getId(), key.txHash(), key.slot(), key.label(), entity.getBody(), entity.getCbor(),
                        entity.getTxIndex(), entity.getBlockNumber(), entity.getBlockTime(), entity.getUpdateDateTime());
            } else if (enriched) {
                entity.setUpdateDateTime(LocalDateTime.now());
                dsl.execute("update transaction_metadata set cbor=?, tx_index=?, block=?, block_time=?, "
                                + "update_datetime=? where id=?", entity.getCbor(), entity.getTxIndex(),
                        entity.getBlockNumber(), entity.getBlockTime(), entity.getUpdateDateTime(), entity.getId());
            }
            saved.put(key, entity);
        }
        // On failure the transaction rolls back the local setting along with every label write.
        // Restore on success so the remaining event handlers retain their caller's budget.
        if (changedTimeout) dsl.fetchOne("select set_config('lock_timeout', ?, true)", previousTimeout);
        return labels.stream().map(label -> metadataMapper.toTxMetadataLabel(saved.get(MetadataKey.of(label)))).toList();
    }

    private static TxMetadataLabelEntity entity(Record row) {
        return TxMetadataLabelEntity.builder().id(row.get("id", UUID.class))
                .txHash(row.get("tx_hash", String.class)).slot(row.get("slot", Long.class))
                .label(row.get("label", String.class)).body(row.get("body", String.class))
                .cbor(row.get("cbor", String.class)).txIndex(row.get("tx_index", Integer.class))
                .blockNumber(row.get("block", Long.class)).blockTime(row.get("block_time", Long.class))
                .updateDateTime(row.get("update_datetime", LocalDateTime.class)).build();
    }

    private static boolean reconcile(TxMetadataLabelEntity stored, TxMetadataLabel incoming, MetadataKey key) {
        if (!Objects.equals(stored.getBody(), incoming.getBody())) conflict(key, "body");
        boolean enriched = enrich(stored, incoming.getCbor(), TxMetadataLabelEntity::getCbor,
                TxMetadataLabelEntity::setCbor, key, "cbor");
        enriched |= enrich(stored, incoming.getTxIndex(), TxMetadataLabelEntity::getTxIndex,
                TxMetadataLabelEntity::setTxIndex, key, "tx_index");
        enriched |= enrich(stored, incoming.getBlockNumber(), TxMetadataLabelEntity::getBlockNumber,
                TxMetadataLabelEntity::setBlockNumber, key, "block");
        enriched |= enrich(stored, incoming.getBlockTime(), TxMetadataLabelEntity::getBlockTime,
                TxMetadataLabelEntity::setBlockTime, key, "block_time");
        return enriched;
    }

    private static <T> boolean enrich(TxMetadataLabelEntity stored, T incoming,
                                     Function<TxMetadataLabelEntity, T> getter,
                                     BiConsumer<TxMetadataLabelEntity, T> setter, MetadataKey key, String field) {
        T present = getter.apply(stored);
        if (present != null && incoming != null && !present.equals(incoming)) conflict(key, field);
        if (present == null && incoming != null) {
            setter.accept(stored, incoming);
            return true;
        }
        return false;
    }

    private static void conflict(MetadataKey key, String field) {
        throw new IllegalStateException("Metadata replay conflict in " + field + " for " + key);
    }

    private record MetadataKey(String txHash, Long slot, String label) {
        private static MetadataKey of(TxMetadataLabel metadata) {
            if (metadata.getTxHash() == null || metadata.getSlot() == null || metadata.getLabel() == null)
                throw new IllegalArgumentException("Metadata replay requires tx_hash, slot and outer label");
            return new MetadataKey(metadata.getTxHash(), metadata.getSlot(), metadata.getLabel());
        }

        private UUID uuid() {
            return UUID.nameUUIDFromBytes(("yaci:transaction_metadata:v1\0" + txHash + "\0" + slot + "\0" + label)
                    .getBytes(StandardCharsets.UTF_8));
        }

        private long lockId() {
            UUID uuid = uuid();
            return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        }
    }

    @Override
    public int deleteBySlotGreaterThan(long slot) {
        return metadataLabelRepository.deleteBySlotGreaterThan(slot);
    }
}
