package com.bloxbean.cardano.yaci.store.governance.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** Completed, registration-bound offchain outcome. Pending anchors have no outcome. */
public record DRepAnchorMetadata(JsonNode json, String bytes, Error error) {
    public record Error(String code, String message) { }
}
