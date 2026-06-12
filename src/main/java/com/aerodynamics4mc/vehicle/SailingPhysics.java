package com.aerodynamics4mc.vehicle;

import net.minecraft.world.phys.Vec3;

public final class SailingPhysics {
    public static final double DEFAULT_SPEED_SCALE = 10.0;

    private static final double METERS_PER_SECOND_TO_BLOCKS_PER_TICK = 1.0 / 20.0;
    private static final double BASE_WIND_ACCELERATION = 0.003;
    private static final double TARGET_SPEED_ACCELERATION_FACTOR = 0.14;
    private static final double LATERAL_WATER_DRAG = 0.62;
    private static final double MIN_SPEED = 1.0E-6;

    private SailingPhysics() {
    }

    public static Vec3 step(
            Vec3 currentVelocity,
            Vec3 windMetersPerSecond,
            float yawDegrees,
            boolean sailTrimmed,
            boolean sailReefed
    ) {
        return step(
                currentVelocity,
                windMetersPerSecond,
                yawDegrees,
                sailTrimmed,
                sailReefed,
                DEFAULT_SPEED_SCALE
        );
    }

    public static Vec3 step(
            Vec3 currentVelocity,
            Vec3 windMetersPerSecond,
            float yawDegrees,
            boolean sailTrimmed,
            boolean sailReefed,
            double speedScale
    ) {
        double safeSpeedScale = Math.max(0.0, speedScale);
        Vec3 horizontalWind = new Vec3(windMetersPerSecond.x, 0.0, windMetersPerSecond.z)
                .scale(METERS_PER_SECOND_TO_BLOCKS_PER_TICK * safeSpeedScale);
        Vec3 horizontalVelocity = new Vec3(currentVelocity.x, 0.0, currentVelocity.z);
        Vec3 forward = forwardFromYaw(yawDegrees);
        double windSpeed = horizontalWind.length();

        double forwardSpeed = horizontalVelocity.dot(forward);
        if (sailReefed) {
            forwardSpeed *= 0.82;
        }
        Vec3 forwardVelocity = forward.scale(forwardSpeed);
        Vec3 lateralVelocity = horizontalVelocity.subtract(forwardVelocity).scale(LATERAL_WATER_DRAG);

        if (windSpeed <= MIN_SPEED) {
            return new Vec3(lateralVelocity.x + forwardVelocity.x, currentVelocity.y, lateralVelocity.z + forwardVelocity.z);
        }

        Vec3 windDirection = horizontalWind.scale(1.0 / windSpeed);
        double windAlignment = windDirection.dot(forward);
        double trimScale = sailReefed ? 0.35 : sailTrimmed ? 1.12 : 1.0;
        double targetForwardSpeed = windSpeed * sailEfficiency(windAlignment) * trimScale;
        double maxAcceleration = BASE_WIND_ACCELERATION
                + targetForwardSpeed * TARGET_SPEED_ACCELERATION_FACTOR;
        double updatedForwardSpeed = moveToward(forwardSpeed, targetForwardSpeed, maxAcceleration);
        Vec3 updatedHorizontal = forward.scale(updatedForwardSpeed).add(lateralVelocity);

        return new Vec3(updatedHorizontal.x, currentVelocity.y, updatedHorizontal.z);
    }

    public static Vec3 smoothWind(Vec3 currentMetersPerSecond, Vec3 targetMetersPerSecond) {
        Vec3 current = horizontal(currentMetersPerSecond);
        Vec3 target = horizontal(targetMetersPerSecond);
        double currentSpeed = current.length();
        double targetSpeed = target.length();
        double updatedSpeed = moveToward(currentSpeed, targetSpeed, 0.08);

        if (updatedSpeed <= MIN_SPEED) {
            return Vec3.ZERO;
        }
        if (currentSpeed <= 0.05 || targetSpeed <= 0.05) {
            return targetSpeed <= MIN_SPEED ? Vec3.ZERO : target.scale(updatedSpeed / targetSpeed);
        }

        Vec3 currentDirection = current.scale(1.0 / currentSpeed);
        Vec3 targetDirection = target.scale(1.0 / targetSpeed);
        Vec3 updatedDirection = rotateDirectionToward(currentDirection, targetDirection, Math.toRadians(1.5));
        return updatedDirection.scale(updatedSpeed);
    }

    private static Vec3 horizontal(Vec3 vector) {
        return new Vec3(vector.x, 0.0, vector.z);
    }

    private static Vec3 forwardFromYaw(float yawDegrees) {
        double radians = Math.toRadians(yawDegrees);
        return new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
    }

    private static double sailEfficiency(double windAlignment) {
        double tailWind = Math.max(0.0, windAlignment);
        double headWind = Math.max(0.0, -windAlignment);
        double beamReach = Math.sqrt(Math.max(0.0, 1.0 - windAlignment * windAlignment));
        return clamp(tailWind + beamReach * (0.85 - headWind * 0.25) - headWind * 0.45, 0.0, 1.0);
    }

    private static Vec3 rotateDirectionToward(Vec3 currentDirection, Vec3 targetDirection, double maxRadians) {
        double currentAngle = Math.atan2(currentDirection.z, currentDirection.x);
        double targetAngle = Math.atan2(targetDirection.z, targetDirection.x);
        double delta = wrapRadians(targetAngle - currentAngle);
        double step = clamp(delta, -maxRadians, maxRadians);
        double updatedAngle = currentAngle + step;
        return new Vec3(Math.cos(updatedAngle), 0.0, Math.sin(updatedAngle));
    }

    private static double moveToward(double value, double target, double maxStep) {
        double delta = target - value;
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return value + Math.copySign(maxStep, delta);
    }

    private static double wrapRadians(double radians) {
        while (radians <= -Math.PI) {
            radians += Math.PI * 2.0;
        }
        while (radians > Math.PI) {
            radians -= Math.PI * 2.0;
        }
        return radians;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
