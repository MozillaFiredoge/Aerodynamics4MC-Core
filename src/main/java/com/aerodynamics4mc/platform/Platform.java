package com.aerodynamics4mc.platform;

import com.github.razorplay.packet_handler.network.IPacket;
import net.minecraft.server.level.ServerPlayer;

public interface Platform {
	boolean isModLoaded(String modId);

	ModLoader loader();

	String mcVersion();

	boolean isDevelopmentEnvironment();

	default boolean isDebug() {
		return isDevelopmentEnvironment();
	}

	enum ModLoader {
		FABRIC, NEOFORGE, FORGE, QUILT
	}

	void sendPacketToClient(IPacket packet, ServerPlayer player);
}
