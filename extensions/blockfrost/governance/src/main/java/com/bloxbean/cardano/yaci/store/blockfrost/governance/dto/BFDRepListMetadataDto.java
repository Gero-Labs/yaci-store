package com.bloxbean.cardano.yaci.store.blockfrost.governance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.bloxbean.cardano.yaci.store.governance.domain.DRepAnchorMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BFDRepListMetadataDto {
    private String url;
    private String hash;
    private JsonNode jsonMetadata;
    private String bytes;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DRepAnchorMetadata.Error error;
}
