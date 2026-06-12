package com.aerodynamics4mc.api.minecraft;

import com.aerodynamics4mc.api.A4mcBlockPos;
import com.aerodynamics4mc.api.A4mcPlayerRef;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.GameplayWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.runtime.AeroServerRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class AeroMinecraftWindApi {
    private AeroMinecraftWindApi() {
    }

    public static A4mcWorldRef worldRef(ServerLevel world) {
        return AeroServerRuntime.worldRef(world);
    }

    public static A4mcPlayerRef playerRef(ServerPlayer player) {
        return AeroServerRuntime.playerRef(player);
    }

    public static AeroWindSample sample(ServerLevel world, A4mcVec3 position) {
        return sample(world, position, SamplePolicy.SERVER_COARSE_ONLY);
    }

    public static AeroWindSample sample(ServerLevel world, A4mcVec3 position, SamplePolicy policy) {
        return AeroServerRuntime.sampleFlow(world, toMinecraftOrNull(position), policy);
    }

    public static AeroWindSample sample(ServerLevel world, A4mcBlockPos position) {
        return sample(world, position, SamplePolicy.SERVER_COARSE_ONLY);
    }

    public static AeroWindSample sample(ServerLevel world, A4mcBlockPos position, SamplePolicy policy) {
        return AeroServerRuntime.sampleFlow(world, toMinecraftOrNull(position), policy);
    }

    public static AeroWindSample sample(ServerPlayer player, A4mcVec3 position) {
        return sample(player, position, SamplePolicy.SERVER_COARSE_ONLY);
    }

    public static AeroWindSample sample(ServerPlayer player, A4mcVec3 position, SamplePolicy policy) {
        return AeroServerRuntime.sampleFlow(player, toMinecraftOrNull(position), policy);
    }

    public static AeroWindSample sample(ServerPlayer player, A4mcBlockPos position) {
        return sample(player, position, SamplePolicy.SERVER_COARSE_ONLY);
    }

    public static AeroWindSample sample(ServerPlayer player, A4mcBlockPos position, SamplePolicy policy) {
        return AeroServerRuntime.sampleFlow(player, toMinecraftOrNull(position), policy);
    }

    public static AeroWindSample sample(ServerLevel world, Vec3 position) {
        return AeroServerRuntime.sampleFlow(world, position);
    }

    public static AeroWindSample sample(ServerLevel world, Vec3 position, SamplePolicy policy) {
        return AeroServerRuntime.sampleFlow(world, position, policy);
    }

    public static AeroWindSample sample(ServerLevel world, BlockPos position) {
        return AeroServerRuntime.sampleFlow(world, position);
    }

    public static AeroWindSample sample(ServerLevel world, BlockPos position, SamplePolicy policy) {
        return AeroServerRuntime.sampleFlow(world, position, policy);
    }

    public static AeroWindSample sample(ServerPlayer player, Vec3 position) {
        return AeroServerRuntime.sampleFlow(player, position);
    }

    public static AeroWindSample sample(ServerPlayer player, Vec3 position, SamplePolicy policy) {
        return AeroServerRuntime.sampleFlow(player, position, policy);
    }

    public static AeroWindSample sample(ServerPlayer player, BlockPos position) {
        return AeroServerRuntime.sampleFlow(player, position);
    }

    public static AeroWindSample sample(ServerPlayer player, BlockPos position, SamplePolicy policy) {
        return AeroServerRuntime.sampleFlow(player, position, policy);
    }

    public static GameplayWindSample sampleGameplay(ServerLevel world, A4mcVec3 position) {
        return sampleGameplay(world, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
    }

    public static GameplayWindSample sampleGameplay(ServerLevel world, A4mcVec3 position, SamplePolicy policy) {
        return AeroServerRuntime.sampleGameplay(world, toMinecraftOrNull(position), policy);
    }

    public static GameplayWindSample sampleGameplay(ServerLevel world, A4mcBlockPos position) {
        return sampleGameplay(world, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
    }

    public static GameplayWindSample sampleGameplay(ServerLevel world, A4mcBlockPos position, SamplePolicy policy) {
        return AeroServerRuntime.sampleGameplay(world, toMinecraftOrNull(position), policy);
    }

    public static GameplayWindSample sampleGameplay(ServerPlayer player, A4mcVec3 position) {
        return sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
    }

    public static GameplayWindSample sampleGameplay(ServerPlayer player, A4mcVec3 position, SamplePolicy policy) {
        return AeroServerRuntime.sampleGameplay(player, toMinecraftOrNull(position), policy);
    }

    public static GameplayWindSample sampleGameplay(ServerPlayer player, A4mcBlockPos position) {
        return sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
    }

    public static GameplayWindSample sampleGameplay(ServerPlayer player, A4mcBlockPos position, SamplePolicy policy) {
        return AeroServerRuntime.sampleGameplay(player, toMinecraftOrNull(position), policy);
    }

    public static GameplayWindSample sampleGameplay(ServerLevel world, Vec3 position) {
        return AeroServerRuntime.sampleGameplay(world, position);
    }

    public static GameplayWindSample sampleGameplay(ServerLevel world, Vec3 position, SamplePolicy policy) {
        return AeroServerRuntime.sampleGameplay(world, position, policy);
    }

    public static GameplayWindSample sampleGameplay(ServerLevel world, BlockPos position) {
        return AeroServerRuntime.sampleGameplay(world, position);
    }

    public static GameplayWindSample sampleGameplay(ServerLevel world, BlockPos position, SamplePolicy policy) {
        return AeroServerRuntime.sampleGameplay(world, position, policy);
    }

    public static GameplayWindSample sampleGameplay(ServerPlayer player, Vec3 position) {
        return AeroServerRuntime.sampleGameplay(player, position);
    }

    public static GameplayWindSample sampleGameplay(ServerPlayer player, Vec3 position, SamplePolicy policy) {
        return AeroServerRuntime.sampleGameplay(player, position, policy);
    }

    public static GameplayWindSample sampleGameplay(ServerPlayer player, BlockPos position) {
        return AeroServerRuntime.sampleGameplay(player, position);
    }

    public static GameplayWindSample sampleGameplay(ServerPlayer player, BlockPos position, SamplePolicy policy) {
        return AeroServerRuntime.sampleGameplay(player, position, policy);
    }

    public static Vec3 sampleMeanVelocity(ServerLevel world, Vec3 position) {
        return AeroMinecraftVectors.meanVelocity(sample(world, position));
    }

    public static A4mcVec3 sampleMeanVelocity(ServerLevel world, A4mcVec3 position) {
        return sample(world, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleMeanVelocity(ServerLevel world, A4mcBlockPos position) {
        return sample(world, position).meanVelocityVector();
    }

    public static Vec3 sampleEffectiveVelocity(ServerLevel world, Vec3 position) {
        return AeroMinecraftVectors.effectiveVelocity(sample(world, position));
    }

    public static A4mcVec3 sampleEffectiveVelocity(ServerLevel world, A4mcVec3 position) {
        return sample(world, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleEffectiveVelocity(ServerLevel world, A4mcBlockPos position) {
        return sample(world, position).effectiveVelocityVector();
    }

    public static Vec3 sampleMeanVelocity(ServerPlayer player, Vec3 position) {
        return AeroMinecraftVectors.meanVelocity(sample(player, position));
    }

    public static A4mcVec3 sampleMeanVelocity(ServerPlayer player, A4mcVec3 position) {
        return sample(player, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleMeanVelocity(ServerPlayer player, A4mcBlockPos position) {
        return sample(player, position).meanVelocityVector();
    }

    public static Vec3 sampleEffectiveVelocity(ServerPlayer player, Vec3 position) {
        return AeroMinecraftVectors.effectiveVelocity(sample(player, position));
    }

    public static A4mcVec3 sampleEffectiveVelocity(ServerPlayer player, A4mcVec3 position) {
        return sample(player, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleEffectiveVelocity(ServerPlayer player, A4mcBlockPos position) {
        return sample(player, position).effectiveVelocityVector();
    }

    public static Vec3 sampleGameplayMeanVelocity(ServerLevel world, Vec3 position) {
        return AeroMinecraftVectors.meanVelocity(sampleGameplay(world, position));
    }

    public static A4mcVec3 sampleGameplayMeanVelocity(ServerLevel world, A4mcVec3 position) {
        return sampleGameplay(world, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleGameplayMeanVelocity(ServerLevel world, A4mcBlockPos position) {
        return sampleGameplay(world, position).meanVelocityVector();
    }

    public static Vec3 sampleGameplayEffectiveVelocity(ServerLevel world, Vec3 position) {
        return AeroMinecraftVectors.effectiveVelocity(sampleGameplay(world, position));
    }

    public static A4mcVec3 sampleGameplayEffectiveVelocity(ServerLevel world, A4mcVec3 position) {
        return sampleGameplay(world, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleGameplayEffectiveVelocity(ServerLevel world, A4mcBlockPos position) {
        return sampleGameplay(world, position).effectiveVelocityVector();
    }

    public static Vec3 sampleGameplayMeanVelocity(ServerPlayer player, Vec3 position) {
        return AeroMinecraftVectors.meanVelocity(sampleGameplay(player, position));
    }

    public static A4mcVec3 sampleGameplayMeanVelocity(ServerPlayer player, A4mcVec3 position) {
        return sampleGameplay(player, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleGameplayMeanVelocity(ServerPlayer player, A4mcBlockPos position) {
        return sampleGameplay(player, position).meanVelocityVector();
    }

    public static Vec3 sampleGameplayEffectiveVelocity(ServerPlayer player, Vec3 position) {
        return AeroMinecraftVectors.effectiveVelocity(sampleGameplay(player, position));
    }

    public static A4mcVec3 sampleGameplayEffectiveVelocity(ServerPlayer player, A4mcVec3 position) {
        return sampleGameplay(player, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleGameplayEffectiveVelocity(ServerPlayer player, A4mcBlockPos position) {
        return sampleGameplay(player, position).effectiveVelocityVector();
    }

    private static Vec3 toMinecraftOrNull(A4mcVec3 position) {
        if (position == null) {
            return null;
        }
        return AeroMinecraftVectors.toMinecraft(position);
    }

    private static BlockPos toMinecraftOrNull(A4mcBlockPos position) {
        if (position == null) {
            return null;
        }
        return AeroMinecraftVectors.toMinecraft(position);
    }
}
