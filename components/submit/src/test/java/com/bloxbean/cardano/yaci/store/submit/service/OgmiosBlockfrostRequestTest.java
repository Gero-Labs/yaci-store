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
                        {"jsonrpc":"2.0","method":"evaluateTransaction","params":{"transaction":{"cbor":"83000000"},"additionalUtxo":[{"transaction":{"id":"abc"},"index":3,"address":"addr_test1fixture","value":{"ada":{"lovelace":2500000}},"datum":"80","script":{"language":"plutus:v2","cbor":"4100"}}]}}
                        """))
                .andExpect(request -> {
                    var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("params").has("additionalUtxoSet")).isFalse();
                })
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"result\":[]}", org.springframework.http.MediaType.APPLICATION_JSON));
        var service = new OgmiosService(new MockEnvironment().withProperty("store.cardano.ogmios-url", "http://ogmios.invalid"), http, json);
        var result = service.evaluateBlockfrostTransaction(new byte[]{(byte) 0x83,0,0,0}, additional);
        assertThat(result.isRight()).isTrue();
        server.verify();
    }

    @Test
    void nullAdditionalInputsUseTheOgmiosV6EmptyArray() throws Exception {
        ObjectMapper json = new ObjectMapper();
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://ogmios.invalid"))
                .andExpect(content().json("""
                        {"jsonrpc":"2.0","method":"evaluateTransaction","params":{"transaction":{"cbor":"00"},"additionalUtxo":[]}}
                        """, true))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"result\":[]}", org.springframework.http.MediaType.APPLICATION_JSON));
        var service = new OgmiosService(new MockEnvironment().withProperty("store.cardano.ogmios-url", "http://ogmios.invalid"), http, json);
        assertThat(service.evaluateBlockfrostTransaction(new byte[]{0}, null).isRight()).isTrue();
        server.verify();
    }

    @Test
    void legacyJsonRpcCallForwardsTheSuppliedListUsingTheV6Parameter() throws Exception {
        ObjectMapper json = new ObjectMapper();
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var additional = java.util.List.<Object>of(json.readTree("""
                {"transaction":{"id":"abc"},"index":3,"address":"addr_test1fixture","value":{"ada":{"lovelace":2500000}},"script":{"language":"native","json":{"clause":"all","from":[]}}}
                """));
        server.expect(requestTo("http://ogmios.invalid"))
                .andExpect(content().json("""
                        {"jsonrpc":"2.0","method":"evaluateTransaction","params":{"transaction":{"cbor":"00"},"additionalUtxo":[{"transaction":{"id":"abc"},"index":3,"address":"addr_test1fixture","value":{"ada":{"lovelace":2500000}},"script":{"language":"native","json":{"clause":"all","from":[]}}}]}}
                        """, true))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"result\":[]}", org.springframework.http.MediaType.APPLICATION_JSON));
        var service = new OgmiosService(new MockEnvironment().withProperty("store.cardano.ogmios-url", "http://ogmios.invalid"), http, json);
        assertThat(service.evaluateTransactionViaJsonRpc(new byte[]{0}, additional).isRight()).isTrue();
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
