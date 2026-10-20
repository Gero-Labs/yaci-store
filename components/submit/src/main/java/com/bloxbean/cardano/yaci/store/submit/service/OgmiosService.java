package com.bloxbean.cardano.yaci.store.submit.service;

import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.ogmios.http.OgmiosBackendService;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vavr.control.Either;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "store.cardano.ogmios-url")
@Slf4j
public class OgmiosService {
    private String ogmiosUrl;
    private OgmiosBackendService ogmiosBackendService; //Used only for Tx submission

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    public OgmiosService(Environment env, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.ogmiosUrl = env.getProperty("store.cardano.ogmios-url");
        this.ogmiosBackendService = new OgmiosBackendService(ogmiosUrl);
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        log.info("<< Ogmios Service initialized >> " + ogmiosUrl);
    }

    public Result<String> submitTx(byte[] cborTx) throws ApiException {
        Result<String> result = ogmiosBackendService.getTransactionService().submitTransaction(cborTx);
        return result;
    }

    public Either<JsonNode, JsonNode> evaluateTx(byte[] cborTx, Set<Utxo> additionalUtxos) throws ApiException {
        if (log.isDebugEnabled())
            log.debug("Evaluating tx ..." + ogmiosUrl);

        try {
            return evaluateTransactionViaJsonRpc(cborTx, Collections.emptyList());
        } catch (Exception e) {
            throw new ApiException("Error evaluating tx: ", e);
        }
    }

    public String getOgmiosUrl() {
        return ogmiosUrl;
    }

    /** The BF caller supplies an Ogmios-v6 UTxO array; do not silently replace it with []. */
    public Either<JsonNode, JsonNode> evaluateBlockfrostTransaction(byte[] cbor, JsonNode additionalUtxoSet) throws ApiException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", "evaluateTransaction");
        ObjectNode params = request.putObject("params");
        params.putObject("transaction").put("cbor", HexUtil.encodeHexString(cbor));
        params.set("additionalUtxo", additionalUtxoSet == null ? objectMapper.createArrayNode() : additionalUtxoSet);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        try {
            var response = restTemplate.postForEntity(getOgmiosUrl(), new HttpEntity<>(request.toString(), headers), String.class);
            JsonNode result = objectMapper.readTree(response.getBody());
            return blockfrostEvaluationResult(result);
        } catch (HttpServerErrorException unavailable) {
            throw new ApiException("Ogmios evaluation is unavailable", unavailable);
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode().value() == 429) {
                throw new ApiException("Ogmios evaluation is unavailable", error);
            }
            try {
                return blockfrostEvaluationResult(objectMapper.readTree(error.getResponseBodyAsString()));
            } catch (JsonProcessingException malformed) {
                throw new ApiException("Ogmios returned an invalid evaluation response", malformed);
            }
        } catch (Exception error) {
            throw new ApiException("Ogmios evaluation is unavailable", error);
        }
    }

    private Either<JsonNode, JsonNode> blockfrostEvaluationResult(JsonNode response) throws ApiException {
        if (response != null && response.isObject()) {
            if (response.path("result").isArray()) {
                return Either.right(response);
            }
            if (response.path("error").isObject() && response.path("error").has("code")) {
                return Either.left(response);
            }
        }
        throw new ApiException("Ogmios returned an invalid evaluation response");
    }

    public Either<JsonNode, JsonNode> evaluateTransactionViaJsonRpc(byte[] cbor, List<Object> additionalUtxoSet) throws ApiException {
        String evaluateTransactionEndpoint = getOgmiosUrl();

        String cborHex = HexUtil.encodeHexString(cbor);
        String requestBodyTemplate = """
        {
            "jsonrpc": "2.0",
            "method": "evaluateTransaction",
            "params": {
                "transaction": {
                    "cbor": "%s"
                },
                "additionalUtxo": %s
            }
        }
        """;
        String requestBody;
        try {
            String additionalUtxoSetJson = objectMapper.writeValueAsString(additionalUtxoSet == null ? Collections.emptyList() : additionalUtxoSet);
            requestBody = String.format(requestBodyTemplate, cborHex, additionalUtxoSetJson);
        } catch (Exception e) {
            throw new ApiException("Error serializing additionalUtxoSet to JSON: " + e.getMessage(), e);
        }

        // HTTP request setup
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(evaluateTransactionEndpoint, requestEntity, String.class);
            var res = objectMapper.readTree(response.getBody());
            if (res.has("result")) {
                return Either.right(res);
            } else {
                return Either.left(res);
            }
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            if (log.isDebugEnabled()) {
                // Handle 4xx and 5xx HTTP errors
                log.error("Error calling evaluateTransaction JSON-RPC endpoint: {}", ex.getMessage());
                log.error("Error response body: {}", ex.getResponseBodyAsString());
            }

            try {
                var res = objectMapper.readTree(ex.getResponseBodyAsString());
                return Either.left(res);
            } catch (JsonProcessingException jsonEx) {
                if (log.isDebugEnabled()) {
                    log.error("Failed to parse error response as JSON", jsonEx);
                }
            }

            throw new ApiException("Error calling evaluateTransaction JSON-RPC endpoint: " + ex.getMessage(), ex);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.error("Unexpected error when calling evaluateTransaction JSON-RPC endpoint: {}", e.getMessage(), e);
            }
            throw new ApiException("Unexpected error when calling evaluateTransaction JSON-RPC endpoint", e);
        }
    }

    public JsonNode transformTxEvaluationSuccessResultToV5BFFormat(JsonNode jsonNode) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("type", "jsonwsp/response");
        response.put("version", "1.0");
        response.put("servicename", "ogmios");
        response.put("methodname", "EvaluateTx");

        ObjectNode resultNode = objectMapper.createObjectNode();
        ObjectNode evaluationResultsNode = objectMapper.createObjectNode();
        resultNode.set("EvaluationResult", evaluationResultsNode);

        ArrayNode result = (ArrayNode) jsonNode.get("result");
        for (int i=0; i < result.size(); i++) {
            JsonNode validatorResult = result.get(i);
            var validatorNode = validatorResult.get("validator");
            var index = validatorNode.get("index").asInt();
            var purpose = validatorNode.get("purpose").asText();

            var budgetNode = validatorResult.get("budget");
            var memory = budgetNode.get("memory").asLong();
            var cpu = budgetNode.get("cpu").asLong();

            ObjectNode cost = objectMapper.createObjectNode();
            cost.put("memory", memory);
            cost.put("steps", cpu);

            var tagIndex = purpose.toLowerCase() + ":" + index;
            evaluationResultsNode.set(tagIndex, cost);
        }

        response.put("result", resultNode);

        return response;
    }

    public JsonNode transformTxEvaluationErrorToV5BFFormat(JsonNode jsonNode) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("type", "jsonwsp/response");
        response.put("version", "1.0");
        response.put("servicename", "ogmios");
        response.put("methodname", "EvaluateTx");

        ObjectNode resultNode = objectMapper.createObjectNode();

        JsonNode errorNode = jsonNode.get("error");
        resultNode.put("EvaluationFailure", errorNode);

        response.put("result", resultNode);

        return response;
    }
}
