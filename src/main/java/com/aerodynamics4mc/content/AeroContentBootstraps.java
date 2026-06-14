package com.aerodynamics4mc.content;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.platform.Platform;

import java.util.ServiceLoader;

public final class AeroContentBootstraps {
	private AeroContentBootstraps() {
	}

	public static void registerBuiltinContent(Platform.ModLoader loader, Object modEventBus) {
		AeroContentContext context = new AeroContentContext(loader, modEventBus);
		for (AeroContentBootstrap bootstrap : ServiceLoader.load(AeroContentBootstrap.class)) {
			ModTemplate.LOGGER.debug("Registering content bootstrap {}", bootstrap.getClass().getName());
			bootstrap.register(context);
		}
	}
}
