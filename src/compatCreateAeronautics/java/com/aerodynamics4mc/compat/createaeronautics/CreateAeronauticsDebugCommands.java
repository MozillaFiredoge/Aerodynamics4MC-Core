package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import com.aerodynamics4mc.api.AeroAirfoilProfile;
import com.aerodynamics4mc.api.AeroL2ForceMoment;
import com.aerodynamics4mc.api.AeroL2Request;
import com.aerodynamics4mc.api.AeroL2Result;
import com.aerodynamics4mc.api.AeroPolarRequest;
import com.aerodynamics4mc.api.AeroPolarResult;
import com.aerodynamics4mc.api.AeroPolarSample;
import com.aerodynamics4mc.api.AeroPolarTable;
import com.aerodynamics4mc.api.AeroSurfaceDescriptor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.aerodynamics4mc.runtime.AeroServerRuntime.feedback;

public final class CreateAeronauticsDebugCommands {
	private static final int DEFAULT_GRID_SIZE = 64;
	private static final int MIN_GRID_SIZE = 4;
	private static final int MAX_GRID_SIZE = 256;
	private static final int DEFAULT_SOLVE_STEPS = 0;
	private static final int MAX_SOLVE_STEPS = 4096;
	private static final int DEFAULT_POLAR_STEPS_PER_SAMPLE = 200;
	private static final double DEFAULT_POLAR_MIN_ALPHA = -20.0;
	private static final double DEFAULT_POLAR_MAX_ALPHA = 25.0;
	private static final double DEFAULT_POLAR_ALPHA_STEP = 5.0;
	private static final int MAX_POLAR_ROWS_TO_PRINT = 32;
	private static final CreateAeronauticsSubLevelAdapter ADAPTER = new CreateAeronauticsSubLevelAdapter();
	private static final CreateAeronauticsWingScanner WING_SCANNER = new CreateAeronauticsWingScanner();
	private static final CreateAeronauticsPolarCache POLAR_CACHE = CreateAeronauticsPolarCache.INSTANCE;

	private CreateAeronauticsDebugCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("aero_ca")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("scan_here")
						.executes(ctx -> scanHere(ctx.getSource(), DEFAULT_GRID_SIZE, DEFAULT_SOLVE_STEPS))
						.then(Commands.argument("grid", IntegerArgumentType.integer(MIN_GRID_SIZE, MAX_GRID_SIZE))
								.executes(ctx -> scanHere(
										ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "grid"),
										DEFAULT_SOLVE_STEPS
								))
								.then(Commands.argument("solve_steps", IntegerArgumentType.integer(0, MAX_SOLVE_STEPS))
										.executes(ctx -> scanHere(
												ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "grid"),
												IntegerArgumentType.getInteger(ctx, "solve_steps")
										))
								)
						)
					)
					.then(Commands.literal("wing_scan_here")
							.executes(ctx -> wingScanHere(ctx.getSource()))
					)
					.then(Commands.literal("wing_polar_here")
							.executes(ctx -> wingPolarHere(
									ctx.getSource(),
									DEFAULT_GRID_SIZE,
									DEFAULT_POLAR_STEPS_PER_SAMPLE,
									DEFAULT_POLAR_MIN_ALPHA,
									DEFAULT_POLAR_MAX_ALPHA,
									DEFAULT_POLAR_ALPHA_STEP
							))
							.then(Commands.argument("grid", IntegerArgumentType.integer(MIN_GRID_SIZE, MAX_GRID_SIZE))
									.executes(ctx -> wingPolarHere(
											ctx.getSource(),
											IntegerArgumentType.getInteger(ctx, "grid"),
											DEFAULT_POLAR_STEPS_PER_SAMPLE,
											DEFAULT_POLAR_MIN_ALPHA,
											DEFAULT_POLAR_MAX_ALPHA,
											DEFAULT_POLAR_ALPHA_STEP
									))
									.then(Commands.argument("steps_per_sample", IntegerArgumentType.integer(1, MAX_SOLVE_STEPS))
											.executes(ctx -> wingPolarHere(
													ctx.getSource(),
													IntegerArgumentType.getInteger(ctx, "grid"),
													IntegerArgumentType.getInteger(ctx, "steps_per_sample"),
													DEFAULT_POLAR_MIN_ALPHA,
													DEFAULT_POLAR_MAX_ALPHA,
													DEFAULT_POLAR_ALPHA_STEP
											))
											.then(Commands.argument("min_alpha", DoubleArgumentType.doubleArg(-90.0, 90.0))
													.then(Commands.argument("max_alpha", DoubleArgumentType.doubleArg(-90.0, 90.0))
															.then(Commands.argument("step_alpha", DoubleArgumentType.doubleArg(0.5, 45.0))
																	.executes(ctx -> wingPolarHere(
																			ctx.getSource(),
																			IntegerArgumentType.getInteger(ctx, "grid"),
																			IntegerArgumentType.getInteger(ctx, "steps_per_sample"),
																			DoubleArgumentType.getDouble(ctx, "min_alpha"),
																			DoubleArgumentType.getDouble(ctx, "max_alpha"),
																			DoubleArgumentType.getDouble(ctx, "step_alpha")
																	))
															)
													)
											)
									)
							)
					)
					.then(Commands.literal("polar_cache")
							.executes(ctx -> polarCacheStatus(ctx.getSource()))
							.then(Commands.literal("status")
									.executes(ctx -> polarCacheStatus(ctx.getSource()))
							)
							.then(Commands.literal("clear")
									.executes(ctx -> polarCacheClear(ctx.getSource()))
							)
					)
					.then(Commands.literal("airfoil")
							.executes(ctx -> airfoilStatus(ctx.getSource()))
							.then(Commands.literal("status")
									.executes(ctx -> airfoilStatus(ctx.getSource()))
							)
							.then(Commands.literal("list")
									.executes(ctx -> airfoilList(ctx.getSource()))
							)
							.then(Commands.literal("use")
									.then(Commands.argument("id", StringArgumentType.greedyString())
											.executes(ctx -> airfoilUse(
													ctx.getSource(),
													StringArgumentType.getString(ctx, "id")
											))
									)
							)
							.then(Commands.literal("export")
									.then(Commands.argument("id", StringArgumentType.greedyString())
											.executes(ctx -> airfoilExport(
													ctx.getSource(),
													StringArgumentType.getString(ctx, "id")
											))
									)
							)
							.then(Commands.literal("import")
									.then(Commands.argument("path", StringArgumentType.greedyString())
											.executes(ctx -> airfoilImport(
													ctx.getSource(),
													StringArgumentType.getString(ctx, "path")
											))
									)
							)
					)
					.then(Commands.literal("flight_polar_status")
							.executes(ctx -> flightPolarStatus(ctx.getSource()))
					)
					.then(Commands.literal("flight_force")
							.executes(ctx -> flightForceStatus(ctx.getSource()))
							.then(Commands.literal("status")
									.executes(ctx -> flightForceStatus(ctx.getSource()))
							)
							.then(Commands.literal("enable")
									.executes(ctx -> setFlightForce(ctx.getSource(), true))
							)
							.then(Commands.literal("disable")
									.executes(ctx -> setFlightForce(ctx.getSource(), false))
							)
							.then(Commands.argument("enabled", BoolArgumentType.bool())
									.executes(ctx -> setFlightForce(
											ctx.getSource(),
											BoolArgumentType.getBool(ctx, "enabled")
									))
							)
					)
					.then(Commands.literal("flight_force_debug")
							.executes(ctx -> flightForceStatus(ctx.getSource()))
							.then(Commands.literal("status")
									.executes(ctx -> flightForceStatus(ctx.getSource()))
							)
							.then(Commands.literal("enable")
									.executes(ctx -> setFlightForce(ctx.getSource(), true))
							)
							.then(Commands.literal("disable")
									.executes(ctx -> setFlightForce(ctx.getSource(), false))
							)
							.then(Commands.argument("enabled", BoolArgumentType.bool())
									.executes(ctx -> setFlightForce(
											ctx.getSource(),
											BoolArgumentType.getBool(ctx, "enabled")
									))
							)
					)
			);
	}

	private static int wingPolarHere(
			CommandSourceStack source,
			int gridSize,
			int stepsPerSample,
			double minAlpha,
			double maxAlpha,
			double alphaStep
	) {
		if (maxAlpha < minAlpha) {
			feedback(source, "Polar angle range is invalid: max_alpha must be >= min_alpha");
			return 0;
		}

		ServerLevel level = source.getLevel();
		BlockPos pos = BlockPos.containing(source.getPosition());
		Object subLevel = findCommandServerSubLevel(source, level, pos);
		if (subLevel == null) {
			feedbackNoSubLevel(source, level, pos);
			return 0;
		}

		CreateAeronauticsWingScanner.WingScanResult scan = WING_SCANNER.scan(subLevel);
		if (!scan.hasSelectedGroup()) {
			feedback(source, "No A4MC airfoil wing blocks found; accepted=" + CreateAeronauticsWingScanner.keywordSummary());
			return 0;
		}

		CreateAeronauticsWingScanner.WingGroup group = scan.selectedGroup();
		AeroSurfaceDescriptor surface = group.surface();
		AeroPolarRequest request = AeroPolarRequest.builder(surface)
				.gridSize(gridSize)
				.stepsPerSample(stepsPerSample)
				.angleSweep(minAlpha, maxAlpha, alphaStep)
				.build();
		long startNanos = System.nanoTime();
		CreateAeronauticsPolarCache.LookupResult lookup = POLAR_CACHE.getOrGenerate(request);
		long elapsedNanos = System.nanoTime() - startNanos;

		feedback(source, "Create Aeronautics wing polar " + describeSubLevel(subLevel)
				+ " selected=" + group.index()
				+ " blocks=" + group.blockCount()
				+ " bounds=" + formatIntBounds(group.bounds()));
		return reportPolarResult(source, lookup, elapsedNanos);
	}

	private static int polarCacheStatus(CommandSourceStack source) {
		CreateAeronauticsPolarCache.CacheStats stats = POLAR_CACHE.stats();
		feedback(source, "Create Aeronautics polar cache entries=" + stats.entries()
				+ "/" + stats.maxEntries()
				+ " hits=" + stats.hits()
				+ " misses=" + stats.misses());
		return 1;
	}

	private static int polarCacheClear(CommandSourceStack source) {
		CreateAeronauticsPolarCache.CacheStats stats = POLAR_CACHE.clear();
		feedback(source, "Create Aeronautics polar cache cleared entries=" + stats.entries()
				+ "/" + stats.maxEntries()
				+ " hits=" + stats.hits()
				+ " misses=" + stats.misses());
		return 1;
	}

	private static int airfoilStatus(CommandSourceStack source) {
		AeroAirfoilDefinition selected = CreateAeronauticsAirfoilLibrary.selectedDefinition();
		feedback(source, "Create Aeronautics A4MC airfoil selected=" + selected.id()
				+ " name=\"" + selected.displayName() + "\" "
				+ profileSummary(selected.profile())
				+ " definitions=" + CreateAeronauticsAirfoilLibrary.definitions().size());
		feedback(source, "Airfoil JSON root=" + airfoilRoot(source));
		return 1;
	}

	private static int airfoilList(CommandSourceStack source) {
		A4mcId selectedId = CreateAeronauticsAirfoilLibrary.selectedId();
		List<AeroAirfoilDefinition> definitions = CreateAeronauticsAirfoilLibrary.definitions();
		feedback(source, "Create Aeronautics A4MC airfoils definitions=" + definitions.size()
				+ " selected=" + selectedId);
		for (AeroAirfoilDefinition definition : definitions) {
			String marker = definition.id().equals(selectedId) ? "* " : "- ";
			feedback(source, marker + definition.id()
					+ " name=\"" + definition.displayName() + "\" "
					+ profileSummary(definition.profile())
					+ " coordinates=" + definition.coordinates().size());
		}
		return 1;
	}

	private static int airfoilUse(CommandSourceStack source, String idText) {
		A4mcId id = parseAirfoilId(source, idText);
		if (id == null) {
			return 0;
		}
		if (!CreateAeronauticsAirfoilLibrary.select(id)) {
			feedback(source, "Unknown A4MC airfoil " + id + "; run /aero_ca airfoil list");
			return 0;
		}
		AeroAirfoilDefinition selected = CreateAeronauticsAirfoilLibrary.selectedDefinition();
		feedback(source, "Create Aeronautics A4MC airfoil selected=" + selected.id()
				+ " name=\"" + selected.displayName() + "\" "
				+ profileSummary(selected.profile()));
		CreateAeronauticsAirfoilSync.broadcast(source.getServer());
		return 1;
	}

	private static int airfoilExport(CommandSourceStack source, String idText) {
		A4mcId id = parseAirfoilId(source, idText);
		if (id == null) {
			return 0;
		}
		AeroAirfoilDefinition definition = CreateAeronauticsAirfoilLibrary.find(id).orElse(null);
		if (definition == null) {
			feedback(source, "Unknown A4MC airfoil " + id + "; run /aero_ca airfoil list");
			return 0;
		}
		try {
			CreateAeronauticsAirfoilDiskStore.ExportResult result =
					CreateAeronauticsAirfoilDiskStore.exportDefinition(source.getServer(), definition);
			feedback(source, "Exported A4MC airfoil " + id + " to " + result.outputPath());
			return 1;
		} catch (IOException | IllegalArgumentException e) {
			feedback(source, "Failed to export A4MC airfoil " + id + ": " + e.getMessage());
			return 0;
		}
	}

	private static int airfoilImport(CommandSourceStack source, String pathText) {
		feedback(source, "Runtime A4MC airfoil import is disabled.");
		feedback(source, "Place JSON files under " + airfoilRoot(source)
				+ " and restart the server/client; requested path was \"" + pathText + "\".");
		return 0;
	}

	private static int flightPolarStatus(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		BlockPos pos = BlockPos.containing(source.getPosition());
		Object subLevel = findCommandServerSubLevel(source, level, pos);
		List<String> lines = subLevel == null
				? CreateAeronauticsFlightPolarService.INSTANCE.statusLines()
				: CreateAeronauticsFlightPolarService.INSTANCE.statusLines(level, subLevel);
		for (String line : lines) {
			feedback(source, line);
		}
		return 1;
	}

	private static int flightForceStatus(CommandSourceStack source) {
		boolean enabled = CreateAeronauticsFlightPolarService.INSTANCE.forceApplyEnabled();
		feedback(source, "Create Aeronautics A4MC wing force apply=" + (enabled ? "enabled" : "disabled"));
		return 1;
	}

	private static int setFlightForce(CommandSourceStack source, boolean enabled) {
		CreateAeronauticsFlightPolarService.INSTANCE.setForceApplyEnabled(enabled);
		feedback(source, "Create Aeronautics A4MC wing force apply=" + (enabled ? "enabled" : "disabled"));
		if (enabled) {
			feedback(source, "A4MC airfoil wing lift and drag are queued on Sable physics steps; Aeronautics propulsion is not overridden.");
		}
		return 1;
	}

	private static int wingScanHere(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		BlockPos pos = BlockPos.containing(source.getPosition());
		Object subLevel = findCommandServerSubLevel(source, level, pos);
		if (subLevel == null) {
			feedbackNoSubLevel(source, level, pos);
			return 0;
		}

		CreateAeronauticsWingScanner.WingScanResult scan = WING_SCANNER.scan(subLevel);
		feedback(source, "Create Aeronautics wing scan " + describeSubLevel(subLevel)
				+ " plot=" + formatIntBounds(scan.plotBounds()));
		if (!scan.hasSelectedGroup()) {
			feedback(source, "No A4MC airfoil wing blocks found; accepted=" + CreateAeronauticsWingScanner.keywordSummary());
			return 0;
		}

		CreateAeronauticsWingScanner.WingGroup group = scan.selectedGroup();
		AeroSurfaceDescriptor surface = group.surface();
		feedback(source, "Wing groups=" + scan.groups().size()
				+ " selected=" + group.index()
				+ " blocks=" + group.blockCount()
				+ " bounds=" + formatIntBounds(group.bounds()));
		feedback(source, "Wing surface id=" + surface.id()
				+ " profile=" + surface.airfoilProfile().id()
				+ " hash=" + surface.shapeHash());
		feedback(source, "Wing geometry span=" + format3(surface.spanMeters()) + "m"
				+ " chord=" + format3(surface.chordMeters()) + "m"
				+ " area=" + format3(surface.areaSquareMeters()) + "m^2"
				+ " aspect=" + format3(surface.aspectRatio())
				+ " mac=" + format3(surface.meanAerodynamicChordMeters()) + "m");
		feedback(source, "Wing frame origin=" + formatVec(surface.localOriginMeters())
				+ " chordDir=" + formatVec(surface.chordDirection())
				+ " spanDir=" + formatVec(surface.spanDirection())
				+ " normal=" + formatVec(surface.normalDirection()));
		return 1;
	}

	private static int scanHere(CommandSourceStack source, int gridSize, int solveSteps) {
		ServerLevel level = source.getLevel();
		BlockPos pos = BlockPos.containing(source.getPosition());
		Object subLevel = findCommandServerSubLevel(source, level, pos);
		if (subLevel == null) {
			feedbackNoSubLevel(source, level, pos);
			return 0;
		}

		CreateAeronauticsSubLevelAdapter.ScanResult scan = ADAPTER.scan(subLevel, gridSize);
		AeroL2Request request = scan.request();
		CreateAeronauticsSubLevelAdapter.SubLevelMaskFrame frame = scan.referenceFrame();
		feedback(source, "Create Aeronautics mask " + describeSubLevel(subLevel));
		feedback(source, "Mask grid=" + gridSize
				+ " cells=" + request.cells()
				+ " dx=" + format3(frame.cellSizeMeters()) + "m"
				+ " solidBlocks=" + frame.solidBlocks()
				+ " solidCells=" + frame.solidCells()
				+ " fill=" + format2(100.0 * frame.solidCells() / Math.max(1, request.cells())) + "%");
		feedback(source, "Mask body=" + formatIntBounds(
				frame.bodyMinX(), frame.bodyMinY(), frame.bodyMinZ(),
				frame.bodyMaxX(), frame.bodyMaxY(), frame.bodyMaxZ()
		) + " domain=" + formatBounds(
				frame.domainMinX(), frame.domainMinY(), frame.domainMinZ(),
				frame.domainMaxX(), frame.domainMaxY(), frame.domainMaxZ()
		));

		if (solveSteps <= 0) {
			feedback(source, "Mask scan complete; pass solve_steps > 0 to run an on-demand L2 wind tunnel.");
			return 1;
		}

		AeroL2Request solveRequest = withSolveOptions(request, solveSteps);
		long startNanos = System.nanoTime();
		CreateAeronauticsWindTunnelService.SubmissionResult submission =
				CreateAeronauticsWindTunnelService.INSTANCE.submit(level.dimension(), solveRequest);
		long elapsedNanos = System.nanoTime() - startNanos;
		if (!submission.accepted()) {
			feedback(source, "L2 wind tunnel busy for world " + submission.busyWorldKey());
			return 0;
		}
		reportSolveResult(source, submission.result(), solveSteps, elapsedNanos);
		return submission.result() != null && submission.result().succeeded() ? 1 : 0;
	}

	private static int reportPolarResult(
			CommandSourceStack source,
			CreateAeronauticsPolarCache.LookupResult lookup,
			long elapsedNanos
	) {
		if (lookup == null) {
			feedback(source, "Polar generation returned no cache lookup result");
			return 0;
		}
		AeroPolarResult result = lookup.result();
		if (result == null) {
			feedback(source, "Polar generation returned no result");
			return 0;
		}
		feedback(source, "Polar status=" + result.status()
				+ " elapsedMs=" + format3(elapsedNanos / 1_000_000.0)
				+ " cache=" + (lookup.cacheHit() ? "hit" : "miss")
				+ " key=" + lookup.keyHash()
				+ " entries=" + lookup.cacheSize()
				+ " hits=" + lookup.hits()
				+ " misses=" + lookup.misses()
				+ " runtime=" + result.runtimeInfo());
		if (!result.succeeded()) {
			feedback(source, "Polar generation failed: " + result.message());
			return 0;
		}
		if (!result.hasTable()) {
			feedback(source, "Polar generation succeeded without a table");
			return 0;
		}

		AeroPolarTable table = result.table();
		AeroSurfaceDescriptor surface = table.surface();
		feedback(source, "Polar table surface=" + surface.id()
				+ " profile=" + surface.airfoilProfile().id()
				+ " samples=" + table.samples().size()
				+ " tableHash=" + table.tableHash()
				+ " solver=" + table.solverId());
		int printedRows = 0;
		for (AeroPolarSample sample : table.samples()) {
			if (printedRows >= MAX_POLAR_ROWS_TO_PRINT) {
				break;
			}
			feedback(source, "AoA=" + formatSigned3(sample.angleOfAttackDegrees()) + "deg"
					+ " Cl=" + format4(sample.liftCoefficient())
					+ " Cd=" + format4(sample.dragCoefficient())
					+ " Cm=" + format4(sample.momentCoefficient()));
			printedRows++;
		}
		if (printedRows < table.samples().size()) {
			feedback(source, "Polar output truncated: printed " + printedRows + " of " + table.samples().size() + " rows");
		}
		return 1;
	}

	private static AeroL2Request withSolveOptions(AeroL2Request request, int steps) {
		return AeroL2Request.builder(request.nx(), request.ny(), request.nz())
				.cellSizeMeters(request.dxMeters())
				.timeStepSeconds(request.dtSeconds())
				.steps(steps)
				.sampleStride(request.sampleStride())
				.inlet(request.inletVx(), request.inletVy(), request.inletVz())
				.air(request.densityKgM3(), request.kinematicViscosityM2S())
				.solidMask(request.solidMask())
				.outputFlowAtlas(false)
				.forceMomentReference(request.referenceX(), request.referenceY(), request.referenceZ())
				.build();
	}

	private static void reportSolveResult(
			CommandSourceStack source,
			AeroL2Result result,
			int solveSteps,
			long elapsedNanos
	) {
		if (result == null) {
			feedback(source, "L2 solve returned no result");
			return;
		}
		feedback(source, "L2 solve status=" + result.status()
				+ " steps=" + solveSteps
				+ " elapsedMs=" + format3(elapsedNanos / 1_000_000.0)
				+ " runtime=" + result.runtimeInfo());
		if (!result.succeeded()) {
			feedback(source, "L2 solve failed: " + result.message());
			return;
		}
		if (!result.hasForceMoment()) {
			feedback(source, "L2 solve succeeded without force/moment output");
			return;
		}
		AeroL2ForceMoment forceMoment = result.forceMoment();
		feedback(source, "L2 force=" + formatVec(forceMoment.forceX(), forceMoment.forceY(), forceMoment.forceZ())
				+ " moment=" + formatVec(forceMoment.momentX(), forceMoment.momentY(), forceMoment.momentZ())
				+ " cop=" + formatVec(
						forceMoment.centerOfPressureX(),
						forceMoment.centerOfPressureY(),
						forceMoment.centerOfPressureZ()
				));
	}

	private static Object findCommandServerSubLevel(CommandSourceStack source, ServerLevel level, BlockPos pos) {
		try {
			Object helper = sableHelper();
			Entity entity = source.getEntity();
			if (entity != null) {
				for (String methodName : new String[] {
						"getTrackingOrVehicleSubLevel",
						"getTrackingSubLevel",
						"getVehicleSubLevel"
				}) {
					Object subLevel = invokeEntitySubLevelQuery(helper, methodName, entity);
					if (subLevel != null && isServerSubLevel(subLevel)) {
						return subLevel;
					}
				}
			}

			Object containing = invokeContaining(helper, level, pos);
			if (containing != null && isServerSubLevel(containing)) {
				return containing;
			}
			return null;
		} catch (ReflectiveOperationException | LinkageError e) {
			throw new IllegalStateException("Unable to query Sable sublevels", e);
		}
	}

	private static Object sableHelper() throws ReflectiveOperationException {
		Class<?> sableClass = Class.forName("dev.ryanhcode.sable.Sable", false, CreateAeronauticsDebugCommands.class.getClassLoader());
		Field helperField = sableClass.getField("HELPER");
		return helperField.get(null);
	}

	private static Object invokeEntitySubLevelQuery(Object helper, String methodName, Entity entity)
			throws InvocationTargetException, IllegalAccessException {
		Objects.requireNonNull(helper, "helper");
		Objects.requireNonNull(entity, "entity");
		for (Method method : helper.getClass().getMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
				continue;
			}
			Class<?> parameterType = method.getParameterTypes()[0];
			if (!parameterType.isAssignableFrom(entity.getClass())) {
				continue;
			}
			return method.invoke(helper, entity);
		}
		return null;
	}

	private static Object invokeContaining(Object helper, ServerLevel level, BlockPos pos)
			throws InvocationTargetException, IllegalAccessException {
		Objects.requireNonNull(helper, "helper");
		for (Method method : helper.getClass().getMethods()) {
			if (!method.getName().equals("getContaining") || method.getParameterCount() != 2) {
				continue;
			}
			Class<?>[] parameterTypes = method.getParameterTypes();
			if (!parameterTypes[0].isAssignableFrom(level.getClass())) {
				continue;
			}
			if (!parameterTypes[1].isAssignableFrom(pos.getClass())) {
				continue;
			}
			return method.invoke(helper, level, pos);
		}
		throw new IllegalStateException("Sable HELPER has no getContaining(Level, BlockPos-compatible) method");
	}

	private static boolean isServerSubLevel(Object subLevel) throws ClassNotFoundException {
		Class<?> serverSubLevelClass = Class.forName(
				"dev.ryanhcode.sable.sublevel.ServerSubLevel",
				false,
				CreateAeronauticsDebugCommands.class.getClassLoader()
		);
		return serverSubLevelClass.isInstance(subLevel);
	}

	private static String describeSubLevel(Object subLevel) {
		return "subLevel=" + invokeStringOrUnknown(subLevel, "getName")
				+ " uuid=" + invokeStringOrUnknown(subLevel, "getUniqueId");
	}

	private static void feedbackNoSubLevel(CommandSourceStack source, ServerLevel level, BlockPos pos) {
		ChunkPos chunk = new ChunkPos(pos);
		Entity entity = source.getEntity();
		String entityText = entity == null ? "none" : entity.getType().toString();
		feedback(source, "Create Aeronautics debug: no ServerSubLevel for source"
				+ " pos=" + formatBlockPos(pos)
				+ " chunk=(" + chunk.x + ", " + chunk.z + ")"
				+ " level=" + level.dimension().identifier()
				+ " entity=" + entityText);
	}

	private static String invokeStringOrUnknown(Object target, String methodName) {
		try {
			Method method = target.getClass().getMethod(methodName);
			Object value = method.invoke(target);
			return value == null ? "null" : value.toString();
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return "unknown";
		}
	}

	private static A4mcId parseAirfoilId(CommandSourceStack source, String idText) {
		try {
			return A4mcId.parse(idText);
		} catch (IllegalArgumentException e) {
			feedback(source, "Invalid A4MC airfoil id \"" + idText + "\": " + e.getMessage());
			return null;
		}
	}

	private static Path airfoilRoot(CommandSourceStack source) {
		return CreateAeronauticsAirfoilDiskStore.root(source.getServer());
	}

	private static String profileSummary(AeroAirfoilProfile profile) {
		return "kind=" + profile.kind()
				+ " camber=" + format2(100.0 * profile.maxCamberRatio()) + "%"
				+ " camberPos=" + format2(100.0 * profile.maxCamberPositionRatio()) + "%"
				+ " thickness=" + format2(100.0 * profile.thicknessRatio()) + "%";
	}

	private static String formatBlockPos(BlockPos pos) {
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	private static String formatIntBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return "[" + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + "]";
	}

	private static String formatIntBounds(CreateAeronauticsWingScanner.BlockBounds bounds) {
		return formatIntBounds(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}

	private static String formatBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		return "[" + format2(minX) + "," + format2(minY) + "," + format2(minZ)
				+ " -> " + format2(maxX) + "," + format2(maxY) + "," + format2(maxZ) + "]";
	}

	private static String formatVec(double x, double y, double z) {
		return "(" + format3(x) + ", " + format3(y) + ", " + format3(z) + ")";
	}

	private static String formatVec(A4mcVec3 vec) {
		return formatVec(vec.x(), vec.y(), vec.z());
	}

	private static String format2(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	private static String format3(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private static String formatSigned3(double value) {
		return String.format(Locale.ROOT, "%+.3f", value);
	}

	private static String format4(double value) {
		return String.format(Locale.ROOT, "%.4f", value);
	}
}
