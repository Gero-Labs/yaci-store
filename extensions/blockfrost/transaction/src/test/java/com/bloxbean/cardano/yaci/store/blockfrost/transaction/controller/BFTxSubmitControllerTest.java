package com.bloxbean.cardano.yaci.store.blockfrost.transaction.controller;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.yaci.store.blockfrost.transaction.service.BFTxSubmitService;
import com.bloxbean.cardano.yaci.store.submit.service.OgmiosService;
import com.bloxbean.cardano.yaci.store.submit.service.TxSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BFTxSubmitControllerTest {
    private static final String TX_HASH = "0123456789abcdef".repeat(4);

    private final OgmiosService ogmios = mock(OgmiosService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var providers = new StaticListableBeanFactory();
        providers.addBean("ogmios", ogmios);
        var service = new BFTxSubmitService(new MockEnvironment(),
                providers.getBeanProvider(OgmiosService.class),
                providers.getBeanProvider(TxSubmissionService.class));
        mvc = MockMvcBuilders.standaloneSetup(new BFTxSubmitController(service))
                .addPlaceholderValue("blockfrost.apiPrefix", "/api/v0")
                .build();
    }

    @Test
    void absentCborBodyReachesSubmissionBackendAsEmptyBytes() throws Exception {
        when(ogmios.submitTx(any())).thenReturn(Result.error("decoder refused empty bytes"));

        var response = mvc.perform(post("/api/v0/tx/submit")
                        .contentType(MediaType.APPLICATION_CBOR))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(response.getResolvedException())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo("decoder refused empty bytes");
                });
        var submitted = ArgumentCaptor.forClass(byte[].class);
        verify(ogmios).submitTx(submitted.capture());
        assertThat(submitted.getValue()).isEmpty();
    }

    @Test
    void nonemptyCborBytesAndSuccessfulHashRemainUnchanged() throws Exception {
        byte[] cbor = {(byte) 0x84, 0, (byte) 0xff, (byte) 0x80};
        when(ogmios.submitTx(any())).thenReturn(Result.success(TX_HASH).withValue(TX_HASH));

        mvc.perform(post("/api/v0/tx/submit").contentType(MediaType.APPLICATION_CBOR).content(cbor))
                .andExpect(status().isOk())
                .andExpect(content().string(TX_HASH));

        var submitted = ArgumentCaptor.forClass(byte[].class);
        verify(ogmios).submitTx(submitted.capture());
        assertThat(submitted.getValue()).containsExactly(cbor);
    }

    @Test
    void unsupportedContentTypeStillRejectsBeforeSubmission() throws Exception {
        mvc.perform(post("/api/v0/tx/submit").contentType(MediaType.TEXT_PLAIN).content("80"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(ogmios);
    }
}
