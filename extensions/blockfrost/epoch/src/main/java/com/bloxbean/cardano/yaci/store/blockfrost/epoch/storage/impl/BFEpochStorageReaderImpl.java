package com.bloxbean.cardano.yaci.store.blockfrost.epoch.storage.impl;

import com.bloxbean.cardano.yaci.store.adapot.domain.EpochStake;
import com.bloxbean.cardano.yaci.store.blockfrost.epoch.storage.BFEpochStorageReader;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.epochaggr.domain.Epoch;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.yaci.store.blocks.jooq.Tables.BLOCK;
import static com.bloxbean.cardano.yaci.store.epoch_aggr.jooq.Tables.EPOCH;
import static com.bloxbean.cardano.yaci.store.adapot.jooq.Tables.EPOCH_STAKE;
import static com.bloxbean.cardano.yaci.store.adapot.jooq.Tables.EPOCH_STAKE_TOTAL;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.noCondition;

@Component
@RequiredArgsConstructor
public class BFEpochStorageReaderImpl implements BFEpochStorageReader {
    private final DSLContext dsl;
    @Value("${store.adapot.epoch-stake-totals-enabled:false}")
    private boolean epochStakeTotalsEnabled;

    @Override
    public List<Epoch> findNextEpochs(int epoch, int page, int count) {
        int offset = Math.max(page, 0) * count;

        return dsl.select(EPOCH.NUMBER, EPOCH.BLOCK_COUNT, EPOCH.TRANSACTION_COUNT, EPOCH.TOTAL_OUTPUT,
                        EPOCH.TOTAL_FEES, EPOCH.START_TIME, EPOCH.END_TIME, EPOCH.MAX_SLOT)
                .from(EPOCH)
                .where(EPOCH.NUMBER.gt((long) epoch))
                .orderBy(EPOCH.NUMBER.asc())
                .limit(count)
                .offset(offset)
                .fetch(this::toEpoch);
    }

    @Override
    public List<Epoch> findPreviousEpochs(int epoch, int page, int count) {
        int offset = Math.max(page, 0) * count;

        // Select N nearest predecessors (DESC), then return in ascending order to match Blockfrost spec
        var subSelect = dsl.select(EPOCH.NUMBER)
                .from(EPOCH)
                .where(EPOCH.NUMBER.lt((long) epoch))
                .orderBy(EPOCH.NUMBER.desc())
                .limit(count)
                .offset(offset);

        return dsl.select(EPOCH.NUMBER, EPOCH.BLOCK_COUNT, EPOCH.TRANSACTION_COUNT, EPOCH.TOTAL_OUTPUT,
                        EPOCH.TOTAL_FEES, EPOCH.START_TIME, EPOCH.END_TIME, EPOCH.MAX_SLOT)
                .from(EPOCH)
                .where(EPOCH.NUMBER.in(subSelect))
                .orderBy(EPOCH.NUMBER.asc())
                .fetch(this::toEpoch);
    }

    @Override
    public List<String> findBlockHashesByEpoch(int epoch, int page, int count, Order order) {
        int offset = Math.max(page, 0) * count;
        SortField<?> orderBy = order == Order.desc ? BLOCK.SLOT.desc() : BLOCK.SLOT.asc();

        return dsl.select(BLOCK.HASH)
                .from(BLOCK)
                .where(BLOCK.EPOCH.eq(epoch))
                .orderBy(orderBy)
                .limit(count)
                .offset(offset)
                .fetch(record -> record.get(BLOCK.HASH));
    }

    @Override
    public List<String> findBlockHashesByEpochAndPool(int epoch, String poolId, int page, int count, Order order) {
        int offset = Math.max(page, 0) * count;
        SortField<?> orderBy = order == Order.desc ? BLOCK.SLOT.desc() : BLOCK.SLOT.asc();

        return dsl.select(BLOCK.HASH)
                .from(BLOCK)
                .where(BLOCK.EPOCH.eq(epoch))
                .and(BLOCK.SLOT_LEADER.eq(poolId))
                .orderBy(orderBy)
                .limit(count)
                .offset(offset)
                .fetch(record -> record.get(BLOCK.HASH));
    }

    @Override
    public List<EpochStake> findEpochStakes(int activeEpoch, int page, int count) {
        return findEpochStakes(activeEpoch, page, count, noCondition());
    }

    @Override
    public List<EpochStake> findEpochStakesByPool(int activeEpoch, String poolId, int page, int count) {
        return findEpochStakes(activeEpoch, page, count, EPOCH_STAKE.POOL_ID.eq(poolId));
    }

    private List<EpochStake> findEpochStakes(int activeEpoch, int page, int count, Condition poolFilter) {
        // Blockfrost omits zero-amount stake entries. Filtering after LIMIT would leave short
        // pages and shift page boundaries; the generic ledger snapshot reader retains zeros.
        return dsl.selectFrom(EPOCH_STAKE)
                .where(EPOCH_STAKE.EPOCH.eq(activeEpoch - 2))
                .and(EPOCH_STAKE.AMOUNT.gt(DSL.inline(BigInteger.ZERO)))
                .and(poolFilter)
                .orderBy(credentialOrder())
                .limit(count)
                .offset((long) Math.max(page, 0) * count)
                .fetch(row -> EpochStake.builder()
                        .epoch(row.getEpoch())
                        .activeEpoch(row.getActiveEpoch())
                        .address(row.getAddress())
                        .amount(row.getAmount())
                        .poolId(row.getPoolId())
                        .delegationEpoch(row.getDelegationEpoch())
                        .build());
    }

