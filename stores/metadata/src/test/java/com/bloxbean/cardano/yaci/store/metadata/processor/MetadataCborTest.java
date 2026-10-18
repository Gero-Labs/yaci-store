package com.bloxbean.cardano.yaci.store.metadata.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataCborTest {
    // Label 721's nested pairs are deliberately not canonical key order.
    private static final String ORDERED = "a11902d1a2616201616102";

    @ParameterizedTest
    @ValueSource(strings = {
            "a11902d1a2616201616102", // Shelley metadata map
            "82a11902d1a261620161610280", // Allegra/Mary [metadata, scripts]
            "9fa11902d1a261620161610280ff", // indefinite era wrapper
            "d90103a200a11902d1a26162016161020180", // Alonzo+ tag259
            "d90103bf018000a11902d1a2616201616102ff" // indefinite wrapper, metadata second
    })
    void preservesNestedPairOrderFromEveryAuxiliaryEra(String auxiliary) {
        assertThat(MetadataCbor.labels(auxiliary, "a11902d1a2616102616201"))
                .isEqualTo(Map.of("721", ORDERED));
    }

    @Test
    void normalizesIndefiniteContainersAndNonMinimalIntegersKeepingRepeatedKeys() {
        // 721 -> {"b": [_1, {"a":1,"a":2}], "a":-2}; all containers indefinite.
        String auxiliary = "bf1902d1bf61629f1801bf616101616102ffff61613801ffff";
        assertThat(MetadataCbor.labels(auxiliary, null)).isEqualTo(Map.of("721",
                "a11902d1a261628201a2616101616102616121"));
    }

    @Test
    void retainsRepeatedDefiniteMapKeysAndEncodesUnsignedAndNegative64BitValues() {
        String auxiliary = "a101a3001bffffffffffffffff003bffffffffffffffff00617a";
        assertThat(MetadataCbor.labels(auxiliary, null)).isEqualTo(Map.of("1", auxiliary));
    }

    @Test
    void normalizesChunkedByteAndTextStrings() {
        assertThat(MetadataCbor.labels("a101825f41014102ff7f61616162ff", null))
                .isEqualTo(Map.of("1", "a10182420102626162"));
    }

    @Test
    void emitsOneSingletonForEachLabel() {
        assertThat(MetadataCbor.labels("a2026162016161", null))
                .isEqualTo(Map.of("2", "a1026162", "1", "a1016161"));
    }

    @Test
    void supportsLegacyEventsOnlyWhenOriginalAuxiliaryBytesAreAbsent() {
        assertThat(MetadataCbor.labels(null, ORDERED)).isEqualTo(Map.of("721", ORDERED));
        assertThat(MetadataCbor.labels("", ORDERED)).isEqualTo(Map.of("721", ORDERED));
        assertThat(MetadataCbor.labels(null, null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "d90104a100a0", // unsupported auxiliary tag
            "a101f5", // special values are not ledger metadatum
            "a101d8024101", // tagged/bignum values are not ledger metadatum
            "a101bf01ff", // odd map item count
            "a1019f01", // truncated indefinite list
            "a10101ff", // trailing input
            "83a08080", // wrong era wrapper length
            "82a000", // native scripts must be an array
            "d90103a200a000a0" // repeated wrapper metadata key
    })
    void rejectsMalformedOriginalBytesWithoutCanonicalFallback(String auxiliary) {
        assertThatThrownBy(() -> MetadataCbor.labels(auxiliary, ORDERED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsDeepListsStackSafe() {
        String auxiliary = "a101" + "9f".repeat(10000) + "01" + "ff".repeat(10000);
        assertThat(MetadataCbor.labels(auxiliary, null))
                .isEqualTo(Map.of("1", "a101" + "81".repeat(10000) + "01"));
    }
}
