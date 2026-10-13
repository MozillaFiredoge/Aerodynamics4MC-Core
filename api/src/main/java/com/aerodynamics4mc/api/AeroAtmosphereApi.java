package com.aerodynamics4mc.api;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Optional atmosphere settings for world-generation and planetary-weather integrations. */
public final class AeroAtmosphereApi {
    public static final float DEFAULT_ALTITUDE_LAPSE_RATE_K_PER_BLOCK = 0.0065f;
    private static final ConcurrentHashMap<A4mcId, Float> LAPSE_RATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<A4mcId, Registration> CORIOLIS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<A4mcId, WindRegistration> BACKGROUND_WIND = new ConcurrentHashMap<>();
    private static final java.util.Set<A4mcId> NO_BACKGROUND_DRAG = ConcurrentHashMap.newKeySet();
    private static final AtomicLong REVISION = new AtomicLong();

    private AeroAtmosphereApi() {}

    /** Set a dimension's lapse rate in K/block. Register during initialization for immediate initial targets. */
    public static synchronized void setAltitudeLapseRate(A4mcId dimensionId, float kelvinPerBlock) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (!Float.isFinite(kelvinPerBlock) || kelvinPerBlock < 0.0f) {
            throw new IllegalArgumentException("Lapse rate must be finite and non-negative: " + kelvinPerBlock);
        }
        if (altitudeLapseRate(dimensionId) == kelvinPerBlock) return;
        if (kelvinPerBlock == DEFAULT_ALTITUDE_LAPSE_RATE_K_PER_BLOCK) LAPSE_RATES.remove(dimensionId);
        else LAPSE_RATES.put(dimensionId, kelvinPerBlock);
        invalidateAtmosphere();
    }

    /** Restore the default rate for this dimension, for example when an integration unloads. */
    public static void clearAltitudeLapseRate(A4mcId dimensionId) {
        setAltitudeLapseRate(dimensionId, DEFAULT_ALTITUDE_LAPSE_RATE_K_PER_BLOCK);
    }

    public static float altitudeLapseRate(A4mcId dimensionId) {
        return LAPSE_RATES.getOrDefault(Objects.requireNonNull(dimensionId, "dimensionId"),
                DEFAULT_ALTITUDE_LAPSE_RATE_K_PER_BLOCK);
    }

    /**
     * One Coriolis authority per dimension. The returned handle removes only this registration
     * and is idempotent. Registration is process-wide; providers may inspect world.platformHandle().
     */
    public static AutoCloseable registerCoriolisProvider(A4mcId dimensionId, AeroCoriolisProvider provider) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Registration registration = new Registration(Objects.requireNonNull(provider, "provider"));
        if (CORIOLIS.putIfAbsent(dimensionId, registration) != null) {
            throw new IllegalStateException("Coriolis provider already registered for " + dimensionId);
        }
        invalidateAtmosphere();
        return () -> {
            if (CORIOLIS.remove(dimensionId, registration)) invalidateAtmosphere();
        };
    }


    /** Publish one external horizontal wind authority per dimension; unclaimed columns use built-in weather. */
    public static AutoCloseable registerBackgroundWindProvider(A4mcId dimensionId, AeroBackgroundWindProvider provider) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        WindRegistration registration = new WindRegistration(Objects.requireNonNull(provider, "provider"));
        if (BACKGROUND_WIND.putIfAbsent(dimensionId, registration) != null) {
            throw new IllegalStateException("Background wind provider already registered for " + dimensionId);
        }
        invalidateAtmosphere();
        return () -> { if (BACKGROUND_WIND.remove(dimensionId, registration)) invalidateAtmosphere(); };
    }

    /** Default true. Disable additional L0 roughness/terrain adjustments when the external model already includes them. */
    public static void setBackgroundWindDragEnabled(A4mcId dimensionId, boolean enabled) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        boolean changed = enabled ? NO_BACKGROUND_DRAG.remove(dimensionId) : NO_BACKGROUND_DRAG.add(dimensionId);
        if (changed) invalidateAtmosphere();
    }

    public static boolean backgroundWindDragEnabled(A4mcId dimensionId) {
        return !NO_BACKGROUND_DRAG.contains(Objects.requireNonNull(dimensionId, "dimensionId"));
    }

    public static AeroBackgroundWindSample sampleBackgroundWind(A4mcWorldRef world, int blockX, int blockZ) {
        Objects.requireNonNull(world, "world");
        if (!world.isServerSide()) throw new IllegalArgumentException("Background wind sampling requires a server world");
        WindRegistration registration = BACKGROUND_WIND.get(world.dimensionId());
        if (registration == null) return AeroBackgroundWindSample.UNAVAILABLE;
        int period = AeroWorldWrapApi.periodBlocks(world.dimensionId());
        int x = canonical(blockX, period), z = canonical(blockZ, period);
        try {
            return Objects.requireNonNull(registration.provider.sample(world,x,z), "sample");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Background wind provider for " + world + " failed at column " + x + "," + z, exception);
        }
    }

    /**
     * Sample one shared world-coordinate model, or preserve the caller's legacy cell-based model.
     * Callback failures and invalid results are errors, not silent fallbacks.
     */
    public static CoriolisSample sampleCoriolis(A4mcWorldRef world, int blockX, int blockZ, int legacyCellSizeBlocks) {
        Objects.requireNonNull(world, "world");
        if (!world.isServerSide()) throw new IllegalArgumentException("Coriolis sampling requires a server world");
        if (legacyCellSizeBlocks <= 0) throw new IllegalArgumentException("Cell size must be positive");
        Registration registration = CORIOLIS.get(world.dimensionId());
        if (registration != null) {
            int period = AeroWorldWrapApi.periodBlocks(world.dimensionId());
            int x = canonical(blockX, period);
            int z = canonical(blockZ, period);
            OptionalDouble sample;
            try {
                sample = Objects.requireNonNull(registration.provider.sample(world, x, z), "sample");
                if (sample.isPresent()) {
                    double factor = sample.getAsDouble();
                    if (!Double.isFinite(factor) || factor < -1.0 || factor > 1.0) {
                        throw new IllegalArgumentException("Coriolis factor must be finite and in [-1, 1]: " + factor);
                    }
                    float value = (float) factor;
                    return new CoriolisSample(value, Math.signum(value));
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Coriolis provider for " + world + " failed at column " + x + "," + z, exception);
            }
        }
        int cellZ = Math.floorDiv(blockZ, legacyCellSizeBlocks);
        // Keep the original float remainder and its positive-zero direction for unmodified worlds.
        float wrapped = cellZ % 384.0f;
        if (wrapped < 0.0f) wrapped += 384.0f;
        float factor = Math.max(-1.0f, Math.min(1.0f, (wrapped - 192.0f) / 192.0f));
        return new CoriolisSample(factor, factor >= 0.0f ? 1.0f : -1.0f);
    }

    private static int canonical(int coordinate, int period) {
        if (period <= 0) return coordinate;
        return (int) (Math.floorMod((long) coordinate + period / 2, period) - period / 2);
    }

    /** Publish provider state before invalidating; cached forcing updates at the next grid refresh. */
    public static void invalidateAtmosphere() {
        REVISION.incrementAndGet();
    }

    public static long atmosphereRevision() {
        return REVISION.get();
    }

    public record CoriolisSample(float factor, float turningSign) {}

    private static final class WindRegistration {
        private final AeroBackgroundWindProvider provider;
        private WindRegistration(AeroBackgroundWindProvider provider) { this.provider = provider; }
    }

    private static final class Registration {
        private final AeroCoriolisProvider provider;
        private Registration(AeroCoriolisProvider provider) { this.provider = provider; }
    }
}
