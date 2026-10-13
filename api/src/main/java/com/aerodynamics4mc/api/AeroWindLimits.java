package com.aerodynamics4mc.api;

/** Shared physical limits and coarse-wind wire units; local L2 atlas units are separate. */
public final class AeroWindLimits {
    public static final float MAX_WIND_SPEED_MPS = 40.0f;
    public static final float MAX_L1_LATTICE_SPEED = 0.35f;
    private AeroWindLimits() {}

    /** Scale a vector as a whole, retaining its direction. */
    public static float scale(float x, float y, float z) {
        double speed = Math.sqrt((double)x*x + (double)y*y + (double)z*z);
        return speed > MAX_WIND_SPEED_MPS ? (float)(MAX_WIND_SPEED_MPS / speed) : 1.0f;
    }

    /** Keep a 10% margin below the existing lattice-speed bound. */
    public static float stableStepSeconds(int cellSizeBlocks, float requestedSeconds) {
        if (cellSizeBlocks <= 0 || !Float.isFinite(requestedSeconds) || requestedSeconds <= 0) {
            throw new IllegalArgumentException("Cell size and step duration must be positive and finite");
        }
        return Math.min(requestedSeconds, cellSizeBlocks * MAX_L1_LATTICE_SPEED * 0.9f / MAX_WIND_SPEED_MPS);
    }

    public static short encodeCoarseVelocity(float velocity) {
        float value = Float.isFinite(velocity) ? velocity : 0;
        return (short)Math.round(Math.max(-1, Math.min(1, value / MAX_WIND_SPEED_MPS)) * 32767);
    }

    public static float decodeCoarseVelocity(short velocity) {
        return velocity / 32767.0f * MAX_WIND_SPEED_MPS;
    }

    /** Limit both the mean and the mean-plus-gust vector, retaining diagnostic metadata. */
    public static AeroWindSample limitWeatherSample(AeroWindSample sample) {
        float meanScale = scale(sample.velocityX(), sample.velocityY(), sample.velocityZ());
        float x = sample.velocityX() * meanScale;
        float y = sample.velocityY() * meanScale;
        float z = sample.velocityZ() * meanScale;
        float ex = x + sample.gustX(), ey = y + sample.gustY(), ez = z + sample.gustZ();
        float gustScale = scale(ex, ey, ez);
        if (meanScale == 1 && gustScale == 1) return sample;
        return new AeroWindSample(x,y,z,sample.pressure(),sample.level(),sample.authority(),
            sample.l1Epoch(),sample.worldDeltaEpoch(),sample.l2Epoch(),sample.confidence(),
            sample.temperatureKelvin(),sample.humidity(),sample.turbulenceIntensity(),
            ex*gustScale-x,ey*gustScale-y,ez*gustScale-z,sample.windShearXPerBlock(),sample.windShearZPerBlock(),
            sample.ablStability(),sample.ablMixingStrength());
    }
}
