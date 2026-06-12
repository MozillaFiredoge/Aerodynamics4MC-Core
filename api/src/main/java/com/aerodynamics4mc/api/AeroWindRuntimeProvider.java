package com.aerodynamics4mc.api;

public interface AeroWindRuntimeProvider {
    AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy);

    AeroWindSample sample(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy);

    AeroWindSample sample(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy);

    AeroWindSample sample(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy);

    GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy);

    GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy);

    GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy);

    GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy);
}
