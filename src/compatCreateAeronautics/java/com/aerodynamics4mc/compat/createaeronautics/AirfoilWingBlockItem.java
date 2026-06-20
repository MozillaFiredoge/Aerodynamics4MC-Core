package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
//? <1.21.11 {
/*import net.minecraft.world.InteractionResultHolder;
*///?}
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
//? >=1.21.11 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class AirfoilWingBlockItem extends BlockItem {
	private static final String AIRFOIL_ID_KEY = "airfoil_id";

	public AirfoilWingBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	public static Optional<A4mcId> airfoilId(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return Optional.empty();
		}
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		CompoundTag tag = customData.copyTag();
		String value = getString(tag, AIRFOIL_ID_KEY);
		if (value.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(A4mcId.parse(value));
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}

	public static A4mcId airfoilIdOrSelected(ItemStack stack) {
		return airfoilId(stack).orElseGet(CreateAeronauticsAirfoilLibrary::selectedId);
	}

	public static void setAirfoilId(ItemStack stack, A4mcId id) {
		if (stack == null || stack.isEmpty() || id == null) {
			return;
		}
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		CompoundTag tag = customData.copyTag();
		tag.putString(AIRFOIL_ID_KEY, id.toString());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	@Override
	//? >=1.21.11 {
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		return useAirfoilWing(world, user, hand);
	}
	//?} <1.21.11 {
	/*public InteractionResultHolder<ItemStack> use(Level world, Player user, InteractionHand hand) {
		return new InteractionResultHolder<>(useAirfoilWing(world, user, hand), user.getItemInHand(hand));
	}
	*///?}

	private InteractionResult useAirfoilWing(Level world, Player user, InteractionHand hand) {
		if (world.isClientSide()) {
			return AirfoilWingItemClientBridge.open(user, hand, user.getItemInHand(hand));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	//? >=1.21.11 {
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
		appendAirfoilTooltip(stack, tooltip);
	}
	//?} <1.21.11 {
	/*public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		appendAirfoilTooltip(stack, tooltip::add);
	}
	*///?}

	private static void appendAirfoilTooltip(ItemStack stack, Consumer<Component> tooltip) {
		A4mcId id = airfoilIdOrSelected(stack);
		AeroAirfoilDefinition definition = CreateAeronauticsAirfoilLibrary.definitionOrDefault(id);
		tooltip.accept(Component.translatable(
				"item." + CreateAeronauticsCompat.MOD_ID + ".airfoil_wing.tooltip.airfoil",
				Component.literal(definition.displayName()),
				Component.literal(definition.id().toString())
		).withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable(
				"item." + CreateAeronauticsCompat.MOD_ID + ".airfoil_wing.tooltip.open"
		).withStyle(ChatFormatting.DARK_GRAY));
	}

	private static String getString(CompoundTag tag, String key) {
		if (tag == null || !tag.contains(key)) {
			return "";
		}
		//? >=1.21.11 {
		return tag.getString(key).orElse("");
		//?} <1.21.11 {
		/*return tag.getString(key);
		*///?}
	}
}
