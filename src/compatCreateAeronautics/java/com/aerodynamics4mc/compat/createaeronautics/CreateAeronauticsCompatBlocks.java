package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.ModTemplate;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;
import java.util.function.Function;

public final class CreateAeronauticsCompatBlocks {
	public static final String AIRFOIL_WING_PATH = "airfoil_wing";
	public static final String AIRFOIL_WING_ID = CreateAeronauticsCompat.MOD_ID + ":" + AIRFOIL_WING_PATH;
	public static final String FUTURE_CONTENT_AIRFOIL_WING_ID = ModTemplate.MOD_ID + ":" + AIRFOIL_WING_PATH;
	private static final Set<String> A4MC_WING_BLOCK_IDS = Set.of(
			AIRFOIL_WING_ID,
			FUTURE_CONTENT_AIRFOIL_WING_ID
	);

	public static final DeferredRegister.Blocks BLOCKS =
			DeferredRegister.createBlocks(CreateAeronauticsCompat.MOD_ID);
	public static final DeferredRegister.Items ITEMS =
			DeferredRegister.createItems(CreateAeronauticsCompat.MOD_ID);
	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
			DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateAeronauticsCompat.MOD_ID);
	public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateAeronauticsCompat.MOD_ID);

	public static final DeferredBlock<AirfoilWingBlock> AIRFOIL_WING =
			registerBlock(AIRFOIL_WING_PATH, properties -> new AirfoilWingBlock(properties.strength(0.8f).noOcclusion()));
	public static final java.util.function.Supplier<BlockEntityType<AirfoilWingBlockEntity>> AIRFOIL_WING_BLOCK_ENTITY =
			BLOCK_ENTITIES.register(AIRFOIL_WING_PATH, () ->
					//? >=1.21.11 {
					new BlockEntityType<>(AirfoilWingBlockEntity::new, AIRFOIL_WING.get())
					//?} <1.21.11 {
					/*new BlockEntityType<>(AirfoilWingBlockEntity::new, java.util.Set.of(AIRFOIL_WING.get()), null)
					*///?}
			);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
			CREATIVE_MODE_TABS.register("creative_tab", () -> CreativeModeTab.builder()
					.icon(() -> new ItemStack(AIRFOIL_WING.get()))
					.title(Component.translatable("itemGroup." + CreateAeronauticsCompat.MOD_ID))
					.displayItems((params, output) -> output.accept(AIRFOIL_WING.get()))
					.build()
			);

	private CreateAeronauticsCompatBlocks() {
	}

	public static void register(IEventBus modEventBus) {
		BLOCKS.register(modEventBus);
		ITEMS.register(modEventBus);
		BLOCK_ENTITIES.register(modEventBus);
		CREATIVE_MODE_TABS.register(modEventBus);
	}

	public static boolean isA4mcWingBlockId(String blockId) {
		return A4MC_WING_BLOCK_IDS.contains(blockId);
	}

	public static String supportedWingBlockSummary() {
		return String.join(", ", A4MC_WING_BLOCK_IDS);
	}

	private static <T extends Block> DeferredBlock<T> registerBlock(
			String name,
			Function<BlockBehaviour.Properties, T> function
	) {
		DeferredBlock<T> block = BLOCKS.registerBlock(name, function);
		registerBlockItem(name, block);
		return block;
	}

	private static <T extends Block> void registerBlockItem(String name, DeferredBlock<T> block) {
		ITEMS.registerItem(name, properties -> {
			Item.Properties itemProperties = properties/*? if >=1.21.11 {*/.useBlockDescriptionPrefix()/*?}*/;
			if (AIRFOIL_WING_PATH.equals(name)) {
				return new AirfoilWingBlockItem(block.get(), itemProperties);
			}
			return new BlockItem(block.get(), itemProperties);
		});
	}
}
