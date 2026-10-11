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
public class AeroFlowAnalysisPacket implements IPacket {
	private Identifier dimensionId;
	private BlockPos origin;
	private int baseSampleStride;
	private int fullResolution;
	private float velocityTolerance;
	private float pressureTolerance;
	private short[] basePackedFlow;
	private byte[] residualVx;
	private byte[] residualVy;
	private byte[] residualVz;
	private byte[] residualPressure;

	@Override
	public void read(PacketDataSerializer serializer) throws PacketSerializationException {
		dimensionId = MinecraftSerializer.readIdentifier(serializer);
		origin = MinecraftSerializer.readBlockPos(serializer);
		baseSampleStride = serializer.readInt();
		fullResolution = serializer.readInt();
		velocityTolerance = serializer.readFloat();
		pressureTolerance = serializer.readFloat();
		basePackedFlow = MinecraftSerializer.readShortArray(serializer);
		residualVx = serializer.readByteArray();
		residualVy = serializer.readByteArray();
		residualVz = serializer.readByteArray();
		residualPressure = serializer.readByteArray();
	}

	@Override
	public void write(PacketDataSerializer serializer) throws PacketSerializationException {
		MinecraftSerializer.writeIdentifier(serializer, dimensionId);
		MinecraftSerializer.writeBlockPos(serializer, origin);
		serializer.writeInt(baseSampleStride);
		serializer.writeInt(fullResolution);
		serializer.writeFloat(velocityTolerance);
		serializer.writeFloat(pressureTolerance);
		MinecraftSerializer.writeShortArray(serializer, basePackedFlow);
		serializer.writeByteArray(residualVx);
		serializer.writeByteArray(residualVy);
		serializer.writeByteArray(residualVz);
		serializer.writeByteArray(residualPressure);

	}

	/** The same field moved horizontally, as by whole periods of a wrapping world. */
	public AeroFlowAnalysisPacket offset(int dx, int dz) {
		return new AeroFlowAnalysisPacket(dimensionId, origin.offset(dx, 0, dz), baseSampleStride, fullResolution,
				velocityTolerance, pressureTolerance, basePackedFlow, residualVx, residualVy, residualVz, residualPressure);
	}
}
