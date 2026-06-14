package com.aerodynamics4mc.officialcontent.platform.neoforge;

//? neoforge {

import com.aerodynamics4mc.officialcontent.AeroContentConstants;
import com.aerodynamics4mc.officialcontent.client.AeroContentClient;
import com.aerodynamics4mc.officialcontent.client.WindDriftParticle;
import com.aerodynamics4mc.particle.ModParticles;
import com.aerodynamics4mc.vehicle.ModEntities;
//? >=1.21.11 {
import net.minecraft.client.model.geom.ModelLayers;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = AeroContentConstants.CONTENT_MOD_ID, value = Dist.CLIENT)
public final class NeoforgeContentClientEventSubscriber {
	private static boolean clientEventsRegistered;

	private NeoforgeContentClientEventSubscriber() {
	}

	@SubscribeEvent
	public static void onClientSetup(final FMLClientSetupEvent event) {
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
		if (clientEventsRegistered) {
			return;
		}
		clientEventsRegistered = true;
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) ->
				AeroContentClient.getInstance().onClientTick(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
			if (event.getLevel().isClientSide()) {
				AeroContentClient.getInstance().clear();
			}
		});
	}
}
//?}
