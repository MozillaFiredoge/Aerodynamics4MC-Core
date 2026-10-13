package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.api.*;
import com.aerodynamics4mc.network.packet.AeroLocalWeatherPacket;
import com.aerodynamics4mc.network.packet.AeroMesoscaleMapPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real weather packet, map, temperature and Coriolis regressions; run on an isolated server. */
public final class WeatherAtmosphereRuntimeTest {
    public static void run(ServerLevel world) throws Exception {
        checkSnapshotConsumers();
        var id = world.dimension().location();
        A4mcId dimension = A4mcId.of(id.getNamespace(), id.getPath());
        float originalRate = AeroAtmosphereApi.altitudeLapseRate(dimension);
        try {
            checkLapseRate(world, dimension);
            AeroAtmosphereApi.clearAltitudeLapseRate(dimension);
            checkCoriolis(world, dimension);
        } finally { AeroAtmosphereApi.setAltitudeLapseRate(dimension, originalRate); }
        System.out.println("COARSE_GRID_ATMOSPHERE_PASS: weather/map array order, shared lapse rate, live forcing refresh, custom hemisphere and equator");
    }

    private static void checkSnapshotConsumers() throws Exception {
        int width = 5, layers = 8;
        var snapshot = fixture(width, layers);
        for (int x=0; x<width; x++) for (int layer=0; layer<layers; layer++) for (int z=0; z<width; z++) {
            int index = (x * layers + layer) * width + z;
            snapshot.windX()[index] = 1 + layer * 0.1f + z * 0.01f + x * 0.001f;
            snapshot.windY()[index] = 20;
            snapshot.humidity()[index] = layer == 7 || (layer < 4 && x == 1 && z == 4) ? 1 : 0;
            snapshot.ambientAirTemperatureKelvin()[index] = layer < 4 && x == 1 && z == 4 ? 268 : 300;
        }
        var packet = LocalWeatherGrid.buildPacket(ResourceLocation.fromNamespaceAndPath("test", "layout"), snapshot);
        for (int x=0; x<width; x++) for (int z=0; z<width; z++) {
            int base = (x * width + z) * AeroLocalWeatherPacket.CHANNEL_COUNT;
            boolean wet = x == 1 && z == 4;
            check((packet.getPackedWeather()[base + AeroLocalWeatherPacket.CH_PRECIPITATION] > -32767) == wet,
                  "Rain must stay in its X/Z column and use only the lower four layers");
            check((packet.getPackedWeather()[base + AeroLocalWeatherPacket.CH_CLOUD_WATER] > -32767) == wet,
                  "Cloud water must stay in its X/Z column");
            check(packet.getPackedWeather()[base + AeroLocalWeatherPacket.CH_SNOW_FRACTION] == (wet ? 32767 : -32767),
                  "Snow fraction must use this column's temperature");
        }
        var mapMethod = AeroServerRuntime.class.getDeclaredMethod("buildMeteorologicalMapLayer", MesoscaleGrid.Snapshot.class, int.class);
        mapMethod.setAccessible(true);
        for (int layer=0; layer<layers; layer++) {
            short[] map = (short[]) mapMethod.invoke(AeroServerRuntime.getInstance(), snapshot, layer);
            for (int x=0; x<width; x++) for (int z=0; z<width; z++) {
                float marker = 1 + layer * 0.1f + z * 0.01f + x * 0.001f;
                short expected = (short) Math.round(marker / AeroMesoscaleMapPacket.HORIZONTAL_WIND_RANGE_MPS * 32767);
                check(map[(x * width + z) * AeroMesoscaleMapPacket.CHANNEL_COUNT + AeroMesoscaleMapPacket.CH_WIND_X] == expected,
                      "Map must select the requested layer and Z");
                check(map[(x * width + z) * AeroMesoscaleMapPacket.CHANNEL_COUNT + AeroMesoscaleMapPacket.CH_WIND_Y]
                      == (short)Math.round(20 / AeroMesoscaleMapPacket.VERTICAL_WIND_RANGE_MPS * 32767),
                      "Map must preserve strong vertical wind");
            }
        }
        for (int layer=0; layer<4; layer++) snapshot.terrainSolidMask()[(1 * layers + layer) * width + 4] = 1;
        packet = LocalWeatherGrid.buildPacket(ResourceLocation.fromNamespaceAndPath("test", "layout"), snapshot);
        check(packet.getPackedWeather()[(1 * width + 4) * AeroLocalWeatherPacket.CHANNEL_COUNT + AeroLocalWeatherPacket.CH_PRECIPITATION] == -32767,
              "Solid-mask reads must use the same column/layer order");
        System.out.println("COARSE_GRID_LAYOUT_PASS: real precipitation/cloud/snow packet and all eight map layers");
    }

