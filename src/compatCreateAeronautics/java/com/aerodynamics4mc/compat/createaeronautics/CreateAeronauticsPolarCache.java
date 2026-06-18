package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.AeroPolarRequest;
import com.aerodynamics4mc.api.AeroPolarResult;
import com.aerodynamics4mc.api.AeroSurfaceDescriptor;
import com.aerodynamics4mc.api.AeroWindApi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class CreateAeronauticsPolarCache {
	public static final CreateAeronauticsPolarCache INSTANCE = new CreateAeronauticsPolarCache(64);

	private final int maxEntries;
	private final LinkedHashMap<PolarKey, AeroPolarResult> cache;
	private long hits;
	private long misses;

	public CreateAeronauticsPolarCache(int maxEntries) {
		if (maxEntries <= 0) {
			throw new IllegalArgumentException("maxEntries must be positive");
		}
		this.maxEntries = maxEntries;
		this.cache = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<PolarKey, AeroPolarResult> eldest) {
				return size() > CreateAeronauticsPolarCache.this.maxEntries;
			}
		};
	}

	public synchronized LookupResult getOrGenerate(AeroPolarRequest request) {
		Objects.requireNonNull(request, "request");
		PolarKey key = PolarKey.from(request);
		AeroPolarResult cached = cache.get(key);
		if (cached != null) {
			hits++;
			return new LookupResult(cached, key.shortHash(), true, cache.size(), hits, misses);
		}

		misses++;
		AeroPolarResult generated = AeroWindApi.runPolar(request);
		if (generated != null && generated.succeeded() && generated.hasTable()) {
			cache.put(key, generated);
		}
		return new LookupResult(generated, key.shortHash(), false, cache.size(), hits, misses);
	}

	public synchronized CacheStats stats() {
		return new CacheStats(cache.size(), maxEntries, hits, misses);
	}

	public synchronized CacheStats clear() {
		cache.clear();
		hits = 0L;
		misses = 0L;
		return stats();
	}

	public record LookupResult(
			AeroPolarResult result,
			String keyHash,
			boolean cacheHit,
			int cacheSize,
			long hits,
			long misses
	) {
	}

	public record CacheStats(int entries, int maxEntries, long hits, long misses) {
	}

	private record PolarKey(
			String shapeHash,
			String airfoilProfileId,
			double spanMeters,
			double chordMeters,
			double areaSquareMeters,
			double controlSurfaceRatio,
			A4mcVec3 chordDirection,
			A4mcVec3 spanDirection,
			A4mcVec3 normalDirection,
			int gridSize,
			int stepsPerSample,
			double minAngleOfAttackDegrees,
			double maxAngleOfAttackDegrees,
			double angleStepDegrees,
			List<Double> reynoldsNumbers,
			List<Double> controlDeflectionDegrees,
			boolean outputDebugFlowAtlas
	) {
		static PolarKey from(AeroPolarRequest request) {
			AeroSurfaceDescriptor surface = request.surface();
			return new PolarKey(
					surface.shapeHash(),
					surface.airfoilProfile().id().toString(),
					surface.spanMeters(),
					surface.chordMeters(),
					surface.areaSquareMeters(),
					surface.controlSurfaceRatio(),
					surface.chordDirection(),
					surface.spanDirection(),
					surface.normalDirection(),
					request.gridSize(),
					request.stepsPerSample(),
					request.minAngleOfAttackDegrees(),
					request.maxAngleOfAttackDegrees(),
					request.angleStepDegrees(),
					List.copyOf(request.reynoldsNumbers()),
					List.copyOf(request.controlDeflectionDegrees()),
					request.outputDebugFlowAtlas()
			);
		}

		String shortHash() {
			MessageDigest digest = sha256();
			update(digest, shapeHash);
			update(digest, airfoilProfileId);
			update(digest, format(spanMeters));
			update(digest, format(chordMeters));
			update(digest, format(areaSquareMeters));
			update(digest, format(controlSurfaceRatio));
			update(digest, format(chordDirection));
			update(digest, format(spanDirection));
			update(digest, format(normalDirection));
			update(digest, Integer.toString(gridSize));
			update(digest, Integer.toString(stepsPerSample));
			update(digest, format(minAngleOfAttackDegrees));
			update(digest, format(maxAngleOfAttackDegrees));
			update(digest, format(angleStepDegrees));
			update(digest, reynoldsNumbers.toString());
			update(digest, controlDeflectionDegrees.toString());
			update(digest, Boolean.toString(outputDebugFlowAtlas));
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

	private static String format(A4mcVec3 vec) {
		return format(vec.x()) + "," + format(vec.y()) + "," + format(vec.z());
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.8f", value);
	}
}
