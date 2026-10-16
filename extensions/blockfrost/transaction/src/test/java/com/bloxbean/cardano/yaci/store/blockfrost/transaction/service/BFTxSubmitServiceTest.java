package com.bloxbean.cardano.yaci.store.blockfrost.transaction.service;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.helper.model.TxResult;
import com.bloxbean.cardano.yaci.store.submit.service.OgmiosService;
import com.bloxbean.cardano.yaci.store.submit.service.TxSubmissionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BFTxSubmitServiceTest {
    private static final String TX_HASH = "0123456789abcdef".repeat(4);

    @Test
    void nullBodyReachesOgmiosAsEmptyBytesBeforeLocalNodeFallback() throws Exception {
        var ogmios = mock(OgmiosService.class);
        var local = mock(TxSubmissionService.class);
        when(ogmios.submitTx(any())).thenReturn(Result.success(TX_HASH).withValue(TX_HASH));
        var service = service(new MockEnvironment(), ogmios, local);

        assertThat(service.submitTx(null)).isEqualTo(TX_HASH);

        var submitted = ArgumentCaptor.forClass(byte[].class);
        verify(ogmios).submitTx(submitted.capture());
        assertThat(submitted.getValue()).isEmpty();
        verifyNoInteractions(local);
    }

    @Test
    void nullBodyReachesLocalNodeAsEmptyConwayBytes() {
        var local = mock(TxSubmissionService.class);
        when(local.submitTx(eq(TxBodyType.CONWAY), any())).thenReturn(new TxResult(TX_HASH, true, null));
        var service = service(new MockEnvironment(), null, local);

        assertThat(service.submitTx(null)).isEqualTo(TX_HASH);

        var submitted = ArgumentCaptor.forClass(byte[].class);
        verify(local).submitTx(eq(TxBodyType.CONWAY), submitted.capture());
        assertThat(submitted.getValue()).isEmpty();
    }

    @Test
    void externalSubmitApiStillTakesPrecedenceAndReceivesUnchangedBytes() {
        var ogmios = mock(OgmiosService.class);
        var local = mock(TxSubmissionService.class);
        var service = service(new MockEnvironment().withProperty("store.cardano.submit-api-url", "http://submit.invalid"),
                ogmios, local);
        var server = server(service);
        byte[] cbor = {(byte) 0x84, 0, (byte) 0xff, (byte) 0x80};
        server.expect(requestTo("http://submit.invalid"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/cbor"))
                .andExpect(content().bytes(cbor))
                .andRespond(withSuccess("\"" + TX_HASH + "\"", MediaType.APPLICATION_JSON));

        assertThat(service.submitTx(cbor)).isEqualTo("\"" + TX_HASH + "\"");

        server.verify();
        verifyNoInteractions(ogmios, local);
    }

    @Test
    void emptyExternalSubmissionPreservesProviderDecoderFailure() {
        var service = service(new MockEnvironment().withProperty("store.cardano.submit-api-url", "http://submit.invalid"),
                null, null);
        var server = server(service);
        String decoderError = "{\"tag\":\"TxSubmitFail\",\"contents\":{\"tag\":\"TxCmdTxReadError\",\"contents\":[]}}";
        server.expect(requestTo("http://submit.invalid"))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(decoderError));

        assertThatThrownBy(() -> service.submitTx(null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo(decoderError);
                });

        server.verify();
    }

    private static BFTxSubmitService service(MockEnvironment env, OgmiosService ogmios, TxSubmissionService local) {
        var providers = new StaticListableBeanFactory();
        if (ogmios != null) providers.addBean("ogmios", ogmios);
        if (local != null) providers.addBean("local", local);
        return new BFTxSubmitService(env, providers.getBeanProvider(OgmiosService.class),
                providers.getBeanProvider(TxSubmissionService.class));
    }

    private static MockRestServiceServer server(BFTxSubmitService service) {
        return MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(service, "restTemplate")).build();
    }
}
