package com.aerodynamics4mc.network;

import com.github.razorplay.packet_handler.network.IPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ServerPacketHandler {
	private static final List<AdditionalHandler> additionalHandlers = new CopyOnWriteArrayList<>();

	private ServerPacketHandler() {
	}

	public static void registerAdditional(AdditionalHandler serverHandler) {
		additionalHandlers.add(Objects.requireNonNull(serverHandler, "serverHandler"));
	}

	public static void clear() {
		additionalHandlers.clear();
	}

	public static boolean handle(IPacket packet, ServerPlayer player, Object context) {
		if (packet == null) {
			return true;
		}
		for (AdditionalHandler additionalHandler : additionalHandlers) {
			if (additionalHandler.handle(packet, player, context)) {
				return true;
			}
		}
		return false;
	}

	@FunctionalInterface
	public interface AdditionalHandler {
		boolean handle(IPacket packet, ServerPlayer player, Object context);
	}
}
