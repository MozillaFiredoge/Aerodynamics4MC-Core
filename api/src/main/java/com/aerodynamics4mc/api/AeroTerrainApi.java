package com.aerodynamics4mc.api;

import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/** Optional terrain inputs for A4MC's server-authoritative atmosphere. */
public final class AeroTerrainApi {
    private static final TreeMap<String, Registration> PROVIDERS = new TreeMap<>();
    private static volatile List<Registration> snapshot = List.of();
    private static final AtomicLong REVISION = new AtomicLong();

    private AeroTerrainApi() {}

    /**
     * Register during mod initialization, before atmosphere sampling starts.
     * The returned handle is idempotent and only removes this registration.
     * Registrations are process-wide; providers must check the supplied world.
     */
    public static synchronized AutoCloseable registerProvider(A4mcId providerId, AeroTerrainProvider provider) {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(provider, "provider");
        String key = providerId.toString();
        if (PROVIDERS.containsKey(key)) {
            throw new IllegalStateException("Terrain provider already registered: " + providerId);
        }
        Registration registration = new Registration(providerId, provider);
        PROVIDERS.put(key, registration);
        publishRegistrations();
        return () -> unregister(key, registration);
    }

    private static synchronized void unregister(String key, Registration registration) {
        if (PROVIDERS.remove(key, registration)) {
            publishRegistrations();
        }
    }

    private static void publishRegistrations() {
        snapshot = List.copyOf(PROVIDERS.values());
        invalidateTerrain();
    }

    /**
     * Invalidate cached coarse terrain in all server worlds after publishing changed terrain.
     * Resampling occurs on the next atmosphere refresh; this does not rebuild loaded L2 geometry.
     * Batch changes before calling this method; do not call it from a sample callback.
     */
    public static void invalidateTerrain() {
        REVISION.incrementAndGet();
    }

    /** Monotonic cache generation, intended for atmosphere implementations. */
    public static long terrainRevision() {
        return REVISION.get();
    }

    /**
     * Returns the unique claim or UNAVAILABLE. Conflicts, null results and callback failures
     * are reported rather than silently falling back to another terrain authority.
     */
    public static AeroTerrainSample sample(A4mcWorldRef world, int blockX, int blockZ) {
        Objects.requireNonNull(world, "world");
        if (!world.isServerSide()) {
            throw new IllegalArgumentException("Terrain sampling requires a server world");
        }
        AeroTerrainSample claimed = AeroTerrainSample.UNAVAILABLE;
        A4mcId claimedBy = null;
        for (Registration registration : snapshot) {
            AeroTerrainSample candidate;
            try {
                candidate = Objects.requireNonNull(registration.provider.sample(world, blockX, blockZ), "sample");
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Terrain provider " + registration.id + " failed at "
                        + world + " column " + blockX + "," + blockZ, exception);
            }
            if (!candidate.available()) continue;
            if (claimed.available()) {
                throw new IllegalStateException("Multiple terrain providers claimed " + world + " column "
                        + blockX + "," + blockZ + ": " + claimedBy + " and " + registration.id);
            }
            claimed = candidate;
            claimedBy = registration.id;
        }
        return claimed;
    }

    // Identity equality prevents a stale close handle from removing a later registration.
    private static final class Registration {
        private final A4mcId id;
        private final AeroTerrainProvider provider;

        private Registration(A4mcId id, AeroTerrainProvider provider) {
            this.id = id;
            this.provider = provider;
        }
    }
}
