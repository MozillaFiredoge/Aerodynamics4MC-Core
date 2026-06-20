package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import com.aerodynamics4mc.api.AeroAirfoilJson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class CreateAeronauticsAirfoilDiskStore {
	private CreateAeronauticsAirfoilDiskStore() {
	}

	public static Path root(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT)
				.resolve("aerodynamics4mc")
				.resolve("airfoils")
				.toAbsolutePath()
				.normalize();
	}

	public static LoadResult loadAll(MinecraftServer server) {
		Path root = root(server);
		if (!Files.isDirectory(root)) {
			return new LoadResult(root, 0, 0);
		}
		int loaded = 0;
		int failed = 0;
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".json"))
					.toList()) {
				try {
					AeroAirfoilDefinition definition = AeroAirfoilJson.read(Files.readString(path, StandardCharsets.UTF_8));
					CreateAeronauticsAirfoilLibrary.register(definition);
					loaded++;
				} catch (IOException | IllegalArgumentException e) {
					failed++;
					ModTemplate.LOGGER.warn("Failed to load Create Aeronautics airfoil JSON {}: {}", path, e.getMessage());
				}
			}
		} catch (IOException e) {
			ModTemplate.LOGGER.warn("Failed to scan Create Aeronautics airfoil directory {}: {}", root, e.getMessage());
			failed++;
		}
		return new LoadResult(root, loaded, failed);
	}

	public static Path airfoilPathFor(MinecraftServer server, A4mcId id) {
		return airfoilPathFor(root(server), id);
	}

	public static Path airfoilPathFor(Path root, A4mcId id) {
		Path path = root.resolve(id.namespace());
		String[] segments = id.path().split("/");
		for (int i = 0; i < segments.length; i++) {
			String segment = safePathSegment(segments[i]);
			path = path.resolve(i + 1 == segments.length ? segment + ".json" : segment);
		}
		return requireInside(root, path);
	}

	public static Path resolveAirfoilPath(MinecraftServer server, String pathText) {
		return resolveAirfoilPath(root(server), pathText);
	}

	public static Path resolveAirfoilPath(Path root, String pathText) {
		String safeText = pathText == null ? "" : pathText.trim();
		if (safeText.isEmpty()) {
			throw new IllegalArgumentException("path must not be empty");
		}
		if (!safeText.endsWith(".json")) {
			safeText += ".json";
		}
		Path rawPath = Path.of(safeText);
		if (rawPath.isAbsolute()) {
			throw new IllegalArgumentException("path must be relative to " + root);
		}
		for (Path segment : rawPath) {
			safePathSegment(segment.toString());
		}
		return requireInside(root, root.resolve(rawPath));
	}

	public static ExportResult exportDefinition(MinecraftServer server, AeroAirfoilDefinition definition) throws IOException {
		Path outputPath = airfoilPathFor(server, definition.id());
		Files.createDirectories(outputPath.getParent());
		Files.writeString(outputPath, AeroAirfoilJson.write(definition), StandardCharsets.UTF_8);
		return new ExportResult(outputPath);
	}

	private static Path requireInside(Path root, Path path) {
		Path safeRoot = root.toAbsolutePath().normalize();
		Path safePath = path.toAbsolutePath().normalize();
		if (!safePath.startsWith(safeRoot)) {
			throw new IllegalArgumentException("path escapes airfoil root: " + path);
		}
		return safePath;
	}

	private static String safePathSegment(String segment) {
		if (segment == null || segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
			throw new IllegalArgumentException("invalid path segment: " + segment);
		}
		return segment;
	}

	public record LoadResult(Path root, int loaded, int failed) {
	}

	public record ExportResult(Path outputPath) {
	}
}
