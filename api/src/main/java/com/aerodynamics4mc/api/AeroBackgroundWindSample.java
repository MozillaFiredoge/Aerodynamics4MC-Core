package com.aerodynamics4mc.api;

/** Horizontal mean wind before optional L0 terrain/roughness adjustments. */
public record AeroBackgroundWindSample(boolean available, float windX, float windZ) {
    public static final AeroBackgroundWindSample UNAVAILABLE = new AeroBackgroundWindSample(false,0,0);
    public AeroBackgroundWindSample {
        if (available && (!Float.isFinite(windX) || !Float.isFinite(windZ))) {
            throw new IllegalArgumentException("Background wind targets must be finite");
        }
    }
    public static AeroBackgroundWindSample available(float windX, float windZ) {
        return new AeroBackgroundWindSample(true,windX,windZ);
    }
}
