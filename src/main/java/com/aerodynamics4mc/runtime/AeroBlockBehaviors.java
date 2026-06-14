package com.aerodynamics4mc.runtime;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AeroBlockBehaviors {
	private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

	private AeroBlockBehaviors() {
	}

	public static void register(Provider provider) {
		PROVIDERS.add(Objects.requireNonNull(provider, "provider"));
	}

	public static boolean isFan(BlockState state) {
		if (state == null) {
			return false;
		}
		for (Provider provider : PROVIDERS) {
			if (provider.isFan(state)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isFanBlockEntity(BlockEntity blockEntity) {
		if (blockEntity == null) {
			return false;
		}
		for (Provider provider : PROVIDERS) {
			if (provider.isFanBlockEntity(blockEntity)) {
				return true;
			}
		}
		return isFan(blockEntity.getBlockState());
	}

	public static Direction fanFacing(BlockState state) {
		if (state == null) {
			return Direction.NORTH;
		}
		for (Provider provider : PROVIDERS) {
			if (provider.isFan(state)) {
				return provider.fanFacing(state);
			}
		}
		return Direction.NORTH;
	}

	public static boolean isDuct(BlockState state) {
		if (state == null) {
			return false;
		}
		for (Provider provider : PROVIDERS) {
			if (provider.isDuct(state)) {
				return true;
			}
		}
		return false;
	}

	public interface Provider {
		default boolean isFan(BlockState state) {
			return false;
		}

		default boolean isFanBlockEntity(BlockEntity blockEntity) {
			return isFan(blockEntity.getBlockState());
		}

		default Direction fanFacing(BlockState state) {
			return Direction.NORTH;
		}

		default boolean isDuct(BlockState state) {
			return false;
		}
	}
}
