package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.ModTemplate;
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

	public record LoadResult(Path root, int loaded, int failed) {
	}
}
