package com.aerodynamics4mc.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AeroAirfoilPresets {
    public static final AeroAirfoilDefinition NACA_0012 = nacaPreset(
        "NACA 0012",
        AeroAirfoilProfile.NACA_0012,
        "Symmetric baseline airfoil for main wings, tails, and propeller test blades."
    );
    public static final AeroAirfoilDefinition NACA_2412 = nacaPreset(
        "NACA 2412",
        AeroAirfoilProfile.NACA_2412,
        "Moderately cambered low-speed airfoil with more lift than NACA 0012."
    );
    public static final AeroAirfoilDefinition NACA_4412 = nacaPreset(
        "NACA 4412",
        AeroAirfoilProfile.NACA_4412,
        "High-camber low-speed airfoil for forgiving lift-heavy aircraft."
    );
    public static final AeroAirfoilDefinition FLAT_PLATE = new AeroAirfoilDefinition(
        AeroAirfoilDefinition.FORMAT_V1,
        AeroAirfoilProfile.FLAT_PLATE.id(),
        "Flat Plate",
        AeroAirfoilProfile.FLAT_PLATE,
        List.of(
            new AeroAirfoilCoordinate(0.0, 0.01),
            new AeroAirfoilCoordinate(1.0, 0.01),
            new AeroAirfoilCoordinate(1.0, -0.01),
            new AeroAirfoilCoordinate(0.0, -0.01)
        ),
        "aerodynamics4mc:preset",
        "Simple flat plate reference for comparing against Create: Aeronautics sail-like surfaces."
    );

    private static final List<AeroAirfoilDefinition> DEFAULTS = List.of(
        NACA_0012,
        NACA_2412,
        NACA_4412,
        FLAT_PLATE
    );

    private AeroAirfoilPresets() {
    }

    public static List<AeroAirfoilDefinition> defaults() {
        return DEFAULTS;
    }

    public static Optional<AeroAirfoilDefinition> find(A4mcId id) {
        for (AeroAirfoilDefinition definition : DEFAULTS) {
            if (definition.id().equals(id)) {
                return Optional.of(definition);
            }
        }
        return Optional.empty();
    }

    private static AeroAirfoilDefinition nacaPreset(
        String displayName,
        AeroAirfoilProfile profile,
        String notes
    ) {
        return new AeroAirfoilDefinition(
            AeroAirfoilDefinition.FORMAT_V1,
            profile.id(),
            displayName,
            profile,
            nacaCoordinates(profile, 32),
            "aerodynamics4mc:preset",
            notes
        );
    }

    private static List<AeroAirfoilCoordinate> nacaCoordinates(AeroAirfoilProfile profile, int segments) {
        List<AeroAirfoilCoordinate> upper = new ArrayList<>(segments + 1);
        List<AeroAirfoilCoordinate> lower = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            double theta = Math.PI * i / segments;
            double x = 0.5 * (1.0 - Math.cos(theta));
            Section section = nacaSection(profile, x);
            upper.add(new AeroAirfoilCoordinate(section.upperX(), section.upperY()));
            lower.add(new AeroAirfoilCoordinate(section.lowerX(), section.lowerY()));
        }

        List<AeroAirfoilCoordinate> coordinates = new ArrayList<>(upper.size() + lower.size() - 1);
        for (int i = upper.size() - 1; i >= 0; i--) {
            coordinates.add(upper.get(i));
        }
        for (int i = 1; i < lower.size(); i++) {
            coordinates.add(lower.get(i));
        }
        return List.copyOf(coordinates);
    }

    private static Section nacaSection(AeroAirfoilProfile profile, double x) {
        double camber = profile.maxCamberRatio();
        double camberPosition = profile.maxCamberPositionRatio();
        double thickness = profile.thicknessRatio();
        double yt = 5.0 * thickness * (
            0.2969 * Math.sqrt(x)
                - 0.1260 * x
                - 0.3516 * x * x
                + 0.2843 * x * x * x
                - 0.1015 * x * x * x * x
        );
        double yc = 0.0;
        double dycDx = 0.0;
        if (camber > 0.0 && camberPosition > 0.0) {
            if (x < camberPosition) {
                yc = camber / (camberPosition * camberPosition)
                    * (2.0 * camberPosition * x - x * x);
                dycDx = 2.0 * camber / (camberPosition * camberPosition) * (camberPosition - x);
            } else {
                double q = 1.0 - camberPosition;
                yc = camber / (q * q) * ((1.0 - 2.0 * camberPosition) + 2.0 * camberPosition * x - x * x);
                dycDx = 2.0 * camber / (q * q) * (camberPosition - x);
            }
        }

        double theta = Math.atan(dycDx);
        return new Section(
            clampUnit(x - yt * Math.sin(theta)),
            clampCoordinate(yc + yt * Math.cos(theta)),
            clampUnit(x + yt * Math.sin(theta)),
            clampCoordinate(yc - yt * Math.cos(theta))
        );
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clampCoordinate(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private record Section(double upperX, double upperY, double lowerX, double lowerY) {
    }
}
