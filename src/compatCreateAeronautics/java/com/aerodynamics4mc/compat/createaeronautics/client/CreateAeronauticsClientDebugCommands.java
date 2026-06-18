package com.aerodynamics4mc.compat.createaeronautics.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class CreateAeronauticsClientDebugCommands {
	private CreateAeronauticsClientDebugCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("aero_ca")
				.then(Commands.literal("flight_polar_overlay")
						.executes(ctx -> status(ctx.getSource()))
						.then(Commands.literal("status")
								.executes(ctx -> status(ctx.getSource()))
						)
						.then(Commands.literal("enable")
								.executes(ctx -> setEnabled(ctx.getSource(), true))
						)
						.then(Commands.literal("disable")
								.executes(ctx -> setEnabled(ctx.getSource(), false))
						)
						.then(Commands.argument("enabled", BoolArgumentType.bool())
								.executes(ctx -> setEnabled(
										ctx.getSource(),
										BoolArgumentType.getBool(ctx, "enabled")
								))
						)
				)
		);
	}

	private static int status(CommandSourceStack source) {
		source.sendSuccess(
				() -> Component.literal("Create Aeronautics A4MC flight test overlay=" + CreateAeronauticsPolarOverlay.status()
						+ " source=client estimate"),
				false
		);
		return 1;
	}

	private static int setEnabled(CommandSourceStack source, boolean enabled) {
		CreateAeronauticsPolarOverlay.setEnabled(enabled);
		source.sendSuccess(
				() -> Component.literal("Create Aeronautics A4MC flight test overlay="
						+ (enabled ? "enabled" : "disabled")
						+ " source=client estimate"),
				false
		);
		return 1;
	}
}
