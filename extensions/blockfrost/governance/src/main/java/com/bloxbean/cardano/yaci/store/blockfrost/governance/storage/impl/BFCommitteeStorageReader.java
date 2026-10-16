package com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.mapper.BFProposalMapper;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.util.BFCommitteeCredential;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.common.util.GovUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortOrder;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.bloxbean.cardano.yaci.store.governance.jooq.Tables.*;
import static com.bloxbean.cardano.yaci.store.governance_aggr.jooq.Tables.COMMITTEE_STATE;
import static com.bloxbean.cardano.yaci.store.transaction.jooq.Tables.TRANSACTION_CBOR;

/** Exact Blockfrost committee shape over Yaci's enacted committee snapshots. */
@Component
@RequiredArgsConstructor
public class BFCommitteeStorageReader {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    private static final String KEY_VOTER = "CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH";
    private static final String SCRIPT_VOTER = "CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH";

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<ObjectNode> committee() {
        long deadline = deadline();
        var committee = dsl.selectFrom(COMMITTEE).orderBy(COMMITTEE.EPOCH.desc()).limit(1).queryTimeout(timeout(deadline)).fetchOne();
        if (committee == null) return Optional.empty();
        ObjectNode result = mapper.createObjectNode();
        String hash = committee.getGovActionTxHash();
        Integer index = committee.getGovActionIndex();
        boolean genesis = hash == null || "genesis.conway".equals(hash);
        result.put("proposal_tx_hash", genesis ? null : hash);
        result.put("proposal_index", genesis ? null : index);
        result.put("gov_action_id", genesis ? null : GovUtil.toGovActionIdBech32(hash, index));
        String state = dsl.select(COMMITTEE_STATE.STATE).from(COMMITTEE_STATE)
                .orderBy(COMMITTEE_STATE.EPOCH.desc()).limit(1).queryTimeout(timeout(deadline)).fetchOne(COMMITTEE_STATE.STATE);
        result.put("is_dissolved", "NO_CONFIDENCE".equals(state));
        result.putObject("quorum").put("numerator", committee.getThresholdNumerator())
                .put("denominator", committee.getThresholdDenominator());
        ArrayNode members = result.putArray("members");
        // Read this seated committee's snapshot; max(member.slot) would resurrect
        // the previous committee after an enactment that removes every member.
        var rows = dsl.selectFrom(COMMITTEE_MEMBER).where(COMMITTEE_MEMBER.SLOT.eq(committee.getSlot()))
                .orderBy(COMMITTEE_MEMBER.HASH).queryTimeout(timeout(deadline)).fetch();
        for (var row : rows) {
            var cold = new BFCommitteeCredential(row.getHash(), "SCRIPTHASH".equals(row.getCredType()), true);
            var registration = dsl.selectFrom(COMMITTEE_REGISTRATION)
                    .where(COMMITTEE_REGISTRATION.COLD_KEY.eq(cold.hash()))
                    .and(COMMITTEE_REGISTRATION.CRED_TYPE.eq(cold.credType()))
                    .orderBy(COMMITTEE_REGISTRATION.SLOT.desc(), COMMITTEE_REGISTRATION.TX_INDEX.desc(),
                            COMMITTEE_REGISTRATION.CERT_INDEX.desc()).limit(1).queryTimeout(timeout(deadline)).fetchOne();
            var resignation = dsl.selectFrom(COMMITTEE_DEREGISTRATION)
                    .where(COMMITTEE_DEREGISTRATION.COLD_KEY.eq(cold.hash()))
                    .and(COMMITTEE_DEREGISTRATION.CRED_TYPE.eq(cold.credType()))
                    .orderBy(COMMITTEE_DEREGISTRATION.SLOT.desc(), COMMITTEE_DEREGISTRATION.TX_INDEX.desc(),
                            COMMITTEE_DEREGISTRATION.CERT_INDEX.desc()).limit(1).queryTimeout(timeout(deadline)).fetchOne();
            boolean authorized = registration != null && (resignation == null
                    || after(registration.getSlot(), registration.getTxIndex(), registration.getCertIndex(),
                             resignation.getSlot(), resignation.getTxIndex(), resignation.getCertIndex()));
            ObjectNode member = members.addObject().put("cc_cold_id", cold.id())
                    .put("cc_cold_hex", cold.hash()).put("cc_cold_has_script", cold.script());
            member.put("status", registration == null ? "not_authorized" : authorized ? "authorized" : "resigned");
            member.put("expiration_epoch", row.getExpiredEpoch());
            if (authorized) {
                BFCommitteeCredential hot = hotCredential(registration.getTxHash(), registration.getSlot(),
                        registration.getCertIndex(), cold, registration.getHotKey(), deadline);
                member.put("cc_hot_id", hot.id()).put("cc_hot_hex", hot.hash()).put("cc_hot_has_script", hot.script());
            } else {
                member.putNull("cc_hot_id").putNull("cc_hot_hex").putNull("cc_hot_has_script");
            }
        }
        timeout(deadline);
        return Optional.of(result);
    }

