package com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl;

import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.certs.CertificateType;
import com.bloxbean.cardano.yaci.core.model.governance.DrepType;
import com.bloxbean.cardano.yaci.core.model.governance.Vote;
import com.bloxbean.cardano.yaci.core.model.governance.VoterType;
import com.bloxbean.cardano.yaci.store.blockfrost.common.util.BlockfrostDialectUtil;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.BFGovernanceStorageReader;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl.model.BFDRepDelegator;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl.model.BFDRep;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl.model.BFProposal;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.util.BFDRepIdentity;
import com.bloxbean.cardano.yaci.store.common.domain.GovActionStatus;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.governance.domain.DRepRegistration;
import com.bloxbean.cardano.yaci.store.governance.domain.VotingProcedure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.SortField;
import org.jooq.SortOrder;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bloxbean.cardano.yaci.store.account.jooq.Tables.STAKE_ADDRESS_BALANCE;
import static com.bloxbean.cardano.yaci.store.adapot.jooq.Tables.ADAPOT_JOBS;
import static com.bloxbean.cardano.yaci.store.adapot.jooq.Tables.EPOCH_STAKE;
import static com.bloxbean.cardano.yaci.store.adapot.jooq.Tables.INSTANT_REWARD;
import static com.bloxbean.cardano.yaci.store.adapot.jooq.Tables.REWARD;
import static com.bloxbean.cardano.yaci.store.adapot.jooq.Tables.REWARD_REST;
import static com.bloxbean.cardano.yaci.store.blocks.jooq.Tables.BLOCK;
import static com.bloxbean.cardano.yaci.store.epoch.jooq.Tables.EPOCH_PARAM;
import static com.bloxbean.cardano.yaci.store.governance.jooq.Tables.*;
import static com.bloxbean.cardano.yaci.store.governance_aggr.jooq.Tables.DREP_DIST;
import static com.bloxbean.cardano.yaci.store.governance_aggr.jooq.Tables.GOV_ACTION_PROPOSAL_STATUS;
import static com.bloxbean.cardano.yaci.store.staking.jooq.Tables.STAKE_REGISTRATION;
import static com.bloxbean.cardano.yaci.store.transaction.jooq.Tables.WITHDRAWAL;

@Slf4j
@Component
@RequiredArgsConstructor
public class BFGovernanceStorageReaderImpl implements BFGovernanceStorageReader {

    private static final String ALWAYS_ABSTAIN_DREP_ID = "drep_always_abstain";
    private static final String ALWAYS_NO_CONFIDENCE_DREP_ID = "drep_always_no_confidence";

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private int offset(int page, int count) {
        return page * count;
    }

