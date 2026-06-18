package com.aerodynamics4mc.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class MesoscaleGrid implements AutoCloseable {
    private static final float SURFACE_RELAXATION_PER_SECOND = 1.0f / 600.0f;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float TERRAIN_WIND_DEFLECTION = 0.35f;
    private static final float TERRAIN_TEMPERATURE_OFFSET_K = 4.0f;
    private static final float AMBIENT_LAPSE_RATE_K_PER_BLOCK = 0.0065f;
    private static final float ABL_STABLE_HEIGHT_BLOCKS = 120.0f;
    private static final float ABL_NEUTRAL_HEIGHT_BLOCKS = 280.0f;
    private static final float ABL_UNSTABLE_HEIGHT_BLOCKS = 460.0f;
    private static final float ABL_STABILITY_DELTA_K = 8.0f;
    private static final float ABL_EKMAN_MAX_TURN_RADIANS = 0.52f;
    private static final float ABL_MIN_ROUGHNESS_METERS = 0.02f;
    private static final float ABL_MAX_ROUGHNESS_METERS = 8.0f;
    private static final float MIN_ALOFT_WIND_SPEED_MPS = 0.05f;
    private static final float MAX_L1_WIND_MPS = 9.5f;
    private static final float MAX_L1_LATTICE_WIND = 0.35f;
    private static final float L1_TERRAIN_SLOPE_REFERENCE = 0.18f;
    private static final float L1_TERRAIN_FORM_DRAG = 0.45f;
    private static final float L1_TERRAIN_CONTOUR_DEFLECTION = 0.45f;
    private static final float L1_THERMAL_SLOPE_WIND_MPS = 1.10f;
    private static final float L1_THERMAL_SLOPE_REFERENCE = 0.08f;
    private static final float L1_COAST_BREEZE_DAY_MPS = 2.40f;
    private static final float L1_COAST_BREEZE_NIGHT_MPS = 0.90f;
    private static final float L1_RIDGE_SPEEDUP_MPS = 1.35f;
    private static final float L1_RIDGE_UPDRAFT_MPS = 1.20f;
    private static final float L1_LEE_SHELTER_MAX = 0.55f;
    private static final float L1_VALLEY_CHANNEL_BLEND = 0.62f;
    private static final float L1_VALLEY_SPEEDUP_MPS = 0.90f;
    private static final float L1_TERRAIN_PROMINENCE_REFERENCE_BLOCKS = 36.0f;
    private static final float L1_VALLEY_WALL_REFERENCE_BLOCKS = 44.0f;
    private static final float DEFAULT_MOLECULAR_NU_M2_S = 1.5e-5f;
    private static final float DEFAULT_PRANDTL_AIR = 0.71f;
    private static final float DEFAULT_TURBULENT_PRANDTL = 0.85f;
    private static final int NATIVE_FORCING_CHANNELS = 25;
    private static final int NATIVE_STATE_CHANNELS = 6;
    private static final int CH_TERRAIN_HEIGHT = 0;
    private static final int CH_BIOME_TEMPERATURE = 1;
    private static final int CH_AMBIENT_TARGET = 2;
    private static final int CH_DEEP_GROUND_TARGET = 3;
    private static final int CH_SURFACE_TARGET = 4;
    private static final int CH_ROUGHNESS = 5;
    private static final int CH_BACKGROUND_WIND_X = 6;
    private static final int CH_BACKGROUND_WIND_Z = 7;
    private static final int CH_SURFACE_CLASS = 8;
    private static final int CH_HUMIDITY = 9;
    private static final int CH_CONVECTIVE_HEATING = 10;
    private static final int CH_CONVECTIVE_MOISTENING = 11;
    private static final int CH_CONVECTIVE_INFLOW_X = 12;
    private static final int CH_CONVECTIVE_INFLOW_Z = 13;
    private static final int CH_TORNADO_WIND_X = 14;
    private static final int CH_TORNADO_WIND_Z = 15;
    private static final int CH_TORNADO_HEATING = 16;
    private static final int CH_TORNADO_MOISTENING = 17;
    private static final int CH_TORNADO_UPDRAFT = 18;
    private static final int CH_NESTED_UPDRAFT = 19;
    private static final int CH_NESTED_WIND_X_DELTA = 20;
    private static final int CH_NESTED_WIND_Z_DELTA = 21;
    private static final int CH_NESTED_AMBIENT_DELTA = 22;
    private static final int CH_NESTED_SURFACE_DELTA = 23;
    private static final int CH_SOLID_MASK = 24;
    private static final int OUT_AMBIENT = 0;
    private static final int OUT_DEEP_GROUND = 1;
    private static final int OUT_SURFACE = 2;
    private static final int OUT_WIND_X = 3;
    private static final int OUT_WIND_Y = 4;
    private static final int OUT_WIND_Z = 5;
    private static final float NESTED_FEEDBACK_WIND_BLEND = 0.45f;
    private static final float NESTED_FEEDBACK_AIR_BLEND = 0.35f;
    private static final float NESTED_FEEDBACK_SURFACE_BLEND = 0.30f;
    private static final float NESTED_FEEDBACK_UPDRAFT_BLEND = 0.60f;
    private static final float NESTED_FEEDBACK_MAX_UPDRAFT = 2.5f;

    private final int cellSizeBlocks;
    private final int radiusCells;
    private final int layerHeightBlocks;
    private final int maxLayers;
    private final float stepSeconds;
    private final int forcingRebuildTicks;
    private final Map<Long, CellColumnState> cells = new HashMap<>();
    private final MesoscaleNativeBridge nativeBridge = new MesoscaleNativeBridge();
    private long nativeContextKey;
    private float[] forcingBuffer = new float[0];
    private float[] stateBuffer = new float[0];
    private volatile ReadState readState = ReadState.EMPTY;
    private int centerCellX;
    private int centerCellZ;
    private int activeLayers = 1;
    private int verticalBaseY = 0;
    private long lastTickProcessed = Long.MIN_VALUE;
    private long lastForcingRefreshTick = Long.MIN_VALUE;
    private float accumulatedStepSeconds = 0.0f;
    private boolean forcingReady = false;
    private NestedFeedbackDiagnostics nestedFeedbackDiagnostics = NestedFeedbackDiagnostics.EMPTY;

    MesoscaleGrid(
        int cellSizeBlocks,
        int radiusCells,
        int layerHeightBlocks,
        int maxLayers,
        float stepSeconds,
        int forcingRebuildTicks
    ) {
        this.cellSizeBlocks = cellSizeBlocks;
        this.radiusCells = radiusCells;
        this.layerHeightBlocks = Math.max(1, layerHeightBlocks);
        this.maxLayers = Math.max(1, maxLayers);
        this.stepSeconds = Math.max(1.0e-3f, stepSeconds);
        this.forcingRebuildTicks = Math.max(1, forcingRebuildTicks);
    }

    synchronized void refresh(
        ServerLevel world,
        BlockPos focus,
        long tickCounter,
        float dtSeconds,
        SeedTerrainProvider provider,
        BackgroundMetGrid background
    ) {
        int nextCenterCellX = Math.floorDiv(focus.getX(), cellSizeBlocks);
        int nextCenterCellZ = Math.floorDiv(focus.getZ(), cellSizeBlocks);
        int nextVerticalBaseY = Math.max(world.getSeaLevel(), world.getMinY());
        int nextActiveLayers = computeActiveLayers();
        boolean firstRefresh = lastTickProcessed == Long.MIN_VALUE;
        boolean layoutChanged = lastTickProcessed != Long.MIN_VALUE
            && (nextCenterCellX != centerCellX
                || nextCenterCellZ != centerCellZ
                || nextVerticalBaseY != verticalBaseY
                || nextActiveLayers != activeLayers);
        centerCellX = nextCenterCellX;
        centerCellZ = nextCenterCellZ;
        verticalBaseY = nextVerticalBaseY;
        activeLayers = nextActiveLayers;
        float elapsedSeconds = firstRefresh
            ? Math.max(1.0e-3f, dtSeconds)
            : Math.max(1L, tickCounter - lastTickProcessed) * dtSeconds;
        lastTickProcessed = tickCounter;
        accumulatedStepSeconds += elapsedSeconds;

        if (layoutChanged) {
            releaseNativeContext();
            forcingReady = false;
        }

        boolean forcingRebuildDue = !forcingReady
            || firstRefresh
            || layoutChanged
            || lastForcingRefreshTick == Long.MIN_VALUE
            || tickCounter - lastForcingRefreshTick >= forcingRebuildTicks;
        if (forcingRebuildDue) {
            buildForcing(world, provider, background, stepSeconds);
            lastForcingRefreshTick = tickCounter;
            forcingReady = true;
        }

        if (forcingRebuildDue) {
            // Seed a coherent state immediately after forcing refresh instead of waiting for the first native step.
            refreshFallback(0.0f);
        }

        Iterator<Map.Entry<Long, CellColumnState>> it = cells.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, CellColumnState> entry = it.next();
            int cx = unpackX(entry.getKey());
            int cz = unpackZ(entry.getKey());
            if (Math.abs(cx - centerCellX) > radiusCells || Math.abs(cz - centerCellZ) > radiusCells) {
                it.remove();
            }
        }
        if (forcingRebuildDue) {
            publishReadState();
        }
    }

    Sample sample(BlockPos pos) {
        return readState.sample(pos);
    }

    boolean seedL2Window(
        BlockPos origin,
        int sizeX,
        int sizeY,
        int sizeZ,
        float[] outWindX,
        float[] outWindY,
        float[] outWindZ,
        float[] outAirTemperature,
        float[] outSurfaceTemperature
    ) {
        ReadState state = readState;
        if (state.empty()
            || sizeX <= 0
            || sizeY <= 0
            || sizeZ <= 0) {
            return false;
        }
        int cellsCount = sizeX * sizeY * sizeZ;
        if (outWindX == null
            || outWindY == null
            || outWindZ == null
            || outAirTemperature == null
            || outSurfaceTemperature == null
            || outWindX.length != cellsCount
            || outWindY.length != cellsCount
            || outWindZ.length != cellsCount
            || outAirTemperature.length != cellsCount
            || outSurfaceTemperature.length != cellsCount) {
            return false;
        }

        int minCellX = state.centerCellX() - state.radiusCells();
        int maxCellX = state.centerCellX() + state.radiusCells();
        int minCellZ = state.centerCellZ() - state.radiusCells();
        int maxCellZ = state.centerCellZ() + state.radiusCells();

        int[] lowerCellX = new int[sizeX];
        int[] upperCellX = new int[sizeX];
        float[] cellBlendX = new float[sizeX];
        for (int x = 0; x < sizeX; x++) {
            double worldX = origin.getX() + x + 0.5;
            double cellCoord = (worldX - state.cellSizeBlocks() * 0.5) / state.cellSizeBlocks();
            int lower = Mth.floor(cellCoord);
            int upper = lower + 1;
            float blend = (float) (cellCoord - lower);
            lower = Mth.clamp(lower, minCellX, maxCellX);
            upper = Mth.clamp(upper, minCellX, maxCellX);
            if (lower == upper) {
                blend = 0.0f;
            }
            lowerCellX[x] = lower;
            upperCellX[x] = upper;
            cellBlendX[x] = blend;
        }

        int[] lowerCellZ = new int[sizeZ];
        int[] upperCellZ = new int[sizeZ];
        float[] cellBlendZ = new float[sizeZ];
        for (int z = 0; z < sizeZ; z++) {
            double worldZ = origin.getZ() + z + 0.5;
            double cellCoord = (worldZ - state.cellSizeBlocks() * 0.5) / state.cellSizeBlocks();
            int lower = Mth.floor(cellCoord);
            int upper = lower + 1;
            float blend = (float) (cellCoord - lower);
            lower = Mth.clamp(lower, minCellZ, maxCellZ);
            upper = Mth.clamp(upper, minCellZ, maxCellZ);
            if (lower == upper) {
                blend = 0.0f;
            }
            lowerCellZ[z] = lower;
            upperCellZ[z] = upper;
            cellBlendZ[z] = blend;
        }

        for (int x = 0; x < sizeX; x++) {
            int x0 = lowerCellX[x];
            int x1 = upperCellX[x];
            float tx = cellBlendX[x];
            for (int z = 0; z < sizeZ; z++) {
                int z0 = lowerCellZ[z];
                int z1 = upperCellZ[z];
                float tz = cellBlendZ[z];

                int c00 = state.columnIndexAtClamped(x0, z0);
                int c10 = state.columnIndexAtClamped(x1, z0);
                int c01 = state.columnIndexAtClamped(x0, z1);
                int c11 = state.columnIndexAtClamped(x1, z1);
                if (c00 < 0 || c10 < 0 || c01 < 0 || c11 < 0) {
                    return false;
                }
                for (int y = 0; y < sizeY; y++) {
                    double worldY = origin.getY() + y + 0.5;
                    double layerCoord = (worldY - (state.verticalBaseY() + state.layerHeightBlocks() * 0.5))
                        / state.layerHeightBlocks();
                    int layer0 = Mth.floor(layerCoord);
                    int layer1 = layer0 + 1;
                    float ty = (float) (layerCoord - layer0);
                    layer0 = Mth.clamp(layer0, 0, state.activeLayers() - 1);
                    layer1 = Mth.clamp(layer1, 0, state.activeLayers() - 1);
                    if (layer0 == layer1) {
                        ty = 0.0f;
                    }
                    int cell = (x * sizeY + y) * sizeZ + z;

                    float windX0 = bilerp(
                        state.windXAt(c00, layer0),
                        state.windXAt(c10, layer0),
                        state.windXAt(c01, layer0),
                        state.windXAt(c11, layer0),
                        tx,
                        tz
                    );
                    float windX1 = bilerp(
                        state.windXAt(c00, layer1),
                        state.windXAt(c10, layer1),
                        state.windXAt(c01, layer1),
                        state.windXAt(c11, layer1),
                        tx,
                        tz
                    );
                    float windY0 = bilerp(
                        state.windYAt(c00, layer0),
                        state.windYAt(c10, layer0),
                        state.windYAt(c01, layer0),
                        state.windYAt(c11, layer0),
                        tx,
                        tz
                    );
                    float windY1 = bilerp(
                        state.windYAt(c00, layer1),
                        state.windYAt(c10, layer1),
                        state.windYAt(c01, layer1),
                        state.windYAt(c11, layer1),
                        tx,
                        tz
                    );
                    float windZ0 = bilerp(
                        state.windZAt(c00, layer0),
                        state.windZAt(c10, layer0),
                        state.windZAt(c01, layer0),
                        state.windZAt(c11, layer0),
                        tx,
                        tz
                    );
                    float windZ1 = bilerp(
                        state.windZAt(c00, layer1),
                        state.windZAt(c10, layer1),
                        state.windZAt(c01, layer1),
                        state.windZAt(c11, layer1),
                        tx,
                        tz
                    );
                    float air0 = bilerp(
                        state.ambientAirTemperatureAt(c00, layer0),
                        state.ambientAirTemperatureAt(c10, layer0),
                        state.ambientAirTemperatureAt(c01, layer0),
                        state.ambientAirTemperatureAt(c11, layer0),
                        tx,
                        tz
                    );
                    float air1 = bilerp(
                        state.ambientAirTemperatureAt(c00, layer1),
                        state.ambientAirTemperatureAt(c10, layer1),
                        state.ambientAirTemperatureAt(c01, layer1),
                        state.ambientAirTemperatureAt(c11, layer1),
                        tx,
                        tz
                    );
                    float surface0 = bilerp(
                        state.surfaceTemperatureAt(c00, layer0),
                        state.surfaceTemperatureAt(c10, layer0),
                        state.surfaceTemperatureAt(c01, layer0),
                        state.surfaceTemperatureAt(c11, layer0),
                        tx,
                        tz
                    );
                    float surface1 = bilerp(
                        state.surfaceTemperatureAt(c00, layer1),
                        state.surfaceTemperatureAt(c10, layer1),
                        state.surfaceTemperatureAt(c01, layer1),
                        state.surfaceTemperatureAt(c11, layer1),
                        tx,
                        tz
                    );

                    outWindX[cell] = Mth.lerp(ty, windX0, windX1);
                    outWindY[cell] = Mth.lerp(ty, windY0, windY1);
                    outWindZ[cell] = Mth.lerp(ty, windZ0, windZ1);
                    outAirTemperature[cell] = Mth.lerp(ty, air0, air1);
                    outSurfaceTemperature[cell] = Mth.lerp(ty, surface0, surface1);
                }
            }
        }
        return true;
    }

    int cellCount() {
        return readState.cellCount();
    }

    synchronized DiagnosticsSummary diagnosticsSummary(BlockPos focus) {
        if (cells.isEmpty() || activeLayers <= 0) {
            return DiagnosticsSummary.EMPTY;
        }
        int centerX = Math.floorDiv(focus.getX(), cellSizeBlocks);
        int centerZ = Math.floorDiv(focus.getZ(), cellSizeBlocks);
        TornadoEnvironment environment = sampleEnvironment(centerX, centerZ, 2);
        if (environment.sampledStateCount() <= 0) {
            return DiagnosticsSummary.EMPTY;
        }
        return new DiagnosticsSummary(
            environment.meanInstabilityProxy(),
            environment.maxInstabilityProxy(),
            environment.meanLowLevelShear(),
            environment.meanHumidity(),
            environment.meanPositiveMoistureConvergence(),
            environment.maxPositiveMoistureConvergence(),
            environment.meanLiftProxy(),
            environment.maxLiftProxy(),
            environment.sampledStateCount()
        );
    }

    synchronized TornadoEnvironment sampleTornadoEnvironment(BlockPos pos, int radiusCells) {
        if (cells.isEmpty() || activeLayers <= 0) {
            return TornadoEnvironment.EMPTY;
        }
        int centerX = Math.floorDiv(pos.getX(), cellSizeBlocks);
        int centerZ = Math.floorDiv(pos.getZ(), cellSizeBlocks);
        return sampleEnvironment(centerX, centerZ, Math.max(0, radiusCells));
    }

    synchronized Snapshot snapshot() {
        int gridWidth = radiusCells * 2 + 1;
        int columnCount = gridWidth * gridWidth;
        int stateCount = columnCount * activeLayers;
        float[] terrainHeightBlocks = new float[columnCount];
        float[] biomeTemperature = new float[columnCount];
        float[] roughnessLengthMeters = new float[columnCount];
        byte[] surfaceClass = new byte[columnCount];
        float[] ambientAirTemperatureKelvin = new float[stateCount];
        float[] deepGroundTemperatureKelvin = new float[stateCount];
        float[] surfaceTemperatureKelvin = new float[stateCount];
        float[] forcingAmbientTargetKelvin = new float[stateCount];
        float[] forcingSurfaceTargetKelvin = new float[stateCount];
        float[] forcingBackgroundWindX = new float[stateCount];
        float[] forcingBackgroundWindZ = new float[stateCount];
        float[] forcingSurfaceWindX = new float[stateCount];
        float[] forcingSurfaceWindZ = new float[stateCount];
        float[] forcingGeostrophicWindX = new float[stateCount];
        float[] forcingGeostrophicWindZ = new float[stateCount];
        float[] forcingWindShearXPerBlock = new float[stateCount];
        float[] forcingWindShearZPerBlock = new float[stateCount];
        float[] ablHeightBlocks = new float[stateCount];
        float[] ablHeightAglBlocks = new float[stateCount];
        float[] ablStability = new float[stateCount];
        float[] ablMixingStrength = new float[stateCount];
        float[] ablProfileBlend = new float[stateCount];
        float[] terrainCoastBreezeX = new float[stateCount];
        float[] terrainCoastBreezeZ = new float[stateCount];
        float[] terrainRidgeWindX = new float[stateCount];
        float[] terrainRidgeWindZ = new float[stateCount];
        float[] terrainValleyWindX = new float[stateCount];
        float[] terrainValleyWindZ = new float[stateCount];
        float[] terrainLeeShelter = new float[stateCount];
        float[] terrainUpdraft = new float[stateCount];
        float[] terrainTurbulence = new float[stateCount];
        float[] terrainRoughnessDrag = new float[stateCount];
        float[] forcingNestedAmbientDeltaKelvin = new float[stateCount];
        float[] forcingNestedSurfaceDeltaKelvin = new float[stateCount];
        float[] forcingNestedWindXDelta = new float[stateCount];
        float[] forcingNestedWindZDelta = new float[stateCount];
        float[] forcingNestedUpdraft = new float[stateCount];
        float[] terrainSolidMask = new float[stateCount];
        float[] windX = new float[stateCount];
        float[] windY = new float[stateCount];
        float[] windZ = new float[stateCount];
        float[] humidity = new float[stateCount];
        float[] convectiveHeating = new float[stateCount];
        float[] convectiveMoistening = new float[stateCount];
        float[] convectiveInflowX = new float[stateCount];
        float[] convectiveInflowZ = new float[stateCount];
        float[] tornadoHeating = new float[stateCount];
        float[] tornadoMoistening = new float[stateCount];
        float[] tornadoWindX = new float[stateCount];
        float[] tornadoWindZ = new float[stateCount];
        float[] tornadoUpdraft = new float[stateCount];
        float[] instabilityProxy = new float[stateCount];
        float[] lowLevelShear = new float[stateCount];
        float[] moistureConvergence = new float[stateCount];
        float[] liftProxy = new float[stateCount];

        for (int cx = centerCellX - radiusCells; cx <= centerCellX + radiusCells; cx++) {
            int localX = cx - (centerCellX - radiusCells);
            for (int cz = centerCellZ - radiusCells; cz <= centerCellZ + radiusCells; cz++) {
                int localZ = cz - (centerCellZ - radiusCells);
                int columnIndex = localX * gridWidth + localZ;
                CellColumnState cell = cells.get(pack(cx, cz));
                if (cell == null) {
                    continue;
                }
                terrainHeightBlocks[columnIndex] = cell.terrainHeightBlocks;
                biomeTemperature[columnIndex] = cell.biomeTemperature;
                roughnessLengthMeters[columnIndex] = cell.roughnessLengthMeters;
                surfaceClass[columnIndex] = cell.surfaceClass;
                int layers = Math.min(activeLayers, cell.layerCount());
                for (int layer = 0; layer < layers; layer++) {
                    int stateIndex = snapshotIndex(localX, layer, localZ, gridWidth);
                    int forcingBase = forcingIndex(cx, layer, cz, gridWidth) * NATIVE_FORCING_CHANNELS;
                    ambientAirTemperatureKelvin[stateIndex] = cell.ambientAirTemperatureKelvin[layer];
                    deepGroundTemperatureKelvin[stateIndex] = cell.deepGroundTemperatureKelvin[layer];
                    surfaceTemperatureKelvin[stateIndex] = cell.surfaceTemperatureKelvin[layer];
                    forcingAmbientTargetKelvin[stateIndex] = forcingBase >= 0 && forcingBase + CH_AMBIENT_TARGET < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_AMBIENT_TARGET]
                        : 0.0f;
                    forcingSurfaceTargetKelvin[stateIndex] = forcingBase >= 0 && forcingBase + CH_SURFACE_TARGET < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_SURFACE_TARGET]
                        : 0.0f;
                    forcingBackgroundWindX[stateIndex] = forcingBase >= 0 && forcingBase + CH_BACKGROUND_WIND_X < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_BACKGROUND_WIND_X]
                        : 0.0f;
                    forcingBackgroundWindZ[stateIndex] = forcingBase >= 0 && forcingBase + CH_BACKGROUND_WIND_Z < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_BACKGROUND_WIND_Z]
                        : 0.0f;
                    forcingSurfaceWindX[stateIndex] = cell.surfaceWindX[layer];
                    forcingSurfaceWindZ[stateIndex] = cell.surfaceWindZ[layer];
                    forcingGeostrophicWindX[stateIndex] = cell.geostrophicWindX[layer];
                    forcingGeostrophicWindZ[stateIndex] = cell.geostrophicWindZ[layer];
                    forcingWindShearXPerBlock[stateIndex] = cell.windShearXPerBlock[layer];
                    forcingWindShearZPerBlock[stateIndex] = cell.windShearZPerBlock[layer];
                    ablHeightBlocks[stateIndex] = cell.ablHeightBlocks[layer];
                    ablHeightAglBlocks[stateIndex] = cell.ablHeightAglBlocks[layer];
                    ablStability[stateIndex] = cell.ablStability[layer];
                    ablMixingStrength[stateIndex] = cell.ablMixingStrength[layer];
                    ablProfileBlend[stateIndex] = cell.ablProfileBlend[layer];
                    terrainCoastBreezeX[stateIndex] = cell.terrainCoastBreezeX[layer];
                    terrainCoastBreezeZ[stateIndex] = cell.terrainCoastBreezeZ[layer];
                    terrainRidgeWindX[stateIndex] = cell.terrainRidgeWindX[layer];
                    terrainRidgeWindZ[stateIndex] = cell.terrainRidgeWindZ[layer];
                    terrainValleyWindX[stateIndex] = cell.terrainValleyWindX[layer];
                    terrainValleyWindZ[stateIndex] = cell.terrainValleyWindZ[layer];
                    terrainLeeShelter[stateIndex] = cell.terrainLeeShelter[layer];
                    terrainUpdraft[stateIndex] = cell.terrainUpdraft[layer];
                    terrainTurbulence[stateIndex] = cell.terrainTurbulence[layer];
                    terrainRoughnessDrag[stateIndex] = cell.terrainRoughnessDrag[layer];
                    forcingNestedAmbientDeltaKelvin[stateIndex] = forcingBase >= 0 && forcingBase + CH_NESTED_AMBIENT_DELTA < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_NESTED_AMBIENT_DELTA]
                        : 0.0f;
                    forcingNestedSurfaceDeltaKelvin[stateIndex] = forcingBase >= 0 && forcingBase + CH_NESTED_SURFACE_DELTA < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_NESTED_SURFACE_DELTA]
                        : 0.0f;
                    forcingNestedWindXDelta[stateIndex] = forcingBase >= 0 && forcingBase + CH_NESTED_WIND_X_DELTA < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_NESTED_WIND_X_DELTA]
                        : 0.0f;
                    forcingNestedWindZDelta[stateIndex] = forcingBase >= 0 && forcingBase + CH_NESTED_WIND_Z_DELTA < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_NESTED_WIND_Z_DELTA]
                        : 0.0f;
                    forcingNestedUpdraft[stateIndex] = forcingBase >= 0 && forcingBase + CH_NESTED_UPDRAFT < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_NESTED_UPDRAFT]
                        : 0.0f;
                    terrainSolidMask[stateIndex] = forcingBase >= 0 && forcingBase + CH_SOLID_MASK < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_SOLID_MASK]
                        : 0.0f;
                    windX[stateIndex] = cell.windX[layer];
                    windY[stateIndex] = cell.windY[layer];
                    windZ[stateIndex] = cell.windZ[layer];
                    humidity[stateIndex] = cell.humidity[layer];
                    convectiveHeating[stateIndex] = forcingBase >= 0 && forcingBase + CH_CONVECTIVE_HEATING < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_CONVECTIVE_HEATING]
                        : 0.0f;
                    convectiveMoistening[stateIndex] = forcingBase >= 0 && forcingBase + CH_CONVECTIVE_MOISTENING < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_CONVECTIVE_MOISTENING]
                        : 0.0f;
                    convectiveInflowX[stateIndex] = forcingBase >= 0 && forcingBase + CH_CONVECTIVE_INFLOW_X < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_CONVECTIVE_INFLOW_X]
                        : 0.0f;
                    convectiveInflowZ[stateIndex] = forcingBase >= 0 && forcingBase + CH_CONVECTIVE_INFLOW_Z < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_CONVECTIVE_INFLOW_Z]
                        : 0.0f;
                    tornadoWindX[stateIndex] = forcingBase >= 0 && forcingBase + CH_TORNADO_WIND_X < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_TORNADO_WIND_X]
                        : 0.0f;
                    tornadoWindZ[stateIndex] = forcingBase >= 0 && forcingBase + CH_TORNADO_WIND_Z < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_TORNADO_WIND_Z]
                        : 0.0f;
                    tornadoHeating[stateIndex] = forcingBase >= 0 && forcingBase + CH_TORNADO_HEATING < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_TORNADO_HEATING]
                        : 0.0f;
                    tornadoMoistening[stateIndex] = forcingBase >= 0 && forcingBase + CH_TORNADO_MOISTENING < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_TORNADO_MOISTENING]
                        : 0.0f;
                    tornadoUpdraft[stateIndex] = forcingBase >= 0 && forcingBase + CH_TORNADO_UPDRAFT < forcingBuffer.length
                        ? forcingBuffer[forcingBase + CH_TORNADO_UPDRAFT]
                            + (forcingBase + CH_NESTED_UPDRAFT < forcingBuffer.length
                                ? forcingBuffer[forcingBase + CH_NESTED_UPDRAFT]
                                : 0.0f)
                        : 0.0f;
                }
            }
        }

        computeDiagnostics(
            gridWidth,
            activeLayers,
            ambientAirTemperatureKelvin,
            deepGroundTemperatureKelvin,
            surfaceTemperatureKelvin,
            windX,
            windZ,
            humidity,
            convectiveHeating,
            convectiveMoistening,
            convectiveInflowX,
            convectiveInflowZ,
            tornadoHeating,
            tornadoMoistening,
            tornadoWindX,
            tornadoWindZ,
            tornadoUpdraft,
            instabilityProxy,
            lowLevelShear,
            moistureConvergence,
            liftProxy
        );

        return new Snapshot(
            gridWidth,
            activeLayers,
            cellSizeBlocks,
            layerHeightBlocks,
            radiusCells,
            centerCellX,
            centerCellZ,
            verticalBaseY,
            stepSeconds,
            lastTickProcessed,
            terrainHeightBlocks,
            biomeTemperature,
            roughnessLengthMeters,
            surfaceClass,
            ambientAirTemperatureKelvin,
            deepGroundTemperatureKelvin,
            surfaceTemperatureKelvin,
            forcingAmbientTargetKelvin,
            forcingSurfaceTargetKelvin,
            forcingBackgroundWindX,
            forcingBackgroundWindZ,
            forcingSurfaceWindX,
            forcingSurfaceWindZ,
            forcingGeostrophicWindX,
            forcingGeostrophicWindZ,
            forcingWindShearXPerBlock,
            forcingWindShearZPerBlock,
            ablHeightBlocks,
            ablHeightAglBlocks,
            ablStability,
            ablMixingStrength,
            ablProfileBlend,
            terrainCoastBreezeX,
            terrainCoastBreezeZ,
            terrainRidgeWindX,
            terrainRidgeWindZ,
            terrainValleyWindX,
            terrainValleyWindZ,
            terrainLeeShelter,
            terrainUpdraft,
            terrainTurbulence,
            terrainRoughnessDrag,
            forcingNestedAmbientDeltaKelvin,
            forcingNestedSurfaceDeltaKelvin,
            forcingNestedWindXDelta,
            forcingNestedWindZDelta,
            forcingNestedUpdraft,
            terrainSolidMask,
            windX,
            windY,
            windZ,
            humidity,
            instabilityProxy,
            lowLevelShear,
            moistureConvergence,
            liftProxy,
            nestedFeedbackDiagnostics
        );
    }

    @Override
    public synchronized void close() {
        releaseNativeContext();
        cells.clear();
        forcingBuffer = new float[0];
        stateBuffer = new float[0];
        readState = ReadState.EMPTY;
        lastTickProcessed = Long.MIN_VALUE;
        lastForcingRefreshTick = Long.MIN_VALUE;
        accumulatedStepSeconds = 0.0f;
        forcingReady = false;
        nestedFeedbackDiagnostics = NestedFeedbackDiagnostics.EMPTY;
    }

    synchronized void runPendingSteps() {
        if (!forcingReady) {
            return;
        }
        int stepsToRun = Math.min(4, (int) Math.floor(accumulatedStepSeconds / stepSeconds));
        if (stepsToRun <= 0) {
            return;
        }
        for (int i = 0; i < stepsToRun; i++) {
            if (!stepNative(stepSeconds)) {
                refreshFallback(stepSeconds);
            }
        }
        accumulatedStepSeconds -= stepsToRun * stepSeconds;
        publishReadState();
    }

    synchronized void applyPendingNestedFeedback(Iterable<NestedFeedbackBin> feedbackBins) {
        if (feedbackBins == null || activeLayers <= 0 || forcingBuffer.length == 0) {
            return;
        }
        Map<NestedFeedbackKey, NestedFeedbackAccumulator> aggregates = new HashMap<>();
        int inputBinCount = 0;
        int acceptedBinCount = 0;
        for (NestedFeedbackBin bin : feedbackBins) {
            inputBinCount++;
            if (bin == null
                || !(bin.volumeAverage() > 0.0f)
                || !Float.isFinite(bin.volumeAverage())
                || !Float.isFinite(bin.densityAverage())
                || !Float.isFinite(bin.momentumXAverage())
                || !Float.isFinite(bin.momentumZAverage())
                || !Float.isFinite(bin.airTemperatureVolumeAverage())
                || !Float.isFinite(bin.surfaceTemperatureVolumeAverage())
                || !Float.isFinite(bin.bottomAreaAverage())
                || !Float.isFinite(bin.bottomMassFluxAverage())
                || !Float.isFinite(bin.topAreaAverage())
                || !Float.isFinite(bin.topMassFluxAverage())) {
                continue;
            }
            int layer = Mth.clamp(bin.layer(), 0, activeLayers - 1);
            if (bin.cellX() < centerCellX - radiusCells
                || bin.cellX() > centerCellX + radiusCells
                || bin.cellZ() < centerCellZ - radiusCells
                || bin.cellZ() > centerCellZ + radiusCells) {
                continue;
            }
            NestedFeedbackKey key = new NestedFeedbackKey(bin.cellX(), layer, bin.cellZ());
            aggregates.computeIfAbsent(key, ignored -> new NestedFeedbackAccumulator()).add(bin);
            acceptedBinCount++;
        }
        if (aggregates.isEmpty()) {
            nestedFeedbackDiagnostics = new NestedFeedbackDiagnostics(
                lastTickProcessed,
                inputBinCount,
                acceptedBinCount,
                0,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f
            );
            return;
        }

        int gridWidth = radiusCells * 2 + 1;
        float coarseCellVolume = Math.max(1.0f, cellSizeBlocks * cellSizeBlocks * layerHeightBlocks);
        int appliedCellCount = 0;
        float coverageSum = 0.0f;
        float maxCoverage = 0.0f;
        float windDeltaSum = 0.0f;
        float maxWindDelta = 0.0f;
        float airDeltaSum = 0.0f;
        float maxAirDelta = 0.0f;
        float surfaceDeltaSum = 0.0f;
        float maxSurfaceDelta = 0.0f;
        float bottomFluxDensitySum = 0.0f;
        float topFluxDensitySum = 0.0f;
        float nestedUpdraftSum = 0.0f;
        float maxAbsNestedUpdraft = 0.0f;
        for (Map.Entry<NestedFeedbackKey, NestedFeedbackAccumulator> entry : aggregates.entrySet()) {
            NestedFeedbackKey key = entry.getKey();
            NestedFeedbackAccumulator aggregate = entry.getValue();
            float densityAverage = Math.max(1.0e-6f, aggregate.densityAverage);
            float coverage = Mth.clamp(aggregate.volumeAverage / coarseCellVolume, 0.0f, 1.0f);
            if (coverage <= 0.0f || !Float.isFinite(densityAverage) || !Float.isFinite(coverage)) {
                continue;
            }

            float meanWindX = aggregate.momentumXAverage / densityAverage;
            float meanWindZ = aggregate.momentumZAverage / densityAverage;
            float meanAirTemperature = aggregate.airTemperatureVolumeAverage / Math.max(1.0e-6f, aggregate.volumeAverage);
            float meanSurfaceTemperature = aggregate.surfaceTemperatureVolumeAverage / Math.max(1.0e-6f, aggregate.volumeAverage);
            float bottomFluxDensity = aggregate.bottomAreaAverage > 0.0f
                ? aggregate.bottomMassFluxAverage / aggregate.bottomAreaAverage
                : 0.0f;
            float topFluxDensity = aggregate.topAreaAverage > 0.0f
                ? aggregate.topMassFluxAverage / aggregate.topAreaAverage
                : 0.0f;
            float meanVerticalVelocity = 0.5f * (bottomFluxDensity + topFluxDensity) / densityAverage;
            float nestedUpdraft = Mth.clamp(meanVerticalVelocity, -NESTED_FEEDBACK_MAX_UPDRAFT, NESTED_FEEDBACK_MAX_UPDRAFT);
            if (!Float.isFinite(meanWindX)
                || !Float.isFinite(meanWindZ)
                || !Float.isFinite(meanAirTemperature)
                || !Float.isFinite(bottomFluxDensity)
                || !Float.isFinite(topFluxDensity)
                || !Float.isFinite(nestedUpdraft)) {
                continue;
            }

            int base = forcingIndex(key.cellX(), key.layer(), key.cellZ(), gridWidth) * NATIVE_FORCING_CHANNELS;
            float windBlend = Mth.clamp(NESTED_FEEDBACK_WIND_BLEND * coverage, 0.0f, 1.0f);
            float airBlend = Mth.clamp(NESTED_FEEDBACK_AIR_BLEND * coverage, 0.0f, 1.0f);
            float surfaceBlend = Mth.clamp(NESTED_FEEDBACK_SURFACE_BLEND * coverage, 0.0f, 1.0f);
            float updraftBlend = Mth.clamp(NESTED_FEEDBACK_UPDRAFT_BLEND * coverage, 0.0f, 1.0f);

            float previousForcingWindX = finiteOrDefault(forcingBuffer[base + CH_BACKGROUND_WIND_X], 0.0f);
            float previousForcingWindZ = finiteOrDefault(forcingBuffer[base + CH_BACKGROUND_WIND_Z], 0.0f);
            float previousForcingAmbient = finiteOrDefault(forcingBuffer[base + CH_AMBIENT_TARGET], meanAirTemperature);
            float previousForcingSurface = finiteOrDefault(forcingBuffer[base + CH_SURFACE_TARGET], meanSurfaceTemperature);
            float previousNestedWindXDelta = finiteOrDefault(forcingBuffer[base + CH_NESTED_WIND_X_DELTA], 0.0f);
            float previousNestedWindZDelta = finiteOrDefault(forcingBuffer[base + CH_NESTED_WIND_Z_DELTA], 0.0f);
            float previousNestedAmbientDelta = finiteOrDefault(forcingBuffer[base + CH_NESTED_AMBIENT_DELTA], 0.0f);
            float previousNestedSurfaceDelta = finiteOrDefault(forcingBuffer[base + CH_NESTED_SURFACE_DELTA], 0.0f);
            float previousNestedUpdraft = finiteOrDefault(forcingBuffer[base + CH_NESTED_UPDRAFT], 0.0f);
            float targetNestedWindXDelta = meanWindX - previousForcingWindX;
            float targetNestedWindZDelta = meanWindZ - previousForcingWindZ;
            float targetNestedAmbientDelta = meanAirTemperature - previousForcingAmbient;

            // Feed back into dedicated nested-delta channels only. The base background
            // and target channels stay owned by the coarse-scale model.
            forcingBuffer[base + CH_NESTED_WIND_X_DELTA] = Mth.lerp(windBlend, previousNestedWindXDelta, targetNestedWindXDelta);
            forcingBuffer[base + CH_NESTED_WIND_Z_DELTA] = Mth.lerp(windBlend, previousNestedWindZDelta, targetNestedWindZDelta);
            forcingBuffer[base + CH_NESTED_AMBIENT_DELTA] = Mth.lerp(airBlend, previousNestedAmbientDelta, targetNestedAmbientDelta);
            forcingBuffer[base + CH_NESTED_UPDRAFT] = Mth.lerp(updraftBlend, previousNestedUpdraft, nestedUpdraft);

            float appliedSurfaceDelta = 0.0f;
            if (Float.isFinite(meanSurfaceTemperature) && meanSurfaceTemperature > 0.0f) {
                float targetNestedSurfaceDelta = meanSurfaceTemperature - previousForcingSurface;
                forcingBuffer[base + CH_NESTED_SURFACE_DELTA] = Mth.lerp(surfaceBlend, previousNestedSurfaceDelta, targetNestedSurfaceDelta);
                appliedSurfaceDelta = Math.abs(forcingBuffer[base + CH_NESTED_SURFACE_DELTA] - previousNestedSurfaceDelta);
            }

            float appliedWindDelta = (float) Math.sqrt(
                (forcingBuffer[base + CH_NESTED_WIND_X_DELTA] - previousNestedWindXDelta)
                    * (forcingBuffer[base + CH_NESTED_WIND_X_DELTA] - previousNestedWindXDelta)
                    + (forcingBuffer[base + CH_NESTED_WIND_Z_DELTA] - previousNestedWindZDelta)
                    * (forcingBuffer[base + CH_NESTED_WIND_Z_DELTA] - previousNestedWindZDelta)
            );
            float appliedAirDelta = Math.abs(forcingBuffer[base + CH_NESTED_AMBIENT_DELTA] - previousNestedAmbientDelta);
            float appliedNestedUpdraft = forcingBuffer[base + CH_NESTED_UPDRAFT];

            appliedCellCount++;
            coverageSum += coverage;
            maxCoverage = Math.max(maxCoverage, coverage);
            windDeltaSum += appliedWindDelta;
            maxWindDelta = Math.max(maxWindDelta, appliedWindDelta);
            airDeltaSum += appliedAirDelta;
            maxAirDelta = Math.max(maxAirDelta, appliedAirDelta);
            surfaceDeltaSum += appliedSurfaceDelta;
            maxSurfaceDelta = Math.max(maxSurfaceDelta, appliedSurfaceDelta);
            bottomFluxDensitySum += bottomFluxDensity;
            topFluxDensitySum += topFluxDensity;
            nestedUpdraftSum += appliedNestedUpdraft;
            maxAbsNestedUpdraft = Math.max(maxAbsNestedUpdraft, Math.max(Math.abs(appliedNestedUpdraft), Math.abs(previousNestedUpdraft)));
        }

        if (appliedCellCount <= 0) {
            nestedFeedbackDiagnostics = new NestedFeedbackDiagnostics(
                lastTickProcessed,
                inputBinCount,
                acceptedBinCount,
                0,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f
            );
            return;
        }

        float appliedCellCountInv = 1.0f / appliedCellCount;
        nestedFeedbackDiagnostics = new NestedFeedbackDiagnostics(
            lastTickProcessed,
            inputBinCount,
            acceptedBinCount,
            appliedCellCount,
            coverageSum * appliedCellCountInv,
            maxCoverage,
            windDeltaSum * appliedCellCountInv,
            maxWindDelta,
            airDeltaSum * appliedCellCountInv,
            maxAirDelta,
            surfaceDeltaSum * appliedCellCountInv,
            maxSurfaceDelta,
            bottomFluxDensitySum * appliedCellCountInv,
            topFluxDensitySum * appliedCellCountInv,
            nestedUpdraftSum * appliedCellCountInv,
            maxAbsNestedUpdraft
        );
    }

    synchronized NestedFeedbackDiagnostics nestedFeedbackDiagnostics() {
        return nestedFeedbackDiagnostics;
    }

    private int cellCenterBlock(int cell) {
        return cell * cellSizeBlocks + cellSizeBlocks / 2;
    }

    private void buildForcing(
        ServerLevel world,
        SeedTerrainProvider provider,
        BackgroundMetGrid background,
        float deltaSeconds
    ) {
        int gridWidth = radiusCells * 2 + 1;
        int cellCount = gridWidth * activeLayers * gridWidth;
        boolean preserveNestedFeedback = forcingReady
            && forcingBuffer.length == cellCount * NATIVE_FORCING_CHANNELS;
        ensureNativeBuffers(cellCount);
        MesoscaleNativeBridge.Transport transport = nativeBridge.deriveTransport(
            gridWidth,
            activeLayers,
            gridWidth,
            cellSizeBlocks,
            stepSeconds,
            DEFAULT_MOLECULAR_NU_M2_S,
            DEFAULT_PRANDTL_AIR,
            DEFAULT_TURBULENT_PRANDTL
        );
        float maxBackgroundWind = transport != null
            ? Math.min(MAX_L1_WIND_MPS, MAX_L1_LATTICE_WIND * transport.velocityScaleMetersPerSecond())
            : MAX_L1_WIND_MPS;
        Map<Long, Float> terrainHeightCache = new HashMap<>(cellCount + gridWidth * 4);
        Map<Long, Byte> surfaceClassCache = new HashMap<>(cellCount + gridWidth * 4);

        for (int cx = centerCellX - radiusCells; cx <= centerCellX + radiusCells; cx++) {
            for (int cz = centerCellZ - radiusCells; cz <= centerCellZ + radiusCells; cz++) {
                long key = pack(cx, cz);
                int sampleX = cellCenterBlock(cx);
                int sampleZ = cellCenterBlock(cz);
                CellColumnState cell = cells.computeIfAbsent(key, ignored -> new CellColumnState(activeLayers));
                cell.ensureLayers(activeLayers);
                ensureStaticColumnState(cell, world, provider, sampleX, sampleZ);
                BackgroundMetGrid.Sample bg = background.sample(new BlockPos(sampleX, world.getSeaLevel(), sampleZ));

                float ambientAirTemperatureKelvin = bg != null
                    ? bg.ambientAirTemperatureKelvin()
                    : 288.15f;
                float deepGroundTemperatureKelvin = bg != null
                    ? bg.deepGroundTemperatureKelvin()
                    : ambientAirTemperatureKelvin + 1.5f;
                float terrainDelta = cell.terrainHeightBlocks - world.getSeaLevel();
                float temperatureAdjustment = -Math.max(0.0f, terrainDelta) * 0.01f * TERRAIN_TEMPERATURE_OFFSET_K;
                float terrainSteer = Mth.clamp(terrainDelta / 96.0f, -1.0f, 1.0f);
                float surfaceWindX = bg != null ? bg.backgroundWindX() : 0.0f;
                float surfaceWindZ = bg != null ? bg.backgroundWindZ() : 0.0f;
                float geostrophicWindX = bg != null ? bg.geostrophicWindX() : surfaceWindX;
                float geostrophicWindZ = bg != null ? bg.geostrophicWindZ() : surfaceWindZ;
                float pressureGradientX = bg != null ? bg.pressureGradientXPaPerMeter() : 0.0f;
                float pressureGradientZ = bg != null ? bg.pressureGradientZPaPerMeter() : 0.0f;
                if (windSpeed(geostrophicWindX, geostrophicWindZ) < MIN_ALOFT_WIND_SPEED_MPS
                    && windSpeed(surfaceWindX, surfaceWindZ) >= MIN_ALOFT_WIND_SPEED_MPS
                    && windSpeed(pressureGradientX, pressureGradientZ) <= 1.0e-5f) {
                    geostrophicWindX = surfaceWindX;
                    geostrophicWindZ = surfaceWindZ;
                }
                float bgHumidity = bg != null ? bg.humidity() : 0.50f;
                float bgConvectiveHeating = bg != null ? bg.convectiveHeatingKelvin() : 0.0f;
                float bgConvectiveMoistening = bg != null ? bg.convectiveMoistening() : 0.0f;
                float bgConvectiveInflowX = bg != null ? bg.convectiveInflowX() : 0.0f;
                float bgConvectiveInflowZ = bg != null ? bg.convectiveInflowZ() : 0.0f;
                float bgConvectiveEnvelope = bg != null ? bg.convectiveEnvelope() : 0.0f;
                float bgTornadoWindX = bg != null ? bg.tornadoWindX() : 0.0f;
                float bgTornadoWindZ = bg != null ? bg.tornadoWindZ() : 0.0f;
                float bgTornadoHeating = bg != null ? bg.tornadoHeatingKelvin() : 0.0f;
                float bgTornadoMoistening = bg != null ? bg.tornadoMoistening() : 0.0f;
                float bgTornadoUpdraft = bg != null ? bg.tornadoUpdraftProxy() : 0.0f;
                float surfaceTargetWindX = surfaceWindX * (1.0f - Math.abs(terrainSteer) * 0.25f)
                    - terrainSteer * TERRAIN_WIND_DEFLECTION * surfaceWindZ;
                float surfaceTargetWindZ = surfaceWindZ * (1.0f - Math.abs(terrainSteer) * 0.25f)
                    + terrainSteer * TERRAIN_WIND_DEFLECTION * surfaceWindX;
                surfaceTargetWindX = Mth.clamp(surfaceTargetWindX, -maxBackgroundWind, maxBackgroundWind);
                surfaceTargetWindZ = Mth.clamp(surfaceTargetWindZ, -maxBackgroundWind, maxBackgroundWind);
                float surfaceAirDelta = (bg != null ? bg.surfaceTemperatureKelvin() : ambientAirTemperatureKelvin)
                    - ambientAirTemperatureKelvin;
                TerrainWindContribution terrainContribution = terrainWindContribution(
                    world,
                    provider,
                    cx,
                    cz,
                    terrainHeightCache,
                    surfaceClassCache,
                    surfaceTargetWindX,
                    surfaceTargetWindZ,
                    surfaceAirDelta
                );
                surfaceTargetWindX = Mth.clamp(
                    terrainContribution.finalWindX(),
                    -maxBackgroundWind,
                    maxBackgroundWind
                );
                surfaceTargetWindZ = Mth.clamp(
                    terrainContribution.finalWindZ(),
                    -maxBackgroundWind,
                    maxBackgroundWind
                );
                WindVector cappedSurfaceTarget = clampWindMagnitude(
                    surfaceTargetWindX,
                    surfaceTargetWindZ,
                    MAX_L1_WIND_MPS
                );
                surfaceTargetWindX = cappedSurfaceTarget.x();
                surfaceTargetWindZ = cappedSurfaceTarget.z();
                float aloftTargetWindX = Mth.clamp(geostrophicWindX, -maxBackgroundWind, maxBackgroundWind);
                float aloftTargetWindZ = Mth.clamp(geostrophicWindZ, -maxBackgroundWind, maxBackgroundWind);
                WindVector cappedAloftTarget = clampWindMagnitude(aloftTargetWindX, aloftTargetWindZ, MAX_L1_WIND_MPS);
                aloftTargetWindX = cappedAloftTarget.x();
                aloftTargetWindZ = cappedAloftTarget.z();
                float aloftSpeed = windSpeed(aloftTargetWindX, aloftTargetWindZ);
                float roughnessMeters = Mth.clamp(
                    finiteOrDefault(cell.roughnessLengthMeters, ABL_MIN_ROUGHNESS_METERS),
                    ABL_MIN_ROUGHNESS_METERS,
                    ABL_MAX_ROUGHNESS_METERS
                );
                float stableWeight = Mth.clamp(-surfaceAirDelta / ABL_STABILITY_DELTA_K, 0.0f, 1.0f);
                float unstableWeight = Mth.clamp(surfaceAirDelta / ABL_STABILITY_DELTA_K, 0.0f, 1.0f);
                float stabilityIndex = Mth.clamp(surfaceAirDelta / ABL_STABILITY_DELTA_K, -1.0f, 1.0f);
                float roughnessNorm = Mth.clamp(roughnessMeters / 2.0f, 0.0f, 1.0f);
                float windMixing = Mth.clamp(Math.max(aloftSpeed, windSpeed(surfaceTargetWindX, surfaceTargetWindZ)) / 12.0f, 0.0f, 1.0f);
                float mixingStrength = Mth.clamp(
                    0.34f
                        + 0.42f * unstableWeight
                        + 0.22f * bgConvectiveEnvelope
                        + 0.18f * windMixing
                        - 0.24f * stableWeight
                        - 0.12f * roughnessNorm,
                    0.08f,
                    1.0f
                );
                float ablHeightBlocks = ABL_NEUTRAL_HEIGHT_BLOCKS
                    + unstableWeight * (ABL_UNSTABLE_HEIGHT_BLOCKS - ABL_NEUTRAL_HEIGHT_BLOCKS)
                    - stableWeight * (ABL_NEUTRAL_HEIGHT_BLOCKS - ABL_STABLE_HEIGHT_BLOCKS)
                    + bgConvectiveEnvelope * 96.0f;
                float ekmanWeight = aloftSpeed > MIN_ALOFT_WIND_SPEED_MPS
                    ? Mth.clamp(
                        0.18f + 0.26f * stableWeight + 0.18f * roughnessNorm - 0.16f * unstableWeight,
                        0.0f,
                        0.58f
                    )
                    : 0.0f;
                float ekmanTurn = ABL_EKMAN_MAX_TURN_RADIANS
                    * (0.45f + 0.45f * stableWeight + 0.20f * roughnessNorm - 0.25f * unstableWeight)
                    * Mth.clamp(aloftSpeed / 2.0f, 0.0f, 1.0f);
                float coriolisSign = pseudoCoriolisFactor(cz) >= 0.0f ? 1.0f : -1.0f;
                WindVector ekmanSurfaceWind = rotateWind(aloftTargetWindX, aloftTargetWindZ, -coriolisSign * ekmanTurn);
                surfaceTargetWindX = Mth.lerp(ekmanWeight, surfaceTargetWindX, ekmanSurfaceWind.x());
                surfaceTargetWindZ = Mth.lerp(ekmanWeight, surfaceTargetWindZ, ekmanSurfaceWind.z());
                surfaceTargetWindX = Mth.clamp(surfaceTargetWindX, -maxBackgroundWind, maxBackgroundWind);
                surfaceTargetWindZ = Mth.clamp(surfaceTargetWindZ, -maxBackgroundWind, maxBackgroundWind);
                cappedSurfaceTarget = clampWindMagnitude(surfaceTargetWindX, surfaceTargetWindZ, MAX_L1_WIND_MPS);
                surfaceTargetWindX = cappedSurfaceTarget.x();
                surfaceTargetWindZ = cappedSurfaceTarget.z();

                float previousLayerWindX = surfaceTargetWindX;
                float previousLayerWindZ = surfaceTargetWindZ;
                int previousLayerY = Mth.floor(cell.terrainHeightBlocks);
                boolean previousLayerAir = false;
                for (int layer = 0; layer < activeLayers; layer++) {
                    int sampleY = layerCenterBlockY(layer);
                    boolean terrainSolid = sampleY <= cell.terrainHeightBlocks;
                    float layerHeightAboveGround = Math.max(0.0f, sampleY - cell.terrainHeightBlocks);
                    float layerAmbient = ambientAirTemperatureKelvin + temperatureAdjustment
                        - Math.max(0.0f, sampleY - world.getSeaLevel()) * AMBIENT_LAPSE_RATE_K_PER_BLOCK;
                    float layerDeep = deepGroundTemperatureKelvin + temperatureAdjustment * 0.5f;
                    float aboveGround = layerHeightAboveGround;
                    float surfaceInfluence = (float) Math.exp(-aboveGround / Math.max(1.0f, layerHeightBlocks * 1.5f));
                    float layerSurfaceTarget = layerAmbient + surfaceInfluence
                        * (((bg != null ? bg.surfaceTemperatureKelvin() : ambientAirTemperatureKelvin) + temperatureAdjustment) - layerAmbient);
                    float profileBlend = ablProfileBlend(aboveGround, ablHeightBlocks, mixingStrength, roughnessMeters);
                    float roughnessInfluence = (float) Math.exp(-aboveGround / (48.0f + roughnessMeters * 28.0f));
                    float roughnessDrag = Mth.clamp(
                        roughnessNorm
                            * roughnessInfluence
                            * (0.55f + 0.25f * stableWeight - 0.18f * unstableWeight),
                        0.0f,
                        0.72f
                    );
                    float layerSurfaceWindX = surfaceTargetWindX * (1.0f - roughnessDrag);
                    float layerSurfaceWindZ = surfaceTargetWindZ * (1.0f - roughnessDrag);
                    float layerWindX = Mth.lerp(profileBlend, layerSurfaceWindX, aloftTargetWindX);
                    float layerWindZ = Mth.lerp(profileBlend, layerSurfaceWindZ, aloftTargetWindZ);
                    float terrainCauseWeight = terrainSolid ? 0.0f : 1.0f - profileBlend;
                    cell.terrainCoastBreezeX[layer] = terrainContribution.coastBreezeX() * terrainCauseWeight;
                    cell.terrainCoastBreezeZ[layer] = terrainContribution.coastBreezeZ() * terrainCauseWeight;
                    cell.terrainRidgeWindX[layer] = terrainContribution.ridgeSpeedupX() * terrainCauseWeight;
                    cell.terrainRidgeWindZ[layer] = terrainContribution.ridgeSpeedupZ() * terrainCauseWeight;
                    cell.terrainValleyWindX[layer] = terrainContribution.valleyChannelX() * terrainCauseWeight;
                    cell.terrainValleyWindZ[layer] = terrainContribution.valleyChannelZ() * terrainCauseWeight;
                    cell.terrainLeeShelter[layer] = terrainContribution.leeShelter() * terrainCauseWeight;
                    cell.terrainTurbulence[layer] = terrainContribution.turbulence() * terrainCauseWeight;
                    cell.terrainRoughnessDrag[layer] = roughnessDrag;
                    cell.terrainUpdraft[layer] = terrainContribution.updraftMps()
                        * terrainCauseWeight
                        * (float) Math.exp(-aboveGround / Math.max(16.0f, layerHeightBlocks * 2.0f));
                    layerWindX = Mth.clamp(layerWindX, -maxBackgroundWind, maxBackgroundWind);
                    layerWindZ = Mth.clamp(layerWindZ, -maxBackgroundWind, maxBackgroundWind);
                    WindVector cappedLayerWind = clampWindMagnitude(layerWindX, layerWindZ, MAX_L1_WIND_MPS);
                    layerWindX = cappedLayerWind.x();
                    layerWindZ = cappedLayerWind.z();
                    if (terrainSolid) {
                        layerWindX = 0.0f;
                        layerWindZ = 0.0f;
                    }
                    float shearDy = previousLayerAir
                        ? Math.max(1.0f, sampleY - previousLayerY)
                        : Math.max(1.0f, aboveGround);
                    cell.surfaceWindX[layer] = surfaceTargetWindX;
                    cell.surfaceWindZ[layer] = surfaceTargetWindZ;
                    cell.geostrophicWindX[layer] = aloftTargetWindX;
                    cell.geostrophicWindZ[layer] = aloftTargetWindZ;
                    cell.windShearXPerBlock[layer] = terrainSolid ? 0.0f : (layerWindX - previousLayerWindX) / shearDy;
                    cell.windShearZPerBlock[layer] = terrainSolid ? 0.0f : (layerWindZ - previousLayerWindZ) / shearDy;
                    cell.ablHeightBlocks[layer] = ablHeightBlocks;
                    cell.ablHeightAglBlocks[layer] = layerHeightAboveGround;
                    cell.ablStability[layer] = stabilityIndex;
                    cell.ablMixingStrength[layer] = mixingStrength;
                    cell.ablProfileBlend[layer] = terrainSolid ? 0.0f : profileBlend;
                    float roughnessDecay = 1.0f / (1.0f + aboveGround / 64.0f);
                    float humidityDecay = 0.85f + 0.15f * surfaceInfluence;
                    float layerHumidity = Mth.clamp(bgHumidity * humidityDecay, 0.0f, 1.0f);
                    float convectiveLayerWeight = bgConvectiveEnvelope
                        * surfaceInfluence
                        * (1.0f - 0.60f * (layer / (float) Math.max(1, activeLayers - 1)));
                    float tornadoLayerWeight = surfaceInfluence
                        * (1.0f - 0.75f * (layer / (float) Math.max(1, activeLayers - 1)));
                    int base = forcingIndex(cx, layer, cz, gridWidth) * NATIVE_FORCING_CHANNELS;
                    float preservedNestedUpdraft = preserveNestedFeedback
                        ? finiteOrDefault(forcingBuffer[base + CH_NESTED_UPDRAFT], 0.0f)
                        : 0.0f;
                    float preservedNestedWindXDelta = preserveNestedFeedback
                        ? finiteOrDefault(forcingBuffer[base + CH_NESTED_WIND_X_DELTA], 0.0f)
                        : 0.0f;
                    float preservedNestedWindZDelta = preserveNestedFeedback
                        ? finiteOrDefault(forcingBuffer[base + CH_NESTED_WIND_Z_DELTA], 0.0f)
                        : 0.0f;
                    float preservedNestedAmbientDelta = preserveNestedFeedback
                        ? finiteOrDefault(forcingBuffer[base + CH_NESTED_AMBIENT_DELTA], 0.0f)
                        : 0.0f;
                    float preservedNestedSurfaceDelta = preserveNestedFeedback
                        ? finiteOrDefault(forcingBuffer[base + CH_NESTED_SURFACE_DELTA], 0.0f)
                        : 0.0f;
                    forcingBuffer[base + CH_TERRAIN_HEIGHT] = cell.terrainHeightBlocks;
                    forcingBuffer[base + CH_BIOME_TEMPERATURE] = cell.biomeTemperature;
                    forcingBuffer[base + CH_AMBIENT_TARGET] = layerAmbient;
                    forcingBuffer[base + CH_DEEP_GROUND_TARGET] = layerDeep;
                    forcingBuffer[base + CH_SURFACE_TARGET] = layerSurfaceTarget;
                    forcingBuffer[base + CH_ROUGHNESS] = cell.roughnessLengthMeters * roughnessDecay;
                    forcingBuffer[base + CH_BACKGROUND_WIND_X] = layerWindX;
                    forcingBuffer[base + CH_BACKGROUND_WIND_Z] = layerWindZ;
                    forcingBuffer[base + CH_SURFACE_CLASS] = cell.surfaceClass;
                    forcingBuffer[base + CH_HUMIDITY] = layerHumidity;
                    forcingBuffer[base + CH_CONVECTIVE_HEATING] = bgConvectiveHeating * convectiveLayerWeight;
                    forcingBuffer[base + CH_CONVECTIVE_MOISTENING] = bgConvectiveMoistening * convectiveLayerWeight;
                    forcingBuffer[base + CH_CONVECTIVE_INFLOW_X] = bgConvectiveInflowX * convectiveLayerWeight;
                    forcingBuffer[base + CH_CONVECTIVE_INFLOW_Z] = bgConvectiveInflowZ * convectiveLayerWeight;
                    forcingBuffer[base + CH_TORNADO_WIND_X] = bgTornadoWindX * tornadoLayerWeight;
                    forcingBuffer[base + CH_TORNADO_WIND_Z] = bgTornadoWindZ * tornadoLayerWeight;
                    forcingBuffer[base + CH_TORNADO_HEATING] = bgTornadoHeating * tornadoLayerWeight;
                    forcingBuffer[base + CH_TORNADO_MOISTENING] = bgTornadoMoistening * tornadoLayerWeight;
                    forcingBuffer[base + CH_TORNADO_UPDRAFT] = bgTornadoUpdraft * tornadoLayerWeight;
                    forcingBuffer[base + CH_NESTED_UPDRAFT] = preservedNestedUpdraft;
                    forcingBuffer[base + CH_NESTED_WIND_X_DELTA] = preservedNestedWindXDelta;
                    forcingBuffer[base + CH_NESTED_WIND_Z_DELTA] = preservedNestedWindZDelta;
                    forcingBuffer[base + CH_NESTED_AMBIENT_DELTA] = preservedNestedAmbientDelta;
                    forcingBuffer[base + CH_NESTED_SURFACE_DELTA] = preservedNestedSurfaceDelta;
                    forcingBuffer[base + CH_SOLID_MASK] = terrainSolid ? 1.0f : 0.0f;
                    cell.humidity[layer] = layerHumidity;
                    if (!terrainSolid) {
                        previousLayerWindX = layerWindX;
                        previousLayerWindZ = layerWindZ;
                        previousLayerY = sampleY;
                        previousLayerAir = true;
                    }
                }
            }
        }
    }

    private void ensureStaticColumnState(
        CellColumnState cell,
        ServerLevel world,
        SeedTerrainProvider provider,
        int sampleX,
        int sampleZ
    ) {
        if (cell.staticInitialized) {
            return;
        }
        SeedTerrainProvider.TerrainSample terrain = provider.sample(world, sampleX, sampleZ);
        cell.terrainHeightBlocks = terrain.terrainHeightBlocks();
        cell.biomeTemperature = terrain.biomeTemperature();
        cell.surfaceClass = terrain.surfaceClass();
        cell.roughnessLengthMeters = terrain.roughnessLengthMeters();
        cell.staticInitialized = true;
    }

    private TerrainWindContribution terrainWindContribution(
        ServerLevel world,
        SeedTerrainProvider provider,
        int cellX,
        int cellZ,
        Map<Long, Float> terrainHeightCache,
        Map<Long, Byte> surfaceClassCache,
        float windX,
        float windZ,
        float surfaceAirDelta
    ) {
        float current = terrainHeightAtCell(world, provider, cellX, cellZ, terrainHeightCache);
        float west = terrainHeightAtCell(world, provider, cellX - 1, cellZ, terrainHeightCache);
        float east = terrainHeightAtCell(world, provider, cellX + 1, cellZ, terrainHeightCache);
        float north = terrainHeightAtCell(world, provider, cellX, cellZ - 1, terrainHeightCache);
        float south = terrainHeightAtCell(world, provider, cellX, cellZ + 1, terrainHeightCache);
        float horizontalSpan = Math.max(1.0f, cellSizeBlocks * 2.0f);
        float slopeX = (east - west) / horizontalSpan;
        float slopeZ = (south - north) / horizontalSpan;
        float slopeMagnitude = windSpeed(slopeX, slopeZ);

        float adjustedX = finiteOrDefault(windX, 0.0f);
        float adjustedZ = finiteOrDefault(windZ, 0.0f);
        float windMagnitude = windSpeed(adjustedX, adjustedZ);
        float coastBreezeX = 0.0f;
        float coastBreezeZ = 0.0f;
        float ridgeSpeedupX = 0.0f;
        float ridgeSpeedupZ = 0.0f;
        float valleyChannelX = 0.0f;
        float valleyChannelZ = 0.0f;
        float updraftMps = 0.0f;
        float leeShelter = 0.0f;
        float turbulence = 0.0f;

        float waterWest = waterPresenceAtCell(world, provider, cellX - 1, cellZ, surfaceClassCache);
        float waterEast = waterPresenceAtCell(world, provider, cellX + 1, cellZ, surfaceClassCache);
        float waterNorth = waterPresenceAtCell(world, provider, cellX, cellZ - 1, surfaceClassCache);
        float waterSouth = waterPresenceAtCell(world, provider, cellX, cellZ + 1, surfaceClassCache);
        float waterGradientX = (waterEast - waterWest) * 0.5f;
        float waterGradientZ = (waterSouth - waterNorth) * 0.5f;
        float waterGradientMagnitude = windSpeed(waterGradientX, waterGradientZ);
        if (waterGradientMagnitude > 1.0e-4f) {
            float landwardX = -waterGradientX / waterGradientMagnitude;
            float landwardZ = -waterGradientZ / waterGradientMagnitude;
            float coastWeight = Mth.clamp(waterGradientMagnitude * 1.65f, 0.0f, 1.0f);
            float dayBreeze = daytimeHeatingFactor(world);
            float nightBreeze = nighttimeCoolingFactor(world);
            float weatherDamping = coastalWeatherDamping(world);
            float coastSpeed = (L1_COAST_BREEZE_DAY_MPS * dayBreeze
                - L1_COAST_BREEZE_NIGHT_MPS * nightBreeze)
                * coastWeight
                * weatherDamping;
            coastBreezeX = landwardX * coastSpeed;
            coastBreezeZ = landwardZ * coastSpeed;
            adjustedX += coastBreezeX;
            adjustedZ += coastBreezeZ;
        }

        float adjustedMagnitude = windSpeed(adjustedX, adjustedZ);
        if (adjustedMagnitude > 1.0e-3f) {
            float windUnitX = adjustedX / adjustedMagnitude;
            float windUnitZ = adjustedZ / adjustedMagnitude;
            if (slopeMagnitude > 1.0e-4f) {
                float slopeWeight = Mth.clamp(slopeMagnitude / L1_TERRAIN_SLOPE_REFERENCE, 0.0f, 1.0f);
                float slopeUnitX = slopeX / slopeMagnitude;
                float slopeUnitZ = slopeZ / slopeMagnitude;
                float alongSlope = windUnitX * slopeUnitX + windUnitZ * slopeUnitZ;
                float windwardLift = Math.max(0.0f, alongSlope) * slopeWeight;
                float uphillDrag = Mth.clamp(
                    windwardLift * L1_TERRAIN_FORM_DRAG * 0.58f,
                    0.0f,
                    0.42f
                );
                adjustedX *= 1.0f - uphillDrag;
                adjustedZ *= 1.0f - uphillDrag;
                updraftMps += L1_RIDGE_UPDRAFT_MPS * windwardLift;

                float contourX = -slopeUnitZ;
                float contourZ = slopeUnitX;
                if (adjustedX * contourX + adjustedZ * contourZ < 0.0f) {
                    contourX = -contourX;
                    contourZ = -contourZ;
                }
                float contourPush = Math.abs(alongSlope)
                    * slopeWeight
                    * L1_TERRAIN_CONTOUR_DEFLECTION
                    * adjustedMagnitude;
                adjustedX += contourX * contourPush;
                adjustedZ += contourZ * contourPush;

                float leeWeight = Math.max(0.0f, -alongSlope) * slopeWeight;
                leeShelter = Mth.clamp(leeWeight * L1_LEE_SHELTER_MAX, 0.0f, L1_LEE_SHELTER_MAX);
                adjustedX *= 1.0f - leeShelter;
                adjustedZ *= 1.0f - leeShelter;
                turbulence += leeWeight * 0.65f;

                float thermalSlopeWeight = Mth.clamp(
                    slopeMagnitude / L1_THERMAL_SLOPE_REFERENCE,
                    0.0f,
                    1.0f
                );
                float thermalWeight = Mth.clamp(
                    Math.abs(surfaceAirDelta) / ABL_STABILITY_DELTA_K,
                    0.0f,
                    1.0f
                ) * thermalSlopeWeight;
                if (thermalWeight > 1.0e-3f) {
                    float thermalSign = surfaceAirDelta >= 0.0f ? 1.0f : -1.0f;
                    float thermalSpeed = L1_THERMAL_SLOPE_WIND_MPS * thermalWeight;
                    adjustedX += slopeUnitX * thermalSign * thermalSpeed;
                    adjustedZ += slopeUnitZ * thermalSign * thermalSpeed;
                    updraftMps += Math.max(0.0f, thermalSign) * thermalSpeed * 0.35f;
                }
            }

            float averageNeighborHeight = (west + east + north + south) * 0.25f;
            float ridgeProminence = Math.max(0.0f, current - averageNeighborHeight);
            float ridgeWeight = Mth.clamp(
                ridgeProminence / L1_TERRAIN_PROMINENCE_REFERENCE_BLOCKS,
                0.0f,
                1.0f
            );
            if (ridgeWeight > 1.0e-3f) {
                float windSpeedWeight = Mth.clamp(adjustedMagnitude / 4.0f, 0.0f, 1.0f);
                float ridgeSpeedup = L1_RIDGE_SPEEDUP_MPS * ridgeWeight * (0.35f + 0.65f * windSpeedWeight);
                ridgeSpeedupX = windUnitX * ridgeSpeedup;
                ridgeSpeedupZ = windUnitZ * ridgeSpeedup;
                adjustedX += ridgeSpeedupX;
                adjustedZ += ridgeSpeedupZ;
                updraftMps += L1_RIDGE_UPDRAFT_MPS * ridgeWeight * windSpeedWeight * 0.70f;
                turbulence += ridgeWeight * 0.25f;
            }

            float eastWestWalls = Math.min(Math.max(0.0f, east - current), Math.max(0.0f, west - current));
            float northSouthWalls = Math.min(Math.max(0.0f, north - current), Math.max(0.0f, south - current));
            float valleyWallHeight = Math.max(eastWestWalls, northSouthWalls);
            float valleyContrast = Math.abs(eastWestWalls - northSouthWalls);
            float valleyWeight = Mth.clamp(
                (valleyWallHeight - 8.0f) / L1_VALLEY_WALL_REFERENCE_BLOCKS,
                0.0f,
                1.0f
            ) * Mth.clamp(valleyContrast / L1_VALLEY_WALL_REFERENCE_BLOCKS, 0.0f, 1.0f);
            if (valleyWeight > 1.0e-3f) {
                float axisX = northSouthWalls > eastWestWalls ? 1.0f : 0.0f;
                float axisZ = eastWestWalls >= northSouthWalls ? 1.0f : 0.0f;
                float axisSign = adjustedX * axisX + adjustedZ * axisZ >= 0.0f ? 1.0f : -1.0f;
                axisX *= axisSign;
                axisZ *= axisSign;
                float targetSpeed = windSpeed(adjustedX, adjustedZ) + L1_VALLEY_SPEEDUP_MPS * valleyWeight;
                float targetX = axisX * targetSpeed;
                float targetZ = axisZ * targetSpeed;
                float blend = valleyWeight * L1_VALLEY_CHANNEL_BLEND;
                valleyChannelX = (targetX - adjustedX) * blend;
                valleyChannelZ = (targetZ - adjustedZ) * blend;
                adjustedX += valleyChannelX;
                adjustedZ += valleyChannelZ;
                turbulence += valleyWeight * 0.18f;
            }
        }

        float finalMagnitude = windSpeed(adjustedX, adjustedZ);
        float maxAdjustedMagnitude = Math.max(
            windMagnitude + L1_THERMAL_SLOPE_WIND_MPS + L1_COAST_BREEZE_DAY_MPS + L1_RIDGE_SPEEDUP_MPS,
            windMagnitude * 1.60f + 1.25f
        );
        if (finalMagnitude > maxAdjustedMagnitude && finalMagnitude > 1.0e-3f) {
            float scale = maxAdjustedMagnitude / finalMagnitude;
            adjustedX *= scale;
            adjustedZ *= scale;
        }
        return new TerrainWindContribution(
            adjustedX,
            adjustedZ,
            coastBreezeX,
            coastBreezeZ,
            ridgeSpeedupX,
            ridgeSpeedupZ,
            valleyChannelX,
            valleyChannelZ,
            updraftMps,
            leeShelter,
            Mth.clamp(turbulence, 0.0f, 1.0f)
        );
    }

    private float terrainHeightAtCell(
        ServerLevel world,
        SeedTerrainProvider provider,
        int cellX,
        int cellZ,
        Map<Long, Float> terrainHeightCache
    ) {
        long key = pack(cellX, cellZ);
        Float cachedHeight = terrainHeightCache.get(key);
        if (cachedHeight != null && Float.isFinite(cachedHeight)) {
            return cachedHeight;
        }
        CellColumnState cell = cells.get(key);
        if (cell != null && cell.staticInitialized && Float.isFinite(cell.terrainHeightBlocks)) {
            terrainHeightCache.put(key, cell.terrainHeightBlocks);
            return cell.terrainHeightBlocks;
        }
        if (provider == null) {
            float seaLevel = world.getSeaLevel();
            terrainHeightCache.put(key, seaLevel);
            return seaLevel;
        }
        SeedTerrainProvider.TerrainSample terrain = provider.sample(
            world,
            cellCenterBlock(cellX),
            cellCenterBlock(cellZ)
        );
        float height = finiteOrDefault(terrain.terrainHeightBlocks(), world.getSeaLevel());
        terrainHeightCache.put(key, height);
        return height;
    }

    private float waterPresenceAtCell(
        ServerLevel world,
        SeedTerrainProvider provider,
        int cellX,
        int cellZ,
        Map<Long, Byte> surfaceClassCache
    ) {
        return surfaceClassAtCell(world, provider, cellX, cellZ, surfaceClassCache)
            == HashedSeedTerrainProvider.SURFACE_CLASS_WATER
            ? 1.0f
            : 0.0f;
    }

    private byte surfaceClassAtCell(
        ServerLevel world,
        SeedTerrainProvider provider,
        int cellX,
        int cellZ,
        Map<Long, Byte> surfaceClassCache
    ) {
        long key = pack(cellX, cellZ);
        Byte cachedClass = surfaceClassCache.get(key);
        if (cachedClass != null) {
            return cachedClass;
        }
        CellColumnState cell = cells.get(key);
        if (cell != null && cell.staticInitialized) {
            surfaceClassCache.put(key, cell.surfaceClass);
            return cell.surfaceClass;
        }
        if (provider == null) {
            surfaceClassCache.put(key, HashedSeedTerrainProvider.SURFACE_CLASS_PLAINS);
            return HashedSeedTerrainProvider.SURFACE_CLASS_PLAINS;
        }
        SeedTerrainProvider.TerrainSample terrain = provider.sample(
            world,
            cellCenterBlock(cellX),
            cellCenterBlock(cellZ)
        );
        byte surfaceClass = terrain.surfaceClass();
        surfaceClassCache.put(key, surfaceClass);
        return surfaceClass;
    }

    private float daytimeHeatingFactor(ServerLevel world) {
        long dayTime = Math.floorMod(world.getDayTime(), 24000L);
        float angle = ((dayTime - 6000.0f) / 24000.0f) * TWO_PI;
        float sun = Mth.cos(angle);
        return Mth.clamp((sun + 0.10f) / 1.10f, 0.0f, 1.0f);
    }

    private float nighttimeCoolingFactor(ServerLevel world) {
        long dayTime = Math.floorMod(world.getDayTime(), 24000L);
        float angle = ((dayTime - 6000.0f) / 24000.0f) * TWO_PI;
        float sun = Mth.cos(angle);
        return Mth.clamp((-sun - 0.20f) / 0.80f, 0.0f, 1.0f);
    }

    private float coastalWeatherDamping(ServerLevel world) {
        float rain = world.getRainLevel(1.0f);
        float thunder = world.getThunderLevel(1.0f);
        return Mth.clamp(1.0f - rain * 0.45f - thunder * 0.25f, 0.35f, 1.0f);
    }

    private boolean stepNative(float deltaSeconds) {
        if (!nativeBridge.isLoaded()) {
            return false;
        }
        int gridWidth = radiusCells * 2 + 1;
        if (nativeContextKey == 0L) {
            nativeContextKey = nativeBridge.createContext(
                gridWidth,
                activeLayers,
                gridWidth,
                cellSizeBlocks,
                stepSeconds,
                DEFAULT_MOLECULAR_NU_M2_S,
                DEFAULT_PRANDTL_AIR,
                DEFAULT_TURBULENT_PRANDTL
            );
            if (nativeContextKey == 0L) {
                return false;
            }
        }
        boolean ok = nativeBridge.stepContext(
            nativeContextKey,
            gridWidth,
            activeLayers,
            gridWidth,
            cellSizeBlocks,
            stepSeconds,
            DEFAULT_MOLECULAR_NU_M2_S,
            DEFAULT_PRANDTL_AIR,
            DEFAULT_TURBULENT_PRANDTL,
            forcingBuffer,
            stateBuffer
        );
        if (!ok) {
            releaseNativeContext();
            return false;
        }

        for (int cx = centerCellX - radiusCells; cx <= centerCellX + radiusCells; cx++) {
            for (int cz = centerCellZ - radiusCells; cz <= centerCellZ + radiusCells; cz++) {
                CellColumnState cell = cells.get(pack(cx, cz));
                if (cell == null) {
                    continue;
                }
                cell.ensureLayers(activeLayers);
                for (int layer = 0; layer < activeLayers; layer++) {
                    int stateBase = forcingIndex(cx, layer, cz, gridWidth) * NATIVE_STATE_CHANNELS;
                    int forcingBase = forcingIndex(cx, layer, cz, gridWidth) * NATIVE_FORCING_CHANNELS;
                    boolean terrainSolid = forcingBuffer[forcingBase + CH_SOLID_MASK] > 0.5f;
                    cell.ambientAirTemperatureKelvin[layer] = finiteOrDefault(
                        stateBuffer[stateBase + OUT_AMBIENT],
                        finiteOrDefault(forcingBuffer[forcingBase + CH_AMBIENT_TARGET], cell.ambientAirTemperatureKelvin[layer])
                    );
                    cell.deepGroundTemperatureKelvin[layer] = finiteOrDefault(
                        stateBuffer[stateBase + OUT_DEEP_GROUND],
                        finiteOrDefault(forcingBuffer[forcingBase + CH_DEEP_GROUND_TARGET], cell.deepGroundTemperatureKelvin[layer])
                    );
                    cell.surfaceTemperatureKelvin[layer] = finiteOrDefault(
                        stateBuffer[stateBase + OUT_SURFACE],
                        finiteOrDefault(forcingBuffer[forcingBase + CH_SURFACE_TARGET], cell.surfaceTemperatureKelvin[layer])
                    );
                    cell.windX[layer] = terrainSolid
                        ? 0.0f
                        : finiteOrDefault(
                            stateBuffer[stateBase + OUT_WIND_X],
                            finiteOrDefault(forcingBuffer[forcingBase + CH_BACKGROUND_WIND_X], cell.windX[layer])
                        );
                    cell.windY[layer] = terrainSolid
                        ? 0.0f
                        : finiteOrDefault(
                            stateBuffer[stateBase + OUT_WIND_Y],
                            cell.windY[layer]
                        ) + cell.terrainUpdraft[layer];
                    cell.windZ[layer] = terrainSolid
                        ? 0.0f
                        : finiteOrDefault(
                            stateBuffer[stateBase + OUT_WIND_Z],
                            finiteOrDefault(forcingBuffer[forcingBase + CH_BACKGROUND_WIND_Z], cell.windZ[layer])
                        );
                    cell.humidity[layer] = Mth.clamp(
                        finiteOrDefault(forcingBuffer[forcingBase + CH_HUMIDITY], cell.humidity[layer]),
                        0.0f,
                        1.0f
                    );
                }
            }
        }
        return true;
    }

    private void refreshFallback(float deltaSeconds) {
        for (int cx = centerCellX - radiusCells; cx <= centerCellX + radiusCells; cx++) {
            for (int cz = centerCellZ - radiusCells; cz <= centerCellZ + radiusCells; cz++) {
                CellColumnState cell = cells.get(pack(cx, cz));
                if (cell == null) {
                    continue;
                }
                cell.ensureLayers(activeLayers);
                int gridWidth = radiusCells * 2 + 1;
                for (int layer = 0; layer < activeLayers; layer++) {
                    int base = forcingIndex(cx, layer, cz, gridWidth) * NATIVE_FORCING_CHANNELS;
                    float ambientTarget = forcingBuffer[base + CH_AMBIENT_TARGET] + forcingBuffer[base + CH_NESTED_AMBIENT_DELTA];
                    float surfaceTarget = forcingBuffer[base + CH_SURFACE_TARGET] + forcingBuffer[base + CH_NESTED_SURFACE_DELTA];
                    float windTargetX = forcingBuffer[base + CH_BACKGROUND_WIND_X] + forcingBuffer[base + CH_NESTED_WIND_X_DELTA];
                    float windTargetY = forcingBuffer[base + CH_NESTED_UPDRAFT]
                        + forcingBuffer[base + CH_TORNADO_UPDRAFT]
                        + cell.terrainUpdraft[layer];
                    float windTargetZ = forcingBuffer[base + CH_BACKGROUND_WIND_Z] + forcingBuffer[base + CH_NESTED_WIND_Z_DELTA];
                    boolean terrainSolid = forcingBuffer[base + CH_SOLID_MASK] > 0.5f;
                    cell.ambientAirTemperatureKelvin[layer] = relax(
                        cell.ambientAirTemperatureKelvin[layer],
                        ambientTarget,
                        deltaSeconds
                    );
                    cell.deepGroundTemperatureKelvin[layer] = relax(
                        cell.deepGroundTemperatureKelvin[layer],
                        forcingBuffer[base + CH_DEEP_GROUND_TARGET],
                        deltaSeconds * 0.25f
                    );
                    cell.surfaceTemperatureKelvin[layer] = relax(
                        cell.surfaceTemperatureKelvin[layer],
                        surfaceTarget,
                        deltaSeconds
                    );
                    cell.windX[layer] = terrainSolid ? 0.0f : relax(cell.windX[layer], windTargetX, deltaSeconds * 0.5f);
                    cell.windY[layer] = terrainSolid ? 0.0f : relax(cell.windY[layer], windTargetY, deltaSeconds * 0.5f);
                    cell.windZ[layer] = terrainSolid ? 0.0f : relax(cell.windZ[layer], windTargetZ, deltaSeconds * 0.5f);
                    cell.humidity[layer] = relax(cell.humidity[layer], forcingBuffer[base + CH_HUMIDITY], deltaSeconds * 0.5f);
                }
            }
        }
    }

    private void ensureNativeBuffers(int cellCount) {
        int forcingLength = cellCount * NATIVE_FORCING_CHANNELS;
        int stateLength = cellCount * NATIVE_STATE_CHANNELS;
        if (forcingBuffer.length != forcingLength) {
            forcingBuffer = new float[forcingLength];
        }
        if (stateBuffer.length != stateLength) {
            stateBuffer = new float[stateLength];
        }
    }

    private void releaseNativeContext() {
        if (nativeContextKey != 0L) {
            nativeBridge.releaseContext(nativeContextKey);
            nativeContextKey = 0L;
        }
    }

    private void publishReadState() {
        int gridWidth = radiusCells * 2 + 1;
        int columnCount = gridWidth * gridWidth;
        int stateCount = columnCount * activeLayers;
        boolean[] columnPresent = new boolean[columnCount];
        float[] terrainHeightBlocks = new float[columnCount];
        float[] biomeTemperature = new float[columnCount];
        float[] roughnessLengthMeters = new float[columnCount];
        byte[] surfaceClass = new byte[columnCount];
        float[] ambientAirTemperatureKelvin = new float[stateCount];
        float[] deepGroundTemperatureKelvin = new float[stateCount];
        float[] surfaceTemperatureKelvin = new float[stateCount];
        float[] windX = new float[stateCount];
        float[] windY = new float[stateCount];
        float[] windZ = new float[stateCount];
        float[] humidity = new float[stateCount];
        float[] windShearXPerBlock = new float[stateCount];
        float[] windShearZPerBlock = new float[stateCount];
        float[] ablHeightBlocks = new float[stateCount];
        float[] ablHeightAglBlocks = new float[stateCount];
        float[] ablStability = new float[stateCount];
        float[] ablMixingStrength = new float[stateCount];
        float[] ablProfileBlend = new float[stateCount];
        int presentColumns = 0;

        for (int cx = centerCellX - radiusCells; cx <= centerCellX + radiusCells; cx++) {
            for (int cz = centerCellZ - radiusCells; cz <= centerCellZ + radiusCells; cz++) {
                CellColumnState cell = cells.get(pack(cx, cz));
                if (cell == null) {
                    continue;
                }
                int column = readColumnIndex(cx, cz, centerCellX, centerCellZ, radiusCells, gridWidth);
                if (column < 0) {
                    continue;
                }
                columnPresent[column] = true;
                presentColumns++;
                terrainHeightBlocks[column] = cell.terrainHeightBlocks;
                biomeTemperature[column] = cell.biomeTemperature;
                roughnessLengthMeters[column] = cell.roughnessLengthMeters;
                surfaceClass[column] = cell.surfaceClass;
                int layerCount = Math.min(activeLayers, cell.layerCount());
                for (int layer = 0; layer < layerCount; layer++) {
                    int state = column * activeLayers + layer;
                    ambientAirTemperatureKelvin[state] = cell.ambientAirTemperatureKelvin[layer];
                    deepGroundTemperatureKelvin[state] = cell.deepGroundTemperatureKelvin[layer];
                    surfaceTemperatureKelvin[state] = cell.surfaceTemperatureKelvin[layer];
                    windX[state] = cell.windX[layer];
                    windY[state] = cell.windY[layer];
                    windZ[state] = cell.windZ[layer];
                    humidity[state] = cell.humidity[layer];
                    windShearXPerBlock[state] = cell.windShearXPerBlock[layer];
                    windShearZPerBlock[state] = cell.windShearZPerBlock[layer];
                    ablHeightBlocks[state] = cell.ablHeightBlocks[layer];
                    ablHeightAglBlocks[state] = cell.ablHeightAglBlocks[layer];
                    ablStability[state] = cell.ablStability[layer];
                    ablMixingStrength[state] = cell.ablMixingStrength[layer];
                    ablProfileBlend[state] = cell.ablProfileBlend[layer];
                }
            }
        }

        readState = new ReadState(
            gridWidth,
            activeLayers,
            cellSizeBlocks,
            layerHeightBlocks,
            radiusCells,
            centerCellX,
            centerCellZ,
            verticalBaseY,
            presentColumns,
            columnPresent,
            terrainHeightBlocks,
            biomeTemperature,
            roughnessLengthMeters,
            surfaceClass,
            ambientAirTemperatureKelvin,
            deepGroundTemperatureKelvin,
            surfaceTemperatureKelvin,
            windX,
            windY,
            windZ,
            humidity,
            windShearXPerBlock,
            windShearZPerBlock,
            ablHeightBlocks,
            ablHeightAglBlocks,
            ablStability,
            ablMixingStrength,
            ablProfileBlend
        );
    }

    private static int readColumnIndex(
        int cellX,
        int cellZ,
        int centerCellX,
        int centerCellZ,
        int radiusCells,
        int gridWidth
    ) {
        int localX = cellX - (centerCellX - radiusCells);
        int localZ = cellZ - (centerCellZ - radiusCells);
        if (localX < 0 || localX >= gridWidth || localZ < 0 || localZ >= gridWidth) {
            return -1;
        }
        return localX * gridWidth + localZ;
    }

    private int computeActiveLayers() {
        return Math.max(1, maxLayers);
    }

    private int layerCenterBlockY(int layer) {
        return verticalBaseY + layer * layerHeightBlocks + layerHeightBlocks / 2;
    }

    private int layerIndexForY(int y) {
        if (activeLayers <= 1) {
            return 0;
        }
        return Mth.clamp(Mth.floor((y - verticalBaseY) / (float) layerHeightBlocks), 0, activeLayers - 1);
    }

    private int forcingIndex(int cellX, int layer, int cellZ, int gridWidth) {
        int localX = cellX - (centerCellX - radiusCells);
        int localZ = cellZ - (centerCellZ - radiusCells);
        return (localX * activeLayers + layer) * gridWidth + localZ;
    }

    private CellColumnState columnStateAtClamped(int cellX, int cellZ) {
        int clampedX = Mth.clamp(cellX, centerCellX - radiusCells, centerCellX + radiusCells);
        int clampedZ = Mth.clamp(cellZ, centerCellZ - radiusCells, centerCellZ + radiusCells);
        return cells.get(pack(clampedX, clampedZ));
    }

    private float bilerp(float v00, float v10, float v01, float v11, float tx, float tz) {
        float vx0 = Mth.lerp(tx, v00, v10);
        float vx1 = Mth.lerp(tx, v01, v11);
        return Mth.lerp(tz, vx0, vx1);
    }

    private float humidityFluxX(int cellX, int cellZ, int layer) {
        CellColumnState state = cells.get(pack(cellX, cellZ));
        if (state == null || state.layerCount() <= 0) {
            return 0.0f;
        }
        int clampedLayer = Mth.clamp(layer, 0, state.layerCount() - 1);
        float humidity = state.humidity[clampedLayer];
        float wind = state.windX[clampedLayer];
        if (!Float.isFinite(humidity) || !Float.isFinite(wind)) {
            return 0.0f;
        }
        return Mth.clamp(humidity, 0.0f, 1.0f) * wind;
    }

    private float humidityFluxZ(int cellX, int cellZ, int layer) {
        CellColumnState state = cells.get(pack(cellX, cellZ));
        if (state == null || state.layerCount() <= 0) {
            return 0.0f;
        }
        int clampedLayer = Mth.clamp(layer, 0, state.layerCount() - 1);
        float humidity = state.humidity[clampedLayer];
        float wind = state.windZ[clampedLayer];
        if (!Float.isFinite(humidity) || !Float.isFinite(wind)) {
            return 0.0f;
        }
        return Mth.clamp(humidity, 0.0f, 1.0f) * wind;
    }

    private int snapshotIndex(int localX, int layer, int localZ, int gridWidth) {
        return (localX * activeLayers + layer) * gridWidth + localZ;
    }

    private void computeDiagnostics(
        int gridWidth,
        int layers,
        float[] ambientAirTemperatureKelvin,
        float[] deepGroundTemperatureKelvin,
        float[] surfaceTemperatureKelvin,
        float[] windX,
        float[] windZ,
        float[] humidity,
        float[] convectiveHeating,
        float[] convectiveMoistening,
        float[] convectiveInflowX,
        float[] convectiveInflowZ,
        float[] tornadoHeating,
        float[] tornadoMoistening,
        float[] tornadoWindX,
        float[] tornadoWindZ,
        float[] tornadoUpdraft,
        float[] instabilityProxy,
        float[] lowLevelShear,
        float[] moistureConvergence,
        float[] liftProxy
    ) {
        float horizontalScaleMeters = Math.max(1.0f, cellSizeBlocks);
        for (int localX = 0; localX < gridWidth; localX++) {
            int leftX = Math.max(0, localX - 1);
            int rightX = Math.min(gridWidth - 1, localX + 1);
            for (int localZ = 0; localZ < gridWidth; localZ++) {
                int prevZ = Math.max(0, localZ - 1);
                int nextZ = Math.min(gridWidth - 1, localZ + 1);
                int surfaceIndex = snapshotIndex(localX, 0, localZ, gridWidth);
                float surfaceReferenceKelvin = surfaceTemperatureKelvin[surfaceIndex];
                float deepGroundReferenceKelvin = deepGroundTemperatureKelvin[surfaceIndex];
                float surfaceAmbientKelvin = ambientAirTemperatureKelvin[surfaceIndex];
                float surfaceWindX = windX[surfaceIndex];
                float surfaceWindZ = windZ[surfaceIndex];
                for (int layer = 0; layer < layers; layer++) {
                    int stateIndex = snapshotIndex(localX, layer, localZ, gridWidth);
                    float ambientLayer = ambientAirTemperatureKelvin[stateIndex];
                    float windLayerX = windX[stateIndex];
                    float windLayerZ = windZ[stateIndex];
                    float humidityLayer = humidity[stateIndex];
                    if (!Float.isFinite(surfaceReferenceKelvin)
                        || !Float.isFinite(deepGroundReferenceKelvin)
                        || !Float.isFinite(surfaceAmbientKelvin)
                        || !Float.isFinite(surfaceWindX)
                        || !Float.isFinite(surfaceWindZ)
                        || !Float.isFinite(ambientLayer)
                        || !Float.isFinite(windLayerX)
                        || !Float.isFinite(windLayerZ)
                        || !Float.isFinite(humidityLayer)) {
                        instabilityProxy[stateIndex] = 0.0f;
                        lowLevelShear[stateIndex] = 0.0f;
                        moistureConvergence[stateIndex] = 0.0f;
                        liftProxy[stateIndex] = 0.0f;
                        continue;
                    }
                    float q = Mth.clamp(humidityLayer, 0.0f, 1.0f);
                    float instability = computeInstabilityProxy(
                        surfaceReferenceKelvin,
                        deepGroundReferenceKelvin,
                        surfaceAmbientKelvin,
                        ambientLayer,
                        q,
                        convectiveHeating[stateIndex] + tornadoHeating[stateIndex],
                        convectiveMoistening[stateIndex] + tornadoMoistening[stateIndex],
                        layer
                    );
                    instabilityProxy[stateIndex] = instability;

                    float du = windLayerX - surfaceWindX;
                    float dv = windLayerZ - surfaceWindZ;
                    lowLevelShear[stateIndex] = Mth.sqrt(du * du + dv * dv);

                    float leftHumidity = humidity[snapshotIndex(leftX, layer, localZ, gridWidth)];
                    float rightHumidity = humidity[snapshotIndex(rightX, layer, localZ, gridWidth)];
                    float southHumidity = humidity[snapshotIndex(localX, layer, prevZ, gridWidth)];
                    float northHumidity = humidity[snapshotIndex(localX, layer, nextZ, gridWidth)];
                    float leftWind = windX[snapshotIndex(leftX, layer, localZ, gridWidth)];
                    float rightWind = windX[snapshotIndex(rightX, layer, localZ, gridWidth)];
                    float southWind = windZ[snapshotIndex(localX, layer, prevZ, gridWidth)];
                    float northWind = windZ[snapshotIndex(localX, layer, nextZ, gridWidth)];
                    float leftFlux = (Float.isFinite(leftHumidity) && Float.isFinite(leftWind))
                        ? Mth.clamp(leftHumidity, 0.0f, 1.0f) * leftWind
                        : 0.0f;
                    float rightFlux = (Float.isFinite(rightHumidity) && Float.isFinite(rightWind))
                        ? Mth.clamp(rightHumidity, 0.0f, 1.0f) * rightWind
                        : 0.0f;
                    float southFlux = (Float.isFinite(southHumidity) && Float.isFinite(southWind))
                        ? Mth.clamp(southHumidity, 0.0f, 1.0f) * southWind
                        : 0.0f;
                    float northFlux = (Float.isFinite(northHumidity) && Float.isFinite(northWind))
                        ? Mth.clamp(northHumidity, 0.0f, 1.0f) * northWind
                        : 0.0f;
                    float dQudx = (rightFlux - leftFlux) / (Math.max(1, rightX - leftX) * horizontalScaleMeters);
                    float dQwdz = (northFlux - southFlux) / (Math.max(1, nextZ - prevZ) * horizontalScaleMeters);
                    float convergence = -(dQudx + dQwdz);
                    moistureConvergence[stateIndex] = convergence;
                    liftProxy[stateIndex] = computeLiftProxy(
                        instability,
                        q,
                        Math.max(0.0f, convergence),
                        lowLevelShear[stateIndex],
                        convectiveHeating[stateIndex] + tornadoHeating[stateIndex] + tornadoUpdraft[stateIndex] * 0.35f,
                        convectiveMoistening[stateIndex] + tornadoMoistening[stateIndex],
                        convectiveInflowX[stateIndex] + tornadoWindX[stateIndex],
                        convectiveInflowZ[stateIndex] + tornadoWindZ[stateIndex],
                        layer,
                        layers,
                        horizontalScaleMeters
                    );
                }
            }
        }
    }

    private float computeInstabilityProxy(
        float surfaceReferenceKelvin,
        float deepGroundReferenceKelvin,
        float surfaceAmbientKelvin,
        float ambientKelvin,
        float humidity,
        float convectiveHeating,
        float convectiveMoistening,
        int layer
    ) {
        float q = Mth.clamp(humidity, 0.0f, 1.0f);
        float moistening = Mth.clamp(convectiveMoistening, 0.0f, 1.0f);
        float surfaceExcess = Math.max(0.0f, surfaceReferenceKelvin - ambientKelvin);
        float deepExcess = Math.max(0.0f, deepGroundReferenceKelvin - ambientKelvin) * 0.55f;
        float boundaryWarmth = Math.max(0.0f, surfaceReferenceKelvin - surfaceAmbientKelvin) * 0.35f;
        float expectedLapse = layer * layerHeightBlocks * AMBIENT_LAPSE_RATE_K_PER_BLOCK;
        float actualLapse = Math.max(0.0f, surfaceAmbientKelvin - ambientKelvin);
        float lapseExcess = Math.max(0.0f, actualLapse - expectedLapse * 0.5f) * 0.75f;
        float convectiveBoost = Math.max(0.0f, convectiveHeating) * (0.70f + 0.30f * moistening) * 1.40f;
        float moistureFactor = 0.30f + 0.70f * Math.max(q, moistening);
        return (surfaceExcess + deepExcess + boundaryWarmth + lapseExcess + convectiveBoost) * moistureFactor;
    }

    private float computeLiftProxy(
        float instability,
        float humidity,
        float positiveConvergence,
        float shear,
        float convectiveHeating,
        float convectiveMoistening,
        float convectiveInflowX,
        float convectiveInflowZ,
        int layer,
        int layers,
        float horizontalScaleMeters
    ) {
        float moistureNorm = Math.min(1.0f, Math.max(Mth.clamp(humidity, 0.0f, 1.0f), Mth.clamp(convectiveMoistening, 0.0f, 1.0f)));
        float instabilityNorm = Math.min(1.5f, instability / 4.0f);
        float convergenceNorm = Math.min(1.5f, positiveConvergence * horizontalScaleMeters * 8.0f);
        float shearNorm = Math.min(1.5f, shear / 6.0f);
        float forcedInflowNorm = Math.min(1.5f, Mth.sqrt(convectiveInflowX * convectiveInflowX + convectiveInflowZ * convectiveInflowZ) / 1.5f);
        float forcedLiftNorm = Math.min(1.5f, Math.max(0.0f, convectiveHeating) * 0.35f + forcedInflowNorm);
        float layerWeight = 1.0f - 0.65f * (layer / (float) Math.max(1, layers - 1));
        return instabilityNorm
            * (0.40f + 0.60f * moistureNorm)
            * (0.25f + Math.max(convergenceNorm, forcedLiftNorm))
            * (0.80f + 0.20f * shearNorm)
            * layerWeight;
    }

    private float forcingChannel(int cellX, int layer, int cellZ, int gridWidth, int channel) {
        int base = forcingIndex(cellX, layer, cellZ, gridWidth) * NATIVE_FORCING_CHANNELS + channel;
        if (base < 0 || base >= forcingBuffer.length) {
            return 0.0f;
        }
        float value = forcingBuffer[base];
        return Float.isFinite(value) ? value : 0.0f;
    }

    private TornadoEnvironment sampleEnvironment(int centerX, int centerZ, int sampleRadius) {
        int sampledStates = 0;
        float meanInstability = 0.0f;
        float maxInstability = 0.0f;
        float meanShear = 0.0f;
        float maxShear = 0.0f;
        float meanHumidity = 0.0f;
        float meanPositiveConvergence = 0.0f;
        float maxPositiveConvergence = 0.0f;
        float meanLift = 0.0f;
        float maxLift = 0.0f;
        float horizontalScaleMeters = Math.max(1.0f, cellSizeBlocks);
        int sampledLayers = Math.max(1, Math.min(activeLayers, 3));
        int gridWidth = radiusCells * 2 + 1;

        for (int cx = centerX - sampleRadius; cx <= centerX + sampleRadius; cx++) {
            for (int cz = centerZ - sampleRadius; cz <= centerZ + sampleRadius; cz++) {
                CellColumnState column = cells.get(pack(cx, cz));
                if (column == null || column.layerCount() <= 0) {
                    continue;
                }
                float surfaceReferenceKelvin = column.surfaceTemperatureKelvin[0];
                float deepGroundReferenceKelvin = column.deepGroundTemperatureKelvin[0];
                float surfaceAmbientKelvin = column.ambientAirTemperatureKelvin[0];
                float surfaceWindX = column.windX[0];
                float surfaceWindZ = column.windZ[0];
                if (!Float.isFinite(surfaceReferenceKelvin)
                    || !Float.isFinite(deepGroundReferenceKelvin)
                    || !Float.isFinite(surfaceAmbientKelvin)
                    || !Float.isFinite(surfaceWindX)
                    || !Float.isFinite(surfaceWindZ)) {
                    continue;
                }
                for (int layer = 0; layer < sampledLayers; layer++) {
                    float ambientLayer = column.ambientAirTemperatureKelvin[layer];
                    float windLayerX = column.windX[layer];
                    float windLayerZ = column.windZ[layer];
                    float humidityLayer = column.humidity[layer];
                    if (!Float.isFinite(ambientLayer)
                        || !Float.isFinite(windLayerX)
                        || !Float.isFinite(windLayerZ)
                        || !Float.isFinite(humidityLayer)) {
                        continue;
                    }
                    float q = Mth.clamp(humidityLayer, 0.0f, 1.0f);
                    float convectiveHeating = forcingChannel(cx, layer, cz, gridWidth, CH_CONVECTIVE_HEATING);
                    float convectiveMoistening = forcingChannel(cx, layer, cz, gridWidth, CH_CONVECTIVE_MOISTENING);
                    float convectiveInflowX = forcingChannel(cx, layer, cz, gridWidth, CH_CONVECTIVE_INFLOW_X);
                    float convectiveInflowZ = forcingChannel(cx, layer, cz, gridWidth, CH_CONVECTIVE_INFLOW_Z);
                    float tornadoHeating = forcingChannel(cx, layer, cz, gridWidth, CH_TORNADO_HEATING);
                    float tornadoMoistening = forcingChannel(cx, layer, cz, gridWidth, CH_TORNADO_MOISTENING);
                    float tornadoWindX = forcingChannel(cx, layer, cz, gridWidth, CH_TORNADO_WIND_X);
                    float tornadoWindZ = forcingChannel(cx, layer, cz, gridWidth, CH_TORNADO_WIND_Z);
                    float tornadoUpdraft = forcingChannel(cx, layer, cz, gridWidth, CH_TORNADO_UPDRAFT)
                        + forcingChannel(cx, layer, cz, gridWidth, CH_NESTED_UPDRAFT);
                    float instability = computeInstabilityProxy(
                        surfaceReferenceKelvin,
                        deepGroundReferenceKelvin,
                        surfaceAmbientKelvin,
                        ambientLayer,
                        q,
                        convectiveHeating + tornadoHeating,
                        convectiveMoistening + tornadoMoistening,
                        layer
                    );
                    float du = windLayerX - surfaceWindX;
                    float dv = windLayerZ - surfaceWindZ;
                    float shear = Mth.sqrt(du * du + dv * dv);

                    float leftFlux = humidityFluxX(cx - 1, cz, layer);
                    float rightFlux = humidityFluxX(cx + 1, cz, layer);
                    float southFlux = humidityFluxZ(cx, cz - 1, layer);
                    float northFlux = humidityFluxZ(cx, cz + 1, layer);
                    float convergence = -((rightFlux - leftFlux) / (2.0f * horizontalScaleMeters)
                        + (northFlux - southFlux) / (2.0f * horizontalScaleMeters));
                    float positiveConvergence = Math.max(0.0f, convergence);
                    float lift = computeLiftProxy(
                        instability,
                        q,
                        positiveConvergence,
                        shear,
                        convectiveHeating + tornadoHeating + tornadoUpdraft * 0.35f,
                        convectiveMoistening + tornadoMoistening,
                        convectiveInflowX + tornadoWindX,
                        convectiveInflowZ + tornadoWindZ,
                        layer,
                        sampledLayers,
                        horizontalScaleMeters
                    );
                    if (!Float.isFinite(instability)
                        || !Float.isFinite(shear)
                        || !Float.isFinite(positiveConvergence)
                        || !Float.isFinite(lift)) {
                        continue;
                    }

                    meanInstability += instability;
                    maxInstability = Math.max(maxInstability, instability);
                    meanShear += shear;
                    maxShear = Math.max(maxShear, shear);
                    meanHumidity += q;
                    meanPositiveConvergence += positiveConvergence;
                    maxPositiveConvergence = Math.max(maxPositiveConvergence, positiveConvergence);
                    meanLift += lift;
                    maxLift = Math.max(maxLift, lift);
                    sampledStates++;
                }
            }
        }

        if (sampledStates <= 0) {
            return TornadoEnvironment.EMPTY;
        }
        float inverseCount = 1.0f / sampledStates;
        return new TornadoEnvironment(
            meanInstability * inverseCount,
            maxInstability,
            meanShear * inverseCount,
            maxShear,
            meanHumidity * inverseCount,
            meanPositiveConvergence * inverseCount,
            maxPositiveConvergence,
            meanLift * inverseCount,
            maxLift,
            sampledStates
        );
    }

    private float relax(float current, float target, float deltaSeconds) {
        float safeTarget = finiteOrDefault(target, finiteOrDefault(current, 0.0f));
        if (!Float.isFinite(current) || current == 0.0f) {
            return safeTarget;
        }
        float alpha = Mth.clamp(deltaSeconds * SURFACE_RELAXATION_PER_SECOND, 0.0f, 1.0f);
        return Mth.lerp(alpha, current, safeTarget);
    }

    private float finiteOrDefault(float value, float fallback) {
        if (Float.isFinite(value)) {
            return value;
        }
        return Float.isFinite(fallback) ? fallback : 0.0f;
    }

    private float windSpeed(float windX, float windZ) {
        if (!Float.isFinite(windX) || !Float.isFinite(windZ)) {
            return 0.0f;
        }
        return Mth.sqrt(windX * windX + windZ * windZ);
    }

    private WindVector clampWindMagnitude(float windX, float windZ, float maxMagnitude) {
        float safeX = finiteOrDefault(windX, 0.0f);
        float safeZ = finiteOrDefault(windZ, 0.0f);
        float speed = windSpeed(safeX, safeZ);
        if (!(speed > maxMagnitude) || speed <= 1.0e-5f) {
            return new WindVector(safeX, safeZ);
        }
        float scale = maxMagnitude / speed;
        return new WindVector(safeX * scale, safeZ * scale);
    }

    private float ablProfileBlend(float heightAglBlocks, float ablHeightBlocks, float mixingStrength, float roughnessMeters) {
        if (!(heightAglBlocks > 0.0f)) {
            return 0.0f;
        }
        float safeAblHeight = Math.max(24.0f, finiteOrDefault(ablHeightBlocks, ABL_NEUTRAL_HEIGHT_BLOCKS));
        float safeMixing = Mth.clamp(finiteOrDefault(mixingStrength, 0.35f), 0.08f, 1.0f);
        float safeRoughness = Mth.clamp(
            finiteOrDefault(roughnessMeters, ABL_MIN_ROUGHNESS_METERS),
            ABL_MIN_ROUGHNESS_METERS,
            ABL_MAX_ROUGHNESS_METERS
        );
        float transitionHeight = safeAblHeight / (0.45f + 1.65f * safeMixing) + safeRoughness * 18.0f;
        float exponentialBlend = 1.0f - (float) Math.exp(-heightAglBlocks / Math.max(8.0f, transitionHeight));
        float mixedLayerBoost = safeMixing * 0.24f * (float) Math.exp(-heightAglBlocks / Math.max(48.0f, safeAblHeight * 0.45f));
        return Mth.clamp(exponentialBlend + mixedLayerBoost, 0.0f, 1.0f);
    }

    private WindVector rotateWind(float windX, float windZ, float radians) {
        if (!Float.isFinite(windX) || !Float.isFinite(windZ) || !Float.isFinite(radians)) {
            return new WindVector(finiteOrDefault(windX, 0.0f), finiteOrDefault(windZ, 0.0f));
        }
        float cos = Mth.cos(radians);
        float sin = Mth.sin(radians);
        return new WindVector(windX * cos - windZ * sin, windX * sin + windZ * cos);
    }

    private float pseudoCoriolisFactor(int cellZ) {
        float periodCells = 384.0f;
        float wrapped = cellZ % periodCells;
        if (wrapped < 0.0f) {
            wrapped += periodCells;
        }
        return Mth.clamp((wrapped - periodCells * 0.5f) / (periodCells * 0.5f), -1.0f, 1.0f);
    }

    private long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private record WindVector(float x, float z) {
    }

    private record TerrainWindContribution(
        float finalWindX,
        float finalWindZ,
        float coastBreezeX,
        float coastBreezeZ,
        float ridgeSpeedupX,
        float ridgeSpeedupZ,
        float valleyChannelX,
        float valleyChannelZ,
        float updraftMps,
        float leeShelter,
        float turbulence
    ) {
    }

    private int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private int unpackZ(long packed) {
        return (int) packed;
    }

    record NestedFeedbackBin(
        int cellX,
        int layer,
        int cellZ,
        float volumeAverage,
        float densityAverage,
        float momentumXAverage,
        float momentumZAverage,
        float airTemperatureVolumeAverage,
        float surfaceTemperatureVolumeAverage,
        float bottomAreaAverage,
        float bottomMassFluxAverage,
        float topAreaAverage,
        float topMassFluxAverage
    ) {
    }

    private record NestedFeedbackKey(int cellX, int layer, int cellZ) {
    }

    private static final class NestedFeedbackAccumulator {
        private float volumeAverage;
        private float densityAverage;
        private float momentumXAverage;
        private float momentumZAverage;
        private float airTemperatureVolumeAverage;
        private float surfaceTemperatureVolumeAverage;
        private float bottomAreaAverage;
        private float bottomMassFluxAverage;
        private float topAreaAverage;
        private float topMassFluxAverage;

        private void add(NestedFeedbackBin bin) {
            volumeAverage += bin.volumeAverage();
            densityAverage += bin.densityAverage();
            momentumXAverage += bin.momentumXAverage();
            momentumZAverage += bin.momentumZAverage();
            airTemperatureVolumeAverage += bin.airTemperatureVolumeAverage();
            surfaceTemperatureVolumeAverage += bin.surfaceTemperatureVolumeAverage();
            bottomAreaAverage += bin.bottomAreaAverage();
            bottomMassFluxAverage += bin.bottomMassFluxAverage();
            topAreaAverage += bin.topAreaAverage();
            topMassFluxAverage += bin.topMassFluxAverage();
        }
    }

    private record ReadState(
        int gridWidth,
        int activeLayers,
        int cellSizeBlocks,
        int layerHeightBlocks,
        int radiusCells,
        int centerCellX,
        int centerCellZ,
        int verticalBaseY,
        int presentColumns,
        boolean[] columnPresent,
        float[] terrainHeightBlocks,
        float[] biomeTemperature,
        float[] roughnessLengthMeters,
        byte[] surfaceClass,
        float[] ambientAirTemperatureKelvin,
        float[] deepGroundTemperatureKelvin,
        float[] surfaceTemperatureKelvin,
        float[] windX,
        float[] windY,
        float[] windZ,
        float[] humidity,
        float[] windShearXPerBlock,
        float[] windShearZPerBlock,
        float[] ablHeightBlocks,
        float[] ablHeightAglBlocks,
        float[] ablStability,
        float[] ablMixingStrength,
        float[] ablProfileBlend
    ) {
        private static final ReadState EMPTY = new ReadState(
            0,
            0,
            1,
            1,
            0,
            0,
            0,
            0,
            0,
            new boolean[0],
            new float[0],
            new float[0],
            new float[0],
            new byte[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0]
        );

        private boolean empty() {
            return gridWidth <= 0 || activeLayers <= 0 || presentColumns <= 0;
        }

        private int cellCount() {
            return presentColumns * activeLayers;
        }

        private Sample sample(BlockPos pos) {
            if (empty() || pos == null) {
                return null;
            }
            int cellX = Math.floorDiv(pos.getX(), cellSizeBlocks);
            int cellZ = Math.floorDiv(pos.getZ(), cellSizeBlocks);
            int column = columnIndex(cellX, cellZ);
            if (column < 0) {
                return null;
            }
            int layer = activeLayers <= 1
                ? 0
                : Mth.clamp(Mth.floor((pos.getY() - verticalBaseY) / (float) layerHeightBlocks), 0, activeLayers - 1);
            int state = stateIndex(column, layer);
            return new Sample(
                terrainHeightBlocks[column],
                biomeTemperature[column],
                ambientAirTemperatureKelvin[state],
                deepGroundTemperatureKelvin[state],
                surfaceTemperatureKelvin[state],
                roughnessLengthMeters[column],
                windX[state],
                windY[state],
                windZ[state],
                humidity[state],
                windShearXPerBlock[state],
                windShearZPerBlock[state],
                ablHeightBlocks[state],
                ablHeightAglBlocks[state],
                ablStability[state],
                ablMixingStrength[state],
                ablProfileBlend[state],
                surfaceClass[column]
            );
        }

        private int columnIndexAtClamped(int cellX, int cellZ) {
            if (empty()) {
                return -1;
            }
            int clampedX = Mth.clamp(cellX, centerCellX - radiusCells, centerCellX + radiusCells);
            int clampedZ = Mth.clamp(cellZ, centerCellZ - radiusCells, centerCellZ + radiusCells);
            return columnIndex(clampedX, clampedZ);
        }

        private int columnIndex(int cellX, int cellZ) {
            int index = readColumnIndex(cellX, cellZ, centerCellX, centerCellZ, radiusCells, gridWidth);
            if (index < 0 || index >= columnPresent.length || !columnPresent[index]) {
                return -1;
            }
            return index;
        }

        private float windXAt(int column, int layer) {
            return windX[stateIndex(column, layer)];
        }

        private float windYAt(int column, int layer) {
            return windY[stateIndex(column, layer)];
        }

        private float windZAt(int column, int layer) {
            return windZ[stateIndex(column, layer)];
        }

        private float ambientAirTemperatureAt(int column, int layer) {
            return ambientAirTemperatureKelvin[stateIndex(column, layer)];
        }

        private float surfaceTemperatureAt(int column, int layer) {
            return surfaceTemperatureKelvin[stateIndex(column, layer)];
        }

        private int stateIndex(int column, int layer) {
            return column * activeLayers + layer;
        }
    }

    record Sample(
        float terrainHeightBlocks,
        float biomeTemperature,
        float ambientAirTemperatureKelvin,
        float deepGroundTemperatureKelvin,
        float surfaceTemperatureKelvin,
        float roughnessLengthMeters,
        float windX,
        float windY,
        float windZ,
        float humidity,
        float windShearXPerBlock,
        float windShearZPerBlock,
        float ablHeightBlocks,
        float ablHeightAglBlocks,
        float ablStability,
        float ablMixingStrength,
        float ablProfileBlend,
        byte surfaceClass
    ) {
    }

    record Snapshot(
        int gridWidth,
        int activeLayers,
        int cellSizeBlocks,
        int layerHeightBlocks,
        int radiusCells,
        int centerCellX,
        int centerCellZ,
        int verticalBaseY,
        float stepSeconds,
        long lastTickProcessed,
        float[] terrainHeightBlocks,
        float[] biomeTemperature,
        float[] roughnessLengthMeters,
        byte[] surfaceClass,
        float[] ambientAirTemperatureKelvin,
        float[] deepGroundTemperatureKelvin,
        float[] surfaceTemperatureKelvin,
        float[] forcingAmbientTargetKelvin,
        float[] forcingSurfaceTargetKelvin,
        float[] forcingBackgroundWindX,
        float[] forcingBackgroundWindZ,
        float[] forcingSurfaceWindX,
        float[] forcingSurfaceWindZ,
        float[] forcingGeostrophicWindX,
        float[] forcingGeostrophicWindZ,
        float[] forcingWindShearXPerBlock,
        float[] forcingWindShearZPerBlock,
        float[] ablHeightBlocks,
        float[] ablHeightAglBlocks,
        float[] ablStability,
        float[] ablMixingStrength,
        float[] ablProfileBlend,
        float[] terrainCoastBreezeX,
        float[] terrainCoastBreezeZ,
        float[] terrainRidgeWindX,
        float[] terrainRidgeWindZ,
        float[] terrainValleyWindX,
        float[] terrainValleyWindZ,
        float[] terrainLeeShelter,
        float[] terrainUpdraft,
        float[] terrainTurbulence,
        float[] terrainRoughnessDrag,
        float[] forcingNestedAmbientDeltaKelvin,
        float[] forcingNestedSurfaceDeltaKelvin,
        float[] forcingNestedWindXDelta,
        float[] forcingNestedWindZDelta,
        float[] forcingNestedUpdraft,
        float[] terrainSolidMask,
        float[] windX,
        float[] windY,
        float[] windZ,
        float[] humidity,
        float[] instabilityProxy,
        float[] lowLevelShear,
        float[] moistureConvergence,
        float[] liftProxy,
        NestedFeedbackDiagnostics nestedFeedbackDiagnostics
    ) {
    }

    record NestedFeedbackDiagnostics(
        long lastAppliedTick,
        int inputBinCount,
        int acceptedBinCount,
        int appliedCellCount,
        float meanCoverage,
        float maxCoverage,
        float meanWindDelta,
        float maxWindDelta,
        float meanAirDeltaKelvin,
        float maxAirDeltaKelvin,
        float meanSurfaceDeltaKelvin,
        float maxSurfaceDeltaKelvin,
        float meanBottomFluxDensity,
        float meanTopFluxDensity,
        float meanNestedUpdraft,
        float maxAbsNestedUpdraft
    ) {
        private static final NestedFeedbackDiagnostics EMPTY = new NestedFeedbackDiagnostics(
            Long.MIN_VALUE,
            0,
            0,
            0,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f
        );
    }

    record DiagnosticsSummary(
        float meanInstabilityProxy,
        float maxInstabilityProxy,
        float meanLowLevelShear,
        float meanHumidity,
        float meanPositiveMoistureConvergence,
        float maxPositiveMoistureConvergence,
        float meanLiftProxy,
        float maxLiftProxy,
        int sampledStateCount
    ) {
        private static final DiagnosticsSummary EMPTY = new DiagnosticsSummary(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
    }

    record TornadoEnvironment(
        float meanInstabilityProxy,
        float maxInstabilityProxy,
        float meanLowLevelShear,
        float maxLowLevelShear,
        float meanHumidity,
        float meanPositiveMoistureConvergence,
        float maxPositiveMoistureConvergence,
        float meanLiftProxy,
        float maxLiftProxy,
        int sampledStateCount
    ) {
        private static final TornadoEnvironment EMPTY = new TornadoEnvironment(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
    }

    private static final class CellColumnState {
        private boolean staticInitialized;
        private float terrainHeightBlocks;
        private float biomeTemperature;
        private float roughnessLengthMeters;
        private byte surfaceClass;
        private float[] ambientAirTemperatureKelvin;
        private float[] deepGroundTemperatureKelvin;
        private float[] surfaceTemperatureKelvin;
        private float[] windX;
        private float[] windY;
        private float[] windZ;
        private float[] surfaceWindX;
        private float[] surfaceWindZ;
        private float[] geostrophicWindX;
        private float[] geostrophicWindZ;
        private float[] windShearXPerBlock;
        private float[] windShearZPerBlock;
        private float[] ablHeightBlocks;
        private float[] ablHeightAglBlocks;
        private float[] ablStability;
        private float[] ablMixingStrength;
        private float[] ablProfileBlend;
        private float[] terrainCoastBreezeX;
        private float[] terrainCoastBreezeZ;
        private float[] terrainRidgeWindX;
        private float[] terrainRidgeWindZ;
        private float[] terrainValleyWindX;
        private float[] terrainValleyWindZ;
        private float[] terrainLeeShelter;
        private float[] terrainUpdraft;
        private float[] terrainTurbulence;
        private float[] terrainRoughnessDrag;
        private float[] humidity;

        private CellColumnState(int layers) {
            ensureLayers(layers);
        }

        private void ensureLayers(int layers) {
            if (ambientAirTemperatureKelvin == null || ambientAirTemperatureKelvin.length != layers) {
                ambientAirTemperatureKelvin = new float[layers];
                deepGroundTemperatureKelvin = new float[layers];
                surfaceTemperatureKelvin = new float[layers];
                windX = new float[layers];
                windY = new float[layers];
                windZ = new float[layers];
                surfaceWindX = new float[layers];
                surfaceWindZ = new float[layers];
                geostrophicWindX = new float[layers];
                geostrophicWindZ = new float[layers];
                windShearXPerBlock = new float[layers];
                windShearZPerBlock = new float[layers];
                ablHeightBlocks = new float[layers];
                ablHeightAglBlocks = new float[layers];
                ablStability = new float[layers];
                ablMixingStrength = new float[layers];
                ablProfileBlend = new float[layers];
                terrainCoastBreezeX = new float[layers];
                terrainCoastBreezeZ = new float[layers];
                terrainRidgeWindX = new float[layers];
                terrainRidgeWindZ = new float[layers];
                terrainValleyWindX = new float[layers];
                terrainValleyWindZ = new float[layers];
                terrainLeeShelter = new float[layers];
                terrainUpdraft = new float[layers];
                terrainTurbulence = new float[layers];
                terrainRoughnessDrag = new float[layers];
                humidity = new float[layers];
            }
        }

        private int layerCount() {
            return ambientAirTemperatureKelvin == null ? 0 : ambientAirTemperatureKelvin.length;
        }
    }
}
