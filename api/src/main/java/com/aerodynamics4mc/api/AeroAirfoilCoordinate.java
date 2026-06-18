package com.aerodynamics4mc.api;

public record AeroAirfoilCoordinate(double x, double y) {
    public AeroAirfoilCoordinate {
        if (!Double.isFinite(x) || x < 0.0 || x > 1.0) {
            throw new IllegalArgumentException("x must be finite and in [0, 1]");
        }
        if (!Double.isFinite(y) || y < -1.0 || y > 1.0) {
            throw new IllegalArgumentException("y must be finite and in [-1, 1]");
        }
    }
}
