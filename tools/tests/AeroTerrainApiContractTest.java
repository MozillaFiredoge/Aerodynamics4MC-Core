package com.aerodynamics4mc.api;

import java.util.ArrayList;
import java.util.List;

public final class AeroTerrainApiContractTest {
    private static final A4mcWorldRef WORLD = A4mcWorldRef.server(A4mcId.parse("minecraft:overworld"));
    private static final AeroTerrainSample CLAIM = AeroTerrainSample.available(274, 0.8f, 0.05f, AeroTerrainSurfaceClass.PLAINS);

    public static void main(String[] args) throws Exception {
        check(!AeroTerrainApi.sample(WORLD, 0, 0).available(), "Empty registry must fall back");
        long revision = AeroTerrainApi.terrainRevision();
        AeroTerrainProvider provider = (world, x, z) -> x == 12 ? CLAIM : AeroTerrainSample.UNAVAILABLE;
        AutoCloseable oldHandle = AeroTerrainApi.registerProvider(A4mcId.parse("test:terrain"), provider);
        check(AeroTerrainApi.terrainRevision() > revision, "Register must invalidate caches");
        check(AeroTerrainApi.sample(WORLD, 12, 0).equals(CLAIM), "Claimed column");
        check(!AeroTerrainApi.sample(WORLD, 13, 0).available(), "Unclaimed column");
        expect(IllegalStateException.class, () -> AeroTerrainApi.registerProvider(A4mcId.parse("test:terrain"), provider));
        revision = AeroTerrainApi.terrainRevision();
        oldHandle.close();
        check(AeroTerrainApi.terrainRevision() > revision, "Close must invalidate caches");
        try (AutoCloseable replacement = AeroTerrainApi.registerProvider(A4mcId.parse("test:terrain"), provider)) {
            oldHandle.close();
            check(AeroTerrainApi.sample(WORLD, 12, 0).available(), "Old handle must not remove replacement");
            try (AutoCloseable overlap = AeroTerrainApi.registerProvider(A4mcId.parse("test:overlap"), provider)) {
                expect(IllegalStateException.class, () -> AeroTerrainApi.sample(WORLD, 12, 0));
                check(!AeroTerrainApi.sample(WORLD, 13, 0).available(), "Disjoint unclaimed columns");
            }
        }
        List<String> calls = new ArrayList<>();
        try (AutoCloseable z = AeroTerrainApi.registerProvider(A4mcId.parse("test:z"), (w, x, y) -> {
            calls.add("z"); return AeroTerrainSample.UNAVAILABLE;
        }); AutoCloseable a = AeroTerrainApi.registerProvider(A4mcId.parse("test:a"), (w, x, y) -> {
            calls.add("a"); return AeroTerrainSample.UNAVAILABLE;
        })) {
            AeroTerrainApi.sample(WORLD, 0, 0);
            check(calls.equals(List.of("a", "z")), "Stable order independent of registration order");
        }
        RuntimeException failure = new RuntimeException("broken terrain");
        try (AutoCloseable broken = AeroTerrainApi.registerProvider(A4mcId.parse("test:broken"), (w, x, z) -> { throw failure; })) {
            try { AeroTerrainApi.sample(WORLD, 0, 0); throw new AssertionError("Failure swallowed"); }
            catch (IllegalStateException exception) {
                check(exception.getCause() == failure && exception.getMessage().contains("test:broken"), "Failure context");
            }
        }
        try (AutoCloseable broken = AeroTerrainApi.registerProvider(A4mcId.parse("test:null"), (w, x, z) -> null)) {
            expect(IllegalStateException.class, () -> AeroTerrainApi.sample(WORLD, 0, 0));
        }
        expect(IllegalArgumentException.class, () -> AeroTerrainApi.sample(A4mcWorldRef.client(WORLD.dimensionId()), 0, 0));
        expect(IllegalArgumentException.class, () -> AeroTerrainSample.available(Float.NaN, 0, 0, AeroTerrainSurfaceClass.ROCK));
        expect(IllegalArgumentException.class, () -> AeroTerrainSample.available(0, Float.POSITIVE_INFINITY, 0, AeroTerrainSurfaceClass.ROCK));
        expect(IllegalArgumentException.class, () -> AeroTerrainSample.available(0, 0, -1, AeroTerrainSurfaceClass.ROCK));
        expect(IllegalArgumentException.class, () -> AeroTerrainSample.available(0, 0, 0, AeroTerrainSurfaceClass.UNKNOWN));
        revision = AeroTerrainApi.terrainRevision();
        AeroTerrainApi.invalidateTerrain();
        check(AeroTerrainApi.terrainRevision() > revision, "Explicit terrain update");
        check(!AeroTerrainApi.sample(WORLD, 12, 0).available(), "All handles cleaned up");
        System.out.println("Terrain API contracts passed");
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    private static void expect(Class<? extends Exception> type, Runnable operation) {
        try { operation.run(); }
        catch (Exception exception) {
            if (type.isInstance(exception)) return;
            throw new AssertionError("Wrong exception", exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
