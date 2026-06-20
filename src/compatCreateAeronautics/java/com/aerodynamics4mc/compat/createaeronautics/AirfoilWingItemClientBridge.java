package com.aerodynamics4mc.compat.createaeronautics;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

public final class AirfoilWingItemClientBridge {
	private static final Opener MISSING_OPENER = (player, hand, stack) -> InteractionResult.SUCCESS;
	private static volatile Opener opener = MISSING_OPENER;

	private AirfoilWingItemClientBridge() {
	}

	public static void register(Opener clientOpener) {
		opener = Objects.requireNonNull(clientOpener, "clientOpener");
	}

	public static InteractionResult open(Player player, InteractionHand hand, ItemStack stack) {
		return opener.open(player, hand, stack);
	}

	@FunctionalInterface
	public interface Opener {
		InteractionResult open(Player player, InteractionHand hand, ItemStack stack);
	}
}
