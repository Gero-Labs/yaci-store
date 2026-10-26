package com.bloxbean.cardano.yaci.store.blockfrost.epoch.storage;

import com.bloxbean.cardano.yaci.store.adapot.domain.EpochStake;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.epochaggr.domain.Epoch;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

public interface BFEpochStorageReader {
    List<Epoch> findNextEpochs(int epoch, int page, int count);

    List<Epoch> findPreviousEpochs(int epoch, int page, int count);

    List<String> findBlockHashesByEpoch(int epoch, int page, int count, Order order);

    List<String> findBlockHashesByEpochAndPool(int epoch, String poolId, int page, int count, Order order);

    /** Returns positive stake entries, filtering the active-epoch snapshot before pagination. */
    List<EpochStake> findEpochStakes(int activeEpoch, int page, int count);

    /** Returns positive stake entries for a pool, filtering before pagination. */
    List<EpochStake> findEpochStakesByPool(int activeEpoch, String poolId, int page, int count);

    Map<Integer, BigInteger> getActiveStakesByEpochs(List<Integer> epochs);
}
