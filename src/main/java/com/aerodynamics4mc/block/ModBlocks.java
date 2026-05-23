package com.aerodynamics4mc.block;

import com.aerodynamics4mc.ModTemplate;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
//? neoforge {
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
//?}

import java.util.function.Function;

public final class ModBlocks {
	//? neoforge {
	public static final DeferredRegister.Blocks BLOCKS =
			DeferredRegister.createBlocks(ModTemplate.MOD_ID);

	public static final DeferredRegister.Items ITEMS =
			DeferredRegister.createItems(ModTemplate.MOD_ID);

	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
			DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModTemplate.MOD_ID);

	public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModTemplate.MOD_ID);
	//?}

	public static final Identifier FAN_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "fan");
	public static final Identifier DUCT_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "duct");
	public static final Identifier WIND_METER_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "wind_meter");
	public static final Identifier WIND_TURBINE_PROBE_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "wind_turbine_probe");


	//? neoforge {
	public static final DeferredItem<Item> WIND_METER_ITEM = ITEMS.registerItem(WIND_METER_ID.getPath(),
			properties -> new WindMeterItem(properties.stacksTo(1)));

	public static final DeferredBlock<Block> FAN_BLOCK = registerBlock(FAN_ID.getPath(), properties -> new FanBlock(properties.strength(1.5f)));
	public static final DeferredBlock<Block> DUCT_BLOCK = registerBlock(DUCT_ID.getPath(), properties -> new DuctBlock(properties.strength(1.0f)));
	public static final DeferredBlock<Block> WIND_TURBINE_PROBE_BLOCK = registerBlock(WIND_TURBINE_PROBE_ID.getPath(), properties -> new WindTurbineProbeBlock(properties.strength(1.5f)));

	public static final java.util.function.Supplier<BlockEntityType<FanBlockEntity>> FAN_BLOCK_ENTITY =
			BLOCK_ENTITIES.register(FAN_ID.getPath(), () -> new BlockEntityType<>(
					FanBlockEntity::new, FAN_BLOCK.get()));
	public static final java.util.function.Supplier<BlockEntityType<WindTurbineProbeBlockEntity>> WIND_TURBINE_PROBE_BLOCK_ENTITY =
			BLOCK_ENTITIES.register(WIND_TURBINE_PROBE_ID.getPath(), () -> new BlockEntityType<>(
					WindTurbineProbeBlockEntity::new, WIND_TURBINE_PROBE_BLOCK.get()));
	//?}

	//? fabric {
	/*public static Item WIND_METER_ITEM = register(WIND_METER_ID.getPath(), WindMeterItem::new, new Item.Properties().stacksTo(1));

	public static Block FAN_BLOCK = register(FAN_ID.getPath(), FanBlock::new, Block.Properties.of().strength(1.5f), true);
	public static Block DUCT_BLOCK = register(DUCT_ID.getPath(), DuctBlock::new, Block.Properties.of().strength(1.0f), true);
	public static Block WIND_TURBINE_PROBE_BLOCK = register(WIND_TURBINE_PROBE_ID.getPath(), WindTurbineProbeBlock::new, Block.Properties.of().strength(1.5f), true);

	public static BlockEntityType<FanBlockEntity> FAN_BLOCK_ENTITY = register(FAN_ID.getPath(), FanBlockEntity::new, FAN_BLOCK);
	public static BlockEntityType<WindTurbineProbeBlockEntity> WIND_TURBINE_PROBE_BLOCK_ENTITY = register(WIND_TURBINE_PROBE_ID.getPath(), WindTurbineProbeBlockEntity::new, WIND_TURBINE_PROBE_BLOCK);
	*///?}

	//? fabric {
	/*public static final ResourceKey<CreativeModeTab> CUSTOM_CREATIVE_TAB_KEY = ResourceKey.create(
			BuiltInRegistries.CREATIVE_MODE_TAB.key(), Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "creative_tab")
	);

	public static final CreativeModeTab CUSTOM_CREATIVE_TAB = net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
			.icon(() -> new ItemStack(FAN_BLOCK))
			.title(Component.translatable("itemGroup." + ModTemplate.MOD_ID))
			.displayItems((params, output) -> {
				output.accept(FAN_BLOCK);
				output.accept(DUCT_BLOCK);
				output.accept(WIND_METER_ITEM);
				output.accept(WIND_TURBINE_PROBE_BLOCK);
			})
			.build();
	*///?}

	//? neoforge {
	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CUSTOM_CREATIVE_TAB =
			CREATIVE_MODE_TABS.register("creative_tab", () -> CreativeModeTab.builder()
					.icon(() -> new ItemStack(FAN_BLOCK.get()))
					.title(Component.translatable("itemGroup." + ModTemplate.MOD_ID))
					.displayItems((params, output) -> {
						output.accept(FAN_BLOCK.get());
						output.accept(DUCT_BLOCK.get());
						output.accept(WIND_METER_ITEM.get());
						output.accept(WIND_TURBINE_PROBE_BLOCK.get());
					})
					.build()
			);
	//?}

	private ModBlocks() {
		// []
	}

	public static void register(/*? neoforge { */net.neoforged.bus.api.IEventBus modEventBus/*?} */) {
		//? fabric {
		/*Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, CUSTOM_CREATIVE_TAB_KEY, CUSTOM_CREATIVE_TAB);
		 *///?}
		//? neoforge {
		BLOCKS.register(modEventBus);
		ITEMS.register(modEventBus);
		BLOCK_ENTITIES.register(modEventBus);
		CREATIVE_MODE_TABS.register(modEventBus);
		//?}
	}

	//? fabric{
	/*public static <T extends Item> T register(String name, Function<Item.Properties, T> itemFactory, Item.Properties settings) {
		ResourceKey<Item> itemKey = keyOfItem(name);
		T item = itemFactory.apply(settings.setId(itemKey));
		Registry.register(BuiltInRegistries.ITEM, itemKey, item);
		return item;
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> blockFactory, BlockBehaviour.Properties settings, boolean shouldRegisterItem) {
		ResourceKey<Block> blockKey = keyOfBlock(name);
		Block block = blockFactory.apply(settings.setId(blockKey));

		if (shouldRegisterItem) {
			ResourceKey<Item> itemKey = keyOfItem(name);
			BlockItem blockItem = new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
			Registry.register(BuiltInRegistries.ITEM, itemKey, blockItem);
		}

		return Registry.register(BuiltInRegistries.BLOCK, blockKey, block);
	}

	private static ResourceKey<Block> keyOfBlock(String name) {
		return ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, name));
	}

	private static ResourceKey<Item> keyOfItem(String name) {
		return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, name));
	}

	private static <T extends BlockEntity> BlockEntityType<T> register(
			String name,
			net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.Factory<? extends T> entityFactory,
			Block... blocks
	) {
		Identifier id = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, name);
		return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.<T>create(entityFactory, blocks).build());
	}
	*///?} neoforge{
	private static <T extends Block> DeferredBlock<T> registerBlock(String name, Function<BlockBehaviour.Properties, T> function) {
		DeferredBlock<T> toReturn = BLOCKS.registerBlock(name, function);
		registerBlockItem(name, toReturn);
		return toReturn;
	}

	private static <T extends Block> void registerBlockItem(String name, DeferredBlock<T> block) {
		ITEMS.registerItem(name, properties -> new BlockItem(block.get(), properties.useBlockDescriptionPrefix()));
	}
	//?}
}
