package com.aerodynamics4mc.network.packet;

import com.aerodynamics4mc.network.util.MinecraftSerializer;
import com.github.razorplay.packet_handler.exceptions.PacketSerializationException;
import com.github.razorplay.packet_handler.network.IPacket;
import com.github.razorplay.packet_handler.network.network_util.PacketDataSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.minecraft.resources.Identifier;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AeroMesoscaleMapPacket implements IPacket {
	public static final int CHANNEL_COUNT = 17;
	public static final int CH_WIND_X = 0;
	public static final int CH_WIND_Y = 1;
	public static final int CH_WIND_Z = 2;
	public static final int CH_SURFACE_WIND_X = 3;
	public static final int CH_SURFACE_WIND_Z = 4;
	public static final int CH_GEOSTROPHIC_WIND_X = 5;
	public static final int CH_GEOSTROPHIC_WIND_Z = 6;
	public static final int CH_HUMIDITY = 7;
	public static final int CH_INSTABILITY = 8;
	public static final int CH_LOW_LEVEL_SHEAR = 9;
	public static final int CH_MOISTURE_CONVERGENCE = 10;
	public static final int CH_LIFT = 11;
	public static final int CH_ABL_AGL_HEIGHT = 12;
	public static final int CH_ABL_STABILITY = 13;
	public static final int CH_ABL_MIXING = 14;
	public static final int CH_TERRAIN_SOLID = 15;
	public static final int CH_SURFACE_CLASS = 16;

	public static final float HORIZONTAL_WIND_RANGE_MPS = 24.0f;
	public static final float VERTICAL_WIND_RANGE_MPS = 8.0f;
	public static final float DIAGNOSTIC_RANGE = 8.0f;
	public static final float SHEAR_RANGE = 8.0f;
	public static final float ABL_HEIGHT_RANGE_BLOCKS = 480.0f;

	private Identifier dimensionType;
	private int gridWidth;
	private int activeLayers;
	private int layer;
	private int cellSizeBlocks;
	private int layerHeightBlocks;
	private int radiusCells;
	private int centerCellX;
	private int centerCellZ;
	private int verticalBaseY;
	private int playerCellX;
	private int playerCellZ;
	private long serverTick;
	private boolean openScreen;
	private short[] packedLayer;

	@Override
	public void read(PacketDataSerializer serializer) throws PacketSerializationException {
		dimensionType = MinecraftSerializer.readIdentifier(serializer);
		gridWidth = serializer.readInt();
		activeLayers = serializer.readInt();
		layer = serializer.readInt();
		cellSizeBlocks = serializer.readInt();
		layerHeightBlocks = serializer.readInt();
		radiusCells = serializer.readInt();
		centerCellX = serializer.readInt();
		centerCellZ = serializer.readInt();
		verticalBaseY = serializer.readInt();
		playerCellX = serializer.readInt();
		playerCellZ = serializer.readInt();
		serverTick = serializer.readLong();
		openScreen = serializer.readBoolean();
		packedLayer = MinecraftSerializer.readShortArray(serializer);
	}

	@Override
	public void write(PacketDataSerializer serializer) throws PacketSerializationException {
		MinecraftSerializer.writeIdentifier(serializer, dimensionType);
		serializer.writeInt(gridWidth);
		serializer.writeInt(activeLayers);
		serializer.writeInt(layer);
		serializer.writeInt(cellSizeBlocks);
		serializer.writeInt(layerHeightBlocks);
		serializer.writeInt(radiusCells);
		serializer.writeInt(centerCellX);
		serializer.writeInt(centerCellZ);
		serializer.writeInt(verticalBaseY);
		serializer.writeInt(playerCellX);
		serializer.writeInt(playerCellZ);
		serializer.writeLong(serverTick);
		serializer.writeBoolean(openScreen);
		MinecraftSerializer.writeShortArray(serializer, packedLayer != null ? packedLayer : new short[0]);
	}
}
