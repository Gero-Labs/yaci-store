package com.bloxbean.cardano.yaci.store.blockfrost.asset.mapper;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.serializers.PlutusDataJsonConverter;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.dto.BFAssetUtxoDTO;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl.model.BFAssetUtxo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class BFAssetUtxoMapper {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String LOVELACE = "lovelace";
    private static final Pattern ASSET_UNIT = Pattern.compile("[0-9a-f]{56}(?:[0-9a-f]{2}){0,32}");
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");

    public BFAssetUtxoDTO toDTO(BFAssetUtxo source) {
        if (blank(source.address()) || blank(source.txHash()) || source.outputIndex() == null
                || source.lovelaceAmount() == null || blank(source.blockHash())
                || source.blockHeight() == null || source.blockTime() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Asset output metadata unavailable");
        }
        String dataHash = source.dataHash();
        Object inlineDatumJson = null;
        if (source.inlineDatum() != null) {
            try {
                byte[] datumBytes = HexUtil.decodeHexString(source.inlineDatum());
                // Hash the indexed bytes. Decoding and serializing can change indefinite CBOR encodings.
                dataHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(datumBytes));
                PlutusData datum = PlutusData.deserialize(datumBytes);
                inlineDatumJson = OBJECT_MAPPER.readValue(PlutusDataJsonConverter.toJson(datum), Object.class);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Unable to decode indexed inline datum", e);
            }
        }
        return BFAssetUtxoDTO.builder()
                .address(source.address()).txHash(source.txHash()).outputIndex(source.outputIndex())
                .amount(amounts(source.lovelaceAmount(), source.amountsJson()))
                .block(source.blockHash()).blockHeight(source.blockHeight()).blockTime(source.blockTime())
                .dataHash(dataHash).inlineDatum(source.inlineDatum()).inlineDatumJson(inlineDatumJson)
                .referenceScriptHash(source.referenceScriptHash()).build();
    }

    private List<BFAssetUtxoDTO.Amount> amounts(Long lovelaceAmount, String amountsJson) {
        Map<String, BigInteger> amounts = new TreeMap<>(Comparator
                .comparing((String unit) -> !LOVELACE.equals(unit)).thenComparing(Comparator.naturalOrder()));
        try {
            JsonNode root = amountsJson == null ? null : OBJECT_MAPPER.readTree(amountsJson);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("Indexed output amounts must be an array");
            }
            for (JsonNode amount : root) {
                JsonNode unit = amount.get("unit");
                if (!amount.isObject() || unit == null || !unit.isTextual()
                        || (!LOVELACE.equals(unit.textValue()) && !ASSET_UNIT.matcher(unit.textValue()).matches())) {
                    throw new IllegalArgumentException("Invalid indexed output unit");
                }
                JsonNode quantity = amount.get("quantity");
                BigInteger value;
                if (quantity != null && quantity.isIntegralNumber()) {
                    value = quantity.bigIntegerValue();
                } else if (quantity != null && quantity.isTextual()
                        && INTEGER.matcher(quantity.textValue()).matches()) {
                    value = new BigInteger(quantity.textValue());
                } else {
                    throw new IllegalArgumentException("Indexed output quantity must be an integer");
                }
                amounts.merge(unit.textValue(), value, BigInteger::add);
            }
        } catch (IOException | IllegalArgumentException malformed) {
            // A matched asset output must carry its complete value. Skipping an invalid entry
            // or truncating a fraction would publish an incomplete successful response.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to decode indexed output amounts", malformed);
        }
        // The dedicated ADA column is authoritative; amounts can also contain lovelace.
        amounts.put(LOVELACE, BigInteger.valueOf(lovelaceAmount));
        return amounts.entrySet().stream()
                .map(entry -> new BFAssetUtxoDTO.Amount(entry.getKey(), entry.getValue().toString())).toList();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
