package com.bloxbean.cardano.yaci.store.submit.service.scalus;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusV1Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.serializers.PlutusDataJsonConverter;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAll;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAny;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAtLeast;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.transaction.spec.script.RequireTimeAfter;
import com.bloxbean.cardano.client.transaction.spec.script.RequireTimeBefore;
import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.submit.domain.OgmiosUtxo;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ScalusAdditionalUtxoMapper {
    private ScalusAdditionalUtxoMapper() {
    }

    static Set<Utxo> fromAdditionalUtxoSet(List<OgmiosUtxo> additionalUtxos,
                                           ReferenceScriptSupplier scriptSupplier) {
        if (additionalUtxos == null || additionalUtxos.isEmpty())
            return Collections.emptySet();

        Set<Utxo> result = new HashSet<>();
        for (OgmiosUtxo additionalUtxo : additionalUtxos) {
            Utxo utxo = fromAdditionalUtxo(additionalUtxo, scriptSupplier);
            if (utxo != null)
                result.add(utxo);
        }

        return result;
    }

    private static Utxo fromAdditionalUtxo(OgmiosUtxo additionalUtxo, ReferenceScriptSupplier scriptSupplier) {
        if (additionalUtxo == null || additionalUtxo.getTxIn() == null || additionalUtxo.getTxOut() == null)
            return null;

        var txIn = additionalUtxo.getTxIn();
        var txOut = additionalUtxo.getTxOut();
        String referenceScriptHash = referenceScriptHash(txOut, scriptSupplier);

        return Utxo.builder()
                .txHash(txIn.getTxId())
                .outputIndex(txIn.getIndex())
                .address(txOut.getAddress())
                .amount(value(txOut.getValue()))
                .dataHash(txOut.getDatumHash())
                .inlineDatum(inlineDatum(txOut.getDatum()))
                .referenceScriptHash(referenceScriptHash)
                .build();
    }

    private static String referenceScriptHash(OgmiosUtxo.TxOut txOut, ReferenceScriptSupplier scriptSupplier) {
        String referenceScriptHash = txOut.getReferenceScriptHash();
        Script script = referenceScript(txOut.getScript());
        if (script == null)
            return referenceScriptHash;

        try {
            referenceScriptHash = HexUtil.encodeHexString(script.getScriptHash());
            if (scriptSupplier != null)
                scriptSupplier.register(referenceScriptHash, script);
            return referenceScriptHash;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to derive reference script hash from additionalUtxoSet script", e);
        }
    }

    private static List<Amount> value(JsonNode valueNode) {
        List<Amount> amounts = new ArrayList<>();
        if (valueNode == null || !valueNode.isObject())
            return amounts;

        // Blockfrost SDKs use {coins, assets}; Ogmios docs use
        // {ada:{lovelace}, policy:{assetName: quantity}}. Normalize both into
        // CCL's flat Amount list.
        addIfPresent(amounts, "lovelace", bigInteger(valueNode, "coins", "lovelace"));

        JsonNode adaNode = first(valueNode, "ada");
        if (adaNode != null)
            addIfPresent(amounts, "lovelace", bigInteger(adaNode, "lovelace"));

        JsonNode assetsNode = first(valueNode, "assets");
        if (assetsNode != null && assetsNode.isObject())
            addFlatAssets(amounts, assetsNode);

        Iterator<Map.Entry<String, JsonNode>> fields = valueNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String policyOrUnit = field.getKey();
            if ("ada".equals(policyOrUnit) || "coins".equals(policyOrUnit) || "lovelace".equals(policyOrUnit) || "assets".equals(policyOrUnit))
                continue;

            JsonNode assetNode = field.getValue();
            if (assetNode.isObject()) {
                assetNode.fields().forEachRemaining(asset -> addIfPresent(amounts, policyOrUnit + asset.getKey(), bigInteger(asset.getValue())));
            } else {
                addIfPresent(amounts, policyOrUnit.replace(".", ""), bigInteger(assetNode));
            }
        }

        return amounts;
    }

    private static void addFlatAssets(List<Amount> amounts, JsonNode assetsNode) {
        assetsNode.fields()
                .forEachRemaining(asset -> addIfPresent(amounts, asset.getKey().replace(".", ""), bigInteger(asset.getValue())));
    }

    private static void addIfPresent(List<Amount> amounts, String unit, BigInteger quantity) {
        if (unit == null || quantity == null)
            return;

        if ("lovelace".equals(unit)) {
            amounts.add(Amount.lovelace(quantity));
        } else {
            amounts.add(Amount.asset(unit, quantity));
        }
    }

    private static String inlineDatum(JsonNode datumNode) {
        if (datumNode == null || datumNode.isNull())
            return null;

        if (datumNode.isTextual())
            return datumNode.asText();

        try {
            return PlutusDataJsonConverter.toPlutusData(datumNode).serializeToHex();
        } catch (Exception e) {
            throw new IllegalArgumentException("additionalUtxoSet datum must be CBOR hex or Cardano Client Lib Plutus-data JSON", e);
        }
    }

    private static Script referenceScript(JsonNode scriptNode) {
        if (scriptNode == null || scriptNode.isNull() || !scriptNode.isObject())
            return null;

        String language = text(scriptNode, "language");
        String cbor = text(scriptNode, "cbor");
        if (language == null) {
            if (scriptNode.has("plutus:v1")) {
                language = "plutus:v1";
                cbor = scriptNode.get("plutus:v1").asText();
            } else if (scriptNode.has("plutus:v2")) {
                language = "plutus:v2";
                cbor = scriptNode.get("plutus:v2").asText();
            } else if (scriptNode.has("plutus:v3")) {
                language = "plutus:v3";
                cbor = scriptNode.get("plutus:v3").asText();
            }
        }

        if ("native".equals(language)) {
            try {
                if (cbor != null)
                    return NativeScript.deserialize((Array) CborSerializationUtil.deserialize(HexUtil.decodeHexString(cbor)));
                return nativeScript(scriptNode.get("json"));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid additionalUtxoSet native reference script", e);
            }
        }

        if (language == null || cbor == null)
            return null;

        // Ogmios/Blockfrost carry reference scripts in the additional UTxO output.
        // CCL Utxo only stores the hash, so we register the decoded script
        // with the per-evaluation ScriptSupplier and put the derived hash on Utxo.
        return switch (language) {
            case "plutus:v1" -> PlutusV1Script.builder().cborHex(cbor).build();
            case "plutus:v2" -> PlutusV2Script.builder().cborHex(cbor).build();
            case "plutus:v3" -> PlutusV3Script.builder().cborHex(cbor).build();
            default -> throw new IllegalArgumentException("Unsupported additionalUtxoSet script language: " + language);
        };
    }

    private static NativeScript nativeScript(JsonNode node) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("Native reference script requires Ogmios JSON clauses");

        String clause = text(node, "clause");
        if (clause == null)
            throw new IllegalArgumentException("Native reference script requires a clause");

        return switch (clause) {
            case "signature" -> {
                String keyHash = text(node, "from");
                if (keyHash == null || !keyHash.matches("[0-9a-fA-F]{56}"))
                    throw new IllegalArgumentException("Native signature requires a 28-byte key hash");
                yield new ScriptPubkey(keyHash);
            }
            case "before" -> new RequireTimeBefore(nativeInteger(node, "slot"));
            case "after" -> new RequireTimeAfter(nativeInteger(node, "slot"));
            case "all", "any", "some" -> {
                JsonNode children = node.get("from");
                if (children == null || !children.isArray())
                    throw new IllegalArgumentException("Native reference script requires an array of children");
                if ("all".equals(clause)) {
                    ScriptAll all = new ScriptAll();
                    children.forEach(child -> all.addScript(nativeScript(child)));
                    yield all;
                } else if ("any".equals(clause)) {
                    ScriptAny any = new ScriptAny();
                    children.forEach(child -> any.addScript(nativeScript(child)));
                    yield any;
                } else {
                    ScriptAtLeast some = new ScriptAtLeast(nativeInteger(node, "atLeast"));
                    children.forEach(child -> some.addScript(nativeScript(child)));
                    yield some;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported native reference script clause: " + clause);
        };
    }

    private static BigInteger nativeInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber())
            throw new IllegalArgumentException("Native reference script requires a non-negative integer " + field);
        BigDecimal number = value.decimalValue();
        if (number.signum() < 0 || number.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                || number.stripTrailingZeros().scale() > 0)
            throw new IllegalArgumentException("Native reference script requires a non-negative 64-bit integer " + field);
        return number.toBigIntegerExact();
    }

    private static JsonNode first(JsonNode node, String... names) {
        if (node == null)
            return null;

        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull())
                return value;
        }

        return null;
    }

    private static String text(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        if (value == null)
            return null;

        return value.asText();
    }

    private static BigInteger bigInteger(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        if (value == null)
            return null;

        return bigInteger(value);
    }

    private static BigInteger bigInteger(JsonNode value) {
        if (value == null || value.isNull())
            return null;

        if (value.isIntegralNumber())
            return value.bigIntegerValue();

        return new BigInteger(value.asText());
    }
}
