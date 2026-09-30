package com.aerodynamics4mc.api;

/**
 * Supplies coarse terrain for columns owned by an integration.
 * Callbacks run on the atmosphere simulation thread, including for unloaded chunks.
 * Implementations must be thread-safe and must not load chunks or wait on the server thread.
 */
@FunctionalInterface
public interface AeroTerrainProvider {
    AeroTerrainSample sample(A4mcWorldRef world, int blockX, int blockZ);
}
