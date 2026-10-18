package com.bloxbean.cardano.yaci.store.blockfrost.governance.mapper;

import com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl.model.BFDRep;
import com.bloxbean.cardano.yaci.store.governance.domain.DRepRegistration;
import com.bloxbean.cardano.yaci.store.governance.domain.DRepAnchorMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BFDRepMetadataPersistenceWireTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void verifiedRawDocumentPopulatesDetailAndList() throws Exception {
        DRepRegistration registration = DRepRegistration.builder().drepId("drep_always_abstain").anchorUrl("https://example.com/m").anchorHash("hash").anchorMetadata(new DRepAnchorMetadata(json.readTree("{\"number\":1}"),"\\x7b226e756d626572223a317d",null)).build();
        JsonNode detail = json.valueToTree(BFDRepMapper.INSTANCE.toMetadataDto(registration));
        assertThat(detail.path("bytes").asText()).isEqualTo("\\x7b226e756d626572223a317d");
        assertThat(detail.path("json_metadata").path("number").asInt()).isEqualTo(1);
        assertThat(detail.has("error")).isFalse();
        BFDRep row = json.readValue("{\"anchorUrl\":\"https://example.com/m\",\"anchorHash\":\"hash\",\"anchorMetadata\":{\"json\":{\"number\":1},\"bytes\":\"\\\\x7b226e756d626572223a317d\"}}", BFDRep.class);
        JsonNode list = json.valueToTree(BFDRepMapper.INSTANCE.toListMetadataDto(row));
        assertThat(list.path("bytes").asText()).isEqualTo(detail.path("bytes").asText());
        assertThat(list.path("json_metadata")).isEqualTo(detail.path("json_metadata"));
        assertThat(list.has("error")).isFalse();
    }
    @Test void HistoricalErrorIsExactAndPendingOmitsErrorWhileKeepingNullFields() throws Exception {
        String message = "retained gateway429 then504; not a current HTTP observation";
        DRepRegistration registration = DRepRegistration.builder().anchorUrl("https://example.com/m").anchorMetadata(new DRepAnchorMetadata(null,null,new DRepAnchorMetadata.Error("HTTP_RESPONSE_ERROR",message))).build();
        JsonNode failed = json.valueToTree(BFDRepMapper.INSTANCE.toMetadataDto(registration));
        assertThat(failed.path("error").path("message").asText()).isEqualTo(message);
        assertThat(failed.path("error").path("code").asText()).isEqualTo("HTTP_RESPONSE_ERROR");
        assertThat(failed.path("json_metadata").isNull()).isTrue();
        assertThat(failed.path("bytes").isNull()).isTrue();
        JsonNode pending = json.valueToTree(BFDRepMapper.INSTANCE.toMetadataDto(DRepRegistration.builder().anchorUrl("https://example.com/m").build()));
        assertThat(pending.has("error")).isFalse();
        assertThat(pending.has("json_metadata")).isTrue();
        assertThat(pending.path("json_metadata").isNull()).isTrue();
        assertThat(pending.has("bytes")).isTrue();
    }
}
