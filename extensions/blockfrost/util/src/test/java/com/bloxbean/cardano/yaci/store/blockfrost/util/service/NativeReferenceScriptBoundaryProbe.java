package com.bloxbean.cardano.yaci.store.blockfrost.util.service;

import com.bloxbean.cardano.yaci.store.blockfrost.util.dto.BFEvaluateUtxosRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

/** Offline runner for the actual mapper when the Gradle/Jupiter dependency cache is unavailable. */
public final class NativeReferenceScriptBoundaryProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        JsonNode fixtures = JSON.readTree(Path.of(args[0]).toFile());
        int passed = 0;
        int failed = 0;
        for (String group : new String[]{"converted", "preserved", "rejected"}) {
            for (JsonNode fixture : fixtures.path(group)) {
                String id = fixture.path("id").asText();
                try {
                    ObjectNode script = group.equals("preserved") ? (ObjectNode) fixture.get("script")
                            : JSON.createObjectNode().set("native", fixture.get("native"));
                    JsonNode original = script.deepCopy();
                    JsonNode actual;
                    try {
                        actual = map(script);
                    } catch (IllegalArgumentException rejected) {
                        if (!group.equals("rejected")) throw rejected;
                        passed++;
                        System.out.println("PASS " + group + "/" + id);
                        continue;
                    }
                    if (group.equals("rejected")) throw new AssertionError("malformed native script was accepted");
                    JsonNode expected = group.equals("converted") ? JSON.createObjectNode()
                            .put("language", "native").set("json", fixture.get("expected")) : fixture.get("expected");
                    if (!expected.equals(JSON.readTree(actual.toString()))) throw new AssertionError("expected=" + expected + " actual=" + actual);
                    if (!original.equals(script)) throw new AssertionError("input script was mutated");
                    passed++;
                    System.out.println("PASS " + group + "/" + id);
                } catch (Throwable failure) {
                    failed++;
                    System.out.println("FAIL " + group + "/" + id + " " + failure);
                }
            }
        }
        for (JsonNode fixture : fixtures.path("wire")) {
            try {
                verifyWire(fixture);
                passed++;
                System.out.println("PASS wire/" + fixture.path("id").asText());
            } catch (Throwable failure) {
                failed++;
                System.out.println("FAIL wire/" + fixture.path("id").asText() + " " + failure);
            }
        }
        // The exact tree decoder is field-scoped; unrelated default Jackson trees remain unchanged.
        if (!JSON.readTree("{\"slot\":17.0000000000000001}").path("slot").isDouble()) {
            throw new AssertionError("default Jackson numeric decoding changed");
        }
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed != 0) System.exit(1);
    }

    static void verifyWire(JsonNode fixture) throws Exception {
        String request = "{\"cbor\":\"80\",\"additionalUtxoSet\":[[{\"txId\":\"abc\",\"index\":0},"
                + "{\"address\":\"addr\",\"value\":{\"coins\":0},\"script\":{\"native\":"
                + fixture.path("native").asText() + "}}]]}";
        BFEvaluateUtxosRequest dto = JSON.readValue(request, BFEvaluateUtxosRequest.class);
        if (!"80".equals(dto.getCbor())) throw new AssertionError("cbor field changed");
        JsonNode mapped;
        try {
            mapped = BFAdditionalUtxoMapper.toOgmios(dto.getAdditionalUtxoSet());
        } catch (IllegalArgumentException rejected) {
            if (!fixture.path("rejected").asBoolean()) throw rejected;
            if (!"additionalUtxoSet native script is malformed".equals(rejected.getMessage())) throw rejected;
            return;
        }
        if (fixture.path("rejected").asBoolean()) throw new AssertionError("fractional or out-of-range slot was accepted");
        var actual = mapped.get(0).path("script").path("json").path("slot").decimalValue().toBigIntegerExact();
        if (!actual.toString().equals(fixture.path("slot").asText())) {
            throw new AssertionError("slot precision lost: expected=" + fixture.path("slot").asText() + " actual=" + actual);
        }
    }

    static JsonNode map(JsonNode script) {
        ObjectNode input = JSON.createObjectNode().put("txId", "a".repeat(64)).put("index", 7);
        ObjectNode output = JSON.createObjectNode().put("address", "addr_test1fixture");
        ObjectNode value = output.putObject("value").put("coins", 2500000);
        value.putObject("assets").put("b".repeat(56) + ".746f6b656e", 4);
        output.put("datum_hash", "c".repeat(64));
        output.put("datum", "80");
        output.set("script", script);
        var tuple = JSON.createArrayNode().add(input).add(output);
        JsonNode utxo = BFAdditionalUtxoMapper.toOgmios(JSON.createArrayNode().add(tuple)).get(0);
        if (!utxo.path("transaction").path("id").asText().equals("a".repeat(64))
                || utxo.path("index").asInt() != 7 || !utxo.path("address").asText().equals("addr_test1fixture")
                || utxo.path("value").path("ada").path("lovelace").asLong() != 2500000
                || utxo.path("value").path("b".repeat(56)).path("746f6b656e").asInt() != 4
                || !utxo.path("datumHash").asText().equals("c".repeat(64))
                || !utxo.path("datum").asText().equals("80")) {
            throw new AssertionError("non-script UTxO fields changed");
        }
        return utxo.get("script");
    }
}
