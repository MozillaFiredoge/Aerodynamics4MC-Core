package com.aerodynamics4mc.network;

import com.aerodynamics4mc.ModTemplate;
import com.github.razorplay.packet_handler.network.IPacket;

import java.util.Objects;

public final class ClientPacketHandler {
	private static final Handler MISSING_HANDLER = (packet, context) ->
			ModTemplate.LOGGER.warn("Received clientbound packet before client network handler registration: {}", packet.getPacketId());
	private static volatile Handler handler = MISSING_HANDLER;

	private ClientPacketHandler() {
	}

	public static void register(Handler clientHandler) {
		handler = Objects.requireNonNull(clientHandler, "clientHandler");
	}

	public static void clear() {
		handler = MISSING_HANDLER;
	}

	public static void handle(IPacket packet, Object context) {
		if (packet == null) {
			return;
		}
		handler.handle(packet, context);
	}

	@FunctionalInterface
	public interface Handler {
		void handle(IPacket packet, Object context);
	}
}
