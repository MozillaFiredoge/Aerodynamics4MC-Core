package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.ModTemplate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class CreateAeronauticsAirfoilSync {
	private CreateAeronauticsAirfoilSync() {
	}

	public static void sendToPlayer(ServerPlayer player) {
		if (player == null) {
			return;
		}
		ModTemplate.xplat().sendPacketToClient(CreateAeronauticsAirfoilSyncPacket.fromLibrary(), player);
	}

	public static void broadcast(MinecraftServer server) {
		if (server == null) {
			return;
		}
		CreateAeronauticsAirfoilSyncPacket packet = CreateAeronauticsAirfoilSyncPacket.fromLibrary();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ModTemplate.xplat().sendPacketToClient(packet, player);
		}
	}
}
