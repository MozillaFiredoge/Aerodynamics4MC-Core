package com.aerodynamics4mc.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record AeroPolarTable(
    AeroSurfaceDescriptor surface,
    List<AeroPolarSample> samples,
    String solverId,
    String tableHash,
    long createdEpochMillis
) {
    private static final Comparator<AeroPolarSample> SAMPLE_ORDER = Comparator
        .comparingDouble(AeroPolarSample::reynoldsNumber)
        .thenComparingDouble(AeroPolarSample::controlDeflectionDegrees)
        .thenComparingDouble(AeroPolarSample::angleOfAttackDegrees);

    public AeroPolarTable {
        surface = Objects.requireNonNull(surface, "surface");
        samples = sortedSamples(samples);
        solverId = normalizeMetadata("solverId", solverId);
        tableHash = normalizeMetadata("tableHash", tableHash);
        if (createdEpochMillis < 0L) {
            throw new IllegalArgumentException("createdEpochMillis must be non-negative");
        }
    }

    public AeroPolarSample lookup(double angleOfAttackDegrees) {
        return lookup(angleOfAttackDegrees, 0.0, 0.0);
    }

    public AeroPolarSample lookup(
        double angleOfAttackDegrees,
        double controlDeflectionDegrees,
        double reynoldsNumber
    ) {
        requireFinite("angleOfAttackDegrees", angleOfAttackDegrees);
        requireFinite("controlDeflectionDegrees", controlDeflectionDegrees);
        if (!Double.isFinite(reynoldsNumber) || reynoldsNumber < 0.0) {
            throw new IllegalArgumentException("reynoldsNumber must be finite and non-negative");
        }

        double deflectionBucket = nearestControlDeflection(controlDeflectionDegrees);
        double reynoldsBucket = nearestReynoldsNumber(deflectionBucket, reynoldsNumber);
        List<AeroPolarSample> bucket = samples.stream()
            .filter(sample -> sample.controlDeflectionDegrees() == deflectionBucket)
            .filter(sample -> sample.reynoldsNumber() == reynoldsBucket)
            .sorted(Comparator.comparingDouble(AeroPolarSample::angleOfAttackDegrees))
            .toList();
        return interpolateAngle(bucket, angleOfAttackDegrees, deflectionBucket, reynoldsBucket);
    }

    private double nearestControlDeflection(double target) {
        double best = samples.get(0).controlDeflectionDegrees();
        double bestDistance = Math.abs(best - target);
        for (AeroPolarSample sample : samples) {
            double candidate = sample.controlDeflectionDegrees();
            double distance = Math.abs(candidate - target);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private double nearestReynoldsNumber(double deflectionBucket, double target) {
        double best = Double.NaN;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (AeroPolarSample sample : samples) {
            if (sample.controlDeflectionDegrees() != deflectionBucket) {
                continue;
            }
            double candidate = sample.reynoldsNumber();
            double distance = Math.abs(candidate - target);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (Double.isNaN(best)) {
            throw new IllegalStateException("polar table has no samples for deflection bucket " + deflectionBucket);
        }
        return best;
    }

    private static AeroPolarSample interpolateAngle(
        List<AeroPolarSample> bucket,
        double angleOfAttackDegrees,
        double controlDeflectionDegrees,
        double reynoldsNumber
    ) {
        if (bucket.isEmpty()) {
            throw new IllegalStateException("polar table bucket is empty");
        }
        AeroPolarSample lower = bucket.get(0);
        AeroPolarSample upper = bucket.get(bucket.size() - 1);
        if (angleOfAttackDegrees <= lower.angleOfAttackDegrees()) {
            return sampleAt(angleOfAttackDegrees, controlDeflectionDegrees, reynoldsNumber, lower);
        }
        if (angleOfAttackDegrees >= upper.angleOfAttackDegrees()) {
            return sampleAt(angleOfAttackDegrees, controlDeflectionDegrees, reynoldsNumber, upper);
        }

        for (int i = 1; i < bucket.size(); i++) {
            upper = bucket.get(i);
            lower = bucket.get(i - 1);
            if (angleOfAttackDegrees <= upper.angleOfAttackDegrees()) {
                double span = upper.angleOfAttackDegrees() - lower.angleOfAttackDegrees();
                double t = span <= 0.0 ? 0.0 : (angleOfAttackDegrees - lower.angleOfAttackDegrees()) / span;
                return new AeroPolarSample(
                    angleOfAttackDegrees,
                    controlDeflectionDegrees,
                    reynoldsNumber,
                    lerp(lower.liftCoefficient(), upper.liftCoefficient(), t),
                    lerp(lower.dragCoefficient(), upper.dragCoefficient(), t),
                    lerp(lower.momentCoefficient(), upper.momentCoefficient(), t)
                );
            }
        }
        return sampleAt(angleOfAttackDegrees, controlDeflectionDegrees, reynoldsNumber, bucket.get(bucket.size() - 1));
    }

    private static AeroPolarSample sampleAt(
        double angleOfAttackDegrees,
        double controlDeflectionDegrees,
        double reynoldsNumber,
        AeroPolarSample source
    ) {
        return new AeroPolarSample(
            angleOfAttackDegrees,
            controlDeflectionDegrees,
            reynoldsNumber,
            source.liftCoefficient(),
            source.dragCoefficient(),
            source.momentCoefficient()
        );
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static List<AeroPolarSample> sortedSamples(List<AeroPolarSample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        List<AeroPolarSample> copy = new ArrayList<>(samples.size());
        for (AeroPolarSample sample : samples) {
            copy.add(Objects.requireNonNull(sample, "sample"));
        }
        copy.sort(SAMPLE_ORDER);
        return List.copyOf(copy);
    }

    private static String normalizeMetadata(String name, String value) {
        String safeValue = value == null ? "" : value.trim();
        if (safeValue.isEmpty()) {
            return "unknown";
        }
        return safeValue;
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
