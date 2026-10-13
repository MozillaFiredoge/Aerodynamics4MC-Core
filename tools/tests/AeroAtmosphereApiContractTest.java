package com.aerodynamics4mc.api;

import java.util.OptionalDouble;

public final class AeroAtmosphereApiContractTest {
    public static void main(String[] args) throws Exception {
        A4mcId id = A4mcId.of("test", "planet");
        A4mcId other = A4mcId.of("test", "other");
        A4mcWorldRef world = A4mcWorldRef.server(id, new Object());
        near(AeroAtmosphereApi.altitudeLapseRate(id), 0.0065f, "Default lapse");
        long before = AeroAtmosphereApi.atmosphereRevision();
        AeroAtmosphereApi.setAltitudeLapseRate(id, 0.14f);
        check(AeroAtmosphereApi.atmosphereRevision() > before, "Setting invalidates forcing");
        near(AeroAtmosphereApi.altitudeLapseRate(id), 0.14f, "Custom lapse");
        near(AeroAtmosphereApi.altitudeLapseRate(other), 0.0065f, "Dimension isolation");
        before = AeroAtmosphereApi.atmosphereRevision();
        AeroAtmosphereApi.setAltitudeLapseRate(id, 0.14f);
        check(AeroAtmosphereApi.atmosphereRevision() == before, "Same value does not invalidate");
        for (float invalid : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            fails(IllegalArgumentException.class, () -> AeroAtmosphereApi.setAltitudeLapseRate(id, invalid));
        }
        AeroAtmosphereApi.setAltitudeLapseRate(id, 0);
        near(AeroAtmosphereApi.altitudeLapseRate(id), 0, "Zero disables lapse");
        AeroAtmosphereApi.clearAltitudeLapseRate(id);

        for (int cellSize : new int[]{64, 256}) {
            for (int z : new int[]{-30000000, -98304, -1, 0, 12288, 49152, 30000000}) {
                float wrapped = Math.floorDiv(z, cellSize) % 384.0f;
                if (wrapped < 0) wrapped += 384;
                float expected = Math.max(-1, Math.min(1, (wrapped - 192) / 192));
                var sample = AeroAtmosphereApi.sampleCoriolis(world, 0, z, cellSize);
                check(Float.floatToIntBits(sample.factor()) == Float.floatToIntBits(expected), "Exact legacy factor");
                near(sample.turningSign(), expected >= 0 ? 1 : -1, "Legacy turning direction");
            }
        }
        AutoCloseable handle = AeroAtmosphereApi.registerCoriolisProvider(id, (w, x, z) -> {
            check(w.platformHandle() == world.platformHandle(), "World handle passed through");
            return OptionalDouble.of(-0.75);
        });
        try {
            fails(IllegalStateException.class, () -> AeroAtmosphereApi.registerCoriolisProvider(id, (w,x,z) -> OptionalDouble.of(1)));
            for (int cellSize : new int[]{64, 256}) {
                var sample = AeroAtmosphereApi.sampleCoriolis(world, 12, 200000, cellSize);
                near(sample.factor(), -0.75f, "Both grids use the same custom model");
                near(sample.turningSign(), -1, "Southern hemisphere");
            }
            check(AeroAtmosphereApi.sampleCoriolis(A4mcWorldRef.server(other), 0, 0, 64).factor() == -1,
                  "Provider isolated by dimension");
        } finally { handle.close(); }
        before = AeroAtmosphereApi.atmosphereRevision();
        handle.close();
        check(AeroAtmosphereApi.atmosphereRevision() == before, "Close is idempotent");
        try (AutoCloseable next = AeroAtmosphereApi.registerCoriolisProvider(id, (w,x,z) -> OptionalDouble.of(0))) {
            handle.close();
            var sample = AeroAtmosphereApi.sampleCoriolis(world, 0, 0, 256);
            near(sample.factor(), 0, "Zero factor");
            near(sample.turningSign(), 0, "Equator disables turning, stale handle cannot remove new registration");
        }
        try (AutoCloseable empty = AeroAtmosphereApi.registerCoriolisProvider(id, (w,x,z) -> OptionalDouble.empty())) {
            near(AeroAtmosphereApi.sampleCoriolis(world, 0, 49152, 256).turningSign(), 1, "Unclaimed columns retain legacy zero sign");
        }
        int originalPeriod = AeroWorldWrapApi.periodBlocks(id);
        try {
            AeroWorldWrapApi.setPeriod(id, 4096);
            try (AutoCloseable periodic = AeroAtmosphereApi.registerCoriolisProvider(id, (w,x,z) -> {
                check(x == 12 && z == -12, "Canonical wrapping coordinates");
                return OptionalDouble.of(0.5);
            })) {
                near(AeroAtmosphereApi.sampleCoriolis(world, 4108, -4108, 64).factor(), 0.5f, "Periodic provider");
            }
        } finally { AeroWorldWrapApi.setPeriod(id, originalPeriod); }
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 1.1, -1.1}) {
            try (AutoCloseable ignored = AeroAtmosphereApi.registerCoriolisProvider(id, (w,x,z) -> OptionalDouble.of(invalid))) {
                fails(IllegalStateException.class, () -> AeroAtmosphereApi.sampleCoriolis(world, 17, 23, 64));
            }
        }
        RuntimeException cause = new RuntimeException("broken integration");
        try (AutoCloseable ignored = AeroAtmosphereApi.registerCoriolisProvider(id, (w,x,z) -> {throw cause;})) {
            try { AeroAtmosphereApi.sampleCoriolis(world, 17, 23, 64); throw new AssertionError("Failure hidden"); }
            catch (IllegalStateException failure) {
                check(failure.getCause() == cause && failure.getMessage().contains("17,23"), "Callback context and cause preserved");
            }
        }
        try (AutoCloseable ignored = AeroAtmosphereApi.registerCoriolisProvider(id, (w,x,z) -> null)) {
            fails(IllegalStateException.class, () -> AeroAtmosphereApi.sampleCoriolis(world, 0, 0, 64));
        }
        fails(IllegalArgumentException.class, () -> AeroAtmosphereApi.sampleCoriolis(A4mcWorldRef.client(id), 0, 0, 64));
        fails(IllegalArgumentException.class, () -> AeroAtmosphereApi.sampleCoriolis(world, 0, 0, 0));
        System.out.println("Atmosphere API contracts passed");
    }

    private static void near(float actual, float expected, String why) {
        check(Math.abs(actual - expected) < 1e-6, why + ": " + actual + " != " + expected);
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void fails(Class<? extends Exception> type, Runnable run) {
        try { run.run(); } catch (Exception failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Unexpected error", failure);
        }
        throw new AssertionError("Expected " + type);
    }
}
