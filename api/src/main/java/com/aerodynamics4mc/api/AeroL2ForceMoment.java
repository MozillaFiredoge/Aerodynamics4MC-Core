package com.aerodynamics4mc.api;

public record AeroL2ForceMoment(
    float forceX,
    float forceY,
    float forceZ,
    float momentX,
    float momentY,
    float momentZ,
    float centerOfPressureX,
    float centerOfPressureY,
    float centerOfPressureZ,
    float referenceX,
    float referenceY,
    float referenceZ
) {
    public AeroL2ForceMoment {
        forceX = finiteOrZero(forceX);
        forceY = finiteOrZero(forceY);
        forceZ = finiteOrZero(forceZ);
        momentX = finiteOrZero(momentX);
        momentY = finiteOrZero(momentY);
        momentZ = finiteOrZero(momentZ);
        centerOfPressureX = finiteOrZero(centerOfPressureX);
        centerOfPressureY = finiteOrZero(centerOfPressureY);
        centerOfPressureZ = finiteOrZero(centerOfPressureZ);
        referenceX = finiteOrZero(referenceX);
        referenceY = finiteOrZero(referenceY);
        referenceZ = finiteOrZero(referenceZ);
    }

    public A4mcVec3 forceVector() {
        return new A4mcVec3(forceX, forceY, forceZ);
    }

    public A4mcVec3 momentVector() {
        return new A4mcVec3(momentX, momentY, momentZ);
    }

    public A4mcVec3 centerOfPressure() {
        return new A4mcVec3(centerOfPressureX, centerOfPressureY, centerOfPressureZ);
    }

    public A4mcVec3 referencePoint() {
        return new A4mcVec3(referenceX, referenceY, referenceZ);
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }
}
