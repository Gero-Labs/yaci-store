package com.bloxbean.cardano.yaci.store.blockfrost.governance.util;

import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.util.HexUtil;
import java.util.Arrays;

/** CIP-129 committee identity, including role and credential type. */
public record BFCommitteeCredential(String hash, boolean script, boolean cold) {
    public static BFCommitteeCredential parse(String id) {
        Bech32.Bech32Data decoded = Bech32.decode(id);
        byte[] bytes = decoded.data;
        if (bytes.length != 29) throw new IllegalArgumentException("Invalid committee credential length");
        int header = Byte.toUnsignedInt(bytes[0]);
        boolean cold = header >> 4 == 1;
        if (header >> 4 > 1 || (header & 15) != 2 && (header & 15) != 3
                || !decoded.hrp.equals(cold ? "cc_cold" : "cc_hot")) {
            throw new IllegalArgumentException("Invalid committee credential header");
        }
        return new BFCommitteeCredential(HexUtil.encodeHexString(Arrays.copyOfRange(bytes, 1, 29)),
                (header & 15) == 3, cold);
    }

    public String id() {
        byte[] bytes = HexUtil.decodeHexString(hash);
        return cold ? script ? GovId.ccColdFromScriptHash(bytes) : GovId.ccColdFromKeyHash(bytes)
                : script ? GovId.ccHotFromScriptHash(bytes) : GovId.ccHotFromKeyHash(bytes);
    }

    public String credType() { return script ? "SCRIPTHASH" : "ADDR_KEYHASH"; }
    public String voterType() { return script ? "CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH"
            : "CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH"; }
}
