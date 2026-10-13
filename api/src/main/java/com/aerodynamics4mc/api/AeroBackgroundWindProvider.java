package com.aerodynamics4mc.api;

/** Server-side horizontal mean wind targets from an external planetary weather model. */
@FunctionalInterface
public interface AeroBackgroundWindProvider {
    /** Thread-safe callback at a world column, including unloaded chunks; velocities are in m/s. */
    AeroBackgroundWindSample sample(A4mcWorldRef world, int blockX, int blockZ);
}
