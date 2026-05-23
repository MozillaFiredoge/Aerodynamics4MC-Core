package com.aerodynamics4mc.platform.fabric;

//? fabric {

/*import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.block.ModBlocks;
import com.aerodynamics4mc.network.FabricCustomPayload;
import com.aerodynamics4mc.network.packet.AeroClientL2PreferencePacket;
import com.aerodynamics4mc.runtime.AeroCommands;
import com.aerodynamics4mc.runtime.AeroServerRuntime;
import com.github.razorplay.packet_handler.network.IPacket;
import dev.kikugie.fletching_table.annotation.fabric.Entrypoint;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

@Entrypoint("main")
public class FabricEntrypoint implements ModInitializer {
	private final AeroServerRuntime runtime = AeroServerRuntime.getInstance();

	@Override
	public void onInitialize() {
		ModTemplate.onInitialize();
		ModBlocks.register();
		FabricCustomPayload.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> AeroCommands.register(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(runtime::onServerTick);
		ServerChunkEvents.CHUNK_LOAD.register(runtime::onChunkLoad);
		ServerChunkEvents.CHUNK_UNLOAD.register(runtime::onChunkUnload);
		ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(runtime::onBlockEntityLoad);
		ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(runtime::onBlockEntityUnload);
		ServerWorldEvents.UNLOAD.register((server, world) -> runtime.onWorldUnload(world));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			runtime.sendStateToPlayer(handler.player, server);
			runtime.broadcastState(server);
			runtime.sendFlowSnapshotToPlayer(handler.player, server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			runtime.onPlayerDisconnected(handler.player);
			runtime.broadcastState(server);
		});
		ServerPlayNetworking.registerGlobalReceiver(FabricCustomPayload.CUSTOM_PAYLOAD_ID, (payload, context) ->
				context.server().execute(() -> {
					IPacket packet = payload.packet();
					switch (packet) {
						case AeroClientL2PreferencePacket pkt ->
								runtime.setClientLocalL2Preference(context.player(), pkt.isLocalL2Enabled());
						default -> ModTemplate.LOGGER.info("Unknown client packet: {}", packet.getPacketId());
					}
				}));
		ServerLifecycleEvents.SERVER_STARTED.register(runtime::enableStreamingOnServerStart);
		ServerLifecycleEvents.SERVER_STOPPED.register(runtime::shutdownAll);
	}
}
*///?}
