package com.bloxbean.cardano.yaci.store.blockfrost.metadata.storage.impl;

import com.bloxbean.cardano.yaci.store.blockfrost.metadata.dto.BFMetadataLabelDto;
import com.bloxbean.cardano.yaci.store.blockfrost.metadata.storage.BFMetadataStorageReader;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BFMetadataStorageReaderImpl implements BFMetadataStorageReader {

    private static final Table<?> TX_METADATA = DSL.table(DSL.name("transaction_metadata"));
    private static final Field<String> LABEL = DSL.field(DSL.name("label"), String.class);

    private final DSLContext dsl;

    @Override
    public List<BFMetadataLabelDto> findLabelsWithCount(int page, int count, Order order) {
        int offset = Math.max(page, 0) * count;
        Field<BigDecimal> labelAsNumeric = LABEL.cast(BigDecimal.class);
        SortField<?> orderBy = order == Order.desc ? labelAsNumeric.desc() : labelAsNumeric.asc();

        Field<Long> cntField = DSL.count().cast(Long.class).as("cnt");

        var query = dsl.select(LABEL, cntField)
                .from(TX_METADATA)
                .groupBy(LABEL)
                .orderBy(orderBy)
                .limit(count)
                .offset(offset);

        log.debug("[BFMetadataStorageReader] findLabelsWithCount SQL: {}", query.getSQL(ParamType.INLINED));

        return query.fetch(record -> BFMetadataLabelDto.builder()
                .label(record.get(LABEL))
                .cip10(null)
                .count(String.valueOf(record.get(cntField)))
                .build());
    }
    @Override
    public List<com.bloxbean.cardano.yaci.store.metadata.domain.TxMetadataLabel> findByLabel(
            String label, int page, int count, Order order) {
        // Hosted Blockfrost sorts by tx.id: chain slot and transaction position. Hashes
        // are not transaction positions and shift same-block rows across page boundaries.
        Table<?> metadata = TX_METADATA.as("m");
        Table<?> transaction = DSL.table(DSL.name("transaction")).as("t");
        Field<String> hash = DSL.field(DSL.name("m", "tx_hash"), String.class);
        Field<Integer> storedIndex = DSL.field(DSL.name("m", "tx_index"), Integer.class);
        Field<Integer> txIndex = DSL.coalesce(storedIndex,
                DSL.field(DSL.name("t", "tx_index"), Integer.class));
        Field<Long> slot = DSL.field(DSL.name("m", "slot"), Long.class);
        Field<String> body = DSL.field(DSL.name("m", "body"), String.class);
        Field<String> cbor = DSL.field(DSL.name("m", "cbor"), String.class);
        Field<String> metadataLabel = DSL.field(DSL.name("m", "label"), String.class);
        return dsl.select(hash, txIndex, slot, metadataLabel, body, cbor).from(metadata)
                .leftJoin(transaction).on(hash.eq(DSL.field(DSL.name("t", "tx_hash"), String.class))
                        .and(storedIndex.isNull()))
                .where(metadataLabel.eq(label))
                .orderBy(order == Order.desc ? slot.desc() : slot.asc(),
                        order == Order.desc ? txIndex.desc() : txIndex.asc())
                .limit(count).offset(Math.max(page, 0) * count)
                .fetch(record -> com.bloxbean.cardano.yaci.store.metadata.domain.TxMetadataLabel.builder()
                        .txHash(record.get(hash)).txIndex(record.get(txIndex)).slot(record.get(slot))
                        .label(record.get(metadataLabel)).body(record.get(body)).cbor(record.get(cbor)).build());
    }

}
