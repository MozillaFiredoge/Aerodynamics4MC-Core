package com.aerodynamics4mc.api;

import java.util.Objects;

/** Terrain height is the first free Y coordinate, in blocks; temperature uses biome units. */
public record AeroTerrainSample(
        boolean available,
        float terrainHeightBlocks,
        float biomeTemperature,
        float roughnessLengthMeters,
        AeroTerrainSurfaceClass surfaceClass) {
    public static final AeroTerrainSample UNAVAILABLE =
            new AeroTerrainSample(false, 0, 0, 0, AeroTerrainSurfaceClass.UNKNOWN);

    public AeroTerrainSample {
        Objects.requireNonNull(surfaceClass, "surfaceClass");
        if (available) {
            if (!Float.isFinite(terrainHeightBlocks) || !Float.isFinite(biomeTemperature)
                    || !Float.isFinite(roughnessLengthMeters)) {
                throw new IllegalArgumentException("Available terrain samples must be finite");
            }
            if (roughnessLengthMeters < 0 || surfaceClass == AeroTerrainSurfaceClass.UNKNOWN) {
                throw new IllegalArgumentException("Available terrain requires non-negative roughness and a known surface class");
            }
        }
    }

    public static AeroTerrainSample available(float height, float temperature, float roughness,
            AeroTerrainSurfaceClass surfaceClass) {
        return new AeroTerrainSample(true, height, temperature, roughness, surfaceClass);
    }
}
