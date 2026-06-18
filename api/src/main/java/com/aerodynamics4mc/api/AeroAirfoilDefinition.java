package com.aerodynamics4mc.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record AeroAirfoilDefinition(
    String format,
    A4mcId id,
    String displayName,
    AeroAirfoilProfile profile,
    List<AeroAirfoilCoordinate> coordinates,
    String source,
    String notes
) {
    public static final String FORMAT_V1 = "a4mc-airfoil-v1";

    public AeroAirfoilDefinition {
        format = normalizeFormat(format);
        id = Objects.requireNonNull(id, "id");
        displayName = normalizeRequired("displayName", displayName);
        profile = Objects.requireNonNull(profile, "profile");
        coordinates = immutableCoordinates(coordinates);
        source = normalizeOptional(source);
        notes = normalizeOptional(notes);
    }

    public static AeroAirfoilDefinition of(
        A4mcId id,
        String displayName,
        AeroAirfoilProfile profile,
        List<AeroAirfoilCoordinate> coordinates,
        String source,
        String notes
    ) {
        return new AeroAirfoilDefinition(
            FORMAT_V1,
            id,
            displayName,
            profile,
            coordinates,
            source,
            notes
        );
    }

    private static String normalizeFormat(String format) {
        String safeFormat = format == null ? FORMAT_V1 : format.trim();
        if (!FORMAT_V1.equals(safeFormat)) {
            throw new IllegalArgumentException("unsupported airfoil format: " + safeFormat);
        }
        return safeFormat;
    }

    private static String normalizeRequired(String name, String value) {
        String safeValue = value == null ? "" : value.trim();
        if (safeValue.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return safeValue;
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<AeroAirfoilCoordinate> immutableCoordinates(List<AeroAirfoilCoordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return List.of();
        }
        List<AeroAirfoilCoordinate> copy = new ArrayList<>(coordinates.size());
        for (AeroAirfoilCoordinate coordinate : coordinates) {
            copy.add(Objects.requireNonNull(coordinate, "coordinate"));
        }
        return List.copyOf(copy);
    }
}
