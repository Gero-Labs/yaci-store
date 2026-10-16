package com.bloxbean.cardano.yaci.store.blockfrost.governance.util;

import com.bloxbean.cardano.client.crypto.Bech32;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BFCommitteeCredentialTest {
    @Test
    void roundTripsAllRoleAndCredentialTypes() {
        for (boolean cold : new boolean[]{false,true}) for (boolean script : new boolean[]{false,true}) {
            var identity = new BFCommitteeCredential("11".repeat(28),script,cold);
            assertThat(BFCommitteeCredential.parse(identity.id())).isEqualTo(identity);
        }
    }

    @Test
    void rejectsPrefixHeaderMismatchAndNonCommitteeHeaders() {
        var valid = new BFCommitteeCredential("11".repeat(28),false,false).id();
        assertThatThrownBy(() -> BFCommitteeCredential.parse(Bech32.encode(Bech32.decode(valid).data,"cc_cold")))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] data = Bech32.decode(valid).data;
        data[0] = 0x22;
        assertThatThrownBy(() -> BFCommitteeCredential.parse(Bech32.encode(data,"cc_hot")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
