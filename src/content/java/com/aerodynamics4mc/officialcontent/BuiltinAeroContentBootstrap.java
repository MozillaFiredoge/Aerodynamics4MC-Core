package com.aerodynamics4mc.officialcontent;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.block.FanBlock;
import com.aerodynamics4mc.block.FanBlockEntity;
import com.aerodynamics4mc.block.ModBlocks;
import com.aerodynamics4mc.content.AeroContentBootstrap;
import com.aerodynamics4mc.content.AeroContentContext;
import com.aerodynamics4mc.particle.ModParticles;
import com.aerodynamics4mc.platform.Platform;
import com.aerodynamics4mc.runtime.AeroBlockBehaviors;
import com.aerodynamics4mc.vehicle.ModEntities;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? neoforge {
import net.neoforged.bus.api.IEventBus;
//?}

public final class BuiltinAeroContentBootstrap implements AeroContentBootstrap {
	private static boolean behaviorProviderRegistered;

	@Override
	public void register(AeroContentContext context) {
		registerBlockBehaviors();
		if (context.loader() == Platform.ModLoader.FABRIC) {
			registerFabric();
		} else if (context.loader() == Platform.ModLoader.NEOFORGE) {
			registerNeoForge(context.modEventBus());
		}
	}

	private static synchronized void registerBlockBehaviors() {
		if (behaviorProviderRegistered) {
			return;
		}
		AeroBlockBehaviors.register(new AeroBlockBehaviors.Provider() {
			@Override
			public boolean isFan(BlockState state) {
				return state != null && state.is(ModBlocks.FAN_BLOCK);
			}

			@Override
			public boolean isFanBlockEntity(BlockEntity blockEntity) {
				return blockEntity instanceof FanBlockEntity;
			}

			@Override
			public Direction fanFacing(BlockState state) {
				return state == null ? Direction.NORTH : state.getOptionalValue(FanBlock.FACING).orElse(Direction.NORTH);
			}

			@Override
			public boolean isDuct(BlockState state) {
				return state != null && state.is(ModBlocks.DUCT_BLOCK);
			}
		});
		behaviorProviderRegistered = true;
	}

	private static void registerFabric() {
		//? fabric {
		/*ModEntities.register();
		ModBlocks.register();
		ModParticles.register();
		*///?}
	}

	private static void registerNeoForge(Object modEventBus) {
		//? neoforge {
		if (!(modEventBus instanceof IEventBus eventBus)) {
			ModTemplate.LOGGER.warn("Skipping builtin content registration because NeoForge mod event bus is unavailable");
			return;
		}
		ModEntities.register(eventBus);
		ModBlocks.register(eventBus);
		ModParticles.register(eventBus);
		//?}
	}
}
