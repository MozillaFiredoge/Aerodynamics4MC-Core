package com.aerodynamics4mc.platform.fabric;

//? fabric {

/*import com.aerodynamics4mc.network.FabricCustomPayload;
import com.aerodynamics4mc.platform.Platform;
import com.github.razorplay.packet_handler.network.IPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

public class FabricPlatform implements Platform {

	@Override
	public boolean isModLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	@Override
	public ModLoader loader() {
		return ModLoader.FABRIC;
	}

	@Override
	public String mcVersion() {
		return FabricLoader.getInstance().getRawGameVersion();
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	@Override
	public void sendPacketToServer(IPacket packet) {
		ClientPlayNetworking.send(new FabricCustomPayload(packet));
	}

	@Override
	public void sendPacketToClient(IPacket packet, ServerPlayer player) {
		ServerPlayNetworking.send(player, new FabricCustomPayload(packet));
	}
}
*///?}
