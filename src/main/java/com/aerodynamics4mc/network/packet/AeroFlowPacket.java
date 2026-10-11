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
public class AeroFlowPacket implements IPacket {
	private Identifier dimensionId;
	private BlockPos origin;
	private int sampleStride;
	private short[] packedFlow;
	private byte[] packedFlowBytes;

	@Override
	public void read(PacketDataSerializer serializer) throws PacketSerializationException {
		dimensionId = MinecraftSerializer.readIdentifier(serializer);
		origin = MinecraftSerializer.readBlockPos(serializer);
		sampleStride = serializer.readInt();
		packedFlow = MinecraftSerializer.readShortArray(serializer);
		packedFlowBytes = serializer.readByteArray();
	}

	@Override
	public void write(PacketDataSerializer serializer) throws PacketSerializationException {
		MinecraftSerializer.writeIdentifier(serializer, dimensionId);
		MinecraftSerializer.writeBlockPos(serializer, origin);
		serializer.writeInt(sampleStride);
		MinecraftSerializer.writeShortArray(serializer, packedFlow);
		serializer.writeByteArray(packedFlowBytes);
	}

	public static byte[] encodePackedFlow(short[] packedFlow) {
		if (packedFlow == null) {
			return new byte[0];
		}
		byte[] bytes = new byte[packedFlow.length * Short.BYTES];
		for (int i = 0; i < packedFlow.length; i++) {
			short value = packedFlow[i];
			int base = i * Short.BYTES;
			bytes[base] = (byte) ((value >>> 8) & 0xFF);
			bytes[base + 1] = (byte) (value & 0xFF);
		}
		return bytes;
	}

	public static AeroFlowPacket create(
			Identifier dimensionId,
			BlockPos origin,
			int sampleStride,
			short[] packedFlow) {

		byte[] bytes = encodePackedFlow(packedFlow);
		return new AeroFlowPacket(dimensionId, origin, sampleStride, packedFlow, bytes);
	}

	/** The same field moved horizontally, as by whole periods of a wrapping world. */
	public AeroFlowPacket offset(int dx, int dz) {
		return new AeroFlowPacket(dimensionId, origin.offset(dx, 0, dz), sampleStride, packedFlow, packedFlowBytes);
	}
}
