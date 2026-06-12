package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.api.A4mcBlockPos;
import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.A4mcPlayerRef;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroWindApi;
import com.aerodynamics4mc.api.AeroWindRuntimeProvider;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.AeroWindSamplingRules;
import com.aerodynamics4mc.api.GameplayWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftVectors;
import com.aerodynamics4mc.block.ModBlocks;
import com.aerodynamics4mc.flow.AnalysisFlowCodec;
import com.aerodynamics4mc.network.packet.AeroCoarseWindPacket;
import com.aerodynamics4mc.network.packet.AeroFlowAnalysisPacket;
import com.aerodynamics4mc.network.packet.AeroFlowPacket;
import com.aerodynamics4mc.network.packet.AeroLocalWeatherPacket;
import com.aerodynamics4mc.network.packet.AeroMesoscaleMapPacket;
import com.aerodynamics4mc.network.packet.AeroRuntimeStatePacket;
import lombok.Getter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.aerodynamics4mc.runtime.AeroCommands.storageSafeWorldId;

@Getter
public final class AeroServerRuntime {
	private static final String LOG_PREFIX = "[aerodynamics4mc] ";
	public static final int GRID_SIZE = 64;
	public static final int TICKS_PER_SECOND = 20;
	public static final float BLOCK_SIZE_METERS = 1.0f;
	public static final float DOMAIN_SIZE_METERS = GRID_SIZE * BLOCK_SIZE_METERS;
	public static final float SOLVER_STEP_SECONDS = 1.0f / TICKS_PER_SECOND;
	public static final float CELL_SIZE_METERS = BLOCK_SIZE_METERS;
	public static final int CHANNELS = 11;
	public static final int CH_OBSTACLE = 0;
	public static final int CH_FAN_MASK = 1;
	public static final int CH_FAN_VX = 2;
	public static final int CH_FAN_VY = 3;
	public static final int CH_FAN_VZ = 4;
	public static final int CH_STATE_VX = 5;
	public static final int CH_STATE_VY = 6;
	public static final int CH_STATE_VZ = 7;
	public static final int CH_STATE_P = 8;
	public static final int CH_THERMAL_SOURCE = 9;

	public static final float MAX_SAFE_LATTICE_SPEED = 0.28f;
	public static final float NATIVE_VELOCITY_SCALE = CELL_SIZE_METERS / SOLVER_STEP_SECONDS;
	public static final float MAX_RUNTIME_WIND_SPEED = MAX_SAFE_LATTICE_SPEED * NATIVE_VELOCITY_SCALE;
	public static final float DEFAULT_FAN_INFLOW_SPEED = 4.0f;
	public static final float INFLOW_SPEED = Math.min(DEFAULT_FAN_INFLOW_SPEED, MAX_RUNTIME_WIND_SPEED);
	public static final int FAN_RADIUS = 1;
	public static final int DUCT_SCAN_MAX = 20;
	public static final int DUCT_RING_RADIUS = 1;
	public static final float DUCT_RING_FILL_THRESHOLD = 0.80f;
	public static final int DUCT_MAX_CONSECUTIVE_GAPS = 1;
	public static final int DUCT_LEVEL_ONE_MIN = 6;
	public static final int DUCT_LEVEL_TWO_MIN = 12;
	public static final int DUCT_LEVEL_THREE_MIN = 20;
	public static final int DUCT_JET_RANGE = 20;
	public static final float DUCT_EDGE_FACTOR = 0.22f;
	public static final int RESPONSE_CHANNELS = 4;
	public static final int FLOW_COUNT = GRID_SIZE * GRID_SIZE * GRID_SIZE * RESPONSE_CHANNELS;

	public static final int WINDOW_THERMAL_REFRESH_TICKS = 40;
	public static final int COARSE_FLOW_SYNC_INTERVAL_TICKS = 4;
	public static final int FLOW_ATLAS_BASE_RESEND_INTERVAL_TICKS = 1;
	public static final int FLOW_ATLAS_PLAYER_INTERVAL_INCREMENT_TICKS = TICKS_PER_SECOND / 4;
	public static final int FLOW_ATLAS_MAX_RESEND_INTERVAL_TICKS = TICKS_PER_SECOND * 6;
	public static final int FLOW_ATLAS_MAX_PAYLOADS_PER_SYNC = 2;
	public static final boolean SERVER_AUTHORITATIVE_L2_ENABLED = false;
	public static final boolean SERVER_L2_ATLAS_STREAMING_ENABLED = SERVER_AUTHORITATIVE_L2_ENABLED;
	public static final int PARTICLE_FLOW_SAMPLE_STRIDE = 1;
	public static final float ATLAS_VELOCITY_QUANT_RANGE = 5.6f;
	public static final float ATLAS_PRESSURE_QUANT_RANGE = 0.03f;
	public static final int COARSE_ATMOSPHERE_CHANNELS = 10;
	public static final float COARSE_TEMPERATURE_ANOMALY_RANGE_K = 64.0f;
	public static final float COARSE_TURBULENCE_RANGE_MPS = 3.0f;
	public static final float COARSE_SHEAR_RANGE_PER_BLOCK = 0.08f;
	public static final float ZERO_ATLAS_MAX_SPEED_EPS_MPS = 0.02f;
	public static final int ZERO_ATLAS_HOLD_TICKS = 6;
	public static final float EXPECTED_COARSE_WIND_MIN_MPS = 0.05f;
	public static final int COARSE_WIND_SYNC_CELL_SIZE_BLOCKS = 32;
	public static final int COARSE_WIND_SYNC_SIZE_X = 9;
	public static final int COARSE_WIND_SYNC_SIZE_Y = 5;
	public static final int COARSE_WIND_SYNC_SIZE_Z = 9;
	public static final int COARSE_WIND_RESEND_INTERVAL_TICKS = TICKS_PER_SECOND;
	public static final int LOCAL_WEATHER_RESEND_INTERVAL_TICKS = TICKS_PER_SECOND;
	public static final int LOCAL_WEATHER_SERVER_STATE_MAX_AGE_TICKS = TICKS_PER_SECOND * 5;
	public static final float ANALYSIS_FLOW_VELOCITY_TOLERANCE = 0.02f;
	public static final float ANALYSIS_FLOW_PRESSURE_TOLERANCE = 0.0005f;
	public static final int L2_CAPTURE_DEFAULT_DURATION_SECONDS = 30;
	public static final int L2_CAPTURE_DEFAULT_FPS = 2;
	public static final int L2_CAPTURE_MIN_DURATION_SECONDS = 1;
	public static final int L2_CAPTURE_MAX_DURATION_SECONDS = 600;
	public static final int L2_CAPTURE_MIN_FPS = 1;
	public static final int L2_CAPTURE_MAX_FPS = 20;
	private static final int L2_CAPTURE_MAX_PENDING_FRAMES = 4;
	public static final int INSPECTION_PATCH_DEFAULT_DOMAIN_BLOCKS = 64;
	public static final int INSPECTION_PATCH_MIN_DOMAIN_BLOCKS = 32;
	public static final int INSPECTION_PATCH_MAX_DOMAIN_BLOCKS = 128;
	public static final int INSPECTION_PATCH_DEFAULT_GRID_RESOLUTION = 128;
	public static final int INSPECTION_PATCH_MIN_GRID_RESOLUTION = 32;
	public static final int INSPECTION_PATCH_MAX_GRID_RESOLUTION = 256;
	public static final int INSPECTION_PATCH_DEFAULT_FACE_RESOLUTION = 24;
	public static final int INSPECTION_PATCH_MIN_FACE_RESOLUTION = 2;
	public static final int INSPECTION_PATCH_MAX_FACE_RESOLUTION = 24;
	public static final int INSPECTION_PATCH_DEFAULT_SOLVE_STEPS = 120;
	public static final int INSPECTION_PATCH_MIN_SOLVE_STEPS = 1;
	public static final int INSPECTION_PATCH_MAX_SOLVE_STEPS = 2000;
	public static final float INSPECTION_PATCH_BOUNDARY_VELOCITY_TOLERANCE = 0.0005f;
	public static final float INSPECTION_PATCH_BOUNDARY_TEMPERATURE_TOLERANCE = 0.01f;
	public static final float INSPECTION_PATCH_EMITTER_POWER_TOLERANCE = 0.1f;
	public static final float INSPECTION_PATCH_OUTPUT_VELOCITY_TOLERANCE = 0.001f;
	public static final float INSPECTION_PATCH_OUTPUT_PRESSURE_TOLERANCE = 0.00025f;
	public static final float INSPECTION_PATCH_OUTPUT_TEMPERATURE_TOLERANCE = 0.01f;
	public static final int INSPECTION_PATCH_SPONGE_THICKNESS_CELLS = 12;
	public static final float INSPECTION_PATCH_SPONGE_VELOCITY_RELAXATION = 0.18f;
	public static final float INSPECTION_PATCH_SPONGE_TEMPERATURE_RELAXATION = 0.08f;
	public static final int MAX_SIMULATION_STEP_BACKLOG = 2;
	public static final int CHUNK_SIZE = 16;
	public static final int WINDOW_SECTION_COUNT = GRID_SIZE / CHUNK_SIZE;
	public static final int WINDOW_SECTION_VOLUME = WINDOW_SECTION_COUNT * WINDOW_SECTION_COUNT * WINDOW_SECTION_COUNT;
	public static final int SECTION_CELL_COUNT = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE;
	public static final int SOLVER_WORKER_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
	public static final float STATE_PRESSURE_MIN = -0.03f;
	public static final float STATE_PRESSURE_MAX = 0.03f;
	public static final float THERMAL_BASE_AMBIENT_AIR_TEMPERATURE_K = 288.15f;
	public static final float THERMAL_BIOME_TEMPERATURE_SCALE_K = 12.0f;
	public static final float THERMAL_ALTITUDE_LAPSE_RATE_K_PER_BLOCK = 0.0065f;
	public static final float THERMAL_DEEP_GROUND_OFFSET_K = 1.5f;
	public static final float THERMAL_SKY_TEMP_DROP_DAY_K = 10.0f;
	public static final float THERMAL_SKY_TEMP_DROP_NIGHT_K = 24.0f;
	public static final float THERMAL_PRECIP_TEMP_DROP_K = 4.0f;
	public static final float THERMAL_SOLAR_DIRECT_FLUX_W_M2 = 850.0f;
	public static final float THERMAL_SOLAR_DIFFUSE_FLUX_W_M2 = 140.0f;
	public static final float THERMAL_EMITTER_POWER_LAVA_W = 3200.0f;
	public static final float THERMAL_EMITTER_POWER_MAGMA_W = 1200.0f;
	public static final float THERMAL_EMITTER_POWER_CAMPFIRE_W = 1800.0f;
	public static final float THERMAL_EMITTER_POWER_SOUL_CAMPFIRE_W = 1200.0f;
	public static final float THERMAL_EMITTER_POWER_FIRE_W = 2200.0f;
	public static final float THERMAL_EMITTER_POWER_SOUL_FIRE_W = 1500.0f;
	public static final float THERMAL_EMITTER_POWER_TORCH_W = 80.0f;
	public static final float THERMAL_EMITTER_POWER_SOUL_TORCH_W = 50.0f;
	public static final float THERMAL_EMITTER_POWER_LANTERN_W = 60.0f;
	public static final float THERMAL_EMITTER_POWER_SOUL_LANTERN_W = 40.0f;
	public static final float RUNTIME_TEMPERATURE_SCALE_KELVIN = 20.0f;
	public static final byte SURFACE_KIND_NONE = 0;
	public static final byte SURFACE_KIND_ROCK = 1;
	public static final byte SURFACE_KIND_SOIL = 2;
	public static final byte SURFACE_KIND_VEGETATION = 3;
	public static final byte SURFACE_KIND_SNOW_ICE = 4;
	public static final byte SURFACE_KIND_WATER = 5;
	public static final byte SURFACE_KIND_MOLTEN = 6;
	public static final Direction[] CARDINAL_DIRECTIONS = Direction.values();
	public static final int FACE_COUNT = 6;
	public static final int INSPECTION_PATCH_ALL_FACE_MASK = (1 << FACE_COUNT) - 1;
	public static final int BACKGROUND_MET_CELL_SIZE_BLOCKS = 256;
	public static final int BACKGROUND_MET_RADIUS_CELLS = 20;
	public static final int MESOSCALE_MET_CELL_SIZE_BLOCKS = 64;
	public static final int MESOSCALE_MET_RADIUS_CELLS = 16;
	public static final int MESOSCALE_MET_LAYER_HEIGHT_BLOCKS = 40;
	public static final int MESOSCALE_MET_MAX_LAYERS = Math.max(1, 320 / MESOSCALE_MET_LAYER_HEIGHT_BLOCKS);
	public static final int MESOSCALE_FORCING_REBUILD_TICKS = TICKS_PER_SECOND * 60;
	public static final float MESOSCALE_STEP_SECONDS = MESOSCALE_MET_CELL_SIZE_BLOCKS * SOLVER_STEP_SECONDS;
	public static final int MESOSCALE_REFRESH_TICKS = Math.max(1, Math.round(MESOSCALE_STEP_SECONDS / SOLVER_STEP_SECONDS));
	public static final int L2_TO_L1_FEEDBACK_STEPS = MESOSCALE_REFRESH_TICKS;
	public static final int NESTED_BOUNDARY_FACE_RESOLUTION = 6;
	public static final float NESTED_BOUNDARY_MAX_VY_RATIO = 0.60f;
	public static final int BACKGROUND_MET_REFRESH_TICKS = MESOSCALE_REFRESH_TICKS * 4;
	public static final int STATIC_MIRROR_HIGH_PRIORITY_BUILD_BUDGET_PER_TICK = 1;
	public static final int STATIC_MIRROR_LOW_PRIORITY_BUILD_INTERVAL_TICKS = TICKS_PER_SECOND;
	public static final int STATIC_MIRROR_LOW_PRIORITY_BUILD_BUDGET = 1;
	public static final int FAN_DUCT_REFRESH_BUDGET_PER_TICK = 1;
	public static final int WORLD_DELTA_FLUSH_BATCH_SIZE = 256;
	public static final int WORLD_DELTA_FLUSH_MAX_BATCHES_PER_CYCLE = 1;
	public static final int RESIDENT_BRICK_STATIC_REFRESH_BUDGET_PER_CYCLE = 2;
	public static final boolean ENTITY_SAMPLE_COLLECTION_ENABLED = false;
	public static final int MAIN_THREAD_PHASE_SERVICE_INIT = 0;
	public static final int MAIN_THREAD_PHASE_FOCUS = 1;
	public static final int MAIN_THREAD_PHASE_BACKGROUND_BATCH = 2;
	public static final int MAIN_THREAD_PHASE_ACTIVE_BATCH = 3;
	public static final int MAIN_THREAD_PHASE_LIVE_BUILDS = 4;
	public static final int MAIN_THREAD_PHASE_FAN_REFRESHES = 5;
	public static final int MAIN_THREAD_PHASE_COORDINATOR = 6;
	public static final int MAIN_THREAD_PHASE_FLOW_SYNC = 7;
	public static final int MAIN_THREAD_PHASE_TOTAL = 8;
	public static final String[] MAIN_THREAD_PHASE_NAMES = {
			"serviceInit",
			"focus",
			"bgBatch",
			"activeBatch",
			"liveBuilds",
			"fanRefreshes",
			"coordinator",
			"flowSync",
			"total"
	};
	public static final int CALLBACK_PHASE_CHUNK_LOAD = 0;
	public static final int CALLBACK_PHASE_CHUNK_UNLOAD = 1;
	public static final int CALLBACK_PHASE_BLOCK_ENTITY_LOAD = 2;
	public static final int CALLBACK_PHASE_BLOCK_ENTITY_UNLOAD = 3;
	public static final int CALLBACK_PHASE_WORLD_UNLOAD = 4;
	public static final int CALLBACK_PHASE_BLOCK_CHANGED = 5;
	public static final String[] CALLBACK_PHASE_NAMES = {
			"chunkLoad",
			"chunkUnload",
			"blockEntityLoad",
			"blockEntityUnload",
			"worldUnload",
			"blockChanged"
	};
	public static final int WINDOW_EDGE_STABILIZATION_LAYERS = 8;
	public static final float WINDOW_EDGE_STABILIZATION_MIN_KEEP = 0.15f;
	public static final int REGION_HALO_CELLS = CHUNK_SIZE;
	public static final int REGION_CORE_SIZE = GRID_SIZE - REGION_HALO_CELLS * 2;
	public static final int REGION_LATTICE_STRIDE = REGION_CORE_SIZE;
	public static final int BRICK_RUNTIME_SIZE = REGION_CORE_SIZE;
	public static final int HORIZONTAL_ATTACH_MARGIN_CELLS = Math.max(4, REGION_CORE_SIZE / 4);
	public static final int HORIZONTAL_SOLVE_MARGIN_CELLS = Math.max(4, REGION_CORE_SIZE / 4);
	public static final int VERTICAL_SOLVE_MARGIN_CELLS = Math.max(4, REGION_CORE_SIZE / 4);
	public static final int VERTICAL_ATTACH_MARGIN_CELLS = Math.max(VERTICAL_SOLVE_MARGIN_CELLS + 4, REGION_CORE_SIZE / 3);
	public static final int SHELL_WINDOW_RETENTION_TICKS = 8;
	public static final int SOLVE_WINDOW_RETENTION_TICKS = 12;
	public static final int ACTIVE_BRICK_TRAIL_MAX_SEGMENTS_PER_PLAYER = 8;
	public static final int BOUNDARY_FIELD_REFRESH_TICKS = 4;
	public static final int BRICK_DYNAMIC_SYNC_INTERVAL_TICKS = 4;
	public static final int BRICK_BOUNDARY_REFERENCE_REFRESH_INTERVAL_TICKS = 4;
	public static final String DEPRECATED_ANALYSIS_CAPTURE_MESSAGE =
			"Deprecated: analysis/capture tooling is no longer on the primary roadmap and may lag behind brick-runtime changes.";
	public static final int CORE_SECTION_MIN = REGION_HALO_CELLS / CHUNK_SIZE;
	public static final int CORE_SECTION_MAX = CORE_SECTION_MIN + (REGION_CORE_SIZE / CHUNK_SIZE) - 1;
	public static final int CORE_SECTION_COUNT = (CORE_SECTION_MAX - CORE_SECTION_MIN + 1)
			* (CORE_SECTION_MAX - CORE_SECTION_MIN + 1)
			* (CORE_SECTION_MAX - CORE_SECTION_MIN + 1);
	public static AeroServerRuntime INSTANCE = null;

	public static synchronized AeroServerRuntime getInstance() {
		if (INSTANCE == null)
			INSTANCE = new AeroServerRuntime();

		return INSTANCE;
	}

	public final Map<WindowKey, RegionRecord> regions = new HashMap<>();
	public static final NativeSimulationBridge simulationBridge = new NativeSimulationBridge();
	public final SeedTerrainProvider seedTerrainProvider = new WorldgenSeedTerrainProvider();
	public static final NestedBoundaryCoupler nestedBoundaryCoupler = new NestedBoundaryCoupler();
	public static final WorldMirror worldMirror = new WorldMirror();
	public final DynamicStore dynamicStore = new DynamicStore();
	public static final Map<ResourceKey<Level>, BackgroundMetGrid> backgroundMetGrids = new HashMap<>();
	public final Map<ResourceKey<Level>, WorldScaleDriver> worldScaleDrivers = new HashMap<>();
	public static final Map<ResourceKey<Level>, MesoscaleGrid> mesoscaleMetGrids = new HashMap<>();
	public final Map<ResourceKey<Level>, ConcurrentLinkedQueue<MesoscaleGrid.NestedFeedbackBin>> pendingNestedFeedbackBins = new ConcurrentHashMap<>();
	public final Map<ResourceKey<Level>, NestedFeedbackRuntimeDiagnostics> nestedFeedbackRuntimeDiagnostics = new ConcurrentHashMap<>();
	public final Set<ResourceKey<Level>> brickRuntimeHintWorldKeys = new HashSet<>();
	public final Set<ResourceKey<Level>> brickRuntimeKnownWorldKeys = new HashSet<>();
	public final Map<ResourceKey<Level>, Map<BrickRuntimeHint, Integer>> brickBoundaryReferenceRefreshTicks = new HashMap<>();
	public final Object simulationStateLock = new Object();
	public final Object coordinatorLifecycleLock = new Object();
	public final Object pendingWorldDeltasLock = new Object();
	public final LinkedHashMap<WorldDeltaQueueKey, NativeSimulationBridge.WorldDelta> pendingWorldDeltas = new LinkedHashMap<>();
	public final Object pendingResidentBrickStaticRefreshesLock = new Object();
	public final LinkedHashSet<ChunkResidentBrickRefreshRequest> pendingResidentBrickStaticRefreshes = new LinkedHashSet<>();
	public final ExecutorService diagnosticsExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "aero-diagnostics-writer");
		thread.setDaemon(true);
		return thread;
	});
	public final AtomicInteger simulationStepBudget = new AtomicInteger(0);
	public final AtomicLong runtimeGeneration = new AtomicLong(0L);
	public final AtomicLong publishedFrameCounter = new AtomicLong(0L);
	public final AtomicReference<PublishedFrame> publishedFrame = new AtomicReference<>(null);
	public final AtomicReference<Map<UUID, PlayerProbe>> publishedPlayerProbes = new AtomicReference<>(Map.of());
	public final AtomicReference<Map<UUID, EntitySample>> publishedEntitySamples = new AtomicReference<>(Map.of());
	public volatile Set<WindowKey> desiredWindowKeys = Set.of();
	public volatile Set<WindowKey> desiredSolveWindowKeys = Set.of();
	public volatile Set<WindowKey> anchorDesiredWindowKeys = Set.of();
	public volatile Set<WindowKey> anchorSolveWindowKeys = Set.of();
	public final Map<WindowKey, Integer> shellWindowRetainUntilTick = new HashMap<>();
	public final Map<WindowKey, Integer> solveWindowRetainUntilTick = new HashMap<>();
	public final Map<UUID, PlayerMotionAnchorState> playerMotionAnchorStates = new HashMap<>();
	public final Map<UUID, CoarseWindSyncState> lastCoarseWindSyncStates = new HashMap<>();
	public final Map<UUID, LocalWeatherSyncState> lastLocalWeatherSyncStates = new HashMap<>();
	public final Map<UUID, FlowAtlasSyncState> lastFlowAtlasSyncStates = new HashMap<>();
	public final Map<ResourceKey<Level>, LocalWeatherServerState> localWeatherServerStates = new HashMap<>();
	public final Set<UUID> clientLocalL2Players = ConcurrentHashMap.newKeySet();
	public final Map<WindowKey, Integer> zeroAtlasHoldUntilTick = new HashMap<>();
	public final Set<WindowKey> mirrorOnlyPrewarmedWindowKeys = new HashSet<>();
	public final Map<WindowKey, Long> mirrorOnlyUploadedBrickStaticSignatures = new HashMap<>();
	public final Map<WindowKey, Long> uploadedBrickDynamicSeedSignatures = new HashMap<>();
	public volatile Map<ResourceKey<Level>, WorldEnvironmentSnapshot> worldEnvironmentSnapshots = Map.of();
	public volatile List<PlayerProbeRequest> activePlayerProbeRequests = List.of();
	public volatile List<EntitySampleRequest> activeEntitySampleRequests = List.of();
	public volatile ActiveRegionBatch pendingActiveRegionBatch;
	public volatile BackgroundRefreshBatch pendingBackgroundRefreshBatch;
	public volatile L2CaptureSession activeL2CaptureSession;
	public volatile InspectionSolveSession activeInspectionSolveSession;

	public volatile boolean streamingEnabled = true;
	public volatile boolean renderVelocityVectorsEnabled = true;
	public volatile boolean renderStreamlinesEnabled = true;
	public volatile int tickCounter = 0;
	public long simulationTicks = 0L;
	public long lastObservedPublishedFrameId = 0L;
	public long lastSyncedFlowFrameId = 0L;
	public volatile int lastPublishedFrameTick = Integer.MIN_VALUE;
	public int secondWindowTotalTicks = 0;
	public int secondWindowSimulationTicks = 0;
	public float simulationTicksPerSecond = 0.0f;
	public float lastMaxFlowSpeed = 0.0f;
	public volatile String lastSolverError = "";
	public volatile String lastCoordinatorError = "";
	public volatile int lastCoordinatorObservedTick = Integer.MIN_VALUE;
	public volatile int lastCoordinatorActiveWindowCount = 0;
	public volatile int lastCoordinatorSolveWindowCount = 0;
	public volatile int lastCoordinatorScheduledWindowCount = 0;
	public volatile int lastCoordinatorBusyWindowCount = 0;
	public volatile int lastCoordinatorPublishTick = Integer.MIN_VALUE;
	public volatile int lastCoordinatorScheduleTick = Integer.MIN_VALUE;
	public volatile int lastCoordinatorSolveCompleteTick = Integer.MIN_VALUE;
	public volatile String lastCoordinatorState = "init";
	public volatile String lastCoordinatorNoPublishReason = "";
	public volatile float lastCoordinatorPublishedMaxSpeed = 0.0f;
	public volatile float lastCoordinatorAppliedMaxSpeed = 0.0f;
	public volatile long lastCoordinatorWaitNanos = 0L;
	public volatile long lastCoordinatorPostSolveNanos = 0L;
	public volatile int lastBackgroundRefreshAppliedTick = Integer.MIN_VALUE;
	public volatile int lastBackgroundRefreshWorldCount = 0;
	public volatile long lastBackgroundRefreshNanos = 0L;
	public volatile long lastBackgroundDiagnosticsNanos = 0L;
	public volatile long lastBackgroundDriverNanos = 0L;
	public volatile long lastBackgroundL0Nanos = 0L;
	public volatile long lastBackgroundL1Nanos = 0L;
	public volatile long lastBackgroundFeedbackNanos = 0L;
	public volatile int pendingWorldDeltaCount = 0;
	public volatile int lastWorldDeltaFlushCount = 0;
	public volatile long lastWorldDeltaFlushNanos = 0L;
	public volatile int pendingResidentBrickStaticRefreshCount = 0;
	public volatile int lastResidentBrickStaticRefreshCount = 0;
	public volatile long lastResidentBrickStaticRefreshNanos = 0L;
	public volatile long simulationServiceId = 0L;
	public volatile MinecraftServer currentServer;
	public int lastSimulationFocusX = Integer.MIN_VALUE;
	public int lastSimulationFocusY = Integer.MIN_VALUE;
	public int lastSimulationFocusZ = Integer.MIN_VALUE;
	public SimulationCoordinator simulationCoordinator;
	public final long[] lastMainThreadPhaseNanos = new long[MAIN_THREAD_PHASE_NAMES.length];
	public final long[] maxMainThreadPhaseNanos = new long[MAIN_THREAD_PHASE_NAMES.length];
	public final long[] lastCallbackTotalNanos = new long[CALLBACK_PHASE_NAMES.length];
	public final long[] lastCallbackLockWaitNanos = new long[CALLBACK_PHASE_NAMES.length];
	public final long[] lastCallbackLockHeldNanos = new long[CALLBACK_PHASE_NAMES.length];
	public final long[] maxCallbackTotalNanos = new long[CALLBACK_PHASE_NAMES.length];
	public final long[] maxCallbackLockWaitNanos = new long[CALLBACK_PHASE_NAMES.length];
	public final long[] maxCallbackLockHeldNanos = new long[CALLBACK_PHASE_NAMES.length];

	private AeroServerRuntime() {
		AeroWindApi.registerProvider(new RuntimeProvider());
	}

	private static final class RuntimeProvider implements AeroWindRuntimeProvider {
		@Override
		public AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
			return policy == null ? sampleFlow(world, position) : sampleFlow(world, position, policy);
		}

		@Override
		public AeroWindSample sample(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy) {
			return policy == null ? sampleFlow(world, position) : sampleFlow(world, position, policy);
		}

		@Override
		public AeroWindSample sample(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy) {
			return policy == null ? sampleFlow(player, position) : sampleFlow(player, position, policy);
		}

		@Override
		public AeroWindSample sample(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy) {
			return policy == null ? sampleFlow(player, position) : sampleFlow(player, position, policy);
		}

		@Override
		public GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
			return policy == null ? AeroServerRuntime.sampleGameplay(world, position) : AeroServerRuntime.sampleGameplay(world, position, policy);
		}

		@Override
		public GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy) {
			return policy == null ? AeroServerRuntime.sampleGameplay(world, position) : AeroServerRuntime.sampleGameplay(world, position, policy);
		}

		@Override
		public GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy) {
			return policy == null ? AeroServerRuntime.sampleGameplay(player, position) : AeroServerRuntime.sampleGameplay(player, position, policy);
		}

		@Override
		public GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy) {
			return policy == null ? AeroServerRuntime.sampleGameplay(player, position) : AeroServerRuntime.sampleGameplay(player, position, policy);
		}
	}

	public static void notifyBlockStateChanged(ServerLevel world, BlockPos pos, BlockState oldState, BlockState newState) {
		INSTANCE.onBlockChanged(world, pos, oldState, newState);
	}

	public static PlayerProbe getPlayerProbe(UUID playerId) {
		if (playerId == null) {
			return null;
		}
		return INSTANCE.publishedPlayerProbes.get().get(playerId);
	}

	public static EntitySample getEntitySample(UUID entityId) {
		if (entityId == null) {
			return null;
		}
		return INSTANCE.publishedEntitySamples.get().get(entityId);
	}

	public void setClientLocalL2Preference(ServerPlayer player, boolean enabled) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUUID();
		if (enabled) {
			clientLocalL2Players.add(playerId);
			lastFlowAtlasSyncStates.remove(playerId);
			playerMotionAnchorStates.remove(playerId);
		} else {
			clientLocalL2Players.remove(playerId);
		}
	}

	public void onPlayerDisconnected(ServerPlayer player) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUUID();
		clientLocalL2Players.remove(playerId);
		playerMotionAnchorStates.remove(playerId);
		lastCoarseWindSyncStates.remove(playerId);
		lastLocalWeatherSyncStates.remove(playerId);
		lastFlowAtlasSyncStates.remove(playerId);
	}

	private boolean usesClientLocalL2(ServerPlayer player) {
		return player != null && clientLocalL2Players.contains(player.getUUID());
	}

	private boolean shouldRunServerAuthoritativeL2() {
		return SERVER_AUTHORITATIVE_L2_ENABLED;
	}

	public static A4mcWorldRef worldRef(ServerLevel world) {
		if (world == null) {
			return null;
		}
		return A4mcWorldRef.server(fromMinecraftId(world.dimension().identifier()), world);
	}

	public static A4mcPlayerRef playerRef(ServerPlayer player) {
		if (player == null) {
			return null;
		}
		A4mcWorldRef world = A4mcWorldRef.server(fromMinecraftId(player.level().dimension().identifier()), player.level());
		return A4mcPlayerRef.server(player.getUUID(), world, player);
	}

	public static AeroWindSample sampleFlow(A4mcWorldRef world, A4mcVec3 position) {
		return sampleFlow(world, position, SamplePolicy.SERVER_COARSE_ONLY);
	}

	public static AeroWindSample sampleFlow(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
		if (position == null) {
			return AeroWindSample.ZERO;
		}
		return sampleFlow(world, BlockPos.containing(position.x(), position.y(), position.z()), policy);
	}

	public static AeroWindSample sampleFlow(A4mcWorldRef world, A4mcBlockPos position) {
		return sampleFlow(world, position, SamplePolicy.SERVER_COARSE_ONLY);
	}

	public static AeroWindSample sampleFlow(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy) {
		if (position == null) {
			return AeroWindSample.ZERO;
		}
		return sampleFlow(world, new BlockPos(position.x(), position.y(), position.z()), policy);
	}

	private static AeroWindSample sampleFlow(A4mcWorldRef world, BlockPos position, SamplePolicy policy) {
		if (world == null || position == null) {
			return AeroWindSample.ZERO;
		}
		if (world.platformHandle() instanceof ServerLevel serverWorld) {
			return sampleFlow(serverWorld, position, policy);
		}
		if (world.side() == A4mcWorldRef.Side.CLIENT) {
			return AeroWindSample.ZERO;
		}
		ResourceKey<Level> worldKey = toWorldKey(world);
		if (worldKey == null) {
			return AeroWindSample.ZERO;
		}
		return INSTANCE.sampleWind(worldKey, position, policy);
	}

	public static AeroWindSample sampleFlow(A4mcPlayerRef player, A4mcVec3 position) {
		return sampleFlow(player, position, SamplePolicy.SERVER_COARSE_ONLY);
	}

	public static AeroWindSample sampleFlow(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy) {
		if (position == null) {
			return AeroWindSample.ZERO;
		}
		return sampleFlow(player, BlockPos.containing(position.x(), position.y(), position.z()), policy);
	}

	public static AeroWindSample sampleFlow(A4mcPlayerRef player, A4mcBlockPos position) {
		return sampleFlow(player, position, SamplePolicy.SERVER_COARSE_ONLY);
	}

	public static AeroWindSample sampleFlow(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy) {
		if (position == null) {
			return AeroWindSample.ZERO;
		}
		return sampleFlow(player, new BlockPos(position.x(), position.y(), position.z()), policy);
	}

	private static AeroWindSample sampleFlow(A4mcPlayerRef player, BlockPos position, SamplePolicy policy) {
		if (player == null || position == null) {
			return AeroWindSample.ZERO;
		}
		if (player.platformHandle() instanceof ServerPlayer serverPlayer) {
			return sampleFlow(serverPlayer, position, policy);
		}
		return sampleFlow(player.world(), position, effectiveSamplePolicyForPlayer(player, policy));
	}

	public static GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcVec3 position) {
		return sampleGameplay(world, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
	}

	public static GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
		if (position == null) {
			return GameplayWindSample.ZERO;
		}
		return sampleGameplay(world, BlockPos.containing(position.x(), position.y(), position.z()), policy);
	}

	public static GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcBlockPos position) {
		return sampleGameplay(world, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
	}

	public static GameplayWindSample sampleGameplay(A4mcWorldRef world, A4mcBlockPos position, SamplePolicy policy) {
		if (position == null) {
			return GameplayWindSample.ZERO;
		}
		return sampleGameplay(world, new BlockPos(position.x(), position.y(), position.z()), policy);
	}

	private static GameplayWindSample sampleGameplay(A4mcWorldRef world, BlockPos position, SamplePolicy policy) {
		if (world == null || position == null) {
			return GameplayWindSample.ZERO;
		}
		if (world.platformHandle() instanceof ServerLevel serverWorld) {
			return sampleGameplay(serverWorld, position, policy);
		}
		if (world.side() == A4mcWorldRef.Side.CLIENT) {
			return GameplayWindSample.ZERO;
		}
		ResourceKey<Level> worldKey = toWorldKey(world);
		if (worldKey == null) {
			return GameplayWindSample.ZERO;
		}
		return INSTANCE.sampleGameplayWind(worldKey, position, policy);
	}

	public static GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcVec3 position) {
		return sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
	}

	public static GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcVec3 position, SamplePolicy policy) {
		if (position == null) {
			return GameplayWindSample.ZERO;
		}
		return sampleGameplay(player, BlockPos.containing(position.x(), position.y(), position.z()), policy);
	}

	public static GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcBlockPos position) {
		return sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
	}

	public static GameplayWindSample sampleGameplay(A4mcPlayerRef player, A4mcBlockPos position, SamplePolicy policy) {
		if (position == null) {
			return GameplayWindSample.ZERO;
		}
		return sampleGameplay(player, new BlockPos(position.x(), position.y(), position.z()), policy);
	}

	private static GameplayWindSample sampleGameplay(A4mcPlayerRef player, BlockPos position, SamplePolicy policy) {
		if (player == null || position == null) {
			return GameplayWindSample.ZERO;
		}
		if (player.platformHandle() instanceof ServerPlayer serverPlayer) {
			return sampleGameplay(serverPlayer, position, policy);
		}
		return sampleGameplay(player.world(), position, effectiveSamplePolicyForPlayer(player, policy));
	}

	public static AeroWindSample sampleWind(ServerLevel world, Vec3 position) {
		return sampleFlow(world, position);
	}

	public static AeroWindSample sampleFlow(ServerLevel world, Vec3 position) {
		return sampleFlow(world, position, SamplePolicy.SERVER_COARSE_ONLY);
	}

	public static AeroWindSample sampleFlow(ServerLevel world, Vec3 position, SamplePolicy policy) {
		if (world == null || position == null) {
			return AeroWindSample.ZERO;
		}
		return INSTANCE.sampleWind(world.dimension(), BlockPos.containing(position), policy);
	}

	public static AeroWindSample sampleFlow(ServerPlayer player, Vec3 position) {
		return sampleFlow(player, position, SamplePolicy.SERVER_COARSE_ONLY);
	}

	public static AeroWindSample sampleFlow(ServerPlayer player, Vec3 position, SamplePolicy policy) {
		if (player == null || position == null) {
			return AeroWindSample.ZERO;
		}
		return INSTANCE.sampleWind(
				player.level().dimension(),
				BlockPos.containing(position),
				effectiveSamplePolicyForPlayer(player, policy)
		);
	}

	public static GameplayWindSample sampleGameplay(ServerLevel world, Vec3 position) {
		return sampleGameplay(world, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
	}

	public static GameplayWindSample sampleGameplay(ServerLevel world, Vec3 position, SamplePolicy policy) {
		if (world == null || position == null) {
			return GameplayWindSample.ZERO;
		}
		return INSTANCE.sampleGameplayWind(world.dimension(), BlockPos.containing(position), policy);
	}

	public static GameplayWindSample sampleGameplay(ServerPlayer player, Vec3 position) {
		return sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
	}

	public static GameplayWindSample sampleGameplay(ServerPlayer player, Vec3 position, SamplePolicy policy) {
		if (player == null || position == null) {
			return GameplayWindSample.ZERO;
		}
		return INSTANCE.sampleGameplayWind(
				player.level().dimension(),
				BlockPos.containing(position),
				effectiveSamplePolicyForPlayer(player, policy)
		);
	}

	public static AeroWindSample sampleWind(ServerLevel world, BlockPos position) {
		return sampleFlow(world, position);
	}

	public static AeroWindSample sampleFlow(ServerLevel world, BlockPos position) {
		return sampleFlow(world, position, SamplePolicy.SERVER_COARSE_ONLY);
	}

	public static AeroWindSample sampleFlow(ServerLevel world, BlockPos position, SamplePolicy policy) {
		if (world == null || position == null) {
			return AeroWindSample.ZERO;
		}
		return INSTANCE.sampleWind(world.dimension(), position, policy);
	}

	public static AeroWindSample sampleFlow(ServerPlayer player, BlockPos position) {
		return sampleFlow(player, position, SamplePolicy.SERVER_COARSE_ONLY);
	}

	public static AeroWindSample sampleFlow(ServerPlayer player, BlockPos position, SamplePolicy policy) {
		if (player == null || position == null) {
			return AeroWindSample.ZERO;
		}
		return INSTANCE.sampleWind(
				player.level().dimension(),
				position,
				effectiveSamplePolicyForPlayer(player, policy)
		);
	}

	public static GameplayWindSample sampleGameplay(ServerLevel world, BlockPos position) {
		return sampleGameplay(world, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
	}

	public static GameplayWindSample sampleGameplay(ServerLevel world, BlockPos position, SamplePolicy policy) {
		if (world == null || position == null) {
			return GameplayWindSample.ZERO;
		}
		return INSTANCE.sampleGameplayWind(world.dimension(), position, policy);
	}

	public static GameplayWindSample sampleGameplay(ServerPlayer player, BlockPos position) {
		return sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
	}

	public static GameplayWindSample sampleGameplay(ServerPlayer player, BlockPos position, SamplePolicy policy) {
		if (player == null || position == null) {
			return GameplayWindSample.ZERO;
		}
		return INSTANCE.sampleGameplayWind(
				player.level().dimension(),
				position,
				effectiveSamplePolicyForPlayer(player, policy)
		);
	}

	private static SamplePolicy effectiveSamplePolicyForPlayer(ServerPlayer player, SamplePolicy policy) {
		SamplePolicy effectivePolicy = policy == null ? SamplePolicy.SERVER_COARSE_ONLY : policy;
		if (effectivePolicy != SamplePolicy.DIAGNOSTIC_ALL_SOURCES
				&& AeroMinecraftVectors.isFastPlayerVelocity(player.getDeltaMovement())) {
			return SamplePolicy.SERVER_COARSE_ONLY;
		}
		return effectivePolicy;
	}

	private static SamplePolicy effectiveSamplePolicyForPlayer(A4mcPlayerRef player, SamplePolicy policy) {
		SamplePolicy effectivePolicy = policy == null ? SamplePolicy.SERVER_COARSE_ONLY : policy;
		if (player != null && player.platformHandle() instanceof ServerPlayer serverPlayer) {
			return effectiveSamplePolicyForPlayer(serverPlayer, effectivePolicy);
		}
		return effectivePolicy;
	}

	private static ResourceKey<Level> toWorldKey(A4mcWorldRef world) {
		if (world == null || world.dimensionId() == null) {
			return null;
		}
		return ResourceKey.create(Registries.DIMENSION, toMinecraftId(world.dimensionId()));
	}

	private static Identifier toMinecraftId(A4mcId id) {
		return Identifier.fromNamespaceAndPath(id.namespace(), id.path());
	}

	private static A4mcId fromMinecraftId(Identifier id) {
		return new A4mcId(id.getNamespace(), id.getPath());
	}

	private AeroWindSample sampleWind(ResourceKey<Level> worldKey, BlockPos position, SamplePolicy policy) {
		synchronized (simulationStateLock) {
			return sampleWindLocked(worldKey, position, policy);
		}
	}

	private GameplayWindSample sampleGameplayWind(ResourceKey<Level> worldKey, BlockPos position, SamplePolicy policy) {
		synchronized (simulationStateLock) {
			SamplePolicy effectivePolicy = policy == null ? SamplePolicy.GAMEPLAY_SERVER_ONLY : policy;
			AeroWindSample raw = sampleWindLocked(worldKey, position, effectivePolicy);
			AeroWindSample coarse = raw.sourceLevel() == AeroWindSample.Level.L2
					? sampleCoarseWindLocked(worldKey, position)
					: raw;
			return GameplayWindSample.from(raw, coarse);
		}
	}

	public void onChunkLoad(ServerLevel world, LevelChunk chunk) {
		runMainThreadCallbackProfiledUnlocked(CALLBACK_PHASE_CHUNK_LOAD, () -> {
			if (!shouldRunServerAuthoritativeL2()) {
				return;
			}
			worldMirror.onChunkLoad(world, chunk);
			if (isRuntimeChunkTracked(world.dimension(), chunk.getPos())) {
				submitWorldDeltaToSimulation(new NativeSimulationBridge.WorldDelta(
						NativeSimulationBridge.WORLD_DELTA_CHUNK_LOADED,
						chunk.getPos().getMinBlockX(),
						world.getMinY(),
						chunk.getPos().getMinBlockZ(),
						world.dimension().identifier().hashCode(),
						0,
						0,
						0,
						0.0f,
						0.0f,
						0.0f,
						0.0f
				));
				queueResidentBrickStaticRefreshForChunk(world.dimension(), chunk.getPos());
			}
		});
	}

	public void onChunkUnload(ServerLevel world, LevelChunk chunk) {
		runMainThreadCallbackProfiledUnlocked(CALLBACK_PHASE_CHUNK_UNLOAD, () -> {
			if (!shouldRunServerAuthoritativeL2()) {
				return;
			}
			worldMirror.onChunkUnload(world, chunk.getPos());
			if (isRuntimeChunkTracked(world.dimension(), chunk.getPos())) {
				submitWorldDeltaToSimulation(new NativeSimulationBridge.WorldDelta(
						NativeSimulationBridge.WORLD_DELTA_CHUNK_UNLOADED,
						chunk.getPos().getMinBlockX(),
						world.getMinY(),
						chunk.getPos().getMinBlockZ(),
						world.dimension().identifier().hashCode(),
						0,
						0,
						0,
						0.0f,
						0.0f,
						0.0f,
						0.0f
				));
				queueResidentBrickStaticRefreshForChunk(world.dimension(), chunk.getPos());
			}
		});
	}

	private void queueResidentBrickStaticRefreshForChunk(ResourceKey<Level> worldKey, ChunkPos chunkPos) {
		if (!isRuntimeChunkTracked(worldKey, chunkPos)) {
			return;
		}
		synchronized (pendingResidentBrickStaticRefreshesLock) {
			pendingResidentBrickStaticRefreshes.add(new ChunkResidentBrickRefreshRequest(worldKey, chunkPos.x, chunkPos.z));
			pendingResidentBrickStaticRefreshCount = pendingResidentBrickStaticRefreshes.size();
		}
	}

	private void refreshResidentBrickStaticsForChunk(ServerLevel world, ChunkPos chunkPos) {
		long serviceId = simulationServiceId;
		if (serviceId == 0L || !simulationBridge.isLoaded()) {
			return;
		}
		long worldRuntimeKey = simulationWorldKey(world.dimension());
		int[] residentBrickCoords = simulationBridge.getBrickWorldResidentBrickCoords(serviceId, worldRuntimeKey);
		if (residentBrickCoords == null || residentBrickCoords.length == 0) {
			return;
		}
		for (int i = 0; i + 2 < residentBrickCoords.length; i += 3) {
			int brickX = residentBrickCoords[i];
			int brickY = residentBrickCoords[i + 1];
			int brickZ = residentBrickCoords[i + 2];
			if (!chunkOverlapsBrickColumn(chunkPos, brickX, brickZ)) {
				continue;
			}
			uploadResidentBrickStaticFromMirror(world.dimension(), brickX, brickY, brickZ);
		}
	}

	public void onBlockEntityLoad(BlockEntity blockEntity, ServerLevel world) {
		runMainThreadCallbackProfiledUnlocked(CALLBACK_PHASE_BLOCK_ENTITY_LOAD, () -> {
			if (!shouldRunServerAuthoritativeL2()) {
				return;
			}
			worldMirror.onBlockEntityLoad(blockEntity, world);
			BlockPos pos = blockEntity.getBlockPos();
			if (!isRuntimeBlockTracked(world.dimension(), pos)) {
				return;
			}
			submitWorldDeltaToSimulation(new NativeSimulationBridge.WorldDelta(
					NativeSimulationBridge.WORLD_DELTA_BLOCK_ENTITY_LOADED,
					pos.getX(),
					pos.getY(),
					pos.getZ(),
					world.dimension().identifier().hashCode(),
					blockEntity.getType().toString().hashCode(),
					0,
					0,
					0.0f,
					0.0f,
					0.0f,
					0.0f
			));
		});
	}

	public void onBlockEntityUnload(BlockEntity blockEntity, ServerLevel world) {
		runMainThreadCallbackProfiledUnlocked(CALLBACK_PHASE_BLOCK_ENTITY_UNLOAD, () -> {
			if (!shouldRunServerAuthoritativeL2()) {
				return;
			}
			worldMirror.onBlockEntityUnload(blockEntity, world);
			BlockPos pos = blockEntity.getBlockPos();
			if (!isRuntimeBlockTracked(world.dimension(), pos)) {
				return;
			}
			submitWorldDeltaToSimulation(new NativeSimulationBridge.WorldDelta(
					NativeSimulationBridge.WORLD_DELTA_BLOCK_ENTITY_UNLOADED,
					pos.getX(),
					pos.getY(),
					pos.getZ(),
					world.dimension().identifier().hashCode(),
					blockEntity.getType().toString().hashCode(),
					0,
					0,
					0.0f,
					0.0f,
					0.0f,
					0.0f
			));
		});
	}

	public void onWorldUnload(ServerLevel world) {
		runMainThreadCallbackProfiled(CALLBACK_PHASE_WORLD_UNLOAD, () -> {
			worldMirror.onLevelUnload(world);
			backgroundMetGrids.remove(world.dimension());
			WorldScaleDriver driver = worldScaleDrivers.remove(world.dimension());
			saveWorldScaleDriver(world, driver);
			MesoscaleGrid grid = mesoscaleMetGrids.remove(world.dimension());
			if (grid != null) {
				grid.close();
			}
			pendingNestedFeedbackBins.remove(world.dimension());
			nestedFeedbackRuntimeDiagnostics.remove(world.dimension());
			localWeatherServerStates.remove(world.dimension());
			synchronized (simulationStateLock) {
				brickRuntimeHintWorldKeys.remove(world.dimension());
				brickRuntimeKnownWorldKeys.remove(world.dimension());
			}
			submitWorldDeltaToSimulation(new NativeSimulationBridge.WorldDelta(
					NativeSimulationBridge.WORLD_DELTA_WORLD_UNLOADED,
					0,
					0,
					0,
					world.dimension().identifier().hashCode(),
					0,
					0,
					0,
					0.0f,
					0.0f,
					0.0f,
					0.0f
			));
		});
	}

	private void onBlockChanged(ServerLevel world, BlockPos pos, BlockState oldState, BlockState newState) {
		runMainThreadCallbackProfiledUnlocked(CALLBACK_PHASE_BLOCK_CHANGED, () -> {
			if (!shouldRunServerAuthoritativeL2()) {
				return;
			}
			worldMirror.onBlockChanged(world, pos, oldState, newState);
			invalidateDynamicRegionsForBlock(world, pos);
			if (isRuntimeBlockTracked(world.dimension(), pos)) {
				submitBrickStaticCellPatchDeltas(world, pos);
			}
		});
	}

	private void submitBrickStaticCellPatchDeltas(ServerLevel world, BlockPos centerPos) {
		submitBrickStaticCellPatchDelta(world, centerPos);
		for (Direction direction : CARDINAL_DIRECTIONS) {
			submitBrickStaticCellPatchDelta(world, centerPos.relative(direction));
		}
	}

	private void submitBrickStaticCellPatchDelta(ServerLevel world, BlockPos pos) {
		long serviceId = simulationServiceId;
		if (serviceId == 0L || !isRuntimeBlockTracked(world.dimension(), pos)) {
			return;
		}
		NativeSimulationBridge.WorldDelta delta = buildBrickStaticCellPatchDelta(world, pos);
		if (delta != null) {
			submitWorldDeltaToSimulation(delta);
		}
	}

	private NativeSimulationBridge.WorldDelta buildBrickStaticCellPatchDelta(ServerLevel world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		boolean solid = isSolidObstacle(world, pos, state);
		ThermalMaterial material = thermalMaterial(state);
		byte openFaceMask = sampleStaticOpenFaceMask(world, pos, state, material);
		int packedState = (solid ? 1 : 0) | (Byte.toUnsignedInt(material == null ? SURFACE_KIND_NONE : material.kind()) << 8);
		return new NativeSimulationBridge.WorldDelta(
				NativeSimulationBridge.WORLD_DELTA_BRICK_STATIC_CELL_PATCH,
				pos.getX(),
				pos.getY(),
				pos.getZ(),
				world.dimension().identifier().hashCode(),
				packedState,
				Byte.toUnsignedInt(openFaceMask),
				0,
				sampleEmitterThermalPowerWatts(state),
				0.0f,
				0.0f,
				0.0f
		);
	}

	private void invalidateDynamicRegionsForBlock(ServerLevel world, BlockPos pos) {
		for (WindowKey key : overlappingWindowKeysForBlock(world.dimension(), pos)) {
			dynamicStore.invalidateRegion(world, key.worldKey(), key.origin());
		}
	}

	private List<WindowKey> overlappingWindowKeysForBlock(ResourceKey<Level> worldKey, BlockPos pos) {
		int[] xs = candidateCoreCoordinates(pos.getX());
		int[] ys = candidateCoreCoordinates(pos.getY());
		int[] zs = candidateCoreCoordinates(pos.getZ());
		List<WindowKey> keys = new ArrayList<>(8);
		Set<WindowKey> dedupe = new HashSet<>();
		for (int coreX : xs) {
			for (int coreY : ys) {
				for (int coreZ : zs) {
					BlockPos coreOrigin = new BlockPos(coreX, coreY, coreZ);
					BlockPos windowOrigin = windowOriginFromCoreOrigin(coreOrigin);
					if (!containsBlock(windowOrigin, pos)) {
						continue;
					}
					WindowKey key = new WindowKey(worldKey, windowOrigin);
					if (dedupe.add(key)) {
						keys.add(key);
					}
				}
			}
		}
		return keys;
	}

	private int[] candidateCoreCoordinates(int blockCoord) {
		int stride = REGION_LATTICE_STRIDE;
		int upperAligned = Math.floorDiv(blockCoord + REGION_HALO_CELLS, stride) * stride;
		return new int[]{upperAligned - stride, upperAligned};
	}

	private boolean containsBlock(BlockPos windowOrigin, BlockPos pos) {
		return containsBlock(windowOrigin, pos, GRID_SIZE);
	}

	private boolean containsBlock(BlockPos origin, BlockPos pos, int size) {
		return pos.getX() >= origin.getX() && pos.getX() < origin.getX() + size
				&& pos.getY() >= origin.getY() && pos.getY() < origin.getY() + size
				&& pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + size;
	}

	private boolean isRuntimeBlockTracked(ResourceKey<Level> worldKey, BlockPos pos) {
		if (!streamingEnabled || simulationServiceId == 0L || worldKey == null || pos == null) {
			return false;
		}
		for (WindowKey key : desiredWindowKeys) {
			if (key.worldKey().equals(worldKey) && containsBlock(key.origin(), pos)) {
				return true;
			}
		}
		return false;
	}

	private boolean isRuntimeChunkTracked(ResourceKey<Level> worldKey, ChunkPos chunkPos) {
		if (!streamingEnabled || simulationServiceId == 0L || worldKey == null || chunkPos == null) {
			return false;
		}
		int chunkMinX = chunkPos.getMinBlockX();
		int chunkMinZ = chunkPos.getMinBlockZ();
		int chunkMaxX = chunkMinX + CHUNK_SIZE;
		int chunkMaxZ = chunkMinZ + CHUNK_SIZE;
		for (WindowKey key : desiredWindowKeys) {
			if (!key.worldKey().equals(worldKey)) {
				continue;
			}
			BlockPos origin = key.origin();
			if (chunkMaxX <= origin.getX()
					|| chunkMinX >= origin.getX() + GRID_SIZE
					|| chunkMaxZ <= origin.getZ()
					|| chunkMinZ >= origin.getZ() + GRID_SIZE) {
				continue;
			}
			return true;
		}
		return false;
	}

	private boolean chunkOverlapsBrickColumn(ChunkPos chunkPos, int brickX, int brickZ) {
		int chunkMinX = chunkPos.getMinBlockX();
		int chunkMinZ = chunkPos.getMinBlockZ();
		int chunkMaxX = chunkMinX + CHUNK_SIZE;
		int chunkMaxZ = chunkMinZ + CHUNK_SIZE;
		int brickMinX = brickX * BRICK_RUNTIME_SIZE;
		int brickMinZ = brickZ * BRICK_RUNTIME_SIZE;
		int brickMaxX = brickMinX + BRICK_RUNTIME_SIZE;
		int brickMaxZ = brickMinZ + BRICK_RUNTIME_SIZE;
		return chunkMinX < brickMaxX
				&& chunkMaxX > brickMinX
				&& chunkMinZ < brickMaxZ
				&& chunkMaxZ > brickMinZ;
	}

	private byte sampleStaticOpenFaceMask(
			ServerLevel world,
			BlockPos pos,
			BlockState state,
			ThermalMaterial material
	) {
		float emitterPower = sampleEmitterThermalPowerWatts(state);
		if (material == null && emitterPower <= 0.0f) {
			return 0;
		}
		byte openFaceMask = 0;
		BlockPos.MutableBlockPos neighborCursor = new BlockPos.MutableBlockPos();
		for (Direction direction : CARDINAL_DIRECTIONS) {
			neighborCursor.set(
					pos.getX() + direction.getStepX(),
					pos.getY() + direction.getStepY(),
					pos.getZ() + direction.getStepZ()
			);
			BlockState neighborState = world.getBlockState(neighborCursor);
			boolean openFace;
			if (material != null) {
				openFace = material.atmosphericExchangeRequiresAirNeighbor()
						? neighborState.isAir()
						: !isSolidObstacle(world, neighborCursor, neighborState);
			} else {
				openFace = neighborState.isAir();
			}
			if (openFace) {
				openFaceMask = setFaceBit(openFaceMask, direction);
			}
		}
		return openFaceMask;
	}

	private int renderStatus(CommandSourceStack source) {
		feedback(
				source,
				"Render vectors=" + renderVelocityVectorsEnabled
						+ " streamlines=" + renderStreamlinesEnabled
		);
		return 1;
	}

	private int setRenderVelocityVectors(CommandSourceStack source, boolean enabled) {
		renderVelocityVectorsEnabled = enabled;
		broadcastState(source.getServer());
		feedback(source, "Render vectors " + (enabled ? "enabled" : "disabled"));
		return 1;
	}

	private int setRenderStreamlines(CommandSourceStack source, boolean enabled) {
		renderStreamlinesEnabled = enabled;
		broadcastState(source.getServer());
		feedback(source, "Render streamlines " + (enabled ? "enabled" : "disabled"));
		return 1;
	}


	private void tickL2Capture(MinecraftServer server) {
		L2CaptureSession session = activeL2CaptureSession;
		if (session == null) {
			return;
		}
		if (session.stopRequested.get() || tickCounter > session.endTick) {
			session.stopRequested.set(true);
			activeL2CaptureSession = null;
			persistL2CaptureMetadata(session);
			return;
		}
		if (tickCounter < session.nextSampleTick) {
			return;
		}
	}

	private void captureL2Frame(L2CaptureSession session, L2CapturePendingFrame pendingFrame) {
		try {
			Path framesDir = session.outputDir.resolve("frames");
			String framePrefix = String.format(Locale.ROOT, "frame_%05d_tick%d", pendingFrame.frameIndex(), pendingFrame.tick());
			List<L2CaptureFrameRegionRecord> frameRegions = new ArrayList<>(pendingFrame.regions().size());
			for (L2CapturePendingRegionFrame regionFrame : pendingFrame.regions()) {
				byte[] vxCompressed = simulationBridge.compressFloatGrid3d(
						regionFrame.vx(),
						REGION_CORE_SIZE,
						REGION_CORE_SIZE,
						REGION_CORE_SIZE,
						ANALYSIS_FLOW_VELOCITY_TOLERANCE
				);
				byte[] vyCompressed = simulationBridge.compressFloatGrid3d(
						regionFrame.vy(),
						REGION_CORE_SIZE,
						REGION_CORE_SIZE,
						REGION_CORE_SIZE,
						ANALYSIS_FLOW_VELOCITY_TOLERANCE
				);
				byte[] vzCompressed = simulationBridge.compressFloatGrid3d(
						regionFrame.vz(),
						REGION_CORE_SIZE,
						REGION_CORE_SIZE,
						REGION_CORE_SIZE,
						ANALYSIS_FLOW_VELOCITY_TOLERANCE
				);
				byte[] pressureCompressed = simulationBridge.compressFloatGrid3d(
						regionFrame.pressure(),
						REGION_CORE_SIZE,
						REGION_CORE_SIZE,
						REGION_CORE_SIZE,
						ANALYSIS_FLOW_PRESSURE_TOLERANCE
				);
				if (vxCompressed == null || vyCompressed == null || vzCompressed == null || pressureCompressed == null) {
					session.framesFailed.incrementAndGet();
					return;
				}

				BlockPos origin = regionFrame.origin();
				BlockPos coreOrigin = regionFrame.coreOrigin();
				String regionSuffix = "_ox" + origin.getX() + "_oy" + origin.getY() + "_oz" + origin.getZ();
				Path vxPath = framesDir.resolve(framePrefix + regionSuffix + "_vx.zfp");
				Path vyPath = framesDir.resolve(framePrefix + regionSuffix + "_vy.zfp");
				Path vzPath = framesDir.resolve(framePrefix + regionSuffix + "_vz.zfp");
				Path pressurePath = framesDir.resolve(framePrefix + regionSuffix + "_p.zfp");
				Path obstaclePath = framesDir.resolve(framePrefix + regionSuffix + "_obstacle.mask");
				Files.write(vxPath, vxCompressed);
				Files.write(vyPath, vyCompressed);
				Files.write(vzPath, vzCompressed);
				Files.write(pressurePath, pressureCompressed);
				Files.write(obstaclePath, regionFrame.obstacle());

				frameRegions.add(new L2CaptureFrameRegionRecord(
						origin.getX(),
						origin.getY(),
						origin.getZ(),
						coreOrigin.getX(),
						coreOrigin.getY(),
						coreOrigin.getZ(),
						session.outputDir.relativize(vxPath).toString(),
						session.outputDir.relativize(vyPath).toString(),
						session.outputDir.relativize(vzPath).toString(),
						session.outputDir.relativize(pressurePath).toString(),
						session.outputDir.relativize(obstaclePath).toString()
				));
			}

			session.frames.add(new L2CaptureFrameRecord(
					pendingFrame.frameIndex(),
					pendingFrame.tick(),
					List.copyOf(frameRegions)
			));
			session.framesWritten.incrementAndGet();
			persistL2CaptureMetadata(session);
		} catch (IOException e) {
			session.framesFailed.incrementAndGet();
		} finally {
			session.inFlightFrames.decrementAndGet();
		}
	}

	private void queueCoherentL2CaptureIfNeeded(int sampleTick) {
		L2CaptureSession session = activeL2CaptureSession;
		if (session == null || session.stopRequested.get()) {
			return;
		}
		if (sampleTick < session.nextSampleTick) {
			return;
		}
		if (session.inFlightFrames.get() >= L2_CAPTURE_MAX_PENDING_FRAMES) {
			session.framesDropped.incrementAndGet();
			session.nextSampleTick = sampleTick + session.sampleIntervalTicks;
			persistL2CaptureMetadata(session);
			return;
		}

		List<L2CapturePendingRegionFrame> pendingRegions = new ArrayList<>(session.regions.size());
		for (L2CaptureRegionSpec regionSpec : session.regions) {
			float[] fullFlowState = snapshotFullRegionFlow(regionSpec.key());
			if (fullFlowState == null) {
				session.framesFailed.incrementAndGet();
				session.nextSampleTick = sampleTick + session.sampleIntervalTicks;
				persistL2CaptureMetadata(session);
				return;
			}
			byte[] obstacle = snapshotCoreObstacleMask(regionSpec.key());
			if (obstacle == null) {
				session.framesFailed.incrementAndGet();
				session.nextSampleTick = sampleTick + session.sampleIntervalTicks;
				persistL2CaptureMetadata(session);
				return;
			}
			pendingRegions.add(new L2CapturePendingRegionFrame(
					regionSpec.key().origin(),
					regionSpec.coreOrigin(),
					extractCoreFlowChannel(fullFlowState, 0),
					extractCoreFlowChannel(fullFlowState, 1),
					extractCoreFlowChannel(fullFlowState, 2),
					extractCoreFlowChannel(fullFlowState, 3),
					obstacle
			));
		}

		int frameIndex = session.nextFrameIndex.getAndIncrement();
		session.nextSampleTick = sampleTick + session.sampleIntervalTicks;
		session.inFlightFrames.incrementAndGet();
		L2CapturePendingFrame pendingFrame = new L2CapturePendingFrame(frameIndex, sampleTick, List.copyOf(pendingRegions));
		diagnosticsExecutor.execute(() -> captureL2Frame(session, pendingFrame));
	}

	private float[] snapshotFullRegionFlow(WindowKey key) {
		synchronized (simulationStateLock) {
			if (simulationServiceId == 0L) {
				return null;
			}
			RegionRecord region = regions.get(key);
			if (region == null || !region.serviceReady) {
				return null;
			}
			float[] fullFlowState = new float[GRID_SIZE * GRID_SIZE * GRID_SIZE * NativeSimulationBridge.FLOW_STATE_CHANNELS];
			if (!simulationBridge.getRegionFlowState(
					simulationServiceId,
					simulationRegionKey(key),
					GRID_SIZE,
					GRID_SIZE,
					GRID_SIZE,
					fullFlowState
			)) {
				return null;
			}
			return fullFlowState;
		}
	}

	private float[] extractCoreFlowChannel(float[] fullFlowState, int channel) {
		float[] values = new float[REGION_CORE_SIZE * REGION_CORE_SIZE * REGION_CORE_SIZE];
		int writeIndex = 0;
		for (int x = REGION_HALO_CELLS; x < GRID_SIZE - REGION_HALO_CELLS; x++) {
			for (int y = REGION_HALO_CELLS; y < GRID_SIZE - REGION_HALO_CELLS; y++) {
				for (int z = REGION_HALO_CELLS; z < GRID_SIZE - REGION_HALO_CELLS; z++) {
					int cell = gridCellIndex(x, y, z);
					values[writeIndex++] = fullFlowState[cell * NativeSimulationBridge.FLOW_STATE_CHANNELS + channel];
				}
			}
		}
		return values;
	}

	private byte[] snapshotCoreObstacleMask(WindowKey key) {
		synchronized (simulationStateLock) {
			RegionRecord region = regions.get(key);
			if (region == null) {
				return null;
			}
			byte[] values = new byte[REGION_CORE_SIZE * REGION_CORE_SIZE * REGION_CORE_SIZE];
			int writeIndex = 0;
			for (int x = REGION_HALO_CELLS; x < GRID_SIZE - REGION_HALO_CELLS; x++) {
				int sx = x / CHUNK_SIZE;
				int lx = x % CHUNK_SIZE;
				for (int y = REGION_HALO_CELLS; y < GRID_SIZE - REGION_HALO_CELLS; y++) {
					int sy = y / CHUNK_SIZE;
					int ly = y % CHUNK_SIZE;
					for (int z = REGION_HALO_CELLS; z < GRID_SIZE - REGION_HALO_CELLS; z++) {
						int sz = z / CHUNK_SIZE;
						int lz = z % CHUNK_SIZE;
						WorldMirror.SectionSnapshot section = region.sectionAt(sx, sy, sz);
						values[writeIndex++] = section != null && section.obstacle()[localSectionCellIndex(lx, ly, lz)] >= 0.5f
								? (byte) 1
								: (byte) 0;
					}
				}
			}
			return values;
		}
	}

	public void persistL2CaptureMetadata(L2CaptureSession session) {
		if (session == null) {
			return;
		}
		Path metadataPath = session.outputDir.resolve("metadata.json");
		List<L2CaptureFrameRecord> framesSnapshot;
		synchronized (session.frames) {
			framesSnapshot = List.copyOf(session.frames);
		}
		StringBuilder builder = new StringBuilder(1 << 16);
		builder.append("{\n");
		appendJsonField(builder, "format", "a4mc_l2_capture_v2", true);
		appendJsonField(builder, "deprecated", true, true);
		appendJsonField(builder, "capture_mode", "active_core_bricks", true);
		appendJsonField(builder, "dimension_id", session.dimensionId.toString(), true);
		appendJsonField(builder, "anchor_core_x", session.anchorCoreOrigin.getX(), true);
		appendJsonField(builder, "anchor_core_y", session.anchorCoreOrigin.getY(), true);
		appendJsonField(builder, "anchor_core_z", session.anchorCoreOrigin.getZ(), true);
		appendJsonField(builder, "region_resolution", GRID_SIZE, true);
		appendJsonField(builder, "halo_cells", REGION_HALO_CELLS, true);
		appendJsonField(builder, "core_min", REGION_HALO_CELLS, true);
		appendJsonField(builder, "core_max_exclusive", GRID_SIZE - REGION_HALO_CELLS, true);
		appendJsonField(builder, "core_resolution", REGION_CORE_SIZE, true);
		appendJsonField(builder, "obstacle_mask_encoding", "u8_raw_0_1", true);
		appendJsonField(builder, "region_count", session.regions.size(), true);
		appendJsonField(builder, "layout_min_x", session.layoutMinX, true);
		appendJsonField(builder, "layout_min_y", session.layoutMinY, true);
		appendJsonField(builder, "layout_min_z", session.layoutMinZ, true);
		appendJsonField(builder, "layout_max_exclusive_x", session.layoutMaxExclusiveX, true);
		appendJsonField(builder, "layout_max_exclusive_y", session.layoutMaxExclusiveY, true);
		appendJsonField(builder, "layout_max_exclusive_z", session.layoutMaxExclusiveZ, true);
		appendJsonField(builder, "layout_resolution_x", session.layoutMaxExclusiveX - session.layoutMinX, true);
		appendJsonField(builder, "layout_resolution_y", session.layoutMaxExclusiveY - session.layoutMinY, true);
		appendJsonField(builder, "layout_resolution_z", session.layoutMaxExclusiveZ - session.layoutMinZ, true);
		appendJsonField(builder, "channels", NativeSimulationBridge.FLOW_STATE_CHANNELS, true);
		appendJsonField(builder, "velocity_tolerance", ANALYSIS_FLOW_VELOCITY_TOLERANCE, true);
		appendJsonField(builder, "pressure_tolerance", ANALYSIS_FLOW_PRESSURE_TOLERANCE, true);
		appendJsonField(builder, "start_tick", session.startTick, true);
		appendJsonField(builder, "end_tick", session.endTick, true);
		appendJsonField(builder, "duration_seconds", session.durationSeconds, true);
		appendJsonField(builder, "fps_requested", session.fps, true);
		appendJsonField(builder, "sample_interval_ticks", session.sampleIntervalTicks, true);
		appendJsonField(builder, "frames_written", session.framesWritten.get(), true);
		appendJsonField(builder, "frames_dropped", session.framesDropped.get(), true);
		appendJsonField(builder, "frames_failed", session.framesFailed.get(), true);
		int scheduledFrames = session.framesWritten.get() + session.framesDropped.get() + session.framesFailed.get();
		float effectiveFps = session.durationSeconds > 0
				? (float) session.framesWritten.get() / (float) session.durationSeconds
				: 0.0f;
		float captureRatio = scheduledFrames > 0
				? (float) session.framesWritten.get() / (float) scheduledFrames
				: 0.0f;
		appendJsonField(builder, "fps_effective", effectiveFps, true);
		appendJsonField(builder, "capture_ratio", captureRatio, true);
		appendJsonField(builder, "active", activeL2CaptureSession == session && !session.stopRequested.get(), true);
		builder.append("  \"regions\": [\n");
		for (int i = 0; i < session.regions.size(); i++) {
			L2CaptureRegionSpec region = session.regions.get(i);
			builder.append("    {\n");
			appendJsonField(builder, "origin_x", region.key().origin().getX(), true, 6);
			appendJsonField(builder, "origin_y", region.key().origin().getY(), true, 6);
			appendJsonField(builder, "origin_z", region.key().origin().getZ(), true, 6);
			appendJsonField(builder, "core_origin_x", region.coreOrigin().getX(), true, 6);
			appendJsonField(builder, "core_origin_y", region.coreOrigin().getY(), true, 6);
			appendJsonField(builder, "core_origin_z", region.coreOrigin().getZ(), false, 6);
			builder.append("    }");
			if (i + 1 < session.regions.size()) {
				builder.append(',');
			}
			builder.append('\n');
		}
		builder.append("  ],\n");
		builder.append("  \"frames\": [\n");
		for (int i = 0; i < framesSnapshot.size(); i++) {
			L2CaptureFrameRecord frame = framesSnapshot.get(i);
			builder.append("    {\n");
			appendJsonField(builder, "index", frame.index(), true, 6);
			appendJsonField(builder, "tick", frame.tick(), true, 6);
			builder.append("      \"regions\": [\n");
			for (int regionIndex = 0; regionIndex < frame.regions().size(); regionIndex++) {
				L2CaptureFrameRegionRecord region = frame.regions().get(regionIndex);
				builder.append("        {\n");
				appendJsonField(builder, "origin_x", region.originX(), true, 10);
				appendJsonField(builder, "origin_y", region.originY(), true, 10);
				appendJsonField(builder, "origin_z", region.originZ(), true, 10);
				appendJsonField(builder, "core_origin_x", region.coreOriginX(), true, 10);
				appendJsonField(builder, "core_origin_y", region.coreOriginY(), true, 10);
				appendJsonField(builder, "core_origin_z", region.coreOriginZ(), true, 10);
				appendJsonField(builder, "vx", region.vxPath(), true, 10);
				appendJsonField(builder, "vy", region.vyPath(), true, 10);
				appendJsonField(builder, "vz", region.vzPath(), true, 10);
				appendJsonField(builder, "pressure", region.pressurePath(), true, 10);
				appendJsonField(builder, "obstacle", region.obstaclePath(), false, 10);
				builder.append("        }");
				if (regionIndex + 1 < frame.regions().size()) {
					builder.append(',');
				}
				builder.append('\n');
			}
			builder.append("      ]\n");
			builder.append("    }");
			if (i + 1 < framesSnapshot.size()) {
				builder.append(',');
			}
			builder.append('\n');
		}
		builder.append("  ]\n");
		builder.append("}\n");
		try {
			Files.writeString(metadataPath, builder.toString(), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}


	private Path worldScaleDriverPath(ServerLevel world) {
		return world.getServer()
				.getWorldPath(LevelResource.ROOT)
				.resolve("aerodynamics4mc")
				.resolve("weather")
				.resolve("driver_" + storageSafeWorldId(world.dimension()) + ".properties");
	}

	private WorldScaleDriver loadWorldScaleDriver(ServerLevel world) {
		return WorldScaleDriver.loadOrCreate(worldScaleDriverPath(world), world);
	}

	private void saveWorldScaleDriver(ServerLevel world, WorldScaleDriver driver) {
		if (world == null || driver == null) {
			return;
		}
		try {
			driver.save(worldScaleDriverPath(world));
		} catch (IOException ignored) {
			// Keep runtime alive if a diagnostics/persistence write fails.
		}
	}

	private void saveAllWorldScaleDrivers(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (Map.Entry<ResourceKey<Level>, WorldScaleDriver> entry : worldScaleDrivers.entrySet()) {
			ServerLevel world = server.getLevel(entry.getKey());
			if (world == null) {
				continue;
			}
			saveWorldScaleDriver(world, entry.getValue());
		}
	}

	public static void appendJsonField(StringBuilder builder, String key, String value, boolean trailingComma) {
		appendJsonField(builder, key, value, trailingComma, 2);
	}

	public static void appendJsonField(StringBuilder builder, String key, String value, boolean trailingComma, int indentSpaces) {
		appendIndent(builder, indentSpaces);
		builder.append("  \"")
				.append(key)
				.append("\": \"")
				.append(value.replace("\\", "\\\\").replace("\"", "\\\""))
				.append("\"");
		if (trailingComma) {
			builder.append(',');
		}
		builder.append('\n');
	}

	private void appendJsonField(StringBuilder builder, String key, int value, boolean trailingComma) {
		appendJsonField(builder, key, value, trailingComma, 2);
	}

	private void appendJsonField(StringBuilder builder, String key, int value, boolean trailingComma, int indentSpaces) {
		appendIndent(builder, indentSpaces);
		builder.append('"')
				.append(key)
				.append("\": ")
				.append(value);
		if (trailingComma) {
			builder.append(',');
		}
		builder.append('\n');
	}

	private void appendJsonField(StringBuilder builder, String key, long value, boolean trailingComma) {
		appendJsonField(builder, key, value, trailingComma, 2);
	}

	private void appendJsonField(StringBuilder builder, String key, long value, boolean trailingComma, int indentSpaces) {
		appendIndent(builder, indentSpaces);
		builder.append('"')
				.append(key)
				.append("\": ")
				.append(value);
		if (trailingComma) {
			builder.append(',');
		}
		builder.append('\n');
	}

	private void appendJsonField(StringBuilder builder, String key, float value, boolean trailingComma) {
		appendJsonField(builder, key, value, trailingComma, 2);
	}

	private void appendJsonField(StringBuilder builder, String key, float value, boolean trailingComma, int indentSpaces) {
		appendIndent(builder, indentSpaces);
		builder.append('"')
				.append(key)
				.append("\": ")
				.append(Float.toString(value));
		if (trailingComma) {
			builder.append(',');
		}
		builder.append('\n');
	}

	private void appendJsonField(StringBuilder builder, String key, boolean value, boolean trailingComma) {
		appendJsonField(builder, key, value, trailingComma, 2);
	}

	private void appendJsonField(StringBuilder builder, String key, boolean value, boolean trailingComma, int indentSpaces) {
		appendIndent(builder, indentSpaces);
		builder.append('"')
				.append(key)
				.append("\": ")
				.append(value ? "true" : "false");
		if (trailingComma) {
			builder.append(',');
		}
		builder.append('\n');
	}

	private static void appendIndent(StringBuilder builder, int indentSpaces) {
		for (int i = 0; i < indentSpaces; i++) {
			builder.append(' ');
		}
	}

	private static void appendJsonFieldLegacyContextOnly(StringBuilder builder, String key, int value, boolean trailingComma) {
		builder.append("  \"")
				.append(key)
				.append("\": ")
				.append(value);
		if (trailingComma) {
			builder.append(',');
		}
		builder.append('\n');
	}

	public static void appendJsonArray(StringBuilder builder, String key, float[] values, boolean trailingComma) {
		builder.append("  \"").append(key).append("\": [");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				builder.append(',');
			}
			builder.append(Float.toString(values[i]));
		}
		builder.append(']');
		if (trailingComma) {
			builder.append(',');
		}
		builder.append('\n');
	}

	public static void appendJsonByteArray(StringBuilder builder, String key, byte[] values, boolean trailingComma) {
		builder.append("  \"").append(key).append("\": [");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				builder.append(',');
			}
			builder.append(Byte.toUnsignedInt(values[i]));
		}
		builder.append(']');
		if (trailingComma) {
			builder.append(',');
		}
		builder.append('\n');
	}

	public void onServerTick(MinecraftServer server) {
		long tickStartNanos = System.nanoTime();
		currentServer = server;
		if (!streamingEnabled) {
			return;
		}
		if (server.isPaused()) {
			return;
		}
		tickCounter++;
		InspectionSolveSession inspectionSolveSession = activeInspectionSolveSession;
		if (inspectionSolveSession != null) {
			if (inspectionSolveSession.completed.get()) {
				activeInspectionSolveSession = null;
			}
			recordMainThreadPhase(MAIN_THREAD_PHASE_SERVICE_INIT, 0L);
			recordMainThreadPhase(MAIN_THREAD_PHASE_FOCUS, 0L);
			recordMainThreadPhase(MAIN_THREAD_PHASE_BACKGROUND_BATCH, 0L);
			recordMainThreadPhase(MAIN_THREAD_PHASE_ACTIVE_BATCH, 0L);
			recordMainThreadPhase(MAIN_THREAD_PHASE_LIVE_BUILDS, 0L);
			recordMainThreadPhase(MAIN_THREAD_PHASE_FAN_REFRESHES, 0L);
			recordMainThreadPhase(MAIN_THREAD_PHASE_COORDINATOR, 0L);
			recordMainThreadPhase(MAIN_THREAD_PHASE_FLOW_SYNC, 0L);
			recordMainThreadPhase(MAIN_THREAD_PHASE_TOTAL, System.nanoTime() - tickStartNanos);
			return;
		}
		long phaseStartNanos = System.nanoTime();
		if (shouldRunServerAuthoritativeL2()) {
			ensureSimulationServiceInitialized();
		}
		recordMainThreadPhase(MAIN_THREAD_PHASE_SERVICE_INIT, System.nanoTime() - phaseStartNanos);
		phaseStartNanos = System.nanoTime();
		updateSimulationFocus(server);
		recordMainThreadPhase(MAIN_THREAD_PHASE_FOCUS, System.nanoTime() - phaseStartNanos);
		if (tickCounter == 1 || tickCounter % MESOSCALE_REFRESH_TICKS == 0) {
			phaseStartNanos = System.nanoTime();
			pendingBackgroundRefreshBatch = captureBackgroundRefreshBatch(server);
			recordMainThreadPhase(MAIN_THREAD_PHASE_BACKGROUND_BATCH, System.nanoTime() - phaseStartNanos);
		} else {
			recordMainThreadPhase(MAIN_THREAD_PHASE_BACKGROUND_BATCH, 0L);
		}
		phaseStartNanos = System.nanoTime();
		pendingActiveRegionBatch = captureActiveRegionBatch(server);
		recordMainThreadPhase(MAIN_THREAD_PHASE_ACTIVE_BATCH, System.nanoTime() - phaseStartNanos);
		int lowPriorityBuildBudget = tickCounter % STATIC_MIRROR_LOW_PRIORITY_BUILD_INTERVAL_TICKS == 0
				? STATIC_MIRROR_LOW_PRIORITY_BUILD_BUDGET
				: 0;
		phaseStartNanos = System.nanoTime();
		worldMirror.drainLiveBuilds(
				server,
				STATIC_MIRROR_HIGH_PRIORITY_BUILD_BUDGET_PER_TICK,
				lowPriorityBuildBudget,
				this::populateMirrorSectionSnapshot
		);
		recordMainThreadPhase(MAIN_THREAD_PHASE_LIVE_BUILDS, System.nanoTime() - phaseStartNanos);
		phaseStartNanos = System.nanoTime();
		worldMirror.drainFanRefreshes(server, FAN_DUCT_REFRESH_BUDGET_PER_TICK);
		recordMainThreadPhase(MAIN_THREAD_PHASE_FAN_REFRESHES, System.nanoTime() - phaseStartNanos);
		phaseStartNanos = System.nanoTime();
		ensureSimulationCoordinatorRunning();
		recordMainThreadPhase(MAIN_THREAD_PHASE_COORDINATOR, System.nanoTime() - phaseStartNanos);

		boolean shouldSyncCoarseFlow = tickCounter % COARSE_FLOW_SYNC_INTERVAL_TICKS == 0;
		PublishedFrame frame = publishedFrame.get();
		if (frame == null || frame.regionAtlases().isEmpty()) {
			lastSyncedFlowFrameId = 0L;
			if (shouldSyncCoarseFlow) {
				phaseStartNanos = System.nanoTime();
				syncCoarseWindToPlayers(server);
				syncLocalWeatherToPlayers(server);
				recordMainThreadPhase(MAIN_THREAD_PHASE_FLOW_SYNC, System.nanoTime() - phaseStartNanos);
			} else {
				recordMainThreadPhase(MAIN_THREAD_PHASE_FLOW_SYNC, 0L);
			}
			updateSimulationRate(0);
			lastMaxFlowSpeed = 0.0f;
			recordMainThreadPhase(MAIN_THREAD_PHASE_TOTAL, System.nanoTime() - tickStartNanos);
			return;
		}

		phaseStartNanos = System.nanoTime();
		if (shouldSyncCoarseFlow) {
			syncCoarseWindToPlayers(server);
			syncLocalWeatherToPlayers(server);
		}
		if (SERVER_L2_ATLAS_STREAMING_ENABLED) {
			syncPublishedFlowToPlayers(server, frame);
		} else {
			lastFlowAtlasSyncStates.clear();
		}
		if (frame.frameId() > lastSyncedFlowFrameId) {
			lastSyncedFlowFrameId = frame.frameId();
		}
		tickL2Capture(server);
		recordMainThreadPhase(MAIN_THREAD_PHASE_FLOW_SYNC, System.nanoTime() - phaseStartNanos);
		long frameId = frame.frameId();
		int publishedSteps = 0;
		if (frameId > lastObservedPublishedFrameId) {
			long delta = frameId - lastObservedPublishedFrameId;
			simulationTicks += delta;
			publishedSteps = (int) Math.min(Integer.MAX_VALUE, delta);
		}
		if (frameId > lastObservedPublishedFrameId) {
			lastObservedPublishedFrameId = frameId;
			lastMaxFlowSpeed = frame.maxSpeed();
		}
		updateSimulationRate(publishedSteps);
		recordMainThreadPhase(MAIN_THREAD_PHASE_TOTAL, System.nanoTime() - tickStartNanos);
	}

	private void recordMainThreadPhase(int phaseIndex, long nanos) {
		lastMainThreadPhaseNanos[phaseIndex] = nanos;
		if (nanos > maxMainThreadPhaseNanos[phaseIndex]) {
			maxMainThreadPhaseNanos[phaseIndex] = nanos;
		}
	}

	private void resetMainThreadProfiling() {
		Arrays.fill(lastMainThreadPhaseNanos, 0L);
		Arrays.fill(maxMainThreadPhaseNanos, 0L);
		Arrays.fill(lastCallbackTotalNanos, 0L);
		Arrays.fill(lastCallbackLockWaitNanos, 0L);
		Arrays.fill(lastCallbackLockHeldNanos, 0L);
		Arrays.fill(maxCallbackTotalNanos, 0L);
		Arrays.fill(maxCallbackLockWaitNanos, 0L);
		Arrays.fill(maxCallbackLockHeldNanos, 0L);
	}

	public static float nanosToMillis(long nanos) {
		return nanos / 1_000_000.0f;
	}

	private void runMainThreadCallbackProfiled(int phaseIndex, Runnable runnable) {
		long startNanos = System.nanoTime();
		long acquiredNanos;
		synchronized (simulationStateLock) {
			acquiredNanos = System.nanoTime();
			try {
				runnable.run();
			} finally {
				long endNanos = System.nanoTime();
				recordCallbackPhase(phaseIndex, endNanos - startNanos, acquiredNanos - startNanos, endNanos - acquiredNanos);
			}
		}
	}

	private void runMainThreadCallbackProfiledUnlocked(int phaseIndex, Runnable runnable) {
		long startNanos = System.nanoTime();
		try {
			runnable.run();
		} finally {
			long endNanos = System.nanoTime();
			recordCallbackPhase(phaseIndex, endNanos - startNanos, 0L, endNanos - startNanos);
		}
	}

	private void recordCallbackPhase(int phaseIndex, long totalNanos, long waitNanos, long heldNanos) {
		lastCallbackTotalNanos[phaseIndex] = totalNanos;
		lastCallbackLockWaitNanos[phaseIndex] = waitNanos;
		lastCallbackLockHeldNanos[phaseIndex] = heldNanos;
		if (totalNanos > maxCallbackTotalNanos[phaseIndex]) {
			maxCallbackTotalNanos[phaseIndex] = totalNanos;
		}
		if (waitNanos > maxCallbackLockWaitNanos[phaseIndex]) {
			maxCallbackLockWaitNanos[phaseIndex] = waitNanos;
		}
		if (heldNanos > maxCallbackLockHeldNanos[phaseIndex]) {
			maxCallbackLockHeldNanos[phaseIndex] = heldNanos;
		}
	}


	private void stopStreaming(MinecraftServer server, boolean persistDynamicRegions) {
		runtimeGeneration.incrementAndGet();
		L2CaptureSession captureSession = activeL2CaptureSession;
		if (captureSession != null) {
			captureSession.stopRequested.set(true);
			activeL2CaptureSession = null;
			persistL2CaptureMetadata(captureSession);
		}
		streamingEnabled = false;
		stopSimulationCoordinator();
		saveAllWorldScaleDrivers(server);
		tickCounter = 0;
		simulationStepBudget.set(0);
		simulationTicks = 0L;
		lastObservedPublishedFrameId = 0L;
		lastSyncedFlowFrameId = 0L;
		lastPublishedFrameTick = Integer.MIN_VALUE;
		secondWindowTotalTicks = 0;
		secondWindowSimulationTicks = 0;
		simulationTicksPerSecond = 0.0f;
		lastMaxFlowSpeed = 0.0f;
		lastSimulationFocusX = Integer.MIN_VALUE;
		lastSimulationFocusY = Integer.MIN_VALUE;
		lastSimulationFocusZ = Integer.MIN_VALUE;
		synchronized (simulationStateLock) {
			for (Map.Entry<WindowKey, RegionRecord> entry : regions.entrySet()) {
				RegionRecord region = entry.getValue();
				if (region.attached()) {
					detachRegionWindow(entry.getKey(), region);
					deactivateWindow(entry.getKey(), region, persistDynamicRegions, persistDynamicRegions);
				} else {
					deactivateWindowRegionInSimulation(entry.getKey());
				}
			}
			regions.clear();
		}
		backgroundMetGrids.clear();
		for (MesoscaleGrid grid : mesoscaleMetGrids.values()) {
			grid.close();
		}
		mesoscaleMetGrids.clear();
		localWeatherServerStates.clear();
		pendingNestedFeedbackBins.clear();
		nestedFeedbackRuntimeDiagnostics.clear();
		desiredWindowKeys = Set.of();
		desiredSolveWindowKeys = Set.of();
		anchorDesiredWindowKeys = Set.of();
		anchorSolveWindowKeys = Set.of();
		shellWindowRetainUntilTick.clear();
		solveWindowRetainUntilTick.clear();
		playerMotionAnchorStates.clear();
		lastCoarseWindSyncStates.clear();
		lastLocalWeatherSyncStates.clear();
		lastFlowAtlasSyncStates.clear();
		localWeatherServerStates.clear();
		zeroAtlasHoldUntilTick.clear();
		mirrorOnlyPrewarmedWindowKeys.clear();
		mirrorOnlyUploadedBrickStaticSignatures.clear();
		uploadedBrickDynamicSeedSignatures.clear();
		brickRuntimeHintWorldKeys.clear();
		brickRuntimeKnownWorldKeys.clear();
		activePlayerProbeRequests = List.of();
		activeEntitySampleRequests = List.of();
		worldEnvironmentSnapshots = Map.of();
		publishedFrame.set(null);
		publishedPlayerProbes.set(Map.of());
		publishedEntitySamples.set(Map.of());
		synchronized (pendingWorldDeltasLock) {
			pendingWorldDeltas.clear();
			pendingWorldDeltaCount = 0;
		}
		synchronized (pendingResidentBrickStaticRefreshesLock) {
			pendingResidentBrickStaticRefreshes.clear();
			pendingResidentBrickStaticRefreshCount = 0;
		}
		waitForSolverIdle();
		releaseSimulationService();
	}

	private BackgroundRefreshBatch captureBackgroundRefreshBatch(MinecraftServer server) {
		Map<ResourceKey<Level>, BackgroundRefreshRequest> requests = new HashMap<>();
		for (ServerLevel world : server.getAllLevels()) {
			List<ServerPlayer> players = world.players();
			if (players.isEmpty()) {
				continue;
			}
			double sumX = 0.0;
			double sumZ = 0.0;
			for (ServerPlayer player : players) {
				sumX += player.getX();
				sumZ += player.getZ();
			}
			int focusX = Mth.floor(sumX / players.size());
			int focusZ = Mth.floor(sumZ / players.size());
			requests.put(
					world.dimension(),
					new BackgroundRefreshRequest(
							world,
							new BlockPos(focusX, world.getSeaLevel(), focusZ),
							new WorldEnvironmentSnapshot(
									world.getDayTime(),
									world.getRainLevel(1.0f),
									world.getThunderLevel(1.0f),
									world.getSeaLevel()
							)
					)
			);
		}
		return new BackgroundRefreshBatch(tickCounter, Map.copyOf(requests));
	}

	private List<BackgroundRefreshWork> prepareBackgroundRefreshBatch(BackgroundRefreshBatch batch) {
		if (batch == null) {
			return List.of();
		}
		List<MesoscaleGrid> gridsToClose = new ArrayList<>();
		List<BackgroundRefreshWork> works = new ArrayList<>(batch.requests().size());
		Set<ResourceKey<Level>> activeWorldKeys = new HashSet<>(batch.requests().keySet());
		synchronized (simulationStateLock) {
			backgroundMetGrids.keySet().removeIf(worldKey -> !activeWorldKeys.contains(worldKey));
			pendingNestedFeedbackBins.keySet().removeIf(worldKey -> !activeWorldKeys.contains(worldKey));
			nestedFeedbackRuntimeDiagnostics.keySet().removeIf(worldKey -> !activeWorldKeys.contains(worldKey));
			Iterator<Map.Entry<ResourceKey<Level>, MesoscaleGrid>> mesoscaleIterator = mesoscaleMetGrids.entrySet().iterator();
			while (mesoscaleIterator.hasNext()) {
				Map.Entry<ResourceKey<Level>, MesoscaleGrid> entry = mesoscaleIterator.next();
				if (activeWorldKeys.contains(entry.getKey())) {
					continue;
				}
				gridsToClose.add(entry.getValue());
				mesoscaleIterator.remove();
			}

			for (Map.Entry<ResourceKey<Level>, BackgroundRefreshRequest> entry : batch.requests().entrySet()) {
				ResourceKey<Level> worldKey = entry.getKey();
				works.add(new BackgroundRefreshWork(
						batch.tickCounter(),
						worldKey,
						entry.getValue(),
						worldScaleDrivers.get(worldKey),
						backgroundMetGrids.get(worldKey),
						mesoscaleMetGrids.get(worldKey),
						pendingNestedFeedbackBins.get(worldKey)
				));
			}
		}
		for (MesoscaleGrid grid : gridsToClose) {
			grid.close();
		}
		return works;
	}

	private BackgroundRefreshTiming applyPreparedBackgroundRefreshWork(BackgroundRefreshWork work) {
		if (work == null || work.request() == null) {
			return BackgroundRefreshTiming.EMPTY;
		}
		ResourceKey<Level> worldKey = work.worldKey();
		BackgroundRefreshRequest request = work.request();
		WorldScaleDriver driver = work.driver();
		boolean newDriver = driver == null;
		if (newDriver) {
			driver = loadWorldScaleDriver(request.world());
		}
		BackgroundMetGrid grid = work.backgroundGrid();
		boolean newBackgroundGrid = grid == null;
		if (newBackgroundGrid) {
			grid = new BackgroundMetGrid(
					BACKGROUND_MET_CELL_SIZE_BLOCKS,
					BACKGROUND_MET_RADIUS_CELLS,
					BACKGROUND_MET_REFRESH_TICKS
			);
		}
		MesoscaleGrid mesoscale = work.mesoscaleGrid();
		boolean newMesoscaleGrid = mesoscale == null;
		if (newMesoscaleGrid) {
			mesoscale = new MesoscaleGrid(
					MESOSCALE_MET_CELL_SIZE_BLOCKS,
					MESOSCALE_MET_RADIUS_CELLS,
					MESOSCALE_MET_LAYER_HEIGHT_BLOCKS,
					MESOSCALE_MET_MAX_LAYERS,
					MESOSCALE_STEP_SECONDS,
					MESOSCALE_FORCING_REBUILD_TICKS
			);
		}

		long phaseStartNanos = System.nanoTime();
		MesoscaleGrid.DiagnosticsSummary diagnosticsSummary = mesoscale.diagnosticsSummary(request.focus());
		long diagnosticsNanos = System.nanoTime() - phaseStartNanos;
		phaseStartNanos = System.nanoTime();
		driver.advance(
				request.world(),
				request.environmentSnapshot(),
				work.tickCounter(),
				SOLVER_STEP_SECONDS,
				diagnosticsSummary,
				mesoscale,
				request.focus()
		);
		long driverNanos = System.nanoTime() - phaseStartNanos;
		phaseStartNanos = System.nanoTime();
		grid.refresh(
				request.world(),
				request.environmentSnapshot(),
				request.focus(),
				work.tickCounter(),
				SOLVER_STEP_SECONDS,
				seedTerrainProvider,
				driver
		);
		long l0Nanos = System.nanoTime() - phaseStartNanos;
		phaseStartNanos = System.nanoTime();
		mesoscale.refresh(request.world(), request.focus(), work.tickCounter(), SOLVER_STEP_SECONDS, seedTerrainProvider, grid);
		long l1Nanos = System.nanoTime() - phaseStartNanos;
		phaseStartNanos = System.nanoTime();
		mesoscale.applyPendingNestedFeedback(drainPendingNestedFeedback(work.feedbackQueue()));
		long feedbackNanos = System.nanoTime() - phaseStartNanos;

		if (newDriver || newBackgroundGrid || newMesoscaleGrid) {
			WorldScaleDriver finalDriver = driver;
			BackgroundMetGrid finalGrid = grid;
			MesoscaleGrid finalMesoscale = mesoscale;
			synchronized (simulationStateLock) {
				worldScaleDrivers.putIfAbsent(worldKey, finalDriver);
				backgroundMetGrids.putIfAbsent(worldKey, finalGrid);
				mesoscaleMetGrids.putIfAbsent(worldKey, finalMesoscale);
			}
		}
		return new BackgroundRefreshTiming(diagnosticsNanos, driverNanos, l0Nanos, l1Nanos, feedbackNanos);
	}

	private void applyBackgroundRefreshBatch(BackgroundRefreshBatch batch) {
		long refreshStartNanos = System.nanoTime();
		long diagnosticsNanos = 0L;
		long driverNanos = 0L;
		long l0Nanos = 0L;
		long l1Nanos = 0L;
		long feedbackNanos = 0L;
		List<BackgroundRefreshWork> works = prepareBackgroundRefreshBatch(batch);
		for (BackgroundRefreshWork work : works) {
			BackgroundRefreshTiming timing = applyPreparedBackgroundRefreshWork(work);
			diagnosticsNanos += timing.diagnosticsNanos();
			driverNanos += timing.driverNanos();
			l0Nanos += timing.l0Nanos();
			l1Nanos += timing.l1Nanos();
			feedbackNanos += timing.feedbackNanos();
		}
		lastBackgroundRefreshAppliedTick = batch == null ? Integer.MIN_VALUE : batch.tickCounter();
		lastBackgroundRefreshWorldCount = works.size();
		lastBackgroundRefreshNanos = System.nanoTime() - refreshStartNanos;
		lastBackgroundDiagnosticsNanos = diagnosticsNanos;
		lastBackgroundDriverNanos = driverNanos;
		lastBackgroundL0Nanos = l0Nanos;
		lastBackgroundL1Nanos = l1Nanos;
		lastBackgroundFeedbackNanos = feedbackNanos;
	}

	private List<MesoscaleGrid.NestedFeedbackBin> drainPendingNestedFeedback(ConcurrentLinkedQueue<MesoscaleGrid.NestedFeedbackBin> queue) {
		if (queue == null || queue.isEmpty()) {
			return List.of();
		}
		List<MesoscaleGrid.NestedFeedbackBin> drained = new ArrayList<>();
		MesoscaleGrid.NestedFeedbackBin bin;
		while ((bin = queue.poll()) != null) {
			drained.add(bin);
		}
		return drained;
	}

	private List<MesoscaleGrid.NestedFeedbackBin> drainPendingNestedFeedback(ResourceKey<Level> worldKey) {
		ConcurrentLinkedQueue<MesoscaleGrid.NestedFeedbackBin> queue = pendingNestedFeedbackBins.get(worldKey);
		return drainPendingNestedFeedback(queue);
	}

	public static void ensureSimulationServiceInitialized() {
		if (INSTANCE.simulationServiceId != 0L || !simulationBridge.isLoaded()) {
			return;
		}
		INSTANCE.simulationServiceId = simulationBridge.createService();
		if (INSTANCE.simulationServiceId == 0L) {
			String error = simulationBridge.lastError();
			if (error != null && !error.isBlank() && !"not_loaded".equals(error)) {
				INSTANCE.lastSolverError = error;
			}
		}
	}

	private void releaseSimulationService() {
		if (simulationServiceId == 0L) {
			return;
		}
		simulationBridge.releaseService(simulationServiceId);
		simulationServiceId = 0L;
		for (RegionRecord region : regions.values()) {
			region.nestedFeedbackLayoutServiceId = 0L;
		}
	}

	private void updateSimulationFocus(MinecraftServer server) {
		if (simulationServiceId == 0L) {
			return;
		}
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return;
		}
		double sumX = 0.0;
		double sumY = 0.0;
		double sumZ = 0.0;
		for (ServerPlayer player : players) {
			sumX += player.getX();
			sumY += player.getY();
			sumZ += player.getZ();
		}
		int focusX = Mth.floor(sumX / players.size());
		int focusY = Mth.floor(sumY / players.size());
		int focusZ = Mth.floor(sumZ / players.size());
		if (focusX == lastSimulationFocusX && focusY == lastSimulationFocusY && focusZ == lastSimulationFocusZ) {
			return;
		}
		lastSimulationFocusX = focusX;
		lastSimulationFocusY = focusY;
		lastSimulationFocusZ = focusZ;
	}

	private void submitWorldDeltaToSimulation(NativeSimulationBridge.WorldDelta delta) {
		if (simulationServiceId == 0L || delta == null) {
			return;
		}
		synchronized (pendingWorldDeltasLock) {
			WorldDeltaQueueKey key = worldDeltaQueueKey(delta);
			pendingWorldDeltas.remove(key);
			pendingWorldDeltas.put(key, delta);
			pendingWorldDeltaCount = pendingWorldDeltas.size();
		}
	}

	private WorldDeltaQueueKey worldDeltaQueueKey(NativeSimulationBridge.WorldDelta delta) {
		int group = switch (delta.type()) {
			case NativeSimulationBridge.WORLD_DELTA_CHUNK_LOADED,
			     NativeSimulationBridge.WORLD_DELTA_CHUNK_UNLOADED -> NativeSimulationBridge.WORLD_DELTA_CHUNK_LOADED;
			case NativeSimulationBridge.WORLD_DELTA_BLOCK_ENTITY_LOADED,
			     NativeSimulationBridge.WORLD_DELTA_BLOCK_ENTITY_UNLOADED ->
					NativeSimulationBridge.WORLD_DELTA_BLOCK_ENTITY_LOADED;
			default -> delta.type();
		};
		return new WorldDeltaQueueKey(group, delta.x(), delta.y(), delta.z(), delta.data0());
	}

	private int flushPendingWorldDeltas(int maxBatches) {
		long startNanos = System.nanoTime();
		int submittedCount = 0;
		long serviceId = simulationServiceId;
		if (serviceId == 0L) {
			lastWorldDeltaFlushCount = 0;
			lastWorldDeltaFlushNanos = System.nanoTime() - startNanos;
			return 0;
		}
		int batches = Math.max(0, maxBatches);
		for (int batchIndex = 0; batchIndex < batches; batchIndex++) {
			NativeSimulationBridge.WorldDelta[] batch;
			synchronized (pendingWorldDeltasLock) {
				if (pendingWorldDeltas.isEmpty()) {
					pendingWorldDeltaCount = 0;
					break;
				}
				int batchSize = Math.min(WORLD_DELTA_FLUSH_BATCH_SIZE, pendingWorldDeltas.size());
				batch = new NativeSimulationBridge.WorldDelta[batchSize];
				Iterator<Map.Entry<WorldDeltaQueueKey, NativeSimulationBridge.WorldDelta>> iterator =
						pendingWorldDeltas.entrySet().iterator();
				for (int i = 0; i < batchSize; i++) {
					Map.Entry<WorldDeltaQueueKey, NativeSimulationBridge.WorldDelta> entry = iterator.next();
					batch[i] = entry.getValue();
					iterator.remove();
				}
				pendingWorldDeltaCount = pendingWorldDeltas.size();
			}
			if (!simulationBridge.submitWorldDeltas(serviceId, batch)) {
				lastSolverError = simulationBridge.lastError();
			} else {
				submittedCount += batch.length;
			}
		}
		lastWorldDeltaFlushCount = submittedCount;
		lastWorldDeltaFlushNanos = System.nanoTime() - startNanos;
		return submittedCount;
	}

	private int applyPendingResidentBrickStaticRefreshesIfNeeded(int maxRefreshes) {
		long startNanos = System.nanoTime();
		int refreshedCount = 0;
		long serviceId = simulationServiceId;
		MinecraftServer server = currentServer;
		if (serviceId == 0L || server == null) {
			lastResidentBrickStaticRefreshCount = 0;
			lastResidentBrickStaticRefreshNanos = System.nanoTime() - startNanos;
			return 0;
		}
		int budget = Math.max(0, maxRefreshes);
		while (refreshedCount < budget) {
			ChunkResidentBrickRefreshRequest request;
			synchronized (pendingResidentBrickStaticRefreshesLock) {
				Iterator<ChunkResidentBrickRefreshRequest> iterator = pendingResidentBrickStaticRefreshes.iterator();
				if (iterator.hasNext()) {
					request = iterator.next();
					iterator.remove();
				} else {
					request = null;
				}
				pendingResidentBrickStaticRefreshCount = pendingResidentBrickStaticRefreshes.size();
			}
			if (request == null) {
				break;
			}
			ServerLevel world = server.getLevel(request.worldKey());
			if (world == null) {
				continue;
			}
			refreshResidentBrickStaticsForChunk(world, new ChunkPos(request.chunkX(), request.chunkZ()));
			refreshedCount++;
		}
		lastResidentBrickStaticRefreshCount = refreshedCount;
		lastResidentBrickStaticRefreshNanos = System.nanoTime() - startNanos;
		return refreshedCount;
	}

	private static BackgroundMetGrid.Sample sampleBackgroundMet(ResourceKey<Level> worldKey, BlockPos pos) {
		BackgroundMetGrid grid = backgroundMetGrids.get(worldKey);
		return grid == null ? null : grid.sample(pos);
	}

	private static MesoscaleGrid.Sample sampleMesoscaleMet(ResourceKey<Level> worldKey, BlockPos pos) {
		MesoscaleGrid grid = mesoscaleMetGrids.get(worldKey);
		return grid == null ? null : grid.sample(pos);
	}


	private List<MesoscaleGrid> snapshotMesoscaleGrids() {
		synchronized (simulationStateLock) {
			return new ArrayList<>(mesoscaleMetGrids.values());
		}
	}

	private void runMesoscaleStepCycle() {
		List<MesoscaleGrid> grids = snapshotMesoscaleGrids();
		for (MesoscaleGrid grid : grids) {
			grid.runPendingSteps();
		}
	}

	public void enableStreamingOnServerStart(MinecraftServer server) {
		currentServer = server;
		streamingEnabled = true;
		resetMainThreadProfiling();
		lastSyncedFlowFrameId = 0L;
		lastCoarseWindSyncStates.clear();
		lastLocalWeatherSyncStates.clear();
		lastFlowAtlasSyncStates.clear();
		localWeatherServerStates.clear();
		zeroAtlasHoldUntilTick.clear();
		lastSolverError = "";
		lastCoordinatorError = "";
		lastCoordinatorNoPublishReason = "";
	}

	public void shutdownAll(MinecraftServer server) {
		stopStreaming(server, true);
		clientLocalL2Players.clear();
		synchronized (simulationStateLock) {
			worldMirror.close();
		}
		dynamicStore.close();
		currentServer = null;
	}

	public static final class L2CaptureSession {
		final ResourceKey<Level> worldKey;
		final Identifier dimensionId;
		final BlockPos anchorCoreOrigin;
		final List<L2CaptureRegionSpec> regions;
		final Path outputDir;
		final int startTick;
		final int endTick;
		final int durationSeconds;
		final int fps;
		final int sampleIntervalTicks;
		final int layoutMinX;
		final int layoutMinY;
		final int layoutMinZ;
		final int layoutMaxExclusiveX;
		final int layoutMaxExclusiveY;
		final int layoutMaxExclusiveZ;
		final AtomicBoolean stopRequested = new AtomicBoolean(false);
		final AtomicInteger nextFrameIndex = new AtomicInteger(0);
		final AtomicInteger inFlightFrames = new AtomicInteger(0);
		final AtomicInteger framesWritten = new AtomicInteger(0);
		final AtomicInteger framesDropped = new AtomicInteger(0);
		final AtomicInteger framesFailed = new AtomicInteger(0);
		final List<L2CaptureFrameRecord> frames = Collections.synchronizedList(new ArrayList<>());
		volatile int nextSampleTick;

		L2CaptureSession(
				ResourceKey<Level> worldKey,
				Identifier dimensionId,
				BlockPos anchorCoreOrigin,
				List<L2CaptureRegionSpec> regions,
				Path outputDir,
				int startTick,
				int endTick,
				int durationSeconds,
				int fps,
				int sampleIntervalTicks
		) {
			this.worldKey = worldKey;
			this.dimensionId = dimensionId;
			this.anchorCoreOrigin = anchorCoreOrigin.immutable();
			this.regions = List.copyOf(regions);
			this.outputDir = outputDir;
			this.startTick = startTick;
			this.endTick = endTick;
			this.durationSeconds = durationSeconds;
			this.fps = fps;
			this.sampleIntervalTicks = sampleIntervalTicks;
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (L2CaptureRegionSpec region : this.regions) {
				BlockPos coreOrigin = region.coreOrigin();
				minX = Math.min(minX, coreOrigin.getX());
				minY = Math.min(minY, coreOrigin.getY());
				minZ = Math.min(minZ, coreOrigin.getZ());
				maxX = Math.max(maxX, coreOrigin.getX() + REGION_CORE_SIZE);
				maxY = Math.max(maxY, coreOrigin.getY() + REGION_CORE_SIZE);
				maxZ = Math.max(maxZ, coreOrigin.getZ() + REGION_CORE_SIZE);
			}
			this.layoutMinX = minX;
			this.layoutMinY = minY;
			this.layoutMinZ = minZ;
			this.layoutMaxExclusiveX = maxX;
			this.layoutMaxExclusiveY = maxY;
			this.layoutMaxExclusiveZ = maxZ;
			this.nextSampleTick = startTick;
		}
	}

	public record L2CaptureRegionSpec(
			WindowKey key,
			BlockPos coreOrigin
	) {
	}

	private record L2CaptureFrameRecord(
			int index,
			int tick,
			List<L2CaptureFrameRegionRecord> regions
	) {
	}

	private record L2CaptureFrameRegionRecord(
			int originX,
			int originY,
			int originZ,
			int coreOriginX,
			int coreOriginY,
			int coreOriginZ,
			String vxPath,
			String vyPath,
			String vzPath,
			String pressurePath,
			String obstaclePath
	) {
	}

	private record L2CapturePendingFrame(
			int frameIndex,
			int tick,
			List<L2CapturePendingRegionFrame> regions
	) {
	}

	private record L2CapturePendingRegionFrame(
			BlockPos origin,
			BlockPos coreOrigin,
			float[] vx,
			float[] vy,
			float[] vz,
			float[] pressure,
			byte[] obstacle
	) {
	}

	private ActiveRegionBatch captureActiveRegionBatch(MinecraftServer server) {
		List<PlayerRegionAnchor> anchors = new ArrayList<>();
		List<PlayerProbeRequest> probeRequests = new ArrayList<>();
		Map<ResourceKey<Level>, WorldEnvironmentSnapshot> snapshots = new HashMap<>();
		Set<UUID> observedPlayers = new HashSet<>();
		for (ServerLevel world : server.getAllLevels()) {
			List<ServerPlayer> players = world.players();
			if (players.isEmpty()) {
				continue;
			}
			ResourceKey<Level> worldKey = world.dimension();
			snapshots.put(
					worldKey,
					new WorldEnvironmentSnapshot(
							world.getDayTime(),
							world.getRainLevel(1.0f),
							world.getThunderLevel(1.0f),
							world.getSeaLevel()
					)
			);
			for (ServerPlayer player : players) {
				UUID playerId = player.getUUID();
				BlockPos playerPos = player.blockPosition().immutable();
				BlockPos coreOrigin = coreOriginForPosition(playerPos);
				observedPlayers.add(playerId);
				if (!SERVER_AUTHORITATIVE_L2_ENABLED || usesClientLocalL2(player)) {
					playerMotionAnchorStates.remove(playerId);
					continue;
				}
				anchors.add(new PlayerRegionAnchor(worldKey, coreOrigin, playerPos));
				appendPlayerMotionTrailAnchors(playerId, worldKey, playerPos, coreOrigin, anchors);
				playerMotionAnchorStates.put(
						playerId,
						new PlayerMotionAnchorState(worldKey, coreOrigin, tickCounter)
				);
				probeRequests.add(new PlayerProbeRequest(playerId, worldKey, playerPos));
			}
		}
		playerMotionAnchorStates.entrySet().removeIf(entry -> {
			PlayerMotionAnchorState state = entry.getValue();
			return !observedPlayers.contains(entry.getKey()) || tickCounter - state.lastSeenTick() > SOLVE_WINDOW_RETENTION_TICKS;
		});
		Set<WindowKey> activeKeys = activeRegionKeys(anchors);
		List<EntitySampleRequest> entityRequests = ENTITY_SAMPLE_COLLECTION_ENABLED
				? collectEntitySampleRequests(server, activeKeys)
				: List.of();
		return new ActiveRegionBatch(
				tickCounter,
				List.copyOf(anchors),
				List.copyOf(probeRequests),
				List.copyOf(entityRequests),
				Map.copyOf(snapshots)
		);
	}

	private void appendPlayerMotionTrailAnchors(
			UUID playerId,
			ResourceKey<Level> worldKey,
			BlockPos playerPos,
			BlockPos currentCoreOrigin,
			List<PlayerRegionAnchor> anchors
	) {
		PlayerMotionAnchorState previous = playerMotionAnchorStates.get(playerId);
		if (previous == null || !previous.worldKey().equals(worldKey)) {
			return;
		}
		BlockPos previousCoreOrigin = previous.coreOrigin();
		if (previousCoreOrigin.equals(currentCoreOrigin)) {
			return;
		}
		int previousBrickX = Math.floorDiv(previousCoreOrigin.getX(), REGION_LATTICE_STRIDE);
		int previousBrickY = Math.floorDiv(previousCoreOrigin.getY(), REGION_LATTICE_STRIDE);
		int previousBrickZ = Math.floorDiv(previousCoreOrigin.getZ(), REGION_LATTICE_STRIDE);
		int currentBrickX = Math.floorDiv(currentCoreOrigin.getX(), REGION_LATTICE_STRIDE);
		int currentBrickY = Math.floorDiv(currentCoreOrigin.getY(), REGION_LATTICE_STRIDE);
		int currentBrickZ = Math.floorDiv(currentCoreOrigin.getZ(), REGION_LATTICE_STRIDE);
		int deltaX = currentBrickX - previousBrickX;
		int deltaY = currentBrickY - previousBrickY;
		int deltaZ = currentBrickZ - previousBrickZ;
		int steps = Math.max(Math.abs(deltaX), Math.max(Math.abs(deltaY), Math.abs(deltaZ)));
		if (steps <= 0) {
			return;
		}
		int firstStep = Math.max(0, steps - ACTIVE_BRICK_TRAIL_MAX_SEGMENTS_PER_PLAYER);
		for (int step = firstStep; step < steps; step++) {
			int brickX = previousBrickX + Math.round(deltaX * (step / (float) steps));
			int brickY = previousBrickY + Math.round(deltaY * (step / (float) steps));
			int brickZ = previousBrickZ + Math.round(deltaZ * (step / (float) steps));
			BlockPos coreOrigin = new BlockPos(
					brickX * REGION_LATTICE_STRIDE,
					brickY * REGION_LATTICE_STRIDE,
					brickZ * REGION_LATTICE_STRIDE
			);
			if (coreOrigin.equals(currentCoreOrigin)) {
				continue;
			}
			BlockPos syntheticPos = coreOrigin.offset(
					REGION_CORE_SIZE / 2,
					Mth.clamp(playerPos.getY() - coreOrigin.getY(), 0, REGION_CORE_SIZE - 1),
					REGION_CORE_SIZE / 2
			);
			anchors.add(new PlayerRegionAnchor(worldKey, coreOrigin, syntheticPos));
		}
	}

	private List<EntitySampleRequest> collectEntitySampleRequests(MinecraftServer server, Set<WindowKey> activeKeys) {
		if (activeKeys.isEmpty()) {
			return List.of();
		}
		Map<ResourceKey<Level>, List<WindowKey>> keysByWorld = new HashMap<>();
		for (WindowKey key : activeKeys) {
			keysByWorld.computeIfAbsent(key.worldKey(), ignored -> new ArrayList<>()).add(key);
		}
		Map<UUID, EntitySampleRequest> requests = new HashMap<>();
		for (ServerLevel world : server.getAllLevels()) {
			List<WindowKey> worldKeys = keysByWorld.get(world.dimension());
			if (worldKeys == null || worldKeys.isEmpty()) {
				continue;
			}
			for (WindowKey key : worldKeys) {
				BlockPos origin = key.origin();
				AABB regionBox = new AABB(
						origin.getX(),
						origin.getY(),
						origin.getZ(),
						origin.getX() + GRID_SIZE,
						origin.getY() + GRID_SIZE,
						origin.getZ() + GRID_SIZE
				);
				for (Entity entity : world.getEntities(
						(Entity) null,
						regionBox,
						candidate -> !(candidate instanceof ServerPlayer)
								&& !candidate.isRemoved()
								&& !candidate.isSpectator()
				)) {
					requests.putIfAbsent(
							entity.getUUID(),
							new EntitySampleRequest(
									entity.getUUID(),
									world.dimension(),
									entity.blockPosition().immutable()
							)
					);
				}
			}
		}
		return requests.isEmpty() ? List.of() : List.copyOf(requests.values());
	}

	private void synchronizeActiveWindowsFromMirror() {
		synchronizeDesiredRegions();
		Set<WindowKey> solveWindows = desiredSolveWindowKeys;
		List<WindowKey> mirrorOnlyWindowKeysToRemove = new ArrayList<>();

		for (Map.Entry<WindowKey, RegionRecord> regionEntry : regions.entrySet()) {
			WindowKey key = regionEntry.getKey();
			RegionRecord region = regionEntry.getValue();
			boolean solveWindow = solveWindows.contains(key);
			if (!shouldActivelyRefreshWindowThisTick(region, solveWindow)) {
				continue;
			}
			if (region.attached() || region.serviceActive || region.serviceReady) {
				downgradeWindowToMirrorOnly(key, region);
			}
			if (!solveWindow) {
				if (region.sections == null) {
					initializeWindowFromMirror(key, region);
				} else {
					refreshWindowFromMirror(key, region);
				}
				if (!region.busy.get() && !region.attached() && !region.serviceActive && !region.serviceReady) {
					mirrorOnlyWindowKeysToRemove.add(key);
				}
				continue;
			}
			if (!region.fullWindowSectionsRequested && currentServer != null) {
				requestWindowSections(currentServer, key, false);
				region.fullWindowSectionsRequested = true;
			}
			if (region.sections == null) {
				initializeWindowFromMirror(key, region);
			} else {
				refreshWindowFromMirror(key, region);
			}
			uploadSolveWindowCoreStaticBrickToRuntime(key, region);
		}
		for (WindowKey key : mirrorOnlyWindowKeysToRemove) {
			RegionRecord region = regions.get(key);
			if (region == null || region.busy.get() || region.attached() || region.serviceActive || region.serviceReady) {
				continue;
			}
			mirrorOnlyPrewarmedWindowKeys.add(key);
			regions.remove(key);
		}
	}

	private boolean shouldActivelyRefreshWindowThisTick(RegionRecord region, boolean solveWindow) {
		if (solveWindow) {
			return true;
		}
		return region.attached()
				|| region.serviceReady
				|| region.serviceActive
				|| region.sections == null;
	}

	private void downgradeWindowToMirrorOnly(WindowKey key, RegionRecord region) {
		if (region.busy.get()) {
			return;
		}
		if (region.attached()) {
			deactivateWindow(key, region, true, false);
		} else {
			removePublishedRegionAtlas(key);
			if (region.serviceActive || region.serviceReady) {
				deactivateWindowRegionInSimulation(key);
			}
			releaseWindow(key, region);
		}
		region.dynamicRestoreAttempted = false;
		region.completedMaxSpeed = 0.0f;
	}

	private void synchronizeDesiredRegions() {
		Set<WindowKey> desiredWindows = new HashSet<>(desiredWindowKeys);
		Set<WindowKey> solveWindows = desiredSolveWindowKeys;
		Iterator<Map.Entry<WindowKey, Integer>> retainIterator = shellWindowRetainUntilTick.entrySet().iterator();
		while (retainIterator.hasNext()) {
			Map.Entry<WindowKey, Integer> entry = retainIterator.next();
			if (entry.getValue() < tickCounter || solveWindows.contains(entry.getKey())) {
				retainIterator.remove();
				continue;
			}
			desiredWindows.add(entry.getKey());
		}
		mirrorOnlyUploadedBrickStaticSignatures.keySet().retainAll(desiredWindows);
		uploadedBrickDynamicSeedSignatures.keySet().retainAll(desiredWindows);
		MinecraftServer server = currentServer;
		Iterator<Map.Entry<WindowKey, RegionRecord>> iterator = regions.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<WindowKey, RegionRecord> entry = iterator.next();
			if (desiredWindows.contains(entry.getKey())) {
				continue;
			}
			shellWindowRetainUntilTick.remove(entry.getKey());
			mirrorOnlyPrewarmedWindowKeys.remove(entry.getKey());
			mirrorOnlyUploadedBrickStaticSignatures.remove(entry.getKey());
			uploadedBrickDynamicSeedSignatures.remove(entry.getKey());
			RegionRecord region = entry.getValue();
			if (region.attached()) {
				deactivateWindow(entry.getKey(), region, true, false);
			} else {
				deactivateWindowRegionInSimulation(entry.getKey());
			}
			iterator.remove();
		}
		for (WindowKey key : desiredWindows) {
			RegionRecord existingRegion = regions.get(key);
			if (existingRegion != null) {
				if (!solveWindows.contains(key)) {
					prewarmMirrorOnlyWindowCoreStaticBrick(key);
				}
				continue;
			}
			boolean solveWindow = solveWindows.contains(key);
			if (!solveWindow) {
				if (server != null && mirrorOnlyPrewarmedWindowKeys.add(key)) {
					requestWindowSections(server, key, false);
				}
				prewarmMirrorOnlyWindowCoreStaticBrick(key);
				continue;
			}
			mirrorOnlyPrewarmedWindowKeys.remove(key);
			mirrorOnlyUploadedBrickStaticSignatures.remove(key);
			RegionRecord region = new RegionRecord();
			regions.put(key, region);
			if (server != null) {
				requestWindowSections(server, key, false);
				region.fullWindowSectionsRequested = true;
			}
		}
	}

	private void requestWindowSections(MinecraftServer server, WindowKey key, boolean includeHaloSections) {
		for (int sx = CORE_SECTION_MIN; sx <= CORE_SECTION_MAX; sx++) {
			for (int sy = CORE_SECTION_MIN; sy <= CORE_SECTION_MAX; sy++) {
				for (int sz = CORE_SECTION_MIN; sz <= CORE_SECTION_MAX; sz++) {
					BlockPos localOrigin = sectionOrigin(key.origin(), sx, sy, sz);
					worldMirror.requestSectionBuild(server, key.worldKey(), localOrigin, true);
				}
			}
		}
		if (!includeHaloSections) {
			return;
		}
		for (int sx = 0; sx < WINDOW_SECTION_COUNT; sx++) {
			for (int sy = 0; sy < WINDOW_SECTION_COUNT; sy++) {
				for (int sz = 0; sz < WINDOW_SECTION_COUNT; sz++) {
					if (isCoreSection(sx, sy, sz)) {
						continue;
					}
					BlockPos localOrigin = sectionOrigin(key.origin(), sx, sy, sz);
					worldMirror.requestSectionBuild(server, key.worldKey(), localOrigin, false);
				}
			}
		}
	}

	private boolean synchronizeRegionRecordFromMirror(WindowKey key, RegionRecord region) {
		if (!uploadRegionStaticFromMirror(key, region)) {
			return false;
		}
		if (!uploadSolveWindowCoreStaticBrickToRuntime(key, region)) {
			return false;
		}
		if (!region.serviceActive) {
			activateWindowRegionInSimulation(key);
		}
		ensureRegionNestedFeedbackLayoutInSimulation(key, region);
		refreshRegionLifecycle(key, region);
		return region.serviceReady;
	}

	private boolean attachOrRefreshRegionWindow(WindowKey key, RegionRecord region) {
		if (!region.attached()) {
			if (!initializeWindowFromMirror(key, region)) {
				return false;
			}
			if (!region.dynamicRestoreAttempted) {
				if (!ensureWindowDynamicRegionInitialized(key, region)) {
					return false;
				}
				region.dynamicRestoreAttempted = true;
				refreshRegionLifecycle(key, region);
				if (!region.serviceReady) {
					return false;
				}
			}
			attachRegionWindow(region);
			return true;
		}
		refreshWindowFromMirror(key, region);
		return true;
	}

	private static List<FanSource> queryFanSources(ResourceKey<Level> worldKey, BlockPos origin) {
		return queryFanSources(worldKey, origin, GRID_SIZE);
	}

	public static List<FanSource> queryFanSources(ResourceKey<Level> worldKey, BlockPos origin, int gridSize) {
		List<WorldMirror.FanRecord> fans = worldMirror.queryFans(
				worldKey,
				origin,
				gridSize,
				DUCT_JET_RANGE + FAN_RADIUS + 1
		);
		if (fans.isEmpty()) {
			return List.of();
		}
		List<FanSource> result = new ArrayList<>(fans.size());
		for (WorldMirror.FanRecord fan : fans) {
			result.add(new FanSource(fan.pos(), fan.facing(), fan.ductLength()));
		}
		return List.copyOf(result);
	}

	private void refreshRegionFansIfNeeded(WindowKey key, RegionRecord region) {
		if (!region.fansDirty) {
			return;
		}
		region.fans = queryFanSources(key.worldKey(), key.origin());
		region.fansDirty = false;
		region.forcingDirty = true;
	}

	private boolean initializeWindowFromMirror(WindowKey key, RegionRecord region) {
		region.ensureSectionsInitialized();
		int readyCoreSections = 0;
		for (int sx = CORE_SECTION_MIN; sx <= CORE_SECTION_MAX; sx++) {
			for (int sy = CORE_SECTION_MIN; sy <= CORE_SECTION_MAX; sy++) {
				for (int sz = CORE_SECTION_MIN; sz <= CORE_SECTION_MAX; sz++) {
					BlockPos localOrigin = sectionOrigin(key.origin(), sx, sy, sz);
					WorldMirror.SectionSnapshot snapshot = worldMirror.peekSection(key.worldKey(), localOrigin);
					if (snapshot == null) {
						requestWindowSectionIfNeeded(key, sx, sy, sz);
						continue;
					}
					region.setSection(sx, sy, sz, snapshot);
					readyCoreSections++;
				}
			}
		}
		if (readyCoreSections < CORE_SECTION_COUNT) {
			return false;
		}
		region.lastThermalRefreshTick = tickCounter - WINDOW_THERMAL_REFRESH_TICKS;
		region.forcingDirty = true;
		return true;
	}

	private void refreshWindowFromMirror(WindowKey key, RegionRecord region) {
		if (region.sections == null) {
			if (!initializeWindowFromMirror(key, region)) {
				return;
			}
			return;
		}
		boolean sectionUpdated = false;
		for (int sx = CORE_SECTION_MIN; sx <= CORE_SECTION_MAX; sx++) {
			for (int sy = CORE_SECTION_MIN; sy <= CORE_SECTION_MAX; sy++) {
				for (int sz = CORE_SECTION_MIN; sz <= CORE_SECTION_MAX; sz++) {
					BlockPos localOrigin = sectionOrigin(key.origin(), sx, sy, sz);
					WorldMirror.SectionSnapshot snapshot = worldMirror.peekSection(key.worldKey(), localOrigin);
					if (snapshot == null) {
						requestWindowSectionIfNeeded(key, sx, sy, sz);
						continue;
					}
					long previousVersion = region.sectionVersionAt(sx, sy, sz);
					if (region.sectionAt(sx, sy, sz) == null) {
						region.setSection(sx, sy, sz, snapshot);
						sectionUpdated = true;
						region.markBackendResetPending();
						continue;
					}
					if (previousVersion == snapshot.version()) {
						continue;
					}
					sectionUpdated = true;
					region.setSection(sx, sy, sz, snapshot);
					region.markBackendResetPending();
					region.forcingDirty = true;
				}
			}
		}
		if (sectionUpdated) {
			region.lastThermalRefreshTick = tickCounter - WINDOW_THERMAL_REFRESH_TICKS;
		}
	}

	private void populateMirrorSectionSnapshot(ServerLevel world, BlockPos origin, WorldMirror.SectionSnapshot snapshot) {
		Arrays.fill(snapshot.obstacle(), 0.0f);
		Arrays.fill(snapshot.air(), 0.0f);
		Arrays.fill(snapshot.surfaceKind(), SURFACE_KIND_NONE);
		Arrays.fill(snapshot.emitterPowerWatts(), 0.0f);
		Arrays.fill(snapshot.openFaceMask(), (byte) 0);
		Arrays.fill(snapshot.faceSkyExposure(), (byte) 0);
		Arrays.fill(snapshot.faceDirectExposure(), (byte) 0);
		ThermalMaterial[] materials = new ThermalMaterial[SECTION_CELL_COUNT];
		int[] cachedSkyExposure = new int[SECTION_CELL_COUNT];
		int[] cachedDirectExposure = new int[SECTION_CELL_COUNT];
		Arrays.fill(cachedSkyExposure, -1);
		Arrays.fill(cachedDirectExposure, -1);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = 0; x < CHUNK_SIZE; x++) {
			for (int y = 0; y < CHUNK_SIZE; y++) {
				for (int z = 0; z < CHUNK_SIZE; z++) {
					int cell = localSectionCellIndex(x, y, z);
					cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
					BlockState state = world.getBlockState(cursor);
					boolean solid = isSolidObstacle(world, cursor, state);
					snapshot.obstacle()[cell] = solid ? 1.0f : 0.0f;
					snapshot.air()[cell] = state.isAir() ? 1.0f : 0.0f;
					ThermalMaterial material = thermalMaterial(state);
					materials[cell] = material;
					snapshot.surfaceKind()[cell] = material == null ? SURFACE_KIND_NONE : material.kind();
					snapshot.emitterPowerWatts()[cell] = sampleEmitterThermalPowerWatts(state);
				}
			}
		}
		for (int x = 0; x < CHUNK_SIZE; x++) {
			for (int y = 0; y < CHUNK_SIZE; y++) {
				for (int z = 0; z < CHUNK_SIZE; z++) {
					int cell = localSectionCellIndex(x, y, z);
					populateCachedSectionFaceFields(
							world,
							origin,
							x,
							y,
							z,
							materials[cell],
							snapshot.emitterPowerWatts()[cell],
							snapshot.air(),
							snapshot.obstacle(),
							snapshot.openFaceMask(),
							snapshot.faceSkyExposure(),
							snapshot.faceDirectExposure(),
							cachedSkyExposure,
							cachedDirectExposure,
							cursor
					);
				}
			}
		}
	}

	private void populateCachedSectionFaceFields(
			ServerLevel world,
			BlockPos origin,
			int x,
			int y,
			int z,
			ThermalMaterial material,
			float emitterPower,
			float[] localAir,
			float[] localObstacle,
			byte[] openFaceMaskField,
			byte[] faceSkyExposure,
			byte[] faceDirectExposure,
			int[] cachedSkyExposure,
			int[] cachedDirectExposure,
			BlockPos.MutableBlockPos cursor
	) {
		if (material == null && emitterPower <= 0.0f) {
			return;
		}
		int cell = localSectionCellIndex(x, y, z);
		byte openFaceMask = 0;
		for (Direction direction : CARDINAL_DIRECTIONS) {
			int nx = x + direction.getStepX();
			int ny = y + direction.getStepY();
			int nz = z + direction.getStepZ();
			boolean openFace;
			if (inSectionBounds(nx, ny, nz)) {
				int neighborCell = localSectionCellIndex(nx, ny, nz);
				openFace = material != null
						? material.atmosphericExchangeRequiresAirNeighbor()
						  ? localAir[neighborCell] >= 0.5f
						  : localObstacle[neighborCell] < 0.5f
						: localAir[neighborCell] >= 0.5f;
			} else {
				cursor.set(
						origin.getX() + nx,
						origin.getY() + ny,
						origin.getZ() + nz
				);
				BlockState neighborState = world.getBlockState(cursor);
				openFace = material != null
						? material.atmosphericExchangeRequiresAirNeighbor()
						  ? neighborState.isAir()
						  : !isSolidObstacle(world, cursor, neighborState)
						: neighborState.isAir();
			}
			if (!openFace) {
				continue;
			}
			openFaceMask = setFaceBit(openFaceMask, direction);
			int faceIndex = faceDataIndex(cell, direction);
			faceSkyExposure[faceIndex] = cachedSectionSkyExposure(
					world,
					origin,
					nx,
					ny,
					nz,
					cachedSkyExposure,
					cursor
			);
			faceDirectExposure[faceIndex] = cachedSectionDirectExposure(
					world,
					origin,
					nx,
					ny,
					nz,
					cachedDirectExposure,
					cursor
			);
		}
		openFaceMaskField[cell] = openFaceMask;
	}

	private byte cachedSectionSkyExposure(
			ServerLevel world,
			BlockPos origin,
			int x,
			int y,
			int z,
			int[] cache,
			BlockPos.MutableBlockPos cursor
	) {
		if (inSectionBounds(x, y, z)) {
			int cell = localSectionCellIndex(x, y, z);
			int cached = cache[cell];
			if (cached >= 0) {
				return (byte) cached;
			}
			cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
			byte value = quantizeUnitFloat(sampleSkyExposure(world, cursor));
			cache[cell] = Byte.toUnsignedInt(value);
			return value;
		}
		cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
		return quantizeUnitFloat(sampleSkyExposure(world, cursor));
	}

	private byte cachedSectionDirectExposure(
			ServerLevel world,
			BlockPos origin,
			int x,
			int y,
			int z,
			int[] cache,
			BlockPos.MutableBlockPos cursor
	) {
		if (inSectionBounds(x, y, z)) {
			int cell = localSectionCellIndex(x, y, z);
			int cached = cache[cell];
			if (cached >= 0) {
				return (byte) cached;
			}
			cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
			byte value = quantizeUnitFloat(sampleDirectSunExposure(world, cursor));
			cache[cell] = Byte.toUnsignedInt(value);
			return value;
		}
		cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
		return quantizeUnitFloat(sampleDirectSunExposure(world, cursor));
	}

	private boolean shouldRefreshWindowThermal(RegionRecord region) {
		return region.sections != null
				&& tickCounter - region.lastThermalRefreshTick >= WINDOW_THERMAL_REFRESH_TICKS;
	}

	private boolean ensureSimulationL2Runtime() {
		return simulationServiceId != 0L
				&& simulationBridge.isLoaded()
				&& simulationBridge.ensureL2Runtime(simulationServiceId, GRID_SIZE, GRID_SIZE, GRID_SIZE, CHANNELS, RESPONSE_CHANNELS);
	}

	private boolean uploadRegionFanForcingToSimulation(WindowKey key, RegionRecord region) {
		if (simulationServiceId == 0L || region.sections == null) {
			return false;
		}
		int cells = GRID_SIZE * GRID_SIZE * GRID_SIZE;
		byte[] fanMask = new byte[cells];
		float[] fanVx = new float[cells];
		float[] fanVy = new float[cells];
		float[] fanVz = new float[cells];
		for (FanSource fan : region.fans) {
			applyFanSourceToForcing(region, fanMask, fanVx, fanVy, fanVz, fan, key.origin().getX(), key.origin().getY(), key.origin().getZ());
		}
		boolean uploaded = simulationBridge.uploadRegionForcing(
				simulationServiceId,
				simulationRegionKey(key),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE,
				null,
				fanMask,
				fanVx,
				fanVy,
				fanVz
		);
		if (uploaded) {
			region.forcingDirty = false;
		}
		return uploaded;
	}

	private void deactivateWindow(WindowKey key, RegionRecord region, boolean persistDynamicRegion, boolean synchronousPersist) {
		region.markDetached();
		removePublishedRegionAtlas(key);
		if (persistDynamicRegion) {
			persistWindowDynamicRegion(key, region, synchronousPersist);
		}
		deactivateWindowRegionInSimulation(key);
		if (!region.busy.get()) {
			releaseWindow(key, region);
		}
	}

	private void resetWindowBackend(WindowKey key, RegionRecord region) {
		if (simulationServiceId != 0L) {
			simulationBridge.releaseRegionRuntime(simulationServiceId, simulationRegionKey(key));
			region.noteBackendReset(tickCounter);
			if (region.sections != null) {
				refreshRegionLifecycle(key, region);
				if (!region.serviceActive) {
					activateWindowRegionInSimulation(key);
					refreshRegionLifecycle(key, region);
				}
				region.forcingDirty = true;
				refreshRegionFansIfNeeded(key, region);
				ensureRegionNestedFeedbackLayoutInSimulation(key, region);
				seedWindowDynamicRegionFromNestedMet(
						key,
						region,
						new float[FLOW_COUNT],
						new float[GRID_SIZE * GRID_SIZE * GRID_SIZE],
						new float[GRID_SIZE * GRID_SIZE * GRID_SIZE]
				);
				uploadRegionFanForcingToSimulation(key, region);
				refreshRegionThermalInSimulation(key, region);
			}
		}
		region.clearBackendResetPending();
	}

	private Set<WindowKey> activeRegionKeys(List<PlayerRegionAnchor> anchors) {
		Set<WindowKey> keys = new HashSet<>();
		for (PlayerRegionAnchor anchor : anchors) {
			ResourceKey<Level> worldKey = anchor.worldKey();
			BlockPos baseCore = anchor.coreOrigin();
			int localX = Mth.clamp(anchor.blockPos().getX() - baseCore.getX(), 0, REGION_CORE_SIZE - 1);
			int localY = Mth.clamp(anchor.blockPos().getY() - baseCore.getY(), 0, REGION_CORE_SIZE - 1);
			int localZ = Mth.clamp(anchor.blockPos().getZ() - baseCore.getZ(), 0, REGION_CORE_SIZE - 1);
			boolean attachWest = localX < HORIZONTAL_ATTACH_MARGIN_CELLS;
			boolean attachEast = localX >= REGION_CORE_SIZE - HORIZONTAL_ATTACH_MARGIN_CELLS;
			boolean attachDown = localY < VERTICAL_ATTACH_MARGIN_CELLS;
			boolean attachUp = localY >= REGION_CORE_SIZE - VERTICAL_ATTACH_MARGIN_CELLS;
			boolean attachNorth = localZ < HORIZONTAL_ATTACH_MARGIN_CELLS;
			boolean attachSouth = localZ >= REGION_CORE_SIZE - HORIZONTAL_ATTACH_MARGIN_CELLS;
			keys.add(new WindowKey(worldKey, windowOriginFromCoreOrigin(baseCore)));
			if (attachWest) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(-REGION_LATTICE_STRIDE, 0, 0))
				));
			}
			if (attachEast) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(REGION_LATTICE_STRIDE, 0, 0))
				));
			}
			if (attachNorth) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(0, 0, -REGION_LATTICE_STRIDE))
				));
			}
			if (attachSouth) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(0, 0, REGION_LATTICE_STRIDE))
				));
			}
			if (attachWest && attachNorth) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(-REGION_LATTICE_STRIDE, 0, -REGION_LATTICE_STRIDE))
				));
			}
			if (attachWest && attachSouth) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(-REGION_LATTICE_STRIDE, 0, REGION_LATTICE_STRIDE))
				));
			}
			if (attachEast && attachNorth) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(REGION_LATTICE_STRIDE, 0, -REGION_LATTICE_STRIDE))
				));
			}
			if (attachEast && attachSouth) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(REGION_LATTICE_STRIDE, 0, REGION_LATTICE_STRIDE))
				));
			}
			if (attachUp) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(0, REGION_LATTICE_STRIDE, 0))
				));
			}
			if (attachDown) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(0, -REGION_LATTICE_STRIDE, 0))
				));
			}
		}
		return keys;
	}

	public Set<WindowKey> solveRegionKeys(List<PlayerRegionAnchor> anchors) {
		Set<WindowKey> keys = new HashSet<>();
		for (PlayerRegionAnchor anchor : anchors) {
			ResourceKey<Level> worldKey = anchor.worldKey();
			BlockPos baseCore = anchor.coreOrigin();
			keys.add(new WindowKey(worldKey, windowOriginFromCoreOrigin(baseCore)));
			int localX = Mth.clamp(anchor.blockPos().getX() - baseCore.getX(), 0, REGION_CORE_SIZE - 1);
			int localY = Mth.clamp(anchor.blockPos().getY() - baseCore.getY(), 0, REGION_CORE_SIZE - 1);
			int localZ = Mth.clamp(anchor.blockPos().getZ() - baseCore.getZ(), 0, REGION_CORE_SIZE - 1);
			if (localX >= REGION_CORE_SIZE - HORIZONTAL_SOLVE_MARGIN_CELLS) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(REGION_LATTICE_STRIDE, 0, 0))
				));
			}
			if (localX < HORIZONTAL_SOLVE_MARGIN_CELLS) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(-REGION_LATTICE_STRIDE, 0, 0))
				));
			}
			if (localY >= REGION_CORE_SIZE - VERTICAL_SOLVE_MARGIN_CELLS) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(0, REGION_LATTICE_STRIDE, 0))
				));
			}
			if (localY < VERTICAL_SOLVE_MARGIN_CELLS) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(0, -REGION_LATTICE_STRIDE, 0))
				));
			}
			if (localZ >= REGION_CORE_SIZE - HORIZONTAL_SOLVE_MARGIN_CELLS) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(0, 0, REGION_LATTICE_STRIDE))
				));
			}
			if (localZ < HORIZONTAL_SOLVE_MARGIN_CELLS) {
				keys.add(new WindowKey(
						worldKey,
						windowOriginFromCoreOrigin(baseCore.offset(0, 0, -REGION_LATTICE_STRIDE))
				));
			}
		}
		return keys;
	}

	public BlockPos coreOriginForPosition(BlockPos pos) {
		int x = Math.floorDiv(pos.getX(), REGION_LATTICE_STRIDE) * REGION_LATTICE_STRIDE;
		int y = Math.floorDiv(pos.getY(), REGION_LATTICE_STRIDE) * REGION_LATTICE_STRIDE;
		int z = Math.floorDiv(pos.getZ(), REGION_LATTICE_STRIDE) * REGION_LATTICE_STRIDE;
		return new BlockPos(x, y, z);
	}

	private BlockPos windowOriginFromCoreOrigin(BlockPos coreOrigin) {
		return coreOrigin.offset(-REGION_HALO_CELLS, -REGION_HALO_CELLS, -REGION_HALO_CELLS);
	}

	private int voxelIndex(int x, int y, int z) {
		return ((x * GRID_SIZE + y) * GRID_SIZE + z) * CHANNELS;
	}

	private int localSectionCellIndex(int x, int y, int z) {
		return ((x * CHUNK_SIZE + y) * CHUNK_SIZE + z);
	}

	private int windowSectionIndex(int sx, int sy, int sz) {
		return ((sx * WINDOW_SECTION_COUNT + sy) * WINDOW_SECTION_COUNT + sz);
	}

	private boolean isCoreSection(int sx, int sy, int sz) {
		return sx >= CORE_SECTION_MIN && sx <= CORE_SECTION_MAX
				&& sy >= CORE_SECTION_MIN && sy <= CORE_SECTION_MAX
				&& sz >= CORE_SECTION_MIN && sz <= CORE_SECTION_MAX;
	}

	private BlockPos sectionOrigin(BlockPos windowOrigin, int sx, int sy, int sz) {
		return new BlockPos(
				windowOrigin.getX() + sx * CHUNK_SIZE,
				windowOrigin.getY() + sy * CHUNK_SIZE,
				windowOrigin.getZ() + sz * CHUNK_SIZE
		);
	}

	private int gridCellIndex(int x, int y, int z) {
		return (x * GRID_SIZE + y) * GRID_SIZE + z;
	}

	public static int patchCellIndex(int x, int y, int z, int size) {
		return (x * size + y) * size + z;
	}

	private int patchCellIndex3d(int x, int y, int z, int sizeY, int sizeZ) {
		return (x * sizeY + y) * sizeZ + z;
	}


	private static double inspectionPatchCellSizeBlocks(int domainBlocks, int gridResolution) {
		return (double) domainBlocks / (double) gridResolution;
	}


	private static double patchCellCenterWorld(int originCoord, int cell, int cellsPerBlock) {
		return originCoord + ((double) cell + 0.5) / (double) cellsPerBlock;
	}


	public static InspectionPatchStaticFields captureInspectionPatchStaticFields(
			ServerLevel world,
			BlockPos origin,
			int domainBlocks,
			int gridResolution,
			int cellsPerBlock
	) {
		int cells = gridResolution * gridResolution * gridResolution;
		byte[] obstacle = new byte[cells];
		byte[] surfaceKind = new byte[cells];
		byte[] openFaceMask = new byte[cells];
		float[] emitterPowerWatts = new float[cells];
		byte[] faceSkyExposure = new byte[cells * FACE_COUNT];
		byte[] faceDirectExposure = new byte[cells * FACE_COUNT];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		double cellSizeBlocks = inspectionPatchCellSizeBlocks(domainBlocks, gridResolution);
		for (int x = 0; x < gridResolution; x++) {
			double sampleX = patchCellCenterWorld(origin.getX(), x, cellsPerBlock);
			for (int y = 0; y < gridResolution; y++) {
				double sampleY = patchCellCenterWorld(origin.getY(), y, cellsPerBlock);
				for (int z = 0; z < gridResolution; z++) {
					double sampleZ = patchCellCenterWorld(origin.getZ(), z, cellsPerBlock);
					int cell = patchCellIndex(x, y, z, gridResolution);
					cursor.set(Mth.floor(sampleX), Mth.floor(sampleY), Mth.floor(sampleZ));
					BlockState state = world.getBlockState(cursor);
					boolean solid = isSolidObstacleAtPoint(world, cursor, state, sampleX, sampleY, sampleZ);
					obstacle[cell] = solid ? (byte) 1 : (byte) 0;
					ThermalMaterial material = thermalMaterial(state);
					surfaceKind[cell] = material == null ? SURFACE_KIND_NONE : material.kind();
					patchStaticThermalFieldsAtSample(
							world,
							cursor,
							state,
							sampleX,
							sampleY,
							sampleZ,
							cellSizeBlocks,
							cell,
							material,
							emitterPowerWatts,
							openFaceMask,
							faceSkyExposure,
							faceDirectExposure
					);
				}
			}
		}
		return new InspectionPatchStaticFields(
				obstacle,
				surfaceKind,
				openFaceMask,
				emitterPowerWatts,
				faceSkyExposure,
				faceDirectExposure
		);
	}

	private static void patchStaticThermalFieldsAtSample(
			ServerLevel world,
			BlockPos pos,
			BlockState state,
			double sampleX,
			double sampleY,
			double sampleZ,
			double cellSizeBlocks,
			int cell,
			ThermalMaterial material,
			float[] emitterPowerWatts,
			byte[] openFaceMaskField,
			byte[] faceSkyExposure,
			byte[] faceDirectExposure
	) {
		float emitterPower = sampleEmitterThermalPowerWatts(state);
		byte openFaceMask = 0;
		if (emitterPowerWatts != null) {
			emitterPowerWatts[cell] = emitterPower;
		}
		if (openFaceMaskField != null) {
			openFaceMaskField[cell] = 0;
		}
		if (faceSkyExposure != null) {
			Arrays.fill(faceSkyExposure, cell * FACE_COUNT, cell * FACE_COUNT + FACE_COUNT, (byte) 0);
		}
		if (faceDirectExposure != null) {
			Arrays.fill(faceDirectExposure, cell * FACE_COUNT, cell * FACE_COUNT + FACE_COUNT, (byte) 0);
		}
		if (material == null && emitterPower <= 0.0f) {
			return;
		}
		BlockPos.MutableBlockPos neighborCursor = new BlockPos.MutableBlockPos();
		for (Direction direction : CARDINAL_DIRECTIONS) {
			double neighborSampleX = sampleX + direction.getStepX() * cellSizeBlocks;
			double neighborSampleY = sampleY + direction.getStepY() * cellSizeBlocks;
			double neighborSampleZ = sampleZ + direction.getStepZ() * cellSizeBlocks;
			neighborCursor.set(
					Mth.floor(neighborSampleX),
					Mth.floor(neighborSampleY),
					Mth.floor(neighborSampleZ)
			);
			BlockState neighborState = world.getBlockState(neighborCursor);
			boolean openFace;
			if (material != null) {
				openFace = material.atmosphericExchangeRequiresAirNeighbor()
						? neighborState.isAir()
						: !isSolidObstacleAtPoint(world, neighborCursor, neighborState, neighborSampleX, neighborSampleY, neighborSampleZ);
			} else {
				openFace = neighborState.isAir();
			}
			if (!openFace) {
				continue;
			}
			openFaceMask = setFaceBit(openFaceMask, direction);
			if (material != null && faceSkyExposure != null && faceDirectExposure != null) {
				int faceIndex = faceDataIndex(cell, direction);
				faceSkyExposure[faceIndex] = quantizeUnitFloat(sampleSkyExposure(world, neighborCursor));
				faceDirectExposure[faceIndex] = quantizeUnitFloat(sampleDirectSunExposure(world, neighborCursor));
			}
		}
		if (openFaceMaskField != null) {
			openFaceMaskField[cell] = openFaceMask;
		}
	}


	public static boolean writeCompressedFloatGridFile(
			Path path,
			float[] values,
			int nx,
			int ny,
			int nz,
			double tolerance
	) throws IOException {
		byte[] compressed = simulationBridge.compressFloatGrid3d(values, nx, ny, nz, tolerance);
		if (compressed == null) {
			return false;
		}
		Files.write(path, compressed);
		return true;
	}

	public static long simulationRegionKey(WindowKey key) {
		long value = 1469598103934665603L;
		value = (value ^ key.worldKey().identifier().hashCode()) * 1099511628211L;
		value = (value ^ key.origin().getX()) * 1099511628211L;
		value = (value ^ key.origin().getY()) * 1099511628211L;
		value = (value ^ key.origin().getZ()) * 1099511628211L;
		return value == 0L ? 1L : value;
	}

	private long simulationWorldKey(ResourceKey<Level> worldKey) {
		long value = worldKey.identifier().hashCode();
		return value == 0L ? 1L : value;
	}

	private void syncBrickRuntimeHints(Map<ResourceKey<Level>, int[]> hintCoordsByWorld) {
		if (!shouldRunServerAuthoritativeL2()) {
			brickRuntimeHintWorldKeys.clear();
			brickRuntimeKnownWorldKeys.clear();
			brickBoundaryReferenceRefreshTicks.clear();
			return;
		}
		if (simulationServiceId == 0L || !simulationBridge.isLoaded()) {
			return;
		}
		Set<ResourceKey<Level>> activeWorldKeys = hintCoordsByWorld.keySet();
		for (ResourceKey<Level> staleWorldKey : new HashSet<>(brickRuntimeHintWorldKeys)) {
			if (activeWorldKeys.contains(staleWorldKey)) {
				continue;
			}
			simulationBridge.setBrickWorldActiveHints(
					simulationServiceId,
					simulationWorldKey(staleWorldKey),
					BRICK_RUNTIME_SIZE,
					new int[0]
			);
		}
		Set<ResourceKey<Level>> syncedWorldKeys = new HashSet<>();
		for (ResourceKey<Level> worldKey : activeWorldKeys) {
			long worldRuntimeKey = simulationWorldKey(worldKey);
			if (!simulationBridge.ensureBrickWorldRuntime(
					simulationServiceId,
					worldRuntimeKey,
					BRICK_RUNTIME_SIZE,
					1.0f,
					SOLVER_STEP_SECONDS
			)) {
				continue;
			}
			brickRuntimeKnownWorldKeys.add(worldKey);
			int[] hintCoords = hintCoordsByWorld.getOrDefault(worldKey, new int[0]);
			if (simulationBridge.setBrickWorldActiveHints(
					simulationServiceId,
					worldRuntimeKey,
					BRICK_RUNTIME_SIZE,
					hintCoords
			)) {
				syncedWorldKeys.add(worldKey);
			}
		}
		brickRuntimeHintWorldKeys.clear();
		brickRuntimeHintWorldKeys.addAll(syncedWorldKeys);
		brickBoundaryReferenceRefreshTicks.keySet().removeIf(worldKey -> !activeWorldKeys.contains(worldKey));
		refreshBrickRuntimeBoundaryReferences(hintCoordsByWorld);
	}

	private void refreshBrickRuntimeBoundaryReferences(Map<ResourceKey<Level>, int[]> hintCoordsByWorld) {
		if (simulationServiceId == 0L || !simulationBridge.isLoaded()) {
			return;
		}
		for (Map.Entry<ResourceKey<Level>, int[]> entry : hintCoordsByWorld.entrySet()) {
			ResourceKey<Level> worldKey = entry.getKey();
			Map<BrickRuntimeHint, Integer> refreshTicks = brickBoundaryReferenceRefreshTicks.computeIfAbsent(
					worldKey,
					ignored -> new HashMap<>()
			);
			Set<BrickRuntimeHint> closure = expandBrickHintClosure(entry.getValue());
			refreshTicks.keySet().removeIf(hint -> !closure.contains(hint));
			for (BrickRuntimeHint hint : closure) {
				int lastTick = refreshTicks.getOrDefault(hint, Integer.MIN_VALUE);
				if (tickCounter - lastTick < BRICK_BOUNDARY_REFERENCE_REFRESH_INTERVAL_TICKS) {
					continue;
				}
				if (uploadBrickBoundaryReference(worldKey, hint)) {
					refreshTicks.put(hint, tickCounter);
				}
			}
		}
	}

	private Set<BrickRuntimeHint> expandBrickHintClosure(int[] hintCoords) {
		LinkedHashSet<BrickRuntimeHint> closure = new LinkedHashSet<>();
		if (hintCoords == null) {
			return closure;
		}
		for (int i = 0; i + 2 < hintCoords.length; i += NativeSimulationBridge.BRICK_HINT_COORDS_PER_BRICK) {
			int brickX = hintCoords[i];
			int brickY = hintCoords[i + 1];
			int brickZ = hintCoords[i + 2];
			closure.add(new BrickRuntimeHint(brickX, brickY, brickZ));
			closure.add(new BrickRuntimeHint(brickX - 1, brickY, brickZ));
			closure.add(new BrickRuntimeHint(brickX + 1, brickY, brickZ));
			closure.add(new BrickRuntimeHint(brickX, brickY - 1, brickZ));
			closure.add(new BrickRuntimeHint(brickX, brickY + 1, brickZ));
			closure.add(new BrickRuntimeHint(brickX, brickY, brickZ - 1));
			closure.add(new BrickRuntimeHint(brickX, brickY, brickZ + 1));
		}
		return closure;
	}

	private boolean uploadBrickBoundaryReference(ResourceKey<Level> worldKey, BrickRuntimeHint hint) {
		WorldMirror.SectionSnapshot[] snapshots = brickSectionSnapshotsFromMirror(
				worldKey,
				hint.brickX(),
				hint.brickY(),
				hint.brickZ()
		);
		byte[] obstacleMask = buildBrickObstacleMask(snapshots, false);
		if (obstacleMask == null) {
			obstacleMask = new byte[BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE];
		}
		int cells = BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE;
		float[] flowState = new float[cells * RESPONSE_CHANNELS];
		float[] airTemperatureState = new float[cells];
		float[] surfaceTemperatureState = new float[cells];
		BlockPos origin = new BlockPos(
				hint.brickX() * BRICK_RUNTIME_SIZE,
				hint.brickY() * BRICK_RUNTIME_SIZE,
				hint.brickZ() * BRICK_RUNTIME_SIZE
		);
		MesoscaleGrid mesoscaleGrid = mesoscaleMetGrids.get(worldKey);
		boolean seeded = mesoscaleGrid != null
				&& seedDynamicRegionFromMesoscale(
				origin,
				BRICK_RUNTIME_SIZE,
				BRICK_RUNTIME_SIZE,
				BRICK_RUNTIME_SIZE,
				obstacleMask,
				flowState,
				airTemperatureState,
				surfaceTemperatureState,
				mesoscaleGrid
		);
		if (!seeded) {
			seedDynamicRegionFromBoundarySample(
					worldKey,
					origin,
					BRICK_RUNTIME_SIZE,
					BRICK_RUNTIME_SIZE,
					BRICK_RUNTIME_SIZE,
					obstacleMask,
					flowState,
					airTemperatureState,
					surfaceTemperatureState
			);
		}
		if (maxFlowSpeedMetersPerSecond(flowState) < ZERO_ATLAS_MAX_SPEED_EPS_MPS) {
			return false;
		}
		return simulationBridge.uploadBrickWorldBoundaryReferenceBrick(
				simulationServiceId,
				simulationWorldKey(worldKey),
				BRICK_RUNTIME_SIZE,
				hint.brickX(),
				hint.brickY(),
				hint.brickZ(),
				flowState,
				airTemperatureState,
				surfaceTemperatureState
		);
	}

	private Map<ResourceKey<Level>, int[]> brickRuntimeHintCoords(Set<WindowKey> solveKeys) {
		Map<ResourceKey<Level>, LinkedHashSet<BrickRuntimeHint>> hintsByWorld = new HashMap<>();
		for (WindowKey solveKey : solveKeys) {
			ResourceKey<Level> worldKey = solveKey.worldKey();
			BlockPos coreOrigin = solveKey.origin().offset(REGION_HALO_CELLS, REGION_HALO_CELLS, REGION_HALO_CELLS);
			LinkedHashSet<BrickRuntimeHint> hints = hintsByWorld.computeIfAbsent(worldKey, ignored -> new LinkedHashSet<>());
			hints.add(new BrickRuntimeHint(
					Math.floorDiv(coreOrigin.getX(), BRICK_RUNTIME_SIZE),
					Math.floorDiv(coreOrigin.getY(), BRICK_RUNTIME_SIZE),
					Math.floorDiv(coreOrigin.getZ(), BRICK_RUNTIME_SIZE)
			));
		}
		Map<ResourceKey<Level>, int[]> coordsByWorld = new HashMap<>();
		for (Map.Entry<ResourceKey<Level>, LinkedHashSet<BrickRuntimeHint>> entry : hintsByWorld.entrySet()) {
			int[] coords = new int[entry.getValue().size() * NativeSimulationBridge.BRICK_HINT_COORDS_PER_BRICK];
			int index = 0;
			for (BrickRuntimeHint hint : entry.getValue()) {
				coords[index++] = hint.brickX();
				coords[index++] = hint.brickY();
				coords[index++] = hint.brickZ();
			}
			coordsByWorld.put(entry.getKey(), coords);
		}
		return Map.copyOf(coordsByWorld);
	}

	private void stepBrickRuntimeWorlds() {
		if (!shouldRunServerAuthoritativeL2()) {
			brickRuntimeHintWorldKeys.clear();
			brickRuntimeKnownWorldKeys.clear();
			return;
		}
		if (simulationServiceId == 0L || !simulationBridge.isLoaded()) {
			return;
		}
		for (ResourceKey<Level> worldKey : new HashSet<>(brickRuntimeKnownWorldKeys)) {
			boolean stepped = simulationBridge.stepBrickWorldRuntime(
					simulationServiceId,
					simulationWorldKey(worldKey),
					1
			);
			if (!stepped) {
				lastSolverError = simulationBridge.lastError();
			}
		}
	}

	private void appendBrickWindowCoords(Set<WindowKey> windows, ResourceKey<Level> worldKey, int[] brickCoords) {
		if (brickCoords == null) {
			return;
		}
		for (int i = 0; i + 2 < brickCoords.length; i += NativeSimulationBridge.BRICK_HINT_COORDS_PER_BRICK) {
			BlockPos coreOrigin = new BlockPos(
					brickCoords[i] * BRICK_RUNTIME_SIZE,
					brickCoords[i + 1] * BRICK_RUNTIME_SIZE,
					brickCoords[i + 2] * BRICK_RUNTIME_SIZE
			);
			windows.add(new WindowKey(worldKey, windowOriginFromCoreOrigin(coreOrigin)));
		}
	}

	private void refreshDesiredWindowsFromBrickRuntime() {
		Set<WindowKey> solveWindows = new HashSet<>(anchorSolveWindowKeys);
		int solveRetainUntilTick = tickCounter + SOLVE_WINDOW_RETENTION_TICKS;
		solveWindowRetainUntilTick.entrySet().removeIf(entry -> entry.getValue() < tickCounter);
		for (WindowKey key : anchorSolveWindowKeys) {
			solveWindowRetainUntilTick.put(key, solveRetainUntilTick);
		}
		solveWindows.addAll(solveWindowRetainUntilTick.keySet());

		Set<WindowKey> desiredWindows = new HashSet<>(anchorDesiredWindowKeys);
		desiredWindows.addAll(solveWindows);
		desiredSolveWindowKeys = Set.copyOf(solveWindows);
		desiredWindowKeys = Set.copyOf(desiredWindows);
		int retainUntilTick = tickCounter + SHELL_WINDOW_RETENTION_TICKS;
		shellWindowRetainUntilTick.entrySet().removeIf(entry -> entry.getValue() < tickCounter);
		for (WindowKey key : desiredWindowKeys) {
			if (!desiredSolveWindowKeys.contains(key)) {
				shellWindowRetainUntilTick.put(key, retainUntilTick);
			} else {
				shellWindowRetainUntilTick.remove(key);
			}
		}
	}

	private List<NestedFeedbackAxisSpan> buildHorizontalNestedFeedbackSpans(
			int worldMinInclusive,
			int worldMaxExclusive,
			int localOrigin,
			int coarseCellSize
	) {
		List<NestedFeedbackAxisSpan> spans = new ArrayList<>(2);
		int spanStartWorld = worldMinInclusive;
		int currentCell = Math.floorDiv(worldMinInclusive, coarseCellSize);
		for (int world = worldMinInclusive + 1; world < worldMaxExclusive; world++) {
			int cell = Math.floorDiv(world, coarseCellSize);
			if (cell == currentCell) {
				continue;
			}
			spans.add(new NestedFeedbackAxisSpan(currentCell, spanStartWorld - localOrigin, world - localOrigin));
			spanStartWorld = world;
			currentCell = cell;
		}
		spans.add(new NestedFeedbackAxisSpan(currentCell, spanStartWorld - localOrigin, worldMaxExclusive - localOrigin));
		return spans;
	}

	private List<NestedFeedbackAxisSpan> buildVerticalNestedFeedbackSpans(
			int worldMinInclusive,
			int worldMaxExclusive,
			int localOrigin,
			int verticalBaseY
	) {
		List<NestedFeedbackAxisSpan> spans = new ArrayList<>(2);
		int spanStartWorld = worldMinInclusive;
		int currentLayer = Mth.clamp(
				Math.floorDiv(worldMinInclusive - verticalBaseY, MESOSCALE_MET_LAYER_HEIGHT_BLOCKS),
				0,
				MESOSCALE_MET_MAX_LAYERS - 1
		);
		for (int world = worldMinInclusive + 1; world < worldMaxExclusive; world++) {
			int layer = Mth.clamp(
					Math.floorDiv(world - verticalBaseY, MESOSCALE_MET_LAYER_HEIGHT_BLOCKS),
					0,
					MESOSCALE_MET_MAX_LAYERS - 1
			);
			if (layer == currentLayer) {
				continue;
			}
			spans.add(new NestedFeedbackAxisSpan(currentLayer, spanStartWorld - localOrigin, world - localOrigin));
			spanStartWorld = world;
			currentLayer = layer;
		}
		spans.add(new NestedFeedbackAxisSpan(currentLayer, spanStartWorld - localOrigin, worldMaxExclusive - localOrigin));
		return spans;
	}

	private L2ToL1FeedbackLayout buildRegionNestedFeedbackLayout(WindowKey key) {
		ServerLevel world = resolveWorld(key.worldKey());
		int verticalBaseY = world == null ? 0 : Math.max(0, world.getMinY());
		int coreMinX = key.origin().getX() + REGION_HALO_CELLS;
		int coreMinY = key.origin().getY() + REGION_HALO_CELLS;
		int coreMinZ = key.origin().getZ() + REGION_HALO_CELLS;
		int coreMaxX = coreMinX + REGION_CORE_SIZE;
		int coreMaxY = coreMinY + REGION_CORE_SIZE;
		int coreMaxZ = coreMinZ + REGION_CORE_SIZE;

		List<NestedFeedbackAxisSpan> xSpans = buildHorizontalNestedFeedbackSpans(
				coreMinX,
				coreMaxX,
				key.origin().getX(),
				MESOSCALE_MET_CELL_SIZE_BLOCKS
		);
		List<NestedFeedbackAxisSpan> ySpans = buildVerticalNestedFeedbackSpans(
				coreMinY,
				coreMaxY,
				key.origin().getY(),
				verticalBaseY
		);
		List<NestedFeedbackAxisSpan> zSpans = buildHorizontalNestedFeedbackSpans(
				coreMinZ,
				coreMaxZ,
				key.origin().getZ(),
				MESOSCALE_MET_CELL_SIZE_BLOCKS
		);

		int binCount = xSpans.size() * ySpans.size() * zSpans.size();
		if (binCount <= 0 || binCount > NativeSimulationBridge.NESTED_FEEDBACK_MAX_BINS) {
			return null;
		}
		int[] nativeLayout = new int[binCount * NativeSimulationBridge.NESTED_FEEDBACK_LAYOUT_INTS_PER_BIN];
		List<L2ToL1FeedbackLayoutBin> bins = new ArrayList<>(binCount);
		int index = 0;
		for (NestedFeedbackAxisSpan xSpan : xSpans) {
			for (NestedFeedbackAxisSpan ySpan : ySpans) {
				for (NestedFeedbackAxisSpan zSpan : zSpans) {
					L2ToL1FeedbackLayoutBin bin = new L2ToL1FeedbackLayoutBin(
							xSpan.index(),
							ySpan.index(),
							zSpan.index(),
							xSpan.localMin(),
							xSpan.localMax(),
							ySpan.localMin(),
							ySpan.localMax(),
							zSpan.localMin(),
							zSpan.localMax()
					);
					bins.add(bin);
					int base = index * NativeSimulationBridge.NESTED_FEEDBACK_LAYOUT_INTS_PER_BIN;
					nativeLayout[base] = bin.cellX();
					nativeLayout[base + 1] = bin.layer();
					nativeLayout[base + 2] = bin.cellZ();
					nativeLayout[base + 3] = bin.localMinX();
					nativeLayout[base + 4] = bin.localMaxX();
					nativeLayout[base + 5] = bin.localMinY();
					nativeLayout[base + 6] = bin.localMaxY();
					nativeLayout[base + 7] = bin.localMinZ();
					nativeLayout[base + 8] = bin.localMaxZ();
					index++;
				}
			}
		}
		return new L2ToL1FeedbackLayout(nativeLayout, List.copyOf(bins));
	}

	private void ensureRegionNestedFeedbackLayoutInSimulation(WindowKey key, RegionRecord region) {
		if (simulationServiceId == 0L) {
			return;
		}
		if (region.nestedFeedbackLayout == null) {
			region.nestedFeedbackLayout = buildRegionNestedFeedbackLayout(key);
		}
		if (region.nestedFeedbackLayout == null) {
			return;
		}
		if (region.nestedFeedbackLayoutServiceId == simulationServiceId) {
			return;
		}
		if (simulationBridge.setRegionNestedFeedbackLayout(
				simulationServiceId,
				simulationRegionKey(key),
				L2_TO_L1_FEEDBACK_STEPS,
				region.nestedFeedbackLayout.nativeLayout()
		)) {
			region.nestedFeedbackLayoutServiceId = simulationServiceId;
		}
	}

	private void pollRegionNestedFeedback(WindowKey key, RegionRecord region) {
		if (simulationServiceId == 0L || region.nestedFeedbackLayout == null) {
			return;
		}
		List<L2ToL1FeedbackLayoutBin> bins = region.nestedFeedbackLayout.bins();
		if (bins.isEmpty()) {
			return;
		}
		float[] values = new float[bins.size() * NativeSimulationBridge.NESTED_FEEDBACK_VALUES_PER_BIN];
		if (!simulationBridge.pollRegionNestedFeedback(simulationServiceId, simulationRegionKey(key), values)) {
			return;
		}
		float scale = 1.0f / L2_TO_L1_FEEDBACK_STEPS;
		ConcurrentLinkedQueue<MesoscaleGrid.NestedFeedbackBin> queue = pendingNestedFeedbackBins.computeIfAbsent(
				key.worldKey(),
				ignored -> new ConcurrentLinkedQueue<>()
		);
		float volumeAverageSum = 0.0f;
		float bottomFluxDensitySum = 0.0f;
		float topFluxDensitySum = 0.0f;
		int packetBinCount = 0;
		for (int i = 0; i < bins.size(); i++) {
			int base = i * NativeSimulationBridge.NESTED_FEEDBACK_VALUES_PER_BIN;
			float volumeAverage = values[base] * scale;
			float densityAverage = values[base + 1] * scale;
			float momentumXAverage = values[base + 2] * scale;
			float momentumZAverage = values[base + 3] * scale;
			float airTemperatureVolumeAverage = values[base + 4] * scale;
			float surfaceTemperatureVolumeAverage = values[base + 5] * scale;
			float bottomAreaAverage = values[base + 6] * scale;
			float bottomMassFluxAverage = values[base + 7] * scale;
			float topAreaAverage = values[base + 8] * scale;
			float topMassFluxAverage = values[base + 9] * scale;
			if (!Float.isFinite(volumeAverage)
					|| !Float.isFinite(densityAverage)
					|| !Float.isFinite(momentumXAverage)
					|| !Float.isFinite(momentumZAverage)
					|| !Float.isFinite(airTemperatureVolumeAverage)
					|| !Float.isFinite(surfaceTemperatureVolumeAverage)
					|| !Float.isFinite(bottomAreaAverage)
					|| !Float.isFinite(bottomMassFluxAverage)
					|| !Float.isFinite(topAreaAverage)
					|| !Float.isFinite(topMassFluxAverage)) {
				continue;
			}
			if (!(volumeAverage > 0.0f) && !(bottomAreaAverage > 0.0f) && !(topAreaAverage > 0.0f)) {
				continue;
			}
			float bottomFluxDensity = bottomAreaAverage > 0.0f ? bottomMassFluxAverage / bottomAreaAverage : 0.0f;
			float topFluxDensity = topAreaAverage > 0.0f ? topMassFluxAverage / topAreaAverage : 0.0f;
			if (!Float.isFinite(bottomFluxDensity) || !Float.isFinite(topFluxDensity)) {
				continue;
			}
			L2ToL1FeedbackLayoutBin bin = bins.get(i);
			queue.add(new MesoscaleGrid.NestedFeedbackBin(
					bin.cellX(),
					bin.layer(),
					bin.cellZ(),
					volumeAverage,
					densityAverage,
					momentumXAverage,
					momentumZAverage,
					airTemperatureVolumeAverage,
					surfaceTemperatureVolumeAverage,
					bottomAreaAverage,
					bottomMassFluxAverage,
					topAreaAverage,
					topMassFluxAverage
			));
			packetBinCount++;
			volumeAverageSum += volumeAverage;
			bottomFluxDensitySum += bottomFluxDensity;
			topFluxDensitySum += topFluxDensity;
		}
		if (packetBinCount > 0) {
			final int packetBinCountFinal = packetBinCount;
			final float invPacketBinCount = 1.0f / packetBinCountFinal;
			final float meanVolumeAverage = volumeAverageSum * invPacketBinCount;
			final float meanBottomFluxDensity = bottomFluxDensitySum * invPacketBinCount;
			final float meanTopFluxDensity = topFluxDensitySum * invPacketBinCount;
			nestedFeedbackRuntimeDiagnostics.compute(key.worldKey(), (ignored, previous) -> new NestedFeedbackRuntimeDiagnostics(
					(previous == null ? 0L : previous.polledPacketCount()) + 1L,
					(previous == null ? 0L : previous.polledBinCount()) + packetBinCountFinal,
					packetBinCountFinal,
					tickCounter,
					meanVolumeAverage,
					meanBottomFluxDensity,
					meanTopFluxDensity
			));
		}
	}

	private boolean uploadRegionStaticFromMirror(WindowKey key, RegionRecord region) {
		if (simulationServiceId == 0L) {
			return false;
		}
		WorldMirror.SectionSnapshot[] snapshots = new WorldMirror.SectionSnapshot[WINDOW_SECTION_VOLUME];
		int readyCoreSections = 0;
		for (int sx = 0; sx < WINDOW_SECTION_COUNT; sx++) {
			for (int sy = 0; sy < WINDOW_SECTION_COUNT; sy++) {
				for (int sz = 0; sz < WINDOW_SECTION_COUNT; sz++) {
					BlockPos localOrigin = sectionOrigin(key.origin(), sx, sy, sz);
					WorldMirror.SectionSnapshot snapshot = worldMirror.peekSection(key.worldKey(), localOrigin);
					int sectionIndex = windowSectionIndex(sx, sy, sz);
					snapshots[sectionIndex] = snapshot;
					if (snapshot != null) {
						if (isCoreSection(sx, sy, sz)) {
							readyCoreSections++;
						}
					} else {
						requestWindowSectionIfNeeded(key, sx, sy, sz);
					}
				}
			}
		}
		if (readyCoreSections < CORE_SECTION_COUNT) {
			return false;
		}
		if (!region.staticUploaded) {
			return uploadFullRegionStaticFromMirror(key, region, snapshots);
		}
		return uploadRegionStaticPatchesFromMirror(key, region, snapshots);
	}

	private boolean uploadFullRegionStaticFromMirror(
			WindowKey key,
			RegionRecord region,
			WorldMirror.SectionSnapshot[] snapshots
	) {
		int cells = GRID_SIZE * GRID_SIZE * GRID_SIZE;
		byte[] obstacle = new byte[cells];
		byte[] surfaceKind = new byte[cells];
		short[] openFaceMask = new short[cells];
		float[] emitterPower = new float[cells];
		byte[] faceSkyExposure = new byte[cells * FACE_COUNT];
		byte[] faceDirectExposure = new byte[cells * FACE_COUNT];
		for (int sx = 0; sx < WINDOW_SECTION_COUNT; sx++) {
			for (int sy = 0; sy < WINDOW_SECTION_COUNT; sy++) {
				for (int sz = 0; sz < WINDOW_SECTION_COUNT; sz++) {
					int sectionIndex = windowSectionIndex(sx, sy, sz);
					WorldMirror.SectionSnapshot snapshot = snapshots[sectionIndex];
					int baseX = sx * CHUNK_SIZE;
					int baseY = sy * CHUNK_SIZE;
					int baseZ = sz * CHUNK_SIZE;
					if (snapshot == null) {
						for (int lx = 0; lx < CHUNK_SIZE; lx++) {
							int x = baseX + lx;
							for (int ly = 0; ly < CHUNK_SIZE; ly++) {
								int y = baseY + ly;
								for (int lz = 0; lz < CHUNK_SIZE; lz++) {
									int z = baseZ + lz;
									obstacle[gridCellIndex(x, y, z)] = 1;
								}
							}
						}
						region.setSection(sx, sy, sz, null);
						region.uploadedSectionVersions[sectionIndex] = Long.MIN_VALUE;
						continue;
					}
					writeSectionSnapshotIntoRegionBuffers(
							snapshot,
							baseX,
							baseY,
							baseZ,
							obstacle,
							surfaceKind,
							openFaceMask,
							emitterPower,
							faceSkyExposure,
							faceDirectExposure
					);
					region.setSection(sx, sy, sz, snapshot);
					region.uploadedSectionVersions[sectionIndex] = snapshot.version();
				}
			}
		}
		boolean uploaded = simulationBridge.uploadStaticRegion(
				simulationServiceId,
				simulationRegionKey(key),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE,
				obstacle,
				surfaceKind,
				openFaceMask,
				emitterPower,
				faceSkyExposure,
				faceDirectExposure
		);
		if (uploaded) {
			region.staticUploaded = true;
			region.fansDirty = true;
			region.forcingDirty = true;
		}
		return uploaded;
	}

	private boolean uploadRegionStaticPatchesFromMirror(
			WindowKey key,
			RegionRecord region,
			WorldMirror.SectionSnapshot[] snapshots
	) {
		boolean uploadedAny = false;
		byte[] obstacle = new byte[SECTION_CELL_COUNT];
		byte[] surfaceKind = new byte[SECTION_CELL_COUNT];
		short[] openFaceMask = new short[SECTION_CELL_COUNT];
		float[] emitterPower = new float[SECTION_CELL_COUNT];
		byte[] faceSkyExposure = new byte[SECTION_CELL_COUNT * FACE_COUNT];
		byte[] faceDirectExposure = new byte[SECTION_CELL_COUNT * FACE_COUNT];
		for (int sx = 0; sx < WINDOW_SECTION_COUNT; sx++) {
			for (int sy = 0; sy < WINDOW_SECTION_COUNT; sy++) {
				for (int sz = 0; sz < WINDOW_SECTION_COUNT; sz++) {
					int sectionIndex = windowSectionIndex(sx, sy, sz);
					WorldMirror.SectionSnapshot snapshot = snapshots[sectionIndex];
					if (snapshot == null) {
						continue;
					}
					if (region.uploadedSectionVersions[sectionIndex] == snapshot.version()) {
						continue;
					}
					writeSectionSnapshotIntoPatchBuffers(
							snapshot,
							obstacle,
							surfaceKind,
							openFaceMask,
							emitterPower,
							faceSkyExposure,
							faceDirectExposure
					);
					boolean patched = simulationBridge.uploadStaticRegionPatch(
							simulationServiceId,
							simulationRegionKey(key),
							GRID_SIZE,
							GRID_SIZE,
							GRID_SIZE,
							sx * CHUNK_SIZE,
							sy * CHUNK_SIZE,
							sz * CHUNK_SIZE,
							CHUNK_SIZE,
							CHUNK_SIZE,
							CHUNK_SIZE,
							obstacle,
							surfaceKind,
							openFaceMask,
							emitterPower,
							faceSkyExposure,
							faceDirectExposure
					);
					if (!patched) {
						return false;
					}
					region.setSection(sx, sy, sz, snapshot);
					region.uploadedSectionVersions[sectionIndex] = snapshot.version();
					if (isCoreSection(sx, sy, sz)) {
						region.markBackendResetPending();
					}
					uploadedAny = true;
				}
			}
		}
		if (uploadedAny) {
			region.fansDirty = true;
			region.forcingDirty = true;
			region.lastThermalRefreshTick = tickCounter - WINDOW_THERMAL_REFRESH_TICKS;
		}
		return true;
	}

	private boolean uploadSolveWindowCoreStaticBrickToRuntime(WindowKey key, RegionRecord region) {
		if (simulationServiceId == 0L || region.sections == null) {
			return false;
		}
		WorldMirror.SectionSnapshot[] coreSnapshots = coreSectionSnapshotsFromRegion(region);
		long signature = coreStaticBrickSignature(coreSnapshots);
		if (signature == Long.MIN_VALUE) {
			return false;
		}
		if (region.uploadedBrickStaticServiceId == simulationServiceId
				&& region.uploadedBrickStaticSignature == signature) {
			ensureWindowCoreDynamicBrickSeeded(key, coreSnapshots);
			return true;
		}
		boolean uploaded = uploadWindowCoreStaticBrickToRuntime(key, coreSnapshots);
		if (uploaded) {
			region.uploadedBrickStaticSignature = signature;
			region.uploadedBrickStaticServiceId = simulationServiceId;
			ensureWindowCoreDynamicBrickSeeded(key, coreSnapshots);
		}
		return uploaded;
	}

	private void prewarmMirrorOnlyWindowCoreStaticBrick(WindowKey key) {
		if (simulationServiceId == 0L || !simulationBridge.isLoaded() || desiredSolveWindowKeys.contains(key)) {
			return;
		}
		WorldMirror.SectionSnapshot[] coreSnapshots = coreSectionSnapshotsFromMirror(key, true);
		if (coreSnapshots == null) {
			return;
		}
		long signature = coreStaticBrickSignature(coreSnapshots);
		if (signature == Long.MIN_VALUE) {
			return;
		}
		if (Long.valueOf(signature).equals(mirrorOnlyUploadedBrickStaticSignatures.get(key))) {
			return;
		}
		if (uploadWindowCoreStaticBrickToRuntime(key, coreSnapshots)) {
			mirrorOnlyUploadedBrickStaticSignatures.put(key, signature);
			ensureWindowCoreDynamicBrickSeeded(key, coreSnapshots);
		}
	}

	private void ensureWindowCoreDynamicBrickSeeded(WindowKey key, WorldMirror.SectionSnapshot[] coreSnapshots) {
		if (simulationServiceId == 0L || !simulationBridge.isLoaded() || coreSnapshots == null || coreSnapshots.length != CORE_SECTION_COUNT) {
			return;
		}
		long signature = coreStaticBrickSignature(coreSnapshots);
		if (signature != Long.MIN_VALUE
				&& Long.valueOf(signature).equals(uploadedBrickDynamicSeedSignatures.get(key))) {
			return;
		}
		BlockPos coreOrigin = key.origin().offset(REGION_HALO_CELLS, REGION_HALO_CELLS, REGION_HALO_CELLS);
		int brickX = Math.floorDiv(coreOrigin.getX(), BRICK_RUNTIME_SIZE);
		int brickY = Math.floorDiv(coreOrigin.getY(), BRICK_RUNTIME_SIZE);
		int brickZ = Math.floorDiv(coreOrigin.getZ(), BRICK_RUNTIME_SIZE);
		int brickCells = BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE;
		byte[] obstacleMask = buildCoreBrickObstacleMask(coreSnapshots);
		if (obstacleMask == null) {
			return;
		}
		float[] flowState = new float[brickCells * RESPONSE_CHANNELS];
		float[] airTemperatureState = new float[brickCells];
		float[] surfaceTemperatureState = new float[brickCells];
		MesoscaleGrid mesoscaleGrid = mesoscaleMetGrids.get(key.worldKey());
		boolean seeded = mesoscaleGrid != null
				&& seedDynamicRegionFromMesoscale(
				coreOrigin,
				BRICK_RUNTIME_SIZE,
				BRICK_RUNTIME_SIZE,
				BRICK_RUNTIME_SIZE,
				obstacleMask,
				flowState,
				airTemperatureState,
				surfaceTemperatureState,
				mesoscaleGrid
		);
		if (!seeded) {
			seedDynamicRegionFromBoundarySample(
					key.worldKey(),
					coreOrigin,
					BRICK_RUNTIME_SIZE,
					BRICK_RUNTIME_SIZE,
					BRICK_RUNTIME_SIZE,
					obstacleMask,
					flowState,
					airTemperatureState,
					surfaceTemperatureState
			);
		}
		float seedMaxSpeed = maxFlowSpeedMetersPerSecond(flowState);
		if (seedMaxSpeed < ZERO_ATLAS_MAX_SPEED_EPS_MPS) {
			if (signature != Long.MIN_VALUE) {
				uploadedBrickDynamicSeedSignatures.remove(key);
			}
			return;
		}
		boolean uploaded = simulationBridge.uploadBrickWorldDynamicBrick(
				simulationServiceId,
				simulationWorldKey(key.worldKey()),
				BRICK_RUNTIME_SIZE,
				brickX,
				brickY,
				brickZ,
				flowState,
				airTemperatureState,
				surfaceTemperatureState
		);
		if (uploaded && signature != Long.MIN_VALUE) {
			uploadedBrickDynamicSeedSignatures.put(key, signature);
		} else if (!uploaded && signature != Long.MIN_VALUE) {
			uploadedBrickDynamicSeedSignatures.remove(key);
		}
	}

	private boolean uploadWindowCoreStaticBrickToRuntime(
			WindowKey key,
			WorldMirror.SectionSnapshot[] coreSnapshots
	) {
		if (simulationServiceId == 0L || coreSnapshots == null || coreSnapshots.length != CORE_SECTION_COUNT) {
			return false;
		}
		BlockPos coreOrigin = key.origin().offset(REGION_HALO_CELLS, REGION_HALO_CELLS, REGION_HALO_CELLS);
		return uploadBrickStaticSnapshotsToRuntime(
				key.worldKey(),
				Math.floorDiv(coreOrigin.getX(), BRICK_RUNTIME_SIZE),
				Math.floorDiv(coreOrigin.getY(), BRICK_RUNTIME_SIZE),
				Math.floorDiv(coreOrigin.getZ(), BRICK_RUNTIME_SIZE),
				coreSnapshots,
				false,
				false
		);
	}

	private boolean uploadResidentBrickStaticFromMirror(
			ResourceKey<Level> worldKey,
			int brickX,
			int brickY,
			int brickZ
	) {
		WorldMirror.SectionSnapshot[] snapshots = brickSectionSnapshotsFromMirror(worldKey, brickX, brickY, brickZ);
		return uploadBrickStaticSnapshotsToRuntime(worldKey, brickX, brickY, brickZ, snapshots, true, true);
	}

	private byte[] buildCoreBrickObstacleMask(WorldMirror.SectionSnapshot[] coreSnapshots) {
		return buildBrickObstacleMask(coreSnapshots, true);
	}

	private byte[] buildBrickObstacleMask(WorldMirror.SectionSnapshot[] snapshots, boolean fillMissingAsSolid) {
		if (snapshots == null) {
			return null;
		}
		int sectionAxisCount = BRICK_RUNTIME_SIZE / CHUNK_SIZE;
		if (snapshots.length != sectionAxisCount * sectionAxisCount * sectionAxisCount) {
			return null;
		}
		byte[] obstacle = new byte[BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE];
		int snapshotIndex = 0;
		for (int sx = 0; sx < sectionAxisCount; sx++) {
			for (int sy = 0; sy < sectionAxisCount; sy++) {
				for (int sz = 0; sz < sectionAxisCount; sz++) {
					WorldMirror.SectionSnapshot snapshot = snapshots[snapshotIndex++];
					int baseX = sx * CHUNK_SIZE;
					int baseY = sy * CHUNK_SIZE;
					int baseZ = sz * CHUNK_SIZE;
					if (snapshot == null) {
						if (!fillMissingAsSolid) {
							return null;
						}
						fillBrickSectionAsSolid(baseX, baseY, baseZ, obstacle);
					} else {
						writeSectionSnapshotIntoBrickObstacleBuffer(snapshot, baseX, baseY, baseZ, obstacle);
					}
				}
			}
		}
		return obstacle;
	}

	private WorldMirror.SectionSnapshot[] brickSectionSnapshotsFromMirror(
			ResourceKey<Level> worldKey,
			int brickX,
			int brickY,
			int brickZ
	) {
		int sectionAxisCount = BRICK_RUNTIME_SIZE / CHUNK_SIZE;
		WorldMirror.SectionSnapshot[] snapshots = new WorldMirror.SectionSnapshot[sectionAxisCount * sectionAxisCount * sectionAxisCount];
		int index = 0;
		int originX = brickX * BRICK_RUNTIME_SIZE;
		int originY = brickY * BRICK_RUNTIME_SIZE;
		int originZ = brickZ * BRICK_RUNTIME_SIZE;
		for (int sx = 0; sx < sectionAxisCount; sx++) {
			for (int sy = 0; sy < sectionAxisCount; sy++) {
				for (int sz = 0; sz < sectionAxisCount; sz++) {
					snapshots[index++] = worldMirror.peekSection(
							worldKey,
							new BlockPos(
									originX + sx * CHUNK_SIZE,
									originY + sy * CHUNK_SIZE,
									originZ + sz * CHUNK_SIZE
							)
					);
				}
			}
		}
		return snapshots;
	}

	private boolean uploadBrickStaticSnapshotsToRuntime(
			ResourceKey<Level> worldKey,
			int brickX,
			int brickY,
			int brickZ,
			WorldMirror.SectionSnapshot[] snapshots,
			boolean fillMissingAsSolid,
			boolean deferred
	) {
		if (simulationServiceId == 0L || snapshots == null) {
			return false;
		}
		long worldRuntimeKey = simulationWorldKey(worldKey);
		if (!simulationBridge.ensureBrickWorldRuntime(
				simulationServiceId,
				worldRuntimeKey,
				BRICK_RUNTIME_SIZE,
				1.0f,
				SOLVER_STEP_SECONDS
		)) {
			return false;
		}
		int sectionAxisCount = BRICK_RUNTIME_SIZE / CHUNK_SIZE;
		if (snapshots.length != sectionAxisCount * sectionAxisCount * sectionAxisCount) {
			return false;
		}
		int cells = BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE;
		byte[] obstacle = new byte[cells];
		byte[] surfaceKind = new byte[cells];
		short[] openFaceMask = new short[cells];
		float[] emitterPower = new float[cells];
		byte[] faceSkyExposure = new byte[cells * FACE_COUNT];
		byte[] faceDirectExposure = new byte[cells * FACE_COUNT];
		int snapshotIndex = 0;
		for (int sx = 0; sx < sectionAxisCount; sx++) {
			for (int sy = 0; sy < sectionAxisCount; sy++) {
				for (int sz = 0; sz < sectionAxisCount; sz++) {
					WorldMirror.SectionSnapshot snapshot = snapshots[snapshotIndex++];
					int baseX = sx * CHUNK_SIZE;
					int baseY = sy * CHUNK_SIZE;
					int baseZ = sz * CHUNK_SIZE;
					if (snapshot == null) {
						if (!fillMissingAsSolid) {
							return false;
						}
						fillBrickSectionAsSolid(baseX, baseY, baseZ, obstacle);
						continue;
					}
					writeSectionSnapshotIntoBrickBuffers(
							snapshot,
							baseX,
							baseY,
							baseZ,
							obstacle,
							surfaceKind,
							openFaceMask,
							emitterPower,
							faceSkyExposure,
							faceDirectExposure
					);
				}
			}
		}
		return deferred
				? simulationBridge.queueBrickWorldStaticBrickUpload(
				simulationServiceId,
				worldRuntimeKey,
				BRICK_RUNTIME_SIZE,
				brickX,
				brickY,
				brickZ,
				obstacle,
				surfaceKind,
				openFaceMask,
				emitterPower,
				faceSkyExposure,
				faceDirectExposure
		)
				: simulationBridge.uploadBrickWorldStaticBrick(
				simulationServiceId,
				worldRuntimeKey,
				BRICK_RUNTIME_SIZE,
				brickX,
				brickY,
				brickZ,
				obstacle,
				surfaceKind,
				openFaceMask,
				emitterPower,
				faceSkyExposure,
				faceDirectExposure
		);
	}

	private void fillBrickSectionAsSolid(int baseX, int baseY, int baseZ, byte[] obstacle) {
		for (int lx = 0; lx < CHUNK_SIZE; lx++) {
			int x = baseX + lx;
			for (int ly = 0; ly < CHUNK_SIZE; ly++) {
				int y = baseY + ly;
				for (int lz = 0; lz < CHUNK_SIZE; lz++) {
					int z = baseZ + lz;
					obstacle[patchCellIndex(x, y, z, BRICK_RUNTIME_SIZE)] = 1;
				}
			}
		}
	}

	private WorldMirror.SectionSnapshot[] coreSectionSnapshotsFromRegion(RegionRecord region) {
		if (region.sections == null) {
			return null;
		}
		WorldMirror.SectionSnapshot[] snapshots = new WorldMirror.SectionSnapshot[CORE_SECTION_COUNT];
		int index = 0;
		for (int sx = CORE_SECTION_MIN; sx <= CORE_SECTION_MAX; sx++) {
			for (int sy = CORE_SECTION_MIN; sy <= CORE_SECTION_MAX; sy++) {
				for (int sz = CORE_SECTION_MIN; sz <= CORE_SECTION_MAX; sz++) {
					snapshots[index++] = region.sectionAt(sx, sy, sz);
				}
			}
		}
		return snapshots;
	}

	private WorldMirror.SectionSnapshot[] coreSectionSnapshotsFromMirror(WindowKey key, boolean requestMissing) {
		WorldMirror.SectionSnapshot[] snapshots = new WorldMirror.SectionSnapshot[CORE_SECTION_COUNT];
		int index = 0;
		for (int sx = CORE_SECTION_MIN; sx <= CORE_SECTION_MAX; sx++) {
			for (int sy = CORE_SECTION_MIN; sy <= CORE_SECTION_MAX; sy++) {
				for (int sz = CORE_SECTION_MIN; sz <= CORE_SECTION_MAX; sz++) {
					WorldMirror.SectionSnapshot snapshot = worldMirror.peekSection(
							key.worldKey(),
							sectionOrigin(key.origin(), sx, sy, sz)
					);
					if (snapshot == null) {
						if (requestMissing) {
							requestWindowSectionIfNeeded(key, sx, sy, sz);
						}
						return null;
					}
					snapshots[index++] = snapshot;
				}
			}
		}
		return snapshots;
	}

	private long coreStaticBrickSignature(WorldMirror.SectionSnapshot[] coreSnapshots) {
		if (coreSnapshots == null || coreSnapshots.length != CORE_SECTION_COUNT) {
			return Long.MIN_VALUE;
		}
		long value = 1469598103934665603L;
		for (WorldMirror.SectionSnapshot snapshot : coreSnapshots) {
			if (snapshot == null) {
				return Long.MIN_VALUE;
			}
			value = (value ^ snapshot.version()) * 1099511628211L;
		}
		return value == 0L ? 1L : value;
	}

	private void writeSectionSnapshotIntoRegionBuffers(
			WorldMirror.SectionSnapshot snapshot,
			int baseX,
			int baseY,
			int baseZ,
			byte[] obstacle,
			byte[] surfaceKind,
			short[] openFaceMask,
			float[] emitterPower,
			byte[] faceSkyExposure,
			byte[] faceDirectExposure
	) {
		for (int lx = 0; lx < CHUNK_SIZE; lx++) {
			int x = baseX + lx;
			for (int ly = 0; ly < CHUNK_SIZE; ly++) {
				int y = baseY + ly;
				for (int lz = 0; lz < CHUNK_SIZE; lz++) {
					int z = baseZ + lz;
					int local = localSectionCellIndex(lx, ly, lz);
					int cell = gridCellIndex(x, y, z);
					obstacle[cell] = snapshot.obstacle()[local] >= 0.5f ? (byte) 1 : (byte) 0;
					surfaceKind[cell] = snapshot.surfaceKind()[local];
					openFaceMask[cell] = (short) Byte.toUnsignedInt(snapshot.openFaceMask()[local]);
					emitterPower[cell] = snapshot.emitterPowerWatts()[local];
					int faceBase = cell * FACE_COUNT;
					int localFaceBase = local * FACE_COUNT;
					System.arraycopy(snapshot.faceSkyExposure(), localFaceBase, faceSkyExposure, faceBase, FACE_COUNT);
					System.arraycopy(snapshot.faceDirectExposure(), localFaceBase, faceDirectExposure, faceBase, FACE_COUNT);
				}
			}
		}
	}

	private void writeSectionSnapshotIntoBrickBuffers(
			WorldMirror.SectionSnapshot snapshot,
			int baseX,
			int baseY,
			int baseZ,
			byte[] obstacle,
			byte[] surfaceKind,
			short[] openFaceMask,
			float[] emitterPower,
			byte[] faceSkyExposure,
			byte[] faceDirectExposure
	) {
		for (int lx = 0; lx < CHUNK_SIZE; lx++) {
			int x = baseX + lx;
			for (int ly = 0; ly < CHUNK_SIZE; ly++) {
				int y = baseY + ly;
				for (int lz = 0; lz < CHUNK_SIZE; lz++) {
					int z = baseZ + lz;
					int local = localSectionCellIndex(lx, ly, lz);
					int cell = patchCellIndex(x, y, z, BRICK_RUNTIME_SIZE);
					obstacle[cell] = snapshot.obstacle()[local] >= 0.5f ? (byte) 1 : (byte) 0;
					surfaceKind[cell] = snapshot.surfaceKind()[local];
					openFaceMask[cell] = (short) Byte.toUnsignedInt(snapshot.openFaceMask()[local]);
					emitterPower[cell] = snapshot.emitterPowerWatts()[local];
					int faceBase = cell * FACE_COUNT;
					int localFaceBase = local * FACE_COUNT;
					System.arraycopy(snapshot.faceSkyExposure(), localFaceBase, faceSkyExposure, faceBase, FACE_COUNT);
					System.arraycopy(snapshot.faceDirectExposure(), localFaceBase, faceDirectExposure, faceBase, FACE_COUNT);
				}
			}
		}
	}

	private void writeSectionSnapshotIntoBrickObstacleBuffer(
			WorldMirror.SectionSnapshot snapshot,
			int baseX,
			int baseY,
			int baseZ,
			byte[] obstacle
	) {
		for (int lx = 0; lx < CHUNK_SIZE; lx++) {
			int x = baseX + lx;
			for (int ly = 0; ly < CHUNK_SIZE; ly++) {
				int y = baseY + ly;
				for (int lz = 0; lz < CHUNK_SIZE; lz++) {
					int z = baseZ + lz;
					int local = localSectionCellIndex(lx, ly, lz);
					int cell = patchCellIndex(x, y, z, BRICK_RUNTIME_SIZE);
					obstacle[cell] = snapshot.obstacle()[local] >= 0.5f ? (byte) 1 : (byte) 0;
				}
			}
		}
	}

	private void writeSectionSnapshotIntoPatchBuffers(
			WorldMirror.SectionSnapshot snapshot,
			byte[] obstacle,
			byte[] surfaceKind,
			short[] openFaceMask,
			float[] emitterPower,
			byte[] faceSkyExposure,
			byte[] faceDirectExposure
	) {
		for (int cell = 0; cell < SECTION_CELL_COUNT; cell++) {
			obstacle[cell] = snapshot.obstacle()[cell] >= 0.5f ? (byte) 1 : (byte) 0;
			surfaceKind[cell] = snapshot.surfaceKind()[cell];
			openFaceMask[cell] = (short) Byte.toUnsignedInt(snapshot.openFaceMask()[cell]);
			emitterPower[cell] = snapshot.emitterPowerWatts()[cell];
		}
		System.arraycopy(snapshot.faceSkyExposure(), 0, faceSkyExposure, 0, SECTION_CELL_COUNT * FACE_COUNT);
		System.arraycopy(snapshot.faceDirectExposure(), 0, faceDirectExposure, 0, SECTION_CELL_COUNT * FACE_COUNT);
	}

	private void requestWindowSectionIfNeeded(WindowKey key, int sx, int sy, int sz) {
		MinecraftServer server = currentServer;
		if (server == null) {
			return;
		}
		worldMirror.requestSectionBuild(
				server,
				key.worldKey(),
				sectionOrigin(key.origin(), sx, sy, sz),
				isCoreSection(sx, sy, sz)
		);
	}

	private void activateWindowRegionInSimulation(WindowKey key) {
		if (simulationServiceId == 0L) {
			return;
		}
		simulationBridge.activateRegion(
				simulationServiceId,
				simulationRegionKey(key),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE
		);
	}

	private void deactivateWindowRegionInSimulation(WindowKey key) {
		if (simulationServiceId == 0L) {
			return;
		}
		simulationBridge.deactivateRegion(simulationServiceId, simulationRegionKey(key));
	}

	private void refreshRegionLifecycle(WindowKey key, RegionRecord region) {
		if (simulationServiceId == 0L) {
			region.serviceActive = false;
			region.serviceReady = false;
			return;
		}
		long regionKey = simulationRegionKey(key);
		boolean hasRegion = simulationBridge.hasRegion(simulationServiceId, regionKey);
		region.serviceActive = hasRegion;
		region.serviceReady = hasRegion && simulationBridge.isRegionReady(simulationServiceId, regionKey);
	}

	private void attachRegionWindow(RegionRecord region) {
		region.attach();
	}

	private void detachRegionWindow(WindowKey key, RegionRecord region) {
		region.detach();
	}

	private void persistWindowDynamicRegion(WindowKey key, RegionRecord region, boolean synchronousPersist) {
		if (simulationServiceId == 0L) {
			return;
		}
		float[] flowState = new float[FLOW_COUNT];
		int cells = GRID_SIZE * GRID_SIZE * GRID_SIZE;
		float[] airTemperatureState = new float[cells];
		float[] surfaceTemperatureState = new float[cells];
		if (!simulationBridge.exportDynamicRegion(
				simulationServiceId,
				simulationRegionKey(key),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE,
				flowState,
				airTemperatureState,
				surfaceTemperatureState
		)) {
			return;
		}
		ServerLevel world = resolveWorld(key.worldKey());
		if (world != null) {
			if (synchronousPersist) {
				dynamicStore.storeCapturedRegionSync(
						world,
						key.worldKey(),
						key.origin(),
						GRID_SIZE,
						GRID_SIZE,
						GRID_SIZE,
						flowState,
						airTemperatureState,
						surfaceTemperatureState
				);
			} else {
				dynamicStore.storeCapturedRegion(
						world,
						key.worldKey(),
						key.origin(),
						GRID_SIZE,
						GRID_SIZE,
						GRID_SIZE,
						flowState,
						airTemperatureState,
						surfaceTemperatureState
				);
			}
		}
	}

	private boolean ensureWindowDynamicRegionInitialized(WindowKey key, RegionRecord region) {
		if (simulationServiceId == 0L || region.sections == null) {
			return false;
		}
		long regionKey = simulationRegionKey(key);
		if (simulationBridge.hasRegionContext(simulationServiceId, regionKey)) {
			return true;
		}
		int cells = GRID_SIZE * GRID_SIZE * GRID_SIZE;
		float[] flowState = new float[FLOW_COUNT];
		float[] airTemperatureState = new float[cells];
		float[] surfaceTemperatureState = new float[cells];
		ServerLevel world = resolveWorld(key.worldKey());
		if (world != null && dynamicStore.loadRegion(
				world,
				key.worldKey(),
				key.origin(),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE,
				flowState,
				airTemperatureState,
				surfaceTemperatureState
		)) {
			overlaySeedWindowCoreFromBrickRuntime(key, buildRegionObstacleMask(region), flowState, airTemperatureState, surfaceTemperatureState);
			if (simulationBridge.importDynamicRegion(
					simulationServiceId,
					regionKey,
					GRID_SIZE,
					GRID_SIZE,
					GRID_SIZE,
					flowState,
					airTemperatureState,
					surfaceTemperatureState
			)) {
				return true;
			}
		}
		return seedWindowDynamicRegionFromNestedMet(key, region, flowState, airTemperatureState, surfaceTemperatureState);
	}

	private boolean seedWindowDynamicRegionFromNestedMet(
			WindowKey key,
			RegionRecord region,
			float[] flowState,
			float[] airTemperatureState,
			float[] surfaceTemperatureState
	) {
		byte[] obstacleMask = buildRegionObstacleMask(region);
		MesoscaleGrid mesoscaleGrid = mesoscaleMetGrids.get(key.worldKey());
		boolean seeded = mesoscaleGrid != null
				&& seedWindowDynamicRegionFromMesoscale(
				key,
				obstacleMask,
				flowState,
				airTemperatureState,
				surfaceTemperatureState,
				mesoscaleGrid
		);
		if (!seeded) {
			seedWindowDynamicRegionFromBoundarySample(
					key,
					obstacleMask,
					flowState,
					airTemperatureState,
					surfaceTemperatureState
			);
		}
		overlaySeedWindowCoreFromBrickRuntime(key, obstacleMask, flowState, airTemperatureState, surfaceTemperatureState);
		return simulationBridge.importDynamicRegion(
				simulationServiceId,
				simulationRegionKey(key),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE,
				flowState,
				airTemperatureState,
				surfaceTemperatureState
		);
	}

	private void overlaySeedWindowCoreFromBrickRuntime(
			WindowKey key,
			byte[] obstacleMask,
			float[] flowState,
			float[] airTemperatureState,
			float[] surfaceTemperatureState
	) {
		if (simulationServiceId == 0L || !simulationBridge.isLoaded()) {
			return;
		}
		int coreCells = BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE;
		float[] coreFlowState = new float[coreCells * RESPONSE_CHANNELS];
		float[] coreAirTemperatureState = new float[coreCells];
		float[] coreSurfaceTemperatureState = new float[coreCells];
		BlockPos coreOrigin = key.origin().offset(REGION_HALO_CELLS, REGION_HALO_CELLS, REGION_HALO_CELLS);
		boolean copied = simulationBridge.copyBrickWorldDynamicBrick(
				simulationServiceId,
				simulationWorldKey(key.worldKey()),
				BRICK_RUNTIME_SIZE,
				Math.floorDiv(coreOrigin.getX(), BRICK_RUNTIME_SIZE),
				Math.floorDiv(coreOrigin.getY(), BRICK_RUNTIME_SIZE),
				Math.floorDiv(coreOrigin.getZ(), BRICK_RUNTIME_SIZE),
				coreFlowState,
				coreAirTemperatureState,
				coreSurfaceTemperatureState
		);
		if (!copied) {
			return;
		}
		for (int x = 0; x < BRICK_RUNTIME_SIZE; x++) {
			for (int y = 0; y < BRICK_RUNTIME_SIZE; y++) {
				for (int z = 0; z < BRICK_RUNTIME_SIZE; z++) {
					int dstX = REGION_HALO_CELLS + x;
					int dstY = REGION_HALO_CELLS + y;
					int dstZ = REGION_HALO_CELLS + z;
					int dstCell = gridCellIndex(dstX, dstY, dstZ);
					if (obstacleMask[dstCell] != 0) {
						continue;
					}
					int srcCell = patchCellIndex(x, y, z, BRICK_RUNTIME_SIZE);
					int dstBase = dstCell * RESPONSE_CHANNELS;
					int srcBase = srcCell * RESPONSE_CHANNELS;
					for (int channel = 0; channel < RESPONSE_CHANNELS; channel++) {
						flowState[dstBase + channel] = coreFlowState[srcBase + channel];
					}
					airTemperatureState[dstCell] = coreAirTemperatureState[srcCell];
					surfaceTemperatureState[dstCell] = coreSurfaceTemperatureState[srcCell];
				}
			}
		}
	}

	private boolean seedWindowDynamicRegionFromMesoscale(
			WindowKey key,
			byte[] obstacleMask,
			float[] flowState,
			float[] airTemperatureState,
			float[] surfaceTemperatureState,
			MesoscaleGrid mesoscaleGrid
	) {
		return seedDynamicRegionFromMesoscale(
				key.origin(),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE,
				obstacleMask,
				flowState,
				airTemperatureState,
				surfaceTemperatureState,
				mesoscaleGrid
		);
	}

	private void seedWindowDynamicRegionFromBoundarySample(
			WindowKey key,
			byte[] obstacleMask,
			float[] flowState,
			float[] airTemperatureState,
			float[] surfaceTemperatureState
	) {
		seedDynamicRegionFromBoundarySample(
				key.worldKey(),
				key.origin(),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE,
				obstacleMask,
				flowState,
				airTemperatureState,
				surfaceTemperatureState
		);
	}

	private boolean seedDynamicRegionFromMesoscale(
			BlockPos origin,
			int sizeX,
			int sizeY,
			int sizeZ,
			byte[] obstacleMask,
			float[] flowState,
			float[] airTemperatureState,
			float[] surfaceTemperatureState,
			MesoscaleGrid mesoscaleGrid
	) {
		int cells = sizeX * sizeY * sizeZ;
		float[] windX = new float[cells];
		float[] windY = new float[cells];
		float[] windZ = new float[cells];
		if (!mesoscaleGrid.seedL2Window(
				origin,
				sizeX,
				sizeY,
				sizeZ,
				windX,
				windY,
				windZ,
				airTemperatureState,
				surfaceTemperatureState
		)) {
			return false;
		}
		for (int cell = 0; cell < cells; cell++) {
			if (obstacleMask[cell] != 0) {
				continue;
			}
			int base = cell * RESPONSE_CHANNELS;
			flowState[base] = windX[cell] / NATIVE_VELOCITY_SCALE;
			flowState[base + 1] = windY[cell] / NATIVE_VELOCITY_SCALE;
			flowState[base + 2] = windZ[cell] / NATIVE_VELOCITY_SCALE;
		}
		return true;
	}

	private void seedDynamicRegionFromBoundarySample(
			ResourceKey<Level> worldKey,
			BlockPos origin,
			int sizeX,
			int sizeY,
			int sizeZ,
			byte[] obstacleMask,
			float[] flowState,
			float[] airTemperatureState,
			float[] surfaceTemperatureState
	) {
		BlockPos center = origin.offset(sizeX / 2, sizeY / 2, sizeZ / 2);
		NestedBoundaryCoupler.BoundarySample boundarySample = sampleNestedBoundaryAtPosition(worldKey, center);
		float seedVx = boundarySample == null ? 0.0f : boundarySample.windX() / NATIVE_VELOCITY_SCALE;
		float seedVy = boundarySample == null ? 0.0f : boundarySample.windY() / NATIVE_VELOCITY_SCALE;
		float seedVz = boundarySample == null ? 0.0f : boundarySample.windZ() / NATIVE_VELOCITY_SCALE;
		boolean seedVyAvailable = boundarySample != null && boundarySample.verticalWindAvailable();
		float seedAirTemperature = boundarySample == null
				? THERMAL_BASE_AMBIENT_AIR_TEMPERATURE_K
				: boundarySample.ambientAirTemperatureKelvin();
		float seedSurfaceTemperature = boundarySample == null
				? THERMAL_BASE_AMBIENT_AIR_TEMPERATURE_K + THERMAL_DEEP_GROUND_OFFSET_K
				: boundarySample.deepGroundTemperatureKelvin();
		int cells = sizeX * sizeY * sizeZ;
		for (int cell = 0; cell < cells; cell++) {
			airTemperatureState[cell] = seedAirTemperature;
			surfaceTemperatureState[cell] = seedSurfaceTemperature;
			if (obstacleMask[cell] != 0) {
				continue;
			}
			int base = cell * RESPONSE_CHANNELS;
			flowState[base] = seedVx;
			flowState[base + 1] = seedVy;
			flowState[base + 2] = seedVz;
		}
		if (!seedVyAvailable) {
			deriveSeedVerticalVelocity(sizeX, sizeY, sizeZ, obstacleMask, flowState);
		}
	}

	private void deriveSeedVerticalVelocity(int sizeX, int sizeY, int sizeZ, byte[] obstacleMask, float[] flowState) {
		float[] divergenceColumn = new float[sizeY];
		float[] verticalVelocityColumn = new float[sizeY];
		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				float maxHorizontalSpeed = 0.0f;
				for (int y = 0; y < sizeY; y++) {
					int cell = patchCellIndex3d(x, y, z, sizeY, sizeZ);
					int base = cell * RESPONSE_CHANNELS;
					if (obstacleMask[cell] != 0) {
						flowState[base + 1] = 0.0f;
						divergenceColumn[y] = 0.0f;
						continue;
					}
					float vx = flowState[base];
					float vz = flowState[base + 2];
					float vxMinus = sampleSeedFlowComponent(sizeX, sizeY, sizeZ, obstacleMask, flowState, x - 1, y, z, 0, vx);
					float vxPlus = sampleSeedFlowComponent(sizeX, sizeY, sizeZ, obstacleMask, flowState, x + 1, y, z, 0, vx);
					float vzMinus = sampleSeedFlowComponent(sizeX, sizeY, sizeZ, obstacleMask, flowState, x, y, z - 1, 2, vz);
					float vzPlus = sampleSeedFlowComponent(sizeX, sizeY, sizeZ, obstacleMask, flowState, x, y, z + 1, 2, vz);
					divergenceColumn[y] = 0.5f * ((vxPlus - vxMinus) + (vzPlus - vzMinus));
					maxHorizontalSpeed = Math.max(maxHorizontalSpeed, (float) Math.sqrt(vx * vx + vz * vz));
				}
				integrateSeedVerticalVelocityColumn(
						sizeX,
						sizeY,
						sizeZ,
						x,
						z,
						obstacleMask,
						flowState,
						divergenceColumn,
						verticalVelocityColumn,
						maxHorizontalSpeed
				);
			}
		}
	}

	private float sampleSeedFlowComponent(
			int sizeX,
			int sizeY,
			int sizeZ,
			byte[] obstacleMask,
			float[] flowState,
			int x,
			int y,
			int z,
			int componentOffset,
			float fallback
	) {
		if (!inBounds(x, y, z, sizeX, sizeY, sizeZ)) {
			return fallback;
		}
		int cell = patchCellIndex3d(x, y, z, sizeY, sizeZ);
		if (obstacleMask[cell] != 0) {
			return fallback;
		}
		return flowState[cell * RESPONSE_CHANNELS + componentOffset];
	}

	private void integrateSeedVerticalVelocityColumn(
			int sizeX,
			int sizeY,
			int sizeZ,
			int x,
			int z,
			byte[] obstacleMask,
			float[] flowState,
			float[] divergenceColumn,
			float[] verticalVelocityColumn,
			float maxHorizontalSpeed
	) {
		int y = 0;
		float clamp = Math.max(0.15f / NATIVE_VELOCITY_SCALE, maxHorizontalSpeed * NESTED_BOUNDARY_MAX_VY_RATIO);
		while (y < sizeY) {
			int cell = patchCellIndex3d(x, y, z, sizeY, sizeZ);
			if (obstacleMask[cell] != 0) {
				flowState[cell * RESPONSE_CHANNELS + 1] = 0.0f;
				y++;
				continue;
			}
			int startY = y;
			while (y < sizeY && obstacleMask[patchCellIndex3d(x, y, z, sizeY, sizeZ)] == 0) {
				y++;
			}
			int length = y - startY;
			verticalVelocityColumn[0] = 0.0f;
			for (int i = 1; i < length; i++) {
				int currentY = startY + i;
				int previousY = currentY - 1;
				verticalVelocityColumn[i] = verticalVelocityColumn[i - 1]
						- 0.5f * (divergenceColumn[previousY] + divergenceColumn[currentY]);
			}
			float mean = 0.0f;
			for (int i = 0; i < length; i++) {
				mean += verticalVelocityColumn[i];
			}
			mean /= length;
			for (int i = 0; i < length; i++) {
				float vy = Mth.clamp(verticalVelocityColumn[i] - mean, -clamp, clamp);
				int runCell = patchCellIndex3d(x, startY + i, z, sizeY, sizeZ);
				flowState[runCell * RESPONSE_CHANNELS + 1] = vy;
			}
		}
	}

	private ServerLevel resolveWorld(ResourceKey<Level> worldKey) {
		MinecraftServer server = currentServer;
		return server == null ? null : server.getLevel(worldKey);
	}

	private boolean inBounds(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < GRID_SIZE && y < GRID_SIZE && z < GRID_SIZE;
	}

	private boolean inBounds(int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
		return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
	}

	private boolean inSectionBounds(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < CHUNK_SIZE && y < CHUNK_SIZE && z < CHUNK_SIZE;
	}

	private byte[] buildRegionObstacleMask(RegionRecord region) {
		byte[] obstacleMask = new byte[GRID_SIZE * GRID_SIZE * GRID_SIZE];
		if (region.sections == null) {
			return obstacleMask;
		}
		for (int sx = 0; sx < WINDOW_SECTION_COUNT; sx++) {
			int baseX = sx * CHUNK_SIZE;
			for (int sy = 0; sy < WINDOW_SECTION_COUNT; sy++) {
				int baseY = sy * CHUNK_SIZE;
				for (int sz = 0; sz < WINDOW_SECTION_COUNT; sz++) {
					int baseZ = sz * CHUNK_SIZE;
					WorldMirror.SectionSnapshot snapshot = region.sectionAt(sx, sy, sz);
					if (snapshot == null) {
						for (int lx = 0; lx < CHUNK_SIZE; lx++) {
							int x = baseX + lx;
							for (int ly = 0; ly < CHUNK_SIZE; ly++) {
								int y = baseY + ly;
								for (int lz = 0; lz < CHUNK_SIZE; lz++) {
									obstacleMask[gridCellIndex(x, y, baseZ + lz)] = 1;
								}
							}
						}
						continue;
					}
					for (int lx = 0; lx < CHUNK_SIZE; lx++) {
						int x = baseX + lx;
						for (int ly = 0; ly < CHUNK_SIZE; ly++) {
							int y = baseY + ly;
							for (int lz = 0; lz < CHUNK_SIZE; lz++) {
								int cell = gridCellIndex(x, y, baseZ + lz);
								obstacleMask[cell] = snapshot.obstacle()[localSectionCellIndex(lx, ly, lz)] >= 0.5f ? (byte) 1 : (byte) 0;
							}
						}
					}
				}
			}
		}
		return obstacleMask;
	}

	public static float runtimeFanSpeedMetersPerSecond() {
		return INFLOW_SPEED;
	}

	private boolean obstacleAt(RegionRecord region, int x, int y, int z) {
		if (!inBounds(x, y, z)) {
			return true;
		}
		WorldMirror.SectionSnapshot section = region.sectionAt(x / CHUNK_SIZE, y / CHUNK_SIZE, z / CHUNK_SIZE);
		if (section == null) {
			return true;
		}
		return section.obstacle()[localSectionCellIndex(x % CHUNK_SIZE, y % CHUNK_SIZE, z % CHUNK_SIZE)] > 0.5f;
	}

	private void applyFanAtVoxelToForcing(
			RegionRecord region,
			byte[] fanMask,
			float[] fanVxField,
			float[] fanVyField,
			float[] fanVzField,
			int x,
			int y,
			int z,
			float fanVx,
			float fanVy,
			float fanVz
	) {
		if (!inBounds(x, y, z) || obstacleAt(region, x, y, z)) {
			return;
		}
		int cell = gridCellIndex(x, y, z);
		fanMask[cell] = 1;
		fanVxField[cell] += fanVx;
		fanVyField[cell] += fanVy;
		fanVzField[cell] += fanVz;
	}

	private void applyFanSourceToForcing(
			RegionRecord region,
			byte[] fanMask,
			float[] fanVxField,
			float[] fanVyField,
			float[] fanVzField,
			FanSource fan,
			int minX,
			int minY,
			int minZ
	) {
		BlockPos inflowPos = fan.pos().relative(fan.facing());
		int cx = inflowPos.getX() - minX;
		int cy = inflowPos.getY() - minY;
		int cz = inflowPos.getZ() - minZ;

		float inflowSpeed = runtimeFanSpeedMetersPerSecond();
		float fanVx = fan.facing().getStepX() * inflowSpeed;
		float fanVy = fan.facing().getStepY() * inflowSpeed;
		float fanVz = fan.facing().getStepZ() * inflowSpeed;

		int radius2 = FAN_RADIUS * FAN_RADIUS;
		switch (fan.facing().getAxis()) {
			case X -> {
				for (int y = cy - FAN_RADIUS; y <= cy + FAN_RADIUS; y++) {
					for (int z = cz - FAN_RADIUS; z <= cz + FAN_RADIUS; z++) {
						int dy = y - cy;
						int dz = z - cz;
						if (dy * dy + dz * dz > radius2) {
							continue;
						}
						applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, y, z, fanVx, fanVy, fanVz);
					}
				}
			}
			case Y -> {
				for (int x = cx - FAN_RADIUS; x <= cx + FAN_RADIUS; x++) {
					for (int z = cz - FAN_RADIUS; z <= cz + FAN_RADIUS; z++) {
						int dx = x - cx;
						int dz = z - cz;
						if (dx * dx + dz * dz > radius2) {
							continue;
						}
						applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, x, cy, z, fanVx, fanVy, fanVz);
					}
				}
			}
			case Z -> {
				for (int x = cx - FAN_RADIUS; x <= cx + FAN_RADIUS; x++) {
					for (int y = cy - FAN_RADIUS; y <= cy + FAN_RADIUS; y++) {
						int dx = x - cx;
						int dy = y - cy;
						if (dx * dx + dy * dy > radius2) {
							continue;
						}
						applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, x, y, cz, fanVx, fanVy, fanVz);
					}
				}
			}
			default ->
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, cy, cz, fanVx, fanVy, fanVz);
		}
		applyDuctJetToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, fan, minX, minY, minZ);
	}

	private void applyDuctJetToForcing(
			RegionRecord region,
			byte[] fanMask,
			float[] fanVxField,
			float[] fanVyField,
			float[] fanVzField,
			FanSource fan,
			int minX,
			int minY,
			int minZ
	) {
		int level = ductLevel(fan.ductLength());
		if (level <= 0) {
			return;
		}

		BlockPos inflowPos = fan.pos().relative(fan.facing());
		int sx = inflowPos.getX() - minX;
		int sy = inflowPos.getY() - minY;
		int sz = inflowPos.getZ() - minZ;
		int dx = fan.facing().getStepX();
		int dy = fan.facing().getStepY();
		int dz = fan.facing().getStepZ();
		float levelBoost = switch (level) {
			case 1 -> 1.05f;
			case 2 -> 1.25f;
			default -> 1.55f;
		};

		float inflowSpeed = runtimeFanSpeedMetersPerSecond();
		float baseVx = dx * inflowSpeed;
		float baseVy = dy * inflowSpeed;
		float baseVz = dz * inflowSpeed;
		int range = switch (level) {
			case 1 -> 8;
			case 2 -> 14;
			default -> DUCT_JET_RANGE;
		};
		for (int step = 0; step < range; step++) {
			float t = range > 1 ? (float) step / (range - 1) : 0.0f;
			float decay = 1.0f - 0.55f * t;
			float coreScale = levelBoost * Math.max(0.35f, decay);
			int cx = sx + dx * step;
			int cy = sy + dy * step;
			int cz = sz + dz * step;
			applyFanAtVoxelToForcing(
					region,
					fanMask,
					fanVxField,
					fanVyField,
					fanVzField,
					cx,
					cy,
					cz,
					baseVx * coreScale,
					baseVy * coreScale,
					baseVz * coreScale
			);

			float edgeFalloff = Math.max(0.10f, 1.0f - 0.90f * t);
			float edgeScale = coreScale * DUCT_EDGE_FACTOR * edgeFalloff;
			switch (fan.facing().getAxis()) {
				case X -> {
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, cy + 1, cz, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, cy - 1, cz, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, cy, cz + 1, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, cy, cz - 1, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
				}
				case Y -> {
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx + 1, cy, cz, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx - 1, cy, cz, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, cy, cz + 1, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, cy, cz - 1, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
				}
				case Z -> {
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx + 1, cy, cz, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx - 1, cy, cz, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, cy + 1, cz, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
					applyFanAtVoxelToForcing(region, fanMask, fanVxField, fanVyField, fanVzField, cx, cy - 1, cz, baseVx * edgeScale, baseVy * edgeScale, baseVz * edgeScale);
				}
			}
		}
	}

	public static int ductLevel(int ductLength) {
		if (ductLength >= DUCT_LEVEL_THREE_MIN) {
			return 3;
		}
		if (ductLength >= DUCT_LEVEL_TWO_MIN) {
			return 2;
		}
		if (ductLength >= DUCT_LEVEL_ONE_MIN) {
			return 1;
		}
		return 0;
	}

	private BackgroundMetGrid.Sample sampleBackgroundMetAtWindow(WindowKey key) {
		BackgroundMetGrid grid = backgroundMetGrids.get(key.worldKey());
		if (grid == null) {
			return null;
		}
		return grid.sample(key.origin().offset(GRID_SIZE / 2, GRID_SIZE / 2, GRID_SIZE / 2));
	}

	private MesoscaleGrid.Sample sampleMesoscaleMetAtWindow(WindowKey key) {
		MesoscaleGrid grid = mesoscaleMetGrids.get(key.worldKey());
		if (grid == null) {
			return null;
		}
		return grid.sample(key.origin().offset(GRID_SIZE / 2, GRID_SIZE / 2, GRID_SIZE / 2));
	}

	private NestedBoundaryCoupler.BoundarySample sampleNestedBoundaryAtWindow(WindowKey key) {
		MesoscaleGrid.Sample mesoscaleSample = sampleMesoscaleMetAtWindow(key);
		if (mesoscaleSample != null) {
			return nestedBoundaryCoupler.fromMesoscaleSample(mesoscaleSample);
		}
		return nestedBoundaryCoupler.fromBackgroundSample(sampleBackgroundMetAtWindow(key));
	}

	public static NestedBoundaryCoupler.BoundarySample sampleNestedBoundaryAtPosition(ResourceKey<Level> worldKey, BlockPos pos) {
		MesoscaleGrid.Sample mesoscaleSample = sampleMesoscaleMet(worldKey, pos);
		if (mesoscaleSample != null) {
			return nestedBoundaryCoupler.fromMesoscaleSample(mesoscaleSample);
		}
		BackgroundMetGrid.Sample backgroundSample = sampleBackgroundMet(worldKey, pos);
		if (backgroundSample != null) {
			return nestedBoundaryCoupler.fromBackgroundSample(backgroundSample);
		}
		return new NestedBoundaryCoupler.BoundarySample(
				0.0f,
				0.0f,
				0.0f,
				THERMAL_BASE_AMBIENT_AIR_TEMPERATURE_K,
				THERMAL_BASE_AMBIENT_AIR_TEMPERATURE_K + THERMAL_DEEP_GROUND_OFFSET_K
		);
	}

	private BoundaryFieldData sampleNestedBoundaryFieldAtWindow(
			WindowKey key,
			int externalFaceMask,
			NestedBoundaryCoupler.BoundarySample fallback
	) {
		int res = NESTED_BOUNDARY_FACE_RESOLUTION;
		int faceCells = FACE_COUNT * res * res;
		float[] windX = new float[faceCells];
		float[] windY = new float[faceCells];
		float[] windZ = new float[faceCells];
		float[] airTemperature = new float[faceCells];
		BlockPos origin = key.origin();
		double minX = origin.getX();
		double minY = origin.getY();
		double minZ = origin.getZ();
		double maxX = minX + GRID_SIZE;
		double maxY = minY + GRID_SIZE;
		double maxZ = minZ + GRID_SIZE;

		if ((externalFaceMask & (1 << Direction.WEST.ordinal())) != 0) {
			fillVerticalBoundaryFace(key.worldKey(), Direction.WEST.ordinal(), minX + 0.5, minZ, maxZ, minY, maxY, res, windX, windY, windZ, airTemperature, fallback);
		}
		if ((externalFaceMask & (1 << Direction.EAST.ordinal())) != 0) {
			fillVerticalBoundaryFace(key.worldKey(), Direction.EAST.ordinal(), maxX - 0.5, minZ, maxZ, minY, maxY, res, windX, windY, windZ, airTemperature, fallback);
		}
		if ((externalFaceMask & (1 << Direction.NORTH.ordinal())) != 0) {
			fillVerticalBoundaryFace(key.worldKey(), Direction.NORTH.ordinal(), minZ + 0.5, minX, maxX, minY, maxY, res, windX, windY, windZ, airTemperature, fallback);
		}
		if ((externalFaceMask & (1 << Direction.SOUTH.ordinal())) != 0) {
			fillVerticalBoundaryFace(key.worldKey(), Direction.SOUTH.ordinal(), maxZ - 0.5, minX, maxX, minY, maxY, res, windX, windY, windZ, airTemperature, fallback);
		}
		if ((externalFaceMask & (1 << Direction.DOWN.ordinal())) != 0) {
			fillHorizontalBoundaryFace(key.worldKey(), Direction.DOWN.ordinal(), minX, maxX, minZ, maxZ, minY + 0.5, res, windX, windY, windZ, airTemperature, minY, maxY, fallback);
		}
		if ((externalFaceMask & (1 << Direction.UP.ordinal())) != 0) {
			fillHorizontalBoundaryFace(key.worldKey(), Direction.UP.ordinal(), minX, maxX, minZ, maxZ, maxY - 0.5, res, windX, windY, windZ, airTemperature, minY, maxY, fallback);
		}
		return new BoundaryFieldData(res, externalFaceMask, windX, windY, windZ, airTemperature);
	}


	private List<TornadoRegionDescriptor> collectTornadoRegionDescriptors(WindowKey key) {
		WorldScaleDriver driver = worldScaleDrivers.get(key.worldKey());
		if (driver == null) {
			return List.of();
		}
		WorldScaleDriver.Snapshot snapshot = driver.snapshot();
		if (snapshot.tornadoVortices().isEmpty()) {
			return List.of();
		}
		BlockPos origin = key.origin();
		double minX = origin.getX();
		double minZ = origin.getZ();
		double maxX = minX + GRID_SIZE;
		double maxZ = minZ + GRID_SIZE;
		List<TornadoRegionDescriptor> descriptors = new ArrayList<>();
		for (WorldScaleDriver.TornadoVortexSnapshot vortex : snapshot.tornadoVortices()) {
			if (!intersectsRegionHorizontally(vortex, minX, minZ, maxX, maxZ)) {
				continue;
			}
			descriptors.add(new TornadoRegionDescriptor(
					vortex.id(),
					vortex.parentConvectiveClusterId(),
					vortex.centerBlockX() - origin.getX(),
					vortex.baseY() - origin.getY(),
					vortex.centerBlockZ() - origin.getZ(),
					vortex.translationXBlocksPerSecond(),
					vortex.translationZBlocksPerSecond(),
					vortex.coreRadiusBlocks(),
					vortex.influenceRadiusBlocks(),
					vortex.tangentialWindScaleMps(),
					vortex.radialInflowScaleMps(),
					vortex.updraftScale(),
					vortex.condensationBias(),
					vortex.intensity(),
					vortex.rotationSign(),
					vortex.stateOrdinal(),
					tornadoLifecycleEnvelope(vortex)
			));
		}
		return descriptors.isEmpty() ? List.of() : List.copyOf(descriptors);
	}

	private boolean intersectsRegionHorizontally(
			WorldScaleDriver.TornadoVortexSnapshot vortex,
			double minX,
			double minZ,
			double maxX,
			double maxZ
	) {
		double closestX = Mth.clamp(vortex.centerBlockX(), minX, maxX);
		double closestZ = Mth.clamp(vortex.centerBlockZ(), minZ, maxZ);
		double dx = vortex.centerBlockX() - closestX;
		double dz = vortex.centerBlockZ() - closestZ;
		double radius = Math.max(vortex.influenceRadiusBlocks(), vortex.coreRadiusBlocks());
		return dx * dx + dz * dz <= radius * radius;
	}

	private float tornadoLifecycleEnvelope(WorldScaleDriver.TornadoVortexSnapshot vortex) {
		float lifetime = Math.max(1.0f, vortex.lifetimeSeconds());
		float progress = Mth.clamp(vortex.ageSeconds() / lifetime, 0.0f, 1.0f);
		return switch (vortex.stateOrdinal()) {
			case 0 -> Mth.clamp(progress / 0.25f, 0.0f, 1.0f);
			case 1 -> 1.0f;
			case 2 -> Mth.clamp((1.0f - progress) / 0.25f, 0.0f, 1.0f);
			default -> 0.0f;
		};
	}

	public static void fillVerticalBoundaryFace(
			ResourceKey<Level> worldKey,
			int face,
			double fixedAxis,
			double horizontalMin,
			double horizontalMax,
			double minY,
			double maxY,
			int resolution,
			float[] windX,
			float[] windY,
			float[] windZ,
			float[] airTemperature,
			NestedBoundaryCoupler.BoundarySample fallback
	) {
		for (int u = 0; u < resolution; u++) {
			float[] vxColumn = new float[resolution];
			float[] sampledVyColumn = new float[resolution];
			float[] vzColumn = new float[resolution];
			float[] tempColumn = new float[resolution];
			float[] divColumn = new float[resolution];
			boolean[] sampledVyAvailable = new boolean[resolution];
			boolean needsDerivedVy = false;
			float maxHorizontalSpeed = 0.0f;
			for (int v = 0; v < resolution; v++) {
				double horizontal = lerp(horizontalMin, horizontalMax, (u + 0.5) / resolution);
				double y = lerp(minY, maxY, (v + 0.5) / resolution);
				BlockPos samplePos = switch (face) {
					case 4, 5 -> BlockPos.containing(fixedAxis, y, horizontal);
					case 2, 3 -> BlockPos.containing(horizontal, y, fixedAxis);
					default -> BlockPos.ZERO;
				};
				NestedMetState state = sampleNestedMetState(worldKey, samplePos, fallback);
				vxColumn[v] = state.windX();
				sampledVyColumn[v] = state.windY();
				sampledVyAvailable[v] = state.verticalWindAvailable();
				vzColumn[v] = state.windZ();
				tempColumn[v] = state.airTemperatureKelvin();
				if (state.verticalWindAvailable()) {
					divColumn[v] = 0.0f;
				} else {
					divColumn[v] = sampleHorizontalDivergence(worldKey, samplePos, fallback);
					needsDerivedVy = true;
				}
				maxHorizontalSpeed = Math.max(maxHorizontalSpeed, (float) Math.sqrt(state.windX() * state.windX() + state.windZ() * state.windZ()));
			}
			float[] derivedVyColumn = needsDerivedVy
					? integrateVerticalVelocity(divColumn, maxHorizontalSpeed, (float) ((maxY - minY) / Math.max(1, resolution - 1)))
					: null;
			for (int v = 0; v < resolution; v++) {
				int index = boundaryFaceIndex(face, u, v, resolution);
				float vy = sampledVyAvailable[v] ? sampledVyColumn[v] : derivedVyColumn[v];
				windX[index] = vxColumn[v] / NATIVE_VELOCITY_SCALE;
				windY[index] = vy / NATIVE_VELOCITY_SCALE;
				windZ[index] = vzColumn[v] / NATIVE_VELOCITY_SCALE;
				airTemperature[index] = tempColumn[v];
			}
		}
	}

	public static void fillHorizontalBoundaryFace(
			ResourceKey<Level> worldKey,
			int face,
			double minX,
			double maxX,
			double minZ,
			double maxZ,
			double fixedY,
			int resolution,
			float[] windX,
			float[] windY,
			float[] windZ,
			float[] airTemperature,
			double columnMinY,
			double columnMaxY,
			NestedBoundaryCoupler.BoundarySample fallback
	) {
		for (int u = 0; u < resolution; u++) {
			for (int v = 0; v < resolution; v++) {
				double x = lerp(minX, maxX, (u + 0.5) / resolution);
				double z = lerp(minZ, maxZ, (v + 0.5) / resolution);
				BlockPos samplePos = BlockPos.containing(x, fixedY, z);
				NestedMetState state = sampleNestedMetState(worldKey, samplePos, fallback);
				float vy = state.verticalWindAvailable()
						? state.windY()
						: sampleColumnVerticalVelocity(worldKey, x, z, columnMinY, columnMaxY, face == Direction.DOWN.ordinal() ? 0 : resolution - 1, resolution, fallback);
				int index = boundaryFaceIndex(face, u, v, resolution);
				windX[index] = state.windX() / NATIVE_VELOCITY_SCALE;
				windY[index] = vy / NATIVE_VELOCITY_SCALE;
				windZ[index] = state.windZ() / NATIVE_VELOCITY_SCALE;
				airTemperature[index] = state.airTemperatureKelvin();
			}
		}
	}

	private static float sampleColumnVerticalVelocity(
			ResourceKey<Level> worldKey,
			double x,
			double z,
			double minY,
			double maxY,
			int targetIndex,
			int resolution,
			NestedBoundaryCoupler.BoundarySample fallback
	) {
		float[] divColumn = new float[resolution];
		float maxHorizontalSpeed = 0.0f;
		for (int i = 0; i < resolution; i++) {
			double y = lerp(minY, maxY, (i + 0.5) / resolution);
			BlockPos samplePos = BlockPos.containing(x, y, z);
			NestedMetState state = sampleNestedMetState(worldKey, samplePos, fallback);
			divColumn[i] = sampleHorizontalDivergence(worldKey, samplePos, fallback);
			maxHorizontalSpeed = Math.max(maxHorizontalSpeed, (float) Math.sqrt(state.windX() * state.windX() + state.windZ() * state.windZ()));
		}
		float[] vyColumn = integrateVerticalVelocity(divColumn, maxHorizontalSpeed, (float) ((maxY - minY) / Math.max(1, resolution - 1)));
		return vyColumn[Mth.clamp(targetIndex, 0, resolution - 1)];
	}

	private static float[] integrateVerticalVelocity(float[] divergenceColumn, float maxHorizontalSpeed, float dyMeters) {
		float[] vy = new float[divergenceColumn.length];
		for (int i = 1; i < divergenceColumn.length; i++) {
			vy[i] = vy[i - 1] - 0.5f * (divergenceColumn[i - 1] + divergenceColumn[i]) * dyMeters;
		}
		float mean = 0.0f;
		for (float value : vy) {
			mean += value;
		}
		mean /= Math.max(1, vy.length);
		float clamp = Math.max(0.15f, maxHorizontalSpeed * NESTED_BOUNDARY_MAX_VY_RATIO);
		for (int i = 0; i < vy.length; i++) {
			vy[i] = Mth.clamp(vy[i] - mean, -clamp, clamp);
		}
		return vy;
	}

	private static float sampleHorizontalDivergence(
			ResourceKey<Level> worldKey,
			BlockPos samplePos,
			NestedBoundaryCoupler.BoundarySample fallback
	) {
		BlockPos xMinus = samplePos.offset(-MESOSCALE_MET_CELL_SIZE_BLOCKS, 0, 0);
		BlockPos xPlus = samplePos.offset(MESOSCALE_MET_CELL_SIZE_BLOCKS, 0, 0);
		BlockPos zMinus = samplePos.offset(0, 0, -MESOSCALE_MET_CELL_SIZE_BLOCKS);
		BlockPos zPlus = samplePos.offset(0, 0, MESOSCALE_MET_CELL_SIZE_BLOCKS);
		NestedMetState sxMinus = sampleNestedMetState(worldKey, xMinus, fallback);
		NestedMetState sxPlus = sampleNestedMetState(worldKey, xPlus, fallback);
		NestedMetState szMinus = sampleNestedMetState(worldKey, zMinus, fallback);
		NestedMetState szPlus = sampleNestedMetState(worldKey, zPlus, fallback);
		float dxMeters = MESOSCALE_MET_CELL_SIZE_BLOCKS;
		float dzMeters = MESOSCALE_MET_CELL_SIZE_BLOCKS;
		float dudx = (sxPlus.windX() - sxMinus.windX()) / (2.0f * dxMeters);
		float dwdz = (szPlus.windZ() - szMinus.windZ()) / (2.0f * dzMeters);
		return dudx + dwdz;
	}

	private static NestedMetState sampleNestedMetState(
			ResourceKey<Level> worldKey,
			BlockPos pos,
			NestedBoundaryCoupler.BoundarySample fallback
	) {
		MesoscaleGrid.Sample mesoscale = sampleMesoscaleMet(worldKey, pos);
		if (mesoscale != null) {
			return new NestedMetState(
					mesoscale.windX(),
					mesoscale.windY(),
					mesoscale.windZ(),
					mesoscale.ambientAirTemperatureKelvin(),
					true
			);
		}
		BackgroundMetGrid.Sample background = sampleBackgroundMet(worldKey, pos);
		if (background != null) {
			return new NestedMetState(
					background.backgroundWindX(),
					0.0f,
					background.backgroundWindZ(),
					background.ambientAirTemperatureKelvin(),
					false
			);
		}
		return new NestedMetState(
				fallback == null ? 0.0f : fallback.windX(),
				fallback == null ? 0.0f : fallback.windY(),
				fallback == null ? 0.0f : fallback.windZ(),
				fallback == null ? THERMAL_BASE_AMBIENT_AIR_TEMPERATURE_K : fallback.ambientAirTemperatureKelvin(),
				fallback != null && fallback.verticalWindAvailable()
		);
	}

	private static int boundaryFaceIndex(int face, int u, int v, int resolution) {
		return (face * resolution + u) * resolution + v;
	}

	private static double lerp(double min, double max, double t) {
		return min + (max - min) * t;
	}

	private boolean isSolidObstacle(ServerLevel world, BlockPos pos) {
		return isSolidObstacle(world, pos, world.getBlockState(pos));
	}

	private static boolean isSolidObstacle(ServerLevel world, BlockPos pos, BlockState state) {
		if (state.isAir() || state.is(ModBlocks.DUCT_BLOCK)) {
			return false;
		}
		return !state.getCollisionShape(world, pos).isEmpty();
	}

	private static boolean isSolidObstacleAtPoint(
			ServerLevel world,
			BlockPos pos,
			BlockState state,
			double worldX,
			double worldY,
			double worldZ
	) {
		if (!isSolidObstacle(world, pos, state)) {
			return false;
		}
		VoxelShape shape = state.getCollisionShape(world, pos);
		if (shape.isEmpty()) {
			return false;
		}
		double localX = Mth.clamp(worldX - pos.getX(), 0.0, 0.999999);
		double localY = Mth.clamp(worldY - pos.getY(), 0.0, 0.999999);
		double localZ = Mth.clamp(worldZ - pos.getZ(), 0.0, 0.999999);
		for (AABB box : shape.toAabbs()) {
			if (localX >= box.minX && localX < box.maxX
					&& localY >= box.minY && localY < box.maxY
					&& localZ >= box.minZ && localZ < box.maxZ) {
				return true;
			}
		}
		return false;
	}

	private static float sampleEmitterThermalPowerWatts(BlockState state) {
		float powerWatts = 0.0f;
		if (state.is(Blocks.LAVA) || state.is(Blocks.LAVA_CAULDRON)) {
			powerWatts += THERMAL_EMITTER_POWER_LAVA_W;
		}
		if (state.is(Blocks.MAGMA_BLOCK)) {
			powerWatts += THERMAL_EMITTER_POWER_MAGMA_W;
		}
		if (state.is(Blocks.CAMPFIRE)) {
			powerWatts += state.getOptionalValue(BlockStateProperties.LIT).orElse(false) ? THERMAL_EMITTER_POWER_CAMPFIRE_W : 0.0f;
		}
		if (state.is(Blocks.SOUL_CAMPFIRE)) {
			powerWatts += state.getOptionalValue(BlockStateProperties.LIT).orElse(false) ? THERMAL_EMITTER_POWER_SOUL_CAMPFIRE_W : 0.0f;
		}
		if (state.is(Blocks.FIRE)) {
			powerWatts += THERMAL_EMITTER_POWER_FIRE_W;
		}
		if (state.is(Blocks.SOUL_FIRE)) {
			powerWatts += THERMAL_EMITTER_POWER_SOUL_FIRE_W;
		}
		if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) {
			powerWatts += THERMAL_EMITTER_POWER_TORCH_W;
		}
		if (state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)) {
			powerWatts += THERMAL_EMITTER_POWER_SOUL_TORCH_W;
		}
		if (state.is(Blocks.LANTERN)) {
			powerWatts += THERMAL_EMITTER_POWER_LANTERN_W;
		}
		if (state.is(Blocks.SOUL_LANTERN)) {
			powerWatts += THERMAL_EMITTER_POWER_SOUL_LANTERN_W;
		}
		return Math.max(powerWatts, 0.0f);
	}

	public static ThermalEnvironment sampleThermalEnvironment(
			WorldEnvironmentSnapshot snapshot,
			ResourceKey<Level> worldKey,
			BlockPos samplePos,
			float surfaceDeltaSeconds
	) {
		long timeOfDay = snapshot == null ? 6000L : snapshot.timeOfDay();
		float rainGradient = snapshot == null ? 0.0f : snapshot.rainGradient();
		float thunderGradient = snapshot == null ? 0.0f : snapshot.thunderGradient();
		int seaLevel = snapshot == null ? 63 : snapshot.seaLevel();
		float dayPhase = (float) Math.floorMod(timeOfDay, 24000L) / 24000.0f;
		float solarAltitude = Math.max(0.0f, (float) Math.sin(dayPhase * (float) (Math.PI * 2.0)));
		float rain = rainGradient;
		float thunder = thunderGradient;
		float clearSky = Mth.clamp(1.0f - 0.65f * rain - 0.25f * thunder, 0.15f, 1.0f);
		float directRadiation = THERMAL_SOLAR_DIRECT_FLUX_W_M2 * solarAltitude * clearSky;
		float diffuseRadiation = THERMAL_SOLAR_DIFFUSE_FLUX_W_M2
				* (0.30f + 0.70f * solarAltitude)
				* (0.55f + 0.45f * clearSky);
		float precipitationStrength = Math.max(rain, thunder * 0.60f);
		MesoscaleGrid.Sample mesoscaleSample = sampleMesoscaleMet(worldKey, samplePos);
		BackgroundMetGrid.Sample backgroundSample = mesoscaleSample == null ? sampleBackgroundMet(worldKey, samplePos) : null;
		float biomeTemperature = mesoscaleSample != null
				? mesoscaleSample.biomeTemperature()
				: backgroundSample != null
				  ? backgroundSample.biomeTemperature()
				  : 0.8f;
		float ambientAirTemperatureKelvin;
		float deepGroundTemperatureKelvin;
		if (mesoscaleSample != null) {
			ambientAirTemperatureKelvin = mesoscaleSample.ambientAirTemperatureKelvin();
			deepGroundTemperatureKelvin = mesoscaleSample.deepGroundTemperatureKelvin();
		} else if (backgroundSample != null) {
			ambientAirTemperatureKelvin = backgroundSample.ambientAirTemperatureKelvin();
			deepGroundTemperatureKelvin = backgroundSample.deepGroundTemperatureKelvin();
		} else {
			float altitudeOffsetK = (samplePos.getY() - seaLevel) * THERMAL_ALTITUDE_LAPSE_RATE_K_PER_BLOCK;
			ambientAirTemperatureKelvin = THERMAL_BASE_AMBIENT_AIR_TEMPERATURE_K
					+ (biomeTemperature - 0.8f) * THERMAL_BIOME_TEMPERATURE_SCALE_K
					- altitudeOffsetK;
			deepGroundTemperatureKelvin = ambientAirTemperatureKelvin + THERMAL_DEEP_GROUND_OFFSET_K;
		}
		float skyTemperatureDropK = Mth.lerp(solarAltitude, THERMAL_SKY_TEMP_DROP_NIGHT_K, THERMAL_SKY_TEMP_DROP_DAY_K);
		float skyTemperatureKelvin = ambientAirTemperatureKelvin - skyTemperatureDropK * clearSky;
		float precipitationTemperatureKelvin = ambientAirTemperatureKelvin - THERMAL_PRECIP_TEMP_DROP_K;
		float azimuth = dayPhase * (float) (Math.PI * 2.0) - (float) (Math.PI * 0.5);
		float horizontal = (float) Math.sqrt(Math.max(0.0, 1.0 - solarAltitude * solarAltitude));
		return new ThermalEnvironment(
				directRadiation,
				diffuseRadiation,
				ambientAirTemperatureKelvin,
				deepGroundTemperatureKelvin,
				skyTemperatureKelvin,
				precipitationTemperatureKelvin,
				precipitationStrength,
				(float) Math.cos(azimuth) * horizontal,
				solarAltitude,
				(float) Math.sin(azimuth) * horizontal,
				surfaceDeltaSeconds
		);
	}

	private static float sampleSkyExposure(ServerLevel world, BlockPos pos) {
		return world.getBrightness(LightLayer.SKY, pos) / 15.0f;
	}

	private static float sampleDirectSunExposure(ServerLevel world, BlockPos pos) {
		return world.canSeeSkyFromBelowWater(pos) ? 1.0f : 0.0f;
	}

	private static boolean isStoneLikeTerrain(BlockState state) {
		return state.is(Blocks.STONE)
				|| state.is(Blocks.COBBLESTONE)
				|| state.is(Blocks.DEEPSLATE)
				|| state.is(Blocks.COBBLED_DEEPSLATE)
				|| state.is(Blocks.GRANITE)
				|| state.is(Blocks.DIORITE)
				|| state.is(Blocks.ANDESITE)
				|| state.is(Blocks.TUFF)
				|| state.is(Blocks.CALCITE)
				|| state.is(Blocks.BLACKSTONE)
				|| state.is(Blocks.BASALT);
	}

	private static boolean isSoilSurface(BlockState state) {
		return state.is(Blocks.DIRT)
				|| state.is(Blocks.COARSE_DIRT)
				|| state.is(Blocks.ROOTED_DIRT)
				|| state.is(Blocks.PODZOL)
				|| state.is(Blocks.SAND)
				|| state.is(Blocks.RED_SAND)
				|| state.is(Blocks.GRAVEL)
				|| state.is(Blocks.CLAY)
				|| state.is(Blocks.MUD);
	}

	private static boolean isVegetatedSurface(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK)
				|| state.is(Blocks.MYCELIUM)
				|| state.is(Blocks.MOSS_BLOCK);
	}

	private static boolean isSnowOrIceSurface(BlockState state) {
		return state.is(Blocks.SNOW_BLOCK)
				|| state.is(Blocks.ICE)
				|| state.is(Blocks.PACKED_ICE)
				|| state.is(Blocks.BLUE_ICE);
	}

	private static boolean isWaterSurface(BlockState state) {
		return state.getFluidState().is(FluidTags.WATER);
	}

	private static boolean isMoltenSurface(BlockState state) {
		return state.is(Blocks.LAVA)
				|| state.is(Blocks.LAVA_CAULDRON);
	}

	private static ThermalMaterial thermalMaterial(BlockState state) {
		if (isMoltenSurface(state)) {
			return ThermalMaterial.MOLTEN;
		}
		if (isWaterSurface(state)) {
			return ThermalMaterial.WATER;
		}
		if (isSnowOrIceSurface(state)) {
			return ThermalMaterial.SNOW_ICE;
		}
		if (isVegetatedSurface(state)) {
			return ThermalMaterial.VEGETATION;
		}
		if (isSoilSurface(state)) {
			return ThermalMaterial.SOIL;
		}
		if (isStoneLikeTerrain(state)) {
			return ThermalMaterial.ROCK;
		}
		return null;
	}

	private static int faceDataIndex(int cell, Direction direction) {
		return cell * FACE_COUNT + direction.ordinal();
	}

	private static byte setFaceBit(byte mask, Direction direction) {
		return (byte) (mask | (1 << direction.ordinal()));
	}

	private static byte quantizeUnitFloat(float value) {
		return (byte) Mth.clamp(Math.round(Mth.clamp(value, 0.0f, 1.0f) * 255.0f), 0, 255);
	}

	private void sectionStaticThermalFields(
			ServerLevel world,
			BlockPos pos,
			BlockState state,
			int x,
			int y,
			int z,
			ThermalMaterial material,
			float[] emitterPowerWatts,
			byte[] openFaceMaskField,
			byte[] faceSkyExposure,
			byte[] faceDirectExposure
	) {
		if (!inSectionBounds(x, y, z)) {
			return;
		}
		int cell = localSectionCellIndex(x, y, z);
		float emitterPower = sampleEmitterThermalPowerWatts(state);
		byte openFaceMask = 0;
		if (emitterPowerWatts != null) {
			emitterPowerWatts[cell] = emitterPower;
		}
		if (openFaceMaskField != null) {
			openFaceMaskField[cell] = 0;
		}
		if (faceSkyExposure != null) {
			Arrays.fill(faceSkyExposure, cell * FACE_COUNT, cell * FACE_COUNT + FACE_COUNT, (byte) 0);
		}
		if (faceDirectExposure != null) {
			Arrays.fill(faceDirectExposure, cell * FACE_COUNT, cell * FACE_COUNT + FACE_COUNT, (byte) 0);
		}
		if (material == null && emitterPower <= 0.0f) {
			return;
		}
		BlockPos.MutableBlockPos neighborCursor = new BlockPos.MutableBlockPos();
		for (Direction direction : CARDINAL_DIRECTIONS) {
			neighborCursor.set(
					pos.getX() + direction.getStepX(),
					pos.getY() + direction.getStepY(),
					pos.getZ() + direction.getStepZ()
			);
			BlockState neighborState = world.getBlockState(neighborCursor);
			boolean openFace;
			if (material != null) {
				openFace = material.atmosphericExchangeRequiresAirNeighbor()
						? neighborState.isAir()
						: !isSolidObstacle(world, neighborCursor, neighborState);
			} else {
				openFace = neighborState.isAir();
			}
			if (!openFace) {
				continue;
			}
			openFaceMask = setFaceBit(openFaceMask, direction);
			if (faceSkyExposure != null && faceDirectExposure != null) {
				int faceIndex = faceDataIndex(cell, direction);
				faceSkyExposure[faceIndex] = quantizeUnitFloat(sampleSkyExposure(world, neighborCursor));
				faceDirectExposure[faceIndex] = quantizeUnitFloat(sampleDirectSunExposure(world, neighborCursor));
			}
		}
		if (openFaceMaskField != null) {
			openFaceMaskField[cell] = openFaceMask;
		}
	}

	private void refreshRegionThermalInSimulation(WindowKey key, RegionRecord region) {
		if (simulationServiceId == 0L || region.sections == null) {
			return;
		}
		float deltaSeconds = Math.max(1, tickCounter - region.lastThermalRefreshTick) * SOLVER_STEP_SECONDS;
		ThermalEnvironment environment = sampleThermalEnvironment(
				worldEnvironmentSnapshots.get(key.worldKey()),
				key.worldKey(),
				key.origin().offset(GRID_SIZE / 2, GRID_SIZE / 2, GRID_SIZE / 2),
				deltaSeconds
		);
		if (simulationBridge.refreshRegionThermal(
				simulationServiceId,
				simulationRegionKey(key),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE,
				environment.directSolarFluxWm2(),
				environment.diffuseSolarFluxWm2(),
				environment.ambientAirTemperatureKelvin(),
				environment.deepGroundTemperatureKelvin(),
				environment.skyTemperatureKelvin(),
				environment.precipitationTemperatureKelvin(),
				environment.precipitationStrength(),
				environment.sunX(),
				environment.sunY(),
				environment.sunZ(),
				environment.surfaceDeltaSeconds()
		)) {
			region.lastThermalRefreshTick = tickCounter;
		}
	}

	private BrickRuntimeDynamicState copyBrickRuntimeDynamicState(
			ResourceKey<Level> worldKey,
			int brickX,
			int brickY,
			int brickZ
	) {
		if (!shouldRunServerAuthoritativeL2()) {
			return null;
		}
		if (simulationServiceId == 0L || !simulationBridge.isLoaded()) {
			return null;
		}
		int brickCells = BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE * BRICK_RUNTIME_SIZE;
		float[] flowState = new float[brickCells * RESPONSE_CHANNELS];
		float[] airTemperatureState = new float[brickCells];
		float[] surfaceTemperatureState = new float[brickCells];
		boolean copied = simulationBridge.copyBrickWorldDynamicBrick(
				simulationServiceId,
				simulationWorldKey(worldKey),
				BRICK_RUNTIME_SIZE,
				brickX,
				brickY,
				brickZ,
				flowState,
				airTemperatureState,
				surfaceTemperatureState
		);
		if (!copied) {
			return null;
		}
		return new BrickRuntimeDynamicState(flowState, airTemperatureState, surfaceTemperatureState);
	}

	private SampledPoint sampleBrickRuntimePointLocked(ResourceKey<Level> worldKey, BlockPos probePos) {
		int brickX = Math.floorDiv(probePos.getX(), BRICK_RUNTIME_SIZE);
		int brickY = Math.floorDiv(probePos.getY(), BRICK_RUNTIME_SIZE);
		int brickZ = Math.floorDiv(probePos.getZ(), BRICK_RUNTIME_SIZE);
		BrickRuntimeDynamicState brickState = copyBrickRuntimeDynamicState(worldKey, brickX, brickY, brickZ);
		if (brickState == null) {
			return null;
		}
		int localX = probePos.getX() - brickX * BRICK_RUNTIME_SIZE;
		int localY = probePos.getY() - brickY * BRICK_RUNTIME_SIZE;
		int localZ = probePos.getZ() - brickZ * BRICK_RUNTIME_SIZE;
		if (localX < 0 || localX >= BRICK_RUNTIME_SIZE
				|| localY < 0 || localY >= BRICK_RUNTIME_SIZE
				|| localZ < 0 || localZ >= BRICK_RUNTIME_SIZE) {
			return null;
		}
		int cell = patchCellIndex(localX, localY, localZ, BRICK_RUNTIME_SIZE);
		int base = cell * RESPONSE_CHANNELS;
		ThermalEnvironment environment = sampleThermalEnvironment(
				worldEnvironmentSnapshots.get(worldKey),
				worldKey,
				probePos,
				SOLVER_STEP_SECONDS
		);
		return new SampledPoint(
				brickState.flowState()[base] * NATIVE_VELOCITY_SCALE,
				brickState.flowState()[base + 1] * NATIVE_VELOCITY_SCALE,
				brickState.flowState()[base + 2] * NATIVE_VELOCITY_SCALE,
				brickState.flowState()[base + 3],
				environment.ambientAirTemperatureKelvin()
						+ brickState.airTemperatureState()[cell] * RUNTIME_TEMPERATURE_SCALE_KELVIN,
				brickState.surfaceTemperatureState()[cell]
		);
	}

	private AeroWindSample sampleWindLocked(ResourceKey<Level> worldKey, BlockPos probePos, SamplePolicy policy) {
		SamplePolicy effectivePolicy = policy == null ? SamplePolicy.GAMEPLAY_SERVER_ONLY : policy;
		SampledPoint brickSample = effectivePolicy.allowServerAggregatedL2()
				? sampleBrickRuntimePointLocked(worldKey, probePos)
				: null;
		if (brickSample != null) {
			AeroWindSample coarseBackground = sampleCoarseWindLocked(worldKey, probePos);
			AeroWindSample resolved = AeroWindSample.serverAuthoritative(
					brickSample.velocityX(),
					brickSample.velocityY(),
					brickSample.velocityZ(),
					brickSample.pressure(),
					AeroWindSample.Level.L2,
					AeroWindSample.UNKNOWN_EPOCH,
					AeroWindSample.UNKNOWN_EPOCH,
					tickCounter
			);
			if (coarseBackground.hasAtmosphericDiagnostics()) {
				return resolved.withAtmosphere(
						brickSample.airTemperatureKelvin(),
						coarseBackground.humidity(),
						coarseBackground.turbulenceIntensity(),
						coarseBackground.gustX(),
						coarseBackground.gustY(),
						coarseBackground.gustZ(),
						coarseBackground.windShearXPerBlock(),
						coarseBackground.windShearZPerBlock(),
						coarseBackground.ablStability(),
						coarseBackground.ablMixingStrength()
				);
			}
			return resolved.withAtmosphere(
					brickSample.airTemperatureKelvin(),
					AeroWindSample.UNKNOWN_SCALAR,
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
		if (!effectivePolicy.allowServerCoarse()) {
			return AeroWindSample.ZERO;
		}
		return sampleCoarseWindLocked(worldKey, probePos);
	}

	private AeroWindSample sampleCoarseWindLocked(ResourceKey<Level> worldKey, BlockPos probePos) {
		MesoscaleGrid.Sample mesoscaleSample = sampleMesoscaleMet(worldKey, probePos);
		if (mesoscaleSample != null) {
			float horizontalSpeed = windSpeed(mesoscaleSample.windX(), mesoscaleSample.windZ());
			BackgroundMetGrid.Sample backgroundSample = sampleBackgroundMet(worldKey, probePos);
			AeroWindSample sample = AeroWindSample.serverAuthoritative(
					mesoscaleSample.windX(),
					sampleCoarseVerticalVelocityLocked(worldKey, probePos, horizontalSpeed),
					mesoscaleSample.windZ(),
					backgroundSample == null ? 0.0f : backgroundSample.pressureAnomalyPa(),
					AeroWindSample.Level.L1,
					tickCounter,
					AeroWindSample.UNKNOWN_EPOCH,
					AeroWindSample.UNKNOWN_EPOCH
			);
			float turbulence = l1TurbulenceIntensity(mesoscaleSample, horizontalSpeed);
			float gustPotential = l1GustPotential(mesoscaleSample, horizontalSpeed, turbulence);
			return sample.withAtmosphere(
					mesoscaleSample.ambientAirTemperatureKelvin(),
					mesoscaleSample.humidity(),
					turbulence,
					l1GustComponent(probePos, tickCounter, 0, mesoscaleSample.windX(), mesoscaleSample.windZ(), gustPotential, turbulence),
					l1GustComponent(probePos, tickCounter, 1, mesoscaleSample.windX(), mesoscaleSample.windZ(), gustPotential, turbulence),
					l1GustComponent(probePos, tickCounter, 2, mesoscaleSample.windX(), mesoscaleSample.windZ(), gustPotential, turbulence),
					mesoscaleSample.windShearXPerBlock(),
					mesoscaleSample.windShearZPerBlock(),
					mesoscaleSample.ablStability(),
					mesoscaleSample.ablMixingStrength()
			);
		}
		BackgroundMetGrid.Sample backgroundSample = sampleBackgroundMet(worldKey, probePos);
		if (backgroundSample != null) {
			float horizontalSpeed = (float) Math.sqrt(
					backgroundSample.backgroundWindX() * backgroundSample.backgroundWindX()
							+ backgroundSample.backgroundWindZ() * backgroundSample.backgroundWindZ()
			);
			AeroWindSample sample = AeroWindSample.serverAuthoritative(
					backgroundSample.backgroundWindX(),
					sampleCoarseVerticalVelocityLocked(worldKey, probePos, horizontalSpeed),
					backgroundSample.backgroundWindZ(),
					backgroundSample.pressureAnomalyPa(),
					AeroWindSample.Level.L0,
					AeroWindSample.UNKNOWN_EPOCH,
					AeroWindSample.UNKNOWN_EPOCH,
					AeroWindSample.UNKNOWN_EPOCH
			);
			float turbulence = l0TurbulenceIntensity(backgroundSample, horizontalSpeed);
			return sample.withAtmosphere(
					backgroundSample.ambientAirTemperatureKelvin(),
					backgroundSample.humidity(),
					turbulence,
					gustComponent(probePos, tickCounter, 0, turbulence),
					gustComponent(probePos, tickCounter, 1, turbulence) * 0.25f,
					gustComponent(probePos, tickCounter, 2, turbulence),
					0.0f,
					0.0f,
					0.0f,
					Mth.clamp(backgroundSample.convectiveEnvelope(), 0.0f, 1.0f)
			);
		}
		return AeroWindSample.ZERO;
	}

	private float windSpeed(float windX, float windZ) {
		if (!Float.isFinite(windX) || !Float.isFinite(windZ)) {
			return 0.0f;
		}
		return (float) Math.sqrt(windX * windX + windZ * windZ);
	}

	private float l1TurbulenceIntensity(MesoscaleGrid.Sample sample, float horizontalSpeed) {
		if (sample == null) {
			return 0.0f;
		}
		float shear = (float) Math.sqrt(
				sample.windShearXPerBlock() * sample.windShearXPerBlock()
						+ sample.windShearZPerBlock() * sample.windShearZPerBlock()
		);
		float roughness = Mth.clamp(sample.roughnessLengthMeters() / 2.0f, 0.0f, 1.0f);
		float mixing = Mth.clamp(sample.ablMixingStrength(), 0.0f, 1.0f);
		float instability = Math.max(0.0f, sample.ablStability());
		float nearSurface = 1.0f - Mth.clamp(sample.ablHeightAglBlocks() / Math.max(1.0f, sample.ablHeightBlocks()), 0.0f, 1.0f);
		float speedTerm = horizontalSpeed * (0.025f + 0.050f * roughness + 0.045f * mixing);
		float shearTerm = Mth.clamp(shear * 64.0f, 0.0f, 1.5f);
		float buoyancyTerm = instability * mixing * 0.65f;
		return Mth.clamp(
				speedTerm + shearTerm + buoyancyTerm + nearSurface * roughness * 0.25f,
				0.0f,
				3.0f
		);
	}

	private float l1GustPotential(MesoscaleGrid.Sample sample, float horizontalSpeed, float turbulenceIntensity) {
		if (sample == null) {
			return 0.0f;
		}
		float mixing = Mth.clamp(sample.ablMixingStrength(), 0.0f, 1.0f);
		float profile = Mth.clamp(sample.ablProfileBlend(), 0.0f, 1.0f);
		float shear = (float) Math.sqrt(
				sample.windShearXPerBlock() * sample.windShearXPerBlock()
						+ sample.windShearZPerBlock() * sample.windShearZPerBlock()
		);
		float shearBoost = Mth.clamp(shear * 48.0f, 0.0f, 1.0f);
		float exposed = Mth.clamp(profile + mixing * 0.55f, 0.0f, 1.0f);
		return Mth.clamp(
				1.10f
						+ horizontalSpeed * 0.34f
						+ turbulenceIntensity * 0.42f
						+ exposed * 0.70f
						+ shearBoost * 0.45f,
				1.25f,
				3.80f
		);
	}

	private float l0TurbulenceIntensity(BackgroundMetGrid.Sample sample, float horizontalSpeed) {
		if (sample == null) {
			return 0.0f;
		}
		float convective = Mth.clamp(sample.convectiveEnvelope(), 0.0f, 1.0f);
		float roughness = Mth.clamp(sample.roughnessLengthMeters() / 2.0f, 0.0f, 1.0f);
		float thermal = Mth.clamp(
				Math.max(0.0f, sample.surfaceTemperatureKelvin() - sample.ambientAirTemperatureKelvin()) / 8.0f,
				0.0f,
				1.0f
		);
		return Mth.clamp(horizontalSpeed * (0.018f + 0.040f * roughness) + convective * 0.75f + thermal * 0.35f, 0.0f, 2.0f);
	}

	private float gustComponent(BlockPos pos, long tick, int axis, float turbulenceIntensity) {
		if (!(turbulenceIntensity > 0.0f) || !Float.isFinite(turbulenceIntensity)) {
			return 0.0f;
		}
		double time = tick * 0.035 + axis * 11.37;
		double phaseA = pos.getX() * 0.071 + pos.getY() * 0.031 + pos.getZ() * 0.053 + time;
		double phaseB = pos.getX() * 0.017 - pos.getY() * 0.047 + pos.getZ() * 0.029 + time * 0.43 + axis * 3.19;
		double gust = Math.sin(phaseA) * 0.62 + Math.sin(phaseB) * 0.38;
		return (float) (gust * turbulenceIntensity * 0.42);
	}

	private float l1GustComponent(
			BlockPos pos,
			long tick,
			int axis,
			float meanWindX,
			float meanWindZ,
			float gustPotential,
			float turbulenceIntensity
	) {
		if (!(gustPotential > 0.0f) || !Float.isFinite(gustPotential)) {
			return 0.0f;
		}
		float speed = windSpeed(meanWindX, meanWindZ);
		float dirX;
		float dirZ;
		if (speed > 0.20f) {
			dirX = meanWindX / speed;
			dirZ = meanWindZ / speed;
		} else {
			double fallbackAngle = pseudoAngle(pos);
			dirX = (float) Math.cos(fallbackAngle);
			dirZ = (float) Math.sin(fallbackAngle);
		}

		double timeSeconds = tick * SOLVER_STEP_SECONDS;
		double along = pos.getX() * dirX + pos.getZ() * dirZ;
		double cross = -pos.getX() * dirZ + pos.getZ() * dirX;
		double bandA = Math.sin(along * 0.018 - timeSeconds * 0.42 + cross * 0.003);
		double bandB = Math.sin(along * 0.041 - timeSeconds * 0.95 + cross * 0.011 + 2.13);
		double positiveBand = Math.max(0.0, bandA * 0.68 + bandB * 0.32);
		double pulse = 0.28 + 0.72 * Math.pow(positiveBand, 1.35);
		double alongGust = pulse * gustPotential;
		double lateralPhase = pos.getX() * 0.013 - pos.getZ() * 0.021 + timeSeconds * 0.67 + 1.71;
		double lateral = Math.sin(lateralPhase)
				* gustPotential
				* (0.10 + 0.14 * Mth.clamp(turbulenceIntensity / 3.0f, 0.0f, 1.0f));
		if (axis == 0) {
			return (float) (dirX * alongGust - dirZ * lateral);
		}
		if (axis == 2) {
			return (float) (dirZ * alongGust + dirX * lateral);
		}
		double verticalPhase = pos.getX() * 0.019 + pos.getY() * 0.037 - pos.getZ() * 0.016 + timeSeconds * 0.58;
		return (float) (Math.sin(verticalPhase) * Math.min(0.45f, gustPotential * 0.12f));
	}

	private static double pseudoAngle(BlockPos pos) {
		long value = pos.asLong() ^ 0x9E3779B97F4A7C15L;
		value ^= value >>> 33;
		value *= 0xff51afd7ed558ccdL;
		value ^= value >>> 33;
		value *= 0xc4ceb9fe1a85ec53L;
		value ^= value >>> 33;
		return ((value >>> 11) * 0x1.0p-53) * Math.PI * 2.0;
	}

	private SampledPoint sampleCoarsePointLocked(ResourceKey<Level> worldKey, BlockPos probePos) {
		AeroWindSample wind = sampleCoarseWindLocked(worldKey, probePos);
		if (!wind.hasFlow()) {
			return null;
		}
		ThermalEnvironment environment = sampleThermalEnvironment(
				worldEnvironmentSnapshots.get(worldKey),
				worldKey,
				probePos,
				SOLVER_STEP_SECONDS
		);
		return new SampledPoint(
				wind.velocityX(),
				wind.velocityY(),
				wind.velocityZ(),
				wind.pressure(),
				environment.ambientAirTemperatureKelvin(),
				environment.deepGroundTemperatureKelvin()
		);
	}

	private float sampleCoarseVerticalVelocityLocked(ResourceKey<Level> worldKey, BlockPos probePos, float horizontalSpeed) {
		int layerMinY = Math.floorDiv(probePos.getY(), MESOSCALE_MET_LAYER_HEIGHT_BLOCKS) * MESOSCALE_MET_LAYER_HEIGHT_BLOCKS;
		float divergence = sampleHorizontalDivergence(worldKey, probePos, null);
		float centeredY = (float) ((probePos.getY() + 0.5) - (layerMinY + MESOSCALE_MET_LAYER_HEIGHT_BLOCKS * 0.5));
		float clamp = Math.max(0.15f, horizontalSpeed * NESTED_BOUNDARY_MAX_VY_RATIO);
		return Mth.clamp(-divergence * centeredY, -clamp, clamp);
	}

	private short quantizeSignedToShort(float value, float range) {
		if (!(range > 0.0f) || !Float.isFinite(value)) {
			return 0;
		}
		float normalized = Mth.clamp(value / range, -1.0f, 1.0f);
		return (short) Math.round(normalized * 32767.0f);
	}

	private float finiteOrDefault(float value, float fallback) {
		return Float.isFinite(value) ? value : fallback;
	}

	private float maxFlowSpeedMetersPerSecond(float[] flowState) {
		if (flowState == null) {
			return 0.0f;
		}
		float maxSpeed = 0.0f;
		for (int base = 0; base + 2 < flowState.length; base += RESPONSE_CHANNELS) {
			float vx = flowState[base] * NATIVE_VELOCITY_SCALE;
			float vy = flowState[base + 1] * NATIVE_VELOCITY_SCALE;
			float vz = flowState[base + 2] * NATIVE_VELOCITY_SCALE;
			float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
			if (Float.isFinite(speed) && speed > maxSpeed) {
				maxSpeed = speed;
			}
		}
		return maxSpeed;
	}

	private float expectedCoarseSpeedMetersPerSecond(WindowKey key) {
		BlockPos coreOrigin = key.origin().offset(REGION_HALO_CELLS, REGION_HALO_CELLS, REGION_HALO_CELLS);
		BlockPos center = coreOrigin.offset(BRICK_RUNTIME_SIZE / 2, BRICK_RUNTIME_SIZE / 2, BRICK_RUNTIME_SIZE / 2);
		AeroWindSample coarse = sampleCoarseWindLocked(key.worldKey(), center);
		if (!coarse.hasFlow()) {
			return 0.0f;
		}
		return (float) AeroMinecraftVectors.velocity(coarse).length();
	}

	private boolean shouldSuppressZeroBrickAtlas(WindowKey key, BrickRuntimeAtlasSnapshot atlas) {
		return atlas != null
				&& atlas.maxSpeed() < ZERO_ATLAS_MAX_SPEED_EPS_MPS
				&& expectedCoarseSpeedMetersPerSecond(key) >= EXPECTED_COARSE_WIND_MIN_MPS;
	}

	private boolean shouldHoldPreviousAtlas(
			WindowKey key,
			BrickRuntimeAtlasSnapshot atlas,
			BrickRuntimeAtlasSnapshot previousAtlas
	) {
		if (atlas == null || atlas.maxSpeed() >= ZERO_ATLAS_MAX_SPEED_EPS_MPS) {
			zeroAtlasHoldUntilTick.remove(key);
			return false;
		}
		if (previousAtlas == null || previousAtlas.maxSpeed() < ZERO_ATLAS_MAX_SPEED_EPS_MPS) {
			return false;
		}
		Integer holdUntilTick = zeroAtlasHoldUntilTick.get(key);
		if (holdUntilTick == null) {
			zeroAtlasHoldUntilTick.put(key, tickCounter + ZERO_ATLAS_HOLD_TICKS);
			return true;
		}
		return tickCounter <= holdUntilTick;
	}

	private BrickRuntimeAtlasSnapshot sampleBrickRuntimeCoreAtlas(WindowKey key) {
		BlockPos coreOrigin = key.origin().offset(REGION_HALO_CELLS, REGION_HALO_CELLS, REGION_HALO_CELLS);

		BrickRuntimeDynamicState brickState = copyBrickRuntimeDynamicState(
				key.worldKey(),
				Math.floorDiv(coreOrigin.getX(), BRICK_RUNTIME_SIZE),
				Math.floorDiv(coreOrigin.getY(), BRICK_RUNTIME_SIZE),
				Math.floorDiv(coreOrigin.getZ(), BRICK_RUNTIME_SIZE)
		);

		if (brickState == null) {
			return null;
		}

		float maxSpeed = maxFlowSpeedMetersPerSecond(brickState.flowState());
		int n = BRICK_RUNTIME_SIZE;

		short[] packed = new short[n * n * n * NativeSimulationBridge.PACKED_ATLAS_CHANNELS];

		for (int bx = 0; bx < n; bx++) {
			for (int by = 0; by < n; by++) {
				for (int bz = 0; bz < n; bz++) {
					int srcCell = patchCellIndex(bx, by, bz, BRICK_RUNTIME_SIZE);
					int srcBase = srcCell * RESPONSE_CHANNELS;
					int dstCell = ((bx * n) + by) * n + bz;
					int dstBase = dstCell * NativeSimulationBridge.PACKED_ATLAS_CHANNELS;

					packed[dstBase] = quantizeSignedToShort(
							brickState.flowState()[srcBase] * NATIVE_VELOCITY_SCALE,
							ATLAS_VELOCITY_QUANT_RANGE
					);
					packed[dstBase + 1] = quantizeSignedToShort(
							brickState.flowState()[srcBase + 1] * NATIVE_VELOCITY_SCALE,
							ATLAS_VELOCITY_QUANT_RANGE
					);
					packed[dstBase + 2] = quantizeSignedToShort(
							brickState.flowState()[srcBase + 2] * NATIVE_VELOCITY_SCALE,
							ATLAS_VELOCITY_QUANT_RANGE
					);
					packed[dstBase + 3] = quantizeSignedToShort(
							brickState.flowState()[srcBase + 3],
							ATLAS_PRESSURE_QUANT_RANGE
					);
				}
			}
		}

		byte[] packedBytes = AeroFlowPacket.encodePackedFlow(packed);

		return new BrickRuntimeAtlasSnapshot(coreOrigin, packed, packedBytes, maxSpeed);
	}

	private float publishBrickSolveAtlases(Set<WindowKey> solveWindowKeys) {
		if (!shouldRunServerAuthoritativeL2() || solveWindowKeys.isEmpty()) {
			publishedFrame.set(null);
			lastPublishedFrameTick = Integer.MIN_VALUE;
			lastCoordinatorPublishedMaxSpeed = 0.0f;
			return 0.0f;
		}
		Map<WindowKey, BrickRuntimeAtlasSnapshot> atlases = new HashMap<>();
		Map<WindowKey, Float> regionMaxSpeeds = new HashMap<>();
		PublishedFrame previousFrame = publishedFrame.get();
		for (WindowKey key : solveWindowKeys) {
			BrickRuntimeAtlasSnapshot atlas = sampleBrickRuntimeCoreAtlas(key);
			if (atlas == null) {
				continue;
			}
			BrickRuntimeAtlasSnapshot previousAtlas = previousFrame == null ? null : previousFrame.regionAtlases().get(key);
			Float previousMaxSpeed = previousFrame == null ? null : previousFrame.regionMaxSpeeds().get(key);
			if (shouldHoldPreviousAtlas(key, atlas, previousAtlas) || shouldSuppressZeroBrickAtlas(key, atlas)) {
				uploadedBrickDynamicSeedSignatures.remove(key);
				if (previousAtlas == null || previousMaxSpeed == null) {
					continue;
				}
				atlas = previousAtlas;
				atlases.put(key, atlas);
				regionMaxSpeeds.put(key, previousMaxSpeed);
				continue;
			}
			zeroAtlasHoldUntilTick.remove(key);
			atlases.put(key, atlas);
			regionMaxSpeeds.put(key, atlas.maxSpeed());
		}
		zeroAtlasHoldUntilTick.keySet().retainAll(solveWindowKeys);
		if (atlases.isEmpty()) {
			return previousFrame == null ? 0.0f : previousFrame.maxSpeed();
		}
		float maxSpeed = computePublishedMaxSpeed(regionMaxSpeeds);
		PublishedFrame next = new PublishedFrame(
				publishedFrameCounter.incrementAndGet(),
				maxSpeed,
				Map.copyOf(atlases),
				Map.copyOf(regionMaxSpeeds)
		);
		publishedFrame.set(next);
		lastPublishedFrameTick = tickCounter;
		lastCoordinatorPublishedMaxSpeed = maxSpeed;
		lastCoordinatorPublishTick = tickCounter;
		return maxSpeed;
	}

	private void updateSimulationRate(int steppedTicks) {
		secondWindowTotalTicks++;
		if (steppedTicks > 0) {
			secondWindowSimulationTicks += steppedTicks;
		}
		if (secondWindowTotalTicks >= TICKS_PER_SECOND) {
			simulationTicksPerSecond = (secondWindowSimulationTicks * (float) TICKS_PER_SECOND) / secondWindowTotalTicks;
			secondWindowTotalTicks = 0;
			secondWindowSimulationTicks = 0;
		}
	}

	private void grantSimulationStepBudget() {
		while (true) {
			int current = simulationStepBudget.get();
			if (current >= MAX_SIMULATION_STEP_BACKLOG) {
				return;
			}
			if (simulationStepBudget.compareAndSet(current, current + 1)) {
				return;
			}
		}
	}

	public void sendStateToPlayer(ServerPlayer player, MinecraftServer server) {
		ModTemplate.xplat().sendPacketToClient(new AeroRuntimeStatePacket(streamingEnabled, renderVelocityVectorsEnabled, renderStreamlinesEnabled), player);
	}

	public void broadcastState(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sendStateToPlayer(player, server);
		}
	}

	public void sendFlowSnapshotToPlayer(ServerPlayer player, MinecraftServer server) {
		sendCoarseWindSnapshotToPlayer(player);
		sendLocalWeatherSnapshotToPlayer(player);

		if (!SERVER_L2_ATLAS_STREAMING_ENABLED) {
			return;
		}
		if (usesClientLocalL2(player) || isFastPlayerForL2(player)) {
			return;
		}

		PublishedFrame frame = publishedFrame.get();
		if (frame == null) {
			return;
		}

		for (Map.Entry<WindowKey, BrickRuntimeAtlasSnapshot> entry : frame.regionAtlases().entrySet()) {
			WindowKey key = entry.getKey();
			BrickRuntimeAtlasSnapshot atlas = entry.getValue();

			if (!key.worldKey().equals(player.level().dimension())
					|| !containsBlock(atlas.origin(), player.blockPosition(), BRICK_RUNTIME_SIZE)) {
				continue;
			}

			Identifier dimId = key.worldKey().identifier();

			AeroFlowPacket packet = AeroFlowPacket.create(
					dimId, atlas.origin(), PARTICLE_FLOW_SAMPLE_STRIDE, atlas.packed());

			ModTemplate.xplat().sendPacketToClient(packet, player);
		}
	}

	public void sendMeteorologicalMapToPlayer(ServerPlayer player, int requestedLayer, boolean openScreen) {
		if (player == null || !(player.level() instanceof ServerLevel world)) {
			return;
		}

		MesoscaleGrid grid;
		synchronized (simulationStateLock) {
			grid = mesoscaleMetGrids.get(world.dimension());
		}
		if (grid == null) {
			if (openScreen) {
				player.displayClientMessage(Component.translatable("message.aerodynamics4mc.meteorological_map.no_grid"), false);
			}
			return;
		}

		MesoscaleGrid.Snapshot snapshot = grid.snapshot();
		if (snapshot.gridWidth() <= 0 || snapshot.activeLayers() <= 0) {
			if (openScreen) {
				player.displayClientMessage(Component.translatable("message.aerodynamics4mc.meteorological_map.no_grid"), false);
			}
			return;
		}

		int layer = requestedLayer >= 0
				? requestedLayer
				: Math.floorDiv(player.blockPosition().getY() - snapshot.verticalBaseY(), snapshot.layerHeightBlocks());
		layer = Mth.clamp(layer, 0, snapshot.activeLayers() - 1);

		short[] packedLayer = buildMeteorologicalMapLayer(snapshot, layer);
		AeroMesoscaleMapPacket payload = new AeroMesoscaleMapPacket(
				world.dimension().identifier(),
				snapshot.gridWidth(),
				snapshot.activeLayers(),
				layer,
				snapshot.cellSizeBlocks(),
				snapshot.layerHeightBlocks(),
				snapshot.radiusCells(),
				snapshot.centerCellX(),
				snapshot.centerCellZ(),
				snapshot.verticalBaseY(),
				Math.floorDiv(player.blockPosition().getX(), snapshot.cellSizeBlocks()),
				Math.floorDiv(player.blockPosition().getZ(), snapshot.cellSizeBlocks()),
				snapshot.lastTickProcessed(),
				openScreen,
				packedLayer
		);
		ModTemplate.xplat().sendPacketToClient(payload, player);
	}

	private short[] buildMeteorologicalMapLayer(MesoscaleGrid.Snapshot snapshot, int layer) {
		int gridWidth = snapshot.gridWidth();
		short[] packed = new short[gridWidth * gridWidth * AeroMesoscaleMapPacket.CHANNEL_COUNT];
		for (int localX = 0; localX < gridWidth; localX++) {
			for (int localZ = 0; localZ < gridWidth; localZ++) {
				int stateIndex = mesoscaleSnapshotIndex(localX, layer, localZ, gridWidth, snapshot.activeLayers());
				int columnIndex = localX * gridWidth + localZ;
				int dstBase = columnIndex * AeroMesoscaleMapPacket.CHANNEL_COUNT;
				packed[dstBase + AeroMesoscaleMapPacket.CH_WIND_X] = quantizeSignedToShort(
						snapshot.windX()[stateIndex],
						AeroMesoscaleMapPacket.HORIZONTAL_WIND_RANGE_MPS
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_WIND_Y] = quantizeSignedToShort(
						snapshot.windY()[stateIndex],
						AeroMesoscaleMapPacket.VERTICAL_WIND_RANGE_MPS
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_WIND_Z] = quantizeSignedToShort(
						snapshot.windZ()[stateIndex],
						AeroMesoscaleMapPacket.HORIZONTAL_WIND_RANGE_MPS
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_SURFACE_WIND_X] = quantizeSignedToShort(
						snapshot.forcingSurfaceWindX()[stateIndex],
						AeroMesoscaleMapPacket.HORIZONTAL_WIND_RANGE_MPS
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_SURFACE_WIND_Z] = quantizeSignedToShort(
						snapshot.forcingSurfaceWindZ()[stateIndex],
						AeroMesoscaleMapPacket.HORIZONTAL_WIND_RANGE_MPS
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_GEOSTROPHIC_WIND_X] = quantizeSignedToShort(
						snapshot.forcingGeostrophicWindX()[stateIndex],
						AeroMesoscaleMapPacket.HORIZONTAL_WIND_RANGE_MPS
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_GEOSTROPHIC_WIND_Z] = quantizeSignedToShort(
						snapshot.forcingGeostrophicWindZ()[stateIndex],
						AeroMesoscaleMapPacket.HORIZONTAL_WIND_RANGE_MPS
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_HUMIDITY] = quantizeUnitToShort(snapshot.humidity()[stateIndex]);
				packed[dstBase + AeroMesoscaleMapPacket.CH_INSTABILITY] = quantizeSignedToShort(
						snapshot.instabilityProxy()[stateIndex],
						AeroMesoscaleMapPacket.DIAGNOSTIC_RANGE
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_LOW_LEVEL_SHEAR] = quantizeUnsignedRangeToShort(
						snapshot.lowLevelShear()[stateIndex],
						AeroMesoscaleMapPacket.SHEAR_RANGE
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_MOISTURE_CONVERGENCE] = quantizeSignedToShort(
						snapshot.moistureConvergence()[stateIndex],
						AeroMesoscaleMapPacket.DIAGNOSTIC_RANGE
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_LIFT] = quantizeSignedToShort(
						snapshot.liftProxy()[stateIndex],
						AeroMesoscaleMapPacket.DIAGNOSTIC_RANGE
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_ABL_AGL_HEIGHT] = quantizeUnsignedRangeToShort(
						snapshot.ablHeightAglBlocks()[stateIndex],
						AeroMesoscaleMapPacket.ABL_HEIGHT_RANGE_BLOCKS
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_ABL_STABILITY] = quantizeSignedToShort(
						snapshot.ablStability()[stateIndex],
						1.0f
				);
				packed[dstBase + AeroMesoscaleMapPacket.CH_ABL_MIXING] = quantizeUnitToShort(snapshot.ablMixingStrength()[stateIndex]);
				packed[dstBase + AeroMesoscaleMapPacket.CH_TERRAIN_SOLID] = quantizeUnitToShort(snapshot.terrainSolidMask()[stateIndex]);
				packed[dstBase + AeroMesoscaleMapPacket.CH_SURFACE_CLASS] = (short) (snapshot.surfaceClass()[columnIndex] & 0xFF);
			}
		}
		return packed;
	}

	private int mesoscaleSnapshotIndex(int localX, int layer, int localZ, int gridWidth, int activeLayers) {
		int columnIndex = localX * gridWidth + localZ;
		return columnIndex * activeLayers + layer;
	}

	private short quantizeUnitToShort(float value) {
		float unit = Mth.clamp(finiteOrDefault(value, 0.0f), 0.0f, 1.0f);
		return quantizeSignedToShort(unit * 2.0f - 1.0f, 1.0f);
	}

	private short quantizeUnsignedRangeToShort(float value, float range) {
		if (!(range > 0.0f)) {
			return 0;
		}
		float unit = Mth.clamp(finiteOrDefault(value, 0.0f) / range, 0.0f, 1.0f);
		return quantizeSignedToShort(unit * 2.0f - 1.0f, 1.0f);
	}

	private void sendCoarseWindSnapshotToPlayer(ServerPlayer player) {
		CoarseWindSyncState state = coarseWindSyncStateForPlayer(player);
		AeroCoarseWindPacket payload = buildCoarseWindPayloadForPlayer(player, state);
		if (payload != null) {
			ModTemplate.xplat().sendPacketToClient(payload, player);
			lastCoarseWindSyncStates.put(player.getUUID(), state);
		}
	}

	private void sendLocalWeatherSnapshotToPlayer(ServerPlayer player) {
		AeroLocalWeatherPacket payload = buildLocalWeatherPayloadForPlayer(player);
		if (payload != null) {
			ModTemplate.xplat().sendPacketToClient(payload, player);
			lastLocalWeatherSyncStates.put(player.getUUID(), localWeatherSyncStateFor(player, payload));
		}
	}

	public Biome.Precipitation localPrecipitationAt(ServerLevel world, BlockPos pos) {
		if (world == null || pos == null || !streamingEnabled) {
			return null;
		}
		//? >=1.21.11 {
		if (!world.canHaveWeather()) {
			return null;
		}
		//?}
		LocalWeatherServerState state = localWeatherStateFor(world);
		if (state == null || !state.covers(pos)) {
			return null;
		}

		if (!world.canSeeSky(pos)) {
			return Biome.Precipitation.NONE;
		}
		if (world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) {
			return Biome.Precipitation.NONE;
		}
		Biome.Precipitation biomePrecipitation;
		//? >=1.21.11 {
		biomePrecipitation = world.getBiome(pos).value().getPrecipitationAt(pos, world.getSeaLevel());
		//?} <1.21.11 {
		/*biomePrecipitation = world.getBiome(pos).value().getPrecipitationAt(pos);
		*///?}
		if (biomePrecipitation == Biome.Precipitation.NONE) {
			return Biome.Precipitation.NONE;
		}

		float precipitation = state.sample(pos.getX(), pos.getZ(), AeroLocalWeatherPacket.CH_PRECIPITATION);
		if (precipitation < LocalWeatherServerState.MIN_GAMEPLAY_PRECIPITATION) {
			return Biome.Precipitation.NONE;
		}
		float snowFraction = state.sample(pos.getX(), pos.getZ(), AeroLocalWeatherPacket.CH_SNOW_FRACTION);
		return snowFraction > 0.52f ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
	}

	private LocalWeatherServerState localWeatherStateFor(ServerLevel world) {
		ResourceKey<Level> worldKey = world.dimension();
		LocalWeatherServerState cached = localWeatherServerStates.get(worldKey);
		if (cached != null && tickCounter - cached.runtimeTick() <= LOCAL_WEATHER_SERVER_STATE_MAX_AGE_TICKS) {
			return cached;
		}

		MesoscaleGrid grid;
		synchronized (simulationStateLock) {
			grid = mesoscaleMetGrids.get(worldKey);
		}
		if (grid == null) {
			return null;
		}
		MesoscaleGrid.Snapshot snapshot = grid.snapshot();
		if (snapshot.gridWidth() <= 0 || snapshot.activeLayers() <= 0) {
			return null;
		}
		AeroLocalWeatherPacket payload = LocalWeatherGrid.buildPacket(world.dimension().identifier(), snapshot);
		if (payload == null) {
			return null;
		}
		LocalWeatherServerState next = new LocalWeatherServerState(worldKey, payload, tickCounter);
		localWeatherServerStates.put(worldKey, next);
		return next;
	}

	public void waitForSolverIdle() {
		// Window solve tasks no longer exist; brick epochs run synchronously in the coordinator.
	}

	private void ensureSimulationCoordinatorRunning() {
		synchronized (coordinatorLifecycleLock) {
			if (simulationCoordinator != null && simulationCoordinator.running()) {
				return;
			}
			lastCoordinatorError = "";
			simulationCoordinator = new SimulationCoordinator();
			simulationCoordinator.start();
		}
	}

	public void stopSimulationCoordinator() {
		SimulationCoordinator coordinator;
		synchronized (coordinatorLifecycleLock) {
			coordinator = simulationCoordinator;
			simulationCoordinator = null;
		}
		if (coordinator != null) {
			coordinator.shutdown();
		}
	}


	private void publishRegionAtlas(WindowKey key, float regionMaxSpeed) {
		if (simulationServiceId == 0L) {
			return;
		}
		BrickRuntimeAtlasSnapshot atlas = sampleBrickRuntimeCoreAtlas(key);
		if (atlas == null) {
			return;
		}
		while (true) {
			PublishedFrame current = publishedFrame.get();
			Map<WindowKey, BrickRuntimeAtlasSnapshot> nextAtlases = new HashMap<>(current == null ? Map.of() : current.regionAtlases());
			Map<WindowKey, Float> nextRegionMaxSpeeds = new HashMap<>(current == null ? Map.of() : current.regionMaxSpeeds());
			nextAtlases.put(key, atlas);
			nextRegionMaxSpeeds.put(key, Math.max(0.0f, regionMaxSpeed));
			float nextMaxSpeed = computePublishedMaxSpeed(nextRegionMaxSpeeds);
			PublishedFrame next = new PublishedFrame(
					publishedFrameCounter.incrementAndGet(),
					nextMaxSpeed,
					Map.copyOf(nextAtlases),
					Map.copyOf(nextRegionMaxSpeeds)
			);
			if (publishedFrame.compareAndSet(current, next)) {
				lastPublishedFrameTick = tickCounter;
				lastCoordinatorPublishedMaxSpeed = nextMaxSpeed;
				lastCoordinatorPublishTick = tickCounter;
				return;
			}
		}
	}

	private void removePublishedRegionAtlas(WindowKey key) {
		while (true) {
			PublishedFrame current = publishedFrame.get();
			if (current == null || !current.regionAtlases().containsKey(key)) {
				return;
			}
			Map<WindowKey, BrickRuntimeAtlasSnapshot> nextAtlases = new HashMap<>(current.regionAtlases());
			Map<WindowKey, Float> nextRegionMaxSpeeds = new HashMap<>(current.regionMaxSpeeds());
			nextAtlases.remove(key);
			nextRegionMaxSpeeds.remove(key);
			PublishedFrame next = nextAtlases.isEmpty()
					? null
					: new PublishedFrame(
					publishedFrameCounter.incrementAndGet(),
					computePublishedMaxSpeed(nextRegionMaxSpeeds),
					Map.copyOf(nextAtlases),
					Map.copyOf(nextRegionMaxSpeeds)
			);
			if (publishedFrame.compareAndSet(current, next)) {
				if (next == null) {
					lastPublishedFrameTick = Integer.MIN_VALUE;
					lastCoordinatorPublishedMaxSpeed = 0.0f;
				}
				return;
			}
		}
	}

	private float computePublishedMaxSpeed(Map<WindowKey, Float> regionMaxSpeeds) {
		float maxSpeed = 0.0f;
		for (float value : regionMaxSpeeds.values()) {
			if (Float.isFinite(value) && value > maxSpeed) {
				maxSpeed = value;
			}
		}
		return maxSpeed;
	}

	private void syncPublishedFlowToPlayers(MinecraftServer server, PublishedFrame frame) {
		Set<UUID> observedPlayers = new HashSet<>();
		Set<UUID> sentPlayers = new HashSet<>();
		int sentPayloads = 0;

		int flowAtlasIntervalTicks = flowAtlasResendIntervalTicks(server.getPlayerList().getPlayers().size());

		for (Map.Entry<WindowKey, BrickRuntimeAtlasSnapshot> entry : frame.regionAtlases().entrySet()) {
			WindowKey key = entry.getKey();
			ServerLevel world = server.getLevel(key.worldKey());
			if (world == null) continue;

			BrickRuntimeAtlasSnapshot atlas = entry.getValue();
			List<ServerPlayer> recipients = playersInsideFlowAtlas(world, atlas);
			if (recipients.isEmpty()) continue;

			AeroFlowPacket packet = null;

			for (ServerPlayer player : recipients) {
				UUID playerId = player.getUUID();
				observedPlayers.add(playerId);

				if (sentPlayers.contains(playerId)) continue;
				if (!shouldSendFlowAtlasToPlayer(player, key, atlas, frame, flowAtlasIntervalTicks)) continue;
				if (sentPayloads >= FLOW_ATLAS_MAX_PAYLOADS_PER_SYNC) continue;

				if (packet == null) {
					packet = AeroFlowPacket.create(
							world.dimension().identifier(),
							atlas.origin(),
							PARTICLE_FLOW_SAMPLE_STRIDE,
							atlas.packed()
					);
				}

				ModTemplate.xplat().sendPacketToClient(packet, player);

				lastFlowAtlasSyncStates.put(
						playerId,
						new FlowAtlasSyncState(key.worldKey(), atlas.origin(), frame.frameId(), tickCounter)
				);

				sentPlayers.add(playerId);
				sentPayloads++;
			}
		}

		lastFlowAtlasSyncStates.keySet().retainAll(observedPlayers);
	}

	private boolean shouldSendFlowAtlasToPlayer(
			ServerPlayer player,
			WindowKey key,
			BrickRuntimeAtlasSnapshot atlas,
			PublishedFrame frame,
			int flowAtlasIntervalTicks
	) {
		if (usesClientLocalL2(player)) {
			return false;
		}
		if (isFastPlayerForL2(player)) {
			return false;
		}
		FlowAtlasSyncState previous = lastFlowAtlasSyncStates.get(player.getUUID());
		if (previous == null || !previous.sameAtlas(key, atlas)) {
			return true;
		}
		int ticksSinceLastSend = tickCounter - previous.tick();
		if (frame.frameId() <= previous.frameId()) {
			return ticksSinceLastSend >= flowAtlasIntervalTicks;
		}
		return ticksSinceLastSend >= flowAtlasIntervalTicks;
	}

	private int flowAtlasResendIntervalTicks(int playerCount) {
		int onlinePlayers = Math.max(1, playerCount);
		int interval = FLOW_ATLAS_BASE_RESEND_INTERVAL_TICKS
				+ (onlinePlayers - 1) * FLOW_ATLAS_PLAYER_INTERVAL_INCREMENT_TICKS;
		return Mth.clamp(
				interval,
				FLOW_ATLAS_BASE_RESEND_INTERVAL_TICKS,
				FLOW_ATLAS_MAX_RESEND_INTERVAL_TICKS
		);
	}

	private boolean isFastPlayerForL2(ServerPlayer player) {
		return player != null && AeroMinecraftVectors.isFastPlayerVelocity(player.getDeltaMovement());
	}

	private List<ServerPlayer> playersInsideFlowAtlas(ServerLevel world, BrickRuntimeAtlasSnapshot atlas) {
		List<ServerPlayer> recipients = new ArrayList<>();
		for (ServerPlayer player : world.players()) {
			if (usesClientLocalL2(player)) {
				continue;
			}
			if (containsBlock(atlas.origin(), player.blockPosition(), BRICK_RUNTIME_SIZE)) {
				recipients.add(player);
			}
		}
		return recipients;
	}

	private void syncCoarseWindToPlayers(MinecraftServer server) {
		Set<UUID> observedPlayers = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			observedPlayers.add(player.getUUID());
			CoarseWindSyncState state = coarseWindSyncStateForPlayer(player);
			CoarseWindSyncState previous = lastCoarseWindSyncStates.get(player.getUUID());
			boolean staleAfterBackgroundRefresh = previous != null
					&& lastBackgroundRefreshAppliedTick != Integer.MIN_VALUE
					&& previous.tick() < lastBackgroundRefreshAppliedTick;
			if (previous != null
					&& previous.sameRegion(state)
					&& !staleAfterBackgroundRefresh
					&& tickCounter - previous.tick() < COARSE_WIND_RESEND_INTERVAL_TICKS) {
				continue;
			}
			AeroCoarseWindPacket payload = buildCoarseWindPayloadForPlayer(player, state);
			if (payload != null) {
				ModTemplate.xplat().sendPacketToClient(payload, player);
				lastCoarseWindSyncStates.put(player.getUUID(), state);
			}
		}
		lastCoarseWindSyncStates.keySet().retainAll(observedPlayers);
	}

	private void syncLocalWeatherToPlayers(MinecraftServer server) {
		Set<UUID> observedPlayers = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			observedPlayers.add(player.getUUID());
			AeroLocalWeatherPacket payload = buildLocalWeatherPayloadForPlayer(player);
			if (payload == null) {
				continue;
			}
			LocalWeatherSyncState state = localWeatherSyncStateFor(player, payload);
			LocalWeatherSyncState previous = lastLocalWeatherSyncStates.get(player.getUUID());
			if (previous != null
					&& previous.sameSnapshot(state)
					&& tickCounter - previous.tick() < LOCAL_WEATHER_RESEND_INTERVAL_TICKS) {
				continue;
			}
			ModTemplate.xplat().sendPacketToClient(payload, player);
			lastLocalWeatherSyncStates.put(player.getUUID(), state);
		}
		lastLocalWeatherSyncStates.keySet().retainAll(observedPlayers);
	}

	private AeroLocalWeatherPacket buildLocalWeatherPayloadForPlayer(ServerPlayer player) {
		if (player == null || !(player.level() instanceof ServerLevel world)) {
			return null;
		}
		MesoscaleGrid grid;
		synchronized (simulationStateLock) {
			grid = mesoscaleMetGrids.get(world.dimension());
		}
		if (grid == null) {
			return null;
		}
		MesoscaleGrid.Snapshot snapshot = grid.snapshot();
		if (snapshot.gridWidth() <= 0 || snapshot.activeLayers() <= 0) {
			return null;
		}
		AeroLocalWeatherPacket payload = LocalWeatherGrid.buildPacket(world.dimension().identifier(), snapshot);
		if (payload != null) {
			localWeatherServerStates.put(world.dimension(), new LocalWeatherServerState(world.dimension(), payload, tickCounter));
		}
		return payload;
	}

	private LocalWeatherSyncState localWeatherSyncStateFor(ServerPlayer player, AeroLocalWeatherPacket payload) {
		ServerLevel world = (ServerLevel) player.level();
		return new LocalWeatherSyncState(
				world.dimension(),
				payload.getCenterCellX(),
				payload.getCenterCellZ(),
				payload.getServerTick(),
				tickCounter
		);
	}

	private CoarseWindSyncState coarseWindSyncStateForPlayer(ServerPlayer player) {
		ServerLevel world = (ServerLevel) player.level();
		BlockPos playerPos = player.blockPosition();
		int cellSize = COARSE_WIND_SYNC_CELL_SIZE_BLOCKS;
		return new CoarseWindSyncState(
				world.dimension(),
				coarseWindOriginFor(playerPos.getX(), COARSE_WIND_SYNC_SIZE_X, cellSize),
				coarseWindOriginFor(playerPos.getY(), COARSE_WIND_SYNC_SIZE_Y, cellSize),
				coarseWindOriginFor(playerPos.getZ(), COARSE_WIND_SYNC_SIZE_Z, cellSize),
				tickCounter
		);
	}

	private AeroCoarseWindPacket buildCoarseWindPayloadForPlayer(ServerPlayer player, CoarseWindSyncState state) {
		ServerLevel world = (ServerLevel) player.level();
		if (world == null || state == null) {
			return null;
		}
		int originX = state.originX();
		int originY = state.originY();
		int originZ = state.originZ();
		int cellSize = COARSE_WIND_SYNC_CELL_SIZE_BLOCKS;
		BlockPos origin = new BlockPos(originX, originY, originZ);
		short[] packed = new short[
				COARSE_WIND_SYNC_SIZE_X
						* COARSE_WIND_SYNC_SIZE_Y
						* COARSE_WIND_SYNC_SIZE_Z
						* NativeSimulationBridge.PACKED_ATLAS_CHANNELS
				];
		short[] packedAtmosphere = new short[
				COARSE_WIND_SYNC_SIZE_X
						* COARSE_WIND_SYNC_SIZE_Y
						* COARSE_WIND_SYNC_SIZE_Z
						* COARSE_ATMOSPHERE_CHANNELS
				];
		synchronized (simulationStateLock) {
			if (!hasCoarseWindFieldLocked(world.dimension())) {
				return null;
			}
			for (int x = 0; x < COARSE_WIND_SYNC_SIZE_X; x++) {
				for (int y = 0; y < COARSE_WIND_SYNC_SIZE_Y; y++) {
					for (int z = 0; z < COARSE_WIND_SYNC_SIZE_Z; z++) {
						int dstCell = ((x * COARSE_WIND_SYNC_SIZE_Y) + y) * COARSE_WIND_SYNC_SIZE_Z + z;
						int dstBase = dstCell * NativeSimulationBridge.PACKED_ATLAS_CHANNELS;
						int atmosphereBase = dstCell * COARSE_ATMOSPHERE_CHANNELS;
						BlockPos samplePos = BlockPos.containing(
								originX + (x + 0.5) * cellSize,
								originY + (y + 0.5) * cellSize,
								originZ + (z + 0.5) * cellSize
						);
						AeroWindSample sample = sampleCoarseWindLocked(world.dimension(), samplePos);
						packed[dstBase] = quantizeSignedToShort(sample.velocityX(), ATLAS_VELOCITY_QUANT_RANGE);
						packed[dstBase + 1] = quantizeSignedToShort(sample.velocityY(), ATLAS_VELOCITY_QUANT_RANGE);
						packed[dstBase + 2] = quantizeSignedToShort(sample.velocityZ(), ATLAS_VELOCITY_QUANT_RANGE);
						packed[dstBase + 3] = quantizeSignedToShort(sample.pressure(), ATLAS_PRESSURE_QUANT_RANGE);
						packedAtmosphere[atmosphereBase] = quantizeSignedToShort(
								finiteOrDefault(sample.temperatureKelvin(), 288.15f) - 288.15f,
								COARSE_TEMPERATURE_ANOMALY_RANGE_K
						);
						packedAtmosphere[atmosphereBase + 1] = quantizeSignedToShort(
								Mth.clamp(finiteOrDefault(sample.humidity(), 0.0f), 0.0f, 1.0f) * 2.0f - 1.0f,
								1.0f
						);
						packedAtmosphere[atmosphereBase + 2] = quantizeSignedToShort(sample.turbulenceIntensity(), COARSE_TURBULENCE_RANGE_MPS);
						packedAtmosphere[atmosphereBase + 3] = quantizeSignedToShort(sample.gustX(), ATLAS_VELOCITY_QUANT_RANGE);
						packedAtmosphere[atmosphereBase + 4] = quantizeSignedToShort(sample.gustY(), ATLAS_VELOCITY_QUANT_RANGE);
						packedAtmosphere[atmosphereBase + 5] = quantizeSignedToShort(sample.gustZ(), ATLAS_VELOCITY_QUANT_RANGE);
						packedAtmosphere[atmosphereBase + 6] = quantizeSignedToShort(sample.windShearXPerBlock(), COARSE_SHEAR_RANGE_PER_BLOCK);
						packedAtmosphere[atmosphereBase + 7] = quantizeSignedToShort(sample.windShearZPerBlock(), COARSE_SHEAR_RANGE_PER_BLOCK);
						packedAtmosphere[atmosphereBase + 8] = quantizeSignedToShort(sample.ablStability(), 1.0f);
						packedAtmosphere[atmosphereBase + 9] = quantizeSignedToShort(
								Mth.clamp(sample.ablMixingStrength(), 0.0f, 1.0f) * 2.0f - 1.0f,
								1.0f
						);
					}
				}
			}
		}
		return new AeroCoarseWindPacket(
				world.dimension().identifier(),
				origin,
				cellSize,
				COARSE_WIND_SYNC_SIZE_X,
				COARSE_WIND_SYNC_SIZE_Y,
				COARSE_WIND_SYNC_SIZE_Z,
				tickCounter,
				packed,
				packedAtmosphere
		);
	}

	private boolean hasCoarseWindFieldLocked(ResourceKey<Level> worldKey) {
		return worldKey != null
				&& (mesoscaleMetGrids.containsKey(worldKey) || backgroundMetGrids.containsKey(worldKey));
	}

	private int coarseWindOriginFor(int blockCoord, int size, int cellSize) {
		return Math.floorDiv(blockCoord, cellSize) * cellSize - (size / 2) * cellSize;
	}

	private void syncAnalysisFlowToPlayers(MinecraftServer server, PublishedFrame frame) {
		Map<WindowKey, AeroFlowAnalysisPacket> payloadCache = new HashMap<>();
		Set<WindowKey> missingKeys = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel world = (ServerLevel) player.level();
			WindowKey key = new WindowKey(world.dimension(), windowOriginFromCoreOrigin(coreOriginForPosition(player.blockPosition())));
			BrickRuntimeAtlasSnapshot atlas = frame.regionAtlases().get(key);
			if (atlas == null || missingKeys.contains(key)) {
				continue;
			}
			AeroFlowAnalysisPacket payload = payloadCache.get(key);
			if (payload == null && !payloadCache.containsKey(key)) {
				payload = buildAnalysisFlowPayload(world, key, atlas.packed());
				if (payload == null) {
					missingKeys.add(key);
					continue;
				}
				payloadCache.put(key, payload);
			}
			if (payload != null) {
				ModTemplate.xplat().sendPacketToClient(payload, player);
			}
		}
	}

	private AeroFlowAnalysisPacket buildAnalysisFlowPayload(ServerLevel world, WindowKey key, short[] basePacked) {
		float[] fullFlowState = null;
		synchronized (simulationStateLock) {
			if (simulationServiceId == 0L) {
				return null;
			}
			RegionRecord region = regions.get(key);
			if (region == null || !region.serviceReady) {
				return null;
			}
			fullFlowState = new float[GRID_SIZE * GRID_SIZE * GRID_SIZE * NativeSimulationBridge.FLOW_STATE_CHANNELS];
			if (!simulationBridge.getRegionFlowState(
					simulationServiceId,
					simulationRegionKey(key),
					GRID_SIZE,
					GRID_SIZE,
					GRID_SIZE,
					fullFlowState
			)) {
				return null;
			}
		}
		return AnalysisFlowCodec.encodePayload(
				simulationBridge,
				world.dimension().identifier(),
				key.origin(),
				PARTICLE_FLOW_SAMPLE_STRIDE,
				basePacked,
				GRID_SIZE,
				fullFlowState,
				ANALYSIS_FLOW_VELOCITY_TOLERANCE,
				ANALYSIS_FLOW_PRESSURE_TOLERANCE
		);
	}

	private Map<UUID, PlayerProbe> samplePlayerProbesLocked() {
		if (simulationServiceId == 0L || activePlayerProbeRequests.isEmpty()) {
			return Map.of();
		}
		Map<UUID, PlayerProbe> probes = new HashMap<>();
		float[] rawProbe = new float[NativeSimulationBridge.PLAYER_PROBE_CHANNELS];
		for (PlayerProbeRequest request : activePlayerProbeRequests) {
			SampledPoint sample = sampleRegionPointLocked(request.worldKey(), request.blockPos(), rawProbe);
			if (sample == null) {
				continue;
			}
			probes.put(request.playerId(), buildPlayerProbe(request, sample));
		}
		return probes.isEmpty() ? Map.of() : Map.copyOf(probes);
	}

	private Map<UUID, EntitySample> sampleEntitySamplesLocked() {
		if (!ENTITY_SAMPLE_COLLECTION_ENABLED || simulationServiceId == 0L || activeEntitySampleRequests.isEmpty()) {
			return Map.of();
		}
		Map<UUID, EntitySample> samples = new HashMap<>();
		float[] rawProbe = new float[NativeSimulationBridge.PLAYER_PROBE_CHANNELS];
		for (EntitySampleRequest request : activeEntitySampleRequests) {
			SampledPoint sample = sampleRegionPointLocked(request.worldKey(), request.blockPos(), rawProbe);
			if (sample == null) {
				continue;
			}
			samples.put(
					request.entityId(),
					new EntitySample(
							request.entityId(),
							request.worldKey(),
							request.blockPos(),
							sample.velocityX(),
							sample.velocityY(),
							sample.velocityZ(),
							sample.pressure(),
							sample.airTemperatureKelvin(),
							sample.surfaceTemperatureKelvin()
					)
			);
		}
		return samples.isEmpty() ? Map.of() : Map.copyOf(samples);
	}

	private SampledPoint sampleRegionPointLocked(ResourceKey<Level> worldKey, BlockPos probePos, float[] rawProbe) {
		SampledPoint brickSample = sampleBrickRuntimePointLocked(worldKey, probePos);
		if (brickSample != null) {
			return brickSample;
		}
		SampledPoint coarseSample = sampleCoarsePointLocked(worldKey, probePos);
		if (coarseSample != null) {
			return coarseSample;
		}
		WindowKey key = new WindowKey(worldKey, windowOriginFromCoreOrigin(coreOriginForPosition(probePos)));
		RegionRecord region = regions.get(key);
		if (region == null || !region.serviceReady) {
			return null;
		}
		return sampleRegionPointForKey(key, probePos, rawProbe);
	}

	private SampledPoint sampleRegionPointForKey(WindowKey key, BlockPos probePos, float[] rawProbe) {
		ResourceKey<Level> worldKey = key.worldKey();
		int localX = probePos.getX() - key.origin().getX();
		int localY = probePos.getY() - key.origin().getY();
		int localZ = probePos.getZ() - key.origin().getZ();
		if (!inBounds(localX, localY, localZ)) {
			return null;
		}
		if (localX >= REGION_HALO_CELLS && localX < REGION_HALO_CELLS + BRICK_RUNTIME_SIZE
				&& localY >= REGION_HALO_CELLS && localY < REGION_HALO_CELLS + BRICK_RUNTIME_SIZE
				&& localZ >= REGION_HALO_CELLS && localZ < REGION_HALO_CELLS + BRICK_RUNTIME_SIZE) {
			SampledPoint brickSample = sampleBrickRuntimePointLocked(worldKey, probePos);
			if (brickSample != null) {
				return brickSample;
			}
		}
		if (!simulationBridge.sampleRegionPoint(
				simulationServiceId,
				simulationRegionKey(key),
				GRID_SIZE,
				GRID_SIZE,
				GRID_SIZE,
				localX,
				localY,
				localZ,
				rawProbe
		)) {
			return null;
		}
		ThermalEnvironment environment = sampleThermalEnvironment(
				worldEnvironmentSnapshots.get(worldKey),
				worldKey,
				probePos,
				SOLVER_STEP_SECONDS
		);
		return new SampledPoint(
				rawProbe[0] * NATIVE_VELOCITY_SCALE,
				rawProbe[1] * NATIVE_VELOCITY_SCALE,
				rawProbe[2] * NATIVE_VELOCITY_SCALE,
				rawProbe[3],
				environment.ambientAirTemperatureKelvin() + rawProbe[4] * RUNTIME_TEMPERATURE_SCALE_KELVIN,
				rawProbe[5]
		);
	}

	private PlayerProbe buildPlayerProbe(PlayerProbeRequest request, SampledPoint sample) {
		return new PlayerProbe(
				request.playerId(),
				request.worldKey(),
				request.blockPos(),
				sample.velocityX(),
				sample.velocityY(),
				sample.velocityZ(),
				sample.pressure(),
				sample.airTemperatureKelvin(),
				sample.surfaceTemperatureKelvin()
		);
	}

	private void releaseWindow(WindowKey key, RegionRecord region) {
		if (!region.markReleased()) {
			return;
		}
		if (simulationServiceId != 0L) {
			simulationBridge.releaseRegionRuntime(simulationServiceId, simulationRegionKey(key));
		}
	}

	public static void feedback(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal(LOG_PREFIX + message), false);
	}

	public void log(String message) {
		System.out.println(LOG_PREFIX + message);
	}

	public record WindowKey(ResourceKey<Level> worldKey, BlockPos origin) {
	}

	public record ThermalEnvironment(
			float directSolarFluxWm2,
			float diffuseSolarFluxWm2,
			float ambientAirTemperatureKelvin,
			float deepGroundTemperatureKelvin,
			float skyTemperatureKelvin,
			float precipitationTemperatureKelvin,
			float precipitationStrength,
			float sunX,
			float sunY,
			float sunZ,
			float surfaceDeltaSeconds
	) {
	}

	record WorldEnvironmentSnapshot(
			long timeOfDay,
			float rainGradient,
			float thunderGradient,
			int seaLevel
	) {
	}

	private record BackgroundRefreshRequest(
			ServerLevel world,
			BlockPos focus,
			WorldEnvironmentSnapshot environmentSnapshot
	) {
	}

	public record InspectionPatchStaticFields(
			byte[] obstacle,
			byte[] surfaceKind,
			byte[] openFaceMask,
			float[] emitterPowerWatts,
			byte[] faceSkyExposure,
			byte[] faceDirectExposure
	) {
	}

	public record InspectionPatchInput(
			ResourceKey<Level> worldKey,
			BlockPos focus,
			BlockPos origin,
			int domainBlocks,
			int gridResolution,
			int cellsPerBlock,
			int faceResolution,
			Path outputDir,
			WorldEnvironmentSnapshot environmentSnapshot,
			NestedBoundaryCoupler.BoundarySample fallbackBoundary,
			ThermalEnvironment thermalEnvironment,
			BoundaryFieldData boundaryField,
			InspectionPatchStaticFields staticFields,
			List<FanSource> fans
	) {
	}

	public record InspectionPatchForcing(
			float[] thermalSource,
			byte[] fanMask,
			float[] fanVx,
			float[] fanVy,
			float[] fanVz
	) {
	}

	public record InspectionPatchDynamicState(
			float[] flowState,
			float[] airTemperature,
			float[] surfaceTemperature
	) {
	}

	public record InspectionPatchSolveResult(
			int domainBlocks,
			int gridResolution,
			int cellsPerBlock,
			float[] vx,
			float[] vy,
			float[] vz,
			float[] pressure,
			float[] airTemperature,
			float[] surfaceTemperature,
			int completedSteps,
			float maxSpeedMps
	) {
	}

	public record PlayerRegionAnchor(
			ResourceKey<Level> worldKey,
			BlockPos coreOrigin,
			BlockPos blockPos
	) {
	}

	private record PlayerMotionAnchorState(
			ResourceKey<Level> worldKey,
			BlockPos coreOrigin,
			int lastSeenTick
	) {
	}

	private record PlayerProbeRequest(
			UUID playerId,
			ResourceKey<Level> worldKey,
			BlockPos blockPos
	) {
	}

	private record EntitySampleRequest(
			UUID entityId,
			ResourceKey<Level> worldKey,
			BlockPos blockPos
	) {
	}

	private record ChunkResidentBrickRefreshRequest(
			ResourceKey<Level> worldKey,
			int chunkX,
			int chunkZ
	) {
	}

	private record WorldDeltaQueueKey(
			int group,
			int x,
			int y,
			int z,
			int worldHash
	) {
	}

	private record BrickRuntimeHint(
			int brickX,
			int brickY,
			int brickZ
	) {
	}

	private record ActiveRegionBatch(
			int tickCounter,
			List<PlayerRegionAnchor> anchors,
			List<PlayerProbeRequest> playerProbeRequests,
			List<EntitySampleRequest> entitySampleRequests,
			Map<ResourceKey<Level>, WorldEnvironmentSnapshot> environmentSnapshots
	) {
	}

	private record BackgroundRefreshBatch(
			int tickCounter,
			Map<ResourceKey<Level>, BackgroundRefreshRequest> requests
	) {
	}

	private record BackgroundRefreshWork(
			int tickCounter,
			ResourceKey<Level> worldKey,
			BackgroundRefreshRequest request,
			WorldScaleDriver driver,
			BackgroundMetGrid backgroundGrid,
			MesoscaleGrid mesoscaleGrid,
			ConcurrentLinkedQueue<MesoscaleGrid.NestedFeedbackBin> feedbackQueue
	) {
	}

	private record BackgroundRefreshTiming(
			long diagnosticsNanos,
			long driverNanos,
			long l0Nanos,
			long l1Nanos,
			long feedbackNanos
	) {
		private static final BackgroundRefreshTiming EMPTY = new BackgroundRefreshTiming(0L, 0L, 0L, 0L, 0L);
	}

	private record ThermalMaterial(
			byte kind,
			float solarAbsorptivity,
			float emissivity,
			float surfaceHeatCapacityJm2K,
			float convectiveExchangeCoefficientWm2K,
			float bulkConductanceWm2K,
			float rainExchangeCoefficientWm2K,
			boolean atmosphericExchangeRequiresAirNeighbor
	) {
		private static final ThermalMaterial ROCK = new ThermalMaterial(SURFACE_KIND_ROCK, 0.78f, 0.92f, 1.60e5f, 8.0f, 2.4f, 20.0f, false);
		private static final ThermalMaterial SOIL = new ThermalMaterial(SURFACE_KIND_SOIL, 0.88f, 0.94f, 1.35e5f, 7.0f, 1.7f, 24.0f, false);
		private static final ThermalMaterial VEGETATION = new ThermalMaterial(SURFACE_KIND_VEGETATION, 0.64f, 0.96f, 1.90e5f, 9.0f, 1.2f, 32.0f, false);
		private static final ThermalMaterial SNOW_ICE = new ThermalMaterial(SURFACE_KIND_SNOW_ICE, 0.22f, 0.98f, 2.40e5f, 6.0f, 1.0f, 18.0f, false);
		private static final ThermalMaterial WATER = new ThermalMaterial(SURFACE_KIND_WATER, 0.93f, 0.96f, 1.00e6f, 10.0f, 0.6f, 40.0f, true);
		private static final ThermalMaterial MOLTEN = new ThermalMaterial(SURFACE_KIND_MOLTEN, 0.95f, 0.95f, 3.50e5f, 14.0f, 4.0f, 18.0f, false);
	}

	public record FanSource(BlockPos pos, Direction facing, int ductLength) {
	}

	public record PlayerProbe(
			UUID playerId,
			ResourceKey<Level> worldKey,
			BlockPos blockPos,
			float velocityX,
			float velocityY,
			float velocityZ,
			float pressure,
			float airTemperatureKelvin,
			float surfaceTemperatureKelvin
	) {
	}

	public record EntitySample(
			UUID entityId,
			ResourceKey<Level> worldKey,
			BlockPos blockPos,
			float velocityX,
			float velocityY,
			float velocityZ,
			float pressure,
			float airTemperatureKelvin,
			float surfaceTemperatureKelvin
	) {
	}

	private record SampledPoint(
			float velocityX,
			float velocityY,
			float velocityZ,
			float pressure,
			float airTemperatureKelvin,
			float surfaceTemperatureKelvin
	) {
	}

	private record BrickRuntimeDynamicState(
			float[] flowState,
			float[] airTemperatureState,
			float[] surfaceTemperatureState
	) {
	}

	private record BrickRuntimeAtlasSnapshot(BlockPos origin, short[] packed, byte[] packedFlowBytes, float maxSpeed) {
	}

	private record TornadoRegionDescriptor(
			int id,
			int parentConvectiveClusterId,
			float centerXBlocks,
			float centerYBlocks,
			float centerZBlocks,
			float translationXBlocksPerSecond,
			float translationZBlocksPerSecond,
			float coreRadiusBlocks,
			float influenceRadiusBlocks,
			float tangentialWindScaleMps,
			float radialInflowScaleMps,
			float updraftScale,
			float condensationBias,
			float intensity,
			float rotationSign,
			int stateOrdinal,
			float lifecycleEnvelope
	) {
	}

	public record BoundaryFieldData(
			int faceResolution,
			int externalFaceMask,
			float[] windX,
			float[] windY,
			float[] windZ,
			float[] airTemperatureKelvin
	) {
	}

	private record NestedMetState(
			float windX,
			float windY,
			float windZ,
			float airTemperatureKelvin,
			boolean verticalWindAvailable
	) {
	}

	public record PublishedFrame(
			long frameId,
			float maxSpeed,
			Map<WindowKey, BrickRuntimeAtlasSnapshot> regionAtlases,
			Map<WindowKey, Float> regionMaxSpeeds
	) {
	}

	private record CoarseWindSyncState(
			ResourceKey<Level> worldKey,
			int originX,
			int originY,
			int originZ,
			int tick
	) {
		boolean sameRegion(CoarseWindSyncState other) {
			return other != null
					&& worldKey.equals(other.worldKey())
					&& originX == other.originX()
					&& originY == other.originY()
					&& originZ == other.originZ();
		}
	}

	private record LocalWeatherSyncState(
			ResourceKey<Level> worldKey,
			int centerCellX,
			int centerCellZ,
			long serverTick,
			int tick
	) {
		boolean sameSnapshot(LocalWeatherSyncState other) {
			return other != null
					&& worldKey.equals(other.worldKey())
					&& centerCellX == other.centerCellX()
					&& centerCellZ == other.centerCellZ()
					&& serverTick == other.serverTick();
		}
	}

	private record LocalWeatherServerState(
			ResourceKey<Level> worldKey,
			AeroLocalWeatherPacket packet,
			int runtimeTick
	) {
		private static final float MIN_GAMEPLAY_PRECIPITATION = 0.055f;

		boolean covers(BlockPos pos) {
			if (pos == null || packet == null || packet.getGridWidth() <= 0 || packet.getCellSizeBlocks() <= 0) {
				return false;
			}
			float gridX = gridCoordinate(pos.getX());
			float gridZ = gridCoordinate(pos.getZ());
			float min = -0.5f;
			float max = packet.getGridWidth() - 0.5f;
			return gridX >= min && gridX <= max && gridZ >= min && gridZ <= max;
		}

		float sample(int blockX, int blockZ, int channel) {
			if (packet == null || channel < 0 || channel >= AeroLocalWeatherPacket.CHANNEL_COUNT) {
				return 0.0f;
			}
			short[] packed = packet.getPackedWeather();
			int gridWidth = packet.getGridWidth();
			if (packed == null || gridWidth <= 0 || packed.length != gridWidth * gridWidth * AeroLocalWeatherPacket.CHANNEL_COUNT) {
				return 0.0f;
			}
			float gridX = gridCoordinate(blockX);
			float gridZ = gridCoordinate(blockZ);
			int x0 = Mth.floor(gridX);
			int z0 = Mth.floor(gridZ);
			float tx = gridX - x0;
			float tz = gridZ - z0;
			int x1 = x0 + 1;
			int z1 = z0 + 1;

			float v00 = channelAt(clampIndex(x0, gridWidth), clampIndex(z0, gridWidth), channel);
			float v10 = channelAt(clampIndex(x1, gridWidth), clampIndex(z0, gridWidth), channel);
			float v01 = channelAt(clampIndex(x0, gridWidth), clampIndex(z1, gridWidth), channel);
			float v11 = channelAt(clampIndex(x1, gridWidth), clampIndex(z1, gridWidth), channel);
			float vx0 = Mth.lerp(tx, v00, v10);
			float vx1 = Mth.lerp(tx, v01, v11);
			return Mth.clamp(Mth.lerp(tz, vx0, vx1), 0.0f, 1.0f);
		}

		private float gridCoordinate(int blockCoordinate) {
			return (float) (blockCoordinate / (double) packet.getCellSizeBlocks() - packet.getOriginCellX() - 0.5);
		}

		private float channelAt(int localX, int localZ, int channel) {
			int index = (localX * packet.getGridWidth() + localZ) * AeroLocalWeatherPacket.CHANNEL_COUNT + channel;
			short[] packed = packet.getPackedWeather();
			if (index < 0 || packed == null || index >= packed.length) {
				return 0.0f;
			}
			float signed = packed[index] / (float) Short.MAX_VALUE;
			return Mth.clamp(signed * 0.5f + 0.5f, 0.0f, 1.0f);
		}

		private int clampIndex(int index, int size) {
			return Mth.clamp(index, 0, Math.max(0, size - 1));
		}
	}

	private record FlowAtlasSyncState(
			ResourceKey<Level> worldKey,
			BlockPos origin,
			long frameId,
			int tick
	) {
		boolean sameAtlas(WindowKey key, BrickRuntimeAtlasSnapshot atlas) {
			return key != null
					&& atlas != null
					&& worldKey.equals(key.worldKey())
					&& origin.equals(atlas.origin());
		}
	}

	private record NestedFeedbackAxisSpan(int index, int localMin, int localMax) {
	}

	private record L2ToL1FeedbackLayoutBin(
			int cellX,
			int layer,
			int cellZ,
			int localMinX,
			int localMaxX,
			int localMinY,
			int localMaxY,
			int localMinZ,
			int localMaxZ
	) {
	}

	private record L2ToL1FeedbackLayout(
			int[] nativeLayout,
			List<L2ToL1FeedbackLayoutBin> bins
	) {
	}

	public record NestedFeedbackRuntimeDiagnostics(
			long polledPacketCount,
			long polledBinCount,
			int lastPacketBinCount,
			int lastPolledTick,
			float lastMeanVolumeAverage,
			float lastMeanBottomFluxDensity,
			float lastMeanTopFluxDensity
	) {
	}

	public record NativeNestedFeedbackWorldDiagnostics(
			int regionCount,
			int configuredBinCount,
			int stepsPerFeedback,
			int minStepsAccumulated,
			int maxStepsAccumulated,
			int readyRegionCount,
			long emittedPacketCount,
			long nativeResetCount,
			long backendResetCount,
			int lastBackendResetTick
	) {
	}

	@Getter
	public static final class RegionRecord {
		private final AtomicBoolean busy = new AtomicBoolean(false);
		private final AtomicBoolean released = new AtomicBoolean(false);
		private boolean serviceActive;
		private boolean serviceReady;
		private boolean attached;
		private boolean staticUploaded;
		private boolean dynamicRestoreAttempted;
		private boolean fullWindowSectionsRequested;
		private boolean fansDirty = true;
		private boolean forcingDirty = true;
		private boolean backendResetPending;
		private final long[] uploadedSectionVersions = new long[WINDOW_SECTION_VOLUME];
		private WorldMirror.SectionSnapshot[] sections;
		private long[] sectionVersions;
		private int lastThermalRefreshTick;
		private float completedMaxSpeed;
		private List<FanSource> fans = List.of();
		private L2ToL1FeedbackLayout nestedFeedbackLayout;
		private long nestedFeedbackLayoutServiceId;
		private long backendResetCount;
		private int lastBackendResetTick = Integer.MIN_VALUE;
		private NestedBoundaryCoupler.BoundarySample cachedBoundarySample;
		private BoundaryFieldData cachedBoundaryField;
		private int cachedBoundaryExternalFaceMask;
		private int lastBoundaryRefreshTick = Integer.MIN_VALUE;
		private long uploadedBrickStaticSignature = Long.MIN_VALUE;
		private long uploadedBrickStaticServiceId;
		private int lastBrickDynamicSyncTick = Integer.MIN_VALUE;

		private RegionRecord() {
			Arrays.fill(uploadedSectionVersions, Long.MIN_VALUE);
		}

		private boolean attached() {
			return attached;
		}

		private void attach() {
			attached = true;
			released.set(false);
		}

		private void detach() {
			attached = false;
			invalidateBrickDynamicSync();
		}

		private void markDetached() {
			attached = false;
			clearBoundaryCache();
			invalidateBrickDynamicSync();
		}

		private void markBackendResetPending() {
			backendResetPending = true;
		}

		private boolean backendResetPending() {
			return backendResetPending;
		}

		private void clearBackendResetPending() {
			backendResetPending = false;
		}

		private NestedBoundaryCoupler.BoundarySample cachedBoundarySample() {
			return cachedBoundarySample;
		}

		private BoundaryFieldData cachedBoundaryField() {
			return cachedBoundaryField;
		}

		private int cachedBoundaryExternalFaceMask() {
			return cachedBoundaryExternalFaceMask;
		}

		private int lastBoundaryRefreshTick() {
			return lastBoundaryRefreshTick;
		}

		private void updateBoundaryCache(
				NestedBoundaryCoupler.BoundarySample boundarySample,
				BoundaryFieldData boundaryField,
				int externalFaceMask,
				int tick
		) {
			cachedBoundarySample = boundarySample;
			cachedBoundaryField = boundaryField;
			cachedBoundaryExternalFaceMask = externalFaceMask;
			lastBoundaryRefreshTick = tick;
		}

		private void clearBoundaryCache() {
			cachedBoundarySample = null;
			cachedBoundaryField = null;
			cachedBoundaryExternalFaceMask = 0;
			lastBoundaryRefreshTick = Integer.MIN_VALUE;
		}

		private void noteBackendReset(int tick) {
			backendResetCount++;
			lastBackendResetTick = tick;
			invalidateBrickDynamicSync();
		}

		public long backendResetCount() {
			return backendResetCount;
		}

		public int lastBackendResetTick() {
			return lastBackendResetTick;
		}

		private boolean shouldSyncBrickDynamic(int tick) {
			return tick - lastBrickDynamicSyncTick >= BRICK_DYNAMIC_SYNC_INTERVAL_TICKS;
		}

		private void noteBrickDynamicSynced(int tick) {
			lastBrickDynamicSyncTick = tick;
		}

		private void invalidateBrickDynamicSync() {
			lastBrickDynamicSyncTick = Integer.MIN_VALUE;
		}

		private boolean markReleased() {
			return released.compareAndSet(false, true);
		}

		private void ensureSectionsInitialized() {
			if (sections != null) {
				return;
			}
			sections = new WorldMirror.SectionSnapshot[WINDOW_SECTION_VOLUME];
			sectionVersions = new long[WINDOW_SECTION_VOLUME];
			Arrays.fill(sectionVersions, Long.MIN_VALUE);
		}

		private WorldMirror.SectionSnapshot sectionAt(int sx, int sy, int sz) {
			if (sections == null) {
				return null;
			}
			return sections[((sx * WINDOW_SECTION_COUNT + sy) * WINDOW_SECTION_COUNT + sz)];
		}

		private long sectionVersionAt(int sx, int sy, int sz) {
			if (sectionVersions == null) {
				return Long.MIN_VALUE;
			}
			return sectionVersions[((sx * WINDOW_SECTION_COUNT + sy) * WINDOW_SECTION_COUNT + sz)];
		}

		private void setSection(int sx, int sy, int sz, WorldMirror.SectionSnapshot section) {
			ensureSectionsInitialized();
			int index = ((sx * WINDOW_SECTION_COUNT + sy) * WINDOW_SECTION_COUNT + sz);
			sections[index] = section;
			sectionVersions[index] = section == null ? Long.MIN_VALUE : section.version();
		}
	}

	@Getter
	public static final class InspectionSolveSession {
		private final InspectionPatchInput input;
		private final int totalSteps;
		private final Path outputDir;
		private final AtomicBoolean stopRequested = new AtomicBoolean(false);
		private final AtomicBoolean completed = new AtomicBoolean(false);
		private final AtomicInteger completedSteps = new AtomicInteger(0);
		private final AtomicReference<String> phase = new AtomicReference<>("queued");
		private final AtomicReference<String> lastError = new AtomicReference<>("");
		private final AtomicReference<Float> maxSpeedMetersPerSecond = new AtomicReference<>(0.0f);

		public InspectionSolveSession(InspectionPatchInput input, int totalSteps) {
			this.input = input;
			this.totalSteps = totalSteps;
			this.outputDir = input.outputDir();
		}
	}

	public final class SimulationCoordinator implements Runnable {
		private final AtomicBoolean running = new AtomicBoolean(true);
		private final Thread thread = new Thread(this, "aero-sim-coordinator");
		private int lastActiveRegionBatchTick = Integer.MIN_VALUE;
		private int lastBudgetTick = Integer.MIN_VALUE;
		private int lastSynchronizedTick = Integer.MIN_VALUE;
		private int lastBackgroundRefreshTick = Integer.MIN_VALUE;

		private SimulationCoordinator() {
			thread.setDaemon(true);
		}

		private void start() {
			thread.start();
		}

		public boolean running() {
			return running.get() && thread.isAlive();
		}

		private void shutdown() {
			running.set(false);
			thread.interrupt();
			try {
				thread.join();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public void run() {
			try {
				while (running.get()) {
					if (!streamingEnabled) {
						lastCoordinatorState = "streamingDisabled";
						publishedFrame.set(null);
						sleepQuietly(20L);
						continue;
					}

					int observedTick = tickCounter;
					lastCoordinatorObservedTick = observedTick;
					if (pendingWorldDeltaCount > 0) {
						lastCoordinatorState = "worldDeltaFlush";
						flushPendingWorldDeltas(WORLD_DELTA_FLUSH_MAX_BATCHES_PER_CYCLE);
					} else {
						lastWorldDeltaFlushCount = 0;
					}
					if (observedTick != lastSynchronizedTick) {
						grantStepBudgetForObservedTicks();
						synchronized (simulationStateLock) {
							applyPendingActiveRegionBatchIfNeeded();
						}
						applyPendingBackgroundRefreshIfNeeded();
						synchronized (simulationStateLock) {
							if (pendingResidentBrickStaticRefreshCount > 0) {
								lastCoordinatorState = "residentStaticRefresh";
								applyPendingResidentBrickStaticRefreshesIfNeeded(RESIDENT_BRICK_STATIC_REFRESH_BUDGET_PER_CYCLE);
							} else {
								lastResidentBrickStaticRefreshCount = 0;
							}
							refreshDesiredWindowsFromBrickRuntime();
						}
						runMesoscaleStepCycle();
						synchronized (simulationStateLock) {
							synchronizeActiveWindowsFromMirror();
						}
						lastSynchronizedTick = observedTick;
					}

					Set<WindowKey> residentWindowKeys;
					Set<WindowKey> solveWindowKeys;
					synchronized (simulationStateLock) {
						residentWindowKeys = Set.copyOf(desiredWindowKeys);
						solveWindowKeys = Set.copyOf(desiredSolveWindowKeys);
					}
					lastCoordinatorActiveWindowCount = residentWindowKeys.size();
					lastCoordinatorSolveWindowCount = solveWindowKeys.size();
					if (residentWindowKeys.isEmpty()) {
						lastCoordinatorState = "noActiveWindows";
						lastCoordinatorNoPublishReason = "noActiveWindows";
						lastCoordinatorBusyWindowCount = 0;
						sleepQuietly(10L);
						continue;
					}

					lastCoordinatorBusyWindowCount = 0;

					if (solveWindowKeys.isEmpty()) {
						lastCoordinatorState = "noSolveWindows";
						lastCoordinatorNoPublishReason = "noSolveWindows";
						sleepQuietly(5L);
						continue;
					}

					synchronized (simulationStateLock) {
						queueCoherentL2CaptureIfNeeded(observedTick);
					}

					if (!acquireSimulationStepBudget()) {
						lastCoordinatorState = "waitingTick";
						lastCoordinatorNoPublishReason = "";
						sleepQuietly(5L);
						continue;
					}

					lastCoordinatorScheduledWindowCount = solveWindowKeys.size();
					lastCoordinatorState = "brickEpoch";
					long waitStartNanos = System.nanoTime();
					synchronized (simulationStateLock) {
						stepBrickRuntimeWorlds();
					}
					lastCoordinatorWaitNanos = System.nanoTime() - waitStartNanos;
					if (!running.get()) {
						return;
					}

					lastCoordinatorState = "postSolve";
					long postSolveStartNanos = System.nanoTime();
					synchronized (simulationStateLock) {
						lastCoordinatorState = "sampleProbes";
						Map<UUID, PlayerProbe> nextPlayerProbes = samplePlayerProbesLocked();
						if (!nextPlayerProbes.isEmpty() || activePlayerProbeRequests.isEmpty()) {
							publishedPlayerProbes.set(nextPlayerProbes);
						}
						Map<UUID, EntitySample> nextEntitySamples = sampleEntitySamplesLocked();
						if (!nextEntitySamples.isEmpty() || activeEntitySampleRequests.isEmpty()) {
							publishedEntitySamples.set(nextEntitySamples);
						}
						lastCoordinatorState = "publishBrickAtlases";
						float maxSpeedThisCycle = publishBrickSolveAtlases(solveWindowKeys);
						lastCoordinatorAppliedMaxSpeed = maxSpeedThisCycle;
						lastMaxFlowSpeed = maxSpeedThisCycle;
						lastCoordinatorSolveCompleteTick = tickCounter;
						lastCoordinatorState = "published";
						lastCoordinatorNoPublishReason = "";
						PublishedFrame currentFrame = publishedFrame.get();
						if (currentFrame != null) {
							lastCoordinatorPublishedMaxSpeed = currentFrame.maxSpeed();
						} else {
							lastCoordinatorPublishedMaxSpeed = 0.0f;
						}
					}
					lastCoordinatorPostSolveNanos = System.nanoTime() - postSolveStartNanos;
				}
			} catch (Throwable ex) {
				running.set(false);
				lastCoordinatorError = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage());
				log("Simulation coordinator crashed: " + ex);
			} finally {
				running.set(false);
			}
		}

		private boolean acquireSimulationStepBudget() {
			while (true) {
				int current = simulationStepBudget.get();
				if (current <= 0) {
					return false;
				}
				if (simulationStepBudget.compareAndSet(current, current - 1)) {
					return true;
				}
			}
		}

		private void grantStepBudgetForObservedTicks() {
			int observedTick = tickCounter;
			if (observedTick <= lastBudgetTick) {
				return;
			}
			int delta = observedTick - Math.max(lastBudgetTick, 0);
			lastBudgetTick = observedTick;
			for (int i = 0; i < delta; i++) {
				grantSimulationStepBudget();
			}
		}

		private void sleepQuietly(long millis) {
			try {
				Thread.sleep(millis);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

		private void applyPendingBackgroundRefreshIfNeeded() {
			BackgroundRefreshBatch batch = pendingBackgroundRefreshBatch;
			if (batch == null || batch.tickCounter() <= lastBackgroundRefreshTick) {
				return;
			}
			applyBackgroundRefreshBatch(batch);
			lastBackgroundRefreshTick = batch.tickCounter();
		}

		private void applyPendingActiveRegionBatchIfNeeded() {
			ActiveRegionBatch batch = pendingActiveRegionBatch;
			if (batch == null || batch.tickCounter() <= lastActiveRegionBatchTick) {
				return;
			}
			anchorDesiredWindowKeys = Set.copyOf(activeRegionKeys(batch.anchors()));
			anchorSolveWindowKeys = Set.copyOf(solveRegionKeys(batch.anchors()));
			refreshDesiredWindowsFromBrickRuntime();
			syncBrickRuntimeHints(brickRuntimeHintCoords(desiredSolveWindowKeys));
			activePlayerProbeRequests = batch.playerProbeRequests();
			activeEntitySampleRequests = batch.entitySampleRequests();
			worldEnvironmentSnapshots = batch.environmentSnapshots();
			lastActiveRegionBatchTick = batch.tickCounter();
		}

	}
}
