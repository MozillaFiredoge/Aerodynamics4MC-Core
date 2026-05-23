package com.aerodynamics4mc.network;
//? neoforge {

import com.aerodynamics4mc.ModTemplate;
import com.github.razorplay.packet_handler.network.IPacket;
import com.github.razorplay.packet_handler.network.PacketTCP;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ForgeCustomPayload(IPacket packet) implements CustomPacketPayload {
	public static final Type<ForgeCustomPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "packets_channel"));

	public static final StreamCodec<FriendlyByteBuf, ForgeCustomPayload> STREAM_CODEC =
			StreamCodec.ofMember(ForgeCustomPayload::encode, ForgeCustomPayload::decode);

	public static void encode(ForgeCustomPayload payload, FriendlyByteBuf buffer) {
		try {
			buffer.writeBytes(PacketTCP.write(payload.packet()));
		} catch (Exception e) {
			ModTemplate.LOGGER.error("Error encoding packet: {}", e.getMessage());
		}
	}

	public static ForgeCustomPayload decode(FriendlyByteBuf buffer) {
		try {
			byte[] data = new byte[buffer.readableBytes()];
			buffer.readBytes(data);
			ByteArrayDataInput in = ByteStreams.newDataInput(data);
			IPacket packet = PacketTCP.read(in);
			return new ForgeCustomPayload(packet);
		} catch (Exception e) {
			ModTemplate.LOGGER.error("Error decoding packet: {}", e.getMessage());
			return null;
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?}
