package com.aerodynamics4mc.platform.neoforge;

//? neoforge {

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.client.AeroClientCommands;
import com.aerodynamics4mc.client.AeroClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = ModTemplate.MOD_ID, value = Dist.CLIENT)
public class NeoforgeClientEventSubscriber {

	@SubscribeEvent
	public static void onClientSetup(final FMLClientSetupEvent event) {
		ModTemplate.onInitializeClient();
		registerClientEvents();
	}

	private static void registerClientEvents() {
		// Client Tick
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
			Minecraft minecraft = Minecraft.getInstance();
			AeroClientMod.getInstance().getClientL2Solver().onClientTick(minecraft);
			AeroClientMod.getInstance().getVisualizer().onClientTick();
			AeroClientMod.getInstance().getIrisWindBridge().onClientTick(minecraft);
		});

		// Client Disconnect
		NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
			if (event.getLevel().isClientSide()) {
				AeroClientMod.getInstance().getClientL2Solver().close();
				AeroClientMod.getInstance().getVisualizer().clearState();
				AeroClientMod.getInstance().getIrisWindBridge().clear();
			}
		});

		// Render events
		NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> AeroClientMod.getInstance().getVisualizer().renderAtlasOverlay(event));
		NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterLevel event) -> AeroClientMod.getInstance().getIrisWindBridge().onRenderFrame());

		// Commands
		NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
			if (event.getCommandSelection() != Commands.CommandSelection.DEDICATED) {
				AeroClientCommands.register(event.getDispatcher(), event.getBuildContext());
			}
		});
	}
}
//?}
