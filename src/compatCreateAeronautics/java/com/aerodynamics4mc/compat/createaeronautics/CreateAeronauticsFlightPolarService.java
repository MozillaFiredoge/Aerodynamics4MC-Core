package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroAirfoilProfile;
import com.aerodynamics4mc.api.AeroPolarRequest;
import com.aerodynamics4mc.api.AeroPolarResult;
import com.aerodynamics4mc.api.AeroPolarSample;
import com.aerodynamics4mc.api.AeroPolarTable;
import com.aerodynamics4mc.api.AeroSurfaceDescriptor;
import com.aerodynamics4mc.api.AeroWindApi;
import com.aerodynamics4mc.api.GameplayWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class CreateAeronauticsFlightPolarService {
	public static final CreateAeronauticsFlightPolarService INSTANCE = new CreateAeronauticsFlightPolarService();

	private static final int MAX_SUBLEVELS_PER_PASS = 8;
	private static final double STATUS_SAMPLE_SECONDS = 1.0 / 20.0;
	private static final double MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND = 0.05;
	private static final double MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND = 40.0;
	private static final double AIR_DENSITY_KG_PER_CUBIC_METER = 1.225;
	private static final double MIN_AERODYNAMIC_IMPULSE = 1.0e-6;
	private static final double MAX_LINEAR_IMPULSE_PER_SQUARE_METER = 25.0;
	private static final double MAX_ANGULAR_IMPULSE_PER_AREA_CHORD = 25.0;
	private static final double GEOMETRIC_TORQUE_RESPONSE = 0.20;
	private static final double MAX_GEOMETRIC_ANGULAR_IMPULSE_PER_SQUARE_METER = 5.0;
	private static final double POLAR_ANGLE_EPSILON_DEGREES = 1.0e-6;
	private static final int GROUND_EFFECT_MAX_SCAN_BLOCKS = 24;
	private static final double GROUND_EFFECT_MAX_HEIGHT_TO_SPAN = 0.75;
	private static final double GROUND_EFFECT_MAX_LIFT_BOOST = 0.18;
	private static final double GROUND_EFFECT_MAX_INDUCED_DRAG_REDUCTION = 0.30;
	private static final double SPANWISE_PARASITE_DRAG_COEFFICIENT = 0.04;
	private static final double TURBULENCE_DRAG_BOOST = 0.22;
	private static final double TURBULENCE_MAX_LIFT_LOSS = 0.08;
	private static final double WIND_SHEAR_RESPONSE_PER_BLOCK = 4.0;
	private static final double WIND_SHEAR_DRAG_BOOST = 0.18;
	private static final double WIND_SHEAR_MAX_LIFT_LOSS = 0.06;

	private final CreateAeronauticsWingScanner wingScanner = new CreateAeronauticsWingScanner();
	private final CreateAeronauticsPolarCache polarCache = CreateAeronauticsPolarCache.INSTANCE;

	private volatile boolean forceApplyEnabled = true;
	private volatile long physicsTicks;
	private double disabledStatusSeconds;
	private Snapshot lastSnapshot = Snapshot.empty();

	private CreateAeronauticsFlightPolarService() {
	}

	public void physicsTick(ServerLevel world, double timeStepSeconds) {
		if (world == null || !Double.isFinite(timeStepSeconds) || timeStepSeconds <= 0.0) {
			return;
		}
		physicsTicks++;
		boolean providerApplyEnabled = forceApplyEnabled;
		if (!providerApplyEnabled) {
			disabledStatusSeconds += timeStepSeconds;
			if (disabledStatusSeconds < 1.0) {
				return;
			}
			disabledStatusSeconds = 0.0;
		} else {
			disabledStatusSeconds = 0.0;
		}

		int scannedSubLevels = 0;
		int wingSubLevels = 0;
		Snapshot newestSnapshot = null;
		List<Object> candidates = candidateServerSubLevels(world);
		if (candidates.isEmpty()) {
			if (!lastSnapshot.hasWing()) {
				lastSnapshot = Snapshot.noWing(0, 0, providerApplyEnabled);
			}
			return;
		}
		for (Object subLevel : candidates) {
			if (scannedSubLevels >= MAX_SUBLEVELS_PER_PASS) {
				break;
			}
			scannedSubLevels++;
			if (isRemoved(subLevel)) {
				continue;
			}
			Snapshot snapshot = sampleSubLevel(world, subLevel, false, providerApplyEnabled, timeStepSeconds);
			if (snapshot.hasWing()) {
				wingSubLevels++;
				newestSnapshot = snapshot;
			}
		}

		if (newestSnapshot == null) {
			lastSnapshot = Snapshot.noWing(scannedSubLevels, wingSubLevels, providerApplyEnabled);
		} else {
			lastSnapshot = newestSnapshot.withPassCounts(scannedSubLevels, wingSubLevels);
		}
	}

	public boolean forceApplyEnabled() {
		return forceApplyEnabled;
	}

	public void setForceApplyEnabled(boolean enabled) {
		forceApplyEnabled = enabled;
	}

	public Snapshot lastSnapshot() {
		return lastSnapshot;
	}

	public void contributeProviderLiftAndDrag(
			BlockSubLevelLiftProvider.LiftProviderContext context,
			ServerSubLevel subLevel,
			Pose3d providerPose,
			double timeStepSeconds,
			Vector3dc linearVelocity,
			Vector3dc angularVelocity,
			Vector3d linearImpulseAccumulator,
			Vector3d angularImpulseAccumulator,
			BlockSubLevelLiftProvider.LiftProviderGroup group
	) {
		if (!forceApplyEnabled
				|| context == null
				|| subLevel == null
				|| linearImpulseAccumulator == null
				|| angularImpulseAccumulator == null
				|| !Double.isFinite(timeStepSeconds)
				|| timeStepSeconds <= 0.0) {
			return;
		}

		AeroSurfaceDescriptor surface = providerSurface(context, subLevel, providerPose);
		FlightFrame frame = sampleProviderFlightFrame(subLevel, surface, linearVelocity, angularVelocity);
		double relativeWindSpeed = frame.airfoilWind().length();
		if (relativeWindSpeed < MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND
				|| relativeWindSpeed > MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND) {
			return;
		}

		double angleOfAttackDegrees = angleOfAttackDegrees(
				frame.chordDirection(),
				frame.normalDirection(),
				frame.airfoilWind()
		);
		AeroPolarResult result = polarCache.getOrGenerate(providerPolarRequest(surface)).result();
		if (result == null || !result.succeeded() || !result.hasTable()) {
			return;
		}
		AeroPolarSample sample = lookupCoveredPolarSample(result.table(), angleOfAttackDegrees);
		if (sample == null) {
			return;
		}

		ForcePreview forcePreview = previewForce(surface, frame, sample, groundEffect(subLevel.getLevel(), surface, frame));
		accumulateProviderForce(
				subLevel,
				forcePreview,
				timeStepSeconds,
				linearImpulseAccumulator,
				angularImpulseAccumulator,
				group
		);
	}

	public List<String> statusLines() {
		return statusLines(lastSnapshot);
	}

	public List<String> statusLines(ServerLevel world, Object subLevel) {
		if (world == null || subLevel == null) {
			return statusLines();
		}
		Snapshot snapshot = sampleSubLevel(world, subLevel, false, forceApplyEnabled, STATUS_SAMPLE_SECONDS)
				.withPassCounts(1, 0);
		if (snapshot.hasWing()) {
			snapshot = snapshot.withPassCounts(1, 1);
		}
		return statusLines(snapshot);
	}

	private List<String> statusLines(Snapshot snapshot) {
		if (!snapshot.hasWing()) {
			return List.of("Create Aeronautics flight polar: no wing sample yet"
					+ " scanned=" + snapshot.scannedSubLevels()
					+ " wingSubLevels=" + snapshot.wingSubLevels()
					+ " physicsTicks=" + physicsTicks);
		}
		List<String> lines = new ArrayList<>();
		lines.add("Create Aeronautics flight polar subLevel=" + snapshot.subLevelName()
				+ " uuid=" + snapshot.subLevelUuid()
				+ " world=" + snapshot.worldId()
				+ " forceApply=" + (snapshot.forceApplyEnabled() ? "enabled" : "disabled")
				+ " surfaces=" + snapshot.surfaceCount()
				+ " active=" + snapshot.activeSurfaceCount()
				+ " scanned=" + snapshot.scannedSubLevels()
				+ " wingSubLevels=" + snapshot.wingSubLevels());
		lines.add("Flight polar wing total blocks=" + snapshot.blockCount()
				+ " profile=" + snapshot.profileId()
				+ " shape=" + snapshot.shapeHash()
				+ " table=" + snapshot.tableHash()
				+ " cache=" + (snapshot.cacheHit() ? "hit" : "miss")
				+ " key=" + snapshot.cacheKeyHash());
		lines.add("Flight polar frame=" + snapshot.frameSource()
				+ " samplePos=" + formatVec(snapshot.samplePosition())
				+ " localEnvWind=" + formatVec(snapshot.environmentWind())
				+ " localBodyVelocity=" + formatVec(snapshot.bodyVelocity()));
		lines.add("Flight polar localAirfoilWind=" + formatVec(snapshot.relativeWind())
				+ " speed=" + format3(snapshot.relativeWindSpeedMetersPerSecond()) + "m/s"
				+ " aoa=" + formatSigned3(snapshot.angleOfAttackDegrees()) + "deg");
		if (snapshot.hasPolarSample()) {
			lines.add("Flight polar sample Cl=" + format4(snapshot.liftCoefficient())
					+ " Cd=" + format4(snapshot.dragCoefficient())
					+ " Cm=" + format4(snapshot.momentCoefficient()));
			lines.add("Flight polar force preview q=" + format2(snapshot.dynamicPressurePascals()) + "Pa"
					+ " area=" + format3(snapshot.referenceAreaSquareMeters()) + "m^2"
					+ " lift=" + format2(snapshot.liftNewtons()) + "N"
					+ " drag=" + format2(snapshot.dragNewtons()) + "N");
			if (!snapshot.contributions().isEmpty()) {
				WingContribution primary = snapshot.contributions().get(0);
				lines.add("Flight polar modifiers ground=" + formatGroundEffect(primary)
						+ " wind=" + formatWindEnvironment(primary.windEnvironment())
						+ " spanDrag=" + format2(primary.spanwiseDragNewtons()) + "N");
			}
			lines.add("Flight polar force=" + formatVec(snapshot.forcePreview())
					+ "N moment=" + formatVec(snapshot.momentPreview()) + "Nm"
					+ " pitchMoment=" + format2(snapshot.pitchingMomentNewtonMeters()) + "Nm");
			if (snapshot.forceApplyEnabled()) {
				lines.add("Flight polar apply " + snapshot.forceApplyStatus()
						+ " localPoint=" + formatVec(snapshot.forceApplicationPoint())
						+ " linearImpulse=" + formatVec(snapshot.linearImpulse()) + "Ns"
						+ " angularImpulse=" + formatVec(snapshot.angularImpulse()) + "Nms");
			}
		} else if (snapshot.forceApplyStatus().startsWith("polar_")) {
			lines.add("Flight polar sample unavailable: " + snapshot.forceApplyStatus());
		} else if (snapshot.relativeWindSpeedMetersPerSecond() > MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND) {
			lines.add("Flight polar sample unavailable: relative wind exceeds the stability limit "
					+ format3(MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND) + "m/s");
		} else if (snapshot.relativeWindSpeedMetersPerSecond() >= MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND) {
			lines.add("Flight polar sample unavailable: aoa is outside the generated polar table range");
		} else {
			lines.add("Flight polar sample idle: relative wind is below "
					+ format3(MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND) + "m/s");
		}
		int contributionLimit = Math.min(6, snapshot.contributions().size());
		for (int i = 0; i < contributionLimit; i++) {
			WingContribution contribution = snapshot.contributions().get(i);
			String sampleStatus;
			if (contribution.hasPolarSample()) {
				sampleStatus = "Cl=" + format4(contribution.liftCoefficient())
						+ " Cd=" + format4(contribution.dragCoefficient())
						+ " Cm=" + format4(contribution.momentCoefficient());
			} else if (contribution.relativeWindSpeedMetersPerSecond() > MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND) {
				sampleStatus = "speed_limit";
			} else if (contribution.relativeWindSpeedMetersPerSecond() >= MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND) {
				sampleStatus = "aoa_out_of_range";
			} else {
				sampleStatus = "idle";
			}
			lines.add("Wing surface #" + contribution.groupIndex()
					+ " blocks=" + contribution.blockCount()
					+ " speed=" + format3(contribution.relativeWindSpeedMetersPerSecond()) + "m/s"
					+ " aoa=" + formatSigned3(contribution.angleOfAttackDegrees()) + "deg"
					+ " " + sampleStatus
					+ " ge=" + format3(contribution.groundEffectStrength())
					+ " turb=" + format3(contribution.windEnvironment().turbulenceIntensity())
					+ " shear=" + format4(contribution.windEnvironment().windShearMagnitudePerBlock())
					+ " spanDrag=" + format2(contribution.spanwiseDragNewtons()) + "N"
					+ " force=" + formatVec(contribution.forcePreview()) + "N"
					+ " apply=" + contribution.forceApplyStatus());
		}
		if (snapshot.contributions().size() > contributionLimit) {
			lines.add("Wing surface list truncated: " + contributionLimit + "/" + snapshot.contributions().size());
		}
		return List.copyOf(lines);
	}

	private Snapshot sampleSubLevel(
			ServerLevel world,
			Object subLevel,
			boolean applyForces,
			boolean reportApplyEnabled,
			double timeStepSeconds
	) {
		CreateAeronauticsWingScanner.WingScanResult scan = wingScanner.scan(subLevel);
		if (!scan.hasSelectedGroup()) {
			return Snapshot.noWing(0, 0, reportApplyEnabled);
		}

		List<WingContribution> contributions = new ArrayList<>();
		for (CreateAeronauticsWingScanner.WingGroup group : scan.groups()) {
			WingContribution contribution = sampleWingGroup(
					world,
					subLevel,
					group,
					applyForces,
					reportApplyEnabled,
					timeStepSeconds
			);
			if (contribution != null) {
				contributions.add(contribution);
			}
		}
		if (contributions.isEmpty()) {
			return Snapshot.noWing(0, 0, reportApplyEnabled);
		}

		WingContribution primary = primaryContribution(contributions, scan.selectedGroup().index());
		int totalBlockCount = 0;
		int activeSurfaceCount = 0;
		double totalArea = 0.0;
		double totalAreaChord = 0.0;
		double totalLift = 0.0;
		double totalDrag = 0.0;
		double totalPitchingMoment = 0.0;
		A4mcVec3 totalForce = A4mcVec3.ZERO;
		A4mcVec3 totalMoment = A4mcVec3.ZERO;
		A4mcVec3 totalLinearImpulse = A4mcVec3.ZERO;
		A4mcVec3 totalAngularImpulse = A4mcVec3.ZERO;
		boolean anyForceApplied = false;
		for (WingContribution contribution : contributions) {
			totalBlockCount += contribution.blockCount();
			if (contribution.hasPolarSample()) {
				activeSurfaceCount++;
			}
			totalArea += contribution.referenceAreaSquareMeters();
			totalAreaChord += contribution.referenceAreaSquareMeters() * contribution.meanAerodynamicChordMeters();
			totalLift += contribution.liftNewtons();
			totalDrag += contribution.dragNewtons();
			totalPitchingMoment += contribution.pitchingMomentNewtonMeters();
			totalForce = totalForce.add(contribution.forcePreview());
			totalMoment = totalMoment.add(contribution.momentPreview());
			totalLinearImpulse = totalLinearImpulse.add(contribution.linearImpulse());
			totalAngularImpulse = totalAngularImpulse.add(contribution.angularImpulse());
			anyForceApplied |= contribution.forceApplied();
		}
		double meanChord = totalArea <= 0.0 ? primary.meanAerodynamicChordMeters() : totalAreaChord / totalArea;
		return new Snapshot(
				true,
				invokeStringOrUnknown(subLevel, "getName"),
				invokeStringOrUnknown(subLevel, "getUniqueId"),
				world.dimension().identifier().toString(),
				totalBlockCount,
				primary.profileId(),
				"surfaces/" + contributions.size(),
				primary.tableHash(),
				primary.cacheKeyHash(),
				primary.cacheHit(),
				primary.samplePosition(),
				primary.environmentWind(),
				primary.bodyVelocity(),
				primary.relativeWind(),
				primary.relativeWindSpeedMetersPerSecond(),
				primary.angleOfAttackDegrees(),
				activeSurfaceCount > 0,
				primary.hasPolarSample() ? primary.liftCoefficient() : 0.0,
				primary.hasPolarSample() ? primary.dragCoefficient() : 0.0,
				primary.hasPolarSample() ? primary.momentCoefficient() : 0.0,
				primary.dynamicPressurePascals(),
				totalArea,
				meanChord,
				totalLift,
				totalDrag,
				totalPitchingMoment,
				totalForce,
				totalMoment,
				reportApplyEnabled,
				anyForceApplied,
				aggregateForceStatus(contributions, reportApplyEnabled),
				primary.forceApplicationPoint(),
				totalLinearImpulse,
				totalAngularImpulse,
				primary.frameSource(),
				contributions.size(),
				activeSurfaceCount,
				contributions,
				0,
				0
		);
	}

	private WingContribution sampleWingGroup(
			ServerLevel world,
			Object subLevel,
			CreateAeronauticsWingScanner.WingGroup group,
			boolean applyForces,
			boolean reportApplyEnabled,
			double timeStepSeconds
	) {
		AeroSurfaceDescriptor surface = group.surface();
		AeroPolarRequest request = AeroPolarRequest.builder(surface)
				.gridSize(AeroPolarRequest.DEFAULT_GRID_SIZE)
				.stepsPerSample(AeroPolarRequest.DEFAULT_STEPS_PER_SAMPLE)
				.angleSweep(
						AeroPolarRequest.DEFAULT_MIN_ANGLE_OF_ATTACK_DEGREES,
						AeroPolarRequest.DEFAULT_MAX_ANGLE_OF_ATTACK_DEGREES,
						AeroPolarRequest.DEFAULT_ANGLE_STEP_DEGREES
				)
				.build();
		CreateAeronauticsPolarCache.LookupResult lookup = polarCache.getOrGenerate(request);
		AeroPolarResult result = lookup.result();
		if (result == null || !result.succeeded() || !result.hasTable()) {
			return unavailableWingContribution(world, subLevel, group, lookup, result, reportApplyEnabled);
		}

		AeroPolarTable table = result.table();
		FlightFrame frame = sampleFlightFrame(world, subLevel, surface);
		double relativeWindSpeed = frame.airfoilWind().length();
		boolean hasFlow = relativeWindSpeed >= MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND;
		boolean stableSpeed = relativeWindSpeed <= MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND;
		double angleOfAttackDegrees = hasFlow
				? angleOfAttackDegrees(frame.chordDirection(), frame.normalDirection(), frame.airfoilWind())
				: 0.0;
		AeroPolarSample sample = hasFlow && stableSpeed ? lookupCoveredPolarSample(table, angleOfAttackDegrees) : null;
		boolean hasPolarSample = sample != null;
		ForcePreview forcePreview = previewForce(surface, frame, sample, groundEffect(world, surface, frame));
		ForceApplication forceApplication = applyForces && hasPolarSample
				? applyAerodynamicForce(subLevel, forcePreview, timeStepSeconds)
				: ForceApplication.inactive(
						forceStatusWithoutApplication(hasPolarSample, reportApplyEnabled),
						forcePreview.applicationPoint()
				);
		return new WingContribution(
				group.index(),
				group.blockCount(),
				surface.airfoilProfile().id().toString(),
				surface.shapeHash(),
				table.tableHash(),
				lookup.keyHash(),
				lookup.cacheHit(),
				frame.samplePosition(),
				frame.environmentWind(),
				frame.windEnvironment(),
				frame.bodyVelocity(),
				frame.airfoilWind(),
				relativeWindSpeed,
				angleOfAttackDegrees,
				hasPolarSample,
				sample == null ? 0.0 : sample.liftCoefficient(),
				sample == null ? 0.0 : sample.dragCoefficient(),
				sample == null ? 0.0 : sample.momentCoefficient(),
				forcePreview.groundClearanceMeters(),
				forcePreview.groundEffectStrength(),
				forcePreview.groundLiftMultiplier(),
				forcePreview.groundDragMultiplier(),
				forcePreview.spanwiseDragNewtons(),
				forcePreview.dynamicPressurePascals(),
				forcePreview.referenceAreaSquareMeters(),
				forcePreview.meanAerodynamicChordMeters(),
				forcePreview.liftNewtons(),
				forcePreview.dragNewtons(),
				forcePreview.pitchingMomentNewtonMeters(),
				forcePreview.force(),
				forcePreview.moment(),
				reportApplyEnabled,
				forceApplication.applied(),
				forceApplication.status(),
				forceApplication.applicationPoint(),
				forceApplication.linearImpulse(),
				forceApplication.angularImpulse(),
				frame.source()
		);
	}

	private WingContribution unavailableWingContribution(
			ServerLevel world,
			Object subLevel,
			CreateAeronauticsWingScanner.WingGroup group,
			CreateAeronauticsPolarCache.LookupResult lookup,
			AeroPolarResult result,
			boolean reportApplyEnabled
	) {
		AeroSurfaceDescriptor surface = group.surface();
		FlightFrame frame = sampleFlightFrame(world, subLevel, surface);
		double relativeWindSpeed = frame.airfoilWind().length();
		double angleOfAttackDegrees = relativeWindSpeed >= MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND
				? angleOfAttackDegrees(frame.chordDirection(), frame.normalDirection(), frame.airfoilWind())
				: 0.0;
		ForcePreview forcePreview = ForcePreview.zero(
				surface.areaSquareMeters(),
				surface.meanAerodynamicChordMeters(),
				frame.localSamplePosition()
		);
		String status = polarUnavailableStatus(result);
		return new WingContribution(
				group.index(),
				group.blockCount(),
				surface.airfoilProfile().id().toString(),
				surface.shapeHash(),
				"",
				lookup == null ? "" : lookup.keyHash(),
				lookup != null && lookup.cacheHit(),
				frame.samplePosition(),
				frame.environmentWind(),
				frame.windEnvironment(),
				frame.bodyVelocity(),
				frame.airfoilWind(),
				relativeWindSpeed,
				angleOfAttackDegrees,
				false,
				0.0,
				0.0,
				0.0,
				forcePreview.groundClearanceMeters(),
				forcePreview.groundEffectStrength(),
				forcePreview.groundLiftMultiplier(),
				forcePreview.groundDragMultiplier(),
				forcePreview.spanwiseDragNewtons(),
				0.0,
				forcePreview.referenceAreaSquareMeters(),
				forcePreview.meanAerodynamicChordMeters(),
				0.0,
				0.0,
				0.0,
				forcePreview.force(),
				forcePreview.moment(),
				reportApplyEnabled,
				false,
				status,
				forcePreview.applicationPoint(),
				A4mcVec3.ZERO,
				A4mcVec3.ZERO,
				frame.source()
		);
	}

	private static AeroSurfaceDescriptor providerSurface(
			BlockSubLevelLiftProvider.LiftProviderContext context,
			ServerSubLevel subLevel,
			Pose3d providerPose
	) {
		BlockPos pos = context.pos();
		BlockState state = context.state();
		A4mcVec3 localOrigin = A4mcVec3.of(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		A4mcVec3 chordDirection = directionVector(AirfoilWingBlock.chordDirection(state));
		A4mcVec3 spanDirection = directionVector(AirfoilWingBlock.spanDirection(state));
		A4mcVec3 normalDirection = directionVector(AirfoilWingBlock.normalDirection(state));
		if (providerPose != null) {
			localOrigin = transformPosition(providerPose, localOrigin);
			chordDirection = transformNormal(providerPose, chordDirection);
			spanDirection = transformNormal(providerPose, spanDirection);
			normalDirection = transformNormal(providerPose, normalDirection);
		}
		return new AeroSurfaceDescriptor(
				A4mcId.of("aerodynamics4mc", "create_aeronautics/provider_airfoil_wing"),
				"provider_airfoil_wing_block_v1",
				providerProfile(context, subLevel),
				1.0,
				1.0,
				1.0,
				0.0,
				localOrigin,
				chordDirection,
				spanDirection,
				normalDirection
		);
	}

	private static AeroAirfoilProfile providerProfile(
			BlockSubLevelLiftProvider.LiftProviderContext context,
			ServerSubLevel subLevel
	) {
		BlockEntity blockEntity = subLevel.getLevel().getBlockEntity(context.pos());
		if (blockEntity instanceof AirfoilWingBlockEntity wing) {
			return CreateAeronauticsAirfoilLibrary.profileOrSelected(wing.airfoilId());
		}
		return CreateAeronauticsAirfoilLibrary.selectedProfile();
	}

	private static AeroPolarRequest providerPolarRequest(AeroSurfaceDescriptor surface) {
		return AeroPolarRequest.builder(surface)
				.gridSize(AeroPolarRequest.DEFAULT_GRID_SIZE)
				.stepsPerSample(AeroPolarRequest.DEFAULT_STEPS_PER_SAMPLE)
				.angleSweep(
						AeroPolarRequest.DEFAULT_MIN_ANGLE_OF_ATTACK_DEGREES,
						AeroPolarRequest.DEFAULT_MAX_ANGLE_OF_ATTACK_DEGREES,
						AeroPolarRequest.DEFAULT_ANGLE_STEP_DEGREES
				)
				.build();
	}

	private static FlightFrame sampleProviderFlightFrame(
			ServerSubLevel subLevel,
			AeroSurfaceDescriptor surface,
			Vector3dc linearVelocity,
			Vector3dc angularVelocity
	) {
		Pose3d logicalPose = subLevel.logicalPose();
		A4mcVec3 localSamplePosition = surface.localOriginMeters();
		A4mcVec3 samplePosition = transformPosition(logicalPose, localSamplePosition);
		WindEnvironmentSample windEnvironment = sampleEnvironmentWind(subLevel.getLevel(), samplePosition);
		A4mcVec3 environmentWind = transformNormalInverse(logicalPose, windEnvironment.velocity());
		A4mcVec3 bodyVelocity = pointBodyVelocityLocal(
				logicalPose,
				localSamplePosition,
				linearVelocity,
				angularVelocity
		);
		A4mcVec3 relativeWind = environmentWind.subtract(bodyVelocity);
		A4mcVec3 spanDirection = normalizeOr(surface.spanDirection(), A4mcVec3.of(1.0, 0.0, 0.0));
		A4mcVec3 airfoilWind = removeSpanwiseFlow(relativeWind, spanDirection);
		return new FlightFrame(
				samplePosition,
				localSamplePosition,
				environmentWind,
				bodyVelocity,
				relativeWind,
				airfoilWind,
				normalizeOr(surface.chordDirection(), A4mcVec3.of(0.0, 0.0, -1.0)),
				spanDirection,
				normalizeOr(surface.normalDirection(), A4mcVec3.of(0.0, 1.0, 0.0)),
				windEnvironment,
				"pos=sable_pose,dir=provider_local,wind=sable_pose_inverse"
						+ ",windEnv=" + windEnvironment.source()
						+ ",body=sable_velocity"
		);
	}

	private static void accumulateProviderForce(
			ServerSubLevel subLevel,
			ForcePreview forcePreview,
			double timeStepSeconds,
			Vector3d linearImpulseAccumulator,
			Vector3d angularImpulseAccumulator,
			BlockSubLevelLiftProvider.LiftProviderGroup group
	) {
		A4mcVec3 linearImpulse = forcePreview.force().scale(timeStepSeconds);
		A4mcVec3 applicationPoint = forcePreview.applicationPoint();
		A4mcVec3 angularImpulse = forcePreview.moment().scale(timeStepSeconds);
		double area = Math.max(1.0, forcePreview.referenceAreaSquareMeters());
		double areaChord = Math.max(1.0, area * forcePreview.meanAerodynamicChordMeters());
		linearImpulse = clampLength(linearImpulse, area * MAX_LINEAR_IMPULSE_PER_SQUARE_METER);
		A4mcVec3 centerOfMass = centerOfMassLocal(subLevel);
		if (centerOfMass != null) {
			A4mcVec3 leverArm = applicationPoint.subtract(centerOfMass);
			A4mcVec3 geometricAngularImpulse = cross(leverArm, linearImpulse).scale(GEOMETRIC_TORQUE_RESPONSE);
			geometricAngularImpulse = clampLength(geometricAngularImpulse, area * MAX_GEOMETRIC_ANGULAR_IMPULSE_PER_SQUARE_METER);
			angularImpulse = angularImpulse.add(geometricAngularImpulse);
		}
		angularImpulse = clampLength(angularImpulse, areaChord * MAX_ANGULAR_IMPULSE_PER_AREA_CHORD);
		if (linearImpulse.length() <= MIN_AERODYNAMIC_IMPULSE && angularImpulse.length() <= MIN_AERODYNAMIC_IMPULSE) {
			return;
		}

		linearImpulseAccumulator.add(toJoml(linearImpulse));
		angularImpulseAccumulator.add(toJoml(angularImpulse));
		recordProviderForceGroup(
				group,
				applicationPoint,
				forcePreview.liftForce().scale(timeStepSeconds),
				forcePreview.dragForce().scale(timeStepSeconds)
		);
	}

	private static void recordProviderForceGroup(
			BlockSubLevelLiftProvider.LiftProviderGroup group,
			A4mcVec3 applicationPoint,
			A4mcVec3 liftImpulse,
			A4mcVec3 dragImpulse
	) {
		if (group == null) {
			return;
		}
		recordProviderPointForce(
				group.totalLift(),
				group.liftCenter(),
				applicationPoint,
				liftImpulse,
				true,
				group
		);
		recordProviderPointForce(
				group.totalDrag(),
				group.dragCenter(),
				applicationPoint,
				dragImpulse,
				false,
				group
		);
	}

	private static void recordProviderPointForce(
			Vector3d total,
			Vector3d center,
			A4mcVec3 applicationPoint,
			A4mcVec3 impulse,
			boolean lift,
			BlockSubLevelLiftProvider.LiftProviderGroup group
	) {
		double strength = impulse.length();
		if (strength <= MIN_AERODYNAMIC_IMPULSE) {
			return;
		}
		total.add(toJoml(impulse));
		center.fma(strength, toJoml(applicationPoint));
		if (lift) {
			group.totalLiftStrength += strength;
		} else {
			group.totalDragStrength += strength;
		}
	}

	private static ForceApplication applyAerodynamicForce(Object subLevel, ForcePreview forcePreview, double timeStepSeconds) {
		A4mcVec3 linearImpulse = forcePreview.force().scale(timeStepSeconds);
		A4mcVec3 applicationPoint = forcePreview.applicationPoint();
		A4mcVec3 angularImpulse = forcePreview.moment().scale(timeStepSeconds);
		double area = Math.max(1.0, forcePreview.referenceAreaSquareMeters());
		double areaChord = Math.max(1.0, area * forcePreview.meanAerodynamicChordMeters());
		linearImpulse = clampLength(linearImpulse, area * MAX_LINEAR_IMPULSE_PER_SQUARE_METER);
		A4mcVec3 centerOfMass = centerOfMassLocal(subLevel);
		if (centerOfMass != null) {
			A4mcVec3 leverArm = applicationPoint.subtract(centerOfMass);
			A4mcVec3 geometricAngularImpulse = cross(leverArm, linearImpulse).scale(GEOMETRIC_TORQUE_RESPONSE);
			geometricAngularImpulse = clampLength(geometricAngularImpulse, area * MAX_GEOMETRIC_ANGULAR_IMPULSE_PER_SQUARE_METER);
			angularImpulse = angularImpulse.add(geometricAngularImpulse);
		}
		angularImpulse = clampLength(angularImpulse, areaChord * MAX_ANGULAR_IMPULSE_PER_AREA_CHORD);
		if (linearImpulse.length() <= MIN_AERODYNAMIC_IMPULSE && angularImpulse.length() <= MIN_AERODYNAMIC_IMPULSE) {
			return new ForceApplication(false, "zero", applicationPoint, linearImpulse, angularImpulse);
		}

		try {
			Object forceTotal = queuedLiftForceTotal(subLevel);
			if (forceTotal == null) {
				return new ForceApplication(false, "no_lift_force_total", applicationPoint, linearImpulse, angularImpulse);
			}

			Class<?> vector3dcClass = Class.forName(
					"org.joml.Vector3dc",
					false,
					CreateAeronauticsFlightPolarService.class.getClassLoader()
			);
			Method applyLinearAndAngularImpulse = forceTotal.getClass().getMethod(
					"applyLinearAndAngularImpulse",
					vector3dcClass,
					vector3dcClass
			);
			applyLinearAndAngularImpulse.invoke(
					forceTotal,
					toJomlVector3d(linearImpulse),
					toJomlVector3d(angularImpulse)
			);
			return new ForceApplication(
					true,
					centerOfMass == null ? "queued_lift_no_com" : "queued_lift_point",
					applicationPoint,
					linearImpulse,
					angularImpulse
			);
		} catch (ReflectiveOperationException | LinkageError exception) {
			return new ForceApplication(
					false,
					"failed:" + exception.getClass().getSimpleName(),
					applicationPoint,
					linearImpulse,
					angularImpulse
			);
		}
	}

	private static AeroPolarSample lookupCoveredPolarSample(AeroPolarTable table, double angleOfAttackDegrees) {
		double minAngle = Double.POSITIVE_INFINITY;
		double maxAngle = Double.NEGATIVE_INFINITY;
		for (AeroPolarSample sample : table.samples()) {
			minAngle = Math.min(minAngle, sample.angleOfAttackDegrees());
			maxAngle = Math.max(maxAngle, sample.angleOfAttackDegrees());
		}
		if (!Double.isFinite(minAngle) || !Double.isFinite(maxAngle)) {
			return null;
		}
		if (angleOfAttackDegrees < minAngle - POLAR_ANGLE_EPSILON_DEGREES
				|| angleOfAttackDegrees > maxAngle + POLAR_ANGLE_EPSILON_DEGREES) {
			return null;
		}
		return table.lookup(angleOfAttackDegrees);
	}

	private static WingContribution primaryContribution(List<WingContribution> contributions, int selectedGroupIndex) {
		for (WingContribution contribution : contributions) {
			if (contribution.groupIndex() == selectedGroupIndex) {
				return contribution;
			}
		}
		return contributions.get(0);
	}

	private static String aggregateForceStatus(List<WingContribution> contributions, boolean applyForces) {
		if (!applyForces) {
			return "disabled";
		}
		String unavailableStatus = "";
		boolean anyPreview = false;
		boolean anyProvider = false;
		int applied = 0;
		int sampled = 0;
		for (WingContribution contribution : contributions) {
			if (contribution.hasPolarSample()) {
				sampled++;
			} else if (unavailableStatus.isEmpty() && contribution.forceApplyStatus().startsWith("polar_")) {
				unavailableStatus = contribution.forceApplyStatus();
			}
			anyPreview |= contribution.forceApplyStatus().equals("preview");
			anyProvider |= contribution.forceApplyStatus().equals("provider");
			if (contribution.forceApplied()) {
				applied++;
			}
		}
		if (sampled == 0 && !unavailableStatus.isEmpty()) {
			return unavailableStatus;
		}
		if (anyProvider) {
			return "provider sampled=" + sampled + "/" + contributions.size();
		}
		if (anyPreview) {
			return "preview sampled=" + sampled + "/" + contributions.size();
		}
		return "applied=" + applied + "/" + sampled + " sampled=" + sampled + "/" + contributions.size();
	}

	private static String forceStatusWithoutApplication(boolean hasPolarSample, boolean reportApplyEnabled) {
		if (!reportApplyEnabled) {
			return "disabled";
		}
		return hasPolarSample ? "provider" : "idle";
	}

	private static String polarUnavailableStatus(AeroPolarResult result) {
		if (result == null) {
			return "polar_no_result";
		}
		String status = result.status().name().toLowerCase(Locale.ROOT);
		if (!result.succeeded()) {
			String message = result.message();
			if (message == null || message.isBlank()) {
				return "polar_" + status;
			}
			return "polar_" + status + ":" + abbreviate(message, 96);
		}
		return "polar_no_table";
	}

	private static FlightFrame sampleFlightFrame(ServerLevel world, Object subLevel, AeroSurfaceDescriptor surface) {
		Object pose = invokeOrNull(subLevel, "logicalPose");
		A4mcVec3 localSamplePosition = surface.localOriginMeters();
		TransformSample positionSample = transformPosition(pose, localSamplePosition);
		A4mcVec3 samplePosition = positionSample.vector();
		A4mcVec3 chordDirection = normalizeOr(surface.chordDirection(), A4mcVec3.of(0.0, 0.0, -1.0));
		A4mcVec3 spanDirection = normalizeOr(surface.spanDirection(), A4mcVec3.of(1.0, 0.0, 0.0));
		A4mcVec3 normalDirection = normalizeOr(surface.normalDirection(), A4mcVec3.of(0.0, 1.0, 0.0));
		WindEnvironmentSample windEnvironment = sampleEnvironmentWind(world, samplePosition);
		VelocitySample bodyVelocityWorld = sampleBodyVelocity(world, subLevel, localSamplePosition);
		TransformSample environmentWind = transformNormalInverse(pose, windEnvironment.velocity());
		TransformSample bodyVelocity = transformNormalInverse(pose, bodyVelocityWorld.vector());
		A4mcVec3 relativeWind = environmentWind.vector().subtract(bodyVelocity.vector());
		A4mcVec3 airfoilWind = removeSpanwiseFlow(relativeWind, spanDirection);
		return new FlightFrame(
				samplePosition,
				localSamplePosition,
				environmentWind.vector(),
				bodyVelocity.vector(),
				relativeWind,
				airfoilWind,
				chordDirection,
				spanDirection,
				normalDirection,
				windEnvironment,
				"pos=" + positionSample.source()
						+ ",dir=local"
						+ ",wind=" + environmentWind.source()
						+ ",windEnv=" + windEnvironment.source()
						+ ",body=" + bodyVelocityWorld.source() + "->" + bodyVelocity.source()
						+ ",flow=chord_normal"
		);
	}

	private static ForcePreview previewForce(
			AeroSurfaceDescriptor surface,
			FlightFrame frame,
			AeroPolarSample sample,
			GroundEffectSample groundEffect
	) {
		double area = surface.areaSquareMeters();
		double meanChord = surface.meanAerodynamicChordMeters();
		if (sample == null) {
			return ForcePreview.zero(area, meanChord, frame.localSamplePosition());
		}
		GroundEffectSample safeGroundEffect = groundEffect == null ? GroundEffectSample.NONE : groundEffect;
		WindEnvironmentSample safeWindEnvironment = frame.windEnvironment() == null
				? WindEnvironmentSample.NONE
				: frame.windEnvironment();

		double speed = frame.airfoilWind().length();
		double dynamicPressure = 0.5 * AIR_DENSITY_KG_PER_CUBIC_METER * speed * speed;
		double liftCoefficient = sample.liftCoefficient()
				* safeGroundEffect.liftMultiplier()
				* safeWindEnvironment.liftMultiplier();
		double baseDragCoefficient = baseProfileDragCoefficient(surface.airfoilProfile());
		double inducedDragCoefficient = Math.max(0.0, sample.dragCoefficient() - baseDragCoefficient);
		double dragCoefficient = (baseDragCoefficient + inducedDragCoefficient * safeGroundEffect.dragMultiplier())
				* safeWindEnvironment.dragMultiplier();
		double liftNewtons = dynamicPressure * area * liftCoefficient;
		double polarDragNewtons = dynamicPressure * area * dragCoefficient;
		A4mcVec3 spanwiseWind = frame.relativeWind().subtract(frame.airfoilWind());
		double spanwiseSpeed = spanwiseWind.length();
		double spanwiseDynamicPressure = 0.5 * AIR_DENSITY_KG_PER_CUBIC_METER * spanwiseSpeed * spanwiseSpeed;
		double spanwiseDragNewtons = spanwiseDynamicPressure
				* area
				* SPANWISE_PARASITE_DRAG_COEFFICIENT
				* safeWindEnvironment.dragMultiplier();
		double dragNewtons = polarDragNewtons + spanwiseDragNewtons;
		double pitchingMomentNewtonMeters = dynamicPressure * area * meanChord * sample.momentCoefficient();

		A4mcVec3 dragDirection = normalizeOr(frame.airfoilWind(), A4mcVec3.ZERO);
		A4mcVec3 liftDirection = liftDirection(frame.spanDirection(), frame.normalDirection(), dragDirection);
		A4mcVec3 dragForce = dragDirection.scale(polarDragNewtons);
		if (spanwiseDragNewtons > 0.0) {
			dragForce = dragForce.add(normalizeOr(spanwiseWind, A4mcVec3.ZERO).scale(spanwiseDragNewtons));
		}
		A4mcVec3 liftForce = liftDirection.scale(liftNewtons);
		A4mcVec3 force = dragForce.add(liftForce);
		A4mcVec3 moment = frame.spanDirection().scale(pitchingMomentNewtonMeters);
		return new ForcePreview(
				dynamicPressure,
				area,
				meanChord,
				liftNewtons,
				dragNewtons,
				pitchingMomentNewtonMeters,
				safeGroundEffect.clearanceMeters(),
				safeGroundEffect.strength(),
				safeGroundEffect.liftMultiplier(),
				safeGroundEffect.dragMultiplier(),
				spanwiseDragNewtons,
				frame.localSamplePosition(),
				force,
				liftForce,
				dragForce,
				moment
		);
	}

	private static GroundEffectSample groundEffect(ServerLevel world, AeroSurfaceDescriptor surface, FlightFrame frame) {
		if (world == null || surface == null || frame == null || !groundEffectEligible(surface)) {
			return GroundEffectSample.NONE;
		}
		double span = Math.max(1.0, surface.spanMeters());
		double maxClearance = Math.min(GROUND_EFFECT_MAX_SCAN_BLOCKS, Math.max(1.0, span * GROUND_EFFECT_MAX_HEIGHT_TO_SPAN));
		double clearance = groundClearanceMeters(world, frame.samplePosition(), maxClearance);
		if (!Double.isFinite(clearance) || clearance > maxClearance) {
			return GroundEffectSample.NONE;
		}
		double heightToSpan = Math.max(0.02, clearance / span);
		double strength = 1.0 / (1.0 + 64.0 * heightToSpan * heightToSpan);
		strength = clamp(strength, 0.0, 1.0);
		return new GroundEffectSample(
				clearance,
				strength,
				1.0 + GROUND_EFFECT_MAX_LIFT_BOOST * strength,
				1.0 - GROUND_EFFECT_MAX_INDUCED_DRAG_REDUCTION * strength
		);
	}

	private static boolean groundEffectEligible(AeroSurfaceDescriptor surface) {
		A4mcVec3 span = normalizeOr(surface.spanDirection(), A4mcVec3.ZERO);
		A4mcVec3 normal = normalizeOr(surface.normalDirection(), A4mcVec3.ZERO);
		return Math.abs(span.y()) < 0.5 && Math.abs(normal.y()) > 0.5;
	}

	private static double groundClearanceMeters(ServerLevel world, A4mcVec3 samplePosition, double maxDistanceMeters) {
		if (world == null || samplePosition == null || !Double.isFinite(maxDistanceMeters) || maxDistanceMeters <= 0.0) {
			return Double.POSITIVE_INFINITY;
		}
		double sampleX = samplePosition.x();
		double sampleY = samplePosition.y();
		double sampleZ = samplePosition.z();
		if (!Double.isFinite(sampleX) || !Double.isFinite(sampleY) || !Double.isFinite(sampleZ)) {
			return Double.POSITIVE_INFINITY;
		}

		int x = (int) Math.floor(sampleX);
		int z = (int) Math.floor(sampleZ);
		int startY = (int) Math.floor(sampleY - 0.05);
		int minY = Math.max(world.getMinY(), (int) Math.floor(sampleY - maxDistanceMeters) - 1);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = startY; y >= minY; y--) {
			cursor.set(x, y, z);
			BlockState state = world.getBlockState(cursor);
			if (state.isAir()) {
				continue;
			}
			VoxelShape shape = state.getCollisionShape(world, cursor);
			if (shape.isEmpty()) {
				continue;
			}
			double topY = y + shape.bounds().maxY;
			return Math.max(0.0, sampleY - topY);
		}
		return Double.POSITIVE_INFINITY;
	}

	private static double baseProfileDragCoefficient(AeroAirfoilProfile profile) {
		if (profile == null) {
			return 0.02;
		}
		if (profile.kind() == AeroAirfoilProfile.Kind.FLAT_PLATE) {
			return 0.035;
		}
		double thickness = profile.thicknessRatio();
		return clamp(
				0.012 + 0.08 * thickness * thickness + 0.4 * profile.maxCamberRatio() * profile.maxCamberRatio(),
				0.012,
				0.035
		);
	}

	public record Snapshot(
			boolean hasWing,
			String subLevelName,
			String subLevelUuid,
			String worldId,
			int blockCount,
			String profileId,
			String shapeHash,
			String tableHash,
			String cacheKeyHash,
			boolean cacheHit,
			A4mcVec3 samplePosition,
			A4mcVec3 environmentWind,
			A4mcVec3 bodyVelocity,
			A4mcVec3 relativeWind,
			double relativeWindSpeedMetersPerSecond,
			double angleOfAttackDegrees,
			boolean hasPolarSample,
			double liftCoefficient,
			double dragCoefficient,
			double momentCoefficient,
			double dynamicPressurePascals,
			double referenceAreaSquareMeters,
			double meanAerodynamicChordMeters,
			double liftNewtons,
			double dragNewtons,
			double pitchingMomentNewtonMeters,
			A4mcVec3 forcePreview,
			A4mcVec3 momentPreview,
			boolean forceApplyEnabled,
			boolean forceApplied,
			String forceApplyStatus,
			A4mcVec3 forceApplicationPoint,
			A4mcVec3 linearImpulse,
			A4mcVec3 angularImpulse,
			String frameSource,
			int surfaceCount,
			int activeSurfaceCount,
			List<WingContribution> contributions,
			int scannedSubLevels,
			int wingSubLevels
	) {
		private static Snapshot empty() {
			return noWing(0, 0, false);
		}

		private static Snapshot noWing(int scannedSubLevels, int wingSubLevels, boolean forceApplyEnabled) {
			return new Snapshot(
					false,
					"",
					"",
					"",
					0,
					"",
					"",
					"",
					"",
					false,
					A4mcVec3.ZERO,
					A4mcVec3.ZERO,
					A4mcVec3.ZERO,
					A4mcVec3.ZERO,
					0.0,
					0.0,
					false,
					0.0,
					0.0,
					0.0,
					0.0,
					0.0,
					0.0,
					0.0,
					0.0,
					0.0,
					A4mcVec3.ZERO,
					A4mcVec3.ZERO,
					forceApplyEnabled,
					false,
					forceApplyEnabled ? "no_wing" : "disabled",
					A4mcVec3.ZERO,
					A4mcVec3.ZERO,
					A4mcVec3.ZERO,
					"",
					0,
					0,
					List.of(),
					scannedSubLevels,
					wingSubLevels
			);
		}

		public Snapshot {
			subLevelName = Objects.requireNonNullElse(subLevelName, "");
			subLevelUuid = Objects.requireNonNullElse(subLevelUuid, "");
			worldId = Objects.requireNonNullElse(worldId, "");
			profileId = Objects.requireNonNullElse(profileId, "");
			shapeHash = Objects.requireNonNullElse(shapeHash, "");
			tableHash = Objects.requireNonNullElse(tableHash, "");
			cacheKeyHash = Objects.requireNonNullElse(cacheKeyHash, "");
			samplePosition = samplePosition == null ? A4mcVec3.ZERO : samplePosition;
			environmentWind = environmentWind == null ? A4mcVec3.ZERO : environmentWind;
			bodyVelocity = bodyVelocity == null ? A4mcVec3.ZERO : bodyVelocity;
			relativeWind = relativeWind == null ? A4mcVec3.ZERO : relativeWind;
			forcePreview = forcePreview == null ? A4mcVec3.ZERO : forcePreview;
			momentPreview = momentPreview == null ? A4mcVec3.ZERO : momentPreview;
			forceApplyStatus = Objects.requireNonNullElse(forceApplyStatus, "");
			forceApplicationPoint = forceApplicationPoint == null ? A4mcVec3.ZERO : forceApplicationPoint;
			linearImpulse = linearImpulse == null ? A4mcVec3.ZERO : linearImpulse;
			angularImpulse = angularImpulse == null ? A4mcVec3.ZERO : angularImpulse;
			frameSource = Objects.requireNonNullElse(frameSource, "");
			contributions = contributions == null ? List.of() : List.copyOf(contributions);
		}

		private Snapshot withPassCounts(int scannedSubLevels, int wingSubLevels) {
			return new Snapshot(
					hasWing,
					subLevelName,
					subLevelUuid,
					worldId,
					blockCount,
					profileId,
					shapeHash,
					tableHash,
					cacheKeyHash,
					cacheHit,
					samplePosition,
					environmentWind,
					bodyVelocity,
					relativeWind,
					relativeWindSpeedMetersPerSecond,
					angleOfAttackDegrees,
					hasPolarSample,
					liftCoefficient,
					dragCoefficient,
					momentCoefficient,
					dynamicPressurePascals,
					referenceAreaSquareMeters,
					meanAerodynamicChordMeters,
					liftNewtons,
					dragNewtons,
					pitchingMomentNewtonMeters,
					forcePreview,
					momentPreview,
					forceApplyEnabled,
					forceApplied,
					forceApplyStatus,
					forceApplicationPoint,
					linearImpulse,
					angularImpulse,
					frameSource,
					surfaceCount,
					activeSurfaceCount,
					contributions,
					scannedSubLevels,
					wingSubLevels
			);
		}
	}

	private record WingContribution(
			int groupIndex,
			int blockCount,
			String profileId,
			String shapeHash,
			String tableHash,
			String cacheKeyHash,
			boolean cacheHit,
			A4mcVec3 samplePosition,
			A4mcVec3 environmentWind,
			WindEnvironmentSample windEnvironment,
			A4mcVec3 bodyVelocity,
			A4mcVec3 relativeWind,
			double relativeWindSpeedMetersPerSecond,
			double angleOfAttackDegrees,
			boolean hasPolarSample,
			double liftCoefficient,
			double dragCoefficient,
			double momentCoefficient,
			double groundClearanceMeters,
			double groundEffectStrength,
			double groundLiftMultiplier,
			double groundDragMultiplier,
			double spanwiseDragNewtons,
			double dynamicPressurePascals,
			double referenceAreaSquareMeters,
			double meanAerodynamicChordMeters,
			double liftNewtons,
			double dragNewtons,
			double pitchingMomentNewtonMeters,
			A4mcVec3 forcePreview,
			A4mcVec3 momentPreview,
			boolean forceApplyEnabled,
			boolean forceApplied,
			String forceApplyStatus,
			A4mcVec3 forceApplicationPoint,
			A4mcVec3 linearImpulse,
			A4mcVec3 angularImpulse,
			String frameSource
	) {
		private WingContribution {
			profileId = Objects.requireNonNullElse(profileId, "");
			shapeHash = Objects.requireNonNullElse(shapeHash, "");
			tableHash = Objects.requireNonNullElse(tableHash, "");
			cacheKeyHash = Objects.requireNonNullElse(cacheKeyHash, "");
			samplePosition = samplePosition == null ? A4mcVec3.ZERO : samplePosition;
			environmentWind = environmentWind == null ? A4mcVec3.ZERO : environmentWind;
			windEnvironment = windEnvironment == null ? WindEnvironmentSample.NONE : windEnvironment;
			bodyVelocity = bodyVelocity == null ? A4mcVec3.ZERO : bodyVelocity;
			relativeWind = relativeWind == null ? A4mcVec3.ZERO : relativeWind;
			forcePreview = forcePreview == null ? A4mcVec3.ZERO : forcePreview;
			momentPreview = momentPreview == null ? A4mcVec3.ZERO : momentPreview;
			forceApplyStatus = Objects.requireNonNullElse(forceApplyStatus, "");
			forceApplicationPoint = forceApplicationPoint == null ? A4mcVec3.ZERO : forceApplicationPoint;
			linearImpulse = linearImpulse == null ? A4mcVec3.ZERO : linearImpulse;
			angularImpulse = angularImpulse == null ? A4mcVec3.ZERO : angularImpulse;
			frameSource = Objects.requireNonNullElse(frameSource, "");
		}
	}

	private record FlightFrame(
			A4mcVec3 samplePosition,
			A4mcVec3 localSamplePosition,
			A4mcVec3 environmentWind,
			A4mcVec3 bodyVelocity,
			A4mcVec3 relativeWind,
			A4mcVec3 airfoilWind,
			A4mcVec3 chordDirection,
			A4mcVec3 spanDirection,
			A4mcVec3 normalDirection,
			WindEnvironmentSample windEnvironment,
			String source
	) {
		private FlightFrame {
			samplePosition = samplePosition == null ? A4mcVec3.ZERO : samplePosition;
			localSamplePosition = localSamplePosition == null ? A4mcVec3.ZERO : localSamplePosition;
			environmentWind = environmentWind == null ? A4mcVec3.ZERO : environmentWind;
			bodyVelocity = bodyVelocity == null ? A4mcVec3.ZERO : bodyVelocity;
			relativeWind = relativeWind == null ? A4mcVec3.ZERO : relativeWind;
			airfoilWind = airfoilWind == null ? A4mcVec3.ZERO : airfoilWind;
			chordDirection = chordDirection == null ? A4mcVec3.ZERO : chordDirection;
			spanDirection = spanDirection == null ? A4mcVec3.ZERO : spanDirection;
			normalDirection = normalDirection == null ? A4mcVec3.ZERO : normalDirection;
			windEnvironment = windEnvironment == null ? WindEnvironmentSample.NONE : windEnvironment;
			source = Objects.requireNonNullElse(source, "");
		}
	}

	private record ForcePreview(
			double dynamicPressurePascals,
			double referenceAreaSquareMeters,
			double meanAerodynamicChordMeters,
			double liftNewtons,
			double dragNewtons,
			double pitchingMomentNewtonMeters,
			double groundClearanceMeters,
			double groundEffectStrength,
			double groundLiftMultiplier,
			double groundDragMultiplier,
			double spanwiseDragNewtons,
			A4mcVec3 applicationPoint,
			A4mcVec3 force,
			A4mcVec3 liftForce,
			A4mcVec3 dragForce,
			A4mcVec3 moment
	) {
		private static ForcePreview zero(
				double referenceAreaSquareMeters,
				double meanAerodynamicChordMeters,
				A4mcVec3 applicationPoint
		) {
			return new ForcePreview(
					0.0,
					referenceAreaSquareMeters,
					meanAerodynamicChordMeters,
					0.0,
					0.0,
					0.0,
					Double.POSITIVE_INFINITY,
					0.0,
					1.0,
					1.0,
					0.0,
					applicationPoint,
					A4mcVec3.ZERO,
					A4mcVec3.ZERO,
					A4mcVec3.ZERO,
					A4mcVec3.ZERO
			);
		}
	}

	private record WindEnvironmentSample(
			A4mcVec3 velocity,
			double turbulenceIntensity,
			double windShearMagnitudePerBlock,
			double windShearStrength,
			double updraftMetersPerSecond,
			double shelterFactor,
			double ablMixingStrength,
			double liftMultiplier,
			double dragMultiplier,
			String source
	) {
		private static final WindEnvironmentSample NONE = new WindEnvironmentSample(
				A4mcVec3.ZERO,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				1.0,
				1.0,
				"none"
		);

		private static WindEnvironmentSample from(GameplayWindSample sample) {
			GameplayWindSample safeSample = sample == null ? GameplayWindSample.ZERO : sample;
			if (!safeSample.hasFlow()) {
				return NONE;
			}
			double turbulence = clamp(finiteOrZero(safeSample.turbulenceIntensity()), 0.0, 1.0);
			double shearMagnitude = Math.max(0.0, finiteOrZero(safeSample.windShearMagnitudePerBlock()));
			double shearStrength = clamp(shearMagnitude * WIND_SHEAR_RESPONSE_PER_BLOCK, 0.0, 1.0);
			double liftMultiplier = (1.0 - TURBULENCE_MAX_LIFT_LOSS * turbulence)
					* (1.0 - WIND_SHEAR_MAX_LIFT_LOSS * shearStrength);
			double dragMultiplier = 1.0
					+ TURBULENCE_DRAG_BOOST * turbulence
					+ WIND_SHEAR_DRAG_BOOST * shearStrength;
			return new WindEnvironmentSample(
					safeSample.effectiveVelocityVector(),
					turbulence,
					shearMagnitude,
					shearStrength,
					finiteOrZero(safeSample.updraftMetersPerSecond()),
					clamp(finiteOrZero(safeSample.shelterFactor()), 0.0, 1.0),
					Math.max(0.0, finiteOrZero(safeSample.ablMixingStrength())),
					Math.max(0.0, liftMultiplier),
					Math.max(1.0, dragMultiplier),
					safeSample.sourceLevel().name() + "/" + safeSample.authority().name()
			);
		}

		private WindEnvironmentSample {
			velocity = velocity == null ? A4mcVec3.ZERO : velocity;
			turbulenceIntensity = clamp(finiteOrZero(turbulenceIntensity), 0.0, 1.0);
			windShearMagnitudePerBlock = Math.max(0.0, finiteOrZero(windShearMagnitudePerBlock));
			windShearStrength = clamp(finiteOrZero(windShearStrength), 0.0, 1.0);
			updraftMetersPerSecond = finiteOrZero(updraftMetersPerSecond);
			shelterFactor = clamp(finiteOrZero(shelterFactor), 0.0, 1.0);
			ablMixingStrength = Math.max(0.0, finiteOrZero(ablMixingStrength));
			liftMultiplier = Math.max(0.0, finiteOrZero(liftMultiplier));
			dragMultiplier = Math.max(1.0, finiteOrZero(dragMultiplier));
			source = Objects.requireNonNullElse(source, "");
		}
	}

	private record GroundEffectSample(
			double clearanceMeters,
			double strength,
			double liftMultiplier,
			double dragMultiplier
	) {
		private static final GroundEffectSample NONE = new GroundEffectSample(
				Double.POSITIVE_INFINITY,
				0.0,
				1.0,
				1.0
		);
	}

	private record ForceApplication(
			boolean applied,
			String status,
			A4mcVec3 applicationPoint,
			A4mcVec3 linearImpulse,
			A4mcVec3 angularImpulse
	) {
		private static ForceApplication inactive(String status, A4mcVec3 applicationPoint) {
			return new ForceApplication(false, status, applicationPoint, A4mcVec3.ZERO, A4mcVec3.ZERO);
		}
	}

	private record TransformSample(A4mcVec3 vector, String source, boolean transformed) {
	}

	private record VelocitySample(A4mcVec3 vector, String source) {
	}

	private static A4mcVec3 centerOfMassLocal(Object subLevel) {
		try {
			Object massTracker = invokeOrNull(subLevel, "getMassTracker");
			if (massTracker == null) {
				return null;
			}
			Method getCenterOfMass = massTracker.getClass().getMethod("getCenterOfMass");
			Object value = getCenterOfMass.invoke(massTracker);
			if (value == null) {
				return null;
			}
			return A4mcVec3.of(
					vectorComponent(value, "x"),
					vectorComponent(value, "y"),
					vectorComponent(value, "z")
			);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return null;
		}
	}

	private static Object queuedLiftForceTotal(Object subLevel) throws ReflectiveOperationException {
		ClassLoader loader = CreateAeronauticsFlightPolarService.class.getClassLoader();
		Class<?> serverSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel", false, loader);
		if (!serverSubLevelClass.isInstance(subLevel)) {
			return null;
		}
		Class<?> forceGroupClass = Class.forName("dev.ryanhcode.sable.api.physics.force.ForceGroup", false, loader);
		Class<?> forceGroupsClass = Class.forName("dev.ryanhcode.sable.api.physics.force.ForceGroups", false, loader);
		Object liftRegistryObject = forceGroupsClass.getField("LIFT").get(null);
		if (liftRegistryObject == null) {
			return null;
		}
		Method get = liftRegistryObject.getClass().getMethod("get");
		Object liftForceGroup = get.invoke(liftRegistryObject);
		if (liftForceGroup == null) {
			return null;
		}
		Method getOrCreateQueuedForceGroup = serverSubLevelClass.getMethod("getOrCreateQueuedForceGroup", forceGroupClass);
		Object queuedForceGroup = getOrCreateQueuedForceGroup.invoke(subLevel, liftForceGroup);
		if (queuedForceGroup == null) {
			return null;
		}
		Method getForceTotal = queuedForceGroup.getClass().getMethod("getForceTotal");
		return getForceTotal.invoke(queuedForceGroup);
	}

	private static Object toJomlVector3d(A4mcVec3 vector) throws ReflectiveOperationException {
		Class<?> vector3dClass = Class.forName(
				"org.joml.Vector3d",
				false,
				CreateAeronauticsFlightPolarService.class.getClassLoader()
		);
		return vector3dClass
				.getConstructor(double.class, double.class, double.class)
				.newInstance(vector.x(), vector.y(), vector.z());
	}

	private static WindEnvironmentSample sampleEnvironmentWind(ServerLevel world, A4mcVec3 samplePosition) {
		if (world == null || samplePosition == null) {
			return WindEnvironmentSample.NONE;
		}
		Identifier id = world.dimension().identifier();
		A4mcWorldRef worldRef = A4mcWorldRef.server(A4mcId.of(id.getNamespace(), id.getPath()), world);
		GameplayWindSample sample = AeroWindApi.sampleGameplay(worldRef, samplePosition, SamplePolicy.GAMEPLAY_SERVER_ONLY);
		return WindEnvironmentSample.from(sample);
	}

	private static VelocitySample sampleBodyVelocity(ServerLevel world, Object subLevel, A4mcVec3 localSamplePosition) {
		try {
			Object helper = sableHelper();
			Class<?> subLevelAccessClass = Class.forName(
					"dev.ryanhcode.sable.companion.SubLevelAccess",
					false,
					CreateAeronauticsFlightPolarService.class.getClassLoader()
			);
			Method method = helper.getClass().getMethod("getVelocity", Level.class, subLevelAccessClass, Vec3.class);
			Object value = method.invoke(helper, world, subLevel, toMinecraft(localSamplePosition));
			if (value instanceof Vec3 vector) {
				return new VelocitySample(fromMinecraft(vector), "sable_point");
			}
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
		return new VelocitySample(latestLinearVelocity(subLevel), "latestLinearVelocity");
	}

	private static Object sableHelper() throws ReflectiveOperationException {
		Class<?> sableClass = Class.forName("dev.ryanhcode.sable.Sable", false, CreateAeronauticsFlightPolarService.class.getClassLoader());
		Field helperField = sableClass.getField("HELPER");
		return helperField.get(null);
	}

	private static A4mcVec3 latestLinearVelocity(Object subLevel) {
		try {
			Field field = subLevel.getClass().getField("latestLinearVelocity");
			Object vector = field.get(subLevel);
			return A4mcVec3.of(vectorComponent(vector, "x"), vectorComponent(vector, "y"), vectorComponent(vector, "z"));
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return A4mcVec3.ZERO;
		}
	}

	private static double vectorComponent(Object vector, String methodName) throws ReflectiveOperationException {
		if (vector == null) {
			return 0.0;
		}
		Method method = vector.getClass().getMethod(methodName);
		Object value = method.invoke(vector);
		return value instanceof Number number ? number.doubleValue() : 0.0;
	}

	private static double angleOfAttackDegrees(A4mcVec3 chordDirection, A4mcVec3 normalDirection, A4mcVec3 relativeWind) {
		double chord = -dot(relativeWind, chordDirection);
		double normal = dot(relativeWind, normalDirection);
		double angle = Math.toDegrees(Math.atan2(normal, chord));
		while (angle > 90.0) {
			angle -= 180.0;
		}
		while (angle < -90.0) {
			angle += 180.0;
		}
		return angle;
	}

	private static double dot(A4mcVec3 a, A4mcVec3 b) {
		return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
	}

	private static A4mcVec3 liftDirection(A4mcVec3 spanDirection, A4mcVec3 normalDirection, A4mcVec3 dragDirection) {
		A4mcVec3 candidate = cross(spanDirection, dragDirection);
		if (candidate.length() <= 1.0e-9) {
			candidate = normalDirection;
		}
		candidate = normalizeOr(candidate, normalDirection);
		if (dot(candidate, normalDirection) < 0.0) {
			return candidate.scale(-1.0);
		}
		return candidate;
	}

	private static A4mcVec3 removeSpanwiseFlow(A4mcVec3 relativeWind, A4mcVec3 spanDirection) {
		A4mcVec3 safeRelativeWind = relativeWind == null ? A4mcVec3.ZERO : relativeWind;
		A4mcVec3 safeSpanDirection = normalizeOr(spanDirection, A4mcVec3.ZERO);
		if (safeSpanDirection.length() <= 1.0e-9) {
			return safeRelativeWind;
		}
		return safeRelativeWind.subtract(safeSpanDirection.scale(dot(safeRelativeWind, safeSpanDirection)));
	}

	private static A4mcVec3 cross(A4mcVec3 a, A4mcVec3 b) {
		return A4mcVec3.of(
				a.y() * b.z() - a.z() * b.y(),
				a.z() * b.x() - a.x() * b.z(),
				a.x() * b.y() - a.y() * b.x()
		);
	}

	private static A4mcVec3 pointBodyVelocityLocal(
			Pose3d logicalPose,
			A4mcVec3 localPosition,
			Vector3dc linearVelocity,
			Vector3dc angularVelocity
	) {
		Vector3d worldPosition = logicalPose.transformPosition(toJoml(localPosition), new Vector3d());
		Vector3d leverArm = worldPosition.sub(logicalPose.position(), new Vector3d());
		Vector3d velocity = linearVelocity == null ? new Vector3d() : new Vector3d(linearVelocity);
		if (angularVelocity != null) {
			velocity.add(angularVelocity.cross(leverArm, new Vector3d()));
		}
		logicalPose.transformNormalInverse(velocity, velocity);
		return fromJoml(velocity);
	}

	private static A4mcVec3 directionVector(Direction direction) {
		return switch (direction) {
			case DOWN -> A4mcVec3.of(0.0, -1.0, 0.0);
			case UP -> A4mcVec3.of(0.0, 1.0, 0.0);
			case NORTH -> A4mcVec3.of(0.0, 0.0, -1.0);
			case SOUTH -> A4mcVec3.of(0.0, 0.0, 1.0);
			case WEST -> A4mcVec3.of(-1.0, 0.0, 0.0);
			case EAST -> A4mcVec3.of(1.0, 0.0, 0.0);
		};
	}

	private static A4mcVec3 transformPosition(Pose3d pose, A4mcVec3 localPosition) {
		return fromJoml(pose.transformPosition(toJoml(localPosition), new Vector3d()));
	}

	private static A4mcVec3 transformNormal(Pose3d pose, A4mcVec3 localDirection) {
		return fromJoml(pose.transformNormal(toJoml(localDirection), new Vector3d()));
	}

	private static A4mcVec3 transformNormalInverse(Pose3d pose, A4mcVec3 worldDirection) {
		return fromJoml(pose.transformNormalInverse(toJoml(worldDirection), new Vector3d()));
	}

	private static Vector3d toJoml(A4mcVec3 vector) {
		A4mcVec3 safeVector = vector == null ? A4mcVec3.ZERO : vector;
		return new Vector3d(safeVector.x(), safeVector.y(), safeVector.z());
	}

	private static A4mcVec3 fromJoml(Vector3dc vector) {
		if (vector == null) {
			return A4mcVec3.ZERO;
		}
		return A4mcVec3.of(vector.x(), vector.y(), vector.z());
	}

	private static TransformSample transformPosition(Object pose, A4mcVec3 localPosition) {
		return transformVec3(pose, "transformPosition", localPosition, "sable_pose", "local");
	}

	private static TransformSample transformNormalInverse(Object pose, A4mcVec3 worldDirection) {
		return transformVec3(pose, "transformNormalInverse", worldDirection, "sable_pose_inverse", "world");
	}

	private static TransformSample transformVec3(
			Object pose,
			String methodName,
			A4mcVec3 vector,
			String transformedSource,
			String fallbackSource
	) {
		if (pose == null) {
			return new TransformSample(vector, fallbackSource, false);
		}
		try {
			Method method = pose.getClass().getMethod(methodName, Vec3.class);
			Object value = method.invoke(pose, toMinecraft(vector));
			if (value instanceof Vec3 transformed) {
				return new TransformSample(fromMinecraft(transformed), transformedSource, true);
			}
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
		return new TransformSample(vector, fallbackSource, false);
	}

	private static Object invokeOrNull(Object target, String methodName) {
		try {
			Method method = target.getClass().getMethod(methodName);
			return method.invoke(target);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return null;
		}
	}

	private static List<Object> candidateServerSubLevels(ServerLevel world) {
		Set<Object> candidates = new LinkedHashSet<>();
		addPlayerServerSubLevelCandidates(world, candidates);
		candidates.addAll(allServerSubLevels(world));
		return List.copyOf(candidates);
	}

	private static void addPlayerServerSubLevelCandidates(ServerLevel world, Set<Object> candidates) {
		try {
			Object helper = sableHelper();
			for (ServerPlayer player : world.players()) {
				for (String methodName : new String[] {
						"getTrackingOrVehicleSubLevel",
						"getTrackingSubLevel",
						"getVehicleSubLevel",
						"getContaining"
				}) {
					addEntitySubLevelCandidate(helper, methodName, player, candidates);
				}
			}
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
	}

	private static void addEntitySubLevelCandidate(
			Object helper,
			String methodName,
			ServerPlayer player,
			Set<Object> candidates
	) throws ReflectiveOperationException {
		for (Method method : helper.getClass().getMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
				continue;
			}
			if (!method.getParameterTypes()[0].isAssignableFrom(player.getClass())) {
				continue;
			}
			Object subLevel = method.invoke(helper, player);
			if (subLevel != null && isServerSubLevelObject(subLevel)) {
				candidates.add(subLevel);
			}
		}
	}

	private static List<Object> allServerSubLevels(ServerLevel world) {
		try {
			Class<?> containerClass = Class.forName(
					"dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
					false,
					CreateAeronauticsFlightPolarService.class.getClassLoader()
			);
			Object container = invokeStaticContainer(containerClass, world);
			if (container == null) {
				return List.of();
			}
			Method method = container.getClass().getMethod("getAllSubLevels");
			Object value = method.invoke(container);
			if (value instanceof Collection<?> collection) {
				return List.copyOf(collection);
			}
			return List.of();
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return List.of();
		}
	}

	private static boolean isServerSubLevelObject(Object subLevel) throws ClassNotFoundException {
		Class<?> serverSubLevelClass = Class.forName(
				"dev.ryanhcode.sable.sublevel.ServerSubLevel",
				false,
				CreateAeronauticsFlightPolarService.class.getClassLoader()
		);
		return serverSubLevelClass.isInstance(subLevel);
	}

	private static Object invokeStaticContainer(Class<?> containerClass, ServerLevel world)
			throws InvocationTargetException, IllegalAccessException {
		for (Method method : containerClass.getMethods()) {
			if (!method.getName().equals("getContainer") || method.getParameterCount() != 1) {
				continue;
			}
			if (!method.getParameterTypes()[0].isAssignableFrom(world.getClass())) {
				continue;
			}
			return method.invoke(null, world);
		}
		return null;
	}

	private static boolean isRemoved(Object subLevel) {
		try {
			Method method = subLevel.getClass().getMethod("isRemoved");
			Object value = method.invoke(subLevel);
			return value instanceof Boolean booleanValue && booleanValue;
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return false;
		}
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

	private static A4mcVec3 normalizeOr(A4mcVec3 vector, A4mcVec3 fallback) {
		A4mcVec3 safeVector = vector == null ? A4mcVec3.ZERO : vector;
		double length = safeVector.length();
		if (length > 1.0e-9) {
			return safeVector.scale(1.0 / length);
		}
		A4mcVec3 safeFallback = fallback == null ? A4mcVec3.ZERO : fallback;
		double fallbackLength = safeFallback.length();
		return fallbackLength > 1.0e-9 ? safeFallback.scale(1.0 / fallbackLength) : A4mcVec3.ZERO;
	}

	private static A4mcVec3 clampLength(A4mcVec3 vector, double maxLength) {
		A4mcVec3 safeVector = vector == null ? A4mcVec3.ZERO : vector;
		if (!Double.isFinite(maxLength) || maxLength <= 0.0) {
			return A4mcVec3.ZERO;
		}
		double length = safeVector.length();
		if (length <= maxLength || length <= 1.0e-9) {
			return safeVector;
		}
		return safeVector.scale(maxLength / length);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double finiteOrZero(double value) {
		return Double.isFinite(value) ? value : 0.0;
	}

	private static Vec3 toMinecraft(A4mcVec3 vector) {
		A4mcVec3 safeVector = vector == null ? A4mcVec3.ZERO : vector;
		return new Vec3(safeVector.x(), safeVector.y(), safeVector.z());
	}

	private static A4mcVec3 fromMinecraft(Vec3 vector) {
		if (vector == null) {
			return A4mcVec3.ZERO;
		}
		return A4mcVec3.of(vector.x, vector.y, vector.z);
	}

	private static String formatVec(A4mcVec3 vec) {
		return "(" + format3(vec.x()) + ", " + format3(vec.y()) + ", " + format3(vec.z()) + ")";
	}

	private static String formatGroundEffect(WingContribution contribution) {
		if (contribution == null || contribution.groundEffectStrength() <= 0.0) {
			return "off";
		}
		String clearance = Double.isFinite(contribution.groundClearanceMeters())
				? format3(contribution.groundClearanceMeters()) + "m"
				: "unknown";
		return "h=" + clearance
				+ " strength=" + format3(contribution.groundEffectStrength())
				+ " liftx=" + format3(contribution.groundLiftMultiplier())
				+ " dragx=" + format3(contribution.groundDragMultiplier());
	}

	private static String formatWindEnvironment(WindEnvironmentSample sample) {
		WindEnvironmentSample safeSample = sample == null ? WindEnvironmentSample.NONE : sample;
		return "src=" + safeSample.source()
				+ " turb=" + format3(safeSample.turbulenceIntensity())
				+ " shear=" + format4(safeSample.windShearMagnitudePerBlock()) + "/block"
				+ " updraft=" + formatSigned3(safeSample.updraftMetersPerSecond()) + "m/s"
				+ " shelter=" + format3(safeSample.shelterFactor())
				+ " mix=" + format3(safeSample.ablMixingStrength())
				+ " liftx=" + format3(safeSample.liftMultiplier())
				+ " dragx=" + format3(safeSample.dragMultiplier());
	}

	private static String formatSigned3(double value) {
		return String.format(Locale.ROOT, "%+.3f", value);
	}

	private static String format3(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private static String format2(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	private static String format4(double value) {
		return String.format(Locale.ROOT, "%.4f", value);
	}

	private static String abbreviate(String value, int maxLength) {
		if (value == null || maxLength <= 0 || value.length() <= maxLength) {
			return value == null ? "" : value;
		}
		if (maxLength <= 3) {
			return value.substring(0, maxLength);
		}
		return value.substring(0, maxLength - 3) + "...";
	}
}