    private JsonNode jsonNodeFromJSON(JSON json) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json.data());
        } catch (Exception e) {
            log.warn("Failed to parse JSON field: {}", e.getMessage());
            return null;
        }
    }

    private int fetchCurrentEpoch() {
        try {
            Integer epoch = dsl.select(EPOCH_PARAM.EPOCH)
                    .from(EPOCH_PARAM)
                    .orderBy(EPOCH_PARAM.EPOCH.desc())
                    .limit(1)
                    .fetchOne(EPOCH_PARAM.EPOCH);
            return epoch != null ? epoch : 0;
        } catch (Exception e) {
            log.debug("Could not fetch current epoch: {}", e.getMessage());
            return 0;
        }
    }

    private int fetchDRepActivity() {
        try {
            var record = dsl.select(EPOCH_PARAM.PARAMS)
                    .from(EPOCH_PARAM)
                    .orderBy(EPOCH_PARAM.EPOCH.desc())
                    .limit(1)
                    .fetchOne();
            if (record == null) return 0;
            JSON paramsJson = record.get(EPOCH_PARAM.PARAMS);
            if (paramsJson == null) return 0;
            JsonNode params = objectMapper.readTree(paramsJson.data());
            JsonNode drepActivity = params.get("drep_activity");
            return drepActivity != null ? drepActivity.asInt(0) : 0;
        } catch (Exception e) {
            log.debug("Could not fetch drep_activity from epoch_param: {}", e.getMessage());
            return 0;
        }
    }

    private int fetchDRepCurrentEpoch(DRepDeadline deadline) {
        Integer epoch = dsl.select(EPOCH_PARAM.EPOCH).from(EPOCH_PARAM)
                .orderBy(EPOCH_PARAM.EPOCH.desc()).limit(1)
                .queryTimeout(deadline.timeout()).fetchOne(EPOCH_PARAM.EPOCH);
        return epoch == null ? 0 : epoch;
    }

    private int fetchDRepActivity(DRepDeadline deadline) {
        JSON json = dsl.select(EPOCH_PARAM.PARAMS).from(EPOCH_PARAM)
                .orderBy(EPOCH_PARAM.EPOCH.desc()).limit(1)
                .queryTimeout(deadline.timeout()).fetchOne(EPOCH_PARAM.PARAMS);
        if (json == null) return 0;
        try {
            return objectMapper.readTree(json.data()).path("drep_activity").asInt(0);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Invalid current DRep protocol parameters", e);
        }
    }

    private static final class DRepDeadline {
        private final long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        int timeout() {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("DRep read deadline exceeded");
            return (int) Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L);
        }
    }

    private int fetchGovActionLifetime() {
        try {
            var record = dsl.select(EPOCH_PARAM.PARAMS)
                    .from(EPOCH_PARAM)
                    .orderBy(EPOCH_PARAM.EPOCH.desc())
                    .limit(1)
                    .fetchOne();
            if (record == null) return 0;
            JSON paramsJson = record.get(EPOCH_PARAM.PARAMS);
            if (paramsJson == null) return 0;
            JsonNode params = objectMapper.readTree(paramsJson.data());
            JsonNode govActionLifetime = params.get("gov_action_lifetime");
            return govActionLifetime != null ? govActionLifetime.asInt(0) : 0;
        } catch (Exception e) {
            log.debug("Could not fetch gov_action_lifetime from epoch_param: {}", e.getMessage());
            return 0;
        }
    }

    private record ProposalKey(String txHash, int index) {
    }

    private record DRepAnchor(String url, String hash) {
    }

    private Map<ProposalKey, EnumMap<GovActionStatus, Integer>> fetchProposalStatusEpochs(List<ProposalKey> proposalKeys) {
        Map<ProposalKey, EnumMap<GovActionStatus, Integer>> result = new HashMap<>();
        if (proposalKeys.isEmpty()) {
            return result;
        }

        try {
            var statusEpoch = DSL.min(GOV_ACTION_PROPOSAL_STATUS.EPOCH).as("status_epoch");
            var keyRows = proposalKeys.stream()
                    .map(key -> DSL.row(key.txHash(), key.index()))
                    .toList();

            dsl.select(
                            GOV_ACTION_PROPOSAL_STATUS.GOV_ACTION_TX_HASH,
                            GOV_ACTION_PROPOSAL_STATUS.GOV_ACTION_INDEX,
                            GOV_ACTION_PROPOSAL_STATUS.STATUS,
                            statusEpoch
                    )
                    .from(GOV_ACTION_PROPOSAL_STATUS)
                    .where(DSL.row(
                                    GOV_ACTION_PROPOSAL_STATUS.GOV_ACTION_TX_HASH,
                                    GOV_ACTION_PROPOSAL_STATUS.GOV_ACTION_INDEX
                            ).in(keyRows))
                    .and(GOV_ACTION_PROPOSAL_STATUS.STATUS.in(
                            GovActionStatus.RATIFIED.name(),
                            GovActionStatus.EXPIRED.name()
                    ))
                    .groupBy(
                            GOV_ACTION_PROPOSAL_STATUS.GOV_ACTION_TX_HASH,
                            GOV_ACTION_PROPOSAL_STATUS.GOV_ACTION_INDEX,
                            GOV_ACTION_PROPOSAL_STATUS.STATUS
                    )
                    .fetch()
                    .forEach(r -> {
                        ProposalKey key = new ProposalKey(
                                r.get(GOV_ACTION_PROPOSAL_STATUS.GOV_ACTION_TX_HASH),
                                r.get(GOV_ACTION_PROPOSAL_STATUS.GOV_ACTION_INDEX)
                        );
                        GovActionStatus status = GovActionStatus.valueOf(r.get(GOV_ACTION_PROPOSAL_STATUS.STATUS));
                        result.computeIfAbsent(key, ignored -> new EnumMap<>(GovActionStatus.class))
                                .put(status, r.get(statusEpoch));
                    });
        } catch (Exception e) {
            log.warn("Could not fetch proposal status epochs: {}", e.getMessage());
        }
        return result;
    }

    /** Get current epoch from the block table. */
    private Integer getCurrentEpochFromBlock() {
        try {
            return buildCurrentEpochQuery().fetchOne(0, Integer.class);
        } catch (Exception e) {
            log.warn("Could not get current epoch: {}", e.getMessage());
            return null;
        }
    }

    org.jooq.Select<org.jooq.Record1<Integer>> buildCurrentEpochQuery() {
        return dsl.select(DSL.max(BLOCK.EPOCH)).from(BLOCK);
    }

    private List<BFProposal> enrichProposalRows(List<? extends Record> records, int govActionLifetime) {
        if (records.isEmpty()) {
            return List.of();
        }

        List<ProposalKey> proposalKeys = records.stream()
                .map(this::toProposalKey)
                .toList();
        Integer currentEpoch = getCurrentEpochFromBlock();
        Map<ProposalKey, EnumMap<GovActionStatus, Integer>> statusEpochs = fetchProposalStatusEpochs(proposalKeys);

        return records.stream()
                .map(record -> {
                    ProposalKey key = toProposalKey(record);
                    Map<GovActionStatus, Integer> proposalStatusEpochs = statusEpochs.getOrDefault(
                            key,
                            new EnumMap<>(GovActionStatus.class)
                    );
                    return toProposalRow(record, govActionLifetime, proposalStatusEpochs, currentEpoch);
                })
                .toList();
    }

    private ProposalKey toProposalKey(Record record) {
        return new ProposalKey(
                record.get(GOV_ACTION_PROPOSAL.TX_HASH),
                record.get(GOV_ACTION_PROPOSAL.IDX)
        );
    }

    private BFProposal toProposalRow(Record r,
                                     int govActionLifetime,
                                     Map<GovActionStatus, Integer> statusEpochs,
                                     Integer currentEpoch) {
        String txHash = r.get(GOV_ACTION_PROPOSAL.TX_HASH);
        int index = r.get(GOV_ACTION_PROPOSAL.IDX);
        Integer proposalEpoch = r.get(GOV_ACTION_PROPOSAL.EPOCH);
        Integer ratifiedEpoch = statusEpochs.get(GovActionStatus.RATIFIED);
        Integer enactedEpoch = ratifiedEpoch != null && currentEpoch != null && currentEpoch > ratifiedEpoch
                ? ratifiedEpoch + 1
                : null;
        return BFProposal.builder()
                .txHash(txHash)
                .index(index)
                .type(r.get(GOV_ACTION_PROPOSAL.TYPE))
                .details(jsonNodeFromJSON(r.get(GOV_ACTION_PROPOSAL.DETAILS)))
                .deposit(r.get(GOV_ACTION_PROPOSAL.DEPOSIT))
                .returnAddress(r.get(GOV_ACTION_PROPOSAL.RETURN_ADDRESS))
                .anchorUrl(r.get(GOV_ACTION_PROPOSAL.ANCHOR_URL))
                .anchorHash(r.get(GOV_ACTION_PROPOSAL.ANCHOR_HASH))
                .epoch(proposalEpoch)
                .ratifiedEpoch(ratifiedEpoch)
                .enactedEpoch(enactedEpoch)
                .expiredEpoch(statusEpochs.get(GovActionStatus.EXPIRED))
                .govActionLifetime(govActionLifetime)
                .build();
    }

    private DRepRegistration toDRepRegistrationDomain(Record r) {
        // Map the String type from DB to CertificateType enum
        String typeStr = r.get(DREP_REGISTRATION.TYPE);
        CertificateType certType = null;
        if (typeStr != null) {
            try {
                certType = CertificateType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                log.debug("Unknown DRep registration type: {}", typeStr);
            }
        }
        return DRepRegistration.builder()
                .txHash(r.get(DREP_REGISTRATION.TX_HASH))
                .certIndex(r.get(DREP_REGISTRATION.CERT_INDEX) != null
                        ? r.get(DREP_REGISTRATION.CERT_INDEX).longValue() : 0L)
                .type(certType)
                .drepHash(r.get(DREP_REGISTRATION.DREP_HASH))
                .drepId(r.get(DREP_REGISTRATION.DREP_ID))
                .anchorUrl(r.get(DREP_REGISTRATION.ANCHOR_URL))
                .anchorHash(r.get(DREP_REGISTRATION.ANCHOR_HASH))
                .deposit(r.get(DREP_REGISTRATION.DEPOSIT) != null
                        ? BigInteger.valueOf(r.get(DREP_REGISTRATION.DEPOSIT)) : null)
                .slot(r.get(DREP_REGISTRATION.SLOT))
                .epoch(r.get(DREP_REGISTRATION.EPOCH))
                .build();
    }

    private VotingProcedure toVotingProcedureDomain(Record r) {
        String voterTypeStr = r.get(VOTING_PROCEDURE.VOTER_TYPE);
        VoterType voterType = null;
        if (voterTypeStr != null) {
            try { voterType = VoterType.valueOf(voterTypeStr); }
            catch (IllegalArgumentException ignored) {}
        }
        String voteStr = r.get(VOTING_PROCEDURE.VOTE);
        Vote vote = null;
        if (voteStr != null) {
            try { vote = Vote.valueOf(voteStr); }
            catch (IllegalArgumentException ignored) {}
        }
        return VotingProcedure.builder()
                .txHash(r.get(VOTING_PROCEDURE.TX_HASH))
                .index(r.get(VOTING_PROCEDURE.IDX))
                .txIndex(r.get(VOTING_PROCEDURE.TX_INDEX))
                .slot(r.get(VOTING_PROCEDURE.SLOT))
                .voterHash(r.get(VOTING_PROCEDURE.VOTER_HASH))
                .voterType(voterType)
                .vote(vote)
                .govActionTxHash(r.get(VOTING_PROCEDURE.GOV_ACTION_TX_HASH))
                .govActionIndex(r.get(VOTING_PROCEDURE.GOV_ACTION_INDEX))
                .epoch(r.get(VOTING_PROCEDURE.EPOCH))
                .build();
    }

    // ────────────────────────────────────────────────────────────────────────
    // DRep queries
    // ────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<BFDRep> findAllDReps(int page, int count, Order order) {
        DRepDeadline deadline = new DRepDeadline();
        // db-sync assigns drep_hash.id at the first reference, including a delegation or
        // ballot before registration. Certificates are inserted in certificate-index order
        // before voting procedures in the same transaction (Universal/Insert/Tx.hs).
        var registrations = dsl.select(
                        DREP_REGISTRATION.DREP_HASH.as("identity_hash"),
                        DREP_REGISTRATION.CRED_TYPE.as("identity_type"),
                        DREP_REGISTRATION.DREP_ID.as("identity_id"),
                        DREP_REGISTRATION.SLOT.as("first_slot"),
                        DREP_REGISTRATION.TX_INDEX.as("first_tx_index"),
                        DSL.inline(0).as("event_phase"),
                        DREP_REGISTRATION.CERT_INDEX.as("event_index"))
                .from(DREP_REGISTRATION)
                .where(DREP_REGISTRATION.DREP_HASH.isNotNull())
                .and(DREP_REGISTRATION.CRED_TYPE.in("ADDR_KEYHASH", "SCRIPTHASH"));
        var delegationType = DELEGATION_VOTE.DREP_TYPE;
        var delegations = dsl.select(
                        DSL.coalesce(DELEGATION_VOTE.DREP_HASH, "").as("identity_hash"),
                        delegationType.as("identity_type"),
                        DSL.when(delegationType.eq(DrepType.ABSTAIN.name()), ALWAYS_ABSTAIN_DREP_ID)
                                .when(delegationType.eq(DrepType.NO_CONFIDENCE.name()), ALWAYS_NO_CONFIDENCE_DREP_ID)
                                .otherwise(DELEGATION_VOTE.DREP_ID).as("identity_id"),
                        DELEGATION_VOTE.SLOT.as("first_slot"),
                        DELEGATION_VOTE.TX_INDEX.as("first_tx_index"),
                        DSL.inline(0).as("event_phase"),
                        DELEGATION_VOTE.CERT_INDEX.as("event_index"))
                .from(DELEGATION_VOTE)
                .where(delegationType.in("ADDR_KEYHASH", "SCRIPTHASH", DrepType.ABSTAIN.name(), DrepType.NO_CONFIDENCE.name()));
        var votes = dsl.select(
                        VOTING_PROCEDURE.VOTER_HASH.as("identity_hash"),
                        DSL.when(VOTING_PROCEDURE.VOTER_TYPE.eq("DREP_SCRIPT_HASH"), "SCRIPTHASH")
                                .otherwise("ADDR_KEYHASH").as("identity_type"),
                        DSL.val((String) null, String.class).as("identity_id"),
                        VOTING_PROCEDURE.SLOT.as("first_slot"),
                        VOTING_PROCEDURE.TX_INDEX.as("first_tx_index"),
                        DSL.inline(1).as("event_phase"),
                        // A voter's first ballot index is always zero. Relative voter insertion
                        // order is ledger Credential Ord: script credentials, then key credentials.
                        DSL.inline(0).as("event_index"))
                .from(VOTING_PROCEDURE)
                .where(VOTING_PROCEDURE.VOTER_HASH.isNotNull())
                .and(VOTING_PROCEDURE.VOTER_TYPE.in("DREP_KEY_HASH", "DREP_SCRIPT_HASH"));
        var references = firstReferenceInSource(registrations)
                .unionAll(firstReferenceInSource(delegations))
                .unionAll(firstReferenceInSource(votes)).asTable("drep_references");
        var hash = references.field("identity_hash", String.class);
        var type = references.field("identity_type", String.class);
        var firstRanked = dsl.select(references.fields())
                .select(DSL.max(references.field("identity_id", String.class))
                        .over(DSL.partitionBy(hash, type)).as("known_id"))
                .select(DSL.rowNumber().over(DSL.partitionBy(hash, type).orderBy(
                        references.field("first_slot", Long.class), references.field("first_tx_index", Integer.class),
                        references.field("event_phase", Integer.class), references.field("event_index", Integer.class)))
                        .as("rn"))
                .from(references).asTable("first_reference_ranked");
        var first = dsl.select(firstRanked.fields()).from(firstRanked)
                .where(firstRanked.field("rn", Integer.class).eq(1)).asTable("first_reference");
        var firstId = first.field("known_id", String.class);
        SortOrder sortOrder = order == Order.desc ? SortOrder.DESC : SortOrder.ASC;
        List<BFDRep> dReps = dsl.select(first.fields()).from(first)
                .orderBy(first.field("first_slot", Long.class).sort(sortOrder),
                        first.field("first_tx_index", Integer.class).sort(sortOrder),
                        first.field("event_phase", Integer.class).sort(sortOrder),
                        first.field("event_index", Integer.class).sort(sortOrder),
                        DSL.when(first.field("identity_type", String.class).eq("SCRIPTHASH"), 0)
                                .otherwise(1).sort(sortOrder),
                        first.field("identity_hash", String.class).sort(sortOrder))
                .limit(count).offset(offset(page, count))
                .queryTimeout(deadline.timeout()).fetch(r -> {
                    String credentialHash = r.get(first.field("identity_hash", String.class));
                    String credentialType = r.get(first.field("identity_type", String.class));
                    String id = r.get(firstId);
                    boolean hasScript = "SCRIPTHASH".equals(credentialType);
                    if (id == null) {
                        id = hasScript
                                ? com.bloxbean.cardano.client.governance.GovId.drepFromScriptHash(HexUtil.decodeHexString(credentialHash))
                                : com.bloxbean.cardano.client.governance.GovId.drepFromKeyHash(HexUtil.decodeHexString(credentialHash));
                    }
                    return BFDRep.builder().drepHash(credentialHash).drepId(id)
                            .hasScript(hasScript).status("REGISTERED").build();
                });

        enrichDRepList(dReps, deadline);
        deadline.timeout();
        return dReps;
    }

    private Select<Record> firstReferenceInSource(Select<?> source) {
        var references = source.asTable("source_references");
        var hash = references.field("identity_hash");
        var type = references.field("identity_type");
        // Reduce large delegation history to one identity before the cross-source
        // windows. PostgreSQL DISTINCT ON avoids ranking every historical row.
        if (BlockfrostDialectUtil.isPostgres(dsl)) {
            return dsl.select(references.fields()).distinctOn(hash, type).from(references)
                    .orderBy(hash, type, references.field("first_slot"),
                            references.field("first_tx_index"), references.field("event_index"));
        }
        var ranked = dsl.select(references.fields()).select(DSL.rowNumber().over(
                DSL.partitionBy(hash, type).orderBy(references.field("first_slot"),
                        references.field("first_tx_index"), references.field("event_index"))).as("rn"))
                .from(references).asTable("source_ranked");
        return dsl.select(new Field<?>[]{ranked.field("identity_hash"), ranked.field("identity_type"), ranked.field("identity_id"),
                        ranked.field("first_slot"), ranked.field("first_tx_index"),
                        ranked.field("event_phase"), ranked.field("event_index")})
                .from(ranked).where(ranked.field("rn", Integer.class).eq(1));
    }

    private void enrichDRepList(List<BFDRep> dReps, DRepDeadline deadline) {
        if (dReps.isEmpty()) return;

        List<String> drepIds = dReps.stream()
                .map(BFDRep::getDrepId)
                .filter(drepId -> drepId != null && !drepId.isBlank())
                .toList();
        var ranked = dsl.select(DREP.DREP_ID,DREP.STATUS,DREP.EPOCH,DSL.rowNumber().over(
                DSL.partitionBy(DREP.DREP_ID).orderBy(DREP.SLOT.desc(),DREP.TX_INDEX.desc(),DREP.CERT_INDEX.desc())).as("rn"))
                .from(DREP).where(DREP.DREP_ID.in(drepIds)).asTable("page_state_ranked");
        Map<String, Record> states = new HashMap<>();
        dsl.select(ranked.fields()).from(ranked).where(ranked.field("rn",Integer.class).eq(1))
                .queryTimeout(deadline.timeout()).fetch().forEach(record -> states.put(record.get(ranked.field(DREP.DREP_ID)), record));
        int currentEpoch = fetchDRepCurrentEpoch(deadline);
        Map<String, Long> amounts = drepIds.isEmpty() ? Map.of() : fetchLatestDRepAmounts(drepIds, currentEpoch, deadline);
        Map<String, Boolean> registered = fetchDRepRegistered(drepIds, deadline);
        Map<String, Boolean> scriptFlags = drepIds.isEmpty() ? Map.of() : fetchLatestDRepScriptFlags(drepIds, deadline);
        Map<String, DRepAnchor> anchors = drepIds.isEmpty() ? Map.of() : fetchLatestDRepAnchors(drepIds, deadline);
        Map<String, Integer> lastVoteEpochs = drepIds.isEmpty() ? Map.of() : fetchLastDRepVoteEpochs(drepIds, deadline);
        Map<String, Long> specialAmounts = fetchLatestSpecialDRepAmounts(currentEpoch, deadline);
        int drepActivity = fetchDRepActivity(deadline);

        dReps.forEach(dRep -> {
            String drepId = dRep.getDrepId();
            Record state = states.get(drepId);
            if (state != null) {
                dRep.setStatus(state.get(ranked.field(DREP.STATUS)));
                dRep.setEpoch(state.get(ranked.field(DREP.EPOCH)));
            }
            dRep.setAmount(isSpecialDRep(dRep)
                    ? specialAmounts.getOrDefault(drepId, 0L)
                    : amounts.getOrDefault(drepId, 0L));
            dRep.setHasScript(scriptFlags.getOrDefault(drepId, Boolean.TRUE.equals(dRep.getHasScript())));

            Integer lastVoteEpoch = lastVoteEpochs.get(drepId);
            if (lastVoteEpoch != null && (dRep.getEpoch() == null || lastVoteEpoch > dRep.getEpoch())) {
                dRep.setEpoch(lastVoteEpoch);
            }

            boolean retired = !registered.getOrDefault(drepId, true);
            dRep.setStatus(retired ? "RETIRED" : "REGISTERED");
            dRep.setExpired(!retired
                    && dRep.getEpoch() != null
                    && drepActivity > 0
                    && currentEpoch > 0
                    && currentEpoch - dRep.getEpoch() > drepActivity);

            DRepAnchor anchor = anchors.get(drepId);
            if (anchor != null) {
                dRep.setAnchorUrl(anchor.url());
                dRep.setAnchorHash(anchor.hash());
            }
        });
    }

    private Map<String, Boolean> fetchDRepRegistered(List<String> ids, DRepDeadline deadline) {
        var ranked = dsl.select(DREP_REGISTRATION.DREP_ID,DREP_REGISTRATION.TYPE,
                        DREP_REGISTRATION.SLOT,DREP_REGISTRATION.TX_INDEX,
                        DSL.rowNumber().over(DSL.partitionBy(DREP_REGISTRATION.DREP_ID,DREP_REGISTRATION.TYPE)
                                .orderBy(DREP_REGISTRATION.SLOT.desc(),DREP_REGISTRATION.TX_INDEX.desc())).as("rn"))
                .from(DREP_REGISTRATION).where(DREP_REGISTRATION.DREP_ID.in(ids))
                .and(DREP_REGISTRATION.TYPE.in("REG_DREP_CERT","UNREG_DREP_CERT")).asTable("latest_sign_tx");
        Map<String, DRepTx> positive = new HashMap<>(), negative = new HashMap<>();
        dsl.select(ranked.fields()).from(ranked).where(ranked.field("rn",Integer.class).eq(1))
                .queryTimeout(deadline.timeout()).fetch().forEach(row -> {
                    String id=row.get(ranked.field(DREP_REGISTRATION.DREP_ID));
                    DRepTx tx=new DRepTx(row.get(ranked.field(DREP_REGISTRATION.SLOT)),row.get(ranked.field(DREP_REGISTRATION.TX_INDEX)));
                    ("REG_DREP_CERT".equals(row.get(ranked.field(DREP_REGISTRATION.TYPE))) ? positive : negative).put(id,tx);
                });
        Map<String, Boolean> result=new HashMap<>();
        ids.forEach(id -> result.put(id,registered(positive.get(id),negative.get(id))));
        return result;
    }

    private record DRepTx(Long slot, Integer index) implements Comparable<DRepTx> {
        @Override public int compareTo(DRepTx other) {
            int compared=Long.compare(slot == null ? 0 : slot,other.slot == null ? 0 : other.slot);
            return compared != 0 ? compared : Integer.compare(index == null ? 0 : index,other.index == null ? 0 : other.index);
        }
    }

    private boolean registered(DRepTx positive, DRepTx negative) {
        return negative == null || positive != null && positive.compareTo(negative) > 0;
    }

    private boolean isSpecialDRep(BFDRep dRep) {
        return ALWAYS_ABSTAIN_DREP_ID.equals(dRep.getDrepId())
                || ALWAYS_NO_CONFIDENCE_DREP_ID.equals(dRep.getDrepId());
    }

    private Map<String, Long> fetchLatestSpecialDRepAmounts(int currentEpoch, DRepDeadline deadline) {
        Map<String, Long> result = new HashMap<>();
        dsl.select(DREP_DIST.DREP_TYPE, DREP_DIST.AMOUNT)
                .from(DREP_DIST)
                .where(DREP_DIST.EPOCH.eq(currentEpoch))
                .and(DREP_DIST.DREP_TYPE.in(DrepType.ABSTAIN.name(), DrepType.NO_CONFIDENCE.name()))
                .queryTimeout(deadline.timeout()).fetch()
                .forEach(record -> {
                    String drepId = DrepType.ABSTAIN.name().equals(record.get(DREP_DIST.DREP_TYPE))
                            ? ALWAYS_ABSTAIN_DREP_ID
                            : ALWAYS_NO_CONFIDENCE_DREP_ID;
                    result.put(drepId, record.get(DREP_DIST.AMOUNT));
                });
        return result;
    }

    private Map<String, Long> fetchLatestDRepAmounts(List<String> drepIds, int currentEpoch, DRepDeadline deadline) {
        Map<String, Long> amounts = new HashMap<>();
        if (currentEpoch > 0) {
            dsl.select(DREP_DIST.DREP_ID, DREP_DIST.AMOUNT)
                    .from(DREP_DIST)
                    .where(DREP_DIST.DREP_ID.in(drepIds))
                    .and(DREP_DIST.EPOCH.eq(currentEpoch))
                    .queryTimeout(deadline.timeout()).fetch()
                    .forEach(record -> amounts.put(record.get(DREP_DIST.DREP_ID), record.get(DREP_DIST.AMOUNT)));
        }

        List<String> missing = drepIds.stream()
                .filter(id -> id != null && !BFDRepIdentity.isSpecialId(id) && !amounts.containsKey(id))
                .toList();
        if (missing.isEmpty()) {
            return amounts;
        }

        // Resolve the credential from the ID itself. Delegation-only and vote-only
        // identities have no registration row, but still appear in the snapshot.
        Map<DRepCredential, String> identities = credentialIds(missing);
        dsl.select(LOCAL_DREP_DIST.DREP_HASH, LOCAL_DREP_DIST.DREP_TYPE, LOCAL_DREP_DIST.AMOUNT)
                .from(LOCAL_DREP_DIST)
                .where(LOCAL_DREP_DIST.DREP_HASH.in(identities.keySet().stream().map(DRepCredential::hash).toList()))
                .and(LOCAL_DREP_DIST.EPOCH.eq(currentEpoch))
                .queryTimeout(deadline.timeout()).fetch()
                .forEach(record -> {
                    String id = identities.get(new DRepCredential(record.get(LOCAL_DREP_DIST.DREP_HASH),
                            record.get(LOCAL_DREP_DIST.DREP_TYPE)));
                    if (id != null) amounts.put(id, record.get(LOCAL_DREP_DIST.AMOUNT));
                });
        return amounts;
    }

    private Map<String, Boolean> fetchLatestDRepScriptFlags(List<String> drepIds, DRepDeadline deadline) {
        var ranked = dsl.select(
                        DREP_REGISTRATION.DREP_ID,
                        DREP_REGISTRATION.CRED_TYPE,
                        DSL.rowNumber().over(DSL.partitionBy(DREP_REGISTRATION.DREP_ID)
                                .orderBy(DREP_REGISTRATION.SLOT.desc(),
                                        DREP_REGISTRATION.TX_INDEX.desc(),
                                        DREP_REGISTRATION.CERT_INDEX.desc()))
                                .as("rn")
                )
                .from(DREP_REGISTRATION)
                .where(DREP_REGISTRATION.DREP_ID.in(drepIds))
                .asTable("latest_registration_ranked");

        Map<String, Boolean> result = new HashMap<>();
        dsl.select(ranked.field(DREP_REGISTRATION.DREP_ID), ranked.field(DREP_REGISTRATION.CRED_TYPE))
                .from(ranked)
                .where(ranked.field("rn", Integer.class).eq(1))
                .queryTimeout(deadline.timeout()).fetch()
                .forEach(record -> result.put(
                        record.get(ranked.field(DREP_REGISTRATION.DREP_ID)),
                        "SCRIPTHASH".equalsIgnoreCase(record.get(ranked.field(DREP_REGISTRATION.CRED_TYPE)))
                ));
        return result;
    }

    private Map<String, DRepAnchor> fetchLatestDRepAnchors(List<String> drepIds, DRepDeadline deadline) {
        var ranked = dsl.select(
                        DREP_REGISTRATION.DREP_ID,
                        DREP_REGISTRATION.ANCHOR_URL,
                        DREP_REGISTRATION.ANCHOR_HASH,
                        DSL.rowNumber().over(DSL.partitionBy(DREP_REGISTRATION.DREP_ID)
                                .orderBy(DREP_REGISTRATION.SLOT.desc(),
                                        DREP_REGISTRATION.TX_INDEX.desc(),
                                        DREP_REGISTRATION.CERT_INDEX.desc()))
                                .as("rn")
                )
                .from(DREP_REGISTRATION)
                .where(DREP_REGISTRATION.DREP_ID.in(drepIds))
                .and(DREP_REGISTRATION.ANCHOR_URL.isNotNull())
                .asTable("latest_anchor_ranked");

        Map<String, DRepAnchor> result = new HashMap<>();
        dsl.select(
                        ranked.field(DREP_REGISTRATION.DREP_ID),
                        ranked.field(DREP_REGISTRATION.ANCHOR_URL),
                        ranked.field(DREP_REGISTRATION.ANCHOR_HASH)
                )
                .from(ranked)
                .where(ranked.field("rn", Integer.class).eq(1))
                .queryTimeout(deadline.timeout()).fetch()
                .forEach(record -> result.put(
                        record.get(ranked.field(DREP_REGISTRATION.DREP_ID)),
                        new DRepAnchor(
                                record.get(ranked.field(DREP_REGISTRATION.ANCHOR_URL)),
                                record.get(ranked.field(DREP_REGISTRATION.ANCHOR_HASH))
                        )
                ));
        return result;
    }

    private record DRepCredential(String hash, String type) {}

    private Map<DRepCredential, String> credentialIds(List<String> drepIds) {
        Map<DRepCredential, String> identities = new HashMap<>();
        drepIds.stream().filter(id -> id != null && !BFDRepIdentity.isSpecialId(id)).forEach(id -> {
            BFDRepIdentity identity = BFDRepIdentity.parse(id);
            identities.put(new DRepCredential(identity.hash(), identity.credType()), id);
        });
        return identities;
    }

    private String drepId(String hash, String type) {
        return "SCRIPTHASH".equals(type)
                ? GovId.drepFromScriptHash(HexUtil.decodeHexString(hash))
                : GovId.drepFromKeyHash(HexUtil.decodeHexString(hash));
    }

    private Map<String, Integer> fetchLastDRepVoteEpochs(List<String> drepIds, DRepDeadline deadline) {
        Map<DRepCredential, String> identities = credentialIds(drepIds);
        if (identities.isEmpty()) return Map.of();
        Map<String, Integer> result = new HashMap<>();
        var lastVoteEpoch = DSL.max(VOTING_PROCEDURE.EPOCH).as("last_vote_epoch");
        dsl.select(VOTING_PROCEDURE.VOTER_HASH, VOTING_PROCEDURE.VOTER_TYPE, lastVoteEpoch)
                .from(VOTING_PROCEDURE)
                .where(VOTING_PROCEDURE.VOTER_HASH.in(identities.keySet().stream().map(DRepCredential::hash).toList()))
                .and(VOTING_PROCEDURE.VOTER_TYPE.in("DREP_KEY_HASH", "DREP_SCRIPT_HASH"))
                .groupBy(VOTING_PROCEDURE.VOTER_HASH, VOTING_PROCEDURE.VOTER_TYPE)
                .queryTimeout(deadline.timeout()).fetch().forEach(record -> {
                    String type = "DREP_SCRIPT_HASH".equals(record.get(VOTING_PROCEDURE.VOTER_TYPE))
                            ? "SCRIPTHASH" : "ADDR_KEYHASH";
                    String id = identities.get(new DRepCredential(record.get(VOTING_PROCEDURE.VOTER_HASH), type));
                    if (id != null) result.put(id, record.get(lastVoteEpoch));
                });
        return result;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<String> findDRepIdsByHash(String drepHash) {
        if (drepHash == null || drepHash.isBlank()) return List.of();
        DRepDeadline deadline = new DRepDeadline();
        Map<String, String> idsByType = new HashMap<>();
        dsl.selectDistinct(DREP_REGISTRATION.DREP_ID, DREP_REGISTRATION.CRED_TYPE)
                .from(DREP_REGISTRATION).where(DREP_REGISTRATION.DREP_HASH.eq(drepHash))
                .and(DREP_REGISTRATION.CRED_TYPE.in("ADDR_KEYHASH", "SCRIPTHASH"))
                .union(dsl.selectDistinct(DELEGATION_VOTE.DREP_ID, DELEGATION_VOTE.DREP_TYPE)
                        .from(DELEGATION_VOTE).where(DELEGATION_VOTE.DREP_HASH.eq(drepHash))
                        .and(DELEGATION_VOTE.DREP_TYPE.in("ADDR_KEYHASH", "SCRIPTHASH")))
                .queryTimeout(deadline.timeout()).fetch().forEach(record -> idsByType.put(record.get(1, String.class),
                        Optional.ofNullable(record.get(0, String.class)).orElseGet(() -> drepId(drepHash, record.get(1, String.class)))));
        dsl.selectDistinct(VOTING_PROCEDURE.VOTER_TYPE).from(VOTING_PROCEDURE)
                .where(VOTING_PROCEDURE.VOTER_HASH.eq(drepHash))
                .and(VOTING_PROCEDURE.VOTER_TYPE.in("DREP_KEY_HASH", "DREP_SCRIPT_HASH"))
                .queryTimeout(deadline.timeout()).fetch().forEach(record -> {
                    String type = "DREP_SCRIPT_HASH".equals(record.get(VOTING_PROCEDURE.VOTER_TYPE))
                            ? "SCRIPTHASH" : "ADDR_KEYHASH";
                    idsByType.putIfAbsent(type, drepId(drepHash, type));
                });
        // State rows also preserve identities when historical certificates were pruned.
        dsl.selectDistinct(DREP.DREP_ID).from(DREP).where(DREP.DREP_HASH.eq(drepHash))
                .and(DREP.DREP_ID.isNotNull()).queryTimeout(deadline.timeout()).fetch(DREP.DREP_ID).forEach(id ->
                        idsByType.put(BFDRepIdentity.parse(id).credType(), id));
        deadline.timeout();
        return idsByType.values().stream().sorted().toList();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<BFDRep> findDRepById(BFDRepIdentity identity) {
        if (identity == null) {
            return Optional.empty();
        }
        DRepDeadline deadline = new DRepDeadline();
        int currentEpoch = fetchDRepCurrentEpoch(deadline);
        if (identity.isSpecial()) {
            BFDRep special=synthesizeSpecialDRep(identity,currentEpoch,deadline);
            deadline.timeout();
            return Optional.of(special);
        }

        var query = dsl.select(DREP.DREP_ID, DREP.DREP_HASH, DREP.STATUS, DREP.EPOCH)
                .from(DREP)
                .where(drepRowMatches(identity));
        var record = query
                .orderBy(DREP.SLOT.desc(), DREP.TX_INDEX.desc(), DREP.CERT_INDEX.desc())
                .limit(1)
                .queryTimeout(deadline.timeout()).fetchOne();
        if (record == null && !hasDRepReference(identity, deadline)) return Optional.empty();

        String drepId = record != null ? record.get(DREP.DREP_ID) : identity.drepId();
        String drepHash = record != null ? record.get(DREP.DREP_HASH) : identity.hash();
        boolean hasScript = identity.credType() != null
                ? identity.hasScript()
                : dsl.select(DREP_REGISTRATION.CRED_TYPE)
                .from(DREP_REGISTRATION)
                .where(drepRegistrationMatches(identity))
                .orderBy(DREP_REGISTRATION.SLOT.desc(),
                        DREP_REGISTRATION.TX_INDEX.desc(),
                        DREP_REGISTRATION.CERT_INDEX.desc())
                .limit(1)
                .queryTimeout(deadline.timeout()).fetchOptional()
                .map(r -> "SCRIPTHASH".equalsIgnoreCase(r.get(DREP_REGISTRATION.CRED_TYPE)))
                .orElse(false);

        Integer latestRegistrationEpoch = dsl.select(DREP_REGISTRATION.EPOCH)
                .from(DREP_REGISTRATION)
                .where(drepRegistrationMatches(identity))
                .and(DREP_REGISTRATION.TYPE.eq(CertificateType.REG_DREP_CERT.name()))
                .orderBy(DREP_REGISTRATION.SLOT.desc(),
                        DREP_REGISTRATION.TX_INDEX.desc(),
                        DREP_REGISTRATION.CERT_INDEX.desc())
                .limit(1)
                .queryTimeout(deadline.timeout()).fetchOptional(DREP_REGISTRATION.EPOCH)
                .orElse(null);

        Long amount = fetchAmountForIdentity(identity, drepId, drepHash, currentEpoch, deadline);

        var latestNegative = dsl.select(DREP_REGISTRATION.SLOT,DREP_REGISTRATION.TX_INDEX)
                .from(DREP_REGISTRATION).where(drepRegistrationMatches(identity))
                .and(DREP_REGISTRATION.TYPE.eq("UNREG_DREP_CERT"))
                .orderBy(DREP_REGISTRATION.SLOT.desc(),DREP_REGISTRATION.TX_INDEX.desc()).limit(1)
                .queryTimeout(deadline.timeout()).fetchOne();
        var latestPositive = dsl.select(DREP_REGISTRATION.SLOT,DREP_REGISTRATION.TX_INDEX)
                .from(DREP_REGISTRATION).where(drepRegistrationMatches(identity))
                .and(DREP_REGISTRATION.TYPE.eq("REG_DREP_CERT"))
                .orderBy(DREP_REGISTRATION.SLOT.desc(),DREP_REGISTRATION.TX_INDEX.desc()).limit(1)
                .queryTimeout(deadline.timeout()).fetchOne();
        boolean registered = registered(latestPositive == null ? null : new DRepTx(latestPositive.value1(),latestPositive.value2()),
                latestNegative == null ? null : new DRepTx(latestNegative.value1(),latestNegative.value2()));
        String status = registered ? "REGISTERED" : "RETIRED";
        Integer lastCertEpoch = dsl.select(DSL.max(DREP_REGISTRATION.EPOCH))
                .from(DREP_REGISTRATION).where(drepRegistrationMatches(identity)).queryTimeout(deadline.timeout()).fetchOne(0, Integer.class);
        if (record != null && (lastCertEpoch == null || record.get(DREP.EPOCH) != null
                && record.get(DREP.EPOCH) > lastCertEpoch)) lastCertEpoch = record.get(DREP.EPOCH);

        var lastVoteQuery = dsl.select(DSL.max(VOTING_PROCEDURE.EPOCH))
                .from(VOTING_PROCEDURE)
                .where(VOTING_PROCEDURE.VOTER_HASH.eq(drepHash));
        String voterType = identity.voterType();
        if (voterType != null) {
            lastVoteQuery = lastVoteQuery.and(VOTING_PROCEDURE.VOTER_TYPE.eq(voterType));
        } else {
            lastVoteQuery = lastVoteQuery.and(VOTING_PROCEDURE.VOTER_TYPE.in("DREP_KEY_HASH", "DREP_SCRIPT_HASH"));
        }
        Integer lastVoteEpoch = lastVoteQuery.queryTimeout(deadline.timeout()).fetchOne(0, Integer.class);
        Integer lastActiveEpoch = (lastVoteEpoch != null && (lastCertEpoch == null || lastVoteEpoch > lastCertEpoch))
                ? lastVoteEpoch : lastCertEpoch;

        boolean retired = "RETIRED".equalsIgnoreCase(status);

        boolean expired = false;
        if (!retired && lastActiveEpoch != null) {
            int drepActivity = fetchDRepActivity(deadline);
            if (drepActivity > 0 && currentEpoch > 0) {
                expired = (currentEpoch - lastActiveEpoch) > drepActivity;
            }
        }

        deadline.timeout();
        return Optional.of(BFDRep.builder()
                .drepId(drepId)
                .drepHash(drepHash)
                .status(status)
                .epoch(lastActiveEpoch)
                .activeEpoch(retired ? null : latestRegistrationEpoch)
                .amount(amount)
                .hasScript(hasScript)
                .expired(expired)
                .build());
    }

    private boolean hasDRepReference(BFDRepIdentity identity, DRepDeadline deadline) {
        Condition delegation = DELEGATION_VOTE.DREP_HASH.eq(identity.hash())
                .and(identity.credType() == null ? DELEGATION_VOTE.DREP_TYPE.in("ADDR_KEYHASH", "SCRIPTHASH")
                        : DELEGATION_VOTE.DREP_TYPE.eq(identity.credType()));
        Condition vote = VOTING_PROCEDURE.VOTER_HASH.eq(identity.hash())
                .and(identity.voterType() == null ? VOTING_PROCEDURE.VOTER_TYPE.in("DREP_KEY_HASH", "DREP_SCRIPT_HASH")
                        : VOTING_PROCEDURE.VOTER_TYPE.eq(identity.voterType()));
        return Boolean.TRUE.equals(dsl.select(DSL.exists(dsl.selectOne().from(DREP_REGISTRATION).where(drepRegistrationMatches(identity))))
                .queryTimeout(deadline.timeout()).fetchOne(0,Boolean.class))
                || Boolean.TRUE.equals(dsl.select(DSL.exists(dsl.selectOne().from(DELEGATION_VOTE).where(delegation)))
                .queryTimeout(deadline.timeout()).fetchOne(0,Boolean.class))
                || Boolean.TRUE.equals(dsl.select(DSL.exists(dsl.selectOne().from(VOTING_PROCEDURE).where(vote)))
                .queryTimeout(deadline.timeout()).fetchOne(0,Boolean.class));
    }

    /**
     * Protocol-defined DReps have no {@code drep} row. Identity and voting power
     * come from {@code drep_dist.drep_type}, matching the list endpoint.
     */
    private BFDRep synthesizeSpecialDRep(BFDRepIdentity identity, int currentEpoch, DRepDeadline deadline) {
        return BFDRep.builder()
                .drepId(identity.drepId())
                .drepHash("")
                .status("REGISTERED")
                .amount(fetchLatestSpecialDRepAmounts(currentEpoch, deadline).getOrDefault(identity.drepId(), 0L))
                .hasScript(false)
                .expired(false)
                .build();
    }

    /**
     * Current-snapshot voting power, matching list enrichment. A DRep absent from
     * canonical current epoch parameters (and the same local snapshot epoch) has
     * zero voting power rather than a historical amount.
     */
    private Long fetchAmountForIdentity(BFDRepIdentity identity, String drepId, String drepHash, int currentEpoch, DRepDeadline deadline) {
        if (drepId != null) {
            return fetchLatestDRepAmounts(List.of(drepId), currentEpoch, deadline).getOrDefault(drepId,0L);
        }

        var localQuery = dsl.select(LOCAL_DREP_DIST.AMOUNT)
                .from(LOCAL_DREP_DIST)
                .where(LOCAL_DREP_DIST.DREP_HASH.eq(drepHash != null ? drepHash : identity.hash()))
                .and(LOCAL_DREP_DIST.EPOCH.eq(currentEpoch));
        if (identity.credType() != null) {
            localQuery = localQuery.and(LOCAL_DREP_DIST.DREP_TYPE.eq(identity.credType()));
        }
        return localQuery.queryTimeout(deadline.timeout()).fetchOptional(LOCAL_DREP_DIST.AMOUNT).orElse(0L);
    }

    private Condition drepRowMatches(BFDRepIdentity identity) {
        if (identity.drepId() != null) {
            return DREP.DREP_ID.eq(identity.drepId());
        }
        return DREP.DREP_HASH.eq(identity.hash());
    }

    private Condition drepRegistrationMatches(BFDRepIdentity identity) {
        return drepRegistrationMatches(
                identity, DREP_REGISTRATION.DREP_ID, DREP_REGISTRATION.DREP_HASH, DREP_REGISTRATION.CRED_TYPE);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<BFDRepDelegator> findDRepDelegators(BFDRepIdentity identity, int page, int count, Order order) {
        var deadline = new DelegatorDeadline();
        Integer currentEpoch = dsl.select(BLOCK.EPOCH).from(BLOCK).orderBy(BLOCK.NUMBER.desc()).limit(1)
                .queryTimeout(deadline.timeout()).fetchOne(BLOCK.EPOCH);
        if (currentEpoch == null) throw new IllegalStateException("DRep delegator tip unavailable");
        Integer snapshotEpoch = findLatestStakeSnapshotEpoch(currentEpoch, deadline);
        var delegators = buildDRepDelegatorsQuery(identity, page, count, order, snapshotEpoch)
                .queryTimeout(deadline.timeout()).fetch();
        if (delegators.isEmpty()) {
            deadline.timeout();
            return List.of();
        }
        List<String> addresses = delegators.stream().map(r -> r.get("address", String.class)).toList();
        Map<String, BigInteger> rewardChanges = new HashMap<>();
        buildRewardBalanceQuery(addresses, snapshotEpoch, currentEpoch).queryTimeout(deadline.timeout()).fetch()
                .forEach(r -> rewardChanges.put(r.get("address", String.class),
                        r.get("amount", java.math.BigDecimal.class).toBigInteger()));
        deadline.timeout();
        // BF exposes the exact sum, including a negative reward residual. The native API's
        // protective reward clamp must not alter this compatibility result.
        return delegators.map(r -> BFDRepDelegator.builder().address(r.get("address", String.class))
                .amount(r.get("amount", java.math.BigDecimal.class).toBigInteger()
                        .add(r.get("checkpoint_reward", java.math.BigDecimal.class).toBigInteger())
                        .add(rewardChanges.getOrDefault(r.get("address", String.class), BigInteger.ZERO))
                        .longValueExact()).build());
    }

    private static final class DelegatorDeadline {
        private final long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        int timeout() {
            long remaining = end - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted())
                throw new IllegalStateException("DRep delegator deadline exceeded");
            return (int) Math.max(1, Math.min(3, java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(remaining)));
        }
    }

    private Integer findLatestStakeSnapshotEpoch(int currentEpoch, DelegatorDeadline deadline) {
        // These sources are replaced in one committed snapshot transaction. Match their marks
        // to a surviving completed job and require a nonempty UNLOGGED balance crash marker.
        String[] tables = {"ss_max_slot_balances", "ss_pool_rewards", "ss_pool_refund_rewards",
                "ss_insta_spendable_rewards", "ss_spendable_reward_rest", "ss_last_withdrawal"};
        Field<?>[] marks = new Field<?>[tables.length + 1];
        marks[0] = ADAPOT_JOBS.EPOCH.sub(1).as("checkpoint");
        for (int i=0; i<tables.length; i++) {
            marks[i+1] = dsl.select(DSL.field(DSL.name("mark_epoch"), Integer.class))
                    .from(DSL.table(DSL.name(tables[i]))).limit(1).asField("mark_"+i);
        }
        var record = dsl.select(marks).from(ADAPOT_JOBS).where(ADAPOT_JOBS.TYPE.eq("REWARD_CALC"))
                .and(ADAPOT_JOBS.STATUS.eq("COMPLETED")).and(ADAPOT_JOBS.EPOCH.le(currentEpoch))
                .orderBy(ADAPOT_JOBS.EPOCH.desc()).limit(1).queryTimeout(deadline.timeout()).fetchOne();
        if (record == null) return null;
        int checkpoint = record.get("checkpoint", Integer.class);
        if (record.get("mark_0", Integer.class) == null) return null;
        for (int i=0; i<tables.length; i++) {
            Integer mark = record.get("mark_"+i, Integer.class);
            if (mark != null && mark != checkpoint) return null;
        }
        return checkpoint;
    }

    Select<?> buildDRepDelegatorsQuery(BFDRepIdentity identity, int page, int count, Order order, Integer snapshotEpoch) {
        SortOrder direction = order == Order.desc ? SortOrder.DESC : SortOrder.ASC;
        var newerDelegation = DELEGATION_VOTE.as("newer_vote_delegation");
        var deregistration = STAKE_REGISTRATION.as("later_stake_deregistration");
        Condition newer = DSL.row(newerDelegation.SLOT,newerDelegation.TX_INDEX,newerDelegation.CERT_INDEX)
                .gt(DSL.row(DELEGATION_VOTE.SLOT,DELEGATION_VOTE.TX_INDEX,DELEGATION_VOTE.CERT_INDEX));
        // Canonical BF compares tx ids for registration and deregistration; certificate
        // position is used only to choose the latest vote delegation and order the page.
        Condition membership = DSL.exists(dsl.selectOne().from(STAKE_REGISTRATION)
                .where(STAKE_REGISTRATION.ADDRESS.eq(DELEGATION_VOTE.ADDRESS))
                .and(STAKE_REGISTRATION.TYPE.eq("STAKE_REGISTRATION")))
                .andNotExists(dsl.selectOne().from(deregistration)
                        .where(deregistration.ADDRESS.eq(DELEGATION_VOTE.ADDRESS))
                        .and(deregistration.TYPE.eq("STAKE_DEREGISTRATION"))
                        .and(DSL.row(deregistration.SLOT,deregistration.TX_INDEX)
                                .gt(DSL.row(DELEGATION_VOTE.SLOT,DELEGATION_VOTE.TX_INDEX))))
                .andNotExists(dsl.selectOne().from(newerDelegation)
                        .where(newerDelegation.ADDRESS.eq(DELEGATION_VOTE.ADDRESS)).and(newer));
        if (!identity.isSpecial()) {
            var latestRegistration = dsl.select(DREP_REGISTRATION.SLOT,DREP_REGISTRATION.TX_INDEX,DREP_REGISTRATION.CERT_INDEX)
                    .from(DREP_REGISTRATION).where(drepRegistrationMatches(identity))
                    .and(DREP_REGISTRATION.TYPE.eq("REG_DREP_CERT"))
                    .orderBy(DREP_REGISTRATION.SLOT.desc(),DREP_REGISTRATION.TX_INDEX.desc(),DREP_REGISTRATION.CERT_INDEX.desc())
                    .limit(1).asTable("latest_positive_registration");
            var latestDeregistration = dsl.select(DREP_REGISTRATION.SLOT,DREP_REGISTRATION.TX_INDEX,DREP_REGISTRATION.CERT_INDEX)
                    .from(DREP_REGISTRATION).where(drepRegistrationMatches(identity))
                    .and(DREP_REGISTRATION.TYPE.eq("UNREG_DREP_CERT"))
                    .orderBy(DREP_REGISTRATION.SLOT.desc(),DREP_REGISTRATION.TX_INDEX.desc(),DREP_REGISTRATION.CERT_INDEX.desc())
                    .limit(1).asTable("latest_negative_registration");
            Condition active = DSL.notExists(dsl.selectOne().from(latestDeregistration))
                    .or(DSL.exists(dsl.selectOne().from(latestRegistration).crossJoin(latestDeregistration)
                            .where(DSL.row(latestRegistration.field("slot",Long.class),latestRegistration.field("tx_index",Integer.class),latestRegistration.field("cert_index",Integer.class))
                                    .gt(DSL.row(latestDeregistration.field("slot",Long.class),latestDeregistration.field("tx_index",Integer.class),latestDeregistration.field("cert_index",Integer.class))))));
            Condition delegatedAfterRegistration = DSL.notExists(dsl.selectOne().from(latestRegistration))
                    .or(DSL.exists(dsl.selectOne().from(latestRegistration)
                            .where(DSL.row(DELEGATION_VOTE.SLOT,DELEGATION_VOTE.TX_INDEX)
                                    .ge(DSL.row(latestRegistration.field("slot",Long.class),latestRegistration.field("tx_index",Integer.class))))));
            membership = membership.and(active).and(delegatedAfterRegistration);
        }
        var paged = dsl.select(DELEGATION_VOTE.ADDRESS.as("address"),DELEGATION_VOTE.SLOT.as("sort_slot"),
                        DELEGATION_VOTE.TX_INDEX.as("sort_tx"),DELEGATION_VOTE.CERT_INDEX.as("sort_cert"))
                .from(DELEGATION_VOTE).where(delegationVoteMatches(identity)).and(membership)
                .orderBy(DELEGATION_VOTE.SLOT.sort(direction),DELEGATION_VOTE.TX_INDEX.sort(direction),DELEGATION_VOTE.CERT_INDEX.sort(direction))
                .limit(count).offset(offset(page,count)).asTable("page_delegators");
        var address = paged.field("address",String.class);
        var latestBalance = dsl.select(STAKE_ADDRESS_BALANCE.QUANTITY.cast(java.math.BigDecimal.class)).from(STAKE_ADDRESS_BALANCE)
                .where(STAKE_ADDRESS_BALANCE.ADDRESS.eq(address)).orderBy(STAKE_ADDRESS_BALANCE.SLOT.desc()).limit(1).asField();
        Field<java.math.BigDecimal> checkpointReward = DSL.inline(java.math.BigDecimal.ZERO);
        if (snapshotEpoch != null) {
            String[][] sources = {{"ss_pool_rewards","withdrawable_reward"},{"ss_pool_refund_rewards","pool_refund_withdrawable_reward"},
                    {"ss_insta_spendable_rewards","insta_withdrawable_reward"},{"ss_spendable_reward_rest","withdrawable_reward_rest"}};
            for (String[] source:sources) checkpointReward = checkpointReward.add(DSL.coalesce(
                    dsl.select(DSL.field(DSL.name(source[1]),java.math.BigDecimal.class)).from(DSL.table(DSL.name(source[0])))
                            .where(DSL.field(DSL.name("address"),String.class).eq(address))
                            .and(DSL.field(DSL.name("mark_epoch"),Integer.class).eq(snapshotEpoch)).asField(),DSL.inline(java.math.BigDecimal.ZERO)));
        }
        return dsl.select(address,DSL.coalesce(latestBalance,DSL.inline(java.math.BigDecimal.ZERO)).as("amount"),
                        checkpointReward.as("checkpoint_reward")).from(paged)
                .orderBy(paged.field("sort_slot").sort(direction),paged.field("sort_tx").sort(direction),paged.field("sort_cert").sort(direction));
    }

    Select<?> buildRewardBalanceQuery(List<String> addresses, Integer snapshotEpoch, int currentEpoch) {
        var rewards = REWARD.ADDRESS.in(addresses).and(REWARD.SPENDABLE_EPOCH.le(currentEpoch));
        var rest = REWARD_REST.ADDRESS.in(addresses).and(REWARD_REST.SPENDABLE_EPOCH.le(currentEpoch));
        var instant = INSTANT_REWARD.ADDRESS.in(addresses).and(INSTANT_REWARD.SPENDABLE_EPOCH.le(currentEpoch));
        var withdrawals = WITHDRAWAL.ADDRESS.in(addresses);
        if (snapshotEpoch != null) {
            // Fixed partition-key bounds prune checkpointed rewards, while the upper bound
            // always remains the latest block's epoch, never an ahead-of-tip epoch row.
            rewards = rewards.and(REWARD.SPENDABLE_EPOCH.gt(snapshotEpoch))
                    .and(REWARD.TYPE.eq("refund").or(REWARD.TYPE.in("member","leader")
                            .and(REWARD.EARNED_EPOCH.gt(snapshotEpoch).or(REWARD.SPENDABLE_EPOCH.gt(snapshotEpoch+1)))));
            rest = rest.and(REWARD_REST.SPENDABLE_EPOCH.gt(snapshotEpoch));
            instant = instant.and(INSTANT_REWARD.SPENDABLE_EPOCH.gt(snapshotEpoch+1));
            withdrawals = withdrawals.and(WITHDRAWAL.EPOCH.gt(snapshotEpoch));
        }
        var changes = dsl.select(REWARD.ADDRESS.as("address"),REWARD.AMOUNT.cast(java.math.BigDecimal.class).as("amount"))
                .from(REWARD).where(rewards)
                .unionAll(dsl.select(REWARD_REST.ADDRESS.as("address"),REWARD_REST.AMOUNT.cast(java.math.BigDecimal.class).as("amount")).from(REWARD_REST).where(rest))
                .unionAll(dsl.select(INSTANT_REWARD.ADDRESS.as("address"),INSTANT_REWARD.AMOUNT.cast(java.math.BigDecimal.class).as("amount")).from(INSTANT_REWARD).where(instant))
                .unionAll(dsl.select(WITHDRAWAL.ADDRESS.as("address"),WITHDRAWAL.AMOUNT.neg().cast(java.math.BigDecimal.class).as("amount")).from(WITHDRAWAL).where(withdrawals)).asTable("reward_changes");
        return dsl.select(changes.field("address",String.class),DSL.sum(changes.field("amount",java.math.BigDecimal.class)).as("amount"))
                .from(changes).groupBy(changes.field("address",String.class));
    }

    @Override
    public List<DRepRegistration> findDRepUpdates(BFDRepIdentity identity, int page, int count, Order order) {
        SortField<?> sortField = order == Order.desc
                ? DREP_REGISTRATION.SLOT.desc()
                : DREP_REGISTRATION.SLOT.asc();
        return dsl.select(
                        DREP_REGISTRATION.TX_HASH,
                        DREP_REGISTRATION.CERT_INDEX,
                        DREP_REGISTRATION.TYPE,
                        DREP_REGISTRATION.DREP_HASH,
                        DREP_REGISTRATION.DREP_ID,
                        DREP_REGISTRATION.ANCHOR_URL,
                        DREP_REGISTRATION.ANCHOR_HASH,
                        DREP_REGISTRATION.DEPOSIT,
                        DREP_REGISTRATION.SLOT,
                        DREP_REGISTRATION.EPOCH
                )
                .from(DREP_REGISTRATION)
                .where(drepRegistrationMatches(identity))
                .orderBy(sortField)
                .limit(count)
                .offset(offset(page, count))
                .fetch()
                .map(this::toDRepRegistrationDomain);
    }

    @Override
    public List<VotingProcedure> findDRepVotes(BFDRepIdentity identity, int page, int count, Order order) {
        SortField<?> sortField = order == Order.desc
                ? VOTING_PROCEDURE.SLOT.desc()
                : VOTING_PROCEDURE.SLOT.asc();
        var query = dsl.select(
                        VOTING_PROCEDURE.TX_HASH,
                        VOTING_PROCEDURE.IDX,
                        VOTING_PROCEDURE.TX_INDEX,
                        VOTING_PROCEDURE.SLOT,
                        VOTING_PROCEDURE.VOTER_HASH,
                        VOTING_PROCEDURE.VOTER_TYPE,
                        VOTING_PROCEDURE.VOTE,
                        VOTING_PROCEDURE.GOV_ACTION_TX_HASH,
                        VOTING_PROCEDURE.GOV_ACTION_INDEX,
                        VOTING_PROCEDURE.EPOCH
                )
                .from(VOTING_PROCEDURE)
                .where(VOTING_PROCEDURE.VOTER_HASH.eq(identity.hash()));
        String voterType = identity.voterType();
        if (voterType != null) {
            query = query.and(VOTING_PROCEDURE.VOTER_TYPE.eq(voterType));
        } else {
            query = query.and(VOTING_PROCEDURE.VOTER_TYPE.in("DREP_KEY_HASH", "DREP_SCRIPT_HASH"));
        }
        return query
                .orderBy(sortField)
                .limit(count)
                .offset(offset(page, count))
                .fetch()
                .map(this::toVotingProcedureDomain);
    }

    @Override
    public Optional<DRepRegistration> findDRepMetadata(BFDRepIdentity identity) {
        return dsl.select(
                        DREP_REGISTRATION.TX_HASH,
                        DREP_REGISTRATION.CERT_INDEX,
                        DREP_REGISTRATION.TYPE,
                        DREP_REGISTRATION.DREP_HASH,
                        DREP_REGISTRATION.DREP_ID,
                        DREP_REGISTRATION.ANCHOR_URL,
                        DREP_REGISTRATION.ANCHOR_HASH,
                        DREP_REGISTRATION.DEPOSIT,
                        DREP_REGISTRATION.SLOT,
                        DREP_REGISTRATION.EPOCH
                )
                .from(DREP_REGISTRATION)
                .where(drepRegistrationMatches(identity))
                .and(DREP_REGISTRATION.ANCHOR_URL.isNotNull())
                .orderBy(DREP_REGISTRATION.SLOT.desc(),
                        DREP_REGISTRATION.TX_INDEX.desc(),
                        DREP_REGISTRATION.CERT_INDEX.desc())
                .limit(1)
                .fetchOptional()
                .map(this::toDRepRegistrationDomain);
    }

    private Condition delegationVoteMatches(BFDRepIdentity identity) {
        if (identity.isSpecial()) {
            return DELEGATION_VOTE.DREP_TYPE.eq(identity.credType());
        }
        if (identity.drepId() != null) {
            return DELEGATION_VOTE.DREP_ID.eq(identity.drepId());
        }
        Condition hashMatch = DELEGATION_VOTE.DREP_HASH.eq(identity.hash());
        if (identity.credType() != null) {
            return hashMatch.and(DELEGATION_VOTE.DREP_TYPE.eq(identity.credType()));
        }
        return hashMatch;
    }

    private Condition drepRegistrationMatches(BFDRepIdentity identity,
                                             TableField<?, String> drepIdField,
                                             TableField<?, String> drepHashField,
                                             TableField<?, String> credTypeField) {
        if (identity.drepId() != null) {
            return drepIdField.eq(identity.drepId());
        }
        Condition hashMatch = drepHashField.eq(identity.hash());
        if (identity.credType() != null) {
            return hashMatch.and(credTypeField.eq(identity.credType()));
        }
        return hashMatch;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Proposal queries
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public List<BFProposal> findAllProposals(int page, int count, Order order) {
        SortOrder sortOrder = order == Order.desc ? SortOrder.DESC : SortOrder.ASC;
        int govActionLifetime = fetchGovActionLifetime();

        var proposals = dsl.select()
                .from(GOV_ACTION_PROPOSAL)
                .orderBy(
                        GOV_ACTION_PROPOSAL.SLOT.sort(sortOrder),
                        GOV_ACTION_PROPOSAL.TX_INDEX.sort(sortOrder),
                        GOV_ACTION_PROPOSAL.IDX.sort(sortOrder)
                )
                .limit(count)
                .offset(offset(page, count))
                .fetch();
        return enrichProposalRows(proposals, govActionLifetime);
    }

    @Override
    public Optional<BFProposal> findProposalByTxHashAndIndex(String txHash, int index) {
        int govActionLifetime = fetchGovActionLifetime();
        var proposal = dsl.select()
                .from(GOV_ACTION_PROPOSAL)
                .where(GOV_ACTION_PROPOSAL.TX_HASH.eq(txHash))
                .and(GOV_ACTION_PROPOSAL.IDX.eq(index))
                .fetchOptional();
        return proposal.map(record -> enrichProposalRows(List.of(record), govActionLifetime).getFirst());
    }

    @Override
    public Optional<BFProposal> findParameterChangeProposal(String txHash, int index) {
        int govActionLifetime = fetchGovActionLifetime();
        var proposal = dsl.select()
                .from(GOV_ACTION_PROPOSAL)
                .where(GOV_ACTION_PROPOSAL.TX_HASH.eq(txHash))
                .and(GOV_ACTION_PROPOSAL.IDX.eq(index))
                .and(GOV_ACTION_PROPOSAL.TYPE.eq("PARAMETER_CHANGE_ACTION"))
                .fetchOptional();
        return proposal.map(record -> enrichProposalRows(List.of(record), govActionLifetime).getFirst());
    }

    @Override
    public boolean isWithdrawalProposal(String txHash, int index) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(GOV_ACTION_PROPOSAL)
                        .where(GOV_ACTION_PROPOSAL.TX_HASH.eq(txHash))
                        .and(GOV_ACTION_PROPOSAL.IDX.eq(index))
                        .and(GOV_ACTION_PROPOSAL.TYPE.eq("TREASURY_WITHDRAWALS_ACTION"))
        );
    }

    @Override
    public List<BFDRepDelegator> findProposalWithdrawals(String txHash, int index,
                                                          int page, int count, Order order) {
        // Read withdrawals from gov_action_proposal.details JSON — no adapot dependency
        return dsl.select(GOV_ACTION_PROPOSAL.DETAILS)
                .from(GOV_ACTION_PROPOSAL)
                .where(GOV_ACTION_PROPOSAL.TX_HASH.eq(txHash))
                .and(GOV_ACTION_PROPOSAL.IDX.eq(index))
                .and(GOV_ACTION_PROPOSAL.TYPE.eq("TREASURY_WITHDRAWALS_ACTION"))
                .fetchOptional(GOV_ACTION_PROPOSAL.DETAILS)
                .map(json -> {
                    List<BFDRepDelegator> result = new ArrayList<>();
                    try {
                        JsonNode details = objectMapper.readTree(json.data());
                        JsonNode withdrawals = details.get("withdrawals");
                        if (withdrawals != null && withdrawals.isObject()) {
                            withdrawals.fields().forEachRemaining(entry -> result.add(
                                    BFDRepDelegator.builder()
                                            .address(entry.getKey())
                                            .amount(entry.getValue().asLong())
                                            .build()
                            ));
                        }
                        if (order == Order.desc) {
                            Collections.reverse(result);
                        }
                    } catch (Exception e) {
                        log.warn("Could not parse withdrawals from proposal details for {}/{}: {}", txHash, index, e.getMessage());
                    }
                    return result.stream()
                            .skip(offset(page, count))
                            .limit(count)
                            .toList();
                })
                .orElseGet(List::of);
    }

    @Override
    public List<VotingProcedure> findProposalVotes(String txHash, int index, int page, int count, Order order) {
        SortField<?> sortField = order == Order.desc
                ? VOTING_PROCEDURE.SLOT.desc()
                : VOTING_PROCEDURE.SLOT.asc();
        return dsl.select()
                .from(VOTING_PROCEDURE)
                .where(VOTING_PROCEDURE.GOV_ACTION_TX_HASH.eq(txHash))
                .and(VOTING_PROCEDURE.GOV_ACTION_INDEX.eq(index))
                .orderBy(sortField, VOTING_PROCEDURE.IDX.asc())
                .limit(count)
                .offset(offset(page, count))
                .fetch()
                .map(this::toVotingProcedureDomain);
    }

    @Override
    public Optional<BFProposal> findProposalMetadata(String txHash, int index) {
        int govActionLifetime = fetchGovActionLifetime();
        var proposal = dsl.select()
                .from(GOV_ACTION_PROPOSAL)
                .where(GOV_ACTION_PROPOSAL.TX_HASH.eq(txHash))
                .and(GOV_ACTION_PROPOSAL.IDX.eq(index))
                .and(GOV_ACTION_PROPOSAL.ANCHOR_URL.isNotNull())
                .fetchOptional();
        return proposal.map(record -> enrichProposalRows(List.of(record), govActionLifetime).getFirst());
    }
}
