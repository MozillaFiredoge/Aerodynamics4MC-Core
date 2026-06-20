package com.aerodynamics4mc.compat.createaeronautics.client;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroAirfoilCoordinate;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.GsonHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AirfoilWingVisualLibrary implements ResourceManagerReloadListener {
	public static final AirfoilWingVisualLibrary INSTANCE = new AirfoilWingVisualLibrary();
	private static final String RESOURCE_ROOT = "aerodynamics4mc/airfoil_visuals";
	private static final String FORMAT = "a4mc-airfoil-visual-v1";

	private volatile Map<A4mcId, AirfoilVisual> visuals = Map.of();

	private AirfoilWingVisualLibrary() {
	}

	public Optional<AirfoilVisual> find(A4mcId id) {
		return Optional.ofNullable(visuals.get(id));
	}

	@Override
	public void onResourceManagerReload(ResourceManager resourceManager) {
		Map<A4mcId, AirfoilVisual> loaded = new LinkedHashMap<>();
		for (Map.Entry<Identifier, Resource> entry : resourceManager.listResources(
				RESOURCE_ROOT,
				id -> id.getPath().endsWith(".json")
		).entrySet()) {
			Identifier resourceId = entry.getKey();
			try (BufferedReader reader = entry.getValue().openAsReader()) {
				A4mcId airfoilId = airfoilId(resourceId);
				loaded.put(airfoilId, readVisual(GsonHelper.parse(reader)));
			} catch (IOException | IllegalArgumentException e) {
				ModTemplate.LOGGER.warn("Failed to load A4MC airfoil visual {}: {}", resourceId, e.getMessage());
			}
		}
		visuals = Map.copyOf(loaded);
		if (!loaded.isEmpty()) {
			ModTemplate.LOGGER.info("Loaded {} A4MC airfoil visual resource(s)", loaded.size());
		}
	}

	private static A4mcId airfoilId(Identifier resourceId) {
		String path = resourceId.getPath();
		String prefix = RESOURCE_ROOT + "/";
		if (!path.startsWith(prefix) || !path.endsWith(".json")) {
			throw new IllegalArgumentException("unexpected airfoil visual path: " + resourceId);
		}
		String airfoilPath = path.substring(prefix.length(), path.length() - ".json".length());
		return A4mcId.of(resourceId.getNamespace(), airfoilPath);
	}

	private static AirfoilVisual readVisual(JsonObject root) {
		String format = GsonHelper.getAsString(root, "format", FORMAT);
		if (!FORMAT.equals(format)) {
			throw new IllegalArgumentException("unsupported airfoil visual format: " + format);
		}
		return new AirfoilVisual(
				readCoordinates(root),
				GsonHelper.getAsDouble(root, "chord_scale", AirfoilVisual.DEFAULT.chordScale()),
				GsonHelper.getAsDouble(root, "span_scale", AirfoilVisual.DEFAULT.spanScale()),
				GsonHelper.getAsDouble(root, "section_y_scale", AirfoilVisual.DEFAULT.sectionYScale()),
				GsonHelper.getAsDouble(root, "section_y_offset", AirfoilVisual.DEFAULT.sectionYOffset()),
				color(root, "front_color", AirfoilVisual.DEFAULT.frontColor()),
				color(root, "back_color", AirfoilVisual.DEFAULT.backColor()),
				color(root, "connector_color", AirfoilVisual.DEFAULT.connectorColor()),
				color(root, "chord_color", AirfoilVisual.DEFAULT.chordColor())
		);
	}

	private static List<AeroAirfoilCoordinate> readCoordinates(JsonObject root) {
		if (!root.has("coordinates")) {
			return List.of();
		}
		JsonArray array = GsonHelper.getAsJsonArray(root, "coordinates");
		List<AeroAirfoilCoordinate> coordinates = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			JsonObject object = GsonHelper.convertToJsonObject(array.get(i), "coordinates[" + i + "]");
			coordinates.add(new AeroAirfoilCoordinate(
					GsonHelper.getAsDouble(object, "x"),
					GsonHelper.getAsDouble(object, "y")
			));
		}
		return List.copyOf(coordinates);
	}

	private static int color(JsonObject root, String key, int fallback) {
		if (!root.has(key)) {
			return fallback;
		}
		JsonElement element = root.get(key);
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			return element.getAsInt();
		}
		String value = GsonHelper.convertToString(element, key).trim();
		if (value.startsWith("#")) {
			value = value.substring(1);
		} else if (value.startsWith("0x") || value.startsWith("0X")) {
			value = value.substring(2);
		}
		if (value.length() == 6) {
			return 0xFF000000 | Integer.parseUnsignedInt(value, 16);
		}
		if (value.length() == 8) {
			return (int) Long.parseLong(value, 16);
		}
		throw new IllegalArgumentException(key + " must be #RRGGBB or #AARRGGBB");
	}

	public record AirfoilVisual(
			List<AeroAirfoilCoordinate> coordinates,
			double chordScale,
			double spanScale,
			double sectionYScale,
			double sectionYOffset,
			int frontColor,
			int backColor,
			int connectorColor,
			int chordColor
	) {
		public static final AirfoilVisual DEFAULT = new AirfoilVisual(
				List.of(),
				0.90,
				0.92,
				2.20,
				0.50,
				0xE8E4FBFF,
				0xB8608C96,
				0xAA94CBD6,
				0xFFE7C86E
		);

		public AirfoilVisual {
			coordinates = coordinates == null ? List.of() : List.copyOf(coordinates);
			chordScale = finitePositive("chord_scale", chordScale);
			spanScale = finitePositive("span_scale", spanScale);
			sectionYScale = finitePositive("section_y_scale", sectionYScale);
			if (!Double.isFinite(sectionYOffset)) {
				throw new IllegalArgumentException("section_y_offset must be finite");
			}
		}

		public List<AeroAirfoilCoordinate> coordinatesOr(List<AeroAirfoilCoordinate> fallback) {
			return coordinates.isEmpty() ? fallback : coordinates;
		}

		private static double finitePositive(String name, double value) {
			if (!Double.isFinite(value) || value <= 0.0) {
				throw new IllegalArgumentException(name + " must be finite and positive");
			}
			return value;
		}
	}
}
