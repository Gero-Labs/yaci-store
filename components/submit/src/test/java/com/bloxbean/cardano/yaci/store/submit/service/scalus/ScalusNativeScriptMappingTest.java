package com.bloxbean.cardano.yaci.store.submit.service.scalus;

import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.transaction.spec.script.RequireTimeAfter;
import com.bloxbean.cardano.client.transaction.spec.script.RequireTimeBefore;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAll;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAny;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAtLeast;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.submit.domain.OgmiosUtxo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

class ScalusNativeScriptMappingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void nestedOgmiosNativeClausesPreserveTheCanonicalHash() throws Exception {
        String keyHash = "12".repeat(28);
        JsonNode nativeJson = json.readTree("""
                {"clause":"all","from":[
                  {"clause":"signature","from":"%s"},
                  {"clause":"any","from":[{"clause":"before","slot":9223372036854775807}]},
                  {"clause":"some","atLeast":1.0,"from":[{"clause":"after","slot":9007199254740993}]}
                ]}
                """.formatted(keyHash));
        Script expected = new ScriptAll().addScript(new ScriptPubkey(keyHash))
                .addScript(new ScriptAny().addScript(new RequireTimeBefore(BigInteger.valueOf(Long.MAX_VALUE))))
                .addScript(new ScriptAtLeast(1).addScript(new RequireTimeAfter(new BigInteger("9007199254740993"))));
        checkHash(nativeJson, expected);
    }

    @Test
    void additionalBodyDerivesItsHashInsteadOfTrustingCallerHash() throws Exception {
        ObjectNode output = output(json.readTree("{\"language\":\"native\",\"json\":{\"clause\":\"all\",\"from\":[]}}"));
        output.put("referenceScriptHash", "00".repeat(28));
        String hash = mappedHash(output);
        check(hash.equals(new ScriptAll().getPolicyId()), "Derived native policy hash");
    }

    @Test
    void malformedNativeBodiesAreRejected() throws Exception {
        for (String body : new String[]{
                "null", "{}", "{\"clause\":\"all\",\"from\":{}}", "{\"clause\":\"unknown\"}",
                "{\"clause\":\"signature\",\"from\":\"00\"}",
                "{\"clause\":\"before\",\"slot\":-1}",
                "{\"clause\":\"after\",\"slot\":1.5}",
                "{\"clause\":\"before\",\"slot\":9223372036854775808}",
                "{\"clause\":\"after\",\"slot\":1e999999}",
                "{\"clause\":\"some\",\"atLeast\":-1,\"from\":[]}"
        }) {
            ObjectNode script = json.createObjectNode().put("language", "native");
            script.set("json", json.readTree(body));
            expectInvalid(output(script));
        }
        expectInvalid(output(json.readTree("{\"language\":\"native\",\"cbor\":\"40\"}")));
    }

    private void checkHash(JsonNode body, Script expected) throws Exception {
        ObjectNode script = json.createObjectNode().put("language", "native");
        script.set("json", body);
        check(mappedHash(output(script)).equals(HexUtil.encodeHexString(expected.getScriptHash())), "Canonical native hash");
    }

    private ObjectNode output(JsonNode script) {
        ObjectNode output = json.createObjectNode().put("address", "addr_test1...");
        output.putObject("value").put("coins", 1);
        output.set("script", script);
        return output;
    }

    private String mappedHash(ObjectNode output) {
        var pair = json.createArrayNode().add(json.createObjectNode().put("txId", "tx").put("index", 0)).add(output);
        var scripts = new ReferenceScriptSupplier();
        var mapped = ScalusAdditionalUtxoMapper.fromAdditionalUtxoSet(
                OgmiosUtxo.fromAdditionalUtxoSet(json.createArrayNode().add(pair)), scripts).iterator().next();
        check(scripts.hasNativeScripts() && scripts.getScriptRef(mapped.getReferenceScriptHash()).isDefined(),
                "Native body retained as ledger script");
        return mapped.getReferenceScriptHash();
    }

    private void expectInvalid(ObjectNode output) {
        try { mappedHash(output); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Malformed native script accepted");
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
