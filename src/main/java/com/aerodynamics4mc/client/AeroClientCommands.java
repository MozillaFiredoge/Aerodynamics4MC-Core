package com.aerodynamics4mc.client;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.network.packet.AeroClientL2PreferencePacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

public class AeroClientCommands {

	private AeroClientCommands() {
		// private constructor
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher,
	                            final CommandBuildContext buildContext) {

		dispatcher.register(
				Commands.literal("aero_client_l2")
						.executes(ctx -> clientL2Status(ctx.getSource()))
						.then(Commands.literal("status")
								.executes(ctx -> clientL2Status(ctx.getSource())))
						.then(Commands.literal("on")
								.executes(ctx -> setClientL2Experimental(ctx.getSource(), true)))
						.then(Commands.literal("off")
								.executes(ctx -> setClientL2Experimental(ctx.getSource(), false)))
						.then(Commands.literal("stress")
								.executes(ctx -> clientL2StressStatus(ctx.getSource()))
								.then(Commands.literal("status")
										.executes(ctx -> clientL2StressStatus(ctx.getSource())))
								.then(Commands.literal("off")
										.executes(ctx -> setClientL2Stress(ctx.getSource(), "off")))
								.then(Commands.literal("fan")
										.executes(ctx -> setClientL2Stress(ctx.getSource(), "fan")))
								.then(Commands.literal("thermal")
										.executes(ctx -> setClientL2Stress(ctx.getSource(), "thermal")))
								.then(Commands.literal("dirty")
										.executes(ctx -> setClientL2Stress(ctx.getSource(), "dirty")))
								.then(Commands.literal("mixed")
										.executes(ctx -> setClientL2Stress(ctx.getSource(), "mixed"))))
		);

		dispatcher.register(
				Commands.literal("aero")
						.then(Commands.literal("render")
								.executes(ctx -> renderStatus(ctx.getSource()))
								.then(Commands.literal("vectors")
										.then(Commands.literal("on")
												.executes(ctx -> setRenderVelocityVectors(ctx.getSource(), true)))
										.then(Commands.literal("off")
												.executes(ctx -> setRenderVelocityVectors(ctx.getSource(), false))))
								.then(Commands.literal("streamlines")
										.then(Commands.literal("on")
												.executes(ctx -> setRenderStreamlines(ctx.getSource(), true)))
										.then(Commands.literal("off")
												.executes(ctx -> setRenderStreamlines(ctx.getSource(), false)))))
						.then(Commands.literal("cinematic")
								.executes(ctx -> cinematicStatus(ctx.getSource()))
								.then(Commands.literal("status")
										.executes(ctx -> cinematicStatus(ctx.getSource())))
								.then(Commands.literal("clear")
										.executes(ctx -> clearCinematicStorm(ctx.getSource())))
								.then(Commands.literal("storm")
										.executes(ctx -> setCinematicStorm(ctx.getSource(), 1.0f, 0))
										.then(Commands.argument("intensity", FloatArgumentType.floatArg(0.0f, 1.0f))
												.executes(ctx -> setCinematicStorm(ctx.getSource(), FloatArgumentType.getFloat(ctx, "intensity"), 0))
												.then(Commands.argument("duration_seconds", IntegerArgumentType.integer(0, 3600))
														.executes(ctx -> setCinematicStorm(
																ctx.getSource(),
																FloatArgumentType.getFloat(ctx, "intensity"),
																IntegerArgumentType.getInteger(ctx, "duration_seconds")
														))
												)
										)
								)
						)
		);
	}

	// ==================== Command Handlers ====================

	private static int clientL2Status(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		source.sendSuccess(() -> Component.literal(mod.getClientL2Solver().status()), false);
		return 1;
	}

	private static int setClientL2Experimental(CommandSourceStack source, boolean enabled) {
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getClientL2Solver().setExperimentalEnabled(enabled);
		if (enabled) {
			mod.getVisualizer().clearRemoteFlowFields();
		}
		ModTemplate.xplat().sendPacketToServer(new AeroClientL2PreferencePacket(enabled));
		source.sendSuccess(() -> Component.literal("Client L2 local solve " + (enabled ? "enabled" : "disabled")), false);
		return 1;
	}

