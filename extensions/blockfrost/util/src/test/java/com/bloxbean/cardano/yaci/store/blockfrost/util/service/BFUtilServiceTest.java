package com.bloxbean.cardano.yaci.store.blockfrost.util.service;

import com.bloxbean.cardano.yaci.store.blockfrost.util.dto.BFDeriveAddressDto;
import com.bloxbean.cardano.yaci.store.common.config.StoreProperties;
import com.bloxbean.cardano.yaci.store.submit.service.TxEvaluationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BFUtilServiceTest {
    private static final long MAINNET_PROTOCOL_MAGIC = 764824073L;
    private static final long PREPROD_PROTOCOL_MAGIC = 1097911063L;
    private static final String XPUB = "8e8131277ac46bf6b526fff4fe00cecc71026c278340c44458878bd162f70367505e1ea016e67052b3697ac749e4b034010056e7fd8e8486682665cd9693fb21";

    @Test
    void deriveAddressReturnsTestnetBaseAddress() {
        BFDeriveAddressDto result = service(PREPROD_PROTOCOL_MAGIC).deriveAddress(XPUB, 0, 0);

        assertThat(result.getXpub()).isEqualTo(XPUB);
        assertThat(result.getRole()).isEqualTo(0);
        assertThat(result.getIndex()).isEqualTo(0);
        assertThat(result.getAddress()).isEqualTo("addr_test1qzm3qwm0gsda3vavu43a4fe9ls8xlfjv45mjadxv6z5a3uq5wh8uvn3vr6vd7n80yyluxt87u336syy9678flma06sfs33797j");
    }

    @Test
    void deriveAddressReturnsMainnetBaseAddressWhenProtocolMagicIsMainnet() {
        BFDeriveAddressDto result = service(MAINNET_PROTOCOL_MAGIC).deriveAddress(XPUB, 0, 0);

        assertThat(result.getAddress()).isEqualTo("addr1qxm3qwm0gsda3vavu43a4fe9ls8xlfjv45mjadxv6z5a3uq5wh8uvn3vr6vd7n80yyluxt87u336syy9678flma06sfsj8r9jd");
    }

    @Test
    void deriveAddressUsesRequestedRoleAndIndex() {
        BFDeriveAddressDto result = service(PREPROD_PROTOCOL_MAGIC).deriveAddress(XPUB, 1, 7);

        assertThat(result.getRole()).isEqualTo(1);
        assertThat(result.getIndex()).isEqualTo(7);
        assertThat(result.getAddress()).isEqualTo("addr_test1qz7v5x33xz6dek5ssh25dm80hjp5a566q3f9gml870w2m3c5wh8uvn3vr6vd7n80yyluxt87u336syy9678flma06sfsjceddk");
    }

    @Test
    void deriveAddressRejectsInvalidXpubLength() {
        assertThatThrownBy(() -> service(PREPROD_PROTOCOL_MAGIC).deriveAddress("abcd", 0, 0))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("Invalid xpub length. Expected 64 bytes (public key + chain code), got 2");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "00", "80", "gA=="})
    void nonTransactionV5PayloadReturnsRecordedClientFault(String cbor) {
        TxEvaluationService evaluator = mock(TxEvaluationService.class);
        JsonNode response = new BFUtilService(evaluator, new StoreProperties()).evaluateTx(cbor.getBytes(StandardCharsets.UTF_8), 5);
        assertThat(response.path("type").asText()).isEqualTo("jsonwsp/fault");
        assertThat(response.path("fault").path("code").asText()).isEqualTo("client");
        assertThat(response.path("fault").path("string").asText()).isEqualTo(
                "Invalid request: Deserialisation failure while decoding serialised transaction. CBOR failed with error: DeserialiseFailure 0 \"expected tag\".");
        assertThat(response.path("reflection").path("id").asText()).matches("[a-f0-9-]{36}");
        verifyNoInteractions(evaluator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "a", "00 ", "*"})
    void invalidEncodingNeverReachesEvaluator(String cbor) {
        TxEvaluationService evaluator = mock(TxEvaluationService.class);
        assertThatThrownBy(() -> new BFUtilService(evaluator, new StoreProperties()).evaluateTx(cbor.getBytes(StandardCharsets.UTF_8), 5))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo("Invalid request: failed to decode payload from base64 or base16.");
                });
        verifyNoInteractions(evaluator);
    }

    @Test
    void hexAndBase64InputsAreDecodedBeforeConfiguredEvaluator() throws Exception {
        TxEvaluationService evaluator = mock(TxEvaluationService.class);
        ObjectMapper json = new ObjectMapper();
        when(evaluator.evaluateBlockfrostTx(any(), isNull())).thenReturn(Either.right(json.readTree("{\"result\":[]}")));
        BFUtilService service = new BFUtilService(evaluator, new StoreProperties());
        service.evaluateTx("83000000".getBytes(StandardCharsets.UTF_8), 6);
        service.evaluateTx("gwAAAA==".getBytes(StandardCharsets.UTF_8), 6);
        verify(evaluator, times(2)).evaluateBlockfrostTx(eq(new byte[]{(byte) 0x83, 0, 0, 0}), isNull());
    }

    @Test
    void v5PurposesAndReflectionMatchPublishedOgmiosContract() throws Exception {
        TxEvaluationService evaluator = mock(TxEvaluationService.class);
        ObjectMapper json = new ObjectMapper();
        JsonNode source = json.readTree("{\"result\":[]}");
        when(evaluator.evaluateBlockfrostTx(any(), isNull())).thenReturn(Either.right(source));
        when(evaluator.transformTxEvaluationSuccessResultToV5BFFormat(source)).thenReturn(json.readTree(
                "{\"type\":\"jsonwsp/response\",\"result\":{\"EvaluationResult\":{\"publish:2\":{\"memory\":10,\"steps\":20},\"withdraw:3\":{\"memory\":30,\"steps\":40}}}}"));
        JsonNode response = new BFUtilService(evaluator, new StoreProperties()).evaluateTx("83000000".getBytes(StandardCharsets.UTF_8), 5);
        assertThat(response.path("result").path("EvaluationResult").has("certificate:2")).isTrue();
        assertThat(response.path("result").path("EvaluationResult").has("withdrawal:3")).isTrue();
        assertThat(response.path("result").path("EvaluationResult").has("publish:2")).isFalse();
        assertThat(response.path("reflection").path("id").asText()).matches("[a-f0-9-]{36}");
    }

    @Test
    void v6PreservesNodeResultWithJsonRpcEnvelope() throws Exception {
        TxEvaluationService evaluator = mock(TxEvaluationService.class);
        ObjectMapper json = new ObjectMapper();
        JsonNode source = json.readTree("{\"error\":{\"code\":-32602,\"message\":\"decoder failure\"}}");
        when(evaluator.evaluateBlockfrostTx(any(), isNull())).thenReturn(Either.left(source));
        JsonNode response = new BFUtilService(evaluator, new StoreProperties()).evaluateTx("80".getBytes(StandardCharsets.UTF_8), 6);
        assertThat(response.path("error")).isEqualTo(source.path("error"));
        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.path("method").asText()).isEqualTo("evaluateTransaction");
        assertThat(response.path("id").asText()).matches("[a-f0-9-]{36}");
    }

    @Test
    void utxosRouteIgnoresVersionAndReportsInvalidTupleAsFault() throws Exception {
        ObjectMapper json = new ObjectMapper();
        TxEvaluationService evaluator = mock(TxEvaluationService.class);
        BFUtilService service = new BFUtilService(evaluator, new StoreProperties());
        JsonNode fault = service.evaluateTxWithUtxos("80", json.readTree("[[]]"), 6);
        assertThat(fault.path("fault").path("string").asText()).isEqualTo("Invalid request: failed to decode payload from base64 or base16.");
        assertThat(service.evaluateTxWithUtxos("80", json.readTree("[]"), 6).path("type").asText()).isEqualTo("jsonwsp/fault");
        verifyNoInteractions(evaluator);
    }

    @Test
    void unavailableNodeIs503RatherThanCaller400() throws Exception {
        TxEvaluationService evaluator = mock(TxEvaluationService.class);
        when(evaluator.evaluateBlockfrostTx(any(), isNull())).thenThrow(new RuntimeException("offline"));
        assertThatThrownBy(() -> new BFUtilService(evaluator, new StoreProperties()).evaluateTx("83000000".getBytes(StandardCharsets.UTF_8), 6))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private BFUtilService service(long protocolMagic) {
        StoreProperties storeProperties = new StoreProperties();
        storeProperties.setProtocolMagic(protocolMagic);
        return new BFUtilService(mock(TxEvaluationService.class), storeProperties);
    }
}
