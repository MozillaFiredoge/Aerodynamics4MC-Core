package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroWorldWrapApi;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/** Coordinates in dimensions that repeat horizontally, see {@link AeroWorldWrapApi}. */
public final class WorldWrap {
	private WorldWrap() {
	}

	public static int periodBlocks(Level world) {
		return world == null ? 0 : periodBlocks(world.dimension().identifier());
	}

	public static int periodBlocks(Identifier dimensionId) {
		return dimensionId == null ? 0 : AeroWorldWrapApi.periodBlocks(A4mcId.of(dimensionId.getNamespace(), dimensionId.getPath()));
	}

	/** The multiple of the period that moves {@code coordinate} to its copy nearest {@code reference}. */
	public static int shift(int coordinate, int reference, int period) {
		if (period <= 0) {
			return 0;
		}
		return -Math.floorDiv(coordinate - reference + period / 2, period) * period;
	}

	public static int nearest(int coordinate, int reference, int period) {
		return coordinate + shift(coordinate, reference, period);
	}

	public static double nearest(double coordinate, double reference, int period) {
		if (period <= 0) {
			return coordinate;
		}
		return coordinate - Math.floor((coordinate - reference) / period + 0.5) * period;
	}

	public static BlockPos nearest(BlockPos pos, int referenceX, int referenceZ, int period) {
		int dx = shift(pos.getX(), referenceX, period);
		int dz = shift(pos.getZ(), referenceZ, period);
		return dx == 0 && dz == 0 ? pos : pos.offset(dx, 0, dz);
	}
}
