package com.aerodynamics4mc.api.client.minecraft;

import com.aerodynamics4mc.api.A4mcBlockPos;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftVectors;
import com.aerodynamics4mc.client.AeroClientMod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class AeroMinecraftClientWindApi {
    private AeroMinecraftClientWindApi() {
    }

    public static A4mcWorldRef worldRef(ClientLevel world) {
        return AeroClientMod.worldRef(world);
    }

    public static AeroWindSample sample(ClientLevel world, A4mcVec3 position) {
        return AeroClientMod.sampleFlow(world, toMinecraftOrNull(position));
    }

    public static AeroWindSample sample(ClientLevel world, A4mcVec3 position, SamplePolicy policy) {
        return AeroClientMod.sampleFlow(world, toMinecraftOrNull(position), policy);
    }

    public static AeroWindSample sample(ClientLevel world, A4mcBlockPos position) {
        return sample(world, center(position));
    }

    public static AeroWindSample sample(ClientLevel world, A4mcBlockPos position, SamplePolicy policy) {
        return sample(world, center(position), policy);
    }

    public static AeroWindSample sample(ClientLevel world, Vec3 position) {
        return AeroClientMod.sampleFlow(world, position);
    }

    public static AeroWindSample sample(ClientLevel world, Vec3 position, SamplePolicy policy) {
        return AeroClientMod.sampleFlow(world, position, policy);
    }

    public static AeroWindSample sample(ClientLevel world, BlockPos position) {
        return sample(world, center(position));
    }

    public static AeroWindSample sample(ClientLevel world, BlockPos position, SamplePolicy policy) {
        return sample(world, center(position), policy);
    }

    public static Vec3 sampleMeanVelocity(ClientLevel world, Vec3 position) {
        return AeroMinecraftVectors.meanVelocity(sample(world, position));
    }

    public static Vec3 sampleMeanVelocity(ClientLevel world, Vec3 position, SamplePolicy policy) {
        return AeroMinecraftVectors.meanVelocity(sample(world, position, policy));
    }

    public static A4mcVec3 sampleMeanVelocity(ClientLevel world, A4mcVec3 position) {
        return sample(world, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleMeanVelocity(ClientLevel world, A4mcBlockPos position) {
        return sample(world, position).meanVelocityVector();
    }

    public static Vec3 sampleEffectiveVelocity(ClientLevel world, Vec3 position) {
        return AeroMinecraftVectors.effectiveVelocity(sample(world, position));
    }

    public static Vec3 sampleEffectiveVelocity(ClientLevel world, Vec3 position, SamplePolicy policy) {
        return AeroMinecraftVectors.effectiveVelocity(sample(world, position, policy));
    }

    public static A4mcVec3 sampleEffectiveVelocity(ClientLevel world, A4mcVec3 position) {
        return sample(world, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleEffectiveVelocity(ClientLevel world, A4mcBlockPos position) {
        return sample(world, position).effectiveVelocityVector();
    }

    private static Vec3 toMinecraftOrNull(A4mcVec3 position) {
        if (position == null) {
            return null;
        }
        return AeroMinecraftVectors.toMinecraft(position);
    }

    private static A4mcVec3 center(A4mcBlockPos position) {
        if (position == null) {
            return A4mcVec3.ZERO;
        }
        return position.center();
    }

    private static Vec3 center(BlockPos position) {
        if (position == null) {
            return Vec3.ZERO;
        }
        return new Vec3(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
    }
}
