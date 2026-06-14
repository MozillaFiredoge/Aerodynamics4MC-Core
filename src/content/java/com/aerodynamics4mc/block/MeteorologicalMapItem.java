package com.aerodynamics4mc.block;

import com.aerodynamics4mc.runtime.AeroServerRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
//? <1.21.11 {
/*import net.minecraft.world.InteractionResultHolder;
*///?}
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
//? >=1.21.11 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

public final class MeteorologicalMapItem extends Item {
	public MeteorologicalMapItem(Properties properties) {
		super(properties);
	}

	@Override
	//? >=1.21.11 {
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		return useMeteorologicalMap(world, user, hand);
	}
	//?} <1.21.11 {
	/*public InteractionResultHolder<ItemStack> use(Level world, Player user, InteractionHand hand) {
		return new InteractionResultHolder<>(useMeteorologicalMap(world, user, hand), user.getItemInHand(hand));
	}
	*///?}

	private InteractionResult useMeteorologicalMap(Level world, Player user, InteractionHand hand) {
		if (world.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(user instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		user.getCooldowns().addCooldown(user.getItemInHand(hand), 10);
		AeroServerRuntime.getInstance().sendMeteorologicalMapToPlayer(serverPlayer, -1, true);
		return InteractionResult.SUCCESS;
	}

	@Override
	//? >=1.21.11 {
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
		tooltip.accept(Component.translatable("item.aerodynamics4mc.meteorological_map.tooltip").withStyle(ChatFormatting.GRAY));
	}
	//?} <1.21.11 {
	/*public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		tooltip.add(Component.translatable("item.aerodynamics4mc.meteorological_map.tooltip").withStyle(ChatFormatting.GRAY));
	}
	*///?}
}
