package com.aerodynamics4mc.client;

import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.client.AeroClientWindApi;
import com.aerodynamics4mc.particle.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class ClientWindPresenceManager {
    private static final double MIN_VISIBLE_WIND_METERS_PER_SECOND = 0.60;
    private static final double MAX_VISUAL_WIND_METERS_PER_SECOND = 8.0;
    private static final double METERS_PER_SECOND_TO_BLOCKS_PER_TICK = 1.0 / 20.0;
    private static final int WIND_SAMPLE_INTERVAL_TICKS = 4;
    private static final int ENVIRONMENT_SAMPLE_INTERVAL_TICKS = 16;
    private static final int MAX_PARTICLES_PER_TICK = 5;
    private static final int CANDIDATE_ATTEMPTS = 14;

    private final RandomSource random = RandomSource.create();
    private ClientLevel lastLevel;
    private long lastTick = Long.MIN_VALUE;
    private Vec3 smoothedWind = Vec3.ZERO;
    private Vec3 gustWind = Vec3.ZERO;
    private int windSampleTicks;
    private int environmentSampleTicks;
    private float exposure = 0.35f;
    private float leafDensity;
    private float grassDensity;
    private float gustPulse;
    private float lastGustSpeed;
    private double leafBudget;
    private double grassBudget;
    private double traceBudget;

    public void onClientTick(Minecraft minecraft) {
        //? >=1.21.11 {
        tickActive(minecraft);
        //?} <1.21.11 {
        /*clear();
        *///?}
    }

    public void clear() {
        lastLevel = null;
        lastTick = Long.MIN_VALUE;
        smoothedWind = Vec3.ZERO;
        gustWind = Vec3.ZERO;
        windSampleTicks = 0;
        environmentSampleTicks = 0;
        exposure = 0.35f;
        leafDensity = 0.0f;
        grassDensity = 0.0f;
        gustPulse = 0.0f;
        lastGustSpeed = 0.0f;
        leafBudget = 0.0;
        grassBudget = 0.0;
        traceBudget = 0.0;
    }

    public void triggerGustPulse(Vec3 wind, float gustSpeed) {
        //? >=1.21.11 {
        if (wind != null && Double.isFinite(wind.x) && Double.isFinite(wind.z)) {
            gustWind = new Vec3(wind.x, 0.0, wind.z);
        }
        gustPulse = Math.max(gustPulse, Mth.clamp(gustSpeed / 4.5f, 0.30f, 1.0f));
        leafBudget = Math.min(10.0, leafBudget + 1.8 * gustPulse);
        grassBudget = Math.min(10.0, grassBudget + 1.4 * gustPulse);
        traceBudget = Math.min(8.0, traceBudget + 1.2 * gustPulse);
        //?}
    }

    //? >=1.21.11 {
    private void tickActive(Minecraft minecraft) {
        if (minecraft == null || minecraft.isPaused() || minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }

        ClientLevel world = minecraft.level;
        long gameTime = world.getGameTime();
        if (world != lastLevel) {
            clear();
            lastLevel = world;
        }
        if (gameTime == lastTick) {
            return;
        }
        lastTick = gameTime;

        BlockPos playerPos = minecraft.player.blockPosition();
        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().position();
        if (environmentSampleTicks-- <= 0) {
            environmentSampleTicks = ENVIRONMENT_SAMPLE_INTERVAL_TICKS;
            sampleEnvironment(world, playerPos);
        }
        if (windSampleTicks-- <= 0) {
            windSampleTicks = WIND_SAMPLE_INTERVAL_TICKS;
            sampleWind(world, cameraPos);
        }

        gustPulse = Math.max(0.0f, gustPulse - 0.035f);
        double speed = horizontalLength(smoothedWind);
        if (speed < MIN_VISIBLE_WIND_METERS_PER_SECOND && gustPulse <= 0.02f) {
            leafBudget = Math.max(0.0, leafBudget - 0.25);
            grassBudget = Math.max(0.0, grassBudget - 0.25);
            traceBudget = Math.max(0.0, traceBudget - 0.25);
            return;
        }

        double windFactor = smoothstep(
                MIN_VISIBLE_WIND_METERS_PER_SECOND,
                MAX_VISUAL_WIND_METERS_PER_SECOND,
                speed
        );
        double exposureWeight = Mth.clamp(exposure, 0.05f, 1.0f);
        double gustBoost = gustPulse * (0.55 + exposureWeight * 0.65);
        leafBudget = Math.min(10.0, leafBudget + leafDensity * exposureWeight * (0.05 + windFactor * 0.36 + gustBoost * 0.70));
        grassBudget = Math.min(10.0, grassBudget + grassDensity * exposureWeight * (0.04 + windFactor * 0.32 + gustBoost * 0.55));
        traceBudget = Math.min(8.0, traceBudget + exposureWeight * (windFactor * 0.05 + gustBoost * 0.18));

        int spawned = 0;
        while (spawned < MAX_PARTICLES_PER_TICK && leafBudget >= 1.0) {
            if (trySpawnLeafMote(world, cameraPos, activeWind())) {
                leafBudget -= 1.0;
                spawned++;
            } else {
                leafBudget = Math.min(leafBudget, 0.75);
                break;
            }
        }
        while (spawned < MAX_PARTICLES_PER_TICK && grassBudget >= 1.0) {
            if (trySpawnGrassMote(world, cameraPos, activeWind())) {
                grassBudget -= 1.0;
                spawned++;
            } else {
                grassBudget = Math.min(grassBudget, 0.75);
                break;
            }
        }
        while (spawned < MAX_PARTICLES_PER_TICK && traceBudget >= 1.0) {
            if (trySpawnGroundTrace(world, cameraPos, activeWind())) {
                traceBudget -= 1.0;
                spawned++;
            } else {
                traceBudget = Math.min(traceBudget, 0.75);
                break;
            }
        }
    }

    private void sampleWind(ClientLevel world, Vec3 cameraPos) {
        AeroWindSample sample = AeroClientWindApi.sample(world, cameraPos.add(0.0, 0.7, 0.0), SamplePolicy.CLIENT_LOCAL_PREFERRED);
        if (!sample.hasFlow()) {
            smoothedWind = smoothedWind.scale(0.82);
            gustPulse = Math.max(0.0f, gustPulse - 0.05f);
            lastGustSpeed = 0.0f;
            return;
        }

        Vec3 effective = sample.effectiveVelocity();
        Vec3 target = new Vec3(
                finiteClamp(effective.x, -MAX_VISUAL_WIND_METERS_PER_SECOND, MAX_VISUAL_WIND_METERS_PER_SECOND),
                0.0,
                finiteClamp(effective.z, -MAX_VISUAL_WIND_METERS_PER_SECOND, MAX_VISUAL_WIND_METERS_PER_SECOND)
        );
        smoothedWind = new Vec3(
                Mth.lerp(0.26, smoothedWind.x, target.x),
                0.0,
                Mth.lerp(0.26, smoothedWind.z, target.z)
        );

        float gustSpeed = (float) horizontalLength(sample.gustVelocity());
        float gustRise = gustSpeed - lastGustSpeed;
        if (gustRise > 0.24f && horizontalLength(smoothedWind) > 1.0) {
            triggerGustPulse(smoothedWind.add(sample.gustVelocity()), gustSpeed);
        }
        lastGustSpeed = gustSpeed;
    }

    private void sampleEnvironment(ClientLevel world, BlockPos playerPos) {
        if (world == null || playerPos == null) {
            exposure = 0.35f;
            leafDensity = 0.0f;
            grassDensity = 0.0f;
            return;
        }
        BlockPos head = playerPos.above();
        float sky = world.canSeeSkyFromBelowWater(head)
                ? 1.0f
                : world.getBrightness(LightLayer.SKY, head) / 15.0f;
        exposure = Mth.clamp(0.16f + sky * 0.84f - sampleShelter(world, head) * 0.52f, 0.05f, 1.0f);
        leafDensity = sampleLeafDensity(world, head);
        grassDensity = sampleGrassDensity(world, playerPos);
    }

    private boolean trySpawnLeafMote(ClientLevel world, Vec3 cameraPos, Vec3 wind) {
        double speed = horizontalLength(wind);
        if (speed < 0.10) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < CANDIDATE_ATTEMPTS; attempt++) {
            int x = Mth.floor(cameraPos.x) + random.nextInt(23) - 11;
            int y = Mth.floor(cameraPos.y) + random.nextInt(10) - 3;
            int z = Mth.floor(cameraPos.z) + random.nextInt(23) - 11;
            cursor.set(x, y, z);
            if (!world.isLoaded(cursor) || !world.getBlockState(cursor).is(BlockTags.LEAVES)) {
                continue;
            }
            double px = cursor.getX() + random.nextDouble();
            double py = cursor.getY() + random.nextDouble();
            double pz = cursor.getZ() + random.nextDouble();
            spawnMote(world, ModParticles.LEAF_MOTE/*? neoforge{ */.get()/*?} */, px, py, pz, wind, 0.36, 0.018, 0.028);
            return true;
        }
        return false;
    }

    private boolean trySpawnGrassMote(ClientLevel world, Vec3 cameraPos, Vec3 wind) {
        double speed = horizontalLength(wind);
        if (speed < 0.10) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < CANDIDATE_ATTEMPTS; attempt++) {
            int x = Mth.floor(cameraPos.x) + random.nextInt(19) - 9;
            int z = Mth.floor(cameraPos.z) + random.nextInt(19) - 9;
            int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            for (int dy = 1; dy >= -2; dy--) {
                cursor.set(x, surfaceY + dy, z);
                if (!world.isLoaded(cursor)) {
                    continue;
                }
                if (!isWindResponsivePlant(world, cursor)) {
                    continue;
                }
                double px = cursor.getX() + random.nextDouble();
                double py = cursor.getY() + 0.10 + random.nextDouble() * 0.65;
                double pz = cursor.getZ() + random.nextDouble();
                spawnMote(world, ModParticles.GRASS_MOTE/*? neoforge{ */.get()/*?} */, px, py, pz, wind, 0.30, 0.010, 0.020);
                return true;
            }
        }
        return false;
    }

    private boolean trySpawnGroundTrace(ClientLevel world, Vec3 cameraPos, Vec3 wind) {
        if (gustPulse < 0.30f && horizontalLength(wind) < 2.0) {
            return false;
        }
        double speed = horizontalLength(wind);
        if (speed < 0.10) {
            return false;
        }
        Vec3 direction = new Vec3(wind.x / speed, 0.0, wind.z / speed);
        Vec3 side = new Vec3(-direction.z, 0.0, direction.x);
        for (int attempt = 0; attempt < CANDIDATE_ATTEMPTS; attempt++) {
            double along = -3.0 - random.nextDouble() * 10.0;
            double lateral = (random.nextDouble() * 2.0 - 1.0) * 7.0;
            Vec3 candidate = cameraPos.add(direction.scale(along)).add(side.scale(lateral));
            int x = Mth.floor(candidate.x);
            int z = Mth.floor(candidate.z);
            int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (Math.abs(surfaceY - cameraPos.y) > 4.5) {
                continue;
            }
            BlockPos surfacePos = new BlockPos(x, surfaceY, z);
            if (!world.isLoaded(surfacePos) || !world.canSeeSky(surfacePos)) {
                continue;
            }
            BlockState ground = world.getBlockState(surfacePos.below());
            SimpleParticleType type = traceParticleFor(ground);
            if (type == null) {
                continue;
            }
            double px = surfacePos.getX() + random.nextDouble();
            double py = surfacePos.getY() + 0.03 + random.nextDouble() * 0.04;
            double pz = surfacePos.getZ() + random.nextDouble();
            spawnMote(world, type, px, py, pz, wind, 0.42, 0.006, 0.020);
            return true;
        }
        return false;
    }

    private void spawnMote(
            ClientLevel world,
            SimpleParticleType type,
            double x,
            double y,
            double z,
            Vec3 wind,
            double coupling,
            double minLift,
            double maxLift
    ) {
        double speed = horizontalLength(wind);
        double dirX = speed > 1.0e-6 ? wind.x / speed : 0.0;
        double dirZ = speed > 1.0e-6 ? wind.z / speed : 0.0;
        double sideX = -dirZ;
        double sideZ = dirX;
        double lateral = (random.nextDouble() * 2.0 - 1.0) * (0.006 + gustPulse * 0.018);
        double velocityX = wind.x * METERS_PER_SECOND_TO_BLOCKS_PER_TICK * coupling + sideX * lateral;
        double velocityY = minLift + random.nextDouble() * Math.max(0.001, maxLift - minLift) + gustPulse * 0.010;
        double velocityZ = wind.z * METERS_PER_SECOND_TO_BLOCKS_PER_TICK * coupling + sideZ * lateral;
        world.addParticle(type, x, y, z, velocityX, velocityY, velocityZ);
    }

    private Vec3 activeWind() {
        if (gustPulse > 0.04f && horizontalLength(gustWind) > 0.10) {
            return smoothedWind.add(gustWind.scale(gustPulse * 0.35));
        }
        return smoothedWind;
    }

    private float sampleShelter(ClientLevel world, BlockPos center) {
        int solid = 0;
        int checked = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (x == 0 && z == 0 && y >= 0) {
                        continue;
                    }
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!world.isLoaded(cursor)) {
                        continue;
                    }
                    checked++;
                    BlockState state = world.getBlockState(cursor);
                    if (!state.isAir() && !state.getCollisionShape(world, cursor).isEmpty()) {
                        solid++;
                    }
                }
            }
        }
        return checked <= 0 ? 0.0f : Mth.clamp(solid / (float) checked, 0.0f, 1.0f);
    }

    private float sampleLeafDensity(ClientLevel world, BlockPos center) {
        int leaves = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = -2; y <= 6; y += 2) {
            for (int x = -8; x <= 8; x += 3) {
                for (int z = -8; z <= 8; z += 3) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (world.isLoaded(cursor) && world.getBlockState(cursor).is(BlockTags.LEAVES)) {
                        leaves++;
                    }
                }
            }
        }
        return Mth.clamp(leaves / 12.0f, 0.0f, 1.0f);
    }

    private float sampleGrassDensity(ClientLevel world, BlockPos center) {
        int plants = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -8; x <= 8; x += 3) {
            for (int z = -8; z <= 8; z += 3) {
                int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING, center.getX() + x, center.getZ() + z);
                for (int dy = 1; dy >= -1; dy--) {
                    cursor.set(center.getX() + x, surfaceY + dy, center.getZ() + z);
                    if (world.isLoaded(cursor) && isWindResponsivePlant(world, cursor)) {
                        plants++;
                        break;
                    }
                }
            }
        }
        return Mth.clamp(plants / 10.0f, 0.0f, 1.0f);
    }

    private static boolean isWindResponsivePlant(ClientLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.is(BlockTags.LEAVES) || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (!state.getCollisionShape(world, pos).isEmpty()) {
            return false;
        }
        return state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES);
    }

    private static SimpleParticleType traceParticleFor(BlockState state) {
        if (state.is(BlockTags.SNOW)) {
            return ModParticles.SNOW_DRIFT/*? neoforge{ */.get()/*?} */;
        }
        if (state.is(BlockTags.SAND)) {
            return ModParticles.SAND_DUST/*? neoforge{ */.get()/*?} */;
        }
        if (state.is(BlockTags.DIRT)) {
            return ModParticles.DIRT_DUST/*? neoforge{ */.get()/*?} */;
        }
        return null;
    }

    private static double horizontalLength(Vec3 vector) {
        return Math.sqrt(vector.x * vector.x + vector.z * vector.z);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        if (edge1 <= edge0) {
            return value >= edge1 ? 1.0 : 0.0;
        }
        double t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double finiteClamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Mth.clamp(value, min, max);
    }
    //?}
}
