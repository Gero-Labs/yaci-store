package com.bloxbean.cardano.yaci.store.common.util;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.UtxoKey;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;

/** Shared ledger-applied fee calculation; null means collateral has not been resolved. */
public final class TransactionFeeUtil {
    private TransactionFeeUtil() {
    }

    public static BigInteger resolveFee(TransactionBody body, boolean invalid,
                                        Function<List<UtxoKey>, List<AddressUtxo>> utxoLookup) {
        if (!invalid)
            return body.getFee();

        // Babbage's totalCollateral is already net of the collateral return.
        if (body.getTotalCollateral() != null && body.getTotalCollateral().signum() > 0)
            return body.getTotalCollateral();

        var inputs = body.getCollateralInputs();
        if (inputs == null || inputs.isEmpty())
            return null;

        var keys = inputs.stream().map(input -> new UtxoKey(input.getTransactionId(), input.getIndex())).toList();
        var collateral = utxoLookup.apply(keys);
        if (collateral == null || collateral.size() != keys.size())
            return null;

        Set<UtxoKey> remaining = new HashSet<>(keys);
        BigInteger totalInput = BigInteger.ZERO;
        for (var utxo : collateral) {
            if (utxo == null || !remaining.remove(new UtxoKey(utxo.getTxHash(), utxo.getOutputIndex()))
                    || utxo.getLovelaceAmount() == null || utxo.getLovelaceAmount().signum() < 0)
                return null;
            totalInput = totalInput.add(utxo.getLovelaceAmount());
        }
        if (!remaining.isEmpty())
            return null;

        BigInteger totalReturn = BigInteger.ZERO;
        var collateralReturn = body.getCollateralReturn();
        if (collateralReturn != null) {
            if (collateralReturn.getAmounts() == null)
                return null;
            var lovelace = collateralReturn.getAmounts().stream()
                    .filter(amount -> LOVELACE.equals(amount.getUnit())).toList();
            if (lovelace.size() != 1 || lovelace.getFirst().getQuantity() == null
                    || lovelace.getFirst().getQuantity().signum() < 0)
                return null;
            totalReturn = lovelace.getFirst().getQuantity();
        }

        var fee = totalInput.subtract(totalReturn);
        return fee.signum() < 0 ? null : fee;
    }
}
