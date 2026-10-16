package com.bloxbean.cardano.yaci.store.blockfrost.util.service;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.bip32.key.HdPublicKey;
import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.store.blockfrost.util.dto.BFDeriveAddressDto;
import com.bloxbean.cardano.yaci.store.common.config.StoreProperties;
import com.bloxbean.cardano.yaci.store.submit.service.TxEvaluationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vavr.control.Either;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BFUtilService {

    private final TxEvaluationService txEvaluationService;
    private final StoreProperties storeProperties;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENCODING_ERROR = "Invalid request: failed to decode payload from base64 or base16.";

    public JsonNode evaluateTx(byte[] cborTx, int version) {
        byte[] decoded = decodeCbor(cborTx == null ? "" : new String(cborTx, StandardCharsets.UTF_8));
        return evaluate(decoded, null, version);
    }

    public JsonNode evaluateTxWithUtxos(String cbor, JsonNode additionalUtxoSet, int version) {
        byte[] decoded = decodeCbor(cbor);
        JsonNode mapped;
        try {
            mapped = BFAdditionalUtxoMapper.toOgmios(additionalUtxoSet);
        } catch (IllegalArgumentException malformed) {
            // v5 Ogmios reports invalid tuple shapes as a 200 JSON-WSP client fault.
            return clientFault(ENCODING_ERROR);
        }
        // Hosted Blockfrost's additional-UTxO route always uses its v5 service.
        return evaluate(decoded, mapped, 5);
    }

    private JsonNode evaluate(byte[] cborTx, JsonNode additionalUtxoSet, int version) {
        // v5.6 decodeSerializedTx tries raw Babbage last. Untagged non-transaction CBOR
        // therefore ends with this precise decoder error, even after earlier era attempts.
        if (version != 6 && recordedNonTransaction(cborTx)) {
            return clientFault("Invalid request: Deserialisation failure while decoding serialised transaction. "
                    + "CBOR failed with error: DeserialiseFailure 0 \"expected tag\".");
        }
        try {
            Either<JsonNode, JsonNode> result = txEvaluationService.evaluateBlockfrostTx(cborTx, additionalUtxoSet);
            return formatResult(result, version);
        } catch (Exception unavailable) {
            // Transport/configuration failure is availability debt, never a caller's bad CBOR.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transaction evaluator unavailable.");
        }
    }

    public BFDeriveAddressDto deriveAddress(String xpub, int role, int index) {
        byte[] xpubBytes = HexUtil.decodeHexString(xpub);
        if (xpubBytes.length != 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid xpub length. Expected 64 bytes (public key + chain code), got " + xpubBytes.length);
        }

        CIP1852 cip1852 = new CIP1852();
        HdPublicKey paymentKey = cip1852.getPublicKeyFromAccountPubKey(xpubBytes, role, index);
        HdPublicKey stakeKey = cip1852.getPublicKeyFromAccountPubKey(xpubBytes, 2, 0);

        Network network = getNetwork();
        Address address = AddressProvider.getBaseAddress(paymentKey, stakeKey, network);

        return BFDeriveAddressDto.builder()
                .xpub(xpub)
                .role(role)
                .index(index)
                .address(address.toBech32())
                .build();
    }

    private Network getNetwork() {
        long protocolMagic = storeProperties.getProtocolMagic();
        if (protocolMagic == Networks.mainnet().getProtocolMagic()) {
            return Networks.mainnet();
        }
        return Networks.testnet();
    }

    private byte[] decodeCbor(String cbor) {
        if (cbor == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ENCODING_ERROR);
        }
        if (cbor.matches("(?:[0-9a-fA-F]{2})*")) {
            return HexUtil.decodeHexString(cbor);
        }
        try {
            if (cbor.length() % 4 != 0 || !cbor.matches("[A-Za-z0-9+/]*={0,2}")) {
                throw new IllegalArgumentException("Invalid encoding");
            }
            return Base64.getDecoder().decode(cbor);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ENCODING_ERROR);
        }
    }

    private JsonNode formatResult(Either<JsonNode, JsonNode> result, int version) {
        JsonNode source = result.isRight() ? result.get() : result.getLeft();
        if (version == 6) {
            ObjectNode response = source.deepCopy();
            response.put("jsonrpc", "2.0");
            response.put("method", "evaluateTransaction");
            response.put("id", UUID.randomUUID().toString());
            return response;
        }
        if (result.isLeft() && source.path("error").path("code").asInt() == -32602) {
            // Do not substitute a generic validation error for an unrecognized decoder failure.
            return clientFault(source.path("error").path("message").asText("Invalid request."));
        }
        ObjectNode response = (ObjectNode) (result.isRight()
                ? txEvaluationService.transformTxEvaluationSuccessResultToV5BFFormat(source)
                : txEvaluationService.transformTxEvaluationErrorToV5BFFormat(source));
        if (result.isRight()) {
            ObjectNode evaluation = (ObjectNode) response.path("result").path("EvaluationResult");
            renamePurpose(evaluation, "publish:", "certificate:");
            renamePurpose(evaluation, "withdraw:", "withdrawal:");
        }
        response.putObject("reflection").put("id", UUID.randomUUID().toString());
        return response;
    }

    private static void renamePurpose(ObjectNode evaluation, String from, String to) {
        java.util.List<String> names = new java.util.ArrayList<>();
        evaluation.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (name.startsWith(from)) {
                evaluation.set(to + name.substring(from.length()), evaluation.remove(name));
            }
        }
    }

    private static ObjectNode clientFault(String message) {
        ObjectNode response = JSON.createObjectNode();
        response.put("type", "jsonwsp/fault");
        response.put("version", "1.0");
        response.put("servicename", "ogmios");
        response.putObject("fault").put("code", "client").put("string", message);
        response.putObject("reflection").put("id", UUID.randomUUID().toString());
        return response;
    }

    private static boolean recordedNonTransaction(byte[] cbor) {
        // Keep this exact measured/source-derived set narrow. Other CBOR, including differently
        // encoded array headers and tag-24 wrappers, reaches the evaluator unchanged.
        return cbor.length == 0 || cbor.length == 1 && (cbor[0] == 0 || (cbor[0] & 0xff) == 0x80);
    }
}
