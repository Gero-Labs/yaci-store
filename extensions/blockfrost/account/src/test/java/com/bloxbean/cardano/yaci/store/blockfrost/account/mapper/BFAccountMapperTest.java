package com.bloxbean.cardano.yaci.store.blockfrost.account.mapper;

import com.bloxbean.cardano.yaci.store.blockfrost.account.dto.BFAccountAddressesTotalDto;
import com.bloxbean.cardano.yaci.store.blockfrost.account.storage.impl.model.AccountAddressesTotal;
import com.bloxbean.cardano.yaci.store.utxo.domain.Amount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class BFAccountMapperTest {
    private static final String POLICY = "11111111111111111111111111111111111111111111111111111111";
    private static final String NEXT_POLICY = "22222222222222222222222222222222222222222222222222222222";
    private final ObjectMapper json = new ObjectMapper();
    private final BFAccountMapper mapper = BFAccountMapper.INSTANCE;

    @Test
    void addressesTotalMatchesCompleteSavedPreviewBlockfrostBody() throws IOException {
        // This authentic received_sum arrived in first-appearance order, not policy/name order.
        assertSavedBody("preview");
    }

    @Test
    void addressesTotalPreservesCompleteSavedMainnetLovelaceOnlyBody() throws IOException {
        assertSavedBody("mainnet");
    }

    @Test
    void addressesTotalOrdersBothSumsByPolicyThenRawNameBytesWithoutChangingQuantities() {
        // Empty names precede nonempty names; 00ff precedes 01 despite being longer.
        Map<String, BigInteger> received = unorderedAmounts();
        Map<String, BigInteger> sent = new LinkedHashMap<>();
        received.entrySet().stream().toList().reversed()
                .forEach(entry -> sent.put(entry.getKey(), entry.getValue()));
        Map<String, BigInteger> originalReceived = new LinkedHashMap<>(received);
        Map<String, BigInteger> originalSent = new LinkedHashMap<>(sent);

        BFAccountAddressesTotalDto result = mapper.toAddressesTotalDto(
                new AccountAddressesTotal("stake", received, sent, 42));

        assertByteOrderedAmounts(result.getReceivedSum());
        assertByteOrderedAmounts(result.getSentSum());
        assertThat(result.getStakeAddress()).isEqualTo("stake");
        assertThat(result.getTxCount()).isEqualTo(42);
        assertThat(received.entrySet()).containsExactlyElementsOf(originalReceived.entrySet());
        assertThat(sent.entrySet()).containsExactlyElementsOf(originalSent.entrySet());
    }

    @Test
    void addressesTotalSerializesNullAndEmptySumsAsEmptyArrays() throws IOException {
        BFAccountAddressesTotalDto result = mapper.toAddressesTotalDto(
                new AccountAddressesTotal("stake", null, Map.of(), 0));

        JsonNode actual = json.readTree(json.writeValueAsString(result));
        assertThat(actual).isEqualTo(json.readTree("""
                {"stake_address":"stake","received_sum":[],"sent_sum":[],"tx_count":0}
                """));
    }

    @Test
    void utxoAmountsKeepLovelaceFirstRawByteOrderAndNullQuantityFallback() {
        Map<String, BigInteger> amounts = unorderedAmounts();

        assertByteOrderedAmounts(mapper.mapToUtxoAmounts(amounts));
        amounts.remove("lovelace");
        assertThat(mapper.mapToUtxoAmounts(amounts))
                .extracting(Amount::getUnit, Amount::getQuantity)
                .containsExactly(
                        tuple(POLICY, BigInteger.valueOf(4)),
                        tuple(POLICY + "00ff", BigInteger.ZERO),
                        tuple(POLICY + "01", new BigInteger("9223372036854775808")),
                        tuple(NEXT_POLICY, BigInteger.valueOf(9)));
        assertThat(mapper.mapToUtxoAmounts(null)).isEmpty();
        assertThat(mapper.mapToUtxoAmounts(Map.of())).isEmpty();
    }

    private void assertSavedBody(String network) throws IOException {
        JsonNode fixture;
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/addresses-total/" + network + ".json")) {
            if (input == null) throw new IOException("Missing saved totals fixture: " + network);
            fixture = json.readTree(input);
        }
        JsonNode saved = fixture.get("nexus");
        AccountAddressesTotal source = new AccountAddressesTotal(
                saved.get("stake_address").asText(),
                amountsInSavedOrder(saved.get("received_sum")),
                amountsInSavedOrder(saved.get("sent_sum")),
                saved.get("tx_count").asLong());

        JsonNode actual = json.readTree(json.writeValueAsString(mapper.toAddressesTotalDto(source)));
        assertThat(actual).isEqualTo(fixture.get("blockfrost"));
    }

    private Map<String, BigInteger> amountsInSavedOrder(JsonNode rows) {
        Map<String, BigInteger> amounts = new LinkedHashMap<>();
        rows.forEach(row -> amounts.put(row.get("unit").asText(),
                new BigInteger(row.get("quantity").asText())));
        return amounts;
    }

    private Map<String, BigInteger> unorderedAmounts() {
        Map<String, BigInteger> amounts = new LinkedHashMap<>();
        amounts.put(NEXT_POLICY, BigInteger.valueOf(9));
        amounts.put(POLICY + "01", new BigInteger("9223372036854775808"));
        amounts.put(POLICY + "00ff", null);
        amounts.put("lovelace", BigInteger.valueOf(8));
        amounts.put(POLICY, BigInteger.valueOf(4));
        return amounts;
    }

    private void assertByteOrderedAmounts(List<Amount> amounts) {
        assertThat(amounts).extracting(Amount::getUnit, Amount::getQuantity)
                .containsExactly(
                        tuple("lovelace", BigInteger.valueOf(8)),
                        tuple(POLICY, BigInteger.valueOf(4)),
                        tuple(POLICY + "00ff", BigInteger.ZERO),
                        tuple(POLICY + "01", new BigInteger("9223372036854775808")),
                        tuple(NEXT_POLICY, BigInteger.valueOf(9)));
    }
}
