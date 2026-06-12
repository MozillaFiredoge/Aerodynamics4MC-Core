package com.aerodynamics4mc.platform.neoforge;

//? neoforge {

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.client.AeroClientCommands;
import com.aerodynamics4mc.client.AeroClientMod;
import com.aerodynamics4mc.client.WindDriftParticle;
import com.aerodynamics4mc.particle.ModParticles;
import com.aerodynamics4mc.vehicle.ModEntities;
import net.minecraft.client.Minecraft;
//? >=1.21.11 {
import net.minecraft.client.model.geom.ModelLayers;
//?}
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
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

	@SubscribeEvent
	public static void registerParticleProviders(final RegisterParticleProvidersEvent event) {
		event.registerSpriteSet(ModParticles.SAND_DUST.get(), WindDriftParticle.Provider::new);
		event.registerSpriteSet(ModParticles.RED_SAND_DUST.get(), WindDriftParticle.Provider::new);
		event.registerSpriteSet(ModParticles.DIRT_DUST.get(), WindDriftParticle.Provider::new);
		event.registerSpriteSet(ModParticles.SNOW_DRIFT.get(), WindDriftParticle.Provider::new);
		event.registerSpriteSet(ModParticles.LEAF_MOTE.get(), WindDriftParticle.Provider::new);
		event.registerSpriteSet(ModParticles.GRASS_MOTE.get(), WindDriftParticle.Provider::new);
	}

	@SubscribeEvent
	public static void registerEntityRenderers(final EntityRenderersEvent.RegisterRenderers event) {
		event.registerEntityRenderer(ModEntities.sailboat(), context ->
				//? >=1.21.11 {
				new BoatRenderer(context, ModelLayers.OAK_BOAT)
				//?} <1.21.11 {
				/*new BoatRenderer(context, false)
				*///?}
		);
	}

	private static void registerClientEvents() {
		// Client Tick
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
			Minecraft minecraft = Minecraft.getInstance();
			AeroClientMod.getInstance().getClientL2Solver().onClientTick(minecraft);
			AeroClientMod.getInstance().getVisualizer().onClientTick();
			AeroClientMod.getInstance().getIrisWindBridge().onClientTick(minecraft);
			AeroClientMod.getInstance().getWindAmbienceManager().onClientTick(minecraft);
			AeroClientMod.getInstance().getWindPresenceManager().onClientTick(minecraft);
			AeroClientMod.getInstance().getGroundDustWindController().onClientTick(minecraft);
		});

		// Client Disconnect
		NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
			if (event.getLevel().isClientSide()) {
				AeroClientMod.getInstance().getClientL2Solver().close();
				AeroClientMod.getInstance().getVisualizer().clearState();
				AeroClientMod.getInstance().getIrisWindBridge().clear();
				AeroClientMod.getInstance().getWindAmbienceManager().clear();
				AeroClientMod.getInstance().getWindPresenceManager().clear();
				AeroClientMod.getInstance().getGroundDustWindController().clear();
				AeroClientMod.getInstance().getMeteorologicalMapData().clear();
				AeroClientMod.getInstance().getLocalWeatherData().clear();
			}
		});

		// Render events
		//? >=1.21.11 {
		NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> AeroClientMod.getInstance().getVisualizer().renderAtlasOverlay(event));
		NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterLevel event) -> AeroClientMod.getInstance().getIrisWindBridge().onRenderFrame());
		//?} <1.21.11 {
		/*NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
			if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
				AeroClientMod.getInstance().getVisualizer().renderAtlasOverlay(event);
			} else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
				AeroClientMod.getInstance().getIrisWindBridge().onRenderFrame();
			}
		});
		*///?}

		// Commands
		NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
			if (event.getCommandSelection() != Commands.CommandSelection.DEDICATED) {
				AeroClientCommands.register(event.getDispatcher(), event.getBuildContext());
			}
		});
	}
}
//?}
