package com.bloxbean.cardano.yaci.store.blockfrost.asset.service;

import com.bloxbean.cardano.yaci.store.blockfrost.asset.dto.BFAssetAddressDTO;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.dto.BFAssetDTO;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.dto.BFAssetDetailDTO;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.dto.BFAssetHistoryDTO;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.dto.BFAssetTransactionDTO;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.dto.BFAssetUtxoDTO;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.mapper.BFAssetMapper;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.mapper.BFAssetUtxoMapper;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.BFAssetQueryDeadline;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.BFAssetStorageReader;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl.model.BFAssetInfo;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BFAssetService {

    private final BFAssetStorageReader bfAssetStorageReader;
    private final BFAssetMapper bfAssetMapper = BFAssetMapper.INSTANCE;
    private final BFAssetUtxoMapper bfAssetUtxoMapper = new BFAssetUtxoMapper();

    public List<BFAssetDTO> getAssets(int page, int count, Order order) {
        return bfAssetStorageReader.findAssets(page, count, order).stream()
                .map(bfAssetMapper::toBFAssetDTO)
                .toList();
    }

    public BFAssetDetailDTO getAsset(String asset) {
        BFAssetInfo assetInfo = requireAssetInfo(asset);
        return bfAssetMapper.toBFAssetDetailDTO(assetInfo);
    }

    public List<BFAssetHistoryDTO> getAssetHistory(String asset, int page, int count, Order order) {
        requireAssetInfo(asset);

        return bfAssetStorageReader.findAssetHistory(asset, page, count, order).stream()
                .map(bfAssetMapper::toBFAssetHistoryDTO)
                .toList();
    }

    public List<String> getAssetTxs(String asset, int page, int count, Order order) {
        requireAssetInfo(asset);
        return bfAssetStorageReader.findAssetTxHashes(asset, page, count, order);
    }

    public List<BFAssetTransactionDTO> getAssetTransactions(String asset, int page, int count, Order order) {
        requireAssetInfo(asset);

        return bfAssetStorageReader.findAssetTransactions(asset, page, count, order).stream()
                .map(bfAssetMapper::toBFAssetTransactionDTO)
                .toList();
    }

    public List<BFAssetAddressDTO> getAssetAddresses(String asset, int page, int count, Order order) {
        requireAssetInfo(asset);

        return bfAssetStorageReader.findAssetAddresses(asset, page, count, order).stream()
                .map(bfAssetMapper::toBFAssetAddressDTO)
                .toList();
    }

    public List<BFAssetUtxoDTO> getAssetUtxos(String asset, int page, int count, Order order, String from, String to) {
        var deadline = new BFAssetQueryDeadline();
        Long fromHeight = blockHeight(from);
        Long toHeight = blockHeight(to);
        if (fromHeight != null && toHeight != null && fromHeight > toHeight) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lower block height exceeds upper block height");
        }
        var utxos = bfAssetStorageReader.findAssetUtxos(asset, page, count, order, fromHeight, toHeight, deadline);
        deadline.check();
        // A minted asset can have no current outputs, or the page/range may be past its current outputs.
        // Only an empty result needs this point lookup; full asset information includes expensive aggregates.
        if (utxos.isEmpty() && !bfAssetStorageReader.assetExists(asset, deadline)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found: " + asset);
        }
        var result = utxos.stream().map(utxo -> {
            deadline.check();
            return bfAssetUtxoMapper.toDTO(utxo);
        }).toList();
        deadline.check();
        return result;
    }

    private Long blockHeight(String bound) {
        if (bound == null) return null;
        try {
            if (!bound.matches("[+-]?[0-9]+(?::[+-]?[0-9]+)?")) throw new NumberFormatException();
            String[] parts = bound.split(":");
            // Blockfrost validates the optional transaction index but filters by the inclusive height alone.
            if (parts.length == 2 && Integer.parseInt(parts[1]) < 0) throw new NumberFormatException();
            int height = Integer.parseInt(parts[0]);
            if (height < 0) throw new NumberFormatException();
            return (long) height;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid block height: " + bound);
        }
    }

    public List<BFAssetDTO> getPolicyAssets(String policyId, int page, int count, Order order) {
        return bfAssetStorageReader.findAssetsByPolicy(policyId, page, count, order).stream()
                .map(bfAssetMapper::toBFAssetDTO)
                .toList();
    }

    private BFAssetInfo requireAssetInfo(String asset) {
        return bfAssetStorageReader.findAssetInfo(asset)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found: " + asset));
    }
}
