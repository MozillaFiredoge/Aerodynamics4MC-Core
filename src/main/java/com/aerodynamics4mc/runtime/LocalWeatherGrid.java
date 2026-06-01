package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.network.packet.AeroLocalWeatherPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

final class LocalWeatherGrid {
	private static final int WEATHER_LAYER_COUNT = 4;
	private static final float FREEZING_KELVIN = 273.15f;

	private LocalWeatherGrid() {
	}

	static AeroLocalWeatherPacket buildPacket(Identifier dimensionType, MesoscaleGrid.Snapshot snapshot) {
		if (dimensionType == null || snapshot == null || snapshot.gridWidth() <= 0 || snapshot.activeLayers() <= 0) {
			return null;
		}
		int gridWidth = snapshot.gridWidth();
		short[] packed = new short[gridWidth * gridWidth * AeroLocalWeatherPacket.CHANNEL_COUNT];
		int layers = Math.min(snapshot.activeLayers(), WEATHER_LAYER_COUNT);
		for (int localX = 0; localX < gridWidth; localX++) {
			for (int localZ = 0; localZ < gridWidth; localZ++) {
				int column = localX * gridWidth + localZ;
				WeatherColumn columnWeather = computeColumn(snapshot, column, layers);
				int dstBase = column * AeroLocalWeatherPacket.CHANNEL_COUNT;
				packed[dstBase + AeroLocalWeatherPacket.CH_CLOUD_WATER] = quantizeUnitToShort(columnWeather.cloudWater());
				packed[dstBase + AeroLocalWeatherPacket.CH_PRECIPITATION] = quantizeUnitToShort(columnWeather.precipitation());
				packed[dstBase + AeroLocalWeatherPacket.CH_SNOW_FRACTION] = quantizeUnitToShort(columnWeather.snowFraction());
			}
		}
		int originCellX = snapshot.centerCellX() - snapshot.radiusCells();
		int originCellZ = snapshot.centerCellZ() - snapshot.radiusCells();
		return new AeroLocalWeatherPacket(
				dimensionType,
				gridWidth,
				snapshot.cellSizeBlocks(),
				originCellX,
				originCellZ,
				snapshot.centerCellX(),
				snapshot.centerCellZ(),
				snapshot.lastTickProcessed(),
				packed
		);
	}

	private static WeatherColumn computeColumn(MesoscaleGrid.Snapshot snapshot, int column, int layers) {
		float cloudWater = 0.0f;
		float precipitation = 0.0f;
		float snowWeight = 0.0f;
		float precipWeight = 0.0f;
		for (int layer = 0; layer < layers; layer++) {
			int state = column * snapshot.activeLayers() + layer;
			if (state < 0 || state >= snapshot.humidity().length || snapshot.terrainSolidMask()[state] > 0.5f) {
				continue;
			}
			float humidity = Mth.clamp(finiteOrDefault(snapshot.humidity()[state], 0.0f), 0.0f, 1.0f);
			float convergence = Math.max(0.0f, finiteOrDefault(snapshot.moistureConvergence()[state], 0.0f));
			float convergenceNorm = Mth.clamp(convergence * snapshot.cellSizeBlocks() * 8.0f, 0.0f, 1.5f);
			float liftNorm = Mth.clamp(finiteOrDefault(snapshot.liftProxy()[state], 0.0f), 0.0f, 1.5f);
			float instabilityNorm = Mth.clamp(finiteOrDefault(snapshot.instabilityProxy()[state], 0.0f) / 8.0f, 0.0f, 1.2f);
			float temperatureKelvin = finiteOrDefault(snapshot.ambientAirTemperatureKelvin()[state], 288.15f);
			float warmDryPenalty = Mth.clamp((temperatureKelvin - 294.15f) / 28.0f, 0.0f, 0.18f) * (1.0f - humidity);
			float layerWeight = 1.0f - 0.18f * layer;
			float cloudPotential = (
					humidity * 0.78f
							+ convergenceNorm * 0.16f
							+ liftNorm * 0.14f
							+ instabilityNorm * 0.04f
							- warmDryPenalty
			) * layerWeight;
			float layerCloud = smoothstep(0.42f, 0.72f, cloudPotential);
			float layerPrecipitation = smoothstep(0.56f, 0.86f, cloudPotential + liftNorm * 0.07f + convergenceNorm * 0.07f)
					* (0.35f + layerCloud * 0.65f);
			cloudWater = Math.max(cloudWater, layerCloud);
			precipitation = Math.max(precipitation, layerPrecipitation);

			float layerSnow = 1.0f - smoothstep(FREEZING_KELVIN - 4.0f, FREEZING_KELVIN + 3.0f, temperatureKelvin);
			snowWeight += layerSnow * layerPrecipitation;
			precipWeight += layerPrecipitation;
		}
		float snowFraction = precipWeight > 0.001f ? snowWeight / precipWeight : 0.0f;
		return new WeatherColumn(
				Mth.clamp(cloudWater, 0.0f, 1.0f),
				Mth.clamp(precipitation, 0.0f, 1.0f),
				Mth.clamp(snowFraction, 0.0f, 1.0f)
		);
	}

	private static short quantizeUnitToShort(float value) {
		float signed = Mth.clamp(finiteOrDefault(value, 0.0f), 0.0f, 1.0f) * 2.0f - 1.0f;
		return (short) Math.round(signed * Short.MAX_VALUE);
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		if (!(edge1 > edge0)) {
			return value >= edge1 ? 1.0f : 0.0f;
		}
		float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
		return t * t * (3.0f - 2.0f * t);
	}

	private static float finiteOrDefault(float value, float fallback) {
		if (Float.isFinite(value)) {
			return value;
		}
		return Float.isFinite(fallback) ? fallback : 0.0f;
	}

	private record WeatherColumn(float cloudWater, float precipitation, float snowFraction) {
	}
}
