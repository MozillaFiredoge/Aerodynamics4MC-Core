package com.aerodynamics4mc.api;

import java.util.Locale;
import java.util.Objects;

public record AeroAirfoilProfile(
    A4mcId id,
    Kind kind,
    double maxCamberRatio,
    double maxCamberPositionRatio,
    double thicknessRatio
) {
    public static final AeroAirfoilProfile FLAT_PLATE = new AeroAirfoilProfile(
        A4mcId.of("aerodynamics4mc", "flat_plate"),
        Kind.FLAT_PLATE,
        0.0,
        0.0,
        0.02
    );
    public static final AeroAirfoilProfile SYMMETRIC_THIN = naca4(8);
    public static final AeroAirfoilProfile NACA_0012 = naca4(12);
    public static final AeroAirfoilProfile NACA_2412 = naca4(2412);
    public static final AeroAirfoilProfile NACA_4412 = naca4(4412);
    public static final AeroAirfoilProfile NACA_4415 = naca4(4415);

    public AeroAirfoilProfile {
        id = Objects.requireNonNull(id, "id");
        kind = kind == null ? Kind.CUSTOM : kind;
        maxCamberRatio = requireRatio("maxCamberRatio", maxCamberRatio);
        thicknessRatio = requireRatio("thicknessRatio", thicknessRatio);
        maxCamberPositionRatio = requireCamberPosition(maxCamberRatio, maxCamberPositionRatio);
    }

    public static AeroAirfoilProfile naca4(int digits) {
        if (digits < 0 || digits > 9999) {
            throw new IllegalArgumentException("NACA 4-digit code must be in [0, 9999]");
        }
        int maxCamberDigit = digits / 1000;
        int camberPositionDigit = (digits / 100) % 10;
        int thicknessDigits = digits % 100;
        if (maxCamberDigit > 0 && camberPositionDigit == 0) {
            throw new IllegalArgumentException("cambered NACA profiles require a non-zero camber position digit");
        }
        return new AeroAirfoilProfile(
            A4mcId.of("aerodynamics4mc", String.format(Locale.ROOT, "naca_%04d", digits)),
            Kind.NACA_4_DIGIT,
            maxCamberDigit / 100.0,
            camberPositionDigit / 10.0,
            thicknessDigits / 100.0
        );
    }

    public static AeroAirfoilProfile symmetric(A4mcId id, double thicknessRatio) {
        return new AeroAirfoilProfile(id, Kind.SYMMETRIC, 0.0, 0.0, thicknessRatio);
    }

    public static AeroAirfoilProfile cambered(
        A4mcId id,
        double maxCamberRatio,
        double maxCamberPositionRatio,
        double thicknessRatio
    ) {
        return new AeroAirfoilProfile(
            id,
            Kind.CAMBERED,
            maxCamberRatio,
            maxCamberPositionRatio,
            thicknessRatio
        );
    }

    public boolean symmetric() {
        return maxCamberRatio == 0.0;
    }

    private static double requireRatio(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
        return value;
    }

    private static double requireCamberPosition(double maxCamberRatio, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value >= 1.0) {
            throw new IllegalArgumentException("maxCamberPositionRatio must be finite and in [0, 1)");
        }
        if (maxCamberRatio > 0.0 && value == 0.0) {
            throw new IllegalArgumentException("cambered profiles require maxCamberPositionRatio > 0");
        }
        return value;
    }

    public enum Kind {
        FLAT_PLATE,
        SYMMETRIC,
        CAMBERED,
        NACA_4_DIGIT,
        CUSTOM
    }
}
