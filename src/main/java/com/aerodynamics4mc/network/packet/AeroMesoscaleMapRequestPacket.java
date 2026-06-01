package com.aerodynamics4mc.network.packet;

import com.github.razorplay.packet_handler.exceptions.PacketSerializationException;
import com.github.razorplay.packet_handler.network.IPacket;
import com.github.razorplay.packet_handler.network.network_util.PacketDataSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AeroMesoscaleMapRequestPacket implements IPacket {
	private int layer;
	private boolean openScreen;

	@Override
	public void read(PacketDataSerializer serializer) throws PacketSerializationException {
		layer = serializer.readInt();
		openScreen = serializer.readBoolean();
	}

	@Override
	public void write(PacketDataSerializer serializer) throws PacketSerializationException {
		serializer.writeInt(layer);
		serializer.writeBoolean(openScreen);
	}
}
