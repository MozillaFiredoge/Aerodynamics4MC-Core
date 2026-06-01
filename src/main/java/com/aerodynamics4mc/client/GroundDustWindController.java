package com.aerodynamics4mc.client;

import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.client.AeroClientWindApi;
import com.aerodynamics4mc.particle.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class GroundDustWindController {
    private static final double MIN_DUST_WIND_METERS_PER_SECOND = 3.0;
    private static final double MAX_DUST_WIND_METERS_PER_SECOND = 18.0;
    private static final double METERS_PER_SECOND_TO_BLOCKS_PER_TICK = 1.0 / 20.0;
    private static final double WIND_SMOOTHING = 0.18;
    private static final double MAX_SPAWN_BUDGET = 8.0;
    private static final int MAX_PARTICLES_PER_TICK = 4;
    private static final int MAX_SURFACE_ATTEMPTS = 10;

    private final RandomSource random = RandomSource.create();
    private ClientLevel lastLevel;
    private long lastTick = Long.MIN_VALUE;
    private Vec3 smoothedWind = Vec3.ZERO;
    private double spawnBudget;

    public void onClientTick(Minecraft minecraft) {
        //? >=1.21.11 {
        tickActive(minecraft);
        //?} <1.21.11 {
        /*clear();
        *///?}
    }

    public void clear() {
        this.lastLevel = null;
        this.lastTick = Long.MIN_VALUE;
        this.smoothedWind = Vec3.ZERO;
        this.spawnBudget = 0.0;
    }

    //? >=1.21.11 {
    private void tickActive(Minecraft minecraft) {
        if (minecraft == null || minecraft.isPaused() || minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }

        ClientLevel world = minecraft.level;
        long gameTime = world.getGameTime();
        if (world != this.lastLevel) {
            clear();
            this.lastLevel = world;
        }
        if (gameTime == this.lastTick) {
            return;
        }
        this.lastTick = gameTime;

        Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().position();
        Vec3 wind = sampleHorizontalWind(world, cameraPosition);
        double speed = Math.sqrt(wind.x * wind.x + wind.z * wind.z);
        if (speed < MIN_DUST_WIND_METERS_PER_SECOND) {
            this.spawnBudget = Math.max(0.0, this.spawnBudget - 0.35);
            return;
        }

        double windFactor = Mth.clamp(
                (speed - MIN_DUST_WIND_METERS_PER_SECOND)
                        / (MAX_DUST_WIND_METERS_PER_SECOND - MIN_DUST_WIND_METERS_PER_SECOND),
                0.0,
                1.0
        );
        double rate = windFactor * windFactor * (0.7 + windFactor * 1.8);
        this.spawnBudget = Math.min(MAX_SPAWN_BUDGET, this.spawnBudget + rate);

        int particlesToSpawn = Math.min(MAX_PARTICLES_PER_TICK, (int) this.spawnBudget);
        int spawned = 0;
        float rainLevel = world.getRainLevel(1.0f);
        for (int i = 0; i < particlesToSpawn; i++) {
            if (trySpawnParticle(world, cameraPosition, wind, speed, windFactor, rainLevel)) {
                this.spawnBudget -= 1.0;
                spawned++;
            }
        }
        if (spawned == 0) {
            this.spawnBudget = Math.min(this.spawnBudget, 1.25);
        }
    }

    private Vec3 sampleHorizontalWind(ClientLevel world, Vec3 cameraPosition) {
        AeroWindSample sample = AeroClientWindApi.sample(world, cameraPosition, SamplePolicy.CLIENT_LOCAL_PREFERRED);
        Vec3 target = sample.hasFlow() ? sample.effectiveVelocity() : Vec3.ZERO;
        double targetX = finiteClamp(target.x, -MAX_DUST_WIND_METERS_PER_SECOND, MAX_DUST_WIND_METERS_PER_SECOND);
        double targetZ = finiteClamp(target.z, -MAX_DUST_WIND_METERS_PER_SECOND, MAX_DUST_WIND_METERS_PER_SECOND);
        this.smoothedWind = new Vec3(
                Mth.lerp(WIND_SMOOTHING, this.smoothedWind.x, targetX),
                0.0,
                Mth.lerp(WIND_SMOOTHING, this.smoothedWind.z, targetZ)
        );
        return this.smoothedWind;
    }

    private boolean trySpawnParticle(
            ClientLevel world,
            Vec3 cameraPosition,
            Vec3 wind,
            double speed,
            double windFactor,
            float rainLevel
    ) {
        Vec3 direction = new Vec3(wind.x / speed, 0.0, wind.z / speed);
        Vec3 side = new Vec3(-direction.z, 0.0, direction.x);
        double radius = 5.0 + windFactor * 9.0;

        for (int attempt = 0; attempt < MAX_SURFACE_ATTEMPTS; attempt++) {
            double along = -(2.0 + this.random.nextDouble() * radius);
            double lateral = (this.random.nextDouble() * 2.0 - 1.0) * radius * 0.75;
            Vec3 candidate = cameraPosition.add(direction.scale(along)).add(side.scale(lateral));
            SpawnSurface surface = findSurface(world, candidate, cameraPosition.y);
            if (surface == null) {
                continue;
            }

            double weatherScale = surface.profile() == SurfaceProfile.SNOW
                    ? 1.0
                    : 1.0 - rainLevel * 0.85;
            if (weatherScale <= 0.08 || this.random.nextDouble() > surface.profile().spawnScale() * weatherScale) {
                continue;
            }

            spawnDust(world, surface, wind, windFactor);
            return true;
        }
        return false;
    }

    private SpawnSurface findSurface(ClientLevel world, Vec3 candidate, double cameraY) {
        int x = Mth.floor(candidate.x);
        int z = Mth.floor(candidate.z);
        int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (surfaceY <= world.getMinY() || surfaceY >= world.getMaxY()) {
            return null;
        }
        if (Math.abs(surfaceY - cameraY) > 5.0) {
            return null;
        }

        BlockPos surfacePos = new BlockPos(x, surfaceY, z);
        BlockState surfaceState = world.getBlockState(surfacePos);
        if (!surfaceState.isAir() && surfaceState.blocksMotion()) {
            return null;
        }
        if (!world.getFluidState(surfacePos).isEmpty() || !world.canSeeSky(surfacePos)) {
            return null;
        }

        BlockPos groundPos = surfacePos.below();
        BlockState groundState = world.getBlockState(groundPos);
        SurfaceProfile profile = profileForSurface(surfaceState);
        if (profile == null) {
            profile = profileForSurface(groundState);
        }
        if (profile == null) {
            return null;
        }
        return new SpawnSurface(surfacePos, profile);
    }

    private void spawnDust(ClientLevel world, SpawnSurface surface, Vec3 wind, double windFactor) {
        SurfaceProfile profile = surface.profile();
        double x = surface.surfacePos().getX() + this.random.nextDouble();
        double y = surface.surfacePos().getY() + 0.025 + this.random.nextDouble() * 0.04;
        double z = surface.surfacePos().getZ() + this.random.nextDouble();

        double sideJitter = (this.random.nextDouble() * 2.0 - 1.0) * (0.008 + windFactor * 0.018);
        double forwardJitter = (this.random.nextDouble() * 2.0 - 1.0) * 0.006;
        double speed = Math.sqrt(wind.x * wind.x + wind.z * wind.z);
        double dirX = speed > 1.0e-6 ? wind.x / speed : 0.0;
        double dirZ = speed > 1.0e-6 ? wind.z / speed : 0.0;
        double sideX = -dirZ;
        double sideZ = dirX;
        double velocityX = wind.x * METERS_PER_SECOND_TO_BLOCKS_PER_TICK * profile.initialCoupling()
                + dirX * forwardJitter + sideX * sideJitter;
        double velocityZ = wind.z * METERS_PER_SECOND_TO_BLOCKS_PER_TICK * profile.initialCoupling()
                + dirZ * forwardJitter + sideZ * sideJitter;
        double velocityY = profile.minInitialLift()
                + this.random.nextDouble() * (profile.maxInitialLift() - profile.minInitialLift());

        world.addParticle(profile.particleType(), x, y, z, velocityX, velocityY, velocityZ);
    }

    private static SurfaceProfile profileForSurface(BlockState state) {
        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)) {
            return SurfaceProfile.SNOW;
        }
        if (state.is(Blocks.RED_SAND)) {
            return SurfaceProfile.RED_SAND;
        }
        if (state.is(Blocks.SAND) || state.is(Blocks.SANDSTONE) || state.is(Blocks.SMOOTH_SANDSTONE)) {
            return SurfaceProfile.SAND;
        }
        if (state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.FARMLAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY)) {
            return SurfaceProfile.DIRT;
        }
        return null;
    }

    private static double finiteClamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Mth.clamp(value, min, max);
    }

    private record SpawnSurface(BlockPos surfacePos, SurfaceProfile profile) {}

    private enum SurfaceProfile {
        SAND(0.86, 0.62, 0.006, 0.032),
        RED_SAND(0.82, 0.58, 0.006, 0.030),
        DIRT(0.46, 0.44, 0.004, 0.022),
        SNOW(0.90, 0.74, 0.008, 0.036);

        private final double spawnScale;
        private final double initialCoupling;
        private final double minInitialLift;
        private final double maxInitialLift;

        SurfaceProfile(double spawnScale, double initialCoupling, double minInitialLift, double maxInitialLift) {
            this.spawnScale = spawnScale;
            this.initialCoupling = initialCoupling;
            this.minInitialLift = minInitialLift;
            this.maxInitialLift = maxInitialLift;
        }

        private double spawnScale() {
            return this.spawnScale;
        }

        private double initialCoupling() {
            return this.initialCoupling;
        }

        private double minInitialLift() {
            return this.minInitialLift;
        }

        private double maxInitialLift() {
            return this.maxInitialLift;
        }

        private SimpleParticleType particleType() {
            return switch (this) {
                case RED_SAND -> ModParticles.RED_SAND_DUST/*? neoforge{ */.get()/*?} */;
                case DIRT -> ModParticles.DIRT_DUST/*? neoforge{ */.get()/*?} */;
                case SNOW -> ModParticles.SNOW_DRIFT/*? neoforge{ */.get()/*?} */;
                case SAND -> ModParticles.SAND_DUST/*? neoforge{ */.get()/*?} */;
            };
        }
    }
    //?}
}
