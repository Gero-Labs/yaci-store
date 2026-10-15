package com.bloxbean.cardano.yaci.store.blockfrost.governance.controller;

import com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl.BFCommitteeStorageReader;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.util.BFCommitteeCredential;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Blockfrost Governance - Committee")
@RequestMapping("${blockfrost.apiPrefix}/governance/committee")
@ConditionalOnExpression("${store.extensions.blockfrost.governance.enabled:true}")
public class BFCommitteeController {
    private final BFCommitteeStorageReader reader;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode committee() {
        return reader.committee().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Committee not found"));
    }

    @GetMapping(value = "/votes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ArrayNode votes(@RequestParam(defaultValue = "1") @Min(1) @Max(21474836) int page,
                           @RequestParam(defaultValue = "100") @Min(1) @Max(100) int count,
                           @RequestParam(defaultValue = "asc") Order order) {
        return reader.votes(null, page - 1, count, order);
    }

    @GetMapping(value = "/{cc_id}/votes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ArrayNode memberVotes(@PathVariable("cc_id") String id,
                                 @RequestParam(defaultValue = "1") @Min(1) @Max(21474836) int page,
                                 @RequestParam(defaultValue = "100") @Min(1) @Max(100) int count,
                                 @RequestParam(defaultValue = "asc") Order order) {
        BFCommitteeCredential credential;
        try { credential = BFCommitteeCredential.parse(id); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or malformed cc credential id."); }
        return reader.votes(credential, page - 1, count, order);
    }
}
