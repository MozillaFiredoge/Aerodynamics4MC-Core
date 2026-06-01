package com.aerodynamics4mc.client;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.network.packet.AeroMesoscaleMapPacket;
import com.aerodynamics4mc.network.packet.AeroMesoscaleMapRequestPacket;

public final class ClientMeteorologicalMapData {
	private volatile AeroMesoscaleMapPacket latest;

	public void update(AeroMesoscaleMapPacket packet) {
		if (packet == null || !hasExpectedLength(packet)) {
			return;
		}
		latest = packet;
	}

	public void clear() {
		latest = null;
	}

	public AeroMesoscaleMapPacket latest() {
		return latest;
	}

	public void requestRefresh(int layer) {
		ModTemplate.xplat().sendPacketToServer(new AeroMesoscaleMapRequestPacket(layer, false));
	}

	public float windX(AeroMesoscaleMapPacket packet, int localX, int localZ) {
		return signed(packet, localX, localZ, AeroMesoscaleMapPacket.CH_WIND_X, AeroMesoscaleMapPacket.HORIZONTAL_WIND_RANGE_MPS);
	}

	public float windY(AeroMesoscaleMapPacket packet, int localX, int localZ) {
		return signed(packet, localX, localZ, AeroMesoscaleMapPacket.CH_WIND_Y, AeroMesoscaleMapPacket.VERTICAL_WIND_RANGE_MPS);
	}

	public float windZ(AeroMesoscaleMapPacket packet, int localX, int localZ) {
		return signed(packet, localX, localZ, AeroMesoscaleMapPacket.CH_WIND_Z, AeroMesoscaleMapPacket.HORIZONTAL_WIND_RANGE_MPS);
	}

	public float windSpeed(AeroMesoscaleMapPacket packet, int localX, int localZ) {
		float x = windX(packet, localX, localZ);
		float y = windY(packet, localX, localZ);
		float z = windZ(packet, localX, localZ);
		return (float) Math.sqrt(x * x + y * y + z * z);
	}

	public float humidity(AeroMesoscaleMapPacket packet, int localX, int localZ) {
		return unit(packet, localX, localZ, AeroMesoscaleMapPacket.CH_HUMIDITY);
	}

	public float lift(AeroMesoscaleMapPacket packet, int localX, int localZ) {
		return signed(packet, localX, localZ, AeroMesoscaleMapPacket.CH_LIFT, AeroMesoscaleMapPacket.DIAGNOSTIC_RANGE);
	}

	public float terrainSolid(AeroMesoscaleMapPacket packet, int localX, int localZ) {
		return unit(packet, localX, localZ, AeroMesoscaleMapPacket.CH_TERRAIN_SOLID);
	}

	public int surfaceClass(AeroMesoscaleMapPacket packet, int localX, int localZ) {
		int index = channelIndex(packet, localX, localZ, AeroMesoscaleMapPacket.CH_SURFACE_CLASS);
		short[] packed = packet.getPackedLayer();
		if (index < 0 || packed == null || index >= packed.length) {
			return 0;
		}
		return packed[index] & 0xFF;
	}

	public float signed(AeroMesoscaleMapPacket packet, int localX, int localZ, int channel, float range) {
		int index = channelIndex(packet, localX, localZ, channel);
		short[] packed = packet.getPackedLayer();
		if (index < 0 || packed == null || index >= packed.length) {
			return 0.0f;
		}
		return normalizedSigned(packed[index]) * range;
	}

	public float unit(AeroMesoscaleMapPacket packet, int localX, int localZ, int channel) {
		float value = signed(packet, localX, localZ, channel, 1.0f);
		return clamp01(value * 0.5f + 0.5f);
	}

	public Metrics metrics(AeroMesoscaleMapPacket packet) {
		if (packet == null || !hasExpectedLength(packet)) {
			return Metrics.EMPTY;
		}
		float maxSpeed = 0.0f;
		float totalSpeed = 0.0f;
		int strongCellCount = 0;
		int cellCount = packet.getGridWidth() * packet.getGridWidth();
		for (int x = 0; x < packet.getGridWidth(); x++) {
			for (int z = 0; z < packet.getGridWidth(); z++) {
				float speed = windSpeed(packet, x, z);
				maxSpeed = Math.max(maxSpeed, speed);
				totalSpeed += speed;
				if (speed >= 3.0f) {
					strongCellCount++;
				}
			}
		}
		return new Metrics(maxSpeed, cellCount > 0 ? totalSpeed / cellCount : 0.0f, strongCellCount, cellCount);
	}

	private int channelIndex(AeroMesoscaleMapPacket packet, int localX, int localZ, int channel) {
		if (packet == null
				|| localX < 0
				|| localZ < 0
				|| channel < 0
				|| channel >= AeroMesoscaleMapPacket.CHANNEL_COUNT
				|| localX >= packet.getGridWidth()
				|| localZ >= packet.getGridWidth()) {
			return -1;
		}
		int columnIndex = localX * packet.getGridWidth() + localZ;
		return columnIndex * AeroMesoscaleMapPacket.CHANNEL_COUNT + channel;
	}

	private boolean hasExpectedLength(AeroMesoscaleMapPacket packet) {
		if (packet == null || packet.getGridWidth() <= 0 || packet.getPackedLayer() == null) {
			return false;
		}
		return packet.getPackedLayer().length == packet.getGridWidth() * packet.getGridWidth() * AeroMesoscaleMapPacket.CHANNEL_COUNT;
	}

	private static float normalizedSigned(short value) {
		return clamp(value / 32767.0f, -1.0f, 1.0f);
	}

	private static float clamp01(float value) {
		return clamp(value, 0.0f, 1.0f);
	}

	private static float clamp(float value, float min, float max) {
		if (!Float.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	public record Metrics(float maxSpeed, float meanSpeed, int strongCellCount, int cellCount) {
		public static final Metrics EMPTY = new Metrics(0.0f, 0.0f, 0, 0);
	}
}
