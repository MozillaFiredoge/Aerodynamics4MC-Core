package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.api.AeroTerrainApi;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class BackgroundMetGrid {
    private static final float BASE_AIR_TEMPERATURE_K = 288.15f;
    private static final float BIOME_TEMPERATURE_SCALE_K = 12.0f;
    private static final float ALTITUDE_LAPSE_RATE_K_PER_BLOCK = 0.0065f;
    private static final float DEEP_GROUND_OFFSET_K = 1.5f;
    private static final float FLOW_RELAXATION_PER_SECOND = 1.0f / 90.0f;
    private static final float PRESSURE_RELAXATION_PER_SECOND = 1.0f / 180.0f;
    private static final float AIR_TEMPERATURE_RELAXATION_PER_SECOND = 1.0f / 1200.0f;
    private static final float HUMIDITY_RELAXATION_PER_SECOND = 1.0f / 900.0f;
    private static final float DEEP_GROUND_RELAXATION_PER_SECOND = 1.0f / 3600.0f;
    private static final float SURFACE_RELAXATION_PER_SECOND = 1.0f / 1800.0f;
    private static final float FLOW_DIFFUSION_BLEND = 0.16f;
    private static final float PRESSURE_DIFFUSION_BLEND = 0.10f;
    private static final float THERMAL_DIFFUSION_BLEND = 0.10f;
    private static final float HUMIDITY_DIFFUSION_BLEND = 0.08f;
    private static final float SOLAR_SURFACE_HEATING_K = 18.0f;
    private static final float CLEAR_SKY_COOLING_K = 8.0f;
    private static final float MAX_DRIVER_WIND_MPS = 14.0f;
    private static final float MAX_DYNAMIC_WIND_MPS = 18.0f;
    private static final float MAX_PRESSURE_ANOMALY_PA = 2400.0f;
    private static final float GEOSTROPHIC_WIND_SCALE_M2_PER_PA_S = 12.0f;
    private static final float GEOSTROPHIC_DIRECT_WIND_BLEND = 0.18f;
    private static final float GEOSTROPHIC_FALLBACK_WIND_BLEND = 0.32f;
    private static final float GEOSTROPHIC_FALLBACK_SPEED_MPS = 0.35f;
    private static final float MIN_CORIOLIS_FACTOR = 0.55f;
    private static final float MAX_HUMIDITY_RESPONSE = 0.20f;
    private static final float EVAPORATION_SURFACE_DELTA_SCALE = 0.01f;
    private static final float BASE_ROUGHNESS_DRAG_PER_SECOND = 0.0025f;
    private static final float ROUGHNESS_DRAG_SCALE_PER_SECOND = 0.010f;
    private static final float MAX_ROUGHNESS_DRAG = 0.22f;
    private static final float TERRAIN_FORM_DRAG_SCALE = 0.65f;
    private static final float TERRAIN_FLOW_DEFLECTION_SCALE = 0.45f;
    private static final float MAX_TERRAIN_WIND_ADJUSTMENT = 0.55f;

    private final int cellSizeBlocks;
    private final int radiusCells;
    private final int updateIntervalTicks;
    private final Map<Long, CellState> cells = new HashMap<>();
    private volatile ReadState readState = ReadState.EMPTY;
    private ServerLevel currentWorld;
    private SeedTerrainProvider currentProvider;
    private WorldScaleDriver currentDriver;
    private int centerCellX;
    private int centerCellZ;
    private long lastRefreshTick = Long.MIN_VALUE;
    private long currentRefreshTick = Long.MIN_VALUE;
    private long terrainRevision = Long.MIN_VALUE;
    private float currentDeltaSeconds = 1.0f;
    private float currentSolarAltitude = 0.0f;
    private float currentClearSky = 1.0f;
    private float currentRainGradient = 0.0f;
    private float currentThunderGradient = 0.0f;

    BackgroundMetGrid(int cellSizeBlocks, int radiusCells, int updateIntervalTicks) {
        this.cellSizeBlocks = cellSizeBlocks;
        this.radiusCells = radiusCells;
        this.updateIntervalTicks = Math.max(1, updateIntervalTicks);
    }

    synchronized void refresh(
            ServerLevel world,
            AeroServerRuntime.WorldEnvironmentSnapshot environmentSnapshot,
            BlockPos focus,
            long tickCounter,
            float dtSeconds,
            SeedTerrainProvider provider,
            WorldScaleDriver driver
    ) {
        currentWorld = world;
        currentProvider = provider;
        currentDriver = driver;
        long nextTerrainRevision = AeroTerrainApi.terrainRevision();
        boolean terrainChanged = terrainRevision != nextTerrainRevision;
        terrainRevision = nextTerrainRevision;
        int nextCenterCellX = Math.floorDiv(focus.getX(), cellSizeBlocks);
        int nextCenterCellZ = Math.floorDiv(focus.getZ(), cellSizeBlocks);
        boolean centerChanged = nextCenterCellX != centerCellX || nextCenterCellZ != centerCellZ;
        centerCellX = nextCenterCellX;
        centerCellZ = nextCenterCellZ;
        boolean updateDue = lastRefreshTick == Long.MIN_VALUE || tickCounter - lastRefreshTick >= updateIntervalTicks;
        if (updateDue) {
            currentDeltaSeconds = lastRefreshTick == Long.MIN_VALUE
                    ? dtSeconds
                    : Math.max(1L, tickCounter - lastRefreshTick) * dtSeconds;
            lastRefreshTick = tickCounter;
            currentRefreshTick = tickCounter;
        } else {
            currentDeltaSeconds = 0.0f;
            currentRefreshTick = lastRefreshTick;
        }

        long timeOfDay = environmentSnapshot == null ? world.getDayTime() : environmentSnapshot.timeOfDay();
        float rainGradient = environmentSnapshot == null ? world.getRainLevel(1.0f) : environmentSnapshot.rainGradient();
        float thunderGradient = environmentSnapshot == null ? world.getThunderLevel(1.0f) : environmentSnapshot.thunderGradient();
        currentRainGradient = rainGradient;
        currentThunderGradient = thunderGradient;
        float dayPhase = (float) Math.floorMod(timeOfDay, 24000L) / 24000.0f;
        currentSolarAltitude = Math.max(0.0f, (float) Math.sin(dayPhase * (float) (Math.PI * 2.0)));
        currentClearSky = Mth.clamp(1.0f - 0.65f * rainGradient - 0.25f * thunderGradient, 0.15f, 1.0f);

        if (terrainChanged) {
            // Preserve the dynamic weather state while refreshing cached static inputs.
            for (Map.Entry<Long, CellState> entry : cells.entrySet()) {
                updateTerrain(entry.getValue(), unpackX(entry.getKey()), unpackZ(entry.getKey()));
            }
        }
        if (updateDue || centerChanged || terrainChanged || readState.empty()) {
            ensureActiveCells();
        }
        if (updateDue) {
            advanceDynamicField();
        }

        Iterator<Map.Entry<Long, CellState>> it = cells.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, CellState> entry = it.next();
            int cx = unpackX(entry.getKey());
            int cz = unpackZ(entry.getKey());
            if (Math.abs(cx - centerCellX) > radiusCells || Math.abs(cz - centerCellZ) > radiusCells) {
                it.remove();
            }
        }
        if (updateDue || centerChanged || terrainChanged || readState.empty()) {
            publishReadState();
        }
    }

    private void ensureActiveCells() {
        for (int cx = centerCellX - radiusCells; cx <= centerCellX + radiusCells; cx++) {
            for (int cz = centerCellZ - radiusCells; cz <= centerCellZ + radiusCells; cz++) {
                ensureCell(cx, cz);
            }
        }
    }

    private void publishReadState() {
        int gridWidth = radiusCells * 2 + 1;
        int cellCount = gridWidth * gridWidth;
        boolean[] cellPresent = new boolean[cellCount];
        float[] terrainHeightBlocks = new float[cellCount];
        float[] biomeTemperature = new float[cellCount];
        float[] ambientAirTemperatureKelvin = new float[cellCount];
        float[] deepGroundTemperatureKelvin = new float[cellCount];
        float[] surfaceTemperatureKelvin = new float[cellCount];
        float[] roughnessLengthMeters = new float[cellCount];
        float[] pressureAnomalyPa = new float[cellCount];
        float[] pressureGradientXPaPerMeter = new float[cellCount];
        float[] pressureGradientZPaPerMeter = new float[cellCount];
        float[] geostrophicWindX = new float[cellCount];
        float[] geostrophicWindZ = new float[cellCount];
        float[] backgroundWindX = new float[cellCount];
        float[] backgroundWindZ = new float[cellCount];
        float[] humidity = new float[cellCount];
        float[] convectiveHeatingKelvin = new float[cellCount];
        float[] convectiveMoistening = new float[cellCount];
        float[] convectiveInflowX = new float[cellCount];
        float[] convectiveInflowZ = new float[cellCount];
        float[] convectiveEnvelope = new float[cellCount];
        float[] tornadoWindX = new float[cellCount];
        float[] tornadoWindZ = new float[cellCount];
        float[] tornadoHeatingKelvin = new float[cellCount];
        float[] tornadoMoistening = new float[cellCount];
        float[] tornadoUpdraftProxy = new float[cellCount];
        byte[] surfaceClass = new byte[cellCount];
        int presentCount = 0;

        for (int cx = centerCellX - radiusCells; cx <= centerCellX + radiusCells; cx++) {
            for (int cz = centerCellZ - radiusCells; cz <= centerCellZ + radiusCells; cz++) {
                CellState cell = cells.get(pack(cx, cz));
                if (cell == null) {
                    continue;
                }
                int index = readCellIndex(cx, cz, centerCellX, centerCellZ, radiusCells, gridWidth);
                if (index < 0) {
                    continue;
                }
                Sample sample = sampleFromCell(cell, cx, cz);
                cellPresent[index] = true;
                presentCount++;
                terrainHeightBlocks[index] = sample.terrainHeightBlocks();
                biomeTemperature[index] = sample.biomeTemperature();
                ambientAirTemperatureKelvin[index] = sample.ambientAirTemperatureKelvin();
                deepGroundTemperatureKelvin[index] = sample.deepGroundTemperatureKelvin();
                surfaceTemperatureKelvin[index] = sample.surfaceTemperatureKelvin();
                roughnessLengthMeters[index] = sample.roughnessLengthMeters();
                pressureAnomalyPa[index] = sample.pressureAnomalyPa();
                pressureGradientXPaPerMeter[index] = sample.pressureGradientXPaPerMeter();
                pressureGradientZPaPerMeter[index] = sample.pressureGradientZPaPerMeter();
                geostrophicWindX[index] = sample.geostrophicWindX();
                geostrophicWindZ[index] = sample.geostrophicWindZ();
                backgroundWindX[index] = sample.backgroundWindX();
                backgroundWindZ[index] = sample.backgroundWindZ();
                humidity[index] = sample.humidity();
                convectiveHeatingKelvin[index] = sample.convectiveHeatingKelvin();
                convectiveMoistening[index] = sample.convectiveMoistening();
                convectiveInflowX[index] = sample.convectiveInflowX();
                convectiveInflowZ[index] = sample.convectiveInflowZ();
                convectiveEnvelope[index] = sample.convectiveEnvelope();
                tornadoWindX[index] = sample.tornadoWindX();
                tornadoWindZ[index] = sample.tornadoWindZ();
                tornadoHeatingKelvin[index] = sample.tornadoHeatingKelvin();
                tornadoMoistening[index] = sample.tornadoMoistening();
                tornadoUpdraftProxy[index] = sample.tornadoUpdraftProxy();
                surfaceClass[index] = sample.surfaceClass();
            }
        }

        readState = new ReadState(
                gridWidth,
                cellSizeBlocks,
                radiusCells,
                centerCellX,
                centerCellZ,
                presentCount,
                cellPresent,
                terrainHeightBlocks,
                biomeTemperature,
                ambientAirTemperatureKelvin,
                deepGroundTemperatureKelvin,
                surfaceTemperatureKelvin,
                roughnessLengthMeters,
                pressureAnomalyPa,
                pressureGradientXPaPerMeter,
                pressureGradientZPaPerMeter,
                geostrophicWindX,
                geostrophicWindZ,
                backgroundWindX,
                backgroundWindZ,
                humidity,
                convectiveHeatingKelvin,
                convectiveMoistening,
                convectiveInflowX,
                convectiveInflowZ,
                convectiveEnvelope,
                tornadoWindX,
                tornadoWindZ,
                tornadoHeatingKelvin,
                tornadoMoistening,
                tornadoUpdraftProxy,
                surfaceClass
        );
    }

    Sample sample(BlockPos pos) {
        Sample sample = readState.sample(pos);
        if (sample != null) {
            return sample;
        }
        return sampleLocked(pos);
    }

    private synchronized Sample sampleLocked(BlockPos pos) {
        if (currentWorld == null || currentProvider == null) {
            return null;
        }
        int cellX = Math.floorDiv(pos.getX(), cellSizeBlocks);
        int cellZ = Math.floorDiv(pos.getZ(), cellSizeBlocks);
        CellState state = ensureCell(cellX, cellZ);
        return sampleFromCell(state, cellX, cellZ);
    }

    private Sample sampleFromCell(CellState state, int cellX, int cellZ) {
        WorldScaleTarget target = targetState(state, cellX, cellZ);
        float backgroundWindX = finiteOrDefault(state.backgroundWindX, target.targetWindX);
        float backgroundWindZ = finiteOrDefault(state.backgroundWindZ, target.targetWindZ);
        float geostrophicWindX = finiteOrDefault(state.geostrophicWindX, backgroundWindX);
        float geostrophicWindZ = finiteOrDefault(state.geostrophicWindZ, backgroundWindZ);
        return new Sample(
                state.terrainHeightBlocks,
                state.biomeTemperature,
                finiteOrDefault(state.ambientAirTemperatureKelvin, target.targetAmbientAirTemperatureKelvin),
                finiteOrDefault(state.deepGroundTemperatureKelvin, target.targetAmbientAirTemperatureKelvin + DEEP_GROUND_OFFSET_K),
                finiteOrDefault(
                        state.surfaceTemperatureKelvin,
                        target.targetAmbientAirTemperatureKelvin
                                + currentSolarAltitude * currentClearSky * SOLAR_SURFACE_HEATING_K
                                - (1.0f - currentSolarAltitude) * currentClearSky * CLEAR_SKY_COOLING_K
                ),
                state.roughnessLengthMeters,
                finiteClamp(state.pressureAnomalyPa, -MAX_PRESSURE_ANOMALY_PA, MAX_PRESSURE_ANOMALY_PA, target.targetPressureAnomalyPa),
                finiteOrDefault(state.pressureGradientXPaPerMeter, 0.0f),
                finiteOrDefault(state.pressureGradientZPaPerMeter, 0.0f),
                geostrophicWindX,
                geostrophicWindZ,
                backgroundWindX,
                backgroundWindZ,
                finiteClamp(state.humidity, 0.0f, 1.0f, target.targetHumidity),
                finiteOrDefault(state.convectiveHeatingKelvin, target.convectiveHeatingKelvin),
                finiteOrDefault(state.convectiveMoistening, target.convectiveMoistening),
                finiteOrDefault(state.convectiveInflowX, target.convectiveInflowX),
                finiteOrDefault(state.convectiveInflowZ, target.convectiveInflowZ),
                Math.max(0.0f, finiteOrDefault(state.convectiveEnvelope, target.convectiveEnvelope)),
                finiteOrDefault(state.tornadoWindX, target.tornadoWindX),
                finiteOrDefault(state.tornadoWindZ, target.tornadoWindZ),
                finiteOrDefault(state.tornadoHeatingKelvin, target.tornadoHeatingKelvin),
                finiteOrDefault(state.tornadoMoistening, target.tornadoMoistening),
                Math.max(0.0f, finiteOrDefault(state.tornadoUpdraftProxy, target.tornadoUpdraftProxy)),
                state.surfaceClass
        );
    }

    int cellCount() {
        return readState.cellCount();
    }

    synchronized Snapshot snapshot() {
        int gridWidth = radiusCells * 2 + 1;
        int cellCount = gridWidth * gridWidth;
        float[] terrainHeightBlocks = new float[cellCount];
        float[] biomeTemperature = new float[cellCount];
        float[] roughnessLengthMeters = new float[cellCount];
        byte[] surfaceClass = new byte[cellCount];
        float[] ambientAirTemperatureKelvin = new float[cellCount];
        float[] deepGroundTemperatureKelvin = new float[cellCount];
        float[] surfaceTemperatureKelvin = new float[cellCount];
        float[] pressureAnomalyPa = new float[cellCount];
        float[] pressureGradientXPaPerMeter = new float[cellCount];
        float[] pressureGradientZPaPerMeter = new float[cellCount];
        float[] geostrophicWindX = new float[cellCount];
        float[] geostrophicWindZ = new float[cellCount];
        float[] windX = new float[cellCount];
        float[] windZ = new float[cellCount];
        float[] humidity = new float[cellCount];
        float[] vorticity = new float[cellCount];
        float[] divergence = new float[cellCount];
        float[] temperatureAnomaly = new float[cellCount];

        for (int cx = centerCellX - radiusCells; cx <= centerCellX + radiusCells; cx++) {
            int localX = cx - (centerCellX - radiusCells);
            for (int cz = centerCellZ - radiusCells; cz <= centerCellZ + radiusCells; cz++) {
                int localZ = cz - (centerCellZ - radiusCells);
                int cellIndex = localX * gridWidth + localZ;
                CellState cell = cells.get(pack(cx, cz));
                if (cell == null) {
                    continue;
                }
                terrainHeightBlocks[cellIndex] = cell.terrainHeightBlocks;
                biomeTemperature[cellIndex] = cell.biomeTemperature;
                roughnessLengthMeters[cellIndex] = cell.roughnessLengthMeters;
                surfaceClass[cellIndex] = cell.surfaceClass;
                ambientAirTemperatureKelvin[cellIndex] = cell.ambientAirTemperatureKelvin;
                deepGroundTemperatureKelvin[cellIndex] = cell.deepGroundTemperatureKelvin;
                surfaceTemperatureKelvin[cellIndex] = cell.surfaceTemperatureKelvin;
                pressureAnomalyPa[cellIndex] = cell.pressureAnomalyPa;
                pressureGradientXPaPerMeter[cellIndex] = cell.pressureGradientXPaPerMeter;
                pressureGradientZPaPerMeter[cellIndex] = cell.pressureGradientZPaPerMeter;
                geostrophicWindX[cellIndex] = finiteOrDefault(cell.geostrophicWindX, cell.backgroundWindX);
                geostrophicWindZ[cellIndex] = finiteOrDefault(cell.geostrophicWindZ, cell.backgroundWindZ);
                windX[cellIndex] = cell.backgroundWindX;
                windZ[cellIndex] = cell.backgroundWindZ;
                humidity[cellIndex] = cell.humidity;
            }
        }

        populateDiagnostics(
                gridWidth,
                cellSizeBlocks,
                ambientAirTemperatureKelvin,
                windX,
                windZ,
                vorticity,
                divergence,
                temperatureAnomaly
        );

        WorldScaleDriver.Snapshot driverSnapshot = currentDriver == null ? null : currentDriver.snapshot();
        return new Snapshot(
                gridWidth,
                cellSizeBlocks,
                radiusCells,
                centerCellX,
                centerCellZ,
                currentRefreshTick,
                currentDeltaSeconds,
                currentSolarAltitude,
                currentClearSky,
                currentRainGradient,
                currentThunderGradient,
                driverSnapshot,
                terrainHeightBlocks,
                biomeTemperature,
                roughnessLengthMeters,
                surfaceClass,
                ambientAirTemperatureKelvin,
                deepGroundTemperatureKelvin,
                surfaceTemperatureKelvin,
                pressureAnomalyPa,
                pressureGradientXPaPerMeter,
                pressureGradientZPaPerMeter,
                geostrophicWindX,
                geostrophicWindZ,
                windX,
                windZ,
                humidity,
                vorticity,
                divergence,
                temperatureAnomaly
        );
    }

    private void populateDiagnostics(
            int gridWidth,
            int cellSizeBlocks,
            float[] ambientAirTemperatureKelvin,
            float[] windX,
            float[] windZ,
            float[] vorticity,
            float[] divergence,
            float[] temperatureAnomaly
    ) {
        float meanAmbient = 0.0f;
        for (float value : ambientAirTemperatureKelvin) {
            meanAmbient += value;
        }
        meanAmbient /= Math.max(1, ambientAirTemperatureKelvin.length);
        float dxMeters = Math.max(1.0f, cellSizeBlocks);

        for (int x = 0; x < gridWidth; x++) {
            int west = Math.max(0, x - 1);
            int east = Math.min(gridWidth - 1, x + 1);
            float xSpanMeters = Math.max(1.0f, (east - west) * dxMeters);
            for (int z = 0; z < gridWidth; z++) {
                int north = Math.max(0, z - 1);
                int south = Math.min(gridWidth - 1, z + 1);
                float zSpanMeters = Math.max(1.0f, (south - north) * dxMeters);
                int index = x * gridWidth + z;
                float dWindXdx = (windX[east * gridWidth + z] - windX[west * gridWidth + z]) / xSpanMeters;
                float dWindZdx = (windZ[east * gridWidth + z] - windZ[west * gridWidth + z]) / xSpanMeters;
                float dWindXdz = (windX[x * gridWidth + south] - windX[x * gridWidth + north]) / zSpanMeters;
                float dWindZdz = (windZ[x * gridWidth + south] - windZ[x * gridWidth + north]) / zSpanMeters;
                divergence[index] = dWindXdx + dWindZdz;
                vorticity[index] = dWindZdx - dWindXdz;
                temperatureAnomaly[index] = ambientAirTemperatureKelvin[index] - meanAmbient;
            }
        }
    }

    ResourceKey<Level> worldKey(ServerLevel world) {
        return world.dimension();
    }

    private int cellCenterBlock(int cell) {
        return cell * cellSizeBlocks + cellSizeBlocks / 2;
    }

    private CellState ensureCell(int cellX, int cellZ) {
        long key = pack(cellX, cellZ);
        CellState cell = cells.get(key);
        if (cell == null) {
            cell = new CellState();
            updateTerrain(cell, cellX, cellZ);
            cells.put(key, cell);
        }
        updateDynamicState(cell, cellX, cellZ);
        return cell;
    }

    private void updateTerrain(CellState cell, int cellX, int cellZ) {
        SeedTerrainProvider.TerrainSample terrain = currentProvider.sample(
                currentWorld, cellCenterBlock(cellX), cellCenterBlock(cellZ));
        cell.terrainHeightBlocks = terrain.terrainHeightBlocks();
        cell.biomeTemperature = terrain.biomeTemperature();
        cell.surfaceClass = terrain.surfaceClass();
        cell.roughnessLengthMeters = terrain.roughnessLengthMeters();
    }

    private void updateDynamicState(CellState cell, int cellX, int cellZ) {
        if (cell.lastUpdatedTick == currentRefreshTick) {
            return;
        }
        seedDynamicState(cell, cellX, cellZ);
        cell.lastUpdatedTick = currentRefreshTick;
    }

    private void advanceDynamicField() {
        if (cells.isEmpty()) {
            return;
        }
        Map<Long, StateSample> previous = new HashMap<>(cells.size());
        for (Map.Entry<Long, CellState> entry : cells.entrySet()) {
            CellState state = entry.getValue();
            int stateCellX = unpackX(entry.getKey());
            int stateCellZ = unpackZ(entry.getKey());
            WorldScaleTarget target = targetState(state, stateCellX, stateCellZ);
            previous.put(entry.getKey(), new StateSample(
                    finiteOrDefault(state.backgroundWindX, target.targetWindX),
                    finiteOrDefault(state.backgroundWindZ, target.targetWindZ),
                    finiteClamp(state.pressureAnomalyPa, -MAX_PRESSURE_ANOMALY_PA, MAX_PRESSURE_ANOMALY_PA, target.targetPressureAnomalyPa),
                    finiteOrDefault(state.ambientAirTemperatureKelvin, target.targetAmbientAirTemperatureKelvin),
                    finiteClamp(state.humidity, 0.0f, 1.0f, target.targetHumidity)
            ));
        }
        for (int cx = centerCellX - radiusCells; cx <= centerCellX + radiusCells; cx++) {
            for (int cz = centerCellZ - radiusCells; cz <= centerCellZ + radiusCells; cz++) {
                CellState cell = ensureCell(cx, cz);
                WorldScaleTarget target = targetState(cell, cx, cz);
                StateSample prev = sampleState(previous, cx, cz);
                float backtraceX = cx - (prev.windX * currentDeltaSeconds) / cellSizeBlocks;
                float backtraceZ = cz - (prev.windZ * currentDeltaSeconds) / cellSizeBlocks;
                StateSample advected = sampleState(previous, backtraceX, backtraceZ);
                StateSample neighborMean = sampleNeighborMean(previous, cx, cz);

                float roughnessDrag = Mth.clamp(
                        currentDeltaSeconds * (BASE_ROUGHNESS_DRAG_PER_SECOND + cell.roughnessLengthMeters * ROUGHNESS_DRAG_SCALE_PER_SECOND),
                        0.0f,
                        MAX_ROUGHNESS_DRAG
                );
                float nextWindX = mix(advected.windX, neighborMean.windX, FLOW_DIFFUSION_BLEND);
                float nextWindZ = mix(advected.windZ, neighborMean.windZ, FLOW_DIFFUSION_BLEND);
                float nextPressure = mix(advected.pressureAnomalyPa, neighborMean.pressureAnomalyPa, PRESSURE_DIFFUSION_BLEND);
                nextPressure = relax(
                        nextPressure,
                        target.targetPressureAnomalyPa,
                        currentDeltaSeconds,
                        PRESSURE_RELAXATION_PER_SECOND
                );
                nextPressure = Mth.clamp(nextPressure, -MAX_PRESSURE_ANOMALY_PA, MAX_PRESSURE_ANOMALY_PA);
                PressureWind pressureWind = geostrophicWindFromPressure(previous, cx, cz);
                float directWindBlend = directWindBlendForPressureWind(pressureWind);
                float targetWindX = Mth.lerp(directWindBlend, pressureWind.windX(), target.targetWindX);
                float targetWindZ = Mth.lerp(directWindBlend, pressureWind.windZ(), target.targetWindZ);
                WindVector terrainAdjustedWind = applyTerrainFormDrag(cx, cz, targetWindX, targetWindZ);
                nextWindX = relax(nextWindX, terrainAdjustedWind.windX(), currentDeltaSeconds, FLOW_RELAXATION_PER_SECOND);
                nextWindZ = relax(nextWindZ, terrainAdjustedWind.windZ(), currentDeltaSeconds, FLOW_RELAXATION_PER_SECOND);
                nextWindX *= (1.0f - roughnessDrag);
                nextWindZ *= (1.0f - roughnessDrag);
                nextWindX = Mth.clamp(nextWindX, -MAX_DYNAMIC_WIND_MPS, MAX_DYNAMIC_WIND_MPS);
                nextWindZ = Mth.clamp(nextWindZ, -MAX_DYNAMIC_WIND_MPS, MAX_DYNAMIC_WIND_MPS);

                float nextAmbient = mix(advected.ambientAirTemperatureKelvin, neighborMean.ambientAirTemperatureKelvin, THERMAL_DIFFUSION_BLEND);
                nextAmbient = relax(nextAmbient, target.targetAmbientAirTemperatureKelvin, currentDeltaSeconds, AIR_TEMPERATURE_RELAXATION_PER_SECOND);

                float nextHumidity = mix(advected.humidity, neighborMean.humidity, HUMIDITY_DIFFUSION_BLEND);
                float evaporationBoost = Math.max(0.0f, cell.surfaceTemperatureKelvin - nextAmbient) * EVAPORATION_SURFACE_DELTA_SCALE;
                float humidityTarget = Mth.clamp(
                        target.targetHumidity + evaporationBoost + currentRainGradient * 0.05f,
                        0.0f,
                        1.0f
                );
                nextHumidity = Mth.clamp(
                        relax(nextHumidity, humidityTarget, currentDeltaSeconds, HUMIDITY_RELAXATION_PER_SECOND),
                        0.0f,
                        1.0f
                );

                float targetSurfaceTemperatureKelvin = nextAmbient
                        + currentSolarAltitude * currentClearSky * SOLAR_SURFACE_HEATING_K
                        - (1.0f - currentSolarAltitude) * currentClearSky * CLEAR_SKY_COOLING_K
                        - nextHumidity * 2.0f
                        - currentRainGradient * 3.0f;
                cell.ambientAirTemperatureKelvin = finiteOrDefault(nextAmbient, target.targetAmbientAirTemperatureKelvin);
                cell.deepGroundTemperatureKelvin = relax(
                        cell.deepGroundTemperatureKelvin,
                        cell.ambientAirTemperatureKelvin + DEEP_GROUND_OFFSET_K,
                        currentDeltaSeconds,
                        DEEP_GROUND_RELAXATION_PER_SECOND
                );
                cell.surfaceTemperatureKelvin = relax(cell.surfaceTemperatureKelvin, targetSurfaceTemperatureKelvin, currentDeltaSeconds);
                cell.pressureAnomalyPa = finiteClamp(nextPressure, -MAX_PRESSURE_ANOMALY_PA, MAX_PRESSURE_ANOMALY_PA, target.targetPressureAnomalyPa);
                cell.pressureGradientXPaPerMeter = pressureWind.pressureGradientXPaPerMeter();
                cell.pressureGradientZPaPerMeter = pressureWind.pressureGradientZPaPerMeter();
                cell.geostrophicWindX = pressureWind.windX();
                cell.geostrophicWindZ = pressureWind.windZ();
                cell.backgroundWindX = finiteClamp(nextWindX, -MAX_DYNAMIC_WIND_MPS, MAX_DYNAMIC_WIND_MPS, target.targetWindX);
                cell.backgroundWindZ = finiteClamp(nextWindZ, -MAX_DYNAMIC_WIND_MPS, MAX_DYNAMIC_WIND_MPS, target.targetWindZ);
                cell.humidity = finiteClamp(nextHumidity, 0.0f, 1.0f, target.targetHumidity);
                cell.convectiveHeatingKelvin = finiteOrDefault(target.convectiveHeatingKelvin, 0.0f);
                cell.convectiveMoistening = finiteOrDefault(target.convectiveMoistening, 0.0f);
                cell.convectiveInflowX = finiteOrDefault(target.convectiveInflowX, 0.0f);
                cell.convectiveInflowZ = finiteOrDefault(target.convectiveInflowZ, 0.0f);
                cell.convectiveEnvelope = Math.max(0.0f, finiteOrDefault(target.convectiveEnvelope, 0.0f));
                cell.tornadoWindX = finiteOrDefault(target.tornadoWindX, 0.0f);
                cell.tornadoWindZ = finiteOrDefault(target.tornadoWindZ, 0.0f);
                cell.tornadoHeatingKelvin = finiteOrDefault(target.tornadoHeatingKelvin, 0.0f);
                cell.tornadoMoistening = finiteOrDefault(target.tornadoMoistening, 0.0f);
                cell.tornadoUpdraftProxy = Math.max(0.0f, finiteOrDefault(target.tornadoUpdraftProxy, 0.0f));
                cell.lastUpdatedTick = currentRefreshTick;
            }
        }
    }

    private void seedDynamicState(CellState cell, int cellX, int cellZ) {
        WorldScaleTarget target = targetState(cell, cellX, cellZ);
        if (!Float.isFinite(cell.ambientAirTemperatureKelvin) || cell.ambientAirTemperatureKelvin <= 0.0f) {
            cell.ambientAirTemperatureKelvin = target.targetAmbientAirTemperatureKelvin;
        }
        if (!Float.isFinite(cell.deepGroundTemperatureKelvin) || cell.deepGroundTemperatureKelvin <= 0.0f) {
            cell.deepGroundTemperatureKelvin = target.targetAmbientAirTemperatureKelvin + DEEP_GROUND_OFFSET_K;
        }
        if (!Float.isFinite(cell.surfaceTemperatureKelvin) || cell.surfaceTemperatureKelvin <= 0.0f) {
            cell.surfaceTemperatureKelvin = target.targetAmbientAirTemperatureKelvin
                    + currentSolarAltitude * currentClearSky * SOLAR_SURFACE_HEATING_K
                    - (1.0f - currentSolarAltitude) * currentClearSky * CLEAR_SKY_COOLING_K;
        }
        if (!Float.isFinite(cell.pressureAnomalyPa)) {
            cell.pressureAnomalyPa = target.targetPressureAnomalyPa;
        }
        WindVector seededWind = applyTerrainFormDrag(cellX, cellZ, target.targetWindX, target.targetWindZ);
        if (!Float.isFinite(cell.backgroundWindX)) {
            cell.backgroundWindX = seededWind.windX();
        }
        if (!Float.isFinite(cell.backgroundWindZ)) {
            cell.backgroundWindZ = seededWind.windZ();
        }
        if (!Float.isFinite(cell.humidity) || cell.humidity < 0.0f || cell.humidity > 1.0f) {
            cell.humidity = target.targetHumidity;
        }
        cell.convectiveHeatingKelvin = target.convectiveHeatingKelvin;
        cell.convectiveMoistening = target.convectiveMoistening;
        cell.convectiveInflowX = target.convectiveInflowX;
        cell.convectiveInflowZ = target.convectiveInflowZ;
        cell.convectiveEnvelope = target.convectiveEnvelope;
        cell.tornadoWindX = target.tornadoWindX;
        cell.tornadoWindZ = target.tornadoWindZ;
        cell.tornadoHeatingKelvin = target.tornadoHeatingKelvin;
        cell.tornadoMoistening = target.tornadoMoistening;
        cell.tornadoUpdraftProxy = target.tornadoUpdraftProxy;
    }

    private StateSample sampleNeighborMean(Map<Long, StateSample> previous, int cellX, int cellZ) {
        StateSample center = sampleState(previous, cellX, cellZ);
        StateSample west = sampleState(previous, cellX - 1.0f, cellZ);
        StateSample east = sampleState(previous, cellX + 1.0f, cellZ);
        StateSample north = sampleState(previous, cellX, cellZ - 1.0f);
        StateSample south = sampleState(previous, cellX, cellZ + 1.0f);
        return new StateSample(
                (center.windX + west.windX + east.windX + north.windX + south.windX) / 5.0f,
                (center.windZ + west.windZ + east.windZ + north.windZ + south.windZ) / 5.0f,
                (center.pressureAnomalyPa
                        + west.pressureAnomalyPa
                        + east.pressureAnomalyPa
                        + north.pressureAnomalyPa
                        + south.pressureAnomalyPa) / 5.0f,
                (center.ambientAirTemperatureKelvin + west.ambientAirTemperatureKelvin + east.ambientAirTemperatureKelvin + north.ambientAirTemperatureKelvin + south.ambientAirTemperatureKelvin) / 5.0f,
                Mth.clamp(
                        (center.humidity + west.humidity + east.humidity + north.humidity + south.humidity) / 5.0f,
                        0.0f,
                        1.0f
                )
        );
    }

    private StateSample sampleState(Map<Long, StateSample> previous, float cellX, float cellZ) {
        int x0 = Mth.floor(cellX);
        int z0 = Mth.floor(cellZ);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        float tx = cellX - x0;
        float tz = cellZ - z0;

        StateSample s00 = stateAt(previous, x0, z0);
        StateSample s10 = stateAt(previous, x1, z0);
        StateSample s01 = stateAt(previous, x0, z1);
        StateSample s11 = stateAt(previous, x1, z1);

        float windX0 = Mth.lerp(tx, s00.windX, s10.windX);
        float windX1 = Mth.lerp(tx, s01.windX, s11.windX);
        float windZ0 = Mth.lerp(tx, s00.windZ, s10.windZ);
        float windZ1 = Mth.lerp(tx, s01.windZ, s11.windZ);
        float pressure0 = Mth.lerp(tx, s00.pressureAnomalyPa, s10.pressureAnomalyPa);
        float pressure1 = Mth.lerp(tx, s01.pressureAnomalyPa, s11.pressureAnomalyPa);
        float temp0 = Mth.lerp(tx, s00.ambientAirTemperatureKelvin, s10.ambientAirTemperatureKelvin);
        float temp1 = Mth.lerp(tx, s01.ambientAirTemperatureKelvin, s11.ambientAirTemperatureKelvin);
        float humidity0 = Mth.lerp(tx, s00.humidity, s10.humidity);
        float humidity1 = Mth.lerp(tx, s01.humidity, s11.humidity);
        return new StateSample(
                Mth.lerp(tz, windX0, windX1),
                Mth.lerp(tz, windZ0, windZ1),
                Mth.clamp(Mth.lerp(tz, pressure0, pressure1), -MAX_PRESSURE_ANOMALY_PA, MAX_PRESSURE_ANOMALY_PA),
                Mth.lerp(tz, temp0, temp1),
                Mth.clamp(Mth.lerp(tz, humidity0, humidity1), 0.0f, 1.0f)
        );
    }

    private StateSample stateAt(Map<Long, StateSample> previous, int cellX, int cellZ) {
        StateSample sample = previous.get(pack(cellX, cellZ));
        if (sample != null) {
            return sample;
        }
        CellState cell = ensureCell(cellX, cellZ);
        WorldScaleTarget target = targetState(cell, cellX, cellZ);
        return new StateSample(
                target.targetWindX,
                target.targetWindZ,
                target.targetPressureAnomalyPa,
                target.targetAmbientAirTemperatureKelvin,
                target.targetHumidity
        );
    }

    private WorldScaleTarget targetState(CellState cell, int cellX, int cellZ) {
        float baseAmbient = BASE_AIR_TEMPERATURE_K
                + (cell.biomeTemperature - 0.8f) * BIOME_TEMPERATURE_SCALE_K
                - Math.max(0.0f, cell.terrainHeightBlocks - currentWorld.getSeaLevel()) * ALTITUDE_LAPSE_RATE_K_PER_BLOCK;
        WorldScaleDriver.Sample driverSample = currentDriver == null ? null : currentDriver.sample(cellX, cellZ);
        float fallbackWindX = prevailingWindComponent(currentWorld.getSeed(), cellX, cellZ, 0x517cc1b727220a95L);
        float fallbackWindZ = prevailingWindComponent(currentWorld.getSeed(), cellX, cellZ, 0x9e3779b97f4a7c15L);
        float targetWindX = finiteClamp(
                driverSample != null ? driverSample.targetWindX() : fallbackWindX,
                -MAX_DRIVER_WIND_MPS,
                MAX_DRIVER_WIND_MPS,
                fallbackWindX
        );
        float targetWindZ = finiteClamp(
                driverSample != null ? driverSample.targetWindZ() : fallbackWindZ,
                -MAX_DRIVER_WIND_MPS,
                MAX_DRIVER_WIND_MPS,
                fallbackWindZ
        );
        float targetPressureAnomalyPa = finiteClamp(
                driverSample != null ? driverSample.pressureAnomalyPa() : 0.0f,
                -MAX_PRESSURE_ANOMALY_PA,
                MAX_PRESSURE_ANOMALY_PA,
                0.0f
        );
        float targetAmbient = finiteOrDefault(baseAmbient + (driverSample == null ? 0.0f : driverSample.temperatureBiasKelvin()), baseAmbient);
        float humidity = finiteClamp(
                (driverSample == null ? 0.50f : driverSample.humidity())
                        + currentRainGradient * MAX_HUMIDITY_RESPONSE
                        + currentThunderGradient * 0.10f,
                0.0f,
                1.0f,
                0.50f
        );
        return new WorldScaleTarget(
                targetWindX,
                targetWindZ,
                targetPressureAnomalyPa,
                targetAmbient,
                humidity,
                finiteOrDefault(driverSample == null ? 0.0f : driverSample.convectiveHeatingKelvin(), 0.0f),
                finiteOrDefault(driverSample == null ? 0.0f : driverSample.convectiveMoistening(), 0.0f),
                finiteOrDefault(driverSample == null ? 0.0f : driverSample.convectiveInflowX(), 0.0f),
                finiteOrDefault(driverSample == null ? 0.0f : driverSample.convectiveInflowZ(), 0.0f),
                Math.max(0.0f, finiteOrDefault(driverSample == null ? 0.0f : driverSample.convectiveEnvelope(), 0.0f)),
                finiteOrDefault(driverSample == null ? 0.0f : driverSample.tornadoWindX(), 0.0f),
                finiteOrDefault(driverSample == null ? 0.0f : driverSample.tornadoWindZ(), 0.0f),
                finiteOrDefault(driverSample == null ? 0.0f : driverSample.tornadoHeatingKelvin(), 0.0f),
                finiteOrDefault(driverSample == null ? 0.0f : driverSample.tornadoMoistening(), 0.0f),
                Math.max(0.0f, finiteOrDefault(driverSample == null ? 0.0f : driverSample.tornadoUpdraftProxy(), 0.0f))
        );
    }

    private WindVector applyTerrainFormDrag(int cellX, int cellZ, float windX, float windZ) {
        float speedSquared = windX * windX + windZ * windZ;
        if (speedSquared <= 1.0e-5f || currentProvider == null || currentWorld == null) {
            return new WindVector(windX, windZ);
        }

        float west = terrainHeightAtCell(cellX - 1, cellZ);
        float east = terrainHeightAtCell(cellX + 1, cellZ);
        float north = terrainHeightAtCell(cellX, cellZ - 1);
        float south = terrainHeightAtCell(cellX, cellZ + 1);
        float slopeX = (east - west) / Math.max(1.0f, cellSizeBlocks * 2.0f);
        float slopeZ = (south - north) / Math.max(1.0f, cellSizeBlocks * 2.0f);
        float slopeSquared = slopeX * slopeX + slopeZ * slopeZ;
        if (slopeSquared <= 1.0e-6f) {
            return new WindVector(windX, windZ);
        }

        float speed = Mth.sqrt(speedSquared);
        float invSpeed = 1.0f / Math.max(1.0e-3f, speed);
        float invSlope = 1.0f / Math.max(1.0e-3f, Mth.sqrt(slopeSquared));
        float unitWindX = windX * invSpeed;
        float unitWindZ = windZ * invSpeed;
        float uphillComponent = unitWindX * slopeX + unitWindZ * slopeZ;
        float uphillDrag = Mth.clamp(
                Math.max(0.0f, uphillComponent) * TERRAIN_FORM_DRAG_SCALE,
                0.0f,
                MAX_TERRAIN_WIND_ADJUSTMENT
        );

        float adjustedWindX = windX * (1.0f - uphillDrag);
        float adjustedWindZ = windZ * (1.0f - uphillDrag);
        float contourX = -slopeZ * invSlope;
        float contourZ = slopeX * invSlope;
        float contourAlignment = windX * contourX + windZ * contourZ;
        float contourSign = contourAlignment >= 0.0f ? 1.0f : -1.0f;
        float deflection = Mth.clamp(
                Math.abs(uphillComponent) * TERRAIN_FLOW_DEFLECTION_SCALE,
                0.0f,
                MAX_TERRAIN_WIND_ADJUSTMENT
        ) * speed;
        adjustedWindX += contourX * contourSign * deflection;
        adjustedWindZ += contourZ * contourSign * deflection;

        float adjustedSpeed = Mth.sqrt(adjustedWindX * adjustedWindX + adjustedWindZ * adjustedWindZ);
        float maxAdjustedSpeed = Math.max(0.1f, speed * 1.15f);
        if (adjustedSpeed > maxAdjustedSpeed) {
            float scale = maxAdjustedSpeed / adjustedSpeed;
            adjustedWindX *= scale;
            adjustedWindZ *= scale;
        }
        return new WindVector(
                finiteClamp(adjustedWindX, -MAX_DRIVER_WIND_MPS, MAX_DRIVER_WIND_MPS, windX),
                finiteClamp(adjustedWindZ, -MAX_DRIVER_WIND_MPS, MAX_DRIVER_WIND_MPS, windZ)
        );
    }

    private float terrainHeightAtCell(int cellX, int cellZ) {
        CellState existing = cells.get(pack(cellX, cellZ));
        if (existing != null && Float.isFinite(existing.terrainHeightBlocks)) {
            return existing.terrainHeightBlocks;
        }
        SeedTerrainProvider.TerrainSample terrain = currentProvider.sample(
                currentWorld,
                cellCenterBlock(cellX),
                cellCenterBlock(cellZ)
        );
        return Float.isFinite(terrain.terrainHeightBlocks())
                ? terrain.terrainHeightBlocks()
                : currentWorld.getSeaLevel();
    }

    private PressureWind geostrophicWindFromPressure(Map<Long, StateSample> previous, int cellX, int cellZ) {
        StateSample west = sampleState(previous, cellX - 1.0f, cellZ);
        StateSample east = sampleState(previous, cellX + 1.0f, cellZ);
        StateSample north = sampleState(previous, cellX, cellZ - 1.0f);
        StateSample south = sampleState(previous, cellX, cellZ + 1.0f);
        float gradientX = (east.pressureAnomalyPa - west.pressureAnomalyPa) / Math.max(1.0f, cellSizeBlocks * 2.0f);
        float gradientZ = (south.pressureAnomalyPa - north.pressureAnomalyPa) / Math.max(1.0f, cellSizeBlocks * 2.0f);
        float coriolis = pseudoCoriolisFactor(cellZ);
        float coriolisSign = coriolis >= 0.0f ? 1.0f : -1.0f;
        float coriolisStrength = Math.max(MIN_CORIOLIS_FACTOR, Math.abs(coriolis));
        float windX = -gradientZ * GEOSTROPHIC_WIND_SCALE_M2_PER_PA_S * coriolisSign / coriolisStrength;
        float windZ = gradientX * GEOSTROPHIC_WIND_SCALE_M2_PER_PA_S * coriolisSign / coriolisStrength;
        return new PressureWind(
                gradientX,
                gradientZ,
                finiteClamp(windX, -MAX_DRIVER_WIND_MPS, MAX_DRIVER_WIND_MPS, 0.0f),
                finiteClamp(windZ, -MAX_DRIVER_WIND_MPS, MAX_DRIVER_WIND_MPS, 0.0f)
        );
    }

    private float directWindBlendForPressureWind(PressureWind pressureWind) {
        float pressureWindSpeed = windSpeed(pressureWind.windX(), pressureWind.windZ());
        if (pressureWindSpeed >= GEOSTROPHIC_FALLBACK_SPEED_MPS) {
            return GEOSTROPHIC_DIRECT_WIND_BLEND;
        }
        float fallbackWeight = 1.0f - Mth.clamp(pressureWindSpeed / GEOSTROPHIC_FALLBACK_SPEED_MPS, 0.0f, 1.0f);
        return Mth.lerp(fallbackWeight, GEOSTROPHIC_DIRECT_WIND_BLEND, GEOSTROPHIC_FALLBACK_WIND_BLEND);
    }

    private float windSpeed(float windX, float windZ) {
        if (!Float.isFinite(windX) || !Float.isFinite(windZ)) {
            return 0.0f;
        }
        return Mth.sqrt(windX * windX + windZ * windZ);
    }

    private float pseudoCoriolisFactor(int cellZ) {
        float periodCells = 384.0f;
        float wrapped = cellZ % periodCells;
        if (wrapped < 0.0f) {
            wrapped += periodCells;
        }
        return Mth.clamp((wrapped - periodCells * 0.5f) / (periodCells * 0.5f), -1.0f, 1.0f);
    }

    private float relax(float current, float target, float deltaSeconds) {
        float safeTarget = finiteOrDefault(target, finiteOrDefault(current, 0.0f));
        if (!Float.isFinite(current) || current <= 0.0f) {
            return safeTarget;
        }
        float alpha = Mth.clamp(deltaSeconds * SURFACE_RELAXATION_PER_SECOND, 0.0f, 1.0f);
        return Mth.lerp(alpha, current, safeTarget);
    }

    private float relax(float current, float target, float deltaSeconds, float ratePerSecond) {
        float safeTarget = finiteOrDefault(target, finiteOrDefault(current, 0.0f));
        if (!Float.isFinite(current)) {
            return safeTarget;
        }
        float alpha = Mth.clamp(deltaSeconds * ratePerSecond, 0.0f, 1.0f);
        return Mth.lerp(alpha, current, safeTarget);
    }

    private float mix(float a, float b, float t) {
        return Mth.lerp(Mth.clamp(t, 0.0f, 1.0f), a, b);
    }

    private float finiteOrDefault(float value, float fallback) {
        if (Float.isFinite(value)) {
            return value;
        }
        return Float.isFinite(fallback) ? fallback : 0.0f;
    }

    private float finiteClamp(float value, float min, float max, float fallback) {
        if (Float.isFinite(value)) {
            return Mth.clamp(value, min, max);
        }
        return Mth.clamp(finiteOrDefault(fallback, min), min, max);
    }

    private float prevailingWindComponent(long seed, int x, int z, long salt) {
        long h = seed ^ salt ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdl;
        h ^= h >>> 33;
        float unit = ((h >>> 40) & 0xFFFF) / 65535.0f;
        return (unit - 0.5f) * 2.0f;
    }

    private long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private int unpackZ(long packed) {
        return (int) packed;
    }

    private static int readCellIndex(
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

    private record ReadState(
            int gridWidth,
            int cellSizeBlocks,
            int radiusCells,
            int centerCellX,
            int centerCellZ,
            int presentCount,
            boolean[] cellPresent,
            float[] terrainHeightBlocks,
            float[] biomeTemperature,
            float[] ambientAirTemperatureKelvin,
            float[] deepGroundTemperatureKelvin,
            float[] surfaceTemperatureKelvin,
            float[] roughnessLengthMeters,
            float[] pressureAnomalyPa,
            float[] pressureGradientXPaPerMeter,
            float[] pressureGradientZPaPerMeter,
            float[] geostrophicWindX,
            float[] geostrophicWindZ,
            float[] backgroundWindX,
            float[] backgroundWindZ,
            float[] humidity,
            float[] convectiveHeatingKelvin,
            float[] convectiveMoistening,
            float[] convectiveInflowX,
            float[] convectiveInflowZ,
            float[] convectiveEnvelope,
            float[] tornadoWindX,
            float[] tornadoWindZ,
            float[] tornadoHeatingKelvin,
            float[] tornadoMoistening,
            float[] tornadoUpdraftProxy,
            byte[] surfaceClass
    ) {
        private static final ReadState EMPTY = new ReadState(
                0,
                1,
                0,
                0,
                0,
                0,
                new boolean[0],
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
                new byte[0]
        );

        private boolean empty() {
            return gridWidth <= 0 || presentCount <= 0;
        }

        private int cellCount() {
            return presentCount;
        }

        private Sample sample(BlockPos pos) {
            if (empty() || pos == null) {
                return null;
            }
            int cellX = Math.floorDiv(pos.getX(), cellSizeBlocks);
            int cellZ = Math.floorDiv(pos.getZ(), cellSizeBlocks);
            int index = readCellIndex(cellX, cellZ, centerCellX, centerCellZ, radiusCells, gridWidth);
            if (index < 0 || index >= cellPresent.length || !cellPresent[index]) {
                return null;
            }
            return new Sample(
                    terrainHeightBlocks[index],
                    biomeTemperature[index],
                    ambientAirTemperatureKelvin[index],
                    deepGroundTemperatureKelvin[index],
                    surfaceTemperatureKelvin[index],
                    roughnessLengthMeters[index],
                    pressureAnomalyPa[index],
                    pressureGradientXPaPerMeter[index],
                    pressureGradientZPaPerMeter[index],
                    geostrophicWindX[index],
                    geostrophicWindZ[index],
                    backgroundWindX[index],
                    backgroundWindZ[index],
                    humidity[index],
                    convectiveHeatingKelvin[index],
                    convectiveMoistening[index],
                    convectiveInflowX[index],
                    convectiveInflowZ[index],
                    convectiveEnvelope[index],
                    tornadoWindX[index],
                    tornadoWindZ[index],
                    tornadoHeatingKelvin[index],
                    tornadoMoistening[index],
                    tornadoUpdraftProxy[index],
                    surfaceClass[index]
            );
        }
    }

    record Sample(
            float terrainHeightBlocks,
            float biomeTemperature,
            float ambientAirTemperatureKelvin,
            float deepGroundTemperatureKelvin,
            float surfaceTemperatureKelvin,
            float roughnessLengthMeters,
            float pressureAnomalyPa,
            float pressureGradientXPaPerMeter,
            float pressureGradientZPaPerMeter,
            float geostrophicWindX,
            float geostrophicWindZ,
            float backgroundWindX,
            float backgroundWindZ,
            float humidity,
            float convectiveHeatingKelvin,
            float convectiveMoistening,
            float convectiveInflowX,
            float convectiveInflowZ,
            float convectiveEnvelope,
            float tornadoWindX,
            float tornadoWindZ,
            float tornadoHeatingKelvin,
            float tornadoMoistening,
            float tornadoUpdraftProxy,
            byte surfaceClass
    ) {
    }

    record Snapshot(
            int gridWidth,
            int cellSizeBlocks,
            int radiusCells,
            int centerCellX,
            int centerCellZ,
            long tick,
            float deltaSeconds,
            float solarAltitude,
            float clearSky,
            float rainGradient,
            float thunderGradient,
            WorldScaleDriver.Snapshot driver,
            float[] terrainHeightBlocks,
            float[] biomeTemperature,
            float[] roughnessLengthMeters,
            byte[] surfaceClass,
            float[] ambientAirTemperatureKelvin,
            float[] deepGroundTemperatureKelvin,
            float[] surfaceTemperatureKelvin,
            float[] pressureAnomalyPa,
            float[] pressureGradientXPaPerMeter,
            float[] pressureGradientZPaPerMeter,
            float[] geostrophicWindX,
            float[] geostrophicWindZ,
            float[] windX,
            float[] windZ,
            float[] humidity,
            float[] vorticity,
            float[] divergence,
            float[] temperatureAnomaly
    ) {
    }

    private static final class CellState {
        private float terrainHeightBlocks;
        private float biomeTemperature;
        private float ambientAirTemperatureKelvin = Float.NaN;
        private float deepGroundTemperatureKelvin = Float.NaN;
        private float surfaceTemperatureKelvin = Float.NaN;
        private float roughnessLengthMeters;
        private float pressureAnomalyPa = Float.NaN;
        private float pressureGradientXPaPerMeter;
        private float pressureGradientZPaPerMeter;
        private float geostrophicWindX = Float.NaN;
        private float geostrophicWindZ = Float.NaN;
        private float backgroundWindX = Float.NaN;
        private float backgroundWindZ = Float.NaN;
        private float humidity = Float.NaN;
        private float convectiveHeatingKelvin;
        private float convectiveMoistening;
        private float convectiveInflowX;
        private float convectiveInflowZ;
        private float convectiveEnvelope;
        private float tornadoWindX;
        private float tornadoWindZ;
        private float tornadoHeatingKelvin;
        private float tornadoMoistening;
        private float tornadoUpdraftProxy;
        private byte surfaceClass;
        private long lastUpdatedTick = Long.MIN_VALUE;
    }

    private record StateSample(
            float windX,
            float windZ,
            float pressureAnomalyPa,
            float ambientAirTemperatureKelvin,
            float humidity
    ) {
    }

    private record WorldScaleTarget(
            float targetWindX,
            float targetWindZ,
            float targetPressureAnomalyPa,
            float targetAmbientAirTemperatureKelvin,
            float targetHumidity,
            float convectiveHeatingKelvin,
            float convectiveMoistening,
            float convectiveInflowX,
            float convectiveInflowZ,
            float convectiveEnvelope,
            float tornadoWindX,
            float tornadoWindZ,
            float tornadoHeatingKelvin,
            float tornadoMoistening,
            float tornadoUpdraftProxy
    ) {
    }

    private record WindVector(float windX, float windZ) {
    }

    private record PressureWind(
            float pressureGradientXPaPerMeter,
            float pressureGradientZPaPerMeter,
            float windX,
            float windZ
    ) {
    }
}
