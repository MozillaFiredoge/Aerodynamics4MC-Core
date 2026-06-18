package com.aerodynamics4mc.api.client;

import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroL2Request;
import com.aerodynamics4mc.api.AeroL2Result;
import com.aerodynamics4mc.api.AeroPolarRequest;
import com.aerodynamics4mc.api.AeroPolarResult;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;

public interface AeroClientWindRuntimeProvider {
    default AeroL2Result runL2(AeroL2Request request) {
        return AeroL2Result.unavailable("AeroL2 client runtime provider is not available");
    }

    default AeroPolarResult runPolar(AeroPolarRequest request) {
        return AeroPolarResult.unavailable("Aero polar client runtime provider is not available");
    }

    AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy);
}