    private static MesoscaleGrid.Snapshot fixture(int width, int layers) throws Exception {
        var emptyGrid = new MesoscaleGrid(64, 2, 40, layers, 3.2f, 100);
        var empty = emptyGrid.snapshot();
        var components = MesoscaleGrid.Snapshot.class.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        Set<String> columns = Set.of("terrainHeightBlocks", "biomeTemperature", "roughnessLengthMeters");
        for (int i=0; i<components.length; i++) {
            var component = components[i];
            types[i] = component.getType();
            args[i] = component.getAccessor().invoke(empty);
            if (types[i] == float[].class) args[i] = new float[width * width * (columns.contains(component.getName()) ? 1 : layers)];
            if (types[i] == byte[].class) args[i] = new byte[width * width];
            switch (component.getName()) {
                case "gridWidth" -> args[i] = width;
                case "activeLayers" -> args[i] = layers;
                case "radiusCells" -> args[i] = width / 2;
            }
        }
        var constructor = MesoscaleGrid.Snapshot.class.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        emptyGrid.close();
        return constructor.newInstance(args);
    }

    private static void checkLapseRate(ServerLevel world, A4mcId dimension) throws Exception {
        WorldScaleDriver driver = WorldScaleDriver.loadOrCreate(null, world);
        SeedTerrainProvider highTerrain = (w,x,z) -> new SeedTerrainProvider.TerrainSample(world.getSeaLevel()+150, 0.8f, 0.05f, (byte)2);
        AeroAtmosphereApi.clearAltitudeLapseRate(dimension);
        var normal = new BackgroundMetGrid(256, 1, 100);
        normal.refresh(world, null, BlockPos.ZERO, 1, 0.05f, highTerrain, driver);
        float normalTemperature = normal.sample(BlockPos.ZERO).ambientAirTemperatureKelvin();
        AeroAtmosphereApi.setAltitudeLapseRate(dimension, 0.14f);
        var custom = new BackgroundMetGrid(256, 1, 100);
        custom.refresh(world, null, BlockPos.ZERO, 1, 0.05f, highTerrain, driver);
        near(normalTemperature - custom.sample(BlockPos.ZERO).ambientAirTemperatureKelvin(), 150 * (0.14f-0.0065f),
             "L0 terrain-height temperature follows the configured lapse");

        SeedTerrainProvider flat = (w,x,z) -> new SeedTerrainProvider.TerrainSample(world.getSeaLevel(), 0.8f, 0.05f, (byte)2);
        var background = new BackgroundMetGrid(256, 1, 100);
        background.refresh(world, null, BlockPos.ZERO, 1, 0.05f, flat, driver);
        var grid = new MesoscaleGrid(64, 1, 40, 8, 3.2f, 100);
        try {
            grid.refresh(world, BlockPos.ZERO, 1, 0.05f, flat, background);
            var sample = grid.snapshot();
            near(sample.ambientAirTemperatureKelvin()[sample.index(1,0,1)] - sample.ambientAirTemperatureKelvin()[sample.index(1,1,1)],
                 40 * 0.14f, "L1 layer temperatures follow configured lapse");
            near(instability(grid), 2.7f, "Stability threshold uses the same lapse rate");
            AeroAtmosphereApi.clearAltitudeLapseRate(dimension);
            background.refresh(world, null, BlockPos.ZERO, 2, 0.05f, flat, driver);
            grid.refresh(world, BlockPos.ZERO, 2, 0.05f, flat, background);
            sample = grid.snapshot();
            near(sample.forcingAmbientTargetKelvin()[sample.index(1,0,1)] - sample.forcingAmbientTargetKelvin()[sample.index(1,1,1)],
                 40 * 0.0065f, "Live setting change rebuilds forcing before its normal interval");
            near(instability(grid), 8.7075f, "Default stability restored");
        } finally { grid.close(); }
        var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("test", "thermal_fallback"));
        A4mcId fallbackId = A4mcId.of("test", "thermal_fallback");
        try {
            AeroAtmosphereApi.setAltitudeLapseRate(fallbackId, 0.14f);
            var thermal = AeroServerRuntime.sampleThermalEnvironment(new AeroServerRuntime.WorldEnvironmentSnapshot(6000,0,0,63),
                key, new BlockPos(0,213,0), 0.05f);
            near(thermal.ambientAirTemperatureKelvin(), 288.15f - 150 * 0.14f, "Thermal fallback uses the configured lapse");
        } finally { AeroAtmosphereApi.clearAltitudeLapseRate(fallbackId); }
        System.out.println("COARSE_GRID_LAPSE_PASS: L0, L1, stability threshold, thermal fallback, live forcing invalidation");
    }

    private static float instability(MesoscaleGrid grid) throws Exception {
        var method = MesoscaleGrid.class.getDeclaredMethod("computeInstabilityProxy", float.class,float.class,float.class,float.class,
            float.class,float.class,float.class,int.class);
        method.setAccessible(true);
        return (float) method.invoke(grid, 268f,268f,280f,268f,1f,0f,0f,3);
    }

    private static void checkCoriolis(ServerLevel world, A4mcId dimension) throws Exception {
        AtomicInteger hemisphere = new AtomicInteger(1);
        Set<Integer> sampleX = new HashSet<>();
        try (AutoCloseable registration = AeroAtmosphereApi.registerCoriolisProvider(dimension, (w,x,z) -> {
            check(w.platformHandle() == world, "Provider receives the real server world");
            sampleX.add(x);
            return OptionalDouble.of(hemisphere.get());
        })) {
            SeedTerrainProvider flat = (w,x,z) -> new SeedTerrainProvider.TerrainSample(world.getSeaLevel(), 0.8f, 0.05f, (byte)2);
            var background = new BackgroundMetGrid(256, 1, 100);
            background.refresh(world, null, BlockPos.ZERO, 1, 0.05f, flat, WorldScaleDriver.loadOrCreate(null,world));
            checkPressureTurning(background, hemisphere);
            // Seed a uniform published aloft wind to isolate Ekman turning from changing planetary forcing.
            var cellsField = BackgroundMetGrid.class.getDeclaredField("cells");
            cellsField.setAccessible(true);
            for (Object cell : ((Map<?,?>) cellsField.get(background)).values()) {
                for (String name : List.of("backgroundWindX", "geostrophicWindX")) setFloat(cell, name, 4);
                for (String name : List.of("backgroundWindZ", "geostrophicWindZ", "pressureGradientXPaPerMeter", "pressureGradientZPaPerMeter")) setFloat(cell,name,0);
                setFloat(cell,"surfaceTemperatureKelvin",288.15f);
                setFloat(cell,"ambientAirTemperatureKelvin",288.15f);
            }
            var publish = BackgroundMetGrid.class.getDeclaredMethod("publishReadState");
            publish.setAccessible(true);
            publish.invoke(background);
            var grid = new MesoscaleGrid(64,1,40,8,3.2f,100);
            try {
                grid.refresh(world,BlockPos.ZERO,1,0.05f,flat,background);
                float northZ = grid.snapshot().forcingSurfaceWindZ()[grid.snapshot().index(1,0,1)];
                hemisphere.set(-1); AeroAtmosphereApi.invalidateAtmosphere();
                grid.refresh(world,BlockPos.ZERO,2,0.05f,flat,background);
                float southZ = grid.snapshot().forcingSurfaceWindZ()[grid.snapshot().index(1,0,1)];
                check(northZ < 0 && southZ > 0, "Hemisphere must reverse Ekman turning");
                near(northZ + southZ, 0, "Turning is symmetric for equal factors");
                hemisphere.set(0); AeroAtmosphereApi.invalidateAtmosphere();
                grid.refresh(world,BlockPos.ZERO,3,0.05f,flat,background);
                near(grid.snapshot().forcingSurfaceWindZ()[grid.snapshot().index(1,0,1)], 0, "Zero custom factor disables turning");
                check(sampleX.contains(128) && sampleX.contains(32), "Both L0 and L1 call the same provider with block coordinates");
            } finally { grid.close(); }
        }
        System.out.println("COARSE_GRID_CORIOLIS_PASS: provider shared by L0/L1, hemisphere reversal, equator, live forcing invalidation");
    }

    private static void setFloat(Object object, String name, float value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.setFloat(object,value);
    }

    private static void checkPressureTurning(BackgroundMetGrid background, AtomicInteger hemisphere) throws Exception {
        var stateType = Class.forName(BackgroundMetGrid.class.getName() + "$StateSample");
        var constructor = stateType.getDeclaredConstructor(float.class,float.class,float.class,float.class,float.class);
        constructor.setAccessible(true);
        Map<Long,Object> previous = new HashMap<>();
        for (int x=-2; x<=2; x++) for (int z=-2; z<=2; z++) {
            previous.put(((long)x << 32) ^ (z & 0xffffffffL), constructor.newInstance(0f,0f,(float)(x+z),288.15f,0.5f));
        }
        var method = BackgroundMetGrid.class.getDeclaredMethod("geostrophicWindFromPressure", Map.class,int.class,int.class);
        method.setAccessible(true);
        hemisphere.set(1);
        Object north = method.invoke(background, previous, 0, 0);
        hemisphere.set(-1);
        Object south = method.invoke(background, previous, 0, 0);
        check(component(north,"windX") < 0 && component(north,"windZ") > 0, "Northern pressure-derived wind direction");
        near(component(north,"windX") + component(south,"windX"), 0, "Pressure-derived X wind reverses by hemisphere");
        near(component(north,"windZ") + component(south,"windZ"), 0, "Pressure-derived Z wind reverses by hemisphere");
        hemisphere.set(0);
        Object equator = method.invoke(background, previous, 0, 0);
        near(component(equator,"windX"), 0, "Zero custom factor disables geostrophic X wind");
        near(component(equator,"windZ"), 0, "Zero custom factor disables geostrophic Z wind");
        hemisphere.set(1);
    }

    private static float component(Object record, String name) throws Exception {
        var getter = record.getClass().getDeclaredMethod(name);
        getter.setAccessible(true);
        return (float)getter.invoke(record);
    }
    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual-expected) < 0.005f, message + ": " + actual + " != " + expected);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
