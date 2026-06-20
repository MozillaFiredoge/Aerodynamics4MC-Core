package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import com.aerodynamics4mc.network.ServerPacketHandler;
import com.github.razorplay.packet_handler.network.IPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;

public final class CreateAeronauticsServerNetworking {
	private static boolean registered;

	private CreateAeronauticsServerNetworking() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		registered = true;
		ServerPacketHandler.registerAdditional(CreateAeronauticsServerNetworking::handle);
	}

	private static boolean handle(IPacket packet, ServerPlayer player, Object context) {
		if (!(packet instanceof CreateAeronauticsAirfoilItemActionPacket actionPacket)) {
			return false;
		}
		apply(player, actionPacket);
		return true;
	}

	private static void apply(ServerPlayer player, CreateAeronauticsAirfoilItemActionPacket packet) {
		switch (packet.action()) {
			case CreateAeronauticsAirfoilItemActionPacket.ACTION_USE -> applyUse(player, packet);
			case CreateAeronauticsAirfoilItemActionPacket.ACTION_EXPORT -> applyExport(player, packet);
			default -> message(player, "Unknown A4MC airfoil item action: " + packet.action(), ChatFormatting.RED);
		}
	}

	private static void applyUse(ServerPlayer player, CreateAeronauticsAirfoilItemActionPacket packet) {
		AeroAirfoilDefinition definition = findDefinition(player, packet.airfoilId());
		if (definition == null) {
			return;
		}
		InteractionHand hand = packet.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		ItemStack stack = player.getItemInHand(hand);
		if (stack.getItem() != CreateAeronauticsCompatBlocks.AIRFOIL_WING.get().asItem()) {
			message(player, "Hold an A4MC Airfoil Wing item before selecting an airfoil.", ChatFormatting.RED);
			return;
		}
		AirfoilWingBlockItem.setAirfoilId(stack, definition.id());
		player.containerMenu.broadcastChanges();
		message(player, "Airfoil wing item set to " + definition.displayName() + " (" + definition.id() + ")", ChatFormatting.AQUA);
	}

	private static void applyExport(ServerPlayer player, CreateAeronauticsAirfoilItemActionPacket packet) {
		AeroAirfoilDefinition definition = findDefinition(player, packet.airfoilId());
		if (definition == null) {
			return;
		}
		MinecraftServer server = server(player);
		if (server == null) {
			return;
		}
		try {
			CreateAeronauticsAirfoilDiskStore.ExportResult result =
					CreateAeronauticsAirfoilDiskStore.exportDefinition(server, definition);
			message(player, "Exported A4MC airfoil " + definition.id() + " to " + result.outputPath(), ChatFormatting.AQUA);
		} catch (IOException | IllegalArgumentException e) {
			message(player, "Failed to export A4MC airfoil " + definition.id() + ": " + e.getMessage(), ChatFormatting.RED);
		}
	}

	private static AeroAirfoilDefinition findDefinition(ServerPlayer player, String idText) {
		try {
			A4mcId id = A4mcId.parse(idText);
			AeroAirfoilDefinition definition = CreateAeronauticsAirfoilLibrary.find(id).orElse(null);
			if (definition == null) {
				message(player, "Unknown A4MC airfoil " + id + "; import it first.", ChatFormatting.RED);
			}
			return definition;
		} catch (IllegalArgumentException e) {
			message(player, "Invalid A4MC airfoil id \"" + idText + "\": " + e.getMessage(), ChatFormatting.RED);
			return null;
		}
	}

	private static void message(ServerPlayer player, String text, ChatFormatting style) {
		player.displayClientMessage(Component.literal(text).withStyle(style), false);
	}

	private static MinecraftServer server(ServerPlayer player) {
		//? >=1.21.11 {
		return player.server;
		//?} <1.21.11 {
		/*return player.getServer();
		*///?}
	}
}
