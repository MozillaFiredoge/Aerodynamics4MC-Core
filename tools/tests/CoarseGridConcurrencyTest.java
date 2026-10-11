package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroTerrainApi;
import com.aerodynamics4mc.api.AeroWorldWrapApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import com.mojang.authlib.GameProfile;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Invoke run(world) from a server-started test addon; exercises real grids with a blocked terrain provider. */
public final class CoarseGridConcurrencyTest {
    public static void run(ServerLevel world) throws Exception {
        var id = world.dimension().location();
        A4mcId dimension = A4mcId.of(id.getNamespace(), id.getPath());
        int originalPeriod = AeroWorldWrapApi.periodBlocks(dimension);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger height = new AtomicInteger(274);
        SeedTerrainProvider terrain = (w, x, z) -> {
            calls.incrementAndGet();
            return new SeedTerrainProvider.TerrainSample(height.get(), 0.7f, 0.9f, (byte) 3);
        };
        BackgroundMetGrid background = new BackgroundMetGrid(256, 1, 100);
        MesoscaleGrid mesoscale = new MesoscaleGrid(64, 1, 16, 2, 3.2f, 100);
        WorldScaleDriver driver = WorldScaleDriver.loadOrCreate(null, world);
        BlockPos origin = new BlockPos(0, 320, 0);
        BlockPos far = new BlockPos(12000, 320, 12000);
        try {
            check(mesoscale.snapshot().gridWidth() == 0, "Uninitialized snapshot must be empty");
            AeroWorldWrapApi.setPeriod(dimension, 4096);
            background.refresh(world, null, origin, 1, 0.05f, terrain, driver);
            mesoscale.refresh(world, origin, 1, 0.05f, terrain, background);
            check(background.sample(origin) != null && mesoscale.sample(origin) != null, "Initial sampling");
            MesoscaleGrid.Snapshot oldSnapshot = mesoscale.snapshot();
            check(oldSnapshot.covers(new BlockPos(4096, 320, 4096)), "Wrapped snapshot coverage");
            check(!oldSnapshot.covers(far), "Distant destination must not use old weather");

            AeroWorldWrapApi.setPeriod(dimension, 8192);
            blockRefresh(worker, reader, terrain, calls,
                blocked -> background.refresh(world, null, far, 2, 0.05f, blocked, driver),
                () -> {
                    check(background.sample(new BlockPos(4096, 320, 4096)) != null,
                        "Readers must retain the published wrap period while rebuilding");
                    check(background.sample(far) == null, "Unpublished destination must return missing");
                    return null;
                });
            check(background.sample(far) != null, "Destination published after L0 refresh");

            blockRefresh(worker, reader, terrain, calls,
                blocked -> mesoscale.refresh(world, far, 2, 0.05f, blocked, background),
                () -> {
                    check(mesoscale.snapshot() == oldSnapshot, "Snapshot read must not wait or build a new snapshot");
                    check(mesoscale.sample(new BlockPos(4096, 320, 4096)) != null,
                        "L1 sampling must use the published wrap period");
                    check(mesoscale.sample(far) == null, "L1 missing columns must stay missing");
                    return null;
                });
            check(mesoscale.snapshot() != oldSnapshot && mesoscale.snapshot().covers(far), "Destination publication");
            check(mesoscale.sample(far) != null, "L1 destination sampling");

            MesoscaleGrid.Snapshot beforeChange = mesoscale.snapshot();
            height.set(350);
            AeroTerrainApi.invalidateTerrain();
            background.refresh(world, null, far, 3, 0.05f, terrain, driver);
            mesoscale.refresh(world, far, 3, 0.05f, terrain, background);
            check(beforeChange.terrainHeightBlocks()[0] == 274, "Published arrays must remain unchanged");
            check(mesoscale.snapshot().terrainHeightBlocks()[0] == 350, "Terrain invalidation must publish new data");
            check(oldSnapshot.terrainHeightBlocks()[0] == 274, "Old-center snapshot must remain unchanged");
            checkPayloadReads(world, background, mesoscale, terrain, driver, worker, reader, dimension);
            checkFocusRequests(world, dimension);
            mesoscale.close();
            check(mesoscale.snapshot().gridWidth() == 0 && mesoscale.sample(far) == null, "Close clears publication");
            System.out.println("COARSE_GRID_CONCURRENCY_PASS: nonblocking L0/L1 reads, no sampling side effects, atomic wrap mapping, terrain invalidation");
        } finally {
            AeroWorldWrapApi.setPeriod(dimension, originalPeriod);
            worker.shutdownNow();
            reader.shutdownNow();
            mesoscale.close();
        }
    }

    private interface Refresh { void run(SeedTerrainProvider provider); }

