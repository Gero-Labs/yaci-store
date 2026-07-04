package com.bloxbean.cardano.yaci.store.transaction.processor;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.model.VkeyWitness;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FullTxCborReassemblerTest {

    private static final String INPUT_TX_HASH = "9f8e77293350ba62c88bb1ee1633912a3e64950be96fb6b1613e421b52c6971d";
    private static final String OUTPUT_ADDRESS = "addr_test1vpfwv0ezc5g8a4mkku8hhy3y3vp92t7s3ul8g778g5yegsgalc6gc";
    // 32-byte vkey / 64-byte signature -- structurally valid hex lengths (not real key material;
    // this test exercises CBOR reassembly/round-tripping, not signature verification).
    private static final String VKEY_HEX = "0123456789abcdef".repeat(4);
    private static final String SIGNATURE_HEX = "0123456789abcdef".repeat(8);

    @Test
    void reassemble_roundTripsBodyAndVkeyWitness_throughCclTransactionDeserialize() throws Exception {
        // Build a minimal, real (not fabricated) CCL transaction body and serialize it, exactly
        // like an actual block-producer would -- this is the same body CBOR shape yaci-store
        // stores today via transaction.getBody().getCbor().
        var cclBody = com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                .inputs(List.of(new TransactionInput(INPUT_TX_HASH, 0)))
                .outputs(List.of(new TransactionOutput(OUTPUT_ADDRESS, Value.builder().coin(BigInteger.valueOf(5_000_000)).build())))
                .fee(BigInteger.valueOf(170_000))
                .build();
        DataItem bodyDataItem = cclBody.serialize();
        String bodyCborHex = HexUtil.encodeHexString(CborSerializationUtil.serialize(bodyDataItem));

        var yaciBody = com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                .cbor(bodyCborHex)
                .build();

        // one real vkey witness (structurally valid hex; not a real signature, but this test only
        // exercises the CBOR reassembly/round-trip, not signature verification)
        VkeyWitness yaciVkeyWitness = VkeyWitness.builder()
                .key(VKEY_HEX)
                .signature(SIGNATURE_HEX)
                .build();
        Witnesses witnesses = Witnesses.builder()
                .vkeyWitnesses(List.of(yaciVkeyWitness))
                .build();

        com.bloxbean.cardano.yaci.helper.model.Transaction tx = com.bloxbean.cardano.yaci.helper.model.Transaction.builder()
                .txHash("dummyTxHash")
                .body(yaciBody)
                .witnesses(witnesses)
                .invalid(false)
                .build();

        byte[] fullTxCbor = FullTxCborReassembler.reassemble(tx);

        assertThat(fullTxCbor).isNotEmpty();

        // Round-trip through CCL's own deserializer -- if this parses, the bytes are a genuinely
        // valid full transaction (CBOR array, tag 84...), not just body-only bytes with extra noise.
        Transaction roundTripped = Transaction.deserialize(fullTxCbor);

        assertThat(roundTripped.isValid()).isTrue();
        assertThat(roundTripped.getBody().getInputs()).hasSize(1);
        assertThat(roundTripped.getBody().getInputs().get(0).getTransactionId()).isEqualTo(INPUT_TX_HASH);
        assertThat(roundTripped.getBody().getOutputs()).hasSize(1);
        assertThat(roundTripped.getBody().getFee()).isEqualTo(BigInteger.valueOf(170_000));

        assertThat(roundTripped.getWitnessSet().getVkeyWitnesses()).hasSize(1);
        assertThat(HexUtil.encodeHexString(roundTripped.getWitnessSet().getVkeyWitnesses().get(0).getVkey()))
                .isEqualTo(yaciVkeyWitness.getKey());
        assertThat(HexUtil.encodeHexString(roundTripped.getWitnessSet().getVkeyWitnesses().get(0).getSignature()))
                .isEqualTo(yaciVkeyWitness.getSignature());
    }

    @Test
    void reassemble_throws_whenBodyCborMissing() {
        com.bloxbean.cardano.yaci.helper.model.Transaction tx = com.bloxbean.cardano.yaci.helper.model.Transaction.builder()
                .txHash("dummyTxHash")
                .body(com.bloxbean.cardano.yaci.core.model.TransactionBody.builder().build())
                .invalid(false)
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> FullTxCborReassembler.reassemble(tx));
    }
}
