package com.bloxbean.cardano.yaci.store.blockfrost.util.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.IOException;

/** Keeps exact slot numbers before native-script validation, scoped to additionalUtxoSet. */
public final class BFAdditionalUtxoSetDeserializer extends JsonDeserializer<JsonNode> {
    private static final ObjectReader EXACT_NUMBERS = new ObjectMapper().readerFor(JsonNode.class)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Override
    public JsonNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return EXACT_NUMBERS.readValue(parser);
    }
}
