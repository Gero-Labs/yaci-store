package com.bloxbean.cardano.yaci.store.governance.service;

import com.bloxbean.cardano.yaci.store.governance.storage.impl.DRepAnchorResolutionStorageImpl.Binding;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.util.List;

/** Bounded discovery excludes verified successes and non-due or owned rows. */
public class DRepAnchorResolverWork {
    private final DSLContext dsl;

    public DRepAnchorResolverWork(DSLContext dsl) {
        if (dsl.dialect().family() != SQLDialect.POSTGRES)
            throw new IllegalArgumentException("DRep anchor resolver requires PostgreSQL");
        this.dsl = dsl;
    }

    public List<Binding> discover(int limit) {
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("Finite discovery required");
        return dsl.transactionResult(configuration -> {
            var tx = DSL.using(configuration);
            tx.execute("SET LOCAL statement_timeout='5s'");
            tx.execute("SET LOCAL lock_timeout='250ms'");
            return tx.resultQuery("""
                SELECT n.tx_hash,n.cert_index,n.drep_id,n.drep_hash,n.cred_type,n.slot,n.anchor_url,n.anchor_hash
                FROM drep_registration n
                LEFT JOIN drep_anchor_resolution r ON r.tx_hash=n.tx_hash AND r.cert_index=n.cert_index
                WHERE n.anchor_url IS NOT NULL AND n.anchor_hash ~ '^[0-9a-f]{64}$'
                AND n.cred_type IN ('ADDR_KEYHASH','SCRIPTHASH')
                AND (r.state IS NULL OR (r.state<>'SUCCESS'
                    AND (r.next_attempt_at IS NULL OR r.next_attempt_at<=clock_timestamp())
                    AND (r.lease_until IS NULL OR r.lease_until<=clock_timestamp())))
                ORDER BY n.slot DESC,n.tx_index DESC,n.cert_index DESC,n.tx_hash LIMIT ?
                """, limit).queryTimeout(5).fetch().map(row -> new Binding(
                row.get("tx_hash", String.class), row.get("cert_index", Integer.class),
                row.get("drep_id", String.class), row.get("drep_hash", String.class),
                row.get("cred_type", String.class), row.get("slot", Long.class),
                row.get("anchor_url", String.class), row.get("anchor_hash", String.class)));
        });
    }

    public void retryAfter(Binding binding, long generation, int seconds) {
        if (seconds < 60 || seconds > 86400) throw new IllegalArgumentException("Finite retry required");
        dsl.transaction(configuration -> {
            var tx = DSL.using(configuration);
            tx.execute("SET LOCAL statement_timeout='5s'");
            tx.execute("SET LOCAL lock_timeout='250ms'");
            tx.execute("""
                    UPDATE drep_anchor_resolution
                    SET next_attempt_at=clock_timestamp()+(? * INTERVAL '1 second')
                    WHERE tx_hash=? AND cert_index=? AND state='FAILED' AND selected_generation=?
                    """, seconds, binding.txHash(), binding.certIndex(), generation);
        });
    }
}