    private static void checkPayloadReads(ServerLevel world, BackgroundMetGrid background, MesoscaleGrid mesoscale,
                                         SeedTerrainProvider terrain, WorldScaleDriver driver, ExecutorService worker,
                                         ExecutorService reader, A4mcId dimension) throws Exception {
        AeroWorldWrapApi.setPeriod(dimension, 0);
        background.refresh(world, null, BlockPos.ZERO, 4, 0.05f, terrain, driver);
        mesoscale.refresh(world, BlockPos.ZERO, 4, 0.05f, terrain, background);
        AeroServerRuntime runtime = AeroServerRuntime.getInstance();
        var oldBackground = AeroServerRuntime.backgroundMetGrids.put(world.dimension(), background);
        var oldMesoscale = AeroServerRuntime.mesoscaleMetGrids.put(world.dimension(), mesoscale);
        var player = new ServerPlayer(world.getServer(), world,
            new GameProfile(UUID.randomUUID(), "Issue19Payload"), ClientInformation.createDefault());
        try {
            whileLocked(background, worker, reader, () -> {
                player.setPos(0, 320, 0);
                Object state = invoke(runtime, "coarseWindSyncStateForPlayer", player);
                check(invoke(runtime, "buildCoarseWindPayloadForPlayer", player, state) != null,
                    "Covered wind payload must use published data while L0 is locked");
                player.setPos(12000, 320, 12000);
                state = invoke(runtime, "coarseWindSyncStateForPlayer", player);
                check(invoke(runtime, "buildCoarseWindPayloadForPlayer", player, state) == null,
                    "Uncovered wind payload must be deferred while L0 is locked");
                return null;
            });
            whileLocked(mesoscale, worker, reader, () -> {
                player.setPos(0, 320, 0);
                Object first = invoke(runtime, "buildLocalWeatherPayloadForPlayer", player);
                check(first != null, "Covered weather payload must not wait for L1");
                check(first == invoke(runtime, "buildLocalWeatherPayloadForPlayer", player),
                    "Players in one dimension must reuse the published weather payload");
                player.setPos(12000, 320, 12000);
                check(invoke(runtime, "buildLocalWeatherPayloadForPlayer", player) == null,
                    "Uncovered weather payload must not send old-region weather");
                return null;
            });
            System.out.println("COARSE_GRID_PAYLOAD_PASS: nonblocking wind/weather packet builders, deferred misses, weather cache reuse");
        } finally {
            if (oldBackground == null) AeroServerRuntime.backgroundMetGrids.remove(world.dimension());
            else AeroServerRuntime.backgroundMetGrids.put(world.dimension(), oldBackground);
            if (oldMesoscale == null) AeroServerRuntime.mesoscaleMetGrids.remove(world.dimension());
            else AeroServerRuntime.mesoscaleMetGrids.put(world.dimension(), oldMesoscale);
            runtime.localWeatherServerStates.remove(world.dimension());
        }
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (var method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void whileLocked(Object grid, ExecutorService worker, ExecutorService reader,
                                    Callable<Void> read) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = worker.submit(() -> {
            synchronized (grid) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        try {
            check(entered.await(5, TimeUnit.SECONDS), "Grid lock holder started");
            reader.submit(read).get(1, TimeUnit.SECONDS);
            check(!holder.isDone(), "Packet builders finish before the grid lock is released");
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    private static void checkFocusRequests(ServerLevel world, A4mcId dimension) throws Exception {
        AeroServerRuntime runtime = AeroServerRuntime.getInstance();
        var previousFocus = new HashMap<>(runtime.backgroundRefreshFocus);
        var player = new ServerPlayer(world.getServer(), world,
            new GameProfile(UUID.randomUUID(), "Issue19Test"), ClientInformation.createDefault());
        var moved = AeroServerRuntime.class.getDeclaredMethod("backgroundFocusMoved", net.minecraft.server.MinecraftServer.class);
        var capture = AeroServerRuntime.class.getDeclaredMethod("captureBackgroundRefreshBatch", net.minecraft.server.MinecraftServer.class);
        moved.setAccessible(true);
        capture.setAccessible(true);
        world.players().add(player);
        try {
            AeroWorldWrapApi.setPeriod(dimension, 0);
            runtime.backgroundRefreshFocus.put(world.dimension(), BlockPos.ZERO);
            player.setPos(200, 320, 200);
            check(!(boolean) moved.invoke(runtime, world.getServer()), "Nearby moves retain periodic refresh cadence");
            player.setPos(12000, 320, 12000);
            check((boolean) moved.invoke(runtime, world.getServer()), "Teleport requests an immediate refresh");
            capture.invoke(runtime, world.getServer());
            check(!(boolean) moved.invoke(runtime, world.getServer()), "Pending destination must not queue duplicate requests");
            player.setPos(-12000, 320, -12000);
            check((boolean) moved.invoke(runtime, world.getServer()), "Another teleport replaces the requested focus");
            capture.invoke(runtime, world.getServer());
            check(runtime.backgroundRefreshFocus.get(world.dimension()).getX() == -12000, "Latest destination wins");
            AeroWorldWrapApi.setPeriod(dimension, 4096);
            runtime.backgroundRefreshFocus.put(world.dimension(), new BlockPos(2032, 320, 2032));
            player.setPos(-2032, 320, -2032);
            check(!(boolean) moved.invoke(runtime, world.getServer()), "Crossing a wrap seam must not trigger a distant rebuild");
        } finally {
            world.players().remove(player);
            runtime.backgroundRefreshFocus.clear();
            runtime.backgroundRefreshFocus.putAll(previousFocus);
        }
    }

    private static void blockRefresh(ExecutorService worker, ExecutorService reader, SeedTerrainProvider terrain,
                                     AtomicInteger calls, Refresh refresh, Callable<Void> read) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SeedTerrainProvider blocked = (world, x, z) -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test did not release terrain provider");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return terrain.sample(world, x, z);
        };
        Future<?> rebuilding = worker.submit(() -> refresh.run(blocked));
        try {
            check(entered.await(5, TimeUnit.SECONDS), "Refresh must reach the blocking provider");
            int before = calls.get();
            long start = System.nanoTime();
            reader.submit(read).get(1, TimeUnit.SECONDS);
            System.out.printf("COARSE_GRID_READ_MS: %.3f (refresh still blocked)%n", (System.nanoTime() - start) / 1_000_000.0);
            check(calls.get() == before, "Sampling must not call the terrain provider");
            check(!rebuilding.isDone(), "Reads must finish while refresh is still blocked");
        } finally {
            release.countDown();
            rebuilding.get(10, TimeUnit.SECONDS);
        }
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
