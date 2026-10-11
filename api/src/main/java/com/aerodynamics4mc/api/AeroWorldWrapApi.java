package com.aerodynamics4mc.api;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Horizontal wrap-around for dimensions whose world repeats, such as those made by looping-world mods.
 * <p>
 * In a dimension with period {@code P}, the columns {@code (x, z)}, {@code (x + P, z)} and {@code (x, z + P)}
 * are the same place. A4MC then keeps the weather continuous across the seam: the coarse grids follow
 * players over it instead of being rebuilt, the planetary weather repeats with the world, and fields the
 * server sends are placed at the copy of the world nearest the client player.
 * <p>
 * The wrapping mod sets the period on each side it runs on: the server when the dimension loads, the
 * client once it knows the period. Both sides share one setting when they run in the same process.
 */
public final class AeroWorldWrapApi {
    /** Periods are whole multiples of this, the size of A4MC's largest weather cell. */
    public static final int PERIOD_GRANULARITY_BLOCKS = 256;

    private static final Map<A4mcId, Integer> PERIODS = new ConcurrentHashMap<>();

    private AeroWorldWrapApi() {}

    /**
     * Sets the horizontal period of a dimension, or clears it with 0.
     *
     * @throws IllegalArgumentException if the period is negative or not a multiple of
     *         {@link #PERIOD_GRANULARITY_BLOCKS}
     */
    public static void setPeriod(A4mcId dimensionId, int periodBlocks) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (periodBlocks < 0 || periodBlocks % PERIOD_GRANULARITY_BLOCKS != 0) {
            throw new IllegalArgumentException("Period must be a non-negative multiple of "
                    + PERIOD_GRANULARITY_BLOCKS + " blocks: " + periodBlocks);
        }
        if (periodBlocks == 0) {
            PERIODS.remove(dimensionId);
        } else {
            PERIODS.put(dimensionId, periodBlocks);
        }
    }

    /** The horizontal period of a dimension in blocks, or 0 if it does not wrap. */
    public static int periodBlocks(A4mcId dimensionId) {
        return dimensionId == null ? 0 : PERIODS.getOrDefault(dimensionId, 0);
    }
}
