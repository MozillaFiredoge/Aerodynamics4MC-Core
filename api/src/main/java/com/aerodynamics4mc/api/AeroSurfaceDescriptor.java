package com.aerodynamics4mc.api;

import java.util.Objects;

public record AeroSurfaceDescriptor(
    A4mcId id,
    String shapeHash,
    AeroAirfoilProfile airfoilProfile,
    double spanMeters,
    double chordMeters,
    double areaSquareMeters,
    double controlSurfaceRatio,
    A4mcVec3 localOriginMeters,
    A4mcVec3 chordDirection,
    A4mcVec3 spanDirection,
    A4mcVec3 normalDirection
) {
    public AeroSurfaceDescriptor {
        id = id == null ? A4mcId.of("aerodynamics4mc", "surface") : id;
        shapeHash = normalizeShapeHash(shapeHash);
        airfoilProfile = airfoilProfile == null ? AeroAirfoilProfile.FLAT_PLATE : airfoilProfile;
        spanMeters = requirePositiveFinite("spanMeters", spanMeters);
        chordMeters = requirePositiveFinite("chordMeters", chordMeters);
        areaSquareMeters = requirePositiveFinite("areaSquareMeters", areaSquareMeters);
        controlSurfaceRatio = requireUnitRatio("controlSurfaceRatio", controlSurfaceRatio);
        localOriginMeters = localOriginMeters == null ? A4mcVec3.ZERO : localOriginMeters;
        chordDirection = normalizeDirection("chordDirection", chordDirection);
        spanDirection = normalizeDirection("spanDirection", spanDirection);
        normalDirection = normalizeDirection("normalDirection", normalDirection);
    }

    public static AeroSurfaceDescriptor rectangular(
        A4mcId id,
        String shapeHash,
        AeroAirfoilProfile airfoilProfile,
        double spanMeters,
        double chordMeters,
        double controlSurfaceRatio,
        A4mcVec3 localOriginMeters,
        A4mcVec3 chordDirection,
        A4mcVec3 spanDirection,
        A4mcVec3 normalDirection
    ) {
        return new AeroSurfaceDescriptor(
            id,
            shapeHash,
            airfoilProfile,
            spanMeters,
            chordMeters,
            spanMeters * chordMeters,
            controlSurfaceRatio,
            localOriginMeters,
            chordDirection,
            spanDirection,
            normalDirection
        );
    }

    public double aspectRatio() {
        return spanMeters * spanMeters / areaSquareMeters;
    }

    public double meanAerodynamicChordMeters() {
        return areaSquareMeters / spanMeters;
    }

    private static String normalizeShapeHash(String shapeHash) {
        String safeHash = shapeHash == null ? "" : shapeHash.trim();
        if (safeHash.isEmpty()) {
            return "unknown";
        }
        return safeHash;
    }

    private static double requirePositiveFinite(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
        return value;
    }

    private static double requireUnitRatio(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
        return value;
    }

    private static A4mcVec3 normalizeDirection(String name, A4mcVec3 direction) {
        A4mcVec3 safeDirection = Objects.requireNonNull(direction, name);
        double length = safeDirection.length();
        if (length <= 1.0e-9) {
            throw new IllegalArgumentException(name + " must not be a zero vector");
        }
        return safeDirection.scale(1.0 / length);
    }
}
