package com.aerodynamics4mc.platform.fabric;

//? fabric {

/*import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.client.AeroClientCommands;
import com.aerodynamics4mc.client.AeroClientMod;
import com.aerodynamics4mc.network.FabricCustomPayload;
import com.aerodynamics4mc.network.packet.AeroCoarseWindPacket;
import com.aerodynamics4mc.network.packet.AeroFlowAnalysisPacket;
import com.aerodynamics4mc.network.packet.AeroFlowPacket;
import com.aerodynamics4mc.network.packet.AeroRuntimeStatePacket;
import com.github.razorplay.packet_handler.network.IPacket;
import dev.kikugie.fletching_table.annotation.fabric.Entrypoint;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
						default -> ModTemplate.LOGGER.info("Unknown server packet: {}", packet.getPacketId());
					}
				}));
		ModTemplate.onInitializeClient();

		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			AeroClientMod.getInstance().getClientL2Solver().onClientTick(minecraft);
			AeroClientMod.getInstance().getVisualizer().onClientTick();
			AeroClientMod.getInstance().getIrisWindBridge().onClientTick(minecraft);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			AeroClientMod.getInstance().getClientL2Solver().close();
			AeroClientMod.getInstance().getVisualizer().clearState();
			AeroClientMod.getInstance().getIrisWindBridge().clear();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			AeroClientMod.getInstance().getClientL2Solver().close();
			AeroClientMod.getInstance().getIrisWindBridge().close();
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
