package com.aerodynamics4mc.client;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftVectors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class ClientWindAmbienceManager {
    private static final Identifier BREEZE_LOOP = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "wind_breeze_loop");
    private static final Identifier STRONG_LOOP = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "wind_strong_loop");
    private static final Identifier LEAF_RUSTLE_LOOP = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "leaf_rustle_loop");
    private static final Identifier GRASS_RUSTLE_LOOP = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "grass_rustle_loop");
    private static final Identifier GROUND_WIND_LOOP = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "ground_wind_loop");
    private static final Identifier GUST_WHOOSH = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "wind_gust_whoosh");

    private static final int WIND_SAMPLE_INTERVAL_TICKS = 10;
    private static final int ENVIRONMENT_SAMPLE_INTERVAL_TICKS = 20;
    private static final int GUST_MIN_COOLDOWN_TICKS = 45;
    private static final int GUST_MAX_COOLDOWN_TICKS = 120;
    private static final float PLAYBACK_VOLUME_SCALE = 2.0f;

    private final RandomSource random = RandomSource.create();
    private DynamicLoopSound breezeLoop;
    private DynamicLoopSound strongLoop;
    private DynamicLoopSound leafLoop;
    private DynamicLoopSound grassLoop;
    private DynamicLoopSound groundLoop;
    private int tickCounter;
    private int gustCooldownTicks;
    private int windSampleTicks;
    private int environmentSampleTicks;
    private float targetBreezeVolume;
    private float targetStrongVolume;
    private float targetLeafVolume;
    private float targetGrassVolume;
    private float targetGroundVolume;
    private float targetBreezePitch = 1.0f;
    private float targetStrongPitch = 1.0f;
    private float targetLeafPitch = 1.0f;
    private float targetGrassPitch = 1.0f;
    private float targetGroundPitch = 1.0f;
    private float breezeVolume;
    private float strongVolume;
    private float leafVolume;
    private float grassVolume;
    private float groundVolume;
    private float breezePitch = 1.0f;
    private float strongPitch = 1.0f;
    private float leafPitch = 1.0f;
    private float grassPitch = 1.0f;
    private float groundPitch = 1.0f;
    private float exposure = 0.35f;
    private float leafDensity;
    private float grassDensity;
    private float softGroundDensity;
    private float stormVisualIntensity;
    private float lastGustSpeed;
    private Vec3 lastEffectiveWind = Vec3.ZERO;

    public void onClientTick(Minecraft minecraft) {
        tickCounter++;
        if (gustCooldownTicks > 0) {
            gustCooldownTicks--;
        }
        if (minecraft == null || minecraft.level == null || minecraft.player == null || minecraft.isPaused()) {
            fadeOut();
            return;
        }

        ensureLoops(minecraft);
        LocalPlayer player = minecraft.player;
        ClientLevel world = minecraft.level;
        if (environmentSampleTicks-- <= 0) {
            environmentSampleTicks = ENVIRONMENT_SAMPLE_INTERVAL_TICKS;
            sampleEnvironment(world, player.blockPosition());
        }
        if (windSampleTicks-- <= 0) {
            windSampleTicks = WIND_SAMPLE_INTERVAL_TICKS;
            sampleWind(minecraft, world, player);
        }
        updateLoopState();
    }

    public void clear() {
        targetBreezeVolume = 0.0f;
        targetStrongVolume = 0.0f;
        targetLeafVolume = 0.0f;
        targetGrassVolume = 0.0f;
        targetGroundVolume = 0.0f;
        breezeVolume = 0.0f;
        strongVolume = 0.0f;
        leafVolume = 0.0f;
        grassVolume = 0.0f;
        groundVolume = 0.0f;
        leafDensity = 0.0f;
        grassDensity = 0.0f;
        softGroundDensity = 0.0f;
        stormVisualIntensity = 0.0f;
        exposure = 0.35f;
        lastGustSpeed = 0.0f;
        lastEffectiveWind = Vec3.ZERO;
        windSampleTicks = 0;
        environmentSampleTicks = 0;
        gustCooldownTicks = 0;
    }

    public void close() {
        stopLoop(breezeLoop);
        stopLoop(strongLoop);
        stopLoop(leafLoop);
        stopLoop(grassLoop);
        stopLoop(groundLoop);
        breezeLoop = null;
        strongLoop = null;
        leafLoop = null;
        grassLoop = null;
        groundLoop = null;
        clear();
    }

    private void sampleWind(Minecraft minecraft, ClientLevel world, LocalPlayer player) {
        AeroWindSample sample = AeroClientMod.sampleFlow(
                world,
                player.position().add(0.0, 1.2, 0.0),
                SamplePolicy.SERVER_COARSE_ONLY
        );
        if (!sample.hasFlow()) {
            Vec3 cinematicWind = ClientCinematicWind.stormWind(world, player.position(), stormVisualIntensity, 1.35, 6.40);
            float cinematicSpeed = horizontalLength(cinematicWind);
            if (cinematicSpeed <= 0.10f) {
                clearWindTargets();
                lastGustSpeed = 0.0f;
                return;
            }
            float turbulence = Mth.clamp(0.35f + stormVisualIntensity * 0.50f, 0.0f, 1.0f);
            Vec3 gust = cinematicWind.scale(0.28 + stormVisualIntensity * 0.24 + random.nextFloat() * 0.10);
            applyWindTargets(minecraft, player, cinematicWind, gust, cinematicSpeed * 0.78f, turbulence);
            return;
        }

        Vec3 effectiveWind = AeroMinecraftVectors.effectiveVelocity(sample);
        Vec3 gust = AeroMinecraftVectors.gustVelocity(sample);
        float meanSpeed = sample.horizontalSpeedMetersPerSecond();
        float turbulence = Mth.clamp(sample.turbulenceIntensity() / 3.0f, 0.0f, 1.0f);
        applyWindTargets(minecraft, player, effectiveWind, gust, meanSpeed, turbulence);
    }

    private void clearWindTargets() {
        targetBreezeVolume = 0.0f;
        targetStrongVolume = 0.0f;
        targetLeafVolume = 0.0f;
        targetGrassVolume = 0.0f;
        targetGroundVolume = 0.0f;
    }

    private void applyWindTargets(
            Minecraft minecraft,
            LocalPlayer player,
            Vec3 effectiveWind,
            Vec3 gust,
            float meanSpeed,
            float turbulence
    ) {
        float effectiveSpeed = horizontalLength(effectiveWind);
        float gustSpeed = horizontalLength(gust);
        float exposureWeight = Mth.clamp(exposure, 0.0f, 1.0f);
        float stormBoost = stormVisualIntensity * exposureWeight;

        targetBreezeVolume = smoothstep(0.45f, 4.50f, effectiveSpeed) * (0.06f + 0.16f * exposureWeight + stormBoost * 0.035f);
        targetStrongVolume = smoothstep(2.40f, 10.0f, effectiveSpeed) * (0.10f + 0.34f * exposureWeight + stormBoost * 0.18f) * (0.65f + turbulence * 0.35f);
        targetLeafVolume = smoothstep(1.20f, 7.0f, effectiveSpeed) * leafDensity * (0.06f + 0.24f * exposureWeight);
        targetGrassVolume = smoothstep(0.85f, 6.0f, effectiveSpeed) * grassDensity * (0.04f + 0.26f * exposureWeight) * (0.70f + turbulence * 0.30f);
        targetGroundVolume = smoothstep(2.00f, 9.50f, effectiveSpeed) * softGroundDensity * (0.025f + 0.13f * exposureWeight + stormBoost * 0.05f) * (0.60f + turbulence * 0.40f);

        targetBreezePitch = Mth.clamp(0.82f + meanSpeed * 0.035f + turbulence * 0.05f, 0.78f, 1.18f);
        targetStrongPitch = Mth.clamp(0.76f + effectiveSpeed * 0.030f + turbulence * 0.09f + stormVisualIntensity * 0.04f, 0.78f, 1.24f);
        targetLeafPitch = Mth.clamp(0.88f + effectiveSpeed * 0.018f + random.nextFloat() * 0.03f, 0.82f, 1.16f);
        targetGrassPitch = Mth.clamp(0.92f + effectiveSpeed * 0.022f + random.nextFloat() * 0.05f, 0.86f, 1.24f);
        targetGroundPitch = Mth.clamp(0.78f + effectiveSpeed * 0.016f + turbulence * 0.06f, 0.74f, 1.16f);

        maybePlayGustWhoosh(minecraft, player, effectiveWind, effectiveSpeed, gustSpeed, turbulence, exposureWeight);
        lastGustSpeed = gustSpeed;
        lastEffectiveWind = effectiveWind;
    }

    private void maybePlayGustWhoosh(
            Minecraft minecraft,
            LocalPlayer player,
            Vec3 effectiveWind,
            float effectiveSpeed,
            float gustSpeed,
            float turbulence,
            float exposureWeight
    ) {
        float gustRise = gustSpeed - lastGustSpeed;
        float gustThreshold = Mth.lerp(stormVisualIntensity, 1.55f, 0.95f);
        float windThreshold = Mth.lerp(stormVisualIntensity, 2.0f, 1.20f);
        if (gustCooldownTicks > 0
                || gustSpeed < gustThreshold
                || effectiveSpeed < windThreshold
                || exposureWeight < 0.25f
                || gustRise < 0.20f) {
            return;
        }
        float triggerChance = Mth.clamp((gustSpeed - 1.45f) * 0.16f + turbulence * 0.20f + stormVisualIntensity * 0.16f, 0.08f, 0.65f);
        if (random.nextFloat() > triggerChance) {
            return;
        }

        float volume = Mth.clamp((gustSpeed - 1.10f) / 4.5f, 0.12f, 0.72f) * exposureWeight;
        float pitch = Mth.clamp(0.82f + effectiveSpeed * 0.025f + random.nextFloat() * 0.10f, 0.80f, 1.24f);
        Vec3 source = gustSourcePosition(player, effectiveWind);
        minecraft.getSoundManager().play(new SimpleSoundInstance(
                GUST_WHOOSH,
                SoundSource.AMBIENT,
                scaledPlaybackVolume(volume),
                pitch,
                SoundInstance.createUnseededRandom(),
                false,
                0,
                SoundInstance.Attenuation.LINEAR,
                source.x,
                source.y,
                source.z,
                false
        ));
        AeroClientMod.getInstance().getWindPresenceManager().triggerGustPulse(effectiveWind, gustSpeed);
        gustCooldownTicks = GUST_MIN_COOLDOWN_TICKS + random.nextInt(GUST_MAX_COOLDOWN_TICKS - GUST_MIN_COOLDOWN_TICKS + 1);
    }

    private Vec3 gustSourcePosition(LocalPlayer player, Vec3 effectiveWind) {
        float speed = horizontalLength(effectiveWind);
        if (speed < 0.10f) {
            effectiveWind = lastEffectiveWind;
            speed = horizontalLength(effectiveWind);
        }
        if (speed < 0.10f) {
            return player.position();
        }
        double inv = 1.0 / speed;
        double upwindX = -effectiveWind.x * inv;
        double upwindZ = -effectiveWind.z * inv;
        return player.position().add(upwindX * 7.0, 1.6, upwindZ * 7.0);
    }

    private void sampleEnvironment(ClientLevel world, BlockPos playerPos) {
        if (world == null || playerPos == null) {
            exposure = 0.35f;
            leafDensity = 0.0f;
            grassDensity = 0.0f;
            softGroundDensity = 0.0f;
            stormVisualIntensity = 0.0f;
            return;
        }
        BlockPos head = playerPos.above();
        float sky = world.canSeeSkyFromBelowWater(head)
                ? 1.0f
                : world.getBrightness(LightLayer.SKY, head) / 15.0f;
        float shelter = sampleShelter(world, head);
        exposure = Mth.clamp(0.18f + sky * 0.82f - shelter * 0.58f, 0.05f, 1.0f);
        leafDensity = sampleLeafDensity(world, head);
        grassDensity = sampleGrassDensity(world, playerPos);
        softGroundDensity = sampleSoftGroundDensity(world, playerPos);
        stormVisualIntensity = AeroClientMod.getInstance().getLocalWeatherData().stormVisualIntensity(
                world,
                world.getRainLevel(1.0f),
                world.getThunderLevel(1.0f)
        );
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
        if (checked <= 0) {
            return 0.0f;
        }
        return Mth.clamp(solid / (float) checked, 0.0f, 1.0f);
    }

    private float sampleLeafDensity(ClientLevel world, BlockPos center) {
        int leaves = 0;
        int checked = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = -2; y <= 5; y++) {
            for (int x = -6; x <= 6; x += 2) {
                for (int z = -6; z <= 6; z += 2) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!world.isLoaded(cursor)) {
                        continue;
                    }
                    checked++;
                    if (world.getBlockState(cursor).is(BlockTags.LEAVES)) {
                        leaves++;
                    }
                }
            }
        }
        if (checked <= 0) {
            return 0.0f;
        }
        return Mth.clamp(leaves / 18.0f, 0.0f, 1.0f);
    }

    private float sampleGrassDensity(ClientLevel world, BlockPos center) {
        int responsivePlants = 0;
        int checked = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= 2; y++) {
            for (int x = -7; x <= 7; x += 2) {
                for (int z = -7; z <= 7; z += 2) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!world.isLoaded(cursor)) {
                        continue;
                    }
                    checked++;
                    if (isWindResponsivePlant(world, cursor)) {
                        responsivePlants++;
                    }
                }
            }
        }
        if (checked <= 0) {
            return 0.0f;
        }
        return Mth.clamp(responsivePlants / 20.0f, 0.0f, 1.0f);
    }

    private float sampleSoftGroundDensity(ClientLevel world, BlockPos center) {
        int softGround = 0;
        int checked = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -7; x <= 7; x += 3) {
            for (int z = -7; z <= 7; z += 3) {
                for (int y = 1; y >= -3; y--) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!world.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(cursor);
                    if (state.isAir() || state.getCollisionShape(world, cursor).isEmpty()) {
                        continue;
                    }
                    checked++;
                    if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)) {
                        softGround++;
                    }
                    break;
                }
            }
        }
        if (checked <= 0) {
            return 0.0f;
        }
        return Mth.clamp(softGround / (float) checked, 0.0f, 1.0f);
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

    private void ensureLoops(Minecraft minecraft) {
        if (breezeLoop == null || !minecraft.getSoundManager().isActive(breezeLoop)) {
            breezeLoop = new DynamicLoopSound(BREEZE_LOOP, () -> breezeVolume, () -> breezePitch);
            minecraft.getSoundManager().queueTickingSound(breezeLoop);
        }
        if (strongLoop == null || !minecraft.getSoundManager().isActive(strongLoop)) {
            strongLoop = new DynamicLoopSound(STRONG_LOOP, () -> strongVolume, () -> strongPitch);
            minecraft.getSoundManager().queueTickingSound(strongLoop);
        }
        if (leafLoop == null || !minecraft.getSoundManager().isActive(leafLoop)) {
            leafLoop = new DynamicLoopSound(LEAF_RUSTLE_LOOP, () -> leafVolume, () -> leafPitch);
            minecraft.getSoundManager().queueTickingSound(leafLoop);
        }
        if (grassLoop == null || !minecraft.getSoundManager().isActive(grassLoop)) {
            grassLoop = new DynamicLoopSound(GRASS_RUSTLE_LOOP, () -> grassVolume, () -> grassPitch);
            minecraft.getSoundManager().queueTickingSound(grassLoop);
        }
        if (groundLoop == null || !minecraft.getSoundManager().isActive(groundLoop)) {
            groundLoop = new DynamicLoopSound(GROUND_WIND_LOOP, () -> groundVolume, () -> groundPitch);
            minecraft.getSoundManager().queueTickingSound(groundLoop);
        }
    }

    private void updateLoopState() {
        breezeVolume = approach(breezeVolume, targetBreezeVolume, 0.035f);
        strongVolume = approach(strongVolume, targetStrongVolume, 0.030f);
        leafVolume = approach(leafVolume, targetLeafVolume, 0.040f);
        grassVolume = approach(grassVolume, targetGrassVolume, 0.055f);
        groundVolume = approach(groundVolume, targetGroundVolume, 0.025f);
        breezePitch = approach(breezePitch, targetBreezePitch, 0.025f);
        strongPitch = approach(strongPitch, targetStrongPitch, 0.025f);
        leafPitch = approach(leafPitch, targetLeafPitch, 0.035f);
        grassPitch = approach(grassPitch, targetGrassPitch, 0.045f);
        groundPitch = approach(groundPitch, targetGroundPitch, 0.020f);
    }

    private void fadeOut() {
        targetBreezeVolume = 0.0f;
        targetStrongVolume = 0.0f;
        targetLeafVolume = 0.0f;
        targetGrassVolume = 0.0f;
        targetGroundVolume = 0.0f;
        updateLoopState();
    }

    private static void stopLoop(DynamicLoopSound loop) {
        if (loop != null) {
            loop.stopLoop();
        }
    }

    private static float approach(float current, float target, float factor) {
        return current + (target - current) * Mth.clamp(factor, 0.0f, 1.0f);
    }

    private static float horizontalLength(Vec3 vector) {
        return (float) Math.sqrt(vector.x * vector.x + vector.z * vector.z);
    }

    private static float scaledPlaybackVolume(float volume) {
        return Mth.clamp(volume * PLAYBACK_VOLUME_SCALE, 0.0f, 1.0f);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        if (edge1 <= edge0) {
            return value >= edge1 ? 1.0f : 0.0f;
        }
        float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private interface FloatSupplier {
        float get();
    }

    private static final class DynamicLoopSound extends AbstractSoundInstance implements TickableSoundInstance {
        private final FloatSupplier volumeSupplier;
        private final FloatSupplier pitchSupplier;
        private boolean stopped;

        private DynamicLoopSound(Identifier id, FloatSupplier volumeSupplier, FloatSupplier pitchSupplier) {
            super(id, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.volumeSupplier = volumeSupplier;
            this.pitchSupplier = pitchSupplier;
            this.looping = true;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.0f;
            this.pitch = 1.0f;
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void tick() {
            this.volume = scaledPlaybackVolume(volumeSupplier.get());
            this.pitch = Mth.clamp(pitchSupplier.get(), 0.50f, 2.0f);
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public boolean canPlaySound() {
            return !stopped;
        }

        private void stopLoop() {
            stopped = true;
            volume = 0.0f;
        }
    }
}
