package com.aerodynamics4mc.content;

import com.aerodynamics4mc.platform.Platform;

public record AeroContentContext(Platform.ModLoader loader, Object modEventBus) {
}
