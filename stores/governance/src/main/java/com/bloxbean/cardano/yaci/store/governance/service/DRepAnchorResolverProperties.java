package com.bloxbean.cardano.yaci.store.governance.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "store.governance.anchor-resolver")
public class DRepAnchorResolverProperties {
    private boolean enabled = false;
    private int batchSize = 16;
    private int retrySeconds = 3600;
    private long fetchBudgetMillis = 15000;
    private List<String> ipfsGateways = List.of();

    public void validate() {
        if (batchSize < 1 || batchSize > 16 || retrySeconds < 60 || retrySeconds > 86400
                || fetchBudgetMillis < 1 || fetchBudgetMillis > 15000
                || ipfsGateways == null || ipfsGateways.size() > 15)
            throw new IllegalArgumentException("Finite DRep resolver budgets required");
        for (String gateway : ipfsGateways) {
            var uri = java.net.URI.create(gateway);
            if (!DRepAnchorHttpFetcher.allowed(uri) || uri.getQuery() != null)
                throw new IllegalArgumentException("HTTP(S) IPFS gateway base required");
        }
    }
}
