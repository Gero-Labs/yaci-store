package com.bloxbean.cardano.yaci.store.submit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OgmiosBlockfrostRequestTest {
    @Test
    void blockfrostRequestForwardsAdditionalInputsDatumAndReferenceScriptToNode() throws Exception {
        ObjectMapper json = new ObjectMapper();
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var additional = json.readTree("""
                [{"transaction":{"id":"abc"},"index":3,"address":"addr_test1fixture","value":{"ada":{"lovelace":2500000}},"datum":"80","script":{"language":"plutus:v2","cbor":"4100"}}]
                """);
        server.expect(requestTo("http://ogmios.invalid"))
                .andExpect(content().json("""
                        {"jsonrpc":"2.0","method":"evaluateTransaction","params":{"transaction":{"cbor":"83000000"},"additionalUtxoSet":[{"transaction":{"id":"abc"},"index":3,"address":"addr_test1fixture","value":{"ada":{"lovelace":2500000}},"datum":"80","script":{"language":"plutus:v2","cbor":"4100"}}]}}
                        """))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"result\":[]}", org.springframework.http.MediaType.APPLICATION_JSON));
        var service = new OgmiosService(new MockEnvironment().withProperty("store.cardano.ogmios-url", "http://ogmios.invalid"), http, json);
        var result = service.evaluateBlockfrostTransaction(new byte[]{(byte) 0x83,0,0,0}, additional);
        assertThat(result.isRight()).isTrue();
        server.verify();
    }
    @Test
    void nodeHttp503IsUnavailableEvenWhenBodyLooksLikeRpcError() {
        ObjectMapper json = new ObjectMapper();
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://ogmios.invalid")).andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":-32603,\"message\":\"maintenance\"}}"));
        var service = new OgmiosService(new MockEnvironment().withProperty("store.cardano.ogmios-url", "http://ogmios.invalid"), http, json);
        assertThatThrownBy(() -> service.evaluateBlockfrostTransaction(new byte[]{0}, null))
                .isInstanceOf(com.bloxbean.cardano.client.api.exception.ApiException.class)
                .hasMessageContaining("unavailable");
        server.verify();
    }

    @Test
    void malformedSuccessfulHttpBodyIsUnavailableRatherThanEvaluationSuccess() {
        ObjectMapper json = new ObjectMapper();
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://ogmios.invalid")).andRespond(withSuccess("{\"error\":\"maintenance\"}", org.springframework.http.MediaType.APPLICATION_JSON));
        var service = new OgmiosService(new MockEnvironment().withProperty("store.cardano.ogmios-url", "http://ogmios.invalid"), http, json);
        assertThatThrownBy(() -> service.evaluateBlockfrostTransaction(new byte[]{0}, null))
                .isInstanceOf(com.bloxbean.cardano.client.api.exception.ApiException.class);
        server.verify();
    }

}
