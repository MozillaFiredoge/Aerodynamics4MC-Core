package com.aerodynamics4mc.platform.fabric;

//? fabric {

/*import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.client.AeroClientCommands;
import com.aerodynamics4mc.client.AeroClientMod;
import com.aerodynamics4mc.network.ClientPacketHandler;
import com.aerodynamics4mc.network.ClientServerboundPacketSender;
import com.aerodynamics4mc.network.FabricCustomPayload;
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
		ClientServerboundPacketSender.register(packet -> ClientPlayNetworking.send(new FabricCustomPayload(packet)));
		ClientPlayNetworking.registerGlobalReceiver(FabricCustomPayload.CUSTOM_PAYLOAD_ID,
				(payload, context) -> ClientPacketHandler.handle(payload.packet(), context));
		ModTemplate.onInitializeClient();
		AeroClientMod.getInstance().onInitializeClient();

		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			AeroClientMod.getInstance().getLocalAirflowService().onClientTick(minecraft);
			AeroClientMod.getInstance().getVisualizer().onClientTick();
			AeroClientMod.getInstance().getIrisWindBridge().onClientTick(minecraft);
			AeroClientMod.getInstance().getWindAmbienceManager().onClientTick(minecraft);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			AeroClientMod.getInstance().getLocalAirflowService().close();
			AeroClientMod.getInstance().getVisualizer().clearState();
			AeroClientMod.getInstance().getIrisWindBridge().clear();
			AeroClientMod.getInstance().getWindAmbienceManager().clear();
			AeroClientMod.getInstance().getMeteorologicalMapData().clear();
			AeroClientMod.getInstance().getLocalWeatherData().clear();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			AeroClientMod.getInstance().getLocalAirflowService().close();
			AeroClientMod.getInstance().getIrisWindBridge().close();
			AeroClientMod.getInstance().getWindAmbienceManager().close();
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
