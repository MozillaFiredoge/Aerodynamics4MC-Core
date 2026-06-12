package com.aerodynamics4mc.api;

public record GameplayWindSample(
    float meanX,
    float meanY,
    float meanZ,
    float pressure,
    float gustX,
    float gustY,
    float gustZ,
    float temperatureKelvin,
    float humidity,
    float turbulenceIntensity,
    float updraftMetersPerSecond,
    float windShearMagnitudePerBlock,
    float shelterFactor,
    float ablStability,
    float ablMixingStrength,
    float confidence,
    AeroWindSample.Level sourceLevel,
    AeroWindSample.Authority authority,
    long l1Epoch,
    long worldDeltaEpoch,
    long l2Epoch
) {
    private static final float MAX_MEAN_SPEED_MPS = 12.0f;
    private static final float MAX_EFFECTIVE_SPEED_MPS = 15.0f;
    private static final float MAX_GUST_SPEED_MPS = 4.0f;
    private static final float MAX_L2_LOCAL_DELTA_MPS = 2.5f;

    public static final GameplayWindSample ZERO = new GameplayWindSample(
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        AeroWindSample.UNKNOWN_SCALAR,
        AeroWindSample.UNKNOWN_SCALAR,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        AeroWindSample.Level.NONE,
        AeroWindSample.Authority.NONE,
        AeroWindSample.UNKNOWN_EPOCH,
        AeroWindSample.UNKNOWN_EPOCH,
        AeroWindSample.UNKNOWN_EPOCH
    );

    public GameplayWindSample {
        meanX = finiteOrZero(meanX);
        meanY = finiteOrZero(meanY);
        meanZ = finiteOrZero(meanZ);
        pressure = finiteOrZero(pressure);
        gustX = finiteOrZero(gustX);
        gustY = finiteOrZero(gustY);
        gustZ = finiteOrZero(gustZ);
        temperatureKelvin = finiteOrUnknown(temperatureKelvin);
        humidity = Float.isFinite(humidity) ? clamp01(humidity) : AeroWindSample.UNKNOWN_SCALAR;
        turbulenceIntensity = Math.max(0.0f, finiteOrZero(turbulenceIntensity));
        updraftMetersPerSecond = finiteOrZero(updraftMetersPerSecond);
        windShearMagnitudePerBlock = Math.max(0.0f, finiteOrZero(windShearMagnitudePerBlock));
        shelterFactor = clamp01(shelterFactor);
        ablStability = finiteOrZero(ablStability);
        ablMixingStrength = Math.max(0.0f, finiteOrZero(ablMixingStrength));
        sourceLevel = sourceLevel == null ? AeroWindSample.Level.NONE : sourceLevel;
        authority = authority == null ? AeroWindSample.Authority.NONE : authority;
        confidence = sourceLevel == AeroWindSample.Level.NONE || authority == AeroWindSample.Authority.NONE
            ? 0.0f
            : clamp01(confidence);
    }

    public static GameplayWindSample fromRaw(AeroWindSample raw) {
        return from(raw, raw);
    }

    public static GameplayWindSample from(AeroWindSample raw, AeroWindSample coarseReference) {
        AeroWindSample safeRaw = raw == null ? AeroWindSample.ZERO : raw;
        AeroWindSample safeCoarse = coarseReference == null ? AeroWindSample.ZERO : coarseReference;
        if (!safeRaw.hasFlow() && !safeCoarse.hasFlow()) {
            return ZERO;
        }

        AeroWindSample reference = safeCoarse.hasFlow() ? safeCoarse : safeRaw;
        boolean useL2AsLocalModifier = safeRaw.hasFlow()
            && safeRaw.sourceLevel() == AeroWindSample.Level.L2
            && reference.hasFlow()
            && reference.sourceLevel() != AeroWindSample.Level.L2;

        AeroWindSample primary = safeRaw.hasFlow() ? safeRaw : reference;
        float meanX = primary.velocityX();
        float meanY = primary.velocityY();
        float meanZ = primary.velocityZ();
        float deltaClampRatio = 0.0f;
        if (useL2AsLocalModifier) {
            float deltaX = safeRaw.velocityX() - reference.velocityX();
            float deltaY = safeRaw.velocityY() - reference.velocityY();
            float deltaZ = safeRaw.velocityZ() - reference.velocityZ();
            float referenceSpeed = vectorMagnitude(reference.velocityX(), reference.velocityY(), reference.velocityZ());
            float maxLocalDelta = MAX_L2_LOCAL_DELTA_MPS + referenceSpeed * 0.20f;
            float deltaSpeed = vectorMagnitude(deltaX, deltaY, deltaZ);
            if (deltaSpeed > maxLocalDelta && deltaSpeed > 1.0e-5f) {
                float scale = maxLocalDelta / deltaSpeed;
                deltaX *= scale;
                deltaY *= scale;
                deltaZ *= scale;
                deltaClampRatio = clamp01((deltaSpeed - maxLocalDelta) / Math.max(1.0f, maxLocalDelta));
            }
            meanX = reference.velocityX() + deltaX;
            meanY = reference.velocityY() + deltaY;
            meanZ = reference.velocityZ() + deltaZ;
        }

        float[] clampedMean = clampVector(meanX, meanY, meanZ, MAX_MEAN_SPEED_MPS);
        meanX = clampedMean[0];
        meanY = clampedMean[1];
        meanZ = clampedMean[2];

        float[] clampedGust = clampVector(safeRaw.gustX(), safeRaw.gustY(), safeRaw.gustZ(), MAX_GUST_SPEED_MPS);
        float effectiveX = meanX + clampedGust[0];
        float effectiveY = meanY + clampedGust[1];
        float effectiveZ = meanZ + clampedGust[2];
        float[] clampedEffective = clampVector(effectiveX, effectiveY, effectiveZ, MAX_EFFECTIVE_SPEED_MPS);
        clampedGust[0] = clampedEffective[0] - meanX;
        clampedGust[1] = clampedEffective[1] - meanY;
        clampedGust[2] = clampedEffective[2] - meanZ;

        float turbulence = Math.max(safeRaw.turbulenceIntensity(), reference.turbulenceIntensity());
        float shear = Math.max(safeRaw.windShearMagnitudePerBlock(), reference.windShearMagnitudePerBlock());
        float shelter = useL2AsLocalModifier ? shelterFactor(safeRaw, reference) : 0.0f;
        float confidence = safeRaw.hasFlow() ? safeRaw.confidence() : reference.confidence();
        if (useL2AsLocalModifier) {
            confidence = Math.min(confidence, reference.confidence()) * (1.0f - deltaClampRatio * 0.35f);
        }

        return new GameplayWindSample(
            meanX,
            meanY,
            meanZ,
            useL2AsLocalModifier ? reference.pressure() : primary.pressure(),
            clampedGust[0],
            clampedGust[1],
            clampedGust[2],
            firstKnownScalar(safeRaw.temperatureKelvin(), reference.temperatureKelvin()),
            firstKnownScalar(safeRaw.humidity(), reference.humidity()),
            turbulence,
            meanY + clampedGust[1],
            shear,
            shelter,
            primary.ablStability(),
            Math.max(safeRaw.ablMixingStrength(), reference.ablMixingStrength()),
            confidence,
            primary.sourceLevel(),
            primary.authority(),
            latestKnownEpoch(safeRaw.l1Epoch(), reference.l1Epoch()),
            latestKnownEpoch(safeRaw.worldDeltaEpoch(), reference.worldDeltaEpoch()),
            latestKnownEpoch(safeRaw.l2Epoch(), reference.l2Epoch())
        );
    }

    public A4mcVec3 meanVelocityVector() {
        return new A4mcVec3(meanX, meanY, meanZ);
    }

    public A4mcVec3 gustVelocityVector() {
        return new A4mcVec3(gustX, gustY, gustZ);
    }

    public A4mcVec3 effectiveVelocityVector() {
        return new A4mcVec3(meanX + gustX, meanY + gustY, meanZ + gustZ);
    }

    public float meanSpeedMetersPerSecond() {
        return vectorMagnitude(meanX, meanY, meanZ);
    }

    public float horizontalMeanSpeedMetersPerSecond() {
        return vectorMagnitude(meanX, 0.0f, meanZ);
    }

    public float effectiveSpeedMetersPerSecond() {
        return vectorMagnitude(meanX + gustX, meanY + gustY, meanZ + gustZ);
    }

    public boolean hasFlow() {
        return sourceLevel != AeroWindSample.Level.NONE && authority != AeroWindSample.Authority.NONE;
    }

    public boolean hasTemperature() {
        return Float.isFinite(temperatureKelvin);
    }

    public boolean hasHumidity() {
        return Float.isFinite(humidity);
    }

    public boolean isTrustedForGameplay() {
        return authority == AeroWindSample.Authority.SERVER_AUTHORITATIVE
            || authority == AeroWindSample.Authority.SERVER_AGGREGATED;
    }

    public boolean hasLocalL2Modifier() {
        return sourceLevel == AeroWindSample.Level.L2;
    }

    public boolean isSheltered() {
        return shelterFactor > 0.15f;
    }

    private static float shelterFactor(AeroWindSample local, AeroWindSample reference) {
        float referenceSpeed = vectorMagnitude(reference.velocityX(), 0.0f, reference.velocityZ());
        if (referenceSpeed <= 0.20f) {
            return 0.0f;
        }
        float localSpeed = vectorMagnitude(local.velocityX(), 0.0f, local.velocityZ());
        return clamp01(1.0f - localSpeed / referenceSpeed);
    }

    private static float[] clampVector(float x, float y, float z, float maxMagnitude) {
        float safeX = finiteOrZero(x);
        float safeY = finiteOrZero(y);
        float safeZ = finiteOrZero(z);
        float magnitude = vectorMagnitude(safeX, safeY, safeZ);
        if (!(magnitude > maxMagnitude) || magnitude <= 1.0e-5f) {
            return new float[] {safeX, safeY, safeZ};
        }
        float scale = maxMagnitude / magnitude;
        return new float[] {safeX * scale, safeY * scale, safeZ * scale};
    }

    private static long latestKnownEpoch(long first, long second) {
        if (first == AeroWindSample.UNKNOWN_EPOCH) {
            return second;
        }
        if (second == AeroWindSample.UNKNOWN_EPOCH) {
            return first;
        }
        return Math.max(first, second);
    }

    private static float firstKnownScalar(float first, float second) {
        if (Float.isFinite(first)) {
            return first;
        }
        return Float.isFinite(second) ? second : AeroWindSample.UNKNOWN_SCALAR;
    }

    private static float vectorMagnitude(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static float finiteOrUnknown(float value) {
        return Float.isFinite(value) ? value : AeroWindSample.UNKNOWN_SCALAR;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, finiteOrZero(value)));
    }
}
