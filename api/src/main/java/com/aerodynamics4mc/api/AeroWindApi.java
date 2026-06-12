package com.aerodynamics4mc.api;

import java.util.Objects;

public final class AeroWindApi {
    private static final AeroWindRuntimeProvider MISSING_PROVIDER = new MissingProvider();
    private static volatile AeroWindRuntimeProvider provider = MISSING_PROVIDER;

    private AeroWindApi() {
    }

    public static void registerProvider(AeroWindRuntimeProvider runtimeProvider) {
        provider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
    }

    public static boolean isAvailable() {
        return provider != MISSING_PROVIDER;
    }

    public static AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position) {
        return provider.sample(world, position, null);
    }

    public static AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
        return provider.sample(world, position, policy);
    }

    public static AeroWindSample sample(A4mcWorldRef world, A4mcBlockPos position) {
        return provider.sample(world, position, null);
    }

    public static AeroWindSample sample(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy) {
        return provider.sample(world, position, policy);
    }

    public static AeroWindSample sample(A4mcPlayerRef player, A4mcVec3 position) {
        return provider.sample(player, position, null);
    }

    public static AeroWindSample sample(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy) {
        return provider.sample(player, position, policy);
    }

    public static AeroWindSample sample(A4mcPlayerRef player, A4mcBlockPos position) {
        return provider.sample(player, position, null);
    }

    public static AeroWindSample sample(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy) {
        return provider.sample(player, position, policy);
    }

    public static GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcVec3 position) {
        return provider.sampleGameplay(world, position, null);
    }

    public static GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
        return provider.sampleGameplay(world, position, policy);
    }

    public static GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcBlockPos position) {
        return provider.sampleGameplay(world, position, null);
    }

    public static GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy) {
        return provider.sampleGameplay(world, position, policy);
    }

    public static GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcVec3 position) {
        return provider.sampleGameplay(player, position, null);
    }

    public static GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy) {
        return provider.sampleGameplay(player, position, policy);
    }

    public static GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcBlockPos position) {
        return provider.sampleGameplay(player, position, null);
    }

    public static GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy) {
        return provider.sampleGameplay(player, position, policy);
    }

    public static A4mcVec3 sampleMeanVelocity(A4mcWorldRef world, A4mcVec3 position) {
        return sample(world, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleMeanVelocity(A4mcWorldRef world, A4mcBlockPos position) {
        return sample(world, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleEffectiveVelocity(A4mcWorldRef world, A4mcVec3 position) {
        return sample(world, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleEffectiveVelocity(A4mcWorldRef world, A4mcBlockPos position) {
        return sample(world, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleMeanVelocity(A4mcPlayerRef player, A4mcVec3 position) {
        return sample(player, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleMeanVelocity(A4mcPlayerRef player, A4mcBlockPos position) {
        return sample(player, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleEffectiveVelocity(A4mcPlayerRef player, A4mcVec3 position) {
        return sample(player, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleEffectiveVelocity(A4mcPlayerRef player, A4mcBlockPos position) {
        return sample(player, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleGameplayMeanVelocity(A4mcWorldRef world, A4mcVec3 position) {
        return sampleGameplay(world, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleGameplayMeanVelocity(A4mcWorldRef world, A4mcBlockPos position) {
        return sampleGameplay(world, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleGameplayEffectiveVelocity(A4mcWorldRef world, A4mcVec3 position) {
        return sampleGameplay(world, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleGameplayEffectiveVelocity(A4mcWorldRef world, A4mcBlockPos position) {
        return sampleGameplay(world, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleGameplayMeanVelocity(A4mcPlayerRef player, A4mcVec3 position) {
        return sampleGameplay(player, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleGameplayMeanVelocity(A4mcPlayerRef player, A4mcBlockPos position) {
        return sampleGameplay(player, position).meanVelocityVector();
    }

    public static A4mcVec3 sampleGameplayEffectiveVelocity(A4mcPlayerRef player, A4mcVec3 position) {
        return sampleGameplay(player, position).effectiveVelocityVector();
    }

    public static A4mcVec3 sampleGameplayEffectiveVelocity(A4mcPlayerRef player, A4mcBlockPos position) {
        return sampleGameplay(player, position).effectiveVelocityVector();
    }

    private static final class MissingProvider implements AeroWindRuntimeProvider {
        @Override
        public AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
            return AeroWindSample.ZERO;
        }

        @Override
        public AeroWindSample sample(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy) {
            return AeroWindSample.ZERO;
        }

        @Override
        public AeroWindSample sample(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy) {
            return AeroWindSample.ZERO;
        }

        @Override
        public AeroWindSample sample(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy) {
            return AeroWindSample.ZERO;
        }

        @Override
        public GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
            return GameplayWindSample.ZERO;
        }

        @Override
        public GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy) {
            return GameplayWindSample.ZERO;
        }

        @Override
        public GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy) {
            return GameplayWindSample.ZERO;
        }

        @Override
        public GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy) {
            return GameplayWindSample.ZERO;
        }
    }
}
