package com.aerodynamics4mc.api;

public interface AeroWindRuntimeProvider {
    default AeroL2Result runL2(AeroL2Request request) {
        return AeroL2Result.unavailable("AeroL2 runtime provider is not available");
    }

    default AeroPolarResult runPolar(AeroPolarRequest request) {
        return AeroPolarResult.unavailable("Aero polar runtime provider is not available");
    }

    AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy);

    AeroWindSample sample(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy);

    AeroWindSample sample(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy);

    AeroWindSample sample(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy);

    GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy);

    GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy);

    GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy);

    GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy);
}
