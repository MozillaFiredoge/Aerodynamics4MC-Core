package com.aerodynamics4mc.officialcontent.client;
import net.minecraft.client.Minecraft;

public final class AeroContentClient {
	private static final AeroContentClient INSTANCE = new AeroContentClient();

	private final ClientWindPresenceManager windPresenceManager = new ClientWindPresenceManager();
	private final GroundDustWindController groundDustWindController = new GroundDustWindController();

	private AeroContentClient() {
	}

	public static AeroContentClient getInstance() {
		return INSTANCE;
	}

	public void onClientTick(Minecraft minecraft) {
		windPresenceManager.onClientTick(minecraft);
		groundDustWindController.onClientTick(minecraft);
	}

	public void clear() {
		windPresenceManager.clear();
		groundDustWindController.clear();
	}
}
