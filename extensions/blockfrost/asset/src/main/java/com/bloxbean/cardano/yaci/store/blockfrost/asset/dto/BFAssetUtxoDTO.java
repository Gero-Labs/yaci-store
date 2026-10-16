package com.bloxbean.cardano.yaci.store.blockfrost.asset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class BFAssetUtxoDTO {
    private String address;
    private String txHash;
    private Integer outputIndex;
    private List<Amount> amount;
    private String block;
    private Long blockHeight;
    private Long blockTime;
    private String dataHash;
    private String inlineDatum;
    private Object inlineDatumJson;
    private String referenceScriptHash;

    public record Amount(String unit, String quantity) {
    }
}
