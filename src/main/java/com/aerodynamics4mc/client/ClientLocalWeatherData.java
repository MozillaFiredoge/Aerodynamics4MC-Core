package com.aerodynamics4mc.client;

import com.aerodynamics4mc.network.packet.AeroLocalWeatherPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

public final class ClientLocalWeatherData {
	private static final int STALE_AFTER_TICKS = 220;
	private static final float MIN_VISIBLE_PRECIPITATION = 0.055f;
	private static final int RAIN_LEVEL_SAMPLE_RADIUS_BLOCKS = 48;
	private static final int RAIN_LEVEL_SAMPLE_STEP_BLOCKS = 16;

	private volatile AeroLocalWeatherPacket latest;
	private volatile long receivedGameTime;

	public void update(AeroLocalWeatherPacket packet) {
		latest = packet;
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel world = minecraft == null ? null : minecraft.level;
		receivedGameTime = world == null ? 0L : world.getGameTime();
	}

	public void clear() {
		latest = null;
		receivedGameTime = 0L;
	}

	public boolean hasActiveField(Level world) {
		return activePacket(world) != null;
	}

	public float localRainLevel(Level world, float fallback) {
		AeroLocalWeatherPacket packet = activePacket(world);
		if (packet == null) {
			return Mth.clamp(fallback, 0.0f, 1.0f);
		}
		Vec3 cameraPosition = cameraPosition();
		if (cameraPosition == null) {
			return 0.0f;
		}

		float maxPrecipitation = 0.0f;
		int centerX = Mth.floor(cameraPosition.x);
		int centerZ = Mth.floor(cameraPosition.z);
		for (int dx = -RAIN_LEVEL_SAMPLE_RADIUS_BLOCKS; dx <= RAIN_LEVEL_SAMPLE_RADIUS_BLOCKS; dx += RAIN_LEVEL_SAMPLE_STEP_BLOCKS) {
			for (int dz = -RAIN_LEVEL_SAMPLE_RADIUS_BLOCKS; dz <= RAIN_LEVEL_SAMPLE_RADIUS_BLOCKS; dz += RAIN_LEVEL_SAMPLE_STEP_BLOCKS) {
				maxPrecipitation = Math.max(
						maxPrecipitation,
						sampleChannel(packet, centerX + dx, centerZ + dz, AeroLocalWeatherPacket.CH_PRECIPITATION)
				);
			}
		}
		return Mth.clamp(maxPrecipitation, 0.0f, 1.0f);
	}

	public Biome.Precipitation precipitationAt(Level world, BlockPos pos) {
		AeroLocalWeatherPacket packet = activePacket(world);
		if (packet == null || pos == null) {
			return null;
		}
		float precipitation = sampleChannel(packet, pos.getX(), pos.getZ(), AeroLocalWeatherPacket.CH_PRECIPITATION);
		if (precipitation < MIN_VISIBLE_PRECIPITATION) {
			return Biome.Precipitation.NONE;
		}

		float coverage = smoothstep(MIN_VISIBLE_PRECIPITATION, 0.72f, precipitation);
		if (coverage < 0.985f && stableHashUnit(pos.getX(), pos.getZ(), packet.getServerTick() / 80L) > coverage) {
			return Biome.Precipitation.NONE;
		}

		float snowFraction = sampleChannel(packet, pos.getX(), pos.getZ(), AeroLocalWeatherPacket.CH_SNOW_FRACTION);
		return snowFraction > 0.52f ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
	}

	private AeroLocalWeatherPacket activePacket(Level world) {
		AeroLocalWeatherPacket packet = latest;
		if (packet == null || world == null || packet.getGridWidth() <= 0 || packet.getCellSizeBlocks() <= 0) {
			return null;
		}
		short[] packed = packet.getPackedWeather();
		int expectedLength = packet.getGridWidth() * packet.getGridWidth() * AeroLocalWeatherPacket.CHANNEL_COUNT;
		if (packed == null || packed.length != expectedLength) {
			return null;
		}
		Identifier dimension = world.dimension().identifier();
		if (!dimension.equals(packet.getDimensionType())) {
			return null;
		}
		if (world instanceof ClientLevel clientLevel && receivedGameTime > 0L) {
			long age = clientLevel.getGameTime() - receivedGameTime;
			if (age > STALE_AFTER_TICKS) {
				return null;
			}
		}
		return packet;
	}

	private Vec3 cameraPosition() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return null;
		}
		if (minecraft.gameRenderer != null && minecraft.gameRenderer.getMainCamera() != null) {
			return minecraft.gameRenderer.getMainCamera().position();
		}
		return minecraft.player == null ? null : minecraft.player.position();
	}

	private float sampleChannel(AeroLocalWeatherPacket packet, int blockX, int blockZ, int channel) {
		int gridWidth = packet.getGridWidth();
		if (channel < 0 || channel >= AeroLocalWeatherPacket.CHANNEL_COUNT || gridWidth <= 0) {
			return 0.0f;
		}
		double gridX = blockX / (double) packet.getCellSizeBlocks() - packet.getOriginCellX() - 0.5;
		double gridZ = blockZ / (double) packet.getCellSizeBlocks() - packet.getOriginCellZ() - 0.5;
		int x0 = Mth.floor(gridX);
		int z0 = Mth.floor(gridZ);
		float tx = (float) (gridX - x0);
		float tz = (float) (gridZ - z0);
		int x1 = x0 + 1;
		int z1 = z0 + 1;

		float v00 = channelAt(packet, clampIndex(x0, gridWidth), clampIndex(z0, gridWidth), channel);
		float v10 = channelAt(packet, clampIndex(x1, gridWidth), clampIndex(z0, gridWidth), channel);
		float v01 = channelAt(packet, clampIndex(x0, gridWidth), clampIndex(z1, gridWidth), channel);
		float v11 = channelAt(packet, clampIndex(x1, gridWidth), clampIndex(z1, gridWidth), channel);
		float vx0 = Mth.lerp(tx, v00, v10);
		float vx1 = Mth.lerp(tx, v01, v11);
		return Mth.clamp(Mth.lerp(tz, vx0, vx1), 0.0f, 1.0f);
	}

	private float channelAt(AeroLocalWeatherPacket packet, int localX, int localZ, int channel) {
		int index = (localX * packet.getGridWidth() + localZ) * AeroLocalWeatherPacket.CHANNEL_COUNT + channel;
		short[] packed = packet.getPackedWeather();
		if (index < 0 || packed == null || index >= packed.length) {
			return 0.0f;
		}
		float signed = packed[index] / (float) Short.MAX_VALUE;
		return Mth.clamp(signed * 0.5f + 0.5f, 0.0f, 1.0f);
	}

	private int clampIndex(int index, int size) {
		return Mth.clamp(index, 0, Math.max(0, size - 1));
	}

	private float smoothstep(float edge0, float edge1, float value) {
		if (!(edge1 > edge0)) {
			return value >= edge1 ? 1.0f : 0.0f;
		}
		float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
		return t * t * (3.0f - 2.0f * t);
	}

	private float stableHashUnit(int x, int z, long salt) {
		long h = (long) x * 341873128712L ^ (long) z * 132897987541L ^ salt * 42317861L;
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		h *= 0xc4ceb9fe1a85ec53L;
		h ^= h >>> 33;
		return ((h >>> 40) & 0xFFFFFFL) / (float) 0x1000000;
	}
}
