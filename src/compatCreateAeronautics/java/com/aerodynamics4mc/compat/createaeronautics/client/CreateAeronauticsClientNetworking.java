package com.aerodynamics4mc.compat.createaeronautics.client;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import com.aerodynamics4mc.api.AeroAirfoilJson;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsAirfoilLibrary;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsAirfoilSyncPacket;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsPolarCache;
import com.aerodynamics4mc.network.ClientPacketHandler;
import com.github.razorplay.packet_handler.network.IPacket;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public final class CreateAeronauticsClientNetworking {
	private static boolean registered;

	private CreateAeronauticsClientNetworking() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		registered = true;
		ClientPacketHandler.registerAdditional(CreateAeronauticsClientNetworking::handle);
	}

	private static boolean handle(IPacket packet, Object context) {
		if (!(packet instanceof CreateAeronauticsAirfoilSyncPacket syncPacket)) {
			return false;
		}
		((IPayloadContext) context).enqueueWork(() -> apply(syncPacket));
		return true;
	}

	private static void apply(CreateAeronauticsAirfoilSyncPacket packet) {
		try {
			List<AeroAirfoilDefinition> definitions = new ArrayList<>();
			for (String encoded : packet.definitionJson()) {
				definitions.add(AeroAirfoilJson.read(encoded));
			}
			CreateAeronauticsAirfoilLibrary.applySynchronizedState(
					definitions,
					A4mcId.parse(packet.selectedId()),
					packet.revision()
			);
			CreateAeronauticsPolarCache.INSTANCE.clear();
		} catch (IllegalArgumentException e) {
			ModTemplate.LOGGER.warn("Failed to apply Create Aeronautics airfoil sync: {}", e.getMessage());
		}
	}
}
