package com.aerodynamics4mc.client;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.mixin.client.ParticleAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Applies wind after overridden particle ticks, without linking against optional FBP classes. */
public final class FancyParticleWindCompat {
    private static Field configField;
    private static Field globalField;
    private static Method freezeMethod;
    private static boolean lookupAttempted;
    private static boolean lookupFailed;

    private FancyParticleWindCompat() {}

    public static void afterTick(Particle particle) {
        if (!particle.isAlive() || Minecraft.getInstance().isPaused()) return;
        String name = particle.getClass().getName();
        boolean fancy = name.startsWith("hantonik.fbp.particle.");
        int profile = switch (name) {
            case "hantonik.fbp.particle.FBPFlameParticle" -> 1;
            case "hantonik.fbp.particle.FBPCampfireSmokeParticle" -> 2;
            case "hantonik.fbp.particle.FBPSmokeParticle", "hantonik.fbp.particle.FBPWhiteSmokeParticle" -> 3;
            default -> 0;
        };
        if (profile != 0) {
            if (!ParticleWindConfig.allowsBuiltIn(particle)) return;
        } else {
            if (!ParticleWindConfig.allowsAdditional(particle) || hasBuiltInWind(particle)) return;
        }
        // FBP owns its freeze control; do not add velocity or movement while frozen.
        if (fancy && isFancyFrozen()) return;
        ParticleAccessor accessor = (ParticleAccessor) particle;
        Vec3 velocity = new Vec3(accessor.a4mc$getVelocityX(), accessor.a4mc$getVelocityY(), accessor.a4mc$getVelocityZ());
        Vec3 next = switch (profile) {
            case 1 -> ParticleWindController.applyTorchFlame(accessor.a4mc$getWorld(), accessor.a4mc$getX(),
                    accessor.a4mc$getY(), accessor.a4mc$getZ(), velocity);
            case 2 -> ParticleWindController.applyCampfireSmoke(accessor.a4mc$getWorld(), accessor.a4mc$getX(),
                    accessor.a4mc$getY(), accessor.a4mc$getZ(), velocity);
            case 3 -> ParticleWindController.applyTorchSmoke(accessor.a4mc$getWorld(), accessor.a4mc$getX(),
                    accessor.a4mc$getY(), accessor.a4mc$getZ(), velocity);
            default -> ParticleWindController.applyLeaves(accessor.a4mc$getWorld(), accessor.a4mc$getX(),
                    accessor.a4mc$getY(), accessor.a4mc$getZ(), velocity);
        };
        accessor.a4mc$setVelocityX(next.x);
        accessor.a4mc$setVelocityY(next.y);
        accessor.a4mc$setVelocityZ(next.z);
        // FBP flame's tick deliberately moves with (0, yd, 0), ignoring horizontal velocity.
        if (profile == 1) particle.move(next.x, 0, next.z);
    }

    private static boolean hasBuiltInWind(Particle particle) {
        return particle instanceof net.minecraft.client.particle.FlameParticle
                || particle instanceof net.minecraft.client.particle.CampfireSmokeParticle
                || particle instanceof net.minecraft.client.particle.SmokeParticle
                || particle instanceof net.minecraft.client.particle.WhiteSmokeParticle
                //? >=1.21.11 {
                || particle instanceof net.minecraft.client.particle.FallingLeavesParticle
                //?}
                ;
    }

    private static boolean isFancyFrozen() {
        if (lookupFailed) return true;
        try {
            if (!lookupAttempted) {
                lookupAttempted = true;
                configField = Class.forName("hantonik.fbp.FancyBlockParticles").getField("CONFIG");
                globalField = configField.getType().getField("global");
                freezeMethod = globalField.getType().getMethod("isFreezeEffect");
            }
            return (boolean) freezeMethod.invoke(globalField.get(configField.get(null)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            lookupFailed = true;
            ModTemplate.LOGGER.warn("FBP freeze setting is unavailable; skipping FBP wind", exception);
            return true;
        }
    }
}
