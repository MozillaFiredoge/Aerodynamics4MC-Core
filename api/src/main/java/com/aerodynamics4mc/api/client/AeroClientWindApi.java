package com.aerodynamics4mc.api.client;

import com.aerodynamics4mc.api.A4mcBlockPos;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroL2Request;
import com.aerodynamics4mc.api.AeroL2Result;
import com.aerodynamics4mc.api.AeroPolarRequest;
import com.aerodynamics4mc.api.AeroPolarResult;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;

import java.util.Objects;

public final class AeroClientWindApi {
    private static final AeroClientWindRuntimeProvider MISSING_PROVIDER = new MissingProvider();
    private static volatile AeroClientWindRuntimeProvider provider = MISSING_PROVIDER;

    private AeroClientWindApi() {
    }

    public static void registerProvider(AeroClientWindRuntimeProvider runtimeProvider) {
        provider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
    }

    public static boolean isAvailable() {
        return provider != MISSING_PROVIDER;
    }

    public static AeroL2Result runL2(AeroL2Request request) {
        return provider.runL2(request);
    }

    public static AeroPolarResult runPolar(AeroPolarRequest request) {
        return provider.runPolar(request);
    }

    public static AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position) {
        return provider.sample(world, position, null);
    }

    public static AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
        return provider.sample(world, position, policy);
    }

    public static AeroWindSample sample(A4mcWorldRef world, A4mcBlockPos position) {
        return sample(world, center(position));
    }

    public static AeroWindSample sample(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy) {
        return sample(world, center(position), policy);
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

    private static A4mcVec3 center(A4mcBlockPos position) {
        if (position == null) {
            return A4mcVec3.ZERO;
        }
        return position.center();
    }

    private static final class MissingProvider implements AeroClientWindRuntimeProvider {
        @Override
        public AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
            return AeroWindSample.ZERO;
        }
    }
}
