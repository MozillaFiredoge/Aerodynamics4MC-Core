package com.aerodynamics4mc.compat.createaeronautics.platform.neoforge;

//? neoforge {

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsCompat;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsCompatBlocks;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsDebugCommands;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsFlightPolarService;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsAirfoilDiskStore;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsAirfoilSync;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsWindTunnelService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Method;
import java.util.function.Consumer;

@Mod(CreateAeronauticsCompat.MOD_ID)
public final class NeoforgeCreateAeronauticsCompatEntrypoint {
	private static final String SABLE_PRE_PHYSICS_TICK_EVENT_CLASS =
			"dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent";

	public NeoforgeCreateAeronauticsCompatEntrypoint(IEventBus modEventBus, ModContainer modContainer) {
		CreateAeronauticsCompatBlocks.register(modEventBus);
		if (targetModsLoaded()) {
			CreateAeronauticsCompat.initialize();
			NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
			NeoForge.EVENT_BUS.addListener(this::onServerTick);
			NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
			NeoForge.EVENT_BUS.addListener(this::onServerStarted);
			addSablePrePhysicsTickListener();
		}
	}

	private void onRegisterCommands(RegisterCommandsEvent event) {
		CreateAeronauticsDebugCommands.register(event.getDispatcher());
	}

	private void onServerTick(ServerTickEvent.Post event) {
		CreateAeronauticsWindTunnelService.INSTANCE.tick(event.getServer());
	}

	private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			CreateAeronauticsAirfoilSync.sendToPlayer(player);
		}
	}

	private void onServerStarted(ServerStartedEvent event) {
		CreateAeronauticsAirfoilDiskStore.LoadResult result =
				CreateAeronauticsAirfoilDiskStore.loadAll(event.getServer());
		if (result.loaded() > 0 || result.failed() > 0) {
			ModTemplate.LOGGER.info(
					"Loaded Create Aeronautics airfoil JSON from {}: loaded={}, failed={}",
					result.root(),
					result.loaded(),
					result.failed()
			);
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void addSablePrePhysicsTickListener() {
		try {
			Class<?> eventClass = Class.forName(
					SABLE_PRE_PHYSICS_TICK_EVENT_CLASS,
					false,
					NeoforgeCreateAeronauticsCompatEntrypoint.class.getClassLoader()
			);
			if (Event.class.isAssignableFrom(eventClass)) {
				Consumer<Event> listener = this::onSablePrePhysicsTick;
				NeoForge.EVENT_BUS.addListener((Class) eventClass, (Consumer) listener);
			}
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
	}

	private void onSablePrePhysicsTick(Event event) {
		try {
			Method getPhysicsSystem = event.getClass().getMethod("getPhysicsSystem");
			Method getTimeStep = event.getClass().getMethod("getTimeStep");
			Object physicsSystem = getPhysicsSystem.invoke(event);
			if (physicsSystem == null) {
				return;
			}
			Method getLevel = physicsSystem.getClass().getMethod("getLevel");
			Object level = getLevel.invoke(physicsSystem);
			Object timeStep = getTimeStep.invoke(event);
			if (level instanceof ServerLevel serverLevel && timeStep instanceof Number seconds) {
				CreateAeronauticsFlightPolarService.INSTANCE.physicsTick(serverLevel, seconds.doubleValue());
			}
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
	}

	private static boolean targetModsLoaded() {
		ModList mods = ModList.get();
		return mods.isLoaded(CreateAeronauticsCompat.AERONAUTICS_MOD_ID)
				&& mods.isLoaded(CreateAeronauticsCompat.SIMULATED_MOD_ID)
				&& mods.isLoaded(CreateAeronauticsCompat.SABLE_MOD_ID);
	}
}
//?}