	private static int clientL2StressStatus(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		source.sendSuccess(() -> Component.literal("Client L2 stress " + mod.getClientL2Solver().stressStatus()), false);
		return 1;
	}

	private static int setClientL2Stress(CommandSourceStack source, String mode) {
		AeroClientMod mod = AeroClientMod.getInstance();
		try {
			String message = mod.getClientL2Solver().setStressMode(mode);
			source.sendSuccess(() -> Component.literal(message), false);
			return 1;
		} catch (IllegalArgumentException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}

	private static int renderStatus(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		source.sendSuccess(mod::renderStatusText, false);
		return 1;
	}

	private static int setRenderVelocityVectors(CommandSourceStack source, boolean enabled) {
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getVisualizer().setRenderVelocityVectors(enabled);
		source.sendSuccess(() -> Component.literal("Render vectors " + (enabled ? "enabled" : "disabled")), false);
		return 1;
	}

	private static int setRenderStreamlines(CommandSourceStack source, boolean enabled) {
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getVisualizer().setRenderStreamlines(enabled);
		source.sendSuccess(() -> Component.literal("Render streamlines " + (enabled ? "enabled" : "disabled")), false);
		return 1;
	}

	private static int cinematicStatus(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			source.sendSuccess(() -> Component.literal(mod.getLocalWeatherData().stormVisualOverrideStatus(source.getLevel())), false);
			return 1;
		}
		AeroWindStatus status = AeroWindStatus.sample(minecraft);
		source.sendSuccess(
				() -> Component.literal(mod.getLocalWeatherData().stormVisualOverrideStatus(minecraft.level)
						+ String.format(
								java.util.Locale.ROOT,
								"; effective sample %.2f m/s mean=(%.2f, %.2f, %.2f) gust=(%.2f, %.2f, %.2f) source=%s/%s",
								status.effectiveSpeed(),
								status.meanX(),
								status.meanY(),
								status.meanZ(),
								status.gustX(),
								status.gustY(),
								status.gustZ(),
								status.level(),
								status.authority()
						)),
				false
		);
		return 1;
	}

	private static int setCinematicStorm(CommandSourceStack source, float intensity, int durationSeconds) {
		AeroClientMod mod = AeroClientMod.getInstance();
		Level world = source.getLevel();
		mod.getLocalWeatherData().setStormVisualOverride(intensity, durationSeconds, world);
		String duration = durationSeconds <= 0 ? "until cleared" : durationSeconds + " s";
		source.sendSuccess(
				() -> Component.literal(String.format(
						java.util.Locale.ROOT,
						"Cinematic storm visual override set to %.2f for %s",
						intensity,
						duration
				)),
				false
		);
		return 1;
	}

	private static int clearCinematicStorm(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getLocalWeatherData().clearStormVisualOverride();
		source.sendSuccess(() -> Component.literal("Cinematic storm visual override cleared"), false);
		return 1;
	}

	private record AeroWindStatus(
			float effectiveSpeed,
			float meanX,
			float meanY,
			float meanZ,
			float gustX,
			float gustY,
			float gustZ,
			String level,
			String authority
	) {
		private static AeroWindStatus sample(Minecraft minecraft) {
			var sample = AeroClientMod.sampleFlow(
					minecraft.level,
					minecraft.player.position().add(0.0, 1.2, 0.0),
					SamplePolicy.CLIENT_LOCAL_PREFERRED
			);
			return new AeroWindStatus(
					(float) sample.effectiveVelocity().length(),
					sample.velocityX(),
					sample.velocityY(),
					sample.velocityZ(),
					sample.gustX(),
					sample.gustY(),
					sample.gustZ(),
					sample.level().name(),
					sample.authority().name()
			);
		}
	}
}
