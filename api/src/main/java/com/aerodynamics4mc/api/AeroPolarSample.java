package com.aerodynamics4mc.api;

public record AeroPolarSample(
    double angleOfAttackDegrees,
    double controlDeflectionDegrees,
    double reynoldsNumber,
    double liftCoefficient,
    double dragCoefficient,
    double momentCoefficient
) {
    public AeroPolarSample {
        angleOfAttackDegrees = requireFinite("angleOfAttackDegrees", angleOfAttackDegrees);
        controlDeflectionDegrees = requireFinite("controlDeflectionDegrees", controlDeflectionDegrees);
        reynoldsNumber = requireNonNegativeFinite("reynoldsNumber", reynoldsNumber);
        liftCoefficient = requireFinite("liftCoefficient", liftCoefficient);
        dragCoefficient = requireNonNegativeFinite("dragCoefficient", dragCoefficient);
        momentCoefficient = requireFinite("momentCoefficient", momentCoefficient);
    }

    public static AeroPolarSample of(
        double angleOfAttackDegrees,
        double liftCoefficient,
        double dragCoefficient,
        double momentCoefficient
    ) {
        return new AeroPolarSample(
            angleOfAttackDegrees,
            0.0,
            0.0,
            liftCoefficient,
            dragCoefficient,
            momentCoefficient
        );
    }

    private static double requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    private static double requireNonNegativeFinite(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }
}
