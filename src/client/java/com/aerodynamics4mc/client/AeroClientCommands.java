package com.aerodynamics4mc.client;

import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftVectors;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

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
						.then(Commands.literal("focus")
								.executes(ctx -> requestClientL2Focus(ctx.getSource(), 20 * 5))
								.then(Commands.argument("duration_ticks", IntegerArgumentType.integer(1, 20 * 60 * 5))
										.executes(ctx -> requestClientL2Focus(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "duration_ticks")))))
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
												.executes(ctx -> setRenderStreamlines(ctx.getSource(), false))))
								.then(qRenderCommands())
								.then(qCriterionRenderCommands()))
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

	private static LiteralArgumentBuilder<CommandSourceStack> qRenderCommands() {
		return Commands.literal("q")
				.then(Commands.literal("status")
						.executes(ctx -> qCriterionIsoStatus(ctx.getSource())))
				.then(Commands.literal("on")
						.executes(ctx -> setRenderQCriterionIso(ctx.getSource(), true)))
				.then(Commands.literal("off")
						.executes(ctx -> setRenderQCriterionIso(ctx.getSource(), false)))
				.then(Commands.literal("clear")
						.executes(ctx -> clearQCriterionIso(ctx.getSource())))
				.then(qDisplayCommands())
				.then(qInletCommands())
				.then(qThresholdCommands())
				.then(Commands.literal("test")
						.then(qTestHereCommands())
						.then(qTestRegionCommands()))
				.then(Commands.literal("solve")
						.then(qSolveHereCommands(false))
						.then(qSolveRegionCommands(false))
						.then(Commands.literal("entities")
								.then(qSolveHereCommands(true))
								.then(qSolveRegionCommands(true))
								.then(qSolveTargetCommands())))
				.then(Commands.literal("live")
						.then(qLiveHereCommands(false))
						.then(qLiveRegionCommands(false))
						.then(Commands.literal("entities")
								.then(qLiveHereCommands(true))
								.then(qLiveRegionCommands(true))
								.then(qLiveTargetCommands()))
						.then(Commands.literal("stop")
								.executes(ctx -> stopQCriterionIsoLive(ctx.getSource()))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qTestHereCommands() {
		return Commands.literal("here")
				.executes(ctx -> showQCriterionIsoTestHere(ctx.getSource(), 16))
				.then(Commands.argument("size", IntegerArgumentType.integer(4, 256))
						.executes(ctx -> showQCriterionIsoTestHere(
								ctx.getSource(),
								IntegerArgumentType.getInteger(ctx, "size")
						)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qTestRegionCommands() {
		return Commands.literal("region")
				.then(Commands.argument("x", IntegerArgumentType.integer())
						.then(Commands.argument("y", IntegerArgumentType.integer())
								.then(Commands.argument("z", IntegerArgumentType.integer())
										.then(Commands.argument("size", IntegerArgumentType.integer(4, 256))
												.executes(ctx -> showQCriterionIsoTestRegion(
														ctx.getSource(),
														IntegerArgumentType.getInteger(ctx, "x"),
														IntegerArgumentType.getInteger(ctx, "y"),
														IntegerArgumentType.getInteger(ctx, "z"),
														IntegerArgumentType.getInteger(ctx, "size")
												))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qDisplayCommands() {
		return Commands.literal("display")
				.executes(ctx -> qCriterionIsoStatus(ctx.getSource()))
				.then(Commands.literal("surface")
						.executes(ctx -> setQCriterionIsoDisplay(ctx.getSource(), "surface")))
				.then(Commands.literal("voxels")
						.executes(ctx -> setQCriterionIsoDisplay(ctx.getSource(), "voxels")));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qInletCommands() {
		return Commands.literal("inlet")
				.executes(ctx -> qCriterionIsoStatus(ctx.getSource()))
				.then(Commands.literal("noise")
						.then(Commands.argument(
										"amplitude",
										FloatArgumentType.floatArg(0.0f, ClientL2Solver.DEBUG_Q_INLET_NOISE_MAX_AMPLITUDE)
								)
								.executes(ctx -> setQCriterionIsoInletNoise(
										ctx.getSource(),
										FloatArgumentType.getFloat(ctx, "amplitude")
								))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qThresholdCommands() {
		return Commands.literal("threshold")
				.executes(ctx -> qCriterionIsoStatus(ctx.getSource()))
				.then(Commands.literal("auto")
						.then(Commands.argument("fraction", FloatArgumentType.floatArg(0.000001f, 1.0f))
								.executes(ctx -> setQCriterionIsoAutoThreshold(
										ctx.getSource(),
										FloatArgumentType.getFloat(ctx, "fraction")
								))))
				.then(Commands.literal("fixed")
						.then(Commands.argument("value", FloatArgumentType.floatArg(0.0f, 1.0e6f))
								.executes(ctx -> setQCriterionIsoFixedThreshold(
										ctx.getSource(),
										FloatArgumentType.getFloat(ctx, "value")
								))))
				.then(Commands.literal("wall")
						.then(Commands.argument("cells", IntegerArgumentType.integer(0, 8))
								.executes(ctx -> setQCriterionIsoWallRejectCells(
										ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "cells")
								))))
				.then(Commands.literal("reset")
						.executes(ctx -> resetQCriterionIsoThreshold(ctx.getSource())));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qCriterionRenderCommands() {
		return Commands.literal("qcriterion")
				.then(Commands.literal("on")
						.executes(ctx -> setRenderQCriterionIso(ctx.getSource(), true)))
				.then(Commands.literal("off")
						.executes(ctx -> setRenderQCriterionIso(ctx.getSource(), false)))
				.then(Commands.literal("clear")
						.executes(ctx -> clearQCriterionIso(ctx.getSource())));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qSolveHereCommands(boolean includeEntities) {
		return Commands.literal("here")
				.executes(ctx -> solveQCriterionIsoHere(
						ctx.getSource(),
						ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_SIZE,
						ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_STEPS,
						includeEntities
				))
				.then(Commands.argument(
								"size",
								IntegerArgumentType.integer(
										ClientL2Solver.DEBUG_Q_SOLVE_MIN_SIZE,
										ClientL2Solver.DEBUG_Q_SOLVE_MAX_SIZE
								)
						)
						.executes(ctx -> solveQCriterionIsoHere(
								ctx.getSource(),
								IntegerArgumentType.getInteger(ctx, "size"),
								ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_STEPS,
								includeEntities
						))
						.then(Commands.argument(
										"steps",
										IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_SOLVE_MAX_STEPS)
								)
								.executes(ctx -> solveQCriterionIsoHere(
										ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "size"),
										IntegerArgumentType.getInteger(ctx, "steps"),
										includeEntities
								))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qSolveRegionCommands(boolean includeEntities) {
		return Commands.literal("region")
				.then(Commands.argument("x", IntegerArgumentType.integer())
						.then(Commands.argument("y", IntegerArgumentType.integer())
								.then(Commands.argument("z", IntegerArgumentType.integer())
										.then(Commands.argument(
														"size",
														IntegerArgumentType.integer(
																ClientL2Solver.DEBUG_Q_SOLVE_MIN_SIZE,
																ClientL2Solver.DEBUG_Q_SOLVE_MAX_SIZE
														)
												)
												.executes(ctx -> solveQCriterionIsoRegion(
														ctx.getSource(),
														IntegerArgumentType.getInteger(ctx, "x"),
														IntegerArgumentType.getInteger(ctx, "y"),
														IntegerArgumentType.getInteger(ctx, "z"),
														IntegerArgumentType.getInteger(ctx, "size"),
														ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_STEPS,
														includeEntities
												))
												.then(Commands.argument(
																"steps",
																IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_SOLVE_MAX_STEPS)
														)
														.executes(ctx -> solveQCriterionIsoRegion(
																ctx.getSource(),
																IntegerArgumentType.getInteger(ctx, "x"),
																IntegerArgumentType.getInteger(ctx, "y"),
																IntegerArgumentType.getInteger(ctx, "z"),
																IntegerArgumentType.getInteger(ctx, "size"),
																IntegerArgumentType.getInteger(ctx, "steps"),
																includeEntities
														)))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qLiveHereCommands(boolean includeEntities) {
		return Commands.literal("here")
				.executes(ctx -> liveQCriterionIsoHere(
						ctx.getSource(),
						ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_SIZE,
						ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_STEPS_PER_FRAME,
						ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS,
						includeEntities
				))
				.then(Commands.argument(
								"size",
								IntegerArgumentType.integer(
										ClientL2Solver.DEBUG_Q_SOLVE_MIN_SIZE,
										ClientL2Solver.DEBUG_Q_SOLVE_MAX_SIZE
								)
						)
						.executes(ctx -> liveQCriterionIsoHere(
								ctx.getSource(),
								IntegerArgumentType.getInteger(ctx, "size"),
								ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_STEPS_PER_FRAME,
								ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS,
								includeEntities
						))
						.then(Commands.argument(
										"steps_per_frame",
										IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_LIVE_MAX_STEPS_PER_FRAME)
								)
								.executes(ctx -> liveQCriterionIsoHere(
										ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "size"),
										IntegerArgumentType.getInteger(ctx, "steps_per_frame"),
										ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS,
										includeEntities
								))
								.then(Commands.argument(
												"interval_ticks",
												IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_LIVE_MAX_INTERVAL_TICKS)
										)
										.executes(ctx -> liveQCriterionIsoHere(
												ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "size"),
												IntegerArgumentType.getInteger(ctx, "steps_per_frame"),
												IntegerArgumentType.getInteger(ctx, "interval_ticks"),
												includeEntities
										)))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qLiveRegionCommands(boolean includeEntities) {
		return Commands.literal("region")
				.then(Commands.argument("x", IntegerArgumentType.integer())
						.then(Commands.argument("y", IntegerArgumentType.integer())
								.then(Commands.argument("z", IntegerArgumentType.integer())
										.then(Commands.argument(
														"size",
														IntegerArgumentType.integer(
																ClientL2Solver.DEBUG_Q_SOLVE_MIN_SIZE,
																ClientL2Solver.DEBUG_Q_SOLVE_MAX_SIZE
														)
												)
												.executes(ctx -> liveQCriterionIsoRegion(
														ctx.getSource(),
														IntegerArgumentType.getInteger(ctx, "x"),
														IntegerArgumentType.getInteger(ctx, "y"),
														IntegerArgumentType.getInteger(ctx, "z"),
														IntegerArgumentType.getInteger(ctx, "size"),
														ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_STEPS_PER_FRAME,
														ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS,
														includeEntities
												))
												.then(Commands.argument(
																"steps_per_frame",
																IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_LIVE_MAX_STEPS_PER_FRAME)
														)
														.executes(ctx -> liveQCriterionIsoRegion(
																ctx.getSource(),
																IntegerArgumentType.getInteger(ctx, "x"),
																IntegerArgumentType.getInteger(ctx, "y"),
																IntegerArgumentType.getInteger(ctx, "z"),
																IntegerArgumentType.getInteger(ctx, "size"),
																IntegerArgumentType.getInteger(ctx, "steps_per_frame"),
																ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS,
																includeEntities
														))
														.then(Commands.argument(
																		"interval_ticks",
																		IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_LIVE_MAX_INTERVAL_TICKS)
																)
																.executes(ctx -> liveQCriterionIsoRegion(
																		ctx.getSource(),
																		IntegerArgumentType.getInteger(ctx, "x"),
																		IntegerArgumentType.getInteger(ctx, "y"),
																		IntegerArgumentType.getInteger(ctx, "z"),
																		IntegerArgumentType.getInteger(ctx, "size"),
																		IntegerArgumentType.getInteger(ctx, "steps_per_frame"),
																		IntegerArgumentType.getInteger(ctx, "interval_ticks"),
																		includeEntities
																))))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qSolveTargetCommands() {
		return Commands.literal("target")
				.then(qSolveTargetFineCommands())
				.executes(ctx -> solveQCriterionIsoTarget(
						ctx.getSource(),
						ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_SIZE,
						ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_STEPS
				))
				.then(Commands.argument(
								"size",
								IntegerArgumentType.integer(
										ClientL2Solver.DEBUG_Q_SOLVE_MIN_SIZE,
										ClientL2Solver.DEBUG_Q_SOLVE_MAX_SIZE
								)
						)
						.executes(ctx -> solveQCriterionIsoTarget(
								ctx.getSource(),
								IntegerArgumentType.getInteger(ctx, "size"),
								ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_STEPS
						))
						.then(Commands.argument(
										"steps",
										IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_SOLVE_MAX_STEPS)
								)
								.executes(ctx -> solveQCriterionIsoTarget(
										ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "size"),
										IntegerArgumentType.getInteger(ctx, "steps")
								))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qLiveTargetCommands() {
		return Commands.literal("target")
				.then(qLiveTargetFineCommands())
				.executes(ctx -> liveQCriterionIsoTarget(
						ctx.getSource(),
						ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_SIZE,
						ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_STEPS_PER_FRAME,
						ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS
				))
				.then(Commands.argument(
								"size",
								IntegerArgumentType.integer(
										ClientL2Solver.DEBUG_Q_SOLVE_MIN_SIZE,
										ClientL2Solver.DEBUG_Q_SOLVE_MAX_SIZE
								)
						)
						.executes(ctx -> liveQCriterionIsoTarget(
								ctx.getSource(),
								IntegerArgumentType.getInteger(ctx, "size"),
								ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_STEPS_PER_FRAME,
								ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS
						))
						.then(Commands.argument(
										"steps_per_frame",
										IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_LIVE_MAX_STEPS_PER_FRAME)
								)
								.executes(ctx -> liveQCriterionIsoTarget(
										ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "size"),
										IntegerArgumentType.getInteger(ctx, "steps_per_frame"),
										ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS
								))
								.then(Commands.argument(
												"interval_ticks",
												IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_LIVE_MAX_INTERVAL_TICKS)
										)
										.executes(ctx -> liveQCriterionIsoTarget(
												ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "size"),
												IntegerArgumentType.getInteger(ctx, "steps_per_frame"),
												IntegerArgumentType.getInteger(ctx, "interval_ticks")
										)))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qSolveTargetFineCommands() {
		return Commands.literal("fine")
				.executes(ctx -> solveQCriterionIsoTargetFine(
						ctx.getSource(),
						ClientL2Solver.DEBUG_Q_FINE_DEFAULT_GRID_SIZE,
						ClientL2Solver.DEBUG_Q_FINE_DEFAULT_SIZE_BLOCKS,
						ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_STEPS
				))
				.then(Commands.argument(
								"grid_cells",
								IntegerArgumentType.integer(
										ClientL2Solver.DEBUG_Q_FINE_MIN_GRID_SIZE,
										ClientL2Solver.DEBUG_Q_FINE_MAX_GRID_SIZE
								)
						)
						.executes(ctx -> solveQCriterionIsoTargetFine(
								ctx.getSource(),
								IntegerArgumentType.getInteger(ctx, "grid_cells"),
								ClientL2Solver.DEBUG_Q_FINE_DEFAULT_SIZE_BLOCKS,
								ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_STEPS
						))
						.then(Commands.argument(
										"size_blocks",
										IntegerArgumentType.integer(
												ClientL2Solver.DEBUG_Q_FINE_MIN_SIZE_BLOCKS,
												ClientL2Solver.DEBUG_Q_FINE_MAX_SIZE_BLOCKS
										)
								)
								.executes(ctx -> solveQCriterionIsoTargetFine(
										ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "grid_cells"),
										IntegerArgumentType.getInteger(ctx, "size_blocks"),
										ClientL2Solver.DEBUG_Q_SOLVE_DEFAULT_STEPS
								))
								.then(Commands.argument(
												"steps",
												IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_SOLVE_MAX_STEPS)
										)
										.executes(ctx -> solveQCriterionIsoTargetFine(
												ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "grid_cells"),
												IntegerArgumentType.getInteger(ctx, "size_blocks"),
												IntegerArgumentType.getInteger(ctx, "steps")
										)))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> qLiveTargetFineCommands() {
		return Commands.literal("fine")
				.executes(ctx -> liveQCriterionIsoTargetFine(
						ctx.getSource(),
						ClientL2Solver.DEBUG_Q_FINE_DEFAULT_GRID_SIZE,
						ClientL2Solver.DEBUG_Q_FINE_DEFAULT_SIZE_BLOCKS,
						ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_STEPS_PER_FRAME,
						ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS
				))
				.then(Commands.argument(
								"grid_cells",
								IntegerArgumentType.integer(
										ClientL2Solver.DEBUG_Q_FINE_MIN_GRID_SIZE,
										ClientL2Solver.DEBUG_Q_FINE_MAX_GRID_SIZE
								)
						)
						.executes(ctx -> liveQCriterionIsoTargetFine(
								ctx.getSource(),
								IntegerArgumentType.getInteger(ctx, "grid_cells"),
								ClientL2Solver.DEBUG_Q_FINE_DEFAULT_SIZE_BLOCKS,
								ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_STEPS_PER_FRAME,
								ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS
						))
						.then(Commands.argument(
										"size_blocks",
										IntegerArgumentType.integer(
												ClientL2Solver.DEBUG_Q_FINE_MIN_SIZE_BLOCKS,
												ClientL2Solver.DEBUG_Q_FINE_MAX_SIZE_BLOCKS
										)
								)
								.executes(ctx -> liveQCriterionIsoTargetFine(
										ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "grid_cells"),
										IntegerArgumentType.getInteger(ctx, "size_blocks"),
										ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_STEPS_PER_FRAME,
										ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS
								))
								.then(Commands.argument(
												"steps_per_frame",
												IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_LIVE_MAX_STEPS_PER_FRAME)
										)
										.executes(ctx -> liveQCriterionIsoTargetFine(
												ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "grid_cells"),
												IntegerArgumentType.getInteger(ctx, "size_blocks"),
												IntegerArgumentType.getInteger(ctx, "steps_per_frame"),
												ClientL2Solver.DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS
										))
										.then(Commands.argument(
														"interval_ticks",
														IntegerArgumentType.integer(1, ClientL2Solver.DEBUG_Q_LIVE_MAX_INTERVAL_TICKS)
												)
												.executes(ctx -> liveQCriterionIsoTargetFine(
														ctx.getSource(),
														IntegerArgumentType.getInteger(ctx, "grid_cells"),
														IntegerArgumentType.getInteger(ctx, "size_blocks"),
														IntegerArgumentType.getInteger(ctx, "steps_per_frame"),
														IntegerArgumentType.getInteger(ctx, "interval_ticks")
												))))));
	}

	private static int clientL2Status(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		source.sendSuccess(() -> Component.literal(mod.getLocalAirflowService().status() + " | " + mod.getClientL2Solver().status()), false);
		return 1;
	}

	private static int setClientL2Experimental(CommandSourceStack source, boolean enabled) {
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getLocalAirflowService().setEnabled(enabled);
		if (enabled) {
			mod.getVisualizer().clearRemoteFlowFields();
		}
		source.sendSuccess(() -> Component.literal("Client L2 on-demand local solve " + (enabled ? "enabled" : "disabled")), false);
		return 1;
	}

	private static int requestClientL2Focus(CommandSourceStack source, int durationTicks) {
		AeroClientMod mod = AeroClientMod.getInstance();
		if (!mod.getLocalAirflowService().isEnabled()) {
			source.sendFailure(Component.literal("Client L2 on-demand local solve is disabled"));
			return 0;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		mod.getLocalAirflowService().requestPatch(
				"command:focus",
				minecraft.level,
				minecraft.player.blockPosition(),
				32,
				durationTicks,
				"command_focus"
		);
		mod.getVisualizer().clearRemoteFlowFields();
		source.sendSuccess(() -> Component.literal("Requested client L2 focus patch for " + durationTicks + " ticks"), false);
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

	private static int setRenderQCriterionIso(CommandSourceStack source, boolean enabled) {
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getVisualizer().setRenderQCriterionIso(enabled);
		source.sendSuccess(() -> Component.literal("Render Q-criterion iso " + (enabled ? "enabled" : "disabled")), false);
		return 1;
	}

	private static int qCriterionIsoStatus(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		source.sendSuccess(() -> Component.literal(mod.getClientL2Solver().debugQIsoStatus()), false);
		return 1;
	}

	private static int setQCriterionIsoDisplay(CommandSourceStack source, String mode) {
		AeroClientMod mod = AeroClientMod.getInstance();
		String message = mod.getClientL2Solver().setDebugQIsoDisplayMode(mode);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int setQCriterionIsoInletNoise(CommandSourceStack source, float amplitude) {
		AeroClientMod mod = AeroClientMod.getInstance();
		String message = mod.getClientL2Solver().setDebugQInletNoiseAmplitude(amplitude);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int setQCriterionIsoAutoThreshold(CommandSourceStack source, float fraction) {
		AeroClientMod mod = AeroClientMod.getInstance();
		String message = mod.getClientL2Solver().setDebugQIsoAutoThresholdFraction(fraction);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int setQCriterionIsoFixedThreshold(CommandSourceStack source, float threshold) {
		AeroClientMod mod = AeroClientMod.getInstance();
		String message = mod.getClientL2Solver().setDebugQIsoFixedThreshold(threshold);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int setQCriterionIsoWallRejectCells(CommandSourceStack source, int cells) {
		AeroClientMod mod = AeroClientMod.getInstance();
		String message = mod.getClientL2Solver().setDebugQIsoSolidRejectCells(cells);
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int resetQCriterionIsoThreshold(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		String message = mod.getClientL2Solver().resetDebugQIsoThreshold();
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int showQCriterionIsoTestHere(CommandSourceStack source, int size) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		BlockPos playerPos = minecraft.player.blockPosition();
		BlockPos origin = playerPos.offset(-size / 2, -size / 2, -size / 2);
		return showQCriterionIsoTestRegion(source, origin.getX(), origin.getY(), origin.getZ(), size);
	}

	private static int showQCriterionIsoTestRegion(CommandSourceStack source, int x, int y, int z, int size) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		AeroClientMod mod = AeroClientMod.getInstance();
		BlockPos origin = new BlockPos(x, y, z);
		mod.getVisualizer().showDebugQCriterionIsoRegion(
				minecraft.level.dimension().identifier(),
				origin,
				size
		);
		source.sendSuccess(
				() -> Component.literal("Rendered debug Q iso sphere at region origin "
						+ origin.getX() + " " + origin.getY() + " " + origin.getZ()
						+ " size=" + size),
				false
		);
		return 1;
	}

	private static int solveQCriterionIsoHere(CommandSourceStack source, int size, int steps, boolean includeEntities) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		BlockPos playerPos = minecraft.player.blockPosition();
		BlockPos origin = playerPos.offset(-size / 2, -size / 2, -size / 2);
		return solveQCriterionIsoRegion(
				source,
				origin.getX(),
				origin.getY(),
				origin.getZ(),
				size,
				steps,
				includeEntities
		);
	}

	private static int solveQCriterionIsoRegion(
			CommandSourceStack source,
			int x,
			int y,
			int z,
			int size,
			int steps,
			boolean includeEntities
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getVisualizer().setRenderQCriterionIso(true);
		String message = mod.getClientL2Solver().requestDebugQCriterionIsoRegion(
				minecraft.level,
				new BlockPos(x, y, z),
				size,
				steps,
				includeEntities
		);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int solveQCriterionIsoTarget(CommandSourceStack source, int size, int steps) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		Entity target = findNearestQTargetEntity(minecraft, Math.max(16, size));
		if (target == null) {
			source.sendFailure(Component.literal("No nearby entity target found"));
			return 0;
		}
		BlockPos targetPos = target.blockPosition();
		BlockPos origin = targetPos.offset(-size / 2, -size / 2, -size / 2);
		return solveQCriterionIsoRegionTarget(
				source,
				origin.getX(),
				origin.getY(),
				origin.getZ(),
				size,
				steps,
				target.getUUID()
		);
	}

	private static int solveQCriterionIsoTargetFine(
			CommandSourceStack source,
			int gridCells,
			int sizeBlocks,
			int steps
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		Entity target = findNearestQTargetEntity(minecraft, Math.max(16, sizeBlocks * 2));
		if (target == null) {
			source.sendFailure(Component.literal("No nearby entity target found"));
			return 0;
		}
		BlockPos origin = target.blockPosition().offset(-sizeBlocks / 2, -sizeBlocks / 2, -sizeBlocks / 2);
		return solveQCriterionIsoRegionTargetFine(
				source,
				origin.getX(),
				origin.getY(),
				origin.getZ(),
				gridCells,
				sizeBlocks,
				steps,
				target.getUUID()
		);
	}

	private static int solveQCriterionIsoRegionTarget(
			CommandSourceStack source,
			int x,
			int y,
			int z,
			int size,
			int steps,
			UUID targetEntityId
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getVisualizer().setRenderQCriterionIso(true);
		String message = mod.getClientL2Solver().requestDebugQCriterionIsoRegion(
				minecraft.level,
				new BlockPos(x, y, z),
				size,
				steps,
				true,
				targetEntityId
		);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int solveQCriterionIsoRegionTargetFine(
			CommandSourceStack source,
			int x,
			int y,
			int z,
			int gridCells,
			int sizeBlocks,
			int steps,
			UUID targetEntityId
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getVisualizer().setRenderQCriterionIso(true);
		float cellSizeBlocks = sizeBlocks / (float) Math.max(1, gridCells);
		String message = mod.getClientL2Solver().requestDebugQCriterionIsoRegion(
				minecraft.level,
				new BlockPos(x, y, z),
				gridCells,
				steps,
				true,
				targetEntityId,
				cellSizeBlocks
		);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int liveQCriterionIsoHere(
			CommandSourceStack source,
			int size,
			int stepsPerFrame,
			int intervalTicks,
			boolean includeEntities
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		BlockPos playerPos = minecraft.player.blockPosition();
		BlockPos origin = playerPos.offset(-size / 2, -size / 2, -size / 2);
		return liveQCriterionIsoRegion(
				source,
				origin.getX(),
				origin.getY(),
				origin.getZ(),
				size,
				stepsPerFrame,
				intervalTicks,
				includeEntities
		);
	}

	private static int liveQCriterionIsoRegion(
			CommandSourceStack source,
			int x,
			int y,
			int z,
			int size,
			int stepsPerFrame,
			int intervalTicks,
			boolean includeEntities
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getVisualizer().setRenderQCriterionIso(true);
		String message = mod.getClientL2Solver().requestDebugQCriterionIsoLiveRegion(
				minecraft.level,
				new BlockPos(x, y, z),
				size,
				stepsPerFrame,
				intervalTicks,
				includeEntities
		);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int liveQCriterionIsoTarget(
			CommandSourceStack source,
			int size,
			int stepsPerFrame,
			int intervalTicks
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		Entity target = findNearestQTargetEntity(minecraft, Math.max(16, size));
		if (target == null) {
			source.sendFailure(Component.literal("No nearby entity target found"));
			return 0;
		}
		BlockPos targetPos = target.blockPosition();
		BlockPos origin = targetPos.offset(-size / 2, -size / 2, -size / 2);
		return liveQCriterionIsoRegionTarget(
				source,
				origin.getX(),
				origin.getY(),
				origin.getZ(),
				size,
				stepsPerFrame,
				intervalTicks,
				target.getUUID()
		);
	}

	private static int liveQCriterionIsoTargetFine(
			CommandSourceStack source,
			int gridCells,
			int sizeBlocks,
			int stepsPerFrame,
			int intervalTicks
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		Entity target = findNearestQTargetEntity(minecraft, Math.max(16, sizeBlocks * 2));
		if (target == null) {
			source.sendFailure(Component.literal("No nearby entity target found"));
			return 0;
		}
		BlockPos origin = target.blockPosition().offset(-sizeBlocks / 2, -sizeBlocks / 2, -sizeBlocks / 2);
		return liveQCriterionIsoRegionTargetFine(
				source,
				origin.getX(),
				origin.getY(),
				origin.getZ(),
				gridCells,
				sizeBlocks,
				stepsPerFrame,
				intervalTicks,
				target.getUUID()
		);
	}

	private static int liveQCriterionIsoRegionTarget(
			CommandSourceStack source,
			int x,
			int y,
			int z,
			int size,
			int stepsPerFrame,
			int intervalTicks,
			UUID targetEntityId
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getVisualizer().setRenderQCriterionIso(true);
		String message = mod.getClientL2Solver().requestDebugQCriterionIsoLiveRegion(
				minecraft.level,
				new BlockPos(x, y, z),
				size,
				stepsPerFrame,
				intervalTicks,
				true,
				targetEntityId
		);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int liveQCriterionIsoRegionTargetFine(
			CommandSourceStack source,
			int x,
			int y,
			int z,
			int gridCells,
			int sizeBlocks,
			int stepsPerFrame,
			int intervalTicks,
			UUID targetEntityId
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			source.sendFailure(Component.literal("Client world is not available"));
			return 0;
		}
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getVisualizer().setRenderQCriterionIso(true);
		float cellSizeBlocks = sizeBlocks / (float) Math.max(1, gridCells);
		String message = mod.getClientL2Solver().requestDebugQCriterionIsoLiveRegion(
				minecraft.level,
				new BlockPos(x, y, z),
				gridCells,
				stepsPerFrame,
				intervalTicks,
				true,
				targetEntityId,
				cellSizeBlocks
		);
		if (message.contains("failed")) {
			source.sendFailure(Component.literal(message));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static Entity findNearestQTargetEntity(Minecraft minecraft, int rangeBlocks) {
		BlockPos playerPos = minecraft.player.blockPosition();
		int range = Math.max(1, rangeBlocks);
		AABB searchBox = new AABB(
				playerPos.getX() - range,
				playerPos.getY() - range,
				playerPos.getZ() - range,
				playerPos.getX() + range + 1,
				playerPos.getY() + range + 1,
				playerPos.getZ() + range + 1
		);
		Entity nearest = null;
		double nearestDistanceSq = Double.POSITIVE_INFINITY;
		for (Entity candidate : minecraft.level.getEntities(
				(Entity) null,
				searchBox,
				entity -> entity != minecraft.player && !entity.isRemoved() && !entity.isSpectator()
		)) {
			BlockPos entityPos = candidate.blockPosition();
			double dx = entityPos.getX() - playerPos.getX();
			double dy = entityPos.getY() - playerPos.getY();
			double dz = entityPos.getZ() - playerPos.getZ();
			double distanceSq = dx * dx + dy * dy + dz * dz;
			if (distanceSq < nearestDistanceSq) {
				nearestDistanceSq = distanceSq;
				nearest = candidate;
			}
		}
		return nearest;
	}

	private static int stopQCriterionIsoLive(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		String message = mod.getClientL2Solver().stopDebugQCriterionIsoLiveRegion(true);
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int clearQCriterionIso(CommandSourceStack source) {
		AeroClientMod mod = AeroClientMod.getInstance();
		mod.getClientL2Solver().stopDebugQCriterionIsoLiveRegion(true);
		mod.getVisualizer().clearQCriterionIsoFields();
		source.sendSuccess(() -> Component.literal("Cleared Q-criterion iso fields"), false);
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
					(float) AeroMinecraftVectors.effectiveVelocity(sample).length(),
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
