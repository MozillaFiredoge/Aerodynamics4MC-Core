package com.aerodynamics4mc.client;

import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.client.minecraft.AeroMinecraftClientWindApi;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftVectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class WeatherWindController {
    private static final float MAX_WEATHER_WIND_METERS_PER_SECOND = 18.0f;
    private static final float WIND_SMOOTHING = 0.16f;
    private static final float RAIN_DRIFT_PER_MPS_PER_VERTICAL_BLOCK = 0.010f;
    private static final float SNOW_DRIFT_PER_MPS_PER_VERTICAL_BLOCK = 0.022f;
    private static final float MAX_RAIN_VERTEX_DRIFT_BLOCKS = 3.0f;
    private static final float MAX_SNOW_VERTEX_DRIFT_BLOCKS = 6.0f;
    private static final float MAX_WEATHER_VERTICAL_REFERENCE_BLOCKS = 24.0f;

    private static ClientLevel lastLevel;
    private static long lastSampleTick = Long.MIN_VALUE;
    private static float smoothedWindX;
    private static float smoothedWindZ;
    private static boolean renderingSnow;
    private static float renderIntensity;

    private WeatherWindController() {}

    public static void beginRender(Vec3 cameraPosition, boolean snow, float intensity) {
        renderingSnow = snow;
        renderIntensity = Mth.clamp(intensity, 0.0f, 1.0f);

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel world = minecraft == null ? null : minecraft.level;
        if (world == null || cameraPosition == null || renderIntensity <= 0.0f) {
            reset(world);
            return;
        }

        long gameTime = world.getGameTime();
        if (world != lastLevel) {
            lastLevel = world;
            lastSampleTick = Long.MIN_VALUE;
            smoothedWindX = 0.0f;
            smoothedWindZ = 0.0f;
        }
        if (gameTime == lastSampleTick) {
            return;
        }
        lastSampleTick = gameTime;

        AeroWindSample sample = AeroMinecraftClientWindApi.sample(world, cameraPosition, SamplePolicy.SERVER_COARSE_ONLY);
        Vec3 wind = sample.hasFlow() ? AeroMinecraftVectors.effectiveVelocity(sample) : Vec3.ZERO;
        float targetX = finiteClamp((float) wind.x, -MAX_WEATHER_WIND_METERS_PER_SECOND, MAX_WEATHER_WIND_METERS_PER_SECOND);
        float targetZ = finiteClamp((float) wind.z, -MAX_WEATHER_WIND_METERS_PER_SECOND, MAX_WEATHER_WIND_METERS_PER_SECOND);
        smoothedWindX = Mth.lerp(WIND_SMOOTHING, smoothedWindX, targetX);
        smoothedWindZ = Mth.lerp(WIND_SMOOTHING, smoothedWindZ, targetZ);
    }

    public static float driftedX(float x, float cameraRelativeY) {
        return x + drift(smoothedWindX, cameraRelativeY);
    }

    public static float driftedZ(float z, float cameraRelativeY) {
        return z + drift(smoothedWindZ, cameraRelativeY);
    }

    private static float drift(float windComponent, float cameraRelativeY) {
        if (renderIntensity <= 0.0f || Math.abs(windComponent) < 0.01f) {
            return 0.0f;
        }
        float scale = renderingSnow ? SNOW_DRIFT_PER_MPS_PER_VERTICAL_BLOCK : RAIN_DRIFT_PER_MPS_PER_VERTICAL_BLOCK;
        float maxDrift = renderingSnow ? MAX_SNOW_VERTEX_DRIFT_BLOCKS : MAX_RAIN_VERTEX_DRIFT_BLOCKS;
        float vertical = Mth.clamp(cameraRelativeY, -MAX_WEATHER_VERTICAL_REFERENCE_BLOCKS, MAX_WEATHER_VERTICAL_REFERENCE_BLOCKS);
        return Mth.clamp(-vertical * windComponent * scale * renderIntensity, -maxDrift, maxDrift);
    }

    private static float finiteClamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return 0.0f;
        }
        return Mth.clamp(value, min, max);
    }

    private static void reset(ClientLevel world) {
        lastLevel = world;
        lastSampleTick = Long.MIN_VALUE;
        smoothedWindX = 0.0f;
        smoothedWindZ = 0.0f;
    }
}
