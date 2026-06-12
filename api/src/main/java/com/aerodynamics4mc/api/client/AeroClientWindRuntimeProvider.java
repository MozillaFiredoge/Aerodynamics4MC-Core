package com.aerodynamics4mc.api.client;

import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;

public interface AeroClientWindRuntimeProvider {
    AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy);
}
