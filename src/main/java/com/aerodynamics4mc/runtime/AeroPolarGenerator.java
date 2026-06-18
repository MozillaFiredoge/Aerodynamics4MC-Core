package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.api.AeroAirfoilProfile;
import com.aerodynamics4mc.api.AeroPolarRequest;
import com.aerodynamics4mc.api.AeroPolarResult;
import com.aerodynamics4mc.api.AeroPolarSample;
import com.aerodynamics4mc.api.AeroPolarTable;
import com.aerodynamics4mc.api.AeroSurfaceDescriptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AeroPolarGenerator {
	private static final String SOLVER_ID = "aerodynamics4mc:analytic_polar_v1";

	private AeroPolarGenerator() {
	}

	public static AeroPolarResult run(AeroPolarRequest request) {
		if (request == null) {
			return AeroPolarResult.failure(null, "request must not be null", SOLVER_ID);
		}
		try {
			List<AeroPolarSample> samples = new ArrayList<>(request.totalSampleCount());
			for (double reynoldsNumber : request.reynoldsNumbers()) {
				for (double deflectionDegrees : request.controlDeflectionDegrees()) {
					for (int i = 0; i < request.angleSampleCount(); i++) {
						samples.add(sample(request.surface(), request.angleAt(i), reynoldsNumber, deflectionDegrees));
					}
				}
			}
			AeroPolarTable table = new AeroPolarTable(
					request.surface(),
					samples,
					SOLVER_ID,
					tableHash(request, samples),
					System.currentTimeMillis()
			);
			return AeroPolarResult.success(request, table, SOLVER_ID);
		} catch (RuntimeException exception) {
			String message = exception.getMessage() == null
					? exception.getClass().getSimpleName()
					: exception.getMessage();
			return AeroPolarResult.failure(request, message, SOLVER_ID);
		}
	}

	private static AeroPolarSample sample(
			AeroSurfaceDescriptor surface,
			double angleOfAttackDegrees,
			double reynoldsNumber,
			double controlDeflectionDegrees
	) {
		AeroAirfoilProfile profile = surface.airfoilProfile();
		double aspectRatio = clamp(surface.aspectRatio(), 0.25, 24.0);
		double controlEffectiveness = 0.75 * surface.controlSurfaceRatio();
		double effectiveAngleDegrees = angleOfAttackDegrees + controlEffectiveness * controlDeflectionDegrees;
		double zeroLiftAngleDegrees = zeroLiftAngleDegrees(profile);
		double relativeAngleDegrees = effectiveAngleDegrees - zeroLiftAngleDegrees;
		double relativeAngleRadians = Math.toRadians(relativeAngleDegrees);

		double liftSlope = 2.0 * Math.PI * aspectRatio / (aspectRatio + 2.0);
		double stallAngleDegrees = stallAngleDegrees(profile, aspectRatio);
		double maxLift = maxLiftCoefficient(profile, aspectRatio);
		double cl = clamp(liftSlope * relativeAngleRadians, -maxLift, maxLift);
		cl *= postStallLiftFactor(relativeAngleDegrees, stallAngleDegrees);

		double cd0 = profileDragCoefficient(profile) * reynoldsDragFactor(reynoldsNumber);
		double oswaldEfficiency = clamp(0.64 + 0.22 * Math.min(1.0, aspectRatio / 8.0), 0.62, 0.88);
		double inducedDrag = cl * cl / (Math.PI * aspectRatio * oswaldEfficiency);
		double stallDrag = stallDrag(relativeAngleDegrees, stallAngleDegrees);
		double controlDrag = 0.002 * Math.abs(controlDeflectionDegrees) * surface.controlSurfaceRatio();
		double cd = Math.max(0.001, cd0 + inducedDrag + stallDrag + controlDrag);

		double cm = momentCoefficient(profile) - 0.015 * cl - 0.0025 * controlDeflectionDegrees * surface.controlSurfaceRatio();
		return new AeroPolarSample(
				angleOfAttackDegrees,
				controlDeflectionDegrees,
				reynoldsNumber,
				cl,
				cd,
				cm
		);
	}

	private static double zeroLiftAngleDegrees(AeroAirfoilProfile profile) {
		if (profile.kind() == AeroAirfoilProfile.Kind.FLAT_PLATE || profile.symmetric()) {
			return 0.0;
		}
		return -120.0 * profile.maxCamberRatio();
	}

	private static double stallAngleDegrees(AeroAirfoilProfile profile, double aspectRatio) {
		double base = profile.kind() == AeroAirfoilProfile.Kind.FLAT_PLATE ? 11.0 : 13.5;
		double thicknessBonus = 18.0 * clamp(profile.thicknessRatio(), 0.0, 0.18);
		double aspectPenalty = Math.max(0.0, 4.0 - aspectRatio) * 0.35;
		return clamp(base + thicknessBonus - aspectPenalty, 8.0, 18.0);
	}

	private static double maxLiftCoefficient(AeroAirfoilProfile profile, double aspectRatio) {
		double base = profile.kind() == AeroAirfoilProfile.Kind.FLAT_PLATE ? 0.95 : 1.15;
		double camberBonus = 4.0 * profile.maxCamberRatio();
		double aspectBonus = 0.04 * Math.min(aspectRatio, 8.0);
		return clamp(base + camberBonus + aspectBonus, 0.8, 1.65);
	}

	private static double postStallLiftFactor(double relativeAngleDegrees, double stallAngleDegrees) {
		double excess = Math.max(0.0, Math.abs(relativeAngleDegrees) - stallAngleDegrees);
		if (excess <= 0.0) {
			return 1.0;
		}
		double span = Math.max(1.0, 90.0 - stallAngleDegrees);
		return clamp(1.0 - 0.75 * excess / span, 0.25, 1.0);
	}

	private static double profileDragCoefficient(AeroAirfoilProfile profile) {
		if (profile.kind() == AeroAirfoilProfile.Kind.FLAT_PLATE) {
			return 0.035;
		}
		double thickness = profile.thicknessRatio();
		return clamp(0.012 + 0.08 * thickness * thickness + 0.4 * profile.maxCamberRatio() * profile.maxCamberRatio(), 0.012, 0.035);
	}

	private static double reynoldsDragFactor(double reynoldsNumber) {
		if (reynoldsNumber <= 0.0) {
			return 1.0;
		}
		return clamp(Math.sqrt(200_000.0 / reynoldsNumber), 0.55, 2.5);
	}

	private static double stallDrag(double relativeAngleDegrees, double stallAngleDegrees) {
		double excess = Math.max(0.0, Math.abs(relativeAngleDegrees) - stallAngleDegrees);
		if (excess <= 0.0) {
			return 0.0;
		}
		double t = excess / Math.max(1.0, 90.0 - stallAngleDegrees);
		return 1.1 * t * t;
	}

	private static double momentCoefficient(AeroAirfoilProfile profile) {
		if (profile.kind() == AeroAirfoilProfile.Kind.FLAT_PLATE || profile.symmetric()) {
			return 0.0;
		}
		return -2.5 * profile.maxCamberRatio();
	}

	private static String tableHash(AeroPolarRequest request, List<AeroPolarSample> samples) {
		MessageDigest digest = sha256();
		update(digest, request.surface().shapeHash());
		update(digest, request.surface().airfoilProfile().id().toString());
		update(digest, format(request.gridSize()));
		update(digest, format(request.stepsPerSample()));
		update(digest, format(request.minAngleOfAttackDegrees()));
		update(digest, format(request.maxAngleOfAttackDegrees()));
		update(digest, format(request.angleStepDegrees()));
		for (AeroPolarSample sample : samples) {
			update(digest, format(sample.angleOfAttackDegrees())
					+ "," + format(sample.controlDeflectionDegrees())
					+ "," + format(sample.reynoldsNumber())
					+ "," + format(sample.liftCoefficient())
					+ "," + format(sample.dragCoefficient())
					+ "," + format(sample.momentCoefficient()));
		}
		byte[] bytes = digest.digest();
		StringBuilder builder = new StringBuilder(16);
		for (int i = 0; i < 8; i++) {
			int value = bytes[i] & 0xff;
			if (value < 16) {
				builder.append('0');
			}
			builder.append(Integer.toHexString(value));
		}
		return builder.toString();
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 digest is unavailable", e);
		}
	}

	private static void update(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) '\n');
	}

	private static String format(int value) {
		return Integer.toString(value);
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.8f", value);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
