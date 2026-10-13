package com.aerodynamics4mc.network.packet;

import com.aerodynamics4mc.network.util.MinecraftSerializer;
import com.github.razorplay.packet_handler.exceptions.PacketSerializationException;
import com.github.razorplay.packet_handler.network.IPacket;
import com.github.razorplay.packet_handler.network.network_util.PacketDataSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AeroCoarseWindPacket implements IPacket {
	public static final float VELOCITY_RANGE_MPS = com.aerodynamics4mc.api.AeroWindLimits.MAX_WIND_SPEED_MPS;
	private Identifier dimensionType;
	private BlockPos origin;
	private int cellSize;
	private int sizeX;
	private int sizeY;
	private int sizeZ;
	private long serverTick;
	private short[] packedFlow;
	private short[] packedAtmosphere;

	@Override
	public void read(PacketDataSerializer serializer) throws PacketSerializationException {
		dimensionType = MinecraftSerializer.readIdentifier(serializer);
		origin = MinecraftSerializer.readBlockPos(serializer);
		cellSize = serializer.readInt();
		sizeX = serializer.readInt();
		sizeY = serializer.readInt();
		sizeZ = serializer.readInt();
		serverTick = serializer.readLong();
		packedFlow = MinecraftSerializer.readShortArray(serializer);
		packedAtmosphere = MinecraftSerializer.readShortArray(serializer);
	}

	@Override
	public void write(PacketDataSerializer serializer) throws PacketSerializationException {
		MinecraftSerializer.writeIdentifier(serializer, dimensionType);
		MinecraftSerializer.writeBlockPos(serializer, origin);
		serializer.writeInt(cellSize);
		serializer.writeInt(sizeX);
		serializer.writeInt(sizeY);
		serializer.writeInt(sizeZ);
		serializer.writeLong(serverTick);
		MinecraftSerializer.writeShortArray(serializer, packedFlow != null ? packedFlow : new short[0]);
		MinecraftSerializer.writeShortArray(serializer, packedAtmosphere != null ? packedAtmosphere : new short[0]);
	}

	/** The same field moved horizontally, as by whole periods of a wrapping world. */
	public AeroCoarseWindPacket offset(int dx, int dz) {
		return new AeroCoarseWindPacket(dimensionType, origin.offset(dx, 0, dz), cellSize, sizeX, sizeY, sizeZ,
				serverTick, packedFlow, packedAtmosphere);
	}
}
