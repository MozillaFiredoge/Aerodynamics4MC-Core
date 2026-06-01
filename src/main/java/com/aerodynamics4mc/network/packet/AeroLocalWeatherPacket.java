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
public class AeroLocalWeatherPacket implements IPacket {
	public static final int CHANNEL_COUNT = 3;
	public static final int CH_CLOUD_WATER = 0;
	public static final int CH_PRECIPITATION = 1;
	public static final int CH_SNOW_FRACTION = 2;

	private Identifier dimensionType;
	private int gridWidth;
	private int cellSizeBlocks;
	private int originCellX;
	private int originCellZ;
	private int centerCellX;
	private int centerCellZ;
	private long serverTick;
	private short[] packedWeather;

	@Override
	public void read(PacketDataSerializer serializer) throws PacketSerializationException {
		dimensionType = MinecraftSerializer.readIdentifier(serializer);
		gridWidth = serializer.readInt();
		cellSizeBlocks = serializer.readInt();
		originCellX = serializer.readInt();
		originCellZ = serializer.readInt();
		centerCellX = serializer.readInt();
		centerCellZ = serializer.readInt();
		serverTick = serializer.readLong();
		packedWeather = MinecraftSerializer.readShortArray(serializer);
	}

	@Override
	public void write(PacketDataSerializer serializer) throws PacketSerializationException {
		MinecraftSerializer.writeIdentifier(serializer, dimensionType);
		serializer.writeInt(gridWidth);
		serializer.writeInt(cellSizeBlocks);
		serializer.writeInt(originCellX);
		serializer.writeInt(originCellZ);
		serializer.writeInt(centerCellX);
		serializer.writeInt(centerCellZ);
		serializer.writeLong(serverTick);
		MinecraftSerializer.writeShortArray(serializer, packedWeather != null ? packedWeather : new short[0]);
	}
}