    private List<SortField<?>> credentialOrder() {
        // Reward addresses encode one header byte followed by the 28-byte credential.
        // The ledger's historical Credential Ord enumerates scripts before keys. Bech32
        // text order differs from byte order, so project the COMPLETE relation before paging.
        String alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        Field<String> address = DSL.lower(EPOCH_STAKE.ADDRESS);
        Field<String> payload = DSL.substring(address, DSL.position(address, DSL.inline("1")).add(DSL.inline(1)), DSL.inline(47));
        var constructor = DSL.when(DSL.substring(payload, DSL.inline(1), DSL.inline(1)).eq(DSL.inline("7")), DSL.inline(0))
                .otherwise(DSL.inline(1));

        // Word 2 contains the header's last 3 bits and the hash's first 2 bits. Remove
        // the network bits; words 3..47 contain the rest of the hash plus 3 zero padding bits.
        var firstHashBits = DSL.position(DSL.inline(alphabet), DSL.substring(payload, DSL.inline(2), DSL.inline(1)))
                .sub(DSL.inline(1)).mod(DSL.inline(4));
        Field<String> tail = DSL.substring(payload, DSL.inline(3), DSL.inline(45));
        Field<String> orderedTail;
        if (dsl.dialect().family() == SQLDialect.MYSQL || dsl.dialect().family() == SQLDialect.MARIADB) {
            // MySQL has no TRANSLATE. Two uppercase letters per word preserve numeric
            // word order and cannot be replaced again by a later lowercase/digit source.
            orderedTail = tail;
            for (int i = 0; i < alphabet.length(); i++) {
                String rank = "" + (char) ('A' + i / 26) + (char) ('A' + i % 26);
                orderedTail = DSL.replace(orderedTail, DSL.inline(alphabet.substring(i, i + 1)), DSL.inline(rank));
            }
        } else {
            orderedTail = DSL.translate(tail, DSL.inline(alphabet), DSL.inline("0123456789abcdefghijklmnopqrstuv"));
        }
        // Binary comparison avoids database collation changing the alphabet ranks. Address
        // breaks equal-credential ties without dropping rows or changing their payloads.
        return List.of(constructor.asc(), firstHashBits.asc(), orderedTail.cast(SQLDataType.VARBINARY(90)).asc(),
                EPOCH_STAKE.ADDRESS.cast(SQLDataType.VARBINARY(255)).asc());
    }

    @Override
    public Map<Integer, BigInteger> getActiveStakesByEpochs(List<Integer> activeEpochs) {
        if (activeEpochs == null || activeEpochs.isEmpty()) {
            return Collections.emptyMap();
        }

        // active_epoch = epoch + 2 (invariant; set by StakeSnapshotService).
        // epoch_stake is partitioned by `epoch`, so filter on the partition key
        // instead of active_epoch — this enables partition pruning and reuse of
        // the PK (epoch, address), avoiding a full scan across all partitions.
        List<Integer> snapshotEpochs = activeEpochs.stream()
                .map(e -> e - 2)
                .toList();

        if (epochStakeTotalsEnabled) {
            // Enabled only after the bounded background backfill is ready.
            // Each total is committed with its snapshot. Do not fall back to
            // a page-wide stake scan when a snapshot is absent or pruned.
            return dsl.select(EPOCH_STAKE_TOTAL.EPOCH, EPOCH_STAKE_TOTAL.TOTAL)
                    .from(EPOCH_STAKE_TOTAL)
                    .where(EPOCH_STAKE_TOTAL.EPOCH.in(snapshotEpochs))
                    .and(EPOCH_STAKE_TOTAL.TOTAL.isNotNull())
                    .fetch().stream().collect(Collectors.toMap(
                            r -> r.get(EPOCH_STAKE_TOTAL.EPOCH) + 2,
                            r -> r.get(EPOCH_STAKE_TOTAL.TOTAL)));
        }

        var totalStake = sum(EPOCH_STAKE.AMOUNT).cast(BigInteger.class).as("total_stake");
        return dsl.select(EPOCH_STAKE.EPOCH, totalStake)
                .from(EPOCH_STAKE)
                .where(EPOCH_STAKE.EPOCH.in(snapshotEpochs))
                .groupBy(EPOCH_STAKE.EPOCH)
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        r -> r.get(EPOCH_STAKE.EPOCH) + 2,   // remap partition epoch -> active_epoch
                        r -> r.get(totalStake)
                ));
    }

    private Epoch toEpoch(Record record) {
        Long number = record.get(EPOCH.NUMBER);
        if (number == null) {
            return null;
        }

        return Epoch.builder()
                .number(number)
                .blockCount(valueOrZero(record.get(EPOCH.BLOCK_COUNT)))
                .transactionCount(valueOrZero(record.get(EPOCH.TRANSACTION_COUNT)))
                .totalOutput(record.get(EPOCH.TOTAL_OUTPUT))
                .totalFees(toBigInteger(record.get(EPOCH.TOTAL_FEES)))
                .startTime(valueOrZero(record.get(EPOCH.START_TIME)))
                .endTime(valueOrZero(record.get(EPOCH.END_TIME)))
                .maxSlot(valueOrZero(record.get(EPOCH.MAX_SLOT)))
                .build();
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private BigInteger toBigInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return new BigInteger(value.toString());
    }
}
