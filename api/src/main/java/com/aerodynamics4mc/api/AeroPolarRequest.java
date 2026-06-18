package com.aerodynamics4mc.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record AeroPolarRequest(
    AeroSurfaceDescriptor surface,
    int gridSize,
    int stepsPerSample,
    double minAngleOfAttackDegrees,
    double maxAngleOfAttackDegrees,
    double angleStepDegrees,
    List<Double> reynoldsNumbers,
    List<Double> controlDeflectionDegrees,
    boolean outputDebugFlowAtlas
) {
    public static final int DEFAULT_GRID_SIZE = 64;
    public static final int DEFAULT_STEPS_PER_SAMPLE = 200;
    public static final double DEFAULT_MIN_ANGLE_OF_ATTACK_DEGREES = -20.0;
    public static final double DEFAULT_MAX_ANGLE_OF_ATTACK_DEGREES = 25.0;
    public static final double DEFAULT_ANGLE_STEP_DEGREES = 5.0;

    public AeroPolarRequest {
        surface = Objects.requireNonNull(surface, "surface");
        gridSize = requirePositive("gridSize", gridSize);
        stepsPerSample = requirePositive("stepsPerSample", stepsPerSample);
        minAngleOfAttackDegrees = requireFinite("minAngleOfAttackDegrees", minAngleOfAttackDegrees);
        maxAngleOfAttackDegrees = requireFinite("maxAngleOfAttackDegrees", maxAngleOfAttackDegrees);
        angleStepDegrees = requirePositiveFinite("angleStepDegrees", angleStepDegrees);
        if (maxAngleOfAttackDegrees < minAngleOfAttackDegrees) {
            throw new IllegalArgumentException("maxAngleOfAttackDegrees must be >= minAngleOfAttackDegrees");
        }
        reynoldsNumbers = normalizeNonNegativeBuckets("reynoldsNumbers", reynoldsNumbers);
        controlDeflectionDegrees = normalizeFiniteBuckets("controlDeflectionDegrees", controlDeflectionDegrees);
    }

    public static Builder builder(AeroSurfaceDescriptor surface) {
        return new Builder(surface);
    }

    public int angleSampleCount() {
        return (int) Math.floor((maxAngleOfAttackDegrees - minAngleOfAttackDegrees) / angleStepDegrees) + 1;
    }

    public double angleAt(int index) {
        if (index < 0 || index >= angleSampleCount()) {
            throw new IndexOutOfBoundsException("index outside angle sample range");
        }
        return minAngleOfAttackDegrees + index * angleStepDegrees;
    }

    public int totalSampleCount() {
        return angleSampleCount() * reynoldsNumbers.size() * controlDeflectionDegrees.size();
    }

    private static List<Double> normalizeNonNegativeBuckets(String name, List<Double> values) {
        List<Double> normalized = normalizeFiniteBuckets(name, values);
        for (double value : normalized) {
            if (value < 0.0) {
                throw new IllegalArgumentException(name + " entries must be non-negative");
            }
        }
        return normalized;
    }

    private static List<Double> normalizeFiniteBuckets(String name, List<Double> values) {
        List<Double> source = values == null || values.isEmpty() ? List.of(0.0) : values;
        List<Double> normalized = new ArrayList<>(source.size());
        for (Double value : source) {
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " entries must be finite");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static double requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    private static double requirePositiveFinite(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
        return value;
    }

    public static final class Builder {
        private final AeroSurfaceDescriptor surface;
        private int gridSize = DEFAULT_GRID_SIZE;
        private int stepsPerSample = DEFAULT_STEPS_PER_SAMPLE;
        private double minAngleOfAttackDegrees = DEFAULT_MIN_ANGLE_OF_ATTACK_DEGREES;
        private double maxAngleOfAttackDegrees = DEFAULT_MAX_ANGLE_OF_ATTACK_DEGREES;
        private double angleStepDegrees = DEFAULT_ANGLE_STEP_DEGREES;
        private List<Double> reynoldsNumbers = List.of(0.0);
        private List<Double> controlDeflectionDegrees = List.of(0.0);
        private boolean outputDebugFlowAtlas;

        private Builder(AeroSurfaceDescriptor surface) {
            this.surface = Objects.requireNonNull(surface, "surface");
        }

        public Builder gridSize(int gridSize) {
            this.gridSize = gridSize;
            return this;
        }

        public Builder stepsPerSample(int stepsPerSample) {
            this.stepsPerSample = stepsPerSample;
            return this;
        }

        public Builder angleSweep(double minDegrees, double maxDegrees, double stepDegrees) {
            this.minAngleOfAttackDegrees = minDegrees;
            this.maxAngleOfAttackDegrees = maxDegrees;
            this.angleStepDegrees = stepDegrees;
            return this;
        }

        public Builder reynoldsNumbers(List<Double> reynoldsNumbers) {
            this.reynoldsNumbers = reynoldsNumbers;
            return this;
        }

        public Builder controlDeflectionDegrees(List<Double> controlDeflectionDegrees) {
            this.controlDeflectionDegrees = controlDeflectionDegrees;
            return this;
        }

        public Builder outputDebugFlowAtlas(boolean outputDebugFlowAtlas) {
            this.outputDebugFlowAtlas = outputDebugFlowAtlas;
            return this;
        }

        public AeroPolarRequest build() {
            return new AeroPolarRequest(
                surface,
                gridSize,
                stepsPerSample,
                minAngleOfAttackDegrees,
                maxAngleOfAttackDegrees,
                angleStepDegrees,
                reynoldsNumbers,
                controlDeflectionDegrees,
                outputDebugFlowAtlas
            );
        }
    }
}
