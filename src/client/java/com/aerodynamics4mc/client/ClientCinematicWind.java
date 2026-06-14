package com.aerodynamics4mc.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class ClientCinematicWind {
    private static final float MIN_ACTIVE_INTENSITY = 0.05f;

    private ClientCinematicWind() {
    }

    public static Vec3 stormWind(ClientLevel world, Vec3 position, float intensity, double minSpeed, double maxSpeed) {
        float strength = Mth.clamp(Float.isFinite(intensity) ? intensity : 0.0f, 0.0f, 1.0f);
        if (world == null || strength <= MIN_ACTIVE_INTENSITY || maxSpeed <= 0.0) {
            return Vec3.ZERO;
        }

        Vec3 basePosition = position == null ? Vec3.ZERO : position;
        long time = world.getGameTime();
        double spatialPhase = Mth.floor(basePosition.x) * 0.019 + Mth.floor(basePosition.z) * 0.013;
        double angle = 0.85 + spatialPhase + time * 0.006 + Math.sin(time * 0.017 + spatialPhase * 0.5) * 0.28;
        double pulse = 0.72 + 0.28 * Math.sin(time * 0.045 + spatialPhase);
        double speed = Math.max(0.0, minSpeed) + Math.max(0.0, maxSpeed - minSpeed) * strength * pulse;
        return new Vec3(Math.cos(angle) * speed, 0.0, Math.sin(angle) * speed);
    }
}
