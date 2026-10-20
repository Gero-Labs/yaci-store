package com.bloxbean.cardano.yaci.store.blockfrost.util.service;

import com.bloxbean.cardano.client.plutus.spec.serializers.PlutusDataJsonConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Converts the published Blockfrost v5 tuple schema to the configured Ogmios v6 wire schema. */
final class BFAdditionalUtxoMapper {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal MAX_SLOT = new BigDecimal(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE));

    private BFAdditionalUtxoMapper() {}

    static ArrayNode toOgmios(JsonNode additional) {
        ArrayNode result = JSON.createArrayNode();
        if (additional == null || additional.isNull()) {
            return result;
        }
        if (!additional.isArray()) {
            throw new IllegalArgumentException("additionalUtxoSet must be an array");
        }
        for (JsonNode tuple : additional) {
            if (!tuple.isArray() || tuple.size() != 2 || !tuple.get(0).isObject() || !tuple.get(1).isObject()) {
                throw new IllegalArgumentException("additionalUtxoSet must contain [TxIn, TxOut] tuples");
            }
            JsonNode input = tuple.get(0);
            JsonNode output = tuple.get(1);
            if (!input.path("txId").isTextual() || !input.path("index").isIntegralNumber()
                    || !output.path("address").isTextual() || !output.path("value").isObject()) {
                throw new IllegalArgumentException("additionalUtxoSet tuple is incomplete");
            }
            ObjectNode utxo = result.addObject();
            utxo.putObject("transaction").set("id", input.get("txId"));
            utxo.set("index", input.get("index"));
            utxo.set("address", output.get("address"));
            JsonNode value = output.get("value");
            if (!value.has("coins")) {
                throw new IllegalArgumentException("additionalUtxoSet value.coins is required");
            }
            ObjectNode mappedValue = utxo.putObject("value");
            mappedValue.putObject("ada").set("lovelace", value.get("coins"));
            JsonNode assets = value.get("assets");
            if (assets != null && !assets.isNull()) {
                if (!assets.isObject()) {
                    throw new IllegalArgumentException("additionalUtxoSet assets must be an object");
                }
                assets.fields().forEachRemaining(asset -> {
                    String unit = asset.getKey();
                    int dot = unit.indexOf('.');
                    String policy = dot >= 0 ? unit.substring(0, dot) : unit.substring(0, Math.min(56, unit.length()));
                    String name = dot >= 0 ? unit.substring(dot + 1) : unit.substring(Math.min(56, unit.length()));
                    if (!policy.matches("[a-fA-F0-9]{56}") || !name.matches("(?:[a-fA-F0-9]{2})*")) {
                        throw new IllegalArgumentException("additionalUtxoSet asset id is malformed");
                    }
                    ObjectNode policyValue = mappedValue.withObject("/" + policy);
                    policyValue.set(name, asset.getValue());
                });
            }
            JsonNode hash = output.get("datum_hash");
            if (hash != null && !hash.isNull()) {
                utxo.set("datumHash", hash);
            }
            JsonNode datum = output.get("datum");
            if (datum != null && !datum.isNull()) {
                try {
                    utxo.put("datum", datum.isTextual() ? datum.asText() : PlutusDataJsonConverter.toPlutusData(datum).serializeToHex());
                } catch (Exception malformed) {
                    throw new IllegalArgumentException("additionalUtxoSet datum is malformed", malformed);
                }
            }
            JsonNode script = output.get("script");
            if (script != null && !script.isNull()) {
                if (!script.isObject()) {
                    throw new IllegalArgumentException("additionalUtxoSet script must be an object");
                }
                if (script.has("language")) {
                    utxo.set("script", script.deepCopy());
                } else {
                    ObjectNode mappedScript = utxo.putObject("script");
                    for (String language : new String[]{"plutus:v1", "plutus:v2", "plutus:v3", "native"}) {
                        if (script.has(language)) {
                            mappedScript.put("language", language);
                            mappedScript.set("native".equals(language) ? "json" : "cbor",
                                    "native".equals(language) ? nativeScript(script.get(language)) : script.get(language));
                            break;
                        }
                    }
                    if (mappedScript.isEmpty()) {
                        throw new IllegalArgumentException("additionalUtxoSet script language is malformed");
                    }
                }
            }
        }
        return result;
    }

    /** Ogmios v5 timelocks use constructor keys; v6's native JSON requires explicit clauses. */
    private static ObjectNode nativeScript(JsonNode script) {
        if (script.isTextual() && script.textValue().matches("[a-fA-F0-9]{56}")) {
            return JSON.createObjectNode().put("clause", "signature").set("from", script);
        }
        if (!script.isObject()) throw malformedNativeScript();
        // The v5 parser tries these alternatives in this order and ignores unrelated members.
        for (String constructor : new String[]{"all", "any"}) {
            try {
                return nativeChildren(script.get(constructor), JSON.createObjectNode().put("clause", constructor));
            } catch (IllegalArgumentException invalidAlternative) {
                // Another v5 constructor can still decode this object.
            }
        }
        if (script.size() == 1) {
            String constructor = script.fieldNames().next();
            if (constructor.matches("[0-9]+")) {
                BigInteger threshold = new BigInteger(constructor);
                if (threshold.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                    return nativeChildren(script.get(constructor), JSON.createObjectNode()
                            .put("clause", "some").put("atLeast", threshold.longValue()));
                }
            }
        }
        for (String constructor : new String[]{"expiresAt", "startsAt"}) {
            JsonNode slot = script.get(constructor);
            if (slot != null && slot.isNumber()) {
                BigDecimal slotNumber = slot.decimalValue();
                // Compare the compact decimal before expanding an attacker-controlled exponent.
                if (slotNumber.signum() < 0 || slotNumber.compareTo(MAX_SLOT) > 0
                        || slotNumber.stripTrailingZeros().scale() > 0) continue;
                return JSON.createObjectNode().put("clause", "expiresAt".equals(constructor) ? "before" : "after")
                        .set("slot", slot);
            }
        }
        throw malformedNativeScript();
    }

    private static ObjectNode nativeChildren(JsonNode value, ObjectNode result) {
        if (value == null || !value.isArray()) throw malformedNativeScript();
        ArrayNode children = result.putArray("from");
        for (JsonNode child : value) children.add(nativeScript(child));
        return result;
    }

    private static IllegalArgumentException malformedNativeScript() {
        return new IllegalArgumentException("additionalUtxoSet native script is malformed");
    }
}
