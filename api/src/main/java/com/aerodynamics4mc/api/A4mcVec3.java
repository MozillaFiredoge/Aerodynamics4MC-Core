package com.aerodynamics4mc.api;

public record A4mcVec3(double x, double y, double z) {
    public static final A4mcVec3 ZERO = new A4mcVec3(0.0, 0.0, 0.0);

    public A4mcVec3 {
        requireFinite("x", x);
        requireFinite("y", y);
        requireFinite("z", z);
    }

    public static A4mcVec3 of(double x, double y, double z) {
        return new A4mcVec3(x, y, z);
    }

    public A4mcVec3 add(A4mcVec3 other) {
        A4mcVec3 safeOther = other == null ? ZERO : other;
        return new A4mcVec3(x + safeOther.x, y + safeOther.y, z + safeOther.z);
    }

    public A4mcVec3 subtract(A4mcVec3 other) {
        A4mcVec3 safeOther = other == null ? ZERO : other;
        return new A4mcVec3(x - safeOther.x, y - safeOther.y, z - safeOther.z);
    }

    public A4mcVec3 scale(double scale) {
        requireFinite("scale", scale);
        return new A4mcVec3(x * scale, y * scale, z * scale);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
