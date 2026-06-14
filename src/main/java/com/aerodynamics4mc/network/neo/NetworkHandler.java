package com.aerodynamics4mc.network.neo;
//? neoforge {

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.network.ClientPacketHandler;
import com.aerodynamics4mc.network.ForgeCustomPayload;
import com.aerodynamics4mc.network.packet.AeroClientL2PreferencePacket;
import com.aerodynamics4mc.network.packet.AeroMesoscaleMapRequestPacket;
import com.aerodynamics4mc.runtime.AeroServerRuntime;
import com.github.razorplay.packet_handler.network.IPacket;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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

		//? >=1.21.11 {
		registrar.playBidirectional(
				ForgeCustomPayload.TYPE,
				ForgeCustomPayload.STREAM_CODEC,
				NetworkHandler::handleServer
		);
		//?} <1.21.11 {
		/*registrar.playBidirectional(
				ForgeCustomPayload.TYPE,
				ForgeCustomPayload.STREAM_CODEC,
				NetworkHandler::handleByFlow
		);
		*///?}
	}

	private static void handleByFlow(ForgeCustomPayload payload, IPayloadContext context) {
		if (context.flow() == PacketFlow.CLIENTBOUND) {
			ClientPacketHandler.handle(payload.packet(), context);
			return;
		}
		handleServer(payload, context);
	}

	private static void handleServer(ForgeCustomPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			IPacket packet = payload.packet();
			ServerPlayer player = (ServerPlayer) context.player();

			switch (packet) {
				case AeroClientL2PreferencePacket pkt ->
						AeroServerRuntime.getInstance().setClientLocalL2Preference(player, pkt.isLocalL2Enabled());
				case AeroMesoscaleMapRequestPacket pkt ->
						AeroServerRuntime.getInstance().sendMeteorologicalMapToPlayer(player, pkt.getLayer(), pkt.isOpenScreen());
				default -> ModTemplate.LOGGER.warn("Unknown client packet: {}", packet.getPacketId());
			}
		});
	}
}
//?}
