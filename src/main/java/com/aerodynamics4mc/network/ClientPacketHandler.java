package com.aerodynamics4mc.network;

import com.aerodynamics4mc.ModTemplate;
import com.github.razorplay.packet_handler.network.IPacket;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ClientPacketHandler {
	private static final Handler MISSING_HANDLER = (packet, context) ->
			ModTemplate.LOGGER.warn("Received clientbound packet before client network handler registration: {}", packet.getPacketId());
	private static volatile Handler handler = MISSING_HANDLER;
	private static final List<AdditionalHandler> additionalHandlers = new CopyOnWriteArrayList<>();

	private ClientPacketHandler() {
	}

	public static void register(Handler clientHandler) {
		handler = Objects.requireNonNull(clientHandler, "clientHandler");
	}

	public static void registerAdditional(AdditionalHandler clientHandler) {
		additionalHandlers.add(Objects.requireNonNull(clientHandler, "clientHandler"));
	}

	public static void clear() {
		handler = MISSING_HANDLER;
		additionalHandlers.clear();
	}

	public static void handle(IPacket packet, Object context) {
		if (packet == null) {
			return;
		}
		for (AdditionalHandler additionalHandler : additionalHandlers) {
			if (additionalHandler.handle(packet, context)) {
				return;
			}
		}
		handler.handle(packet, context);
	}

	@FunctionalInterface
	public interface Handler {
		void handle(IPacket packet, Object context);
	}

	@FunctionalInterface
	public interface AdditionalHandler {
		boolean handle(IPacket packet, Object context);
	}
}
