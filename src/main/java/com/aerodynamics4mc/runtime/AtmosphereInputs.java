package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroAtmosphereApi;
import com.aerodynamics4mc.api.AeroBackgroundWindSample;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class AtmosphereInputs {
    private AtmosphereInputs() {}

    static float altitudeLapseRate(ResourceKey<Level> worldKey) {
        var id = worldKey.identifier();
        return AeroAtmosphereApi.altitudeLapseRate(A4mcId.of(id.getNamespace(), id.getPath()));
    }

    static boolean backgroundWindDragEnabled(ResourceKey<Level> worldKey) {
        var id = worldKey.identifier();
        return AeroAtmosphereApi.backgroundWindDragEnabled(A4mcId.of(id.getNamespace(),id.getPath()));
    }

    static AeroBackgroundWindSample backgroundWind(ServerLevel world, int blockX, int blockZ) {
        var id = world.dimension().identifier();
        return AeroAtmosphereApi.sampleBackgroundWind(
            A4mcWorldRef.server(A4mcId.of(id.getNamespace(),id.getPath()),world),blockX,blockZ);
    }

    static AeroAtmosphereApi.CoriolisSample coriolis(ServerLevel world, int blockX, int blockZ, int cellSizeBlocks) {
        var id = world.dimension().identifier();
        return AeroAtmosphereApi.sampleCoriolis(
                A4mcWorldRef.server(A4mcId.of(id.getNamespace(), id.getPath()), world), blockX, blockZ, cellSizeBlocks);
    }
}
