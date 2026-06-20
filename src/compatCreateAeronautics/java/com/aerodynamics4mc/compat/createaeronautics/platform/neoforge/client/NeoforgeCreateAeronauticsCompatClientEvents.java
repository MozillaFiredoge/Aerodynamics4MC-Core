package com.aerodynamics4mc.compat.createaeronautics.platform.neoforge.client;

//? neoforge {

import com.aerodynamics4mc.compat.createaeronautics.AirfoilWingItemClientBridge;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsCompat;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilWingScreen;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilWingVisualLibrary;
import com.aerodynamics4mc.compat.createaeronautics.client.CreateAeronauticsClientDebugCommands;
import com.aerodynamics4mc.compat.createaeronautics.client.CreateAeronauticsClientNetworking;
import com.aerodynamics4mc.compat.createaeronautics.client.CreateAeronauticsPolarOverlay;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
//? >=1.21.11 {
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
//?} <1.21.11 {
/*import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
*///?}
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@EventBusSubscriber(modid = CreateAeronauticsCompat.MOD_ID, value = Dist.CLIENT)
public final class NeoforgeCreateAeronauticsCompatClientEvents {
	static {
		CreateAeronauticsClientNetworking.register();
		AirfoilWingItemClientBridge.register(AirfoilWingScreen::open);
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

	//? >=1.21.11 {
	@SubscribeEvent
	public static void onAddClientReloadListeners(AddClientReloadListenersEvent event) {
		event.addListener(
				Identifier.fromNamespaceAndPath(CreateAeronauticsCompat.MOD_ID, "airfoil_visuals"),
				AirfoilWingVisualLibrary.INSTANCE
		);
	}
	//?} <1.21.11 {
	/*@SubscribeEvent
	public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
		event.registerReloadListener(AirfoilWingVisualLibrary.INSTANCE);
	}
	*///?}
}
//?}
