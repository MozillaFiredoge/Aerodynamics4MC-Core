package com.aerodynamics4mc.platform.neoforge;

//? neoforge {

import com.aerodynamics4mc.network.ForgeCustomPayload;
import com.aerodynamics4mc.platform.Platform;
import com.github.razorplay.packet_handler.network.IPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.VersionInfo;
import net.neoforged.neoforge.network.PacketDistributor;

public class NeoforgePlatform implements Platform {

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}

	@Override
	public ModLoader loader() {
		return ModLoader.NEOFORGE;
	}

	@Override
	public String mcVersion() {
		return "";
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLLoader/*? if > 1.21.7 {*/.getCurrent()/*?}*/.isProduction();
	}

	@Override
	public void sendPacketToServer(IPacket packet) {
		if (Minecraft.getInstance().getConnection() != null) {
			Minecraft.getInstance().getConnection().send(new ForgeCustomPayload(packet));
		}
	}

	@Override
	public void sendPacketToClient(IPacket packet, ServerPlayer player) {
		PacketDistributor.sendToPlayer(player, new ForgeCustomPayload(packet));
	}
}
//?}
