package com.aerodynamics4mc.officialcontent.platform.fabric;

//? fabric {

/*import com.aerodynamics4mc.officialcontent.client.AeroContentClient;
import com.aerodynamics4mc.officialcontent.client.WindDriftParticle;
import com.aerodynamics4mc.particle.ModParticles;
import com.aerodynamics4mc.vehicle.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.BoatRenderer;

public final class FabricContentClientEntrypoint implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ParticleFactoryRegistry.getInstance().register(ModParticles.SAND_DUST, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.RED_SAND_DUST, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.DIRT_DUST, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.SNOW_DRIFT, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.LEAF_MOTE, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.GRASS_MOTE, WindDriftParticle.Provider::new);
		EntityRendererRegistry.register(ModEntities.sailboat(), context -> new BoatRenderer(context, ModelLayers.OAK_BOAT));
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> AeroContentClient.getInstance().onClientTick(minecraft));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AeroContentClient.getInstance().clear());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AeroContentClient.getInstance().clear());
	}
}
*///?}
