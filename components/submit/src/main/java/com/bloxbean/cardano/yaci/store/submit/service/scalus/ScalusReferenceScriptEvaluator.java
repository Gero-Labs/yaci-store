package com.bloxbean.cardano.yaci.store.submit.service.scalus;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import scalus.bloxbean.EvaluatorMode;
import scalus.bloxbean.Interop;
import scalus.bloxbean.ScalusTransactionEvaluator;
import scalus.cardano.ledger.MajorProtocolVersion;
import scalus.cardano.ledger.PlutusScriptEvaluationException;
import scalus.cardano.ledger.PlutusScriptEvaluator;
import scalus.cardano.ledger.SlotConfig;
import scalus.cardano.ledger.Transaction;
import scalus.cardano.ledger.TransactionInput;
import scalus.cardano.ledger.TransactionOutput;
import scala.jdk.javaapi.CollectionConverters;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bridges native reference scripts around Scalus's Plutus-only CCL ScriptSupplier. */
final class ScalusReferenceScriptEvaluator {
    private ScalusReferenceScriptEvaluator() {
    }

    static Result<List<EvaluationResult>> evaluate(byte[] cborTx, Set<Utxo> additionalUtxos,
                                                  UtxoSupplier utxoSupplier, ReferenceScriptSupplier scripts,
                                                  SlotConfig slotConfig, ProtocolParams protocolParams) throws ApiException {
        try {
            Transaction tx = Transaction.fromCbor(cborTx, Transaction.fromCbor$default$2(cborTx));
            Map<TransactionInput, Utxo> utxos = new HashMap<>();
            for (Utxo utxo : additionalUtxos)
                utxos.put(Interop.toTransactionInput(utxo), utxo);

            // Resolve once before choosing the bridge: stored inputs can also introduce native scripts.
            Set<TransactionInput> required = new HashSet<>();
            required.addAll(CollectionConverters.asJava(tx.body().value().inputs()));
            required.addAll(CollectionConverters.asJava(tx.body().value().referenceInputs()));
            required.addAll(CollectionConverters.asJava(tx.body().value().collateralInputs()));
            for (TransactionInput input : required) {
                if (!utxos.containsKey(input)) {
                    Utxo utxo = utxoSupplier.getTxOutput(input.transactionId().toHex(), input.index())
                            .orElseThrow(() -> new IllegalStateException("UTxO not found for input " + input));
                    utxos.put(input, utxo);
                }
            }

            if (!scripts.hasNativeScripts()) {
                return new ScalusTransactionEvaluator(slotConfig, protocolParams, utxoSupplier, scripts,
                        EvaluatorMode.EVALUATE_AND_COMPUTE_COST, false)
                        .evaluateTx(cborTx, new HashSet<>(utxos.values()));
            }

            scala.collection.immutable.Map<TransactionInput, TransactionOutput> resolved =
                    scala.collection.immutable.Map$.MODULE$.empty();
            for (TransactionInput input : required) {
                Utxo utxo = utxos.get(input);
                resolved = resolved.updated(input,
                        Interop.toTransactionOutput(utxo, scripts.getScriptRef(utxo.getReferenceScriptHash())));
            }

            // Use the same evaluator, limits, cost models and mode as ScalusTransactionEvaluator.
            // Native policies belong in the resolved outputs; they never produce execution budgets.
            PlutusScriptEvaluator evaluator = PlutusScriptEvaluator.apply(slotConfig,
                    new scalus.cardano.ledger.ExUnits(Long.parseLong(protocolParams.getMaxTxExMem()),
                            Long.parseLong(protocolParams.getMaxTxExSteps())),
                    new MajorProtocolVersion(protocolParams.getProtocolMajorVer()),
                    Interop.getCostModels(protocolParams),
                    scalus.cardano.ledger.EvaluatorMode$.EvaluateAndComputeCost, false,
                    PlutusScriptEvaluator.apply$default$7());
            var redeemers = evaluator.evalPlutusScripts(tx, resolved, evaluator.evalPlutusScripts$default$3());
            List<EvaluationResult> results = new ArrayList<>();
            for (var redeemer : CollectionConverters.asJava(redeemers)) {
                results.add(new EvaluationResult(RedeemerTag.valueOf(redeemer.tag().toString()), redeemer.index(),
                        new ExUnits(BigInteger.valueOf(redeemer.exUnits().memory()),
                                BigInteger.valueOf(redeemer.exUnits().steps()))));
            }
            return Result.success("Evaluation successful").withValue(results);
        } catch (PlutusScriptEvaluationException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            if (e instanceof ApiException apiException)
                throw apiException;
            throw new ApiException("Error evaluating transaction", e);
        }
    }
}
