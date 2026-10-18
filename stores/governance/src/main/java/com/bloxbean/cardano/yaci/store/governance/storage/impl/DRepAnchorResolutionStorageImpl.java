package com.bloxbean.cardano.yaci.store.governance.storage.impl;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.store.governance.domain.DRepAnchorMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Optional;

/** Bounded native storage only. HTTP resolution occurs outside these transactions. */
@Repository
@ConditionalOnProperty(name="store.governance.anchor-resolution-enabled",havingValue="true")
public class DRepAnchorResolutionStorageImpl {
    private final DSLContext dsl;
    private final ObjectMapper json;
    private final int maxBodyBytes;
    public DRepAnchorResolutionStorageImpl(DSLContext dsl,ObjectMapper json,
            @Value("${store.governance.anchor-resolution-max-body-bytes:3000000}") int maxBodyBytes) {
        if (maxBodyBytes < 1 || maxBodyBytes > 3000000) throw new IllegalArgumentException("Bounded document size required");
        if(dsl.dialect().family()!=org.jooq.SQLDialect.POSTGRES)throw new IllegalArgumentException("DRep anchor resolution requires PostgreSQL");
        this.dsl=dsl;this.json=json;this.maxBodyBytes=maxBodyBytes;
    }
    public record Provenance(String source,String receipt,Integer ordinal,Instant observed,Instant captureStarted,Instant captureCompleted) {
        public static Provenance nativeFetch(Instant observed) { return new Provenance("NATIVE_FETCH",null,null,observed,null,null); }
        public static Provenance retained(String receipt,int ordinal,Instant captured) { return new Provenance("RETAINED_REFERENCE_IMPORT",receipt,ordinal,captured,captured,captured); }
        public static Provenance retainedInterval(String receipt,int ordinal,Instant started,Instant completed) {return new Provenance("RETAINED_REFERENCE_IMPORT",receipt,ordinal,null,started,completed);}
    }
    public record Binding(String txHash,int certIndex,String drepId,String drepHash,String credType,
                          long slot,String url,String hash) { }
    public record Lease(long generation,String token) { }
    public record Attempt(String candidate,String kind,String code,String message,Integer status,
                          String observedHash,String contentType,Long size,Instant started,Instant completed,
                          String source,String receipt) {
        public static Attempt reference(String code,String message,String receipt) {
            return new Attempt(null,"REFERENCE_OUTCOME",code,message,null,null,null,null,null,null,
                    "RETAINED_REFERENCE_IMPORT",receipt);
        }
    }
    private static final String MATCH = "tx_hash=? AND cert_index=? AND drep_id=? AND drep_hash=? AND cred_type=? AND slot=? AND anchor_url=? AND anchor_hash=?";
    private Object[] identity(Binding b) { return new Object[]{b.txHash(),b.certIndex(),b.drepId(),b.drepHash(),b.credType(),b.slot(),b.url(),b.hash()}; }
    private boolean canonical(DSLContext tx,Binding b) { return tx.fetchOne("SELECT 1 FROM drep_registration WHERE "+MATCH+" FOR SHARE",identity(b)) != null; }
    public boolean ensurePending(Binding b) {
        if (b.url()==null || b.hash()==null || !b.hash().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Exact anchored registration required");
        return dsl.transactionResult(configuration -> {
            DSLContext tx=org.jooq.impl.DSL.using(configuration);boundedWrite(tx);
            if (!canonical(tx,b)) return false;
            tx.execute("INSERT INTO drep_anchor_resolution(tx_hash,cert_index,drep_id,drep_hash,cred_type,registration_slot,anchor_url,anchor_hash) "
                    +"SELECT tx_hash,cert_index,drep_id,drep_hash,cred_type,slot,anchor_url,anchor_hash FROM drep_registration WHERE "+MATCH
                    +" ON CONFLICT(tx_hash,cert_index) DO NOTHING",identity(b));
            Record row=locked(tx,b);return row!=null;
        });
    }
    private Record locked(DSLContext tx,Binding b) {
        return tx.fetchOne("SELECT * FROM drep_anchor_resolution WHERE "+MATCH.replace("slot=?","registration_slot=?")+" FOR UPDATE",identity(b));
    }
    public Optional<Lease> claim(Binding b,String token,int seconds) {
        if (token==null || seconds<1 || seconds>300) throw new IllegalArgumentException("Bounded owned lease required");
        java.util.UUID.fromString(token);
        return dsl.transactionResult(configuration -> {
            DSLContext tx=org.jooq.impl.DSL.using(configuration);boundedWrite(tx);
            if (!canonical(tx,b)) return Optional.empty();
            Record row=locked(tx,b);
            if (row==null || "SUCCESS".equals(row.get("state"))) return Optional.empty();
            Timestamp until=row.get("lease_until",Timestamp.class);
            Instant now=databaseNow(tx);
            if (until!=null && until.toInstant().isAfter(now)) return Optional.empty();
            Timestamp due=row.get("next_attempt_at",Timestamp.class);
            if(due!=null && due.toInstant().isAfter(now))return Optional.empty();
            long generation=row.get("lease_generation",Long.class)+1;
            tx.execute("UPDATE drep_anchor_resolution SET lease_generation=?,lease_token=?,lease_until=? WHERE tx_hash=? AND cert_index=?",
                    generation,token,Timestamp.from(now.plusSeconds(seconds)),b.txHash(),b.certIndex());
            return Optional.of(new Lease(generation,token));
        });
    }
    private boolean owned(DSLContext tx,Binding b,Lease lease,Record row) {
        Timestamp until=row==null ? null : row.get("lease_until",Timestamp.class);
        return row!=null && lease!=null && !"SUCCESS".equals(row.get("state"))
                && lease.generation()==row.get("lease_generation",Long.class)
                && lease.token()!=null && lease.token().equals(row.get("lease_token")) && until!=null && until.toInstant().isAfter(databaseNow(tx));
    }
    public boolean completeSuccess(Binding b,Lease lease,byte[] raw,Provenance provenance,List<Attempt> attempts) {
        if(raw==null || raw.length>maxBodyBytes) throw new IllegalArgumentException("Bounded original raw document required");
        String hash=HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(raw));
        if(!hash.equals(b.hash())) throw new IllegalArgumentException("Raw document hash differs from native anchor");
        final String parsed;
        try { var node=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);if(node==null)throw new IllegalArgumentException("JSON document required");parsed=json.writeValueAsString(node); }
        catch(Exception invalid){throw new IllegalArgumentException("Valid original JSON document required",invalid);}
        validateProvenance(provenance);
        validateAttempts(attempts,provenance);
        Attempt last=attempts.getLast();
        if ("NATIVE_FETCH".equals(provenance.source()) && (!"SUCCESS".equals(last.kind())
                || !hash.equals(last.observedHash()) || last.size()==null || last.size()!=raw.length
                || last.status()==null || last.status()<200 || last.status()>299 || last.code()!=null || last.message()!=null))
            throw new IllegalArgumentException("Verified final native observation required");
        if("RETAINED_REFERENCE_IMPORT".equals(provenance.source()) && attempts.stream().anyMatch(a->a.code()!=null || a.message()!=null))
            throw new IllegalArgumentException("Imported success cannot carry an error outcome");
        return dsl.transactionResult(configuration -> {
            DSLContext tx=org.jooq.impl.DSL.using(configuration);boundedWrite(tx);
            if (!canonical(tx,b)) return false;
            Record row=locked(tx,b);if(!owned(tx,b,lease,row))return false;
            insertAttempts(tx,b,lease,attempts);
            tx.execute("UPDATE drep_anchor_resolution SET state='SUCCESS',selected_generation=?,raw_bytes=?,parsed_json=CAST(? AS jsonb),body_hash=?,"
                    +"source_kind=?,source_receipt_sha256=?,source_item_ordinal=?,observed_at=?,source_capture_started_at=?,source_capture_completed_at=?,completed_at=?,current_error_code=NULL,current_error_message=NULL,lease_token=NULL,lease_until=NULL WHERE tx_hash=? AND cert_index=?",
                    lease.generation(),raw,parsed,hash,provenance.source(),provenance.receipt(),provenance.ordinal(),timestamp(provenance.observed()),timestamp(provenance.captureStarted()),timestamp(provenance.captureCompleted()),timestamp(databaseNow(tx)),b.txHash(),b.certIndex());
            return true;
        });
    }
    public boolean completeFailure(Binding b,Lease lease,List<Attempt> attempts,Provenance provenance,String code,String message) {
        if(code==null || message==null)throw new IllegalArgumentException("Recorded failure required");
        return dsl.transactionResult(configuration -> {
            DSLContext tx=org.jooq.impl.DSL.using(configuration);boundedWrite(tx);
            if (!canonical(tx,b)) return false;
            Record row=locked(tx,b);if(!owned(tx,b,lease,row))return false;
            validateProvenance(provenance);validateAttempts(attempts,provenance);
            if(attempts.stream().anyMatch(a->"SUCCESS".equals(a.kind())))throw new IllegalArgumentException("Failure cannot contain a verified successful attempt");
            insertAttempts(tx,b,lease,attempts);
            tx.execute("UPDATE drep_anchor_resolution SET state='FAILED',selected_generation=?,raw_bytes=NULL,parsed_json=NULL,body_hash=NULL,source_kind=?,source_receipt_sha256=?,source_item_ordinal=?,observed_at=?,source_capture_started_at=?,source_capture_completed_at=?,completed_at=?,current_error_code=?,current_error_message=?,lease_token=NULL,lease_until=NULL WHERE tx_hash=? AND cert_index=?",
                    lease.generation(),provenance.source(),provenance.receipt(),provenance.ordinal(),timestamp(provenance.observed()),timestamp(provenance.captureStarted()),timestamp(provenance.captureCompleted()),timestamp(databaseNow(tx)),code,message,b.txHash(),b.certIndex());
            return true;
        });
    }
    private void validateProvenance(Provenance p) {
        if(p==null || !List.of("NATIVE_FETCH","RETAINED_REFERENCE_IMPORT").contains(p.source()))
            throw new IllegalArgumentException("Exact provenance required");
        if("RETAINED_REFERENCE_IMPORT".equals(p.source()) && (p.receipt()==null || !p.receipt().matches("[0-9a-f]{64}") || p.ordinal()==null || p.ordinal()<0 || p.captureStarted()==null || p.captureCompleted()==null || p.captureCompleted().isBefore(p.captureStarted())
                || p.observed()!=null && (p.observed().isBefore(p.captureStarted()) || p.observed().isAfter(p.captureCompleted()))))
            throw new IllegalArgumentException("Retained receipt, item and capture time required");
        if("NATIVE_FETCH".equals(p.source()) && (p.observed()==null || p.receipt()!=null || p.ordinal()!=null || p.captureStarted()!=null || p.captureCompleted()!=null))
            throw new IllegalArgumentException("Native provenance cannot claim imported reference");
    }
    private void validateAttempts(List<Attempt> attempts,Provenance provenance) {
        if(attempts==null || attempts.isEmpty() || attempts.size()>16)throw new IllegalArgumentException("Finite recorded attempts required");
        for(Attempt a:attempts) {
            if(a==null || !provenance.source().equals(a.source()) || !java.util.Objects.equals(provenance.receipt(),a.receipt()))
                throw new IllegalArgumentException("Attempt provenance differs");
            if("REFERENCE_OUTCOME".equals(a.kind())) {
                if(!"RETAINED_REFERENCE_IMPORT".equals(a.source()) || a.candidate()!=null || a.status()!=null || a.observedHash()!=null
                        || a.contentType()!=null || a.size()!=null || a.started()!=null || a.completed()!=null)
                    throw new IllegalArgumentException("Opaque reference must not fabricate native observations");
            } else if (!"NATIVE_FETCH".equals(a.source()) || a.candidate()==null || a.started()==null || a.completed()==null
                    || a.completed().isBefore(a.started()) || a.status()!=null && (a.status()<100 || a.status()>599)
                    || a.size()!=null && a.size()<0 || !List.of("SUCCESS","HASH_MISMATCH","HTTP_RESPONSE_ERROR","SIZE_EXCEEDED","INVALID_JSON","DESTINATION_BLOCKED","NETWORK_ERROR").contains(a.kind()))
                throw new IllegalArgumentException("Actual bounded native observation required");
        }
    }
    private void insertAttempts(DSLContext tx,Binding b,Lease lease,List<Attempt> attempts) {
        for(int i=0;i<attempts.size();i++) {
            Attempt a=attempts.get(i);
            tx.execute("INSERT INTO drep_anchor_fetch_attempt(tx_hash,cert_index,resolution_generation,attempt_ordinal,candidate_url,result_kind,error_code,exact_message,observed_http_status,expected_hash,observed_hash,observed_content_type,observed_size,started_at,completed_at,source_kind,source_receipt_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    b.txHash(),b.certIndex(),lease.generation(),i,a.candidate(),a.kind(),a.code(),a.message(),a.status(),b.hash(),a.observedHash(),a.contentType(),a.size(),timestamp(a.started()),timestamp(a.completed()),a.source(),a.receipt());
        }
    }
    private static Timestamp timestamp(Instant value){return value==null ? null : Timestamp.from(value);}
    private static void boundedWrite(DSLContext tx){tx.execute("SET LOCAL statement_timeout='5s'");tx.execute("SET LOCAL lock_timeout='250ms'");}
    private static Instant databaseNow(DSLContext tx){return tx.fetchOne("SELECT clock_timestamp()").get(0,Timestamp.class).toInstant();}
    public Optional<DRepAnchorMetadata> read(Binding b) { return Optional.ofNullable(readBatch(List.of(b),8).get(b)); }
    public Map<Binding,DRepAnchorMetadata> readBatch(List<Binding> bindings,int timeoutSeconds) {
        if(bindings.size()>100 || timeoutSeconds<1 || timeoutSeconds>8)throw new IllegalArgumentException("Bounded page and timeout required");
        if(bindings.isEmpty())return Map.of();
        Map<Binding,DRepAnchorMetadata> result=new HashMap<>();
        List<Object> parameters=new ArrayList<>();
        String keys=bindings.stream().map(b->{parameters.add(b.txHash());parameters.add(b.certIndex());return "(?,?)";}).collect(java.util.stream.Collectors.joining(","));
        var rows=dsl.resultQuery("SELECT r.*,n.slot FROM drep_anchor_resolution r JOIN drep_registration n ON n.tx_hash=r.tx_hash AND n.cert_index=r.cert_index "
                +"WHERE (r.tx_hash,r.cert_index) IN ("+keys+") AND r.drep_id=n.drep_id AND r.drep_hash=n.drep_hash AND r.cred_type=n.cred_type AND r.registration_slot=n.slot AND r.anchor_url=n.anchor_url AND r.anchor_hash=n.anchor_hash",parameters.toArray()).queryTimeout(timeoutSeconds).fetch();
        for(Record row:rows) {
            Binding binding=new Binding(row.get("tx_hash",String.class),row.get("cert_index",Integer.class),row.get("drep_id",String.class),row.get("drep_hash",String.class),row.get("cred_type",String.class),row.get("slot",Long.class),row.get("anchor_url",String.class),row.get("anchor_hash",String.class));
            if(!bindings.contains(binding) || "PENDING".equals(row.get("state")))continue;
            if("FAILED".equals(row.get("state")))result.put(binding,new DRepAnchorMetadata(null,null,new DRepAnchorMetadata.Error(row.get("current_error_code",String.class),row.get("current_error_message",String.class))));
            else try {
                byte[] raw=row.get("raw_bytes",byte[].class);
                if(raw.length>maxBodyBytes || !HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(raw)).equals(binding.hash()))throw new IllegalStateException("Stored hash differs from native anchor");
                result.put(binding,new DRepAnchorMetadata(json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw),"\\x"+HexFormat.of().formatHex(raw),null));
            } catch(Exception invalid){throw new IllegalStateException("Stored verified anchor document invalid",invalid);}
        }
        return result;
    }
}
