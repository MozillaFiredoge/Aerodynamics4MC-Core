package com.aerodynamics4mc.api.minecraft;

import com.aerodynamics4mc.api.A4mcBlockPos;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.AeroWindSamplingRules;
import com.aerodynamics4mc.api.GameplayWindSample;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class AeroMinecraftVectors {
    private AeroMinecraftVectors() {
    }

    public static Vec3 toMinecraft(A4mcVec3 vector) {
        if (vector == null) {
            return Vec3.ZERO;
        }
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    public static A4mcVec3 fromMinecraft(Vec3 vector) {
        if (vector == null) {
            return A4mcVec3.ZERO;
        }
        return new A4mcVec3(vector.x, vector.y, vector.z);
    }

    public static BlockPos toMinecraft(A4mcBlockPos position) {
        if (position == null) {
            return BlockPos.ZERO;
        }
        return new BlockPos(position.x(), position.y(), position.z());
    }

    public static A4mcBlockPos fromMinecraft(BlockPos position) {
        if (position == null) {
            return A4mcBlockPos.ZERO;
        }
        return new A4mcBlockPos(position.getX(), position.getY(), position.getZ());
    }

    public static Vec3 velocity(AeroWindSample sample) {
        return toMinecraft((sample == null ? AeroWindSample.ZERO : sample).velocityVector());
    }

    public static Vec3 meanVelocity(AeroWindSample sample) {
        return toMinecraft((sample == null ? AeroWindSample.ZERO : sample).meanVelocityVector());
    }

    public static Vec3 gustVelocity(AeroWindSample sample) {
        return toMinecraft((sample == null ? AeroWindSample.ZERO : sample).gustVelocityVector());
    }

    public static Vec3 effectiveVelocity(AeroWindSample sample) {
        return toMinecraft((sample == null ? AeroWindSample.ZERO : sample).effectiveVelocityVector());
    }

    public static Vec3 meanVelocity(GameplayWindSample sample) {
        return toMinecraft((sample == null ? GameplayWindSample.ZERO : sample).meanVelocityVector());
    }

    public static Vec3 gustVelocity(GameplayWindSample sample) {
        return toMinecraft((sample == null ? GameplayWindSample.ZERO : sample).gustVelocityVector());
    }

    public static Vec3 effectiveVelocity(GameplayWindSample sample) {
        return toMinecraft((sample == null ? GameplayWindSample.ZERO : sample).effectiveVelocityVector());
    }

    public static boolean isFastPlayerVelocity(Vec3 velocity) {
        return AeroWindSamplingRules.isFastPlayerVelocity(fromMinecraft(velocity));
    }

    public static float horizontalSpeedMetersPerSecond(Vec3 velocity) {
        return AeroWindSamplingRules.horizontalSpeedMetersPerSecond(fromMinecraft(velocity));
    }
}
