package com.aerodynamics4mc.api;

import java.util.OptionalDouble;

/** Supplies a signed, normalized Coriolis factor at a world column. */
@FunctionalInterface
public interface AeroCoriolisProvider {
    /**
     * Return a factor in [-1, 1]: positive for the northern hemisphere, negative for the southern,
     * and zero for no Coriolis turning. This is dimensionless, not a frequency in inverse seconds.
     * Return empty for worlds or columns this integration does not own.
     * Callbacks run on the simulation thread; use thread-safe snapshots without loading chunks
     * or waiting for the server thread. Coordinates are canonicalized in registered wrapping worlds.
     */
    OptionalDouble sample(A4mcWorldRef world, int blockX, int blockZ);
}
