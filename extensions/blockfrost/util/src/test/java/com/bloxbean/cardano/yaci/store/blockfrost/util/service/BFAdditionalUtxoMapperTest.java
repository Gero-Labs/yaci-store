package com.bloxbean.cardano.yaci.store.blockfrost.util.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BFAdditionalUtxoMapperTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void tuplePreservesInputsCoinsAssetsDatumAndReferenceScript() throws Exception {
        String policy = "a".repeat(56);
        var input = json.readTree("""
                [{"txId":"%s","index":7},{"address":"addr_test1fixture","value":{"coins":2500000,"assets":{"%s.746f6b656e":4,"%s":5}},"datum_hash":"%s","datum":"80","script":{"plutus:v2":"4100"}}]
                """.formatted("b".repeat(64), policy, policy, "c".repeat(64)));
        var additional = json.createArrayNode().add(input);
        var mapped = BFAdditionalUtxoMapper.toOgmios(additional).get(0);
        assertThat(mapped.path("transaction").path("id").asText()).isEqualTo("b".repeat(64));
        assertThat(mapped.path("index").asInt()).isEqualTo(7);
        assertThat(mapped.path("value").path("ada").path("lovelace").asLong()).isEqualTo(2500000);
        assertThat(mapped.path("value").path(policy).path("746f6b656e").asInt()).isEqualTo(4);
        assertThat(mapped.path("value").path(policy).path("").asInt()).isEqualTo(5);
        assertThat(mapped.path("datumHash").asText()).isEqualTo("c".repeat(64));
        assertThat(mapped.path("datum").asText()).isEqualTo("80");
        assertThat(mapped.path("script").path("language").asText()).isEqualTo("plutus:v2");
        assertThat(mapped.path("script").path("cbor").asText()).isEqualTo("4100");
    }

    @Test
    void nativeReferenceScriptUsesV6ClauseJson() throws Exception {
        var additional = json.readTree("""
                [[{"txId":"abc","index":0},{"address":"addr","value":{"coins":0},"script":{"native":{"all":[]}}}]]
                """);
        var script = BFAdditionalUtxoMapper.toOgmios(additional).get(0).get("script");
        assertThat(script.path("language").asText()).isEqualTo("native");
        assertThat(script.path("json")).isEqualTo(json.readTree("{\"clause\":\"all\",\"from\":[]}"));
    }

    @Test
    void absentSetEmptyAndBadShapeRejectedBeforeNodeCall() throws Exception {
        assertThat(BFAdditionalUtxoMapper.toOgmios(null)).isEmpty();
        assertThat(BFAdditionalUtxoMapper.toOgmios(json.readTree("[]"))).isEmpty();
        assertThatThrownBy(() -> BFAdditionalUtxoMapper.toOgmios(json.readTree("[[]]"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BFAdditionalUtxoMapper.toOgmios(json.readTree("{}"))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "native constructor {0}")
    @MethodSource("nativeCases")
    void legacyNativeScriptUsesV6Constructors(JsonNode fixture) throws Exception {
        var script = json.createObjectNode().set("native", fixture.get("native"));
        var original = script.deepCopy();
        var mapped = NativeReferenceScriptBoundaryProbe.map(script);
        assertThat(mapped.path("language").asText()).isEqualTo("native");
        assertThat(json.readTree(mapped.path("json").toString())).isEqualTo(fixture.get("expected"));
        assertThat(script).isEqualTo(original);
    }

    @ParameterizedTest(name = "preserved script {0}")
    @MethodSource("preservedCases")
    void plutusAndDirectV6ScriptsRemainUnchanged(JsonNode fixture) {
        var script = fixture.get("script");
        var original = script.deepCopy();
        assertThat(NativeReferenceScriptBoundaryProbe.map(script)).isEqualTo(fixture.get("expected"));
        assertThat(script).isEqualTo(original);
    }

    @ParameterizedTest(name = "malformed native script {0}")
    @MethodSource("malformedCases")
    void malformedLegacyNativeScriptUsesExistingValidationFailure(JsonNode fixture) {
        var script = json.createObjectNode().set("native", fixture.get("native"));
        assertThatThrownBy(() -> NativeReferenceScriptBoundaryProbe.map(script))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("additionalUtxoSet native script is malformed");
    }

    @ParameterizedTest(name = "raw DTO numeric slot {0}")
    @MethodSource("wireCases")
    void rawAdditionalUtxoRequestPreservesExactSlotNumbers(JsonNode fixture) throws Exception {
        NativeReferenceScriptBoundaryProbe.verifyWire(fixture);
    }

    @Test
    void additionalUtxoPrecisionDoesNotChangeDefaultJacksonTrees() throws Exception {
        assertThat(json.readTree("{\"slot\":17.0000000000000001}").path("slot").isDouble()).isTrue();
    }

    static Stream<JsonNode> wireCases() throws Exception { return fixtures("wire"); }

    static Stream<JsonNode> nativeCases() throws Exception { return fixtures("converted"); }
    static Stream<JsonNode> preservedCases() throws Exception { return fixtures("preserved"); }
    static Stream<JsonNode> malformedCases() throws Exception { return fixtures("rejected"); }

    private static Stream<JsonNode> fixtures(String group) throws Exception {
        try (var input = BFAdditionalUtxoMapperTest.class.getResourceAsStream("/native-reference-script-cases.json")) {
            JsonNode cases = new ObjectMapper().readTree(input).get(group);
            return java.util.stream.StreamSupport.stream(cases.spliterator(), false);
        }
    }
}
