package com.bloxbean.cardano.yaci.store.blockfrost.asset.controller;

import com.bloxbean.cardano.yaci.store.blockfrost.asset.service.BFAssetService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BFAssetUtxoControllerTest {
    @Test
    void publishedAssetUtxoRouteReturnsJsonInsteadOfMissingEndpoint() throws Exception {
        var controller = new BFAssetController(mock(BFAssetService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("blockfrost.apiPrefix", "/api/v1/blockfrost").build();

        mvc.perform(get("/api/v1/blockfrost/assets/" + "aa".repeat(28) + "/utxos"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }
}
