package com.aerodynamics4mc.compat.createaeronautics.platform.neoforge.client;

//? neoforge {

import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsCompat;
import com.aerodynamics4mc.compat.createaeronautics.client.CreateAeronauticsClientDebugCommands;
import com.aerodynamics4mc.compat.createaeronautics.client.CreateAeronauticsClientNetworking;
import com.aerodynamics4mc.compat.createaeronautics.client.CreateAeronauticsPolarOverlay;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@EventBusSubscriber(modid = CreateAeronauticsCompat.MOD_ID, value = Dist.CLIENT)
public final class NeoforgeCreateAeronauticsCompatClientEvents {
	static {
		CreateAeronauticsClientNetworking.register();
	}

	private NeoforgeCreateAeronauticsCompatClientEvents() {
	}

	@SubscribeEvent
	public static void onRenderGui(RenderGuiEvent.Post event) {
		CreateAeronauticsPolarOverlay.render(event);
	}

	@SubscribeEvent
	public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
		CreateAeronauticsClientDebugCommands.register(event.getDispatcher());
	}
}
//?}
