package com.aerodynamics4mc.network.neo;
//? neoforge {

//? >=1.21.11 {
import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.network.ClientPacketHandler;
import com.aerodynamics4mc.network.ForgeCustomPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@EventBusSubscriber(modid = ModTemplate.MOD_ID, value = Dist.CLIENT)
public final class ClientNetworkHandler {
	private ClientNetworkHandler() {
	}

	@SubscribeEvent
	public static void register(RegisterClientPayloadHandlersEvent event) {
		event.register(
				ForgeCustomPayload.TYPE,
				(payload, context) -> ClientPacketHandler.handle(payload.packet(), context)
		);
	}
}
//?}
//?}