    private long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(8); }

    // Recompute before every statement so many historical rotations cannot each
    // consume a fresh per-query budget. Timeout never produces a partial response.
    private int timeout(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IllegalStateException("Committee read deadline exceeded");
        return (int) Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L);
    }

    private boolean after(long slot, int txIndex, int certIndex, long otherSlot, int otherTxIndex, int otherCertIndex) {
        return slot > otherSlot || slot == otherSlot && (txIndex > otherTxIndex
                || txIndex == otherTxIndex && certIndex > otherCertIndex);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ArrayNode votes(BFCommitteeCredential credential, int page, int count, Order order) {
        long deadline = deadline();
        Condition scope = VOTING_PROCEDURE.VOTER_TYPE.in(KEY_VOTER, SCRIPT_VOTER);
        if (credential != null) {
            if (!credential.cold()) {
                scope = scope.and(VOTING_PROCEDURE.VOTER_HASH.eq(credential.hash()))
                        .and(VOTING_PROCEDURE.VOTER_TYPE.eq(credential.voterType()));
            } else {
                Condition authorizedHotKeys = DSL.falseCondition();
                // Cold lookups aggregate every authorized hot credential over its history.
                for (var registration : dsl.selectFrom(COMMITTEE_REGISTRATION)
                        .where(COMMITTEE_REGISTRATION.COLD_KEY.eq(credential.hash()))
                        .and(COMMITTEE_REGISTRATION.CRED_TYPE.eq(credential.credType())).queryTimeout(timeout(deadline)).fetch()) {
                    BFCommitteeCredential hot = hotCredential(registration.getTxHash(), registration.getSlot(),
                            registration.getCertIndex(), credential, registration.getHotKey(), deadline);
                    authorizedHotKeys = authorizedHotKeys.or(VOTING_PROCEDURE.VOTER_HASH.eq(hot.hash())
                            .and(VOTING_PROCEDURE.VOTER_TYPE.eq(hot.voterType())));
                }
                scope = scope.and(authorizedHotKeys);
            }
        }
        SortOrder direction = order == Order.desc ? SortOrder.DESC : SortOrder.ASC;
        // db-sync inserts votes in ledger Voter/GovActionId map order. Resolve
        // proposal fields only for the page, not every historical committee vote.
        var pageRows = dsl.selectFrom(VOTING_PROCEDURE).where(scope)
                .orderBy(VOTING_PROCEDURE.SLOT.sort(direction), VOTING_PROCEDURE.TX_INDEX.sort(direction),
                        DSL.when(VOTING_PROCEDURE.VOTER_TYPE.eq(SCRIPT_VOTER), 0).otherwise(1).sort(direction),
                        VOTING_PROCEDURE.VOTER_HASH.sort(direction), VOTING_PROCEDURE.GOV_ACTION_TX_HASH.sort(direction),
                        VOTING_PROCEDURE.GOV_ACTION_INDEX.sort(direction))
                .limit(count).offset((long) page * count).queryTimeout(timeout(deadline)).fetch();
        ArrayNode result = mapper.createArrayNode();
        if (pageRows.isEmpty()) return result;
        Map<String, String> types = new HashMap<>();
        Condition proposals = DSL.falseCondition();
        for (var row : pageRows) proposals = proposals.or(GOV_ACTION_PROPOSAL.TX_HASH.eq(row.getGovActionTxHash())
                .and(GOV_ACTION_PROPOSAL.IDX.eq(row.getGovActionIndex())));
        dsl.select(GOV_ACTION_PROPOSAL.TX_HASH, GOV_ACTION_PROPOSAL.IDX, GOV_ACTION_PROPOSAL.TYPE)
                .from(GOV_ACTION_PROPOSAL).where(proposals).queryTimeout(timeout(deadline)).fetch().forEach(row -> types.put(
                        row.get(GOV_ACTION_PROPOSAL.TX_HASH) + "#" + row.get(GOV_ACTION_PROPOSAL.IDX),
                        BFProposalMapper.INSTANCE.govActionTypeToString(row.get(GOV_ACTION_PROPOSAL.TYPE))));
        for (var row : pageRows) {
            String actionHash = row.getGovActionTxHash();
            int actionIndex = row.getGovActionIndex();
            var hot = new BFCommitteeCredential(row.getVoterHash(), SCRIPT_VOTER.equals(row.getVoterType()), false);
            result.addObject().put("tx_hash", row.getTxHash()).put("voter_hot_id", hot.id())
                    .put("proposal_id", GovUtil.toGovActionIdBech32(actionHash, actionIndex))
                    .put("proposal_tx_hash", actionHash).put("proposal_index", actionIndex)
                    .put("governance_type", types.get(actionHash + "#" + actionIndex))
                    .put("vote", row.getVote().toLowerCase(java.util.Locale.ROOT))
                    .put("metadata_url", row.getAnchorUrl()).put("metadata_hash", row.getAnchorHash())
                    .put("block_height", row.getBlock()).put("block_time", row.getBlockTime());
        }
        timeout(deadline);
        return result;
    }

    private BFCommitteeCredential hotCredential(String txHash, long slot, int certIndex,
                                                 BFCommitteeCredential cold, String hotHash, long deadline) {
        byte[] bytes = dsl.select(TRANSACTION_CBOR.CBOR_DATA).from(TRANSACTION_CBOR)
                .where(TRANSACTION_CBOR.SLOT.eq(slot)).and(TRANSACTION_CBOR.TX_HASH.eq(txHash))
                .queryTimeout(Math.min(2, timeout(deadline))).fetchOne(TRANSACTION_CBOR.CBOR_DATA);
        if (bytes != null) {
            if (bytes.length > 1_048_576) throw new IllegalStateException("Committee authorization body exceeds size limit");
            return decodeHotCredential(bytes, certIndex, cold, hotHash);
        }
        // Historical bodies can be unavailable; a voted hot credential still has
        // its exact credential type in the voting table. Never guess key vs script.
        var types = dsl.selectDistinct(VOTING_PROCEDURE.VOTER_TYPE).from(VOTING_PROCEDURE)
                .where(VOTING_PROCEDURE.VOTER_HASH.eq(hotHash))
                .and(VOTING_PROCEDURE.VOTER_TYPE.in(KEY_VOTER, SCRIPT_VOTER)).queryTimeout(timeout(deadline)).fetch(VOTING_PROCEDURE.VOTER_TYPE);
        if (types.size() == 1) return new BFCommitteeCredential(hotHash, SCRIPT_VOTER.equals(types.getFirst()), false);
        throw new IllegalStateException("Committee authorization certificate body is unavailable");
    }

    static BFCommitteeCredential decodeHotCredential(byte[] bytes, int certIndex,
                                                     BFCommitteeCredential cold, String hotHash) {
        try {
            DataItem item = CborSerializationUtil.deserializeOne(bytes);
            if (item instanceof Array envelope) item = envelope.getDataItems().getFirst();
            var body = (co.nstant.in.cbor.model.Map) item;
            var certificates = (Array) body.get(new UnsignedInteger(4));
            var certificate = (Array) certificates.getDataItems().get(certIndex);
            if (!new UnsignedInteger(14).equals(certificate.getDataItems().getFirst()))
                throw new IllegalArgumentException("Certificate is not a committee authorization");
            var coldItems = ((Array) certificate.getDataItems().get(1)).getDataItems();
            var hotItems = ((Array) certificate.getDataItems().get(2)).getDataItems();
            if (!new UnsignedInteger(0).equals(coldItems.getFirst()) && !new UnsignedInteger(1).equals(coldItems.getFirst()))
                throw new IllegalArgumentException("Invalid cold credential type");
            boolean coldScript = new UnsignedInteger(1).equals(coldItems.getFirst());
            String coldHash = HexUtil.encodeHexString(((ByteString) coldItems.get(1)).getBytes());
            String decodedHotHash = HexUtil.encodeHexString(((ByteString) hotItems.get(1)).getBytes());
            if (!cold.hash().equals(coldHash) || cold.script() != coldScript || !hotHash.equals(decodedHotHash))
                throw new IllegalArgumentException("Certificate credentials do not match the indexed authorization");
            if (!new UnsignedInteger(0).equals(hotItems.getFirst()) && !new UnsignedInteger(1).equals(hotItems.getFirst()))
                throw new IllegalArgumentException("Invalid hot credential type");
            return new BFCommitteeCredential(hotHash, new UnsignedInteger(1).equals(hotItems.getFirst()), false);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decode committee authorization certificate", e);
        }
    }
}
