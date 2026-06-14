package com.aerodynamics4mc.network;

import com.aerodynamics4mc.ModTemplate;
import com.github.razorplay.packet_handler.network.IPacket;

import java.util.Objects;

public final class ClientServerboundPacketSender {
	private static final Sender MISSING_SENDER = packet ->
			ModTemplate.LOGGER.warn("Dropped serverbound packet before client sender registration: {}", packet.getPacketId());
	private static volatile Sender sender = MISSING_SENDER;

	private ClientServerboundPacketSender() {
	}

	public static void register(Sender clientSender) {
		sender = Objects.requireNonNull(clientSender, "clientSender");
	}

	public static void clear() {
		sender = MISSING_SENDER;
	}

	public static void send(IPacket packet) {
		if (packet == null) {
			return;
		}
		sender.send(packet);
	}

	@FunctionalInterface
	public interface Sender {
		void send(IPacket packet);
	}
}
