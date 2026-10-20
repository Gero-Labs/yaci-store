package com.bloxbean.cardano.yaci.store.submit.service.scalus;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptAll;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.client.utxo.UtxoClient;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.UtxoKey;
import com.bloxbean.cardano.yaci.store.submit.domain.OgmiosUtxo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import scalus.bloxbean.EvaluatorMode;
import scalus.bloxbean.ScalusTransactionEvaluator;
import scalus.cardano.ledger.SlotConfig;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class ScalusReferenceScriptEvaluatorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void nativeReferencePolicyEvaluatesOnlyTheRealPlutusRedeemer() throws Exception {
        Result<List<EvaluationResult>> result = evaluate(transactionBytes(), additional(), forbiddenLookup());
        assertSpendBudget(result);
    }

    @Test
    void nativeCborAndJsonProduceTheSameHashAndBudget() throws Exception {
        ArrayNode additional = additional();
        ObjectNode nativeScript = json.createObjectNode().put("language", "native").put("cbor", "820180");
        ((ObjectNode) additional.get(1)).set("script", nativeScript);
        assertSpendBudget(evaluate(transactionBytes(), additional, forbiddenLookup()));
    }

    @Test
    void missingAndChangedNativePolicyRemainFailures() throws Exception {
        ArrayNode missing = additional();
        ((ObjectNode) missing.get(1)).remove("script");
        expectFailure(() -> evaluate(transactionBytes(), missing, forbiddenLookup()), "Script not found");
        ArrayNode changed = additional();
        ((ObjectNode) changed.at("/1/script/json")).put("clause", "any");
        expectFailure(() -> evaluate(transactionBytes(), changed, forbiddenLookup()), "Script not found");
    }

    @Test
    void storedNativeReferenceIsDiscoveredAndEachInputRoleResolvesOnce() throws Exception {
        ArrayNode additional = additional();
        ReferenceScriptSupplier mappingScripts = new ReferenceScriptSupplier();
        Set<Utxo> mapped = map(additional, mappingScripts);
        Map<UtxoKey, AddressUtxo> stored = new HashMap<>();
        for (Utxo utxo : mapped) {
            AddressUtxo addressUtxo = AddressUtxo.builder().txHash(utxo.getTxHash())
                    .outputIndex(utxo.getOutputIndex()).ownerAddr(utxo.getAddress())
                    .lovelaceAmount(utxo.getAmount().get(0).getQuantity()).inlineDatum(utxo.getInlineDatum())
                    .referenceScriptHash(utxo.getReferenceScriptHash()).build();
            if (utxo.getReferenceScriptHash() != null)
                addressUtxo.setScriptRef(HexUtil.encodeHexString(new ScriptAll().scriptRefBytes()));
            stored.put(new UtxoKey(utxo.getTxHash(), utxo.getOutputIndex()), addressUtxo);
        }
        List<UtxoKey> lookups = new ArrayList<>();
        UtxoClient client = new UtxoClient() {
            public Optional<AddressUtxo> getUtxoById(UtxoKey key) {
                lookups.add(key);
                return Optional.ofNullable(stored.get(key));
            }
            public List<AddressUtxo> getUtxosByIds(List<UtxoKey> keys) { throw new AssertionError("No bulk lookup"); }
            public List<com.bloxbean.cardano.yaci.store.common.domain.Utxo> getUtxoByAddress(String address, int page, int count) {
                throw new AssertionError("No address lookup");
            }
        };
        ReferenceScriptSupplier scripts = new ReferenceScriptSupplier();
        Result<List<EvaluationResult>> result = ScalusReferenceScriptEvaluator.evaluate(transactionBytes(), Set.of(),
                new StoreUtxoSupplier(client, scripts), scripts, SlotConfig.preview(), protocolParams());
        assertSpendBudget(result);
        check(scripts.hasNativeScripts(), "Stored native body was registered");
        check(lookups.size() == 3 && Set.copyOf(lookups).equals(stored.keySet()),
                "Spend, reference and collateral resolve exactly once");
    }

    @Test
    void nativeReferenceOnSpendAndCollateralAlsoKeepsTheRealBudget() throws Exception {
        ArrayNode additional = additional();
        JsonNode script = additional.at("/1/script");
        ((ObjectNode) additional.get(0)).set("script", script.deepCopy());
        ((ObjectNode) additional.get(2)).set("script", script.deepCopy());
        assertSpendBudget(evaluate(transactionBytes(), additional, forbiddenLookup()));
    }

    @Test
    void nativeOnlyTransactionSucceedsWithNoExecutionBudgets() throws Exception {
        Transaction tx = Transaction.deserialize(transactionBytes());
        tx.getWitnessSet().setRedeemers(List.of());
        tx.getWitnessSet().setPlutusV3Scripts(List.of());
        tx.getBody().setScriptDataHash(null);
        ArrayNode additional = additional();
        ((ObjectNode) additional.get(0)).put("address", additional.get(2).path("address").asText());
        ((ObjectNode) additional.get(0)).remove("datum");
        Result<List<EvaluationResult>> result = evaluate(tx.serialize(), additional, forbiddenLookup());
        check(result.isSuccessful() && result.getValue().isEmpty(), "Native policy has no execution budgets");
    }

    @Test
    void plutusOnlyResultMatchesTheOriginalScalusBridge() throws Exception {
        Transaction tx = Transaction.deserialize(transactionBytes());
        tx.getBody().setMint(List.of());
        tx.getBody().getOutputs().get(0).getValue().setMultiAssets(List.of());
        ArrayNode additional = additional();
        ((ObjectNode) additional.get(1)).remove("script");
        ReferenceScriptSupplier scripts = new ReferenceScriptSupplier();
        Set<Utxo> utxos = map(additional, scripts);
        Result<List<EvaluationResult>> original = new ScalusTransactionEvaluator(SlotConfig.preview(), protocolParams(),
                forbiddenLookup(), scripts, EvaluatorMode.EVALUATE_AND_COMPUTE_COST, false).evaluateTx(tx.serialize(), utxos);
        Result<List<EvaluationResult>> current = ScalusReferenceScriptEvaluator.evaluate(tx.serialize(), utxos,
                forbiddenLookup(), scripts, SlotConfig.preview(), protocolParams());
        assertSpendBudget(original);
        check(current.isSuccessful() && original.getValue().equals(current.getValue()), "Original Plutus budgets unchanged");
    }

    @Test
    void mixedNativeAndPlutusReferenceBodiesRemainAvailableToScalus() throws Exception {
        Transaction tx = Transaction.deserialize(transactionBytes());
        tx.getWitnessSet().setPlutusV3Scripts(List.of());
        ArrayNode additional = additional();
        ((ObjectNode) additional.get(0)).set("script", json.createObjectNode()
                .put("language", "plutus:v3").put("cbor", "46450101002499"));
        assertSpendBudget(evaluate(tx.serialize(), additional, forbiddenLookup()));
    }

    @Test
    void anUnrelatedNativeAdditionalOutputDoesNotChangePlutusEvaluation() throws Exception {
        Transaction tx = Transaction.deserialize(transactionBytes());
        tx.getBody().setMint(List.of());
        tx.getBody().getOutputs().get(0).getValue().setMultiAssets(List.of());
        ArrayNode additional = additional();
        ObjectNode extra = additional.get(1).deepCopy();
        extra.putObject("transaction").put("id", "f4".repeat(32));
        additional.add(extra);
        ((ObjectNode) additional.get(1)).remove("script");
        assertSpendBudget(evaluate(tx.serialize(), additional, forbiddenLookup()));
    }

    private Result<List<EvaluationResult>> evaluate(byte[] bytes, ArrayNode additional, UtxoSupplier lookup) throws Exception {
        ReferenceScriptSupplier scripts = new ReferenceScriptSupplier();
        return ScalusReferenceScriptEvaluator.evaluate(bytes, map(additional, scripts), lookup, scripts,
                SlotConfig.preview(), protocolParams());
    }

    private Set<Utxo> map(ArrayNode additional, ReferenceScriptSupplier scripts) {
        return ScalusAdditionalUtxoMapper.fromAdditionalUtxoSet(OgmiosUtxo.fromAdditionalUtxoSet(additional), scripts);
    }

    private byte[] transactionBytes() throws Exception {
        return HexUtil.decodeHexString(new String(resource("transaction.cbor.hex"), StandardCharsets.UTF_8).trim());
    }

    private ArrayNode additional() throws Exception { return (ArrayNode) json.readTree(resource("additional-utxos.json")); }
    private ProtocolParams protocolParams() throws Exception { return json.readValue(resource("protocol-params.json"), ProtocolParams.class); }
    private byte[] resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/scalus-native-reference/" + name)) {
            if (stream == null) throw new AssertionError("Missing fixture " + name);
            return stream.readAllBytes();
        }
    }

    private UtxoSupplier forbiddenLookup() {
        return new UtxoSupplier() {
            public List<Utxo> getPage(String address, Integer page, Integer count, OrderEnum order) {
                throw new AssertionError("Network lookup forbidden");
            }
            public Optional<Utxo> getTxOutput(String txHash, int index) { throw new AssertionError("Network lookup forbidden"); }
        };
    }

    private void assertSpendBudget(Result<List<EvaluationResult>> result) {
        check(result.isSuccessful() && result.getValue().size() == 1, "Exactly one successful Plutus result");
        EvaluationResult spend = result.getValue().get(0);
        check(spend.getRedeemerTag() == RedeemerTag.Spend && spend.getIndex() == 0, "Only spend:0");
        check(spend.getExUnits().getMem().equals(BigInteger.valueOf(500)), "Real memory budget");
        check(spend.getExUnits().getSteps().equals(BigInteger.valueOf(64100)), "Real CPU budget");
    }

    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void expectFailure(ThrowingRunnable action, String message) throws Exception {
        try { action.run(); } catch (Exception exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause())
                if (cause.getMessage() != null && cause.getMessage().contains(message)) return;
            throw new AssertionError("Unexpected failure", exception);
        }
        throw new AssertionError("Expected failure: " + message);
    }
    private interface ThrowingRunnable { void run() throws Exception; }
}
