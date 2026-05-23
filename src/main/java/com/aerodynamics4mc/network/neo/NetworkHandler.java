package com.aerodynamics4mc.network.neo;
//? neoforge {

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.client.AeroClientMod;
import com.aerodynamics4mc.network.ForgeCustomPayload;
import com.aerodynamics4mc.network.packet.AeroClientL2PreferencePacket;
import com.aerodynamics4mc.network.packet.AeroCoarseWindPacket;
import com.aerodynamics4mc.network.packet.AeroFlowAnalysisPacket;
import com.aerodynamics4mc.network.packet.AeroFlowPacket;
import com.aerodynamics4mc.network.packet.AeroRuntimeStatePacket;
import com.aerodynamics4mc.runtime.AeroServerRuntime;
import com.github.razorplay.packet_handler.network.IPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = ModTemplate.MOD_ID)
public class NetworkHandler {

	private NetworkHandler() {
		// []
	}

	@SubscribeEvent
	public static void register(RegisterPayloadHandlersEvent event) {
		ModTemplate.LOGGER.info("Registering Network Handlers for " + ModTemplate.MOD_ID);

		PayloadRegistrar registrar = event.registrar("1.0");

		registrar.playBidirectional(
				ForgeCustomPayload.TYPE,
				ForgeCustomPayload.STREAM_CODEC,
				NetworkHandler::handleServer
		);
	}

	@SubscribeEvent
	public static void register(RegisterClientPayloadHandlersEvent event) {
		event.register(
				ForgeCustomPayload.TYPE,
				NetworkHandler::handleClient
		);
	}

	private static void handleClient(ForgeCustomPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			IPacket packet = payload.packet();
			switch (packet) {
				case AeroRuntimeStatePacket pkt -> AeroClientMod.onRuntimeState(pkt, context);
				case AeroFlowAnalysisPacket pkt -> AeroClientMod.onFlowAnalysis(pkt, context);
				case AeroCoarseWindPacket pkt -> AeroClientMod.onCoarseWindField(pkt, context);
				case AeroFlowPacket pkt -> AeroClientMod.onFlowField(pkt, context);
				default -> ModTemplate.LOGGER.warn("Unknown server packet: {}", packet.getPacketId());
			}
		});
	}

	private static void handleServer(ForgeCustomPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			IPacket packet = payload.packet();
			ServerPlayer player = (ServerPlayer) context.player();

			switch (packet) {
				case AeroClientL2PreferencePacket pkt ->
						AeroServerRuntime.getInstance().setClientLocalL2Preference(player, pkt.isLocalL2Enabled());
				default -> ModTemplate.LOGGER.warn("Unknown client packet: {}", packet.getPacketId());
			}
		});
	}
}
//?}
