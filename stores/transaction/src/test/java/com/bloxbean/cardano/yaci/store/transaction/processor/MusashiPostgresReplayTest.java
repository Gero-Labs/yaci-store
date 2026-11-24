package com.bloxbean.cardano.yaci.store.transaction.processor;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.raw.RawEbReferences;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchStateBase;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.TransactionListBuilder;
import com.bloxbean.cardano.yaci.store.client.staking.DummyStakingClient;
import com.bloxbean.cardano.yaci.store.client.staking.StakingClient;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.RollbackEvent;
import com.bloxbean.cardano.yaci.store.events.TransactionEvent;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.bloxbean.cardano.yaci.store.events.internal.PreCommitEvent;
import com.bloxbean.cardano.yaci.store.events.model.internal.BatchBlock;
import com.bloxbean.cardano.yaci.store.transaction.TransactionStoreProperties;
import com.bloxbean.cardano.yaci.store.utxo.UtxoStoreConfiguration;
import com.bloxbean.cardano.yaci.store.utxo.UtxoStoreProperties;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({MusashiPostgresReplayTest.Database.class, UtxoStoreConfiguration.class})
class MusashiPostgresReplayTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean(destroyMethod = "close")
        EmbeddedPostgres postgres() throws IOException {
            return EmbeddedPostgres.builder().setServerConfig("listen_addresses", "127.0.0.1").start();
        }

        @Bean
        DataSource dataSource(EmbeddedPostgres postgres) {
            return postgres.getPostgresDatabase();
        }

        @Bean
        UtxoStoreProperties utxoStoreProperties() {
            return new UtxoStoreProperties();
        }

        @Bean
        StakingClient stakingClient() {
            return new DummyStakingClient();
        }
    }

    @Autowired private ApplicationEventPublisher events;
    @Autowired private JdbcTemplate sql;
    @Autowired private TransactionStoreProperties settings;
    @Autowired private TransactionTemplate dbTransaction;

    @Test
    void certifiedTransactionsAndTheirSignedBytesFollowRollbackAndReplay() throws IOException {
        assertThat(sql.queryForObject("show server_version_num", Integer.class)).isEqualTo(160016);
        boolean oldFullCbor = YaciConfig.INSTANCE.isReturnFullTxCbor();
        boolean oldSaveFullCbor = settings.isSaveFullTxCbor();
        try {
            YaciConfig.INSTANCE.setReturnFullTxCbor(true);
            settings.setSaveFullTxCbor(true);
            replayCapturedBlocks();
        } finally {
            YaciConfig.INSTANCE.setReturnFullTxCbor(oldFullCbor);
            settings.setSaveFullTxCbor(oldSaveFullCbor);
        }
    }

    private void replayCapturedBlocks() throws IOException {
        var ordinary = BlockSerializer.INSTANCE.deserialize(hex("block-9091.hex"));
        assertThat(ordinary.getLeiosCertificate()).isNull();
        assertThat(ordinary.getHeader().getHeaderBody().getEbAnnouncement()).isNotNull();
        publish(ordinary);
        assertRowCounts(ordinary, 354);

        // The parent certified an older EB outside this capture. Use its header as the rollback
        // point and source of this announcement. Never feed the incomplete parent to the Store.
        var parent = BlockSerializer.INSTANCE.deserialize(hex("block-10026.hex"));
        var certifying = BlockSerializer.INSTANCE.deserialize(hex("block-10027.hex"));
        var parentHeader = parent.getHeader().getHeaderBody();
        var header = certifying.getHeader().getHeaderBody();
        var announcement = parentHeader.getEbAnnouncement();
        assertThat(header.getBlockNumber()).isEqualTo(10027L);
        assertThat(header.getPrevHash()).isEqualTo(parentHeader.getBlockHash());
        assertThat(certifying.getLeiosCertificate()).isNotNull();
        assertThat(certifying.getTransactionBodies()).isEmpty();
        var eb = (MsgLeiosBlock) LeiosFetchStateBase.deserialize(hex("msg-leios-block-f951f865.hex"));
        byte[] referenceBytes = eb.getEndorserBlock().getCbor();
        assertThat(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(referenceBytes))).isEqualTo(announcement.getHash());
        assertThat((long) referenceBytes.length).isEqualTo(announcement.getSize());

        var response = (MsgLeiosBlockTxs) LeiosFetchStateBase.deserialize(hex("msg-leios-block-txs-f951f865.hex"));
        var wire = response.getTransactions();
        var refs = RawEbReferences.of(referenceBytes).entries();
        var ids = text("dbsync-10027-tx-hashes.txt").lines().toList();
        assertThat(ids).hasSize(237).doesNotHaveDuplicates();
        assertThat(refs).hasSameSizeAs(ids);
        assertThat(wire).hasSameSizeAs(ids);
        for (int i = 0; i < wire.size(); i++) {
            assertThat(Blake2bUtil.blake2bHash256(wire.get(i))).isEqualTo(refs.get(i).txHash());
            assertThat((long) wire.get(i).length).isEqualTo(refs.get(i).size());
        }
        var ref = LeiosEndorserBlockRef.builder().ebHash(announcement.getHash())
                .ebSlot(parentHeader.getSlot()).ebSize(announcement.getSize()).build();
        var complete = BlockSerializer.withEndorserBlockTxs(certifying, ref, wire);
        assertRowCounts(certifying, 0);

        publish(complete);
        assertCertifiedRows(complete, ids, wire);
        dbTransaction.executeWithoutResult(status -> events.publishEvent(RollbackEvent.builder()
                .rollbackTo(new Point(parentHeader.getSlot(), parentHeader.getBlockHash()))
                .currentPoint(new Point(header.getSlot(), header.getBlockHash()))
                .currentBlock(header.getBlockNumber()).build()));
        assertRowCounts(certifying, 0);
        assertRowCounts(ordinary, 354);
        publish(complete);
        assertCertifiedRows(complete, ids, wire);
        assertRowCounts(ordinary, 354);
    }

    private void publish(Block block) {
        var header = block.getHeader().getHeaderBody();
        var transactions = TransactionListBuilder.build(block);
        var metadata = EventMetadata.builder().era(block.getEra()).protocolMagic(164)
                .block(header.getBlockNumber()).blockHash(header.getBlockHash()).slot(header.getSlot())
                .prevBlockHash(header.getPrevHash()).noOfTxs(transactions.size()).build();
        dbTransaction.executeWithoutResult(status -> {
            events.publishEvent(new TransactionEvent(metadata, transactions));
            events.publishEvent(new PreCommitEvent(metadata));
            events.publishEvent(new CommitEvent<>(metadata, List.of(new BatchBlock(metadata, block, transactions))));
        });
    }

    private void assertRowCounts(Block block, int expected) {
        var header = block.getHeader().getHeaderBody();
        assertThat(sql.queryForObject("select count(*) from transaction where block_hash = ?", Integer.class,
                header.getBlockHash())).isEqualTo(expected);
        assertThat(sql.queryForObject("select count(*) from transaction_cbor where slot = ?", Integer.class,
                header.getSlot())).isEqualTo(expected);
        // Every transaction in these two captured blocks has one output and spends one input.
        assertThat(sql.queryForObject("select count(*) from address_utxo where block_hash = ?", Integer.class,
                header.getBlockHash())).isEqualTo(expected);
        assertThat(sql.queryForObject("select count(*) from tx_input where spent_at_block_hash = ?", Integer.class,
                header.getBlockHash())).isEqualTo(expected);
    }

    private void assertCertifiedRows(Block block, List<String> ids, List<byte[]> wire) {
        assertRowCounts(block, ids.size());
        var header = block.getHeader().getHeaderBody();
        var stored = sql.query("""
                select t.tx_hash, t.tx_index, c.cbor_size, c.cbor_data
                from transaction t join transaction_cbor c on c.tx_hash = t.tx_hash and c.slot = t.slot
                where t.block_hash = ? and t.slot = ? order by t.tx_index
                """, (rs, row) -> {
            assertThat(rs.getInt("tx_index")).isEqualTo(row);
            assertThat(rs.getString("tx_hash")).isEqualTo(ids.get(row));
            byte[] cbor = rs.getBytes("cbor_data");
            assertThat(cbor).hasSize(rs.getInt("cbor_size"));
            var submitted = CborSpan.of(cbor);
            var original = CborSpan.of(wire.get(row));
            assertThat(submitted.size()).isEqualTo(3);
            assertThat(original.size()).isEqualTo(4);
            // Dijkstra's block form adds is_valid after the three submission fields.
            for (int field = 0; field < 3; field++) {
                assertThat(submitted.get(field).bytes()).isEqualTo(original.get(field).bytes());
            }
            return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(submitted.get(0).bytes()));
        }, header.getBlockHash(), header.getSlot());
        assertThat(stored).containsExactlyElementsOf(ids);
        // From the original 237 bodies: 187 of this block's own outputs are spent within it.
        // Compare only this block's contribution, independent of missing history before the capture.
        var unspent = sql.queryForMap("""
                select count(*) as outputs, sum(u.lovelace_amount) as lovelace
                from address_utxo u
                where u.block_hash = ? and u.owner_payment_credential = ?
                  and not exists (select 1 from tx_input i
                                  where i.tx_hash = u.tx_hash and i.output_index = u.output_index)
                """, header.getBlockHash(), "3dd305b41e901a0968ebac16a4ea62e866d117072054a2e9ce5b1010");
        assertThat(((Number) unspent.get("outputs")).longValue()).isEqualTo(50);
        assertThat(((Number) unspent.get("lovelace")).longValue()).isEqualTo(29_960_818_600_000L);
    }

    private static byte[] hex(String name) throws IOException {
        return HexUtil.decodeHexString(text(name).strip());
    }

    private static String text(String name) throws IOException {
        try (var in = Objects.requireNonNull(MusashiPostgresReplayTest.class.getResourceAsStream("/leios/w42/" + name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
