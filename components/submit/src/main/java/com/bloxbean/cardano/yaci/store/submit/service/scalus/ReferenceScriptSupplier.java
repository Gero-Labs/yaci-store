package com.bloxbean.cardano.yaci.store.submit.service.scalus;

import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.util.ScriptReferenceUtil;
import scalus.bloxbean.ScriptSupplier;
import scalus.bloxbean.Interop;
import scalus.cardano.ledger.ScriptRef;
import scala.Option;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class ReferenceScriptSupplier implements ScriptSupplier {
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();

    void register(AddressUtxo addressUtxo) {
        if (addressUtxo == null)
            return;

        register(addressUtxo.getReferenceScriptHash(), addressUtxo.getScriptRef());
    }

    void register(String referenceScriptHash, String scriptRef) {
        if (isBlank(referenceScriptHash) || isBlank(scriptRef))
            return;

        try {
            Script script = ScriptReferenceUtil.deserializeScriptRef(HexUtil.decodeHexString(scriptRef));
            register(referenceScriptHash, script);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to deserialize reference script for hash " + referenceScriptHash, e);
        }
    }

    void register(String referenceScriptHash, Script script) {
        if (isBlank(referenceScriptHash) || script == null)
            return;

        scripts.put(referenceScriptHash, script);
    }

    @Override
    public PlutusScript getScript(String scriptHash) {
        Script script = scripts.get(scriptHash);
        if (!(script instanceof PlutusScript plutusScript))
            throw new IllegalArgumentException("Reference script not found for hash " + scriptHash);

        return plutusScript;
    }

    boolean hasNativeScripts() {
        return scripts.values().stream().anyMatch(NativeScript.class::isInstance);
    }

    Option<ScriptRef> getScriptRef(String scriptHash) {
        if (isBlank(scriptHash))
            return Option.empty();

        Script script = scripts.get(scriptHash);
        if (script == null)
            throw new IllegalArgumentException("Reference script not found for hash " + scriptHash);

        try {
            return Option.apply(new ScriptRef(Interop.getScriptFromScriptRefBytes(script.scriptRefBytes())));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to decode reference script for hash " + scriptHash, e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
