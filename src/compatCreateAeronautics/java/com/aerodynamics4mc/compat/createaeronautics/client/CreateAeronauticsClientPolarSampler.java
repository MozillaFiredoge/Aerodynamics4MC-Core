package com.aerodynamics4mc.compat.createaeronautics.client;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroPolarRequest;
import com.aerodynamics4mc.api.AeroPolarResult;
import com.aerodynamics4mc.api.AeroPolarSample;
import com.aerodynamics4mc.api.AeroPolarTable;
import com.aerodynamics4mc.api.AeroSurfaceDescriptor;
import com.aerodynamics4mc.api.AeroWindApi;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsPolarCache;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsWingScanner;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CreateAeronauticsClientPolarSampler {
	private static final int MAX_SUBLEVELS_PER_SCAN = 8;
	private static final int RESCAN_INTERVAL_TICKS = 10;
	private static final double MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND = 0.05;
	private static final double MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND = 40.0;
	private static final double AIR_DENSITY_KG_PER_CUBIC_METER = 1.225;
	private static final double POLAR_ANGLE_EPSILON_DEGREES = 1.0e-6;
	private static final double MAX_BODY_VELOCITY_ESTIMATE_METERS_PER_SECOND = 120.0;

	private final CreateAeronauticsWingScanner wingScanner = new CreateAeronauticsWingScanner();
	private final CreateAeronauticsPolarCache polarCache = CreateAeronauticsPolarCache.INSTANCE;

	private Snapshot lastSnapshot = Snapshot.empty("disabled");
	private ClientSubLevel selectedSubLevel;
	private CreateAeronauticsWingScanner.WingScanResult selectedScan;
	private long nextRescanGameTime;
	private PreviousFrame previousFrame;
	private long lastSampleGameTime = Long.MIN_VALUE;

	public Snapshot sample(Minecraft client) {
		if (client == null || client.level == null || client.player == null) {
			clear();
			return lastSnapshot;
		}

		long gameTime = client.level.getGameTime();
		if (gameTime == lastSampleGameTime) {
			return lastSnapshot;
		}
		lastSampleGameTime = gameTime;

		try {
			SelectedWing selected = selectedWing(client, gameTime);
			if (selected == null) {
				previousFrame = null;
				lastSnapshot = Snapshot.empty("client estimate: no A4MC wing");
				return lastSnapshot;
			}
			lastSnapshot = sampleSelectedWing(client.level, selected);
			return lastSnapshot;
		} catch (RuntimeException | LinkageError exception) {
			previousFrame = null;
			lastSnapshot = Snapshot.empty("client estimate failed: " + exception.getClass().getSimpleName());
			return lastSnapshot;
		}
	}

	public void clear() {
		selectedSubLevel = null;
		selectedScan = null;
		nextRescanGameTime = 0L;
		previousFrame = null;
		lastSampleGameTime = Long.MIN_VALUE;
		lastSnapshot = Snapshot.empty("disabled");
	}

	private SelectedWing selectedWing(Minecraft client, long gameTime) {
		Vec3 anchor = client.gameRenderer == null
				? client.player.position()
				: client.gameRenderer.getMainCamera().position();
		if (selectedSubLevel != null
				&& selectedScan != null
				&& selectedScan.hasSelectedGroup()
				&& !selectedSubLevel.isRemoved()
				&& gameTime < nextRescanGameTime) {
			return new SelectedWing(selectedSubLevel, selectedScan, selectedScan.selectedGroup());
		}

		selectedSubLevel = null;
		selectedScan = null;
		nextRescanGameTime = gameTime + RESCAN_INTERVAL_TICKS;

		ClientSubLevelContainer container = SubLevelContainer.getContainer(client.level);
		if (container == null) {
			return null;
		}

		SelectedWing best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		int scanned = 0;
		for (ClientSubLevel subLevel : container.getAllSubLevels()) {
			if (subLevel == null || subLevel.isRemoved()) {
				continue;
			}
			if (scanned++ >= MAX_SUBLEVELS_PER_SCAN) {
				break;
			}
			CreateAeronauticsWingScanner.WingScanResult scan = wingScanner.scan(subLevel);
			if (!scan.hasSelectedGroup()) {
				continue;
			}
			double score = distanceScore(subLevel.boundingBox(), anchor);
			if (score < bestScore) {
				bestScore = score;
				best = new SelectedWing(subLevel, scan, scan.selectedGroup());
			}
		}

		if (best != null) {
			selectedSubLevel = best.subLevel();
			selectedScan = best.scan();
		}
		return best;
	}

	private Snapshot sampleSelectedWing(ClientLevel level, SelectedWing selected) {
		ClientSubLevel subLevel = selected.subLevel();
		AeroSurfaceDescriptor surface = selected.group().surface();
		AeroPolarResult result = polarCache.getOrGenerate(polarRequest(surface)).result();
		if (result == null || !result.succeeded() || !result.hasTable()) {
			return Snapshot.empty("client estimate: no polar table");
		}

		Pose3dc pose = subLevel.renderPose();
		A4mcVec3 localSamplePosition = surface.localOriginMeters();
		A4mcVec3 samplePosition = transformPosition(pose, localSamplePosition);
		A4mcVec3 environmentWindWorld = sampleEnvironmentWind(level, samplePosition);
		A4mcVec3 environmentWind = transformNormalInverse(pose, environmentWindWorld);
		A4mcVec3 bodyVelocity = transformNormalInverse(
				pose,
				estimateBodyVelocityWorld(subLevel, surface.shapeHash(), samplePosition)
		);
		A4mcVec3 relativeWind = environmentWind.subtract(bodyVelocity);
		A4mcVec3 spanDirection = normalizeOr(surface.spanDirection(), A4mcVec3.of(1.0, 0.0, 0.0));
		A4mcVec3 airfoilWind = removeSpanwiseFlow(relativeWind, spanDirection);
		double relativeWindSpeed = airfoilWind.length();
		double angleOfAttackDegrees = relativeWindSpeed < MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND
				? 0.0
				: angleOfAttackDegrees(
						normalizeOr(surface.chordDirection(), A4mcVec3.of(0.0, 0.0, -1.0)),
						normalizeOr(surface.normalDirection(), A4mcVec3.of(0.0, 1.0, 0.0)),
						airfoilWind
				);

		AeroPolarTable table = result.table();
		List<PolarPoint> points = polarPoints(table);
		AeroPolarSample sample = relativeWindSpeed >= MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND
				&& relativeWindSpeed <= MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND
				? lookupCoveredPolarSample(table, angleOfAttackDegrees)
				: null;
		ForcePreview forcePreview = previewForce(surface, airfoilWind, spanDirection, surface.normalDirection(), sample);
		FlightTestState flightTestState = flightTestState(points, relativeWindSpeed, angleOfAttackDegrees, sample);
		double liftDragRatio = sample == null || sample.dragCoefficient() <= 1.0e-6
				? Double.NaN
				: sample.liftCoefficient() / sample.dragCoefficient();
		String status = "client estimate";
		if (relativeWindSpeed < MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND) {
			status = "client estimate: idle";
		} else if (relativeWindSpeed > MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND) {
			status = "client estimate: speed limit";
		} else if (sample == null) {
			status = "client estimate: aoa outside table";
		}

		return new Snapshot(
				true,
				status,
				surface.airfoilProfile().id().toString(),
				table.tableHash(),
				relativeWindSpeed,
				angleOfAttackDegrees,
				sample != null,
				sample == null ? 0.0 : sample.liftCoefficient(),
				sample == null ? 0.0 : sample.dragCoefficient(),
				sample == null ? 0.0 : sample.momentCoefficient(),
				forcePreview.liftNewtons(),
				forcePreview.dragNewtons(),
				forcePreview.pitchingMomentNewtonMeters(),
				liftDragRatio,
				flightTestState.stallMarginDegrees(),
				flightTestState.positivePeakAngleDegrees(),
				flightTestState.negativePeakAngleDegrees(),
				flightTestState.label(),
				points
		);
	}

	private A4mcVec3 estimateBodyVelocityWorld(ClientSubLevel subLevel, String shapeHash, A4mcVec3 samplePosition) {
		long now = System.nanoTime();
		UUID uuid = subLevel.getUniqueId();
		PreviousFrame previous = previousFrame;
		previousFrame = new PreviousFrame(uuid, shapeHash, samplePosition, now);
		if (previous == null
				|| !Objects.equals(previous.subLevelId(), uuid)
				|| !Objects.equals(previous.shapeHash(), shapeHash)) {
			return A4mcVec3.ZERO;
		}
		double seconds = (now - previous.nanoTime()) / 1.0e9;
		if (!Double.isFinite(seconds) || seconds <= 1.0e-4 || seconds > 0.5) {
			return A4mcVec3.ZERO;
		}
		A4mcVec3 velocity = samplePosition.subtract(previous.samplePosition()).scale(1.0 / seconds);
		return velocity.length() > MAX_BODY_VELOCITY_ESTIMATE_METERS_PER_SECOND ? A4mcVec3.ZERO : velocity;
	}

	private static AeroPolarRequest polarRequest(AeroSurfaceDescriptor surface) {
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

	private static A4mcVec3 sampleEnvironmentWind(ClientLevel level, A4mcVec3 samplePosition) {
		Identifier id = level.dimension().identifier();
		A4mcWorldRef worldRef = A4mcWorldRef.client(A4mcId.of(id.getNamespace(), id.getPath()), level);
		AeroWindSample sample = AeroWindApi.sample(worldRef, samplePosition, SamplePolicy.VISUAL_LOCAL_FIRST);
		return sample.effectiveVelocityVector();
	}

	private static ForcePreview previewForce(
			AeroSurfaceDescriptor surface,
			A4mcVec3 airfoilWind,
			A4mcVec3 spanDirection,
			A4mcVec3 normalDirection,
			AeroPolarSample sample
	) {
		if (sample == null) {
			return new ForcePreview(0.0, 0.0, 0.0);
		}
		double speed = airfoilWind.length();
		double dynamicPressure = 0.5 * AIR_DENSITY_KG_PER_CUBIC_METER * speed * speed;
		double liftNewtons = dynamicPressure * surface.areaSquareMeters() * sample.liftCoefficient();
		double dragNewtons = dynamicPressure * surface.areaSquareMeters() * sample.dragCoefficient();
		double momentNewtonMeters = dynamicPressure
				* surface.areaSquareMeters()
				* surface.meanAerodynamicChordMeters()
				* sample.momentCoefficient();
		A4mcVec3 dragDirection = normalizeOr(airfoilWind, A4mcVec3.ZERO);
		A4mcVec3 liftDirection = liftDirection(spanDirection, normalDirection, dragDirection);
		if (liftDirection.length() <= 1.0e-9) {
			return new ForcePreview(liftNewtons, dragNewtons, momentNewtonMeters);
		}
		return new ForcePreview(liftNewtons, dragNewtons, momentNewtonMeters);
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

	private static List<PolarPoint> polarPoints(AeroPolarTable table) {
		if (table == null || table.samples().isEmpty()) {
			return List.of();
		}
		double selectedDeflection = table.samples().get(0).controlDeflectionDegrees();
		double bestDeflectionDistance = Math.abs(selectedDeflection);
		for (AeroPolarSample sample : table.samples()) {
			double distance = Math.abs(sample.controlDeflectionDegrees());
			if (distance < bestDeflectionDistance) {
				selectedDeflection = sample.controlDeflectionDegrees();
				bestDeflectionDistance = distance;
			}
		}

		double selectedReynolds = Double.NaN;
		double bestReynoldsDistance = Double.POSITIVE_INFINITY;
		for (AeroPolarSample sample : table.samples()) {
			if (sample.controlDeflectionDegrees() != selectedDeflection) {
				continue;
			}
			double distance = Math.abs(sample.reynoldsNumber());
			if (distance < bestReynoldsDistance) {
				selectedReynolds = sample.reynoldsNumber();
				bestReynoldsDistance = distance;
			}
		}
		if (Double.isNaN(selectedReynolds)) {
			return List.of();
		}

		List<PolarPoint> points = new ArrayList<>();
		for (AeroPolarSample sample : table.samples()) {
			if (sample.controlDeflectionDegrees() == selectedDeflection
					&& sample.reynoldsNumber() == selectedReynolds) {
				points.add(new PolarPoint(
						sample.angleOfAttackDegrees(),
						sample.liftCoefficient(),
						sample.dragCoefficient(),
						sample.momentCoefficient()
				));
			}
		}
		return List.copyOf(points);
	}

	private static FlightTestState flightTestState(
			List<PolarPoint> points,
			double relativeWindSpeed,
			double angleOfAttackDegrees,
			AeroPolarSample sample
	) {
		PeakAngles peakAngles = peakAngles(points);
		double margin = stallMargin(angleOfAttackDegrees, peakAngles);
		if (relativeWindSpeed < MIN_RELATIVE_WIND_SPEED_METERS_PER_SECOND) {
			return new FlightTestState("IDLE", margin, peakAngles.positive(), peakAngles.negative());
		}
		if (relativeWindSpeed > MAX_RELATIVE_WIND_SPEED_METERS_PER_SECOND) {
			return new FlightTestState("SPEED LIMIT", margin, peakAngles.positive(), peakAngles.negative());
		}
		if (sample == null) {
			return new FlightTestState("OUT OF RANGE", margin, peakAngles.positive(), peakAngles.negative());
		}
		if (Double.isFinite(margin)) {
			if (margin < -0.5) {
				return new FlightTestState("POST PEAK", margin, peakAngles.positive(), peakAngles.negative());
			}
			if (margin < 2.0) {
				return new FlightTestState("STALL EDGE", margin, peakAngles.positive(), peakAngles.negative());
			}
			if (margin < 5.0) {
				return new FlightTestState("HIGH AOA", margin, peakAngles.positive(), peakAngles.negative());
			}
		}
		return new FlightTestState("CLEAN", margin, peakAngles.positive(), peakAngles.negative());
	}

	private static PeakAngles peakAngles(List<PolarPoint> points) {
		double positiveAngle = Double.NaN;
		double positiveCl = Double.NEGATIVE_INFINITY;
		double negativeAngle = Double.NaN;
		double negativeCl = Double.POSITIVE_INFINITY;
		for (PolarPoint point : points) {
			if (point.angleOfAttackDegrees() >= 0.0 && point.liftCoefficient() > positiveCl) {
				positiveCl = point.liftCoefficient();
				positiveAngle = point.angleOfAttackDegrees();
			}
			if (point.angleOfAttackDegrees() <= 0.0 && point.liftCoefficient() < negativeCl) {
				negativeCl = point.liftCoefficient();
				negativeAngle = point.angleOfAttackDegrees();
			}
		}
		return new PeakAngles(positiveAngle, negativeAngle);
	}

	private static double stallMargin(double angleOfAttackDegrees, PeakAngles peakAngles) {
		if (angleOfAttackDegrees >= 0.0 && Double.isFinite(peakAngles.positive())) {
			return peakAngles.positive() - angleOfAttackDegrees;
		}
		if (angleOfAttackDegrees < 0.0 && Double.isFinite(peakAngles.negative())) {
			return angleOfAttackDegrees - peakAngles.negative();
		}
		return Double.NaN;
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

	private static A4mcVec3 removeSpanwiseFlow(A4mcVec3 relativeWind, A4mcVec3 spanDirection) {
		A4mcVec3 safeRelativeWind = relativeWind == null ? A4mcVec3.ZERO : relativeWind;
		A4mcVec3 safeSpanDirection = normalizeOr(spanDirection, A4mcVec3.ZERO);
		if (safeSpanDirection.length() <= 1.0e-9) {
			return safeRelativeWind;
		}
		return safeRelativeWind.subtract(safeSpanDirection.scale(dot(safeRelativeWind, safeSpanDirection)));
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

	private static A4mcVec3 cross(A4mcVec3 a, A4mcVec3 b) {
		return A4mcVec3.of(
				a.y() * b.z() - a.z() * b.y(),
				a.z() * b.x() - a.x() * b.z(),
				a.x() * b.y() - a.y() * b.x()
		);
	}

	private static double dot(A4mcVec3 a, A4mcVec3 b) {
		return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
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

	private static A4mcVec3 transformPosition(Pose3dc pose, A4mcVec3 localPosition) {
		return fromJoml(pose.transformPosition(toJoml(localPosition), new Vector3d()));
	}

	private static A4mcVec3 transformNormalInverse(Pose3dc pose, A4mcVec3 worldDirection) {
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

	private static double distanceScore(BoundingBox3dc box, Vec3 anchor) {
		if (box == null || anchor == null) {
			return Double.POSITIVE_INFINITY;
		}
		double dx = axisDistance(anchor.x, box.minX(), box.maxX());
		double dy = axisDistance(anchor.y, box.minY(), box.maxY());
		double dz = axisDistance(anchor.z, box.minZ(), box.maxZ());
		double distanceSq = dx * dx + dy * dy + dz * dz;
		return box.contains(anchor.x, anchor.y, anchor.z) ? -1.0 + distanceSq : distanceSq;
	}

	private static double axisDistance(double value, double min, double max) {
		if (value < min) {
			return min - value;
		}
		if (value > max) {
			return value - max;
		}
		return 0.0;
	}

	private record SelectedWing(
			ClientSubLevel subLevel,
			CreateAeronauticsWingScanner.WingScanResult scan,
			CreateAeronauticsWingScanner.WingGroup group
	) {
	}

	private record PreviousFrame(UUID subLevelId, String shapeHash, A4mcVec3 samplePosition, long nanoTime) {
	}

	private record ForcePreview(double liftNewtons, double dragNewtons, double pitchingMomentNewtonMeters) {
	}

	private record PeakAngles(double positive, double negative) {
	}

	private record FlightTestState(
			String label,
			double stallMarginDegrees,
			double positivePeakAngleDegrees,
			double negativePeakAngleDegrees
	) {
	}

	public record Snapshot(
			boolean available,
			String status,
			String profileId,
			String tableHash,
			double relativeWindSpeedMetersPerSecond,
			double angleOfAttackDegrees,
			boolean hasCurrentSample,
			double liftCoefficient,
			double dragCoefficient,
			double momentCoefficient,
			double liftNewtons,
			double dragNewtons,
			double pitchingMomentNewtonMeters,
			double liftDragRatio,
			double stallMarginDegrees,
			double positivePeakAngleDegrees,
			double negativePeakAngleDegrees,
			String flightState,
			List<PolarPoint> points
	) {
		private static Snapshot empty(String status) {
			return new Snapshot(
					false,
					status,
					"",
					"",
					0.0,
					0.0,
					false,
					0.0,
					0.0,
					0.0,
					0.0,
					0.0,
					0.0,
					Double.NaN,
					Double.NaN,
					Double.NaN,
					Double.NaN,
					"",
					List.of()
			);
		}

		public Snapshot {
			status = Objects.requireNonNullElse(status, "");
			profileId = Objects.requireNonNullElse(profileId, "");
			tableHash = Objects.requireNonNullElse(tableHash, "");
			flightState = Objects.requireNonNullElse(flightState, "");
			points = points == null ? List.of() : List.copyOf(points);
		}
	}

	public record PolarPoint(
			double angleOfAttackDegrees,
			double liftCoefficient,
			double dragCoefficient,
			double momentCoefficient
	) {
	}
}
