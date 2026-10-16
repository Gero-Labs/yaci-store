package com.bloxbean.cardano.yaci.store.script.helper;

import com.bloxbean.cardano.yaci.core.model.Datum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptUtilDatumHashTest {
    @ParameterizedTest
    @CsvSource({
            "00,03170a2e7597b7b7e3d84c05391d139a62b157e78786d8c082f29dcf4c111314",
            "1800,d6151a4af5dbab50abc915c1a41e5bee160989792ae1ca5d8dd7c6294ad04bc0",
            "9f00ff,2d4f2d2515100e24db31e726ea1222cc83060c0b36445582ac7c2e5d8be35805",
            "8100,ddee56aca8d1f17b2eaf7c8eea0c6e4d6b57734393c05e97d664950dcaa4627e"
    })
    void hashesOriginalBytesRatherThanAnEquivalentEncoding(String cbor, String ledgerHash) {
        // Literal Blake2b-256 vectors: equivalent integer/list encodings have distinct identities.
        assertThat(ScriptUtil.getDatumHash(cbor)).isEqualTo(ledgerHash);
    }

    @Test
    void retainsMainnetDatumMapOrderWhenCalculatingItsIdentity() throws IOException {
        String cbor;
        try (var input = getClass().getResourceAsStream("/datum/mainnet-frwif-inline.hex")) {
            cbor = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }

        // Mainnet FRWIF reference output at slot 123154760, output index 1.
        assertThat(ScriptUtil.getDatumHash(Datum.builder().cbor(cbor).build()))
                .isEqualTo("8de29d8a97e996b439468ab227db54961fe4585bc20b2a2d6938767e4612c0d7");
    }

    @Test
    void missingDatumHasNoHash() {
        assertThat(ScriptUtil.getDatumHash((String) null)).isNull();
        assertThat(ScriptUtil.getDatumHash((Datum) null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-hex", "d879", "f6", "0000"})
    void malformedDatumDoesNotAcquireAnIdentity(String cbor) {
        assertThat(ScriptUtil.getDatumHash(cbor)).isNull();
    }
}
