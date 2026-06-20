package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.ModTemplate;
import com.github.razorplay.packet_handler.network.IPacket;
import com.github.razorplay.packet_handler.network.PacketTCP;

public final class CreateAeronauticsCompat {
	public static final String MOD_ID = ModTemplate.MOD_ID + "_compat_create_aeronautics";
	public static final String AERONAUTICS_MOD_ID = "aeronautics";
	public static final String SIMULATED_MOD_ID = "simulated";
	public static final String SABLE_MOD_ID = "sable";

	private static boolean initialized;

	private CreateAeronauticsCompat() {
	}

	public static synchronized void initialize() {
		if (initialized) {
			return;
		}
		initialized = true;
		registerPackets();
		CreateAeronauticsServerNetworking.register();
		CreateAeronauticsEnvironment environment = CreateAeronauticsEnvironment.detect();
		if (!environment.available()) {
			ModTemplate.LOGGER.warn(
					"Create Aeronautics compat is present but target runtime classes are missing: {}",
					environment.missingClasses()
			);
			return;
		}
		CreateAeronauticsContraptionMovement.register();
		ModTemplate.LOGGER.info("Create Aeronautics compat initialized");
	}

	@SuppressWarnings("unchecked")
	private static void registerPackets() {
		Class<? extends IPacket>[] packetClasses = new Class[] {
				CreateAeronauticsAirfoilSyncPacket.class,
				CreateAeronauticsAirfoilItemActionPacket.class
		};
		PacketTCP.registerPackets(packetClasses);
	}
}
