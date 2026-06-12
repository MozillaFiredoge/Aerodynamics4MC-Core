package com.aerodynamics4mc.platform.fabric;

//? fabric {

/*import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.client.AeroClientCommands;
import com.aerodynamics4mc.client.AeroClientMod;
import com.aerodynamics4mc.network.FabricCustomPayload;
import com.aerodynamics4mc.network.packet.AeroCoarseWindPacket;
import com.aerodynamics4mc.network.packet.AeroFlowAnalysisPacket;
import com.aerodynamics4mc.network.packet.AeroFlowPacket;
import com.aerodynamics4mc.network.packet.AeroLocalWeatherPacket;
import com.aerodynamics4mc.network.packet.AeroMesoscaleMapPacket;
import com.aerodynamics4mc.network.packet.AeroRuntimeStatePacket;
import com.aerodynamics4mc.particle.ModParticles;
import com.aerodynamics4mc.client.WindDriftParticle;
import com.aerodynamics4mc.vehicle.ModEntities;
import com.github.razorplay.packet_handler.network.IPacket;
import dev.kikugie.fletching_table.annotation.fabric.Entrypoint;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

@Entrypoint("client")
public class FabricClientEntrypoint implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(FabricCustomPayload.CUSTOM_PAYLOAD_ID, (payload, context) ->
				context.client().execute(() -> {
					IPacket packet = payload.packet();

					switch (packet) {
						case AeroRuntimeStatePacket pkt -> AeroClientMod.onRuntimeState(pkt, context);
						case AeroFlowAnalysisPacket pkt -> AeroClientMod.onFlowAnalysis(pkt, context);
						case AeroCoarseWindPacket pkt -> AeroClientMod.onCoarseWindField(pkt, context);
						case AeroFlowPacket pkt -> AeroClientMod.onFlowField(pkt, context);
						case AeroLocalWeatherPacket pkt -> AeroClientMod.onLocalWeather(pkt, context);
						case AeroMesoscaleMapPacket pkt -> AeroClientMod.onMesoscaleMap(pkt, context);
						default -> ModTemplate.LOGGER.info("Unknown server packet: {}", packet.getPacketId());
					}
				}));
		ParticleFactoryRegistry.getInstance().register(ModParticles.SAND_DUST, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.RED_SAND_DUST, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.DIRT_DUST, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.SNOW_DRIFT, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.LEAF_MOTE, WindDriftParticle.Provider::new);
		ParticleFactoryRegistry.getInstance().register(ModParticles.GRASS_MOTE, WindDriftParticle.Provider::new);
		EntityRendererRegistry.register(ModEntities.sailboat(), context -> new BoatRenderer(context, ModelLayers.OAK_BOAT));
		ModTemplate.onInitializeClient();

		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			AeroClientMod.getInstance().getClientL2Solver().onClientTick(minecraft);
			AeroClientMod.getInstance().getVisualizer().onClientTick();
			AeroClientMod.getInstance().getIrisWindBridge().onClientTick(minecraft);
			AeroClientMod.getInstance().getWindAmbienceManager().onClientTick(minecraft);
			AeroClientMod.getInstance().getWindPresenceManager().onClientTick(minecraft);
			AeroClientMod.getInstance().getGroundDustWindController().onClientTick(minecraft);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			AeroClientMod.getInstance().getClientL2Solver().close();
			AeroClientMod.getInstance().getVisualizer().clearState();
			AeroClientMod.getInstance().getIrisWindBridge().clear();
			AeroClientMod.getInstance().getWindAmbienceManager().clear();
			AeroClientMod.getInstance().getWindPresenceManager().clear();
			AeroClientMod.getInstance().getGroundDustWindController().clear();
			AeroClientMod.getInstance().getMeteorologicalMapData().clear();
			AeroClientMod.getInstance().getLocalWeatherData().clear();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			AeroClientMod.getInstance().getClientL2Solver().close();
			AeroClientMod.getInstance().getIrisWindBridge().close();
			AeroClientMod.getInstance().getWindAmbienceManager().close();
			AeroClientMod.getInstance().getWindPresenceManager().clear();
			AeroClientMod.getInstance().getGroundDustWindController().clear();
			AeroClientMod.getInstance().getMeteorologicalMapData().clear();
			AeroClientMod.getInstance().getLocalWeatherData().clear();
		});

		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> AeroClientMod.getInstance().getVisualizer().renderAtlasOverlay(context));
		WorldRenderEvents.START_MAIN.register(context -> AeroClientMod.getInstance().getIrisWindBridge().onRenderFrame());
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			if (!environment.includeDedicated) {
				AeroClientCommands.register(dispatcher, registryAccess);
			}
		});
	}
}
*///?}
