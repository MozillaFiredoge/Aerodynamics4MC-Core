package com.aerodynamics4mc.client;

import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.AeroWindSamplingRules;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftVectors;
import com.aerodynamics4mc.network.packet.AeroCoarseWindPacket;
import com.aerodynamics4mc.runtime.AeroBlockBehaviors;
import com.aerodynamics4mc.runtime.AerodynamicSolver;
import com.aerodynamics4mc.runtime.NativeSimulationBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ClientL2Solver {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerodynamics4mc/ClientL2Solver");

    private enum SolverMode {
        OFF(
            "off",
            false,
            false,
            32,
            NativeSimulationBridge.REALTIME_SOLVER_CLASSIC_D3Q27
        ),
        V0_1(
            "v0.1-classic-d3q27",
            true,
            false,
            32,
            NativeSimulationBridge.REALTIME_SOLVER_CLASSIC_D3Q27
        ),
        COMPACT(
            "compact-experimental",
            true,
            true,
            128,
            NativeSimulationBridge.REALTIME_SOLVER_COMPACT_EXPERIMENTAL
        ),
        FP16_INPLACE(
            "d3q27-fp16-inplace-experimental",
            true,
            true,
            128,
            NativeSimulationBridge.REALTIME_SOLVER_D3Q27_FP16_INPLACE_EXPERIMENTAL
        );

        private final String statusName;
        private final boolean enabledByDefault;
        private final boolean experimental;
        private final int defaultBrickSize;
        private final int nativeSolverMode;

        SolverMode(
            String statusName,
            boolean enabledByDefault,
            boolean experimental,
            int defaultBrickSize,
            int nativeSolverMode
        ) {
            this.statusName = statusName;
            this.enabledByDefault = enabledByDefault;
            this.experimental = experimental;
            this.defaultBrickSize = defaultBrickSize;
            this.nativeSolverMode = nativeSolverMode;
        }

        static SolverMode parse(String value) {
            if (value == null || value.isBlank()) {
                return V0_1;
            }
            String normalized = value.trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_')
                .replace('.', '_')
                .replace(' ', '_');
            return switch (normalized) {
                case "off", "none", "disable", "disabled" -> OFF;
                case "default", "v0", "v01", "v0_1", "classic", "classic_d3q27",
                    "cumulant", "cumulant_d3q27", "d3q27", "32", "32_3" -> V0_1;
                case "compact", "compact_path", "compact_experimental", "compact_realtime", "compact_realtime_fp16" -> COMPACT;
                case "fp16", "fp16_inplace", "fp16_inplace_d3q27", "d3q27_fp16", "d3q27_fp16_inplace",
                    "d3q27_fp16_inplace_srt", "d3q27_fp16_inplace_experimental" -> FP16_INPLACE;
                default -> throw new IllegalArgumentException("Unknown Client L2 solver mode: " + value);
            };
        }

        String statusName() {
            return statusName;
        }

        boolean enabledByDefault() {
            return enabledByDefault;
        }

        boolean experimental() {
            return experimental;
        }

        int defaultBrickSize() {
            return defaultBrickSize;
        }

        int nativeSolverMode() {
            return nativeSolverMode;
        }
    }

    private static final SolverMode CLIENT_L2_MODE = configuredSolverMode();
    private static final int BRICK_SIZE = configuredBrickSize();
    private static final int CELL_COUNT = BRICK_SIZE * BRICK_SIZE * BRICK_SIZE;
    private static final int FLOW_CHANNELS = NativeSimulationBridge.FLOW_STATE_CHANNELS;
    private static final int PACKED_CHANNELS = NativeSimulationBridge.PACKED_ATLAS_CHANNELS;
    private static final int FACE_COUNT = Direction.values().length;
    private static final int STATIC_REFRESH_TICKS = -1;
    private static final int LOCAL_PUBLISH_INTERVAL_TICKS = configuredInt(
            "a4mc.clientL2.publishIntervalTicks",
            "AERO_LBM_CLIENT_L2_PUBLISH_INTERVAL_TICKS",
            1,
            1,
            200
    );
    private static final int LOCAL_PUBLISH_SAMPLE_STRIDE = configuredInt(
            "a4mc.clientL2.publishSampleStride",
            "AERO_LBM_CLIENT_L2_PUBLISH_SAMPLE_STRIDE",
            BRICK_SIZE >= 128 ? 4 : 1,
            1,
            16
    );
    private static final int SOLVE_INTERVAL_TICKS = configuredInt(
            "a4mc.clientL2.solveIntervalTicks",
            "AERO_LBM_CLIENT_L2_SOLVE_INTERVAL_TICKS",
            1,
            1,
            200
    );
    private static final int BOUNDARY_REFERENCE_REFRESH_MIN_TICKS = 40;
    private static final int FAST_SUSPEND_COOLDOWN_TICKS = 10;
    private static final int STATIC_BUILD_CELLS_PER_TICK = configuredInt(
        "a4mc.clientL2.staticBuildCellsPerTick",
        "AERO_LBM_CLIENT_L2_STATIC_BUILD_CELLS_PER_TICK",
        BRICK_SIZE >= 128 ? 8192 : 1024,
        1,
        CELL_COUNT
    );
    private static final int COARSE_SEED_CELLS_PER_TICK = configuredInt(
        "a4mc.clientL2.coarseSeedCellsPerTick",
        "AERO_LBM_CLIENT_L2_COARSE_SEED_CELLS_PER_TICK",
        BRICK_SIZE >= 128 ? 65536 : 4096,
        1,
        CELL_COUNT
    );
    private static final int BOUNDARY_REFERENCE_CELLS_PER_TICK = configuredInt(
            "a4mc.clientL2.boundaryReferenceCellsPerTick",
            "AERO_LBM_CLIENT_L2_BOUNDARY_REFERENCE_CELLS_PER_TICK",
            BRICK_SIZE >= 128 ? 16384 : 4096,
            1,
            CELL_COUNT
    );
    private static final long STATIC_BUILD_NANOS_PER_TICK = configuredInt(
        "a4mc.clientL2.staticBuildMicrosPerTick",
        "AERO_LBM_CLIENT_L2_STATIC_BUILD_MICROS_PER_TICK",
        BRICK_SIZE >= 128 ? 1500 : 1000,
        100,
        50000
    ) * 1000L;
    private static final long COARSE_SEED_NANOS_PER_TICK = configuredInt(
        "a4mc.clientL2.coarseSeedMicrosPerTick",
        "AERO_LBM_CLIENT_L2_COARSE_SEED_MICROS_PER_TICK",
        BRICK_SIZE >= 128 ? 1000 : 1000,
        100,
        50000
    ) * 1000L;
    private static final long BOUNDARY_REFERENCE_NANOS_PER_TICK = configuredInt(
        "a4mc.clientL2.boundaryReferenceMicrosPerTick",
        "AERO_LBM_CLIENT_L2_BOUNDARY_REFERENCE_MICROS_PER_TICK",
        BRICK_SIZE >= 128 ? 1000 : 1000,
        100,
        50000
    ) * 1000L;
    private static final int STRESS_PATCHES_PER_TICK = configuredInt(
            "a4mc.clientL2.stressPatchesPerTick",
            "AERO_LBM_CLIENT_L2_STRESS_PATCHES_PER_TICK",
            BRICK_SIZE >= 128 ? 512 : 64,
            1,
            CELL_COUNT
    );
    private static final int STRESS_INTERVAL_TICKS = configuredInt(
            "a4mc.clientL2.stressIntervalTicks",
            "AERO_LBM_CLIENT_L2_STRESS_INTERVAL_TICKS",
            1,
            1,
            200
    );
    private static final int STATIC_PATCH_DEBOUNCE_TICKS = configuredInt(
            "a4mc.clientL2.staticPatchDebounceTicks",
            "AERO_LBM_CLIENT_L2_STATIC_PATCH_DEBOUNCE_TICKS",
            0,
            0,
            20
    );
    private static final int BOUNDARY_REFRESH_AFTER_STATIC_PATCH_COOLDOWN_TICKS = configuredInt(
            "a4mc.clientL2.boundaryRefreshAfterStaticPatchCooldownTicks",
            "AERO_LBM_CLIENT_L2_BOUNDARY_REFRESH_AFTER_STATIC_PATCH_COOLDOWN_TICKS",
            20,
            0,
            200
    );
    private static final int STATIC_PATCH_MAX_DEBOUNCE_TICKS = configuredInt(
            "a4mc.clientL2.staticPatchMaxDebounceTicks",
            "AERO_LBM_CLIENT_L2_STATIC_PATCH_MAX_DEBOUNCE_TICKS",
            8,
            1,
            80
    );
    private static final int STATIC_PATCH_BULK_CHANGE_THRESHOLD = configuredInt(
            "a4mc.clientL2.staticPatchBulkChangeThreshold",
            "AERO_LBM_CLIENT_L2_STATIC_PATCH_BULK_CHANGE_THRESHOLD",
            8,
            1,
            CELL_COUNT
    );
    private static final int STATIC_PATCH_BULK_CELL_THRESHOLD = configuredInt(
            "a4mc.clientL2.staticPatchBulkCellThreshold",
            "AERO_LBM_CLIENT_L2_STATIC_PATCH_BULK_CELL_THRESHOLD",
            256,
            1,
            CELL_COUNT
    );
    private static final int STATIC_CACHE_MAX_BRICKS = configuredInt(
            "a4mc.clientL2.staticCacheMaxBricks",
            "AERO_LBM_CLIENT_L2_STATIC_CACHE_MAX_BRICKS",
            BRICK_SIZE >= 128 ? 2 : 32,
            0,
            64
    );
    private static final int COUPLING_BAND_CELLS = 8;
    private static final int MAX_CLIENT_ACTIVE_BRICKS = configuredInt(
            "a4mc.clientL2.maxActiveBricks",
            "AERO_LBM_CLIENT_L2_MAX_ACTIVE_BRICKS",
            BRICK_SIZE >= 128 ? 1 : 2,
            1,
            8
    );
    private static final int MAX_STEPS_PER_CLIENT_TICK = configuredInt(
            "a4mc.clientL2.maxStepsPerClientTick",
            "AERO_LBM_CLIENT_L2_MAX_STEPS_PER_CLIENT_TICK",
            1,
            1,
            16
    );
    private static final float DT_SECONDS = 0.05f;
    private static final float DX_METERS = 1.0f;
    private static final float ATLAS_VELOCITY_RANGE = 5.6f;
    private static final float ATLAS_PRESSURE_RANGE = 0.03f;
    private static final int Q_ISO_MAX_POINTS = configuredInt(
            "a4mc.clientL2.qIsoMaxPoints",
            "AERO_LBM_CLIENT_L2_Q_ISO_MAX_POINTS",
            4096,
            128,
            65536
    );
    private static final int Q_ISO_MAX_TRIANGLES = configuredInt(
            "a4mc.clientL2.qIsoMaxTriangles",
            "AERO_LBM_CLIENT_L2_Q_ISO_MAX_TRIANGLES",
            8192,
            256,
            262144
    );
    private static final float Q_ISO_AUTO_THRESHOLD_FRACTION = configuredFloat(
            "a4mc.clientL2.qIsoAutoThresholdFraction",
            "AERO_LBM_CLIENT_L2_Q_ISO_AUTO_THRESHOLD_FRACTION",
            0.22f,
            0.01f,
            1.0f
    );
    private static final float Q_ISO_FIXED_THRESHOLD = configuredFloat(
            "a4mc.clientL2.qIsoThreshold",
            "AERO_LBM_CLIENT_L2_Q_ISO_THRESHOLD",
            0.0f,
            0.0f,
            1.0e6f
    );
    private static final float Q_ISO_SCALE = configuredFloat(
            "a4mc.clientL2.qIsoScale",
            "AERO_LBM_CLIENT_L2_Q_ISO_SCALE",
            1.0e6f,
            1.0f,
            1.0e9f
    );
    private static final int Q_ISO_SOLID_REJECT_MAX_CELLS = 8;
    private static final int Q_ISO_SOLID_REJECT_DEFAULT_CELLS = configuredInt(
            "a4mc.clientL2.qIsoSolidRejectCells",
            "AERO_LBM_CLIENT_L2_Q_ISO_SOLID_REJECT_CELLS",
            0,
            0,
            Q_ISO_SOLID_REJECT_MAX_CELLS
    );
    static final int DEBUG_Q_SOLVE_DEFAULT_SIZE = 16;
    static final int DEBUG_Q_SOLVE_MIN_SIZE = 4;
    static final int DEBUG_Q_SOLVE_MAX_SIZE = 64;
    static final int DEBUG_Q_SOLVE_DEFAULT_STEPS = 32;
    static final int DEBUG_Q_SOLVE_MAX_STEPS = 256;
    static final int DEBUG_Q_LIVE_DEFAULT_STEPS_PER_FRAME = 2;
    static final int DEBUG_Q_LIVE_MAX_STEPS_PER_FRAME = 32;
    static final int DEBUG_Q_LIVE_DEFAULT_INTERVAL_TICKS = 4;
    static final int DEBUG_Q_LIVE_MAX_INTERVAL_TICKS = 100;
    static final int DEBUG_Q_FINE_DEFAULT_GRID_SIZE = 32;
    static final int DEBUG_Q_FINE_MIN_GRID_SIZE = 8;
    static final int DEBUG_Q_FINE_MAX_GRID_SIZE = 64;
    static final int DEBUG_Q_FINE_DEFAULT_SIZE_BLOCKS = 8;
    static final int DEBUG_Q_FINE_MIN_SIZE_BLOCKS = 2;
    static final int DEBUG_Q_FINE_MAX_SIZE_BLOCKS = 32;
    private static final int DEBUG_Q_MASK_MAX_RENDER_CELLS = 4096;
    private static final int DEBUG_Q_SOLVE_SAMPLE_STRIDE = 1;
    private static final float DEBUG_Q_SOLVE_INLET_X = 2.0f;
    private static final float DEBUG_Q_SOLVE_INLET_Y = 0.0f;
    private static final float DEBUG_Q_SOLVE_INLET_Z = 0.0f;
    static final float DEBUG_Q_INLET_NOISE_MAX_AMPLITUDE = 2.0f;
    private static final float DEBUG_Q_INLET_NOISE_DEFAULT_AMPLITUDE = configuredFloat(
            "a4mc.clientL2.qInletNoiseAmplitude",
            "AERO_LBM_CLIENT_L2_Q_INLET_NOISE_AMPLITUDE",
            0.0f,
            0.0f,
            DEBUG_Q_INLET_NOISE_MAX_AMPLITUDE
    );
    private static final int[] Q_ISO_CUBE_VERTEX_OFFSETS = {
            0, 0, 0,
            1, 0, 0,
            1, 1, 0,
            0, 1, 0,
            0, 0, 1,
            1, 0, 1,
            1, 1, 1,
            0, 1, 1
    };
    private static final int[] Q_ISO_TETRAHEDRA = {
            0, 5, 1, 6,
            0, 1, 2, 6,
            0, 2, 3, 6,
            0, 3, 7, 6,
            0, 7, 4, 6,
            0, 4, 5, 6
    };
    private static final float ZERO_DYNAMIC_MAX_SPEED_EPS_MPS = 0.02f;
    private static final float COARSE_RESEED_MIN_SPEED_MPS = 0.05f;
    private static final float FAST_RESUME_HORIZONTAL_SPEED_MPS = 6.0f;
    private static final float HEAT_COUPLING_TO_ADJACENT_AIR = 0.85f;
    private static final float THERMAL_EMITTER_POWER_LAVA_W = 3200.0f;
    private static final float THERMAL_EMITTER_POWER_MAGMA_W = 1200.0f;
    private static final float THERMAL_EMITTER_POWER_CAMPFIRE_W = 1800.0f;
    private static final float THERMAL_EMITTER_POWER_SOUL_CAMPFIRE_W = 1200.0f;
    private static final float THERMAL_EMITTER_POWER_FIRE_W = 2200.0f;
    private static final float THERMAL_EMITTER_POWER_SOUL_FIRE_W = 1500.0f;
    private static final float THERMAL_EMITTER_POWER_TORCH_W = 80.0f;
    private static final float THERMAL_EMITTER_POWER_SOUL_TORCH_W = 50.0f;
    private static final float THERMAL_EMITTER_POWER_LANTERN_W = 60.0f;
    private static final float THERMAL_EMITTER_POWER_SOUL_LANTERN_W = 40.0f;
    private static final int FAN_FORCE_LENGTH_CELLS = 5;
    private static final int FAN_FORCE_RADIUS_CELLS = 1;
    private static final int HEAT_PLUME_HEIGHT_CELLS = 4;
    private static final byte SURFACE_KIND_FAN_X_NEG = 32;
    private static final byte SURFACE_KIND_FAN_X_POS = 33;
    private static final byte SURFACE_KIND_FAN_Y_NEG = 34;
    private static final byte SURFACE_KIND_FAN_Y_POS = 35;
    private static final byte SURFACE_KIND_FAN_Z_NEG = 36;
    private static final byte SURFACE_KIND_FAN_Z_POS = 37;
    private static final int WORKER_QUEUE_CAPACITY = 128;
    private static final int STRESS_QUEUE_BACKLOG_LIMIT = configuredInt(
            "a4mc.clientL2.stressQueueBacklogLimit",
            "AERO_LBM_CLIENT_L2_STRESS_QUEUE_BACKLOG_LIMIT",
            16,
            0,
            WORKER_QUEUE_CAPACITY
    );
    private static final int ALL_OPEN_FACE_MASK = (1 << FACE_COUNT) - 1;
    private static final boolean CLIENT_L2_DEFAULT_ENABLED = CLIENT_L2_MODE != SolverMode.OFF && configuredBoolean(
        "a4mc.clientL2.enabled",
        "AERO_LBM_CLIENT_L2_ENABLED",
        CLIENT_L2_MODE.enabledByDefault()
    );

    private enum StressMode {
        OFF,
        FAN,
        THERMAL,
        DIRTY,
        MIXED;

        static StressMode parse(String value) {
            if (value == null || value.isBlank()) {
                return OFF;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "fan", "fans" -> FAN;
                case "thermal", "heat" -> THERMAL;
                case "dirty", "obstacle", "geometry" -> DIRTY;
                case "mixed", "all" -> MIXED;
                case "off", "disable", "disabled" -> OFF;
                default -> throw new IllegalArgumentException("Unknown Client L2 stress mode: " + value);
            };
        }
    }

    private enum StaticPatchFlushResult {
        NONE,
        DELAYED,
        SUBMITTED
    }

    private enum QIsoDisplayMode {
        SURFACE("surface"),
        VOXELS("voxels");

        private final String statusName;

        QIsoDisplayMode(String statusName) {
            this.statusName = statusName;
        }
    }

    private final AeroVisualizer visualizer;
    private final ClientL2Worker worker = new ClientL2Worker();
    private final byte[] obstacle = new byte[CELL_COUNT];
    private final byte[] surfaceKind = new byte[CELL_COUNT];
    private final short[] openFaceMask = new short[CELL_COUNT];
    private final float[] emitterPower = new float[CELL_COUNT];
    private final byte[] sourceFanDirection = new byte[CELL_COUNT];
    private final float[] sourceEmitterPower = new float[CELL_COUNT];
    private final byte[] faceSkyExposure = new byte[CELL_COUNT * FACE_COUNT];
    private final byte[] faceDirectExposure = new byte[CELL_COUNT * FACE_COUNT];
    private final float[] flowState = new float[CELL_COUNT * FLOW_CHANNELS];
    private final float[] airTemperature = new float[CELL_COUNT];
    private final float[] surfaceTemperature = new float[CELL_COUNT];
    private final BlockPos.MutableBlockPos staticCursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos staticNeighbor = new BlockPos.MutableBlockPos();
    private final LinkedHashMap<StaticBrickCacheKey, StaticBrickSnapshot> staticBrickCache =
            new LinkedHashMap<>(STATIC_CACHE_MAX_BRICKS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<StaticBrickCacheKey, StaticBrickSnapshot> eldest) {
                    return size() > STATIC_CACHE_MAX_BRICKS;
                }
            };
    private final int[] activeBrickX = new int[MAX_CLIENT_ACTIVE_BRICKS];
    private final int[] activeBrickY = new int[MAX_CLIENT_ACTIVE_BRICKS];
    private final int[] activeBrickZ = new int[MAX_CLIENT_ACTIVE_BRICKS];
    private final boolean[] activeBrickReady = new boolean[MAX_CLIENT_ACTIVE_BRICKS];
    private final boolean[] activeBrickRefreshPending = new boolean[MAX_CLIENT_ACTIVE_BRICKS];
    private final boolean[] activeBrickBoundaryRefreshPending = new boolean[MAX_CLIENT_ACTIVE_BRICKS];
    private int[] activeHintCoords = new int[NativeSimulationBridge.BRICK_HINT_COORDS_PER_BRICK];
    private final LinkedHashMap<BlockPos, PendingSourcePatch> pendingSourcePatches = new LinkedHashMap<>();
    private Identifier pendingStaticPatchDimension;
    private long pendingStaticPatchWorldKey;
    private long pendingStaticPatchFirstChangeGameTime = Long.MIN_VALUE;
    private long pendingStaticPatchLastChangeGameTime = Long.MIN_VALUE;
    private int pendingStaticPatchSourceChanges;

    private long worldKey;
    private BlockPos activeOrigin;
    private Identifier activeDimension;
    private boolean streamingEnabled;
    private boolean experimentalEnabled = CLIENT_L2_DEFAULT_ENABLED;
    private boolean activeHintUploaded;
    private boolean clientSolveDisabled;
    private int activeBrickCount;
    private int prepareCursor;
    private int refreshCursor;
    private int publishCursor;
    private int stagedActiveIndex = -1;
    private int stagedBrickX;
    private int stagedBrickY;
    private int stagedBrickZ;
    private int stagedStaticCursor;
    private int stagedSeedCursor;
    private boolean stagedStaticUploaded;
    private boolean stagedDynamicUploaded;
    private boolean stagedStaticFromCache;
    private boolean stagedCoarseSeedReady;
    private float stagedSeedVx;
    private float stagedSeedVy;
    private float stagedSeedVz;
    private float stagedSeedPressure;
    private BlockPos stagedOrigin;
    private Identifier stagedDimension;
    private int boundaryRefreshActiveIndex = -1;
    private int boundaryRefreshBrickX;
    private int boundaryRefreshBrickY;
    private int boundaryRefreshBrickZ;
    private int boundaryRefreshCursor;
    private float boundaryRefreshMaxCoarseSpeed;
    private BlockPos boundaryRefreshOrigin;
    private Identifier boundaryRefreshDimension;
    private int ticksSinceStaticRefresh = STATIC_REFRESH_TICKS;
    private long lastServerTick = Long.MIN_VALUE;
    private long lastProcessedClientGameTime = Long.MIN_VALUE;
    private long lastSolveClientGameTime = Long.MIN_VALUE;
    private long lastPublishedClientGameTime = Long.MIN_VALUE;
    private long lastBoundaryRefreshClientGameTime = Long.MIN_VALUE;
    private long lastStaticPatchSubmitClientGameTime = Long.MIN_VALUE;
    private long fastSuspendUntilGameTime = Long.MIN_VALUE;
    private long lastDiagnosticGameTime = Long.MIN_VALUE;
    private int lastStaticPatchCount;
    private int lastFanPatchCellCount;
    private int lastHeatPatchCellCount;
    private StressMode stressMode = StressMode.OFF;
    private long stressStartedGameTime = Long.MIN_VALUE;
    private long lastStressSubmitGameTime = Long.MIN_VALUE;
    private long stressSubmittedTicks;
    private long stressSubmittedPatches;
    private long stressSubmittedFanCells;
    private long stressSubmittedHeatCells;
    private long stressSubmittedDirtyCells;
    private boolean stressStaticSubmittedForActiveSet;
    private DebugQIsoLiveRequest debugQIsoLiveRequest;
    private long debugQIsoLiveLastSubmitGameTime = Long.MIN_VALUE;
    private boolean debugQIsoLiveStepPending;
    private float debugQIsoLastThreshold;
    private float debugQIsoLastMaxQ;
    private int debugQIsoLastPositiveSamples;
    private int debugQIsoLastPointCount;
    private int debugQIsoLastTriangleCount;
    private volatile float qIsoFixedThreshold = Q_ISO_FIXED_THRESHOLD;
    private volatile float qIsoAutoThresholdFraction = Q_ISO_AUTO_THRESHOLD_FRACTION;
    private volatile int qIsoSolidRejectCells = Q_ISO_SOLID_REJECT_DEFAULT_CELLS;
    private volatile QIsoDisplayMode qIsoDisplayMode = QIsoDisplayMode.SURFACE;
    private volatile float debugQInletNoiseAmplitude = DEBUG_Q_INLET_NOISE_DEFAULT_AMPLITUDE;
    private float debugQIsoLastInletVx = DEBUG_Q_SOLVE_INLET_X;
    private float debugQIsoLastInletVy = DEBUG_Q_SOLVE_INLET_Y;
    private float debugQIsoLastInletVz = DEBUG_Q_SOLVE_INLET_Z;

    ClientL2Solver(AeroVisualizer visualizer) {
        this.visualizer = visualizer;
    }

    private static SolverMode configuredSolverMode() {
        String value = System.getProperty("a4mc.clientL2.mode");
        if (value == null || value.isBlank()) {
            value = System.getenv("AERO_LBM_CLIENT_L2_MODE");
        }
        try {
            return SolverMode.parse(value);
        } catch (IllegalArgumentException error) {
            LOGGER.warn("Client L2 config a4mc.clientL2.mode={} is invalid; using v0_1", value);
            return SolverMode.V0_1;
        }
    }

    private static int configuredBrickSize() {
        int requested = configuredInt(
            "a4mc.clientL2.brickSize",
            "AERO_LBM_CLIENT_L2_BRICK_SIZE",
            CLIENT_L2_MODE.defaultBrickSize(),
            16,
            128
        );
        int aligned = Math.max(16, Math.min(128, (requested / 16) * 16));
        if (aligned != requested) {
            LOGGER.warn("Client L2 brick size {} is not chunk-aligned; using {}", requested, aligned);
        }
        return aligned;
    }

    private static int configuredInt(String propertyName, String envName, int defaultValue, int min, int max) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        if (value == null || value.isBlank()) {
            return Mth.clamp(defaultValue, min, max);
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            int clamped = Mth.clamp(parsed, min, max);
            if (clamped != parsed) {
                LOGGER.warn(
                        "Client L2 config {}={} outside [{}, {}]; using {}",
                        propertyName,
                        parsed,
                        min,
                        max,
                        clamped
                );
            }
            return clamped;
        } catch (NumberFormatException ignored) {
            LOGGER.warn("Client L2 config {}={} is not an integer; using {}", propertyName, value, defaultValue);
            return Mth.clamp(defaultValue, min, max);
        }
    }

    private static float configuredFloat(String propertyName, String envName, float defaultValue, float min, float max) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        if (value == null || value.isBlank()) {
            return Mth.clamp(defaultValue, min, max);
        }
        try {
            float parsed = Float.parseFloat(value.trim());
            float clamped = Mth.clamp(parsed, min, max);
            if (clamped != parsed) {
                LOGGER.warn(
                        "Client L2 config {}={} outside [{}, {}]; using {}",
                        propertyName,
                        parsed,
                        min,
                        max,
                        clamped
                );
            }
            return clamped;
        } catch (NumberFormatException ignored) {
            LOGGER.warn("Client L2 config {}={} is not a float; using {}", propertyName, value, defaultValue);
            return Mth.clamp(defaultValue, min, max);
        }
    }

    private static boolean configuredBoolean(String propertyName, String envName, boolean defaultValue) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on", "enable", "enabled" -> true;
            case "0", "false", "no", "off", "disable", "disabled" -> false;
            default -> {
                LOGGER.warn("Client L2 config {}={} is not a boolean; using {}", propertyName, value, defaultValue);
                yield defaultValue;
            }
        };
    }

    void initialize() {
        LOGGER.info(
            "Client L2 mode={} enabledByDefault={} brickSize={} experimental={}",
            CLIENT_L2_MODE.statusName(),
            CLIENT_L2_DEFAULT_ENABLED,
            BRICK_SIZE,
            CLIENT_L2_MODE.experimental()
        );
    }

    void onRuntimeState(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
        if (!streamingEnabled || !experimentalEnabled) {
            resetActiveBrick();
            worker.reset();
        }
    }

    void onCoarseWindField(AeroCoarseWindPacket packet) {
        if (!streamingEnabled || !experimentalEnabled || packet == null) {
            return;
        }
        long serverTick = packet.getServerTick();
        if (serverTick < 0L) {
            return;
        }
        if (lastServerTick == Long.MIN_VALUE || serverTick != lastServerTick) {
            markBoundaryRefreshPending();
        }
        lastServerTick = serverTick;
    }

    void onBlockStateChanged(ClientLevel world, BlockPos pos, BlockState oldState, BlockState newState) {
        if (world == null || pos == null || oldState == newState || (oldState != null && oldState.equals(newState))) {
            return;
        }
        if (!experimentalEnabled) {
            return;
        }
        Identifier dimensionId = world.dimension().identifier();
        invalidateStaticCacheForPatchFootprint(dimensionId, pos, oldState, newState);
        if (!streamingEnabled || clientSolveDisabled || worldKey == 0L) {
            return;
        }
        if (activeDimension == null || !activeDimension.equals(dimensionId) || activeBrickCount <= 0) {
            return;
        }
        if (!blockPatchTouchesActiveBrick(pos)) {
            return;
        }
        queueStaticPatchPositions(dimensionId, worldKey, world.getGameTime(), pos, oldState, newState);
    }

    public void onClientTick(Minecraft client) {
        BlockPos anchor = client == null || client.player == null ? null : client.player.getOnPos();
        onClientTick(client, anchor);
    }

    void onClientTick(Minecraft client, BlockPos anchorBlockPos) {
        drainWorkerAtlases();
        tickDebugQCriterionIsoLive(client == null ? null : client.level);
        if (!experimentalEnabled || !streamingEnabled || client == null || client.level == null || client.player == null || anchorBlockPos == null) {
            return;
        }
        if (clientSolveDisabled) {
            return;
        }
        ClientLevel world = client.level;
        long clientGameTime = world.getGameTime();
        if (lastProcessedClientGameTime == clientGameTime) {
            return;
        }
        lastProcessedClientGameTime = clientGameTime;

        float horizontalSpeed = AeroMinecraftVectors.horizontalSpeedMetersPerSecond(client.player.getDeltaMovement());
        if (shouldSuspendForFastMovement(horizontalSpeed, clientGameTime)) {
            suspendForFastMovement(clientGameTime);
            return;
        }
        if (!worker.isNativeLoaded()) {
            maybeLog(client, "native library not loaded: " + worker.loadError());
            return;
        }

        Identifier dimensionId = world.dimension().identifier();
        BlockPos anchorPos = anchorBlockPos.immutable();
        BlockPos origin = brickOrigin(anchorPos);
        int brickX = Math.floorDiv(origin.getX(), BRICK_SIZE);
        int brickY = Math.floorDiv(origin.getY(), BRICK_SIZE);
        int brickZ = Math.floorDiv(origin.getZ(), BRICK_SIZE);
        int localX = anchorPos.getX() - origin.getX();
        int localY = anchorPos.getY() - origin.getY();
        int localZ = anchorPos.getZ() - origin.getZ();
        boolean dimensionChanged = activeDimension == null || !activeDimension.equals(dimensionId);
        boolean originChanged = activeOrigin == null
            || !activeOrigin.equals(origin)
            || dimensionChanged;
        boolean activeSetChanged = originChanged || !activeSetMatches(brickX, brickY, brickZ, localX, localY, localZ);
        if (activeSetChanged) {
            activeOrigin = origin;
            activeDimension = dimensionId;
            worldKey = worldKey(dimensionId);
            activeHintUploaded = false;
            buildActiveBrickSet(brickX, brickY, brickZ, localX, localY, localZ);
            clearPendingStaticPatches();
            cancelStagedPreparation();
            cancelBoundaryReferenceRefresh();
            lastPublishedClientGameTime = Long.MIN_VALUE;
            lastSolveClientGameTime = Long.MIN_VALUE;
            lastBoundaryRefreshClientGameTime = Long.MIN_VALUE;
            lastStaticPatchSubmitClientGameTime = Long.MIN_VALUE;
            stressStaticSubmittedForActiveSet = false;
            if (dimensionChanged) {
                visualizer.clearLocalFlowFields();
            }
            ticksSinceStaticRefresh = 0;
        }

        if (!activeHintUploaded) {
            worker.submitActiveHints(worldKey, activeHintCoords);
            activeHintUploaded = true;
        }

        prepareActiveBricks(client, world, dimensionId);
        if (!hasReadyActiveBrick()) {
            return;
        }
        if (refreshActiveBrickStatic(client, world)) {
            return;
        }
        StaticPatchFlushResult staticPatchFlush = flushPendingStaticPatches(world, dimensionId, clientGameTime);
        if (staticPatchFlush == StaticPatchFlushResult.DELAYED) {
            return;
        }
        boolean submittedStaticPatch = staticPatchFlush == StaticPatchFlushResult.SUBMITTED;
        if (!submittedStaticPatch && refreshActiveBrickBoundaryReference(client, dimensionId, clientGameTime)) {
            return;
        }
        maybeSubmitStressDeltas(dimensionId, clientGameTime);
        if (!submittedStaticPatch
                && lastSolveClientGameTime != Long.MIN_VALUE
                && clientGameTime - lastSolveClientGameTime < SOLVE_INTERVAL_TICKS) {
            return;
        }
        boolean publish = lastPublishedClientGameTime == Long.MIN_VALUE
                || clientGameTime - lastPublishedClientGameTime >= LOCAL_PUBLISH_INTERVAL_TICKS;
        worker.requestStep(
                worldKey,
                publishTargets(dimensionId, publish),
                MAX_STEPS_PER_CLIENT_TICK,
                publish && visualizer.renderQCriterionIsoEnabled()
        );
        lastSolveClientGameTime = clientGameTime;
        if (publish) {
            lastPublishedClientGameTime = clientGameTime;
        }
    }

    void onIdleClientTick() {
        drainWorkerAtlases();
        Minecraft client = Minecraft.getInstance();
        tickDebugQCriterionIsoLive(client == null ? null : client.level);
    }

    String requestDebugQCriterionIsoRegion(ClientLevel world, BlockPos origin, int requestedSize, int requestedSteps) {
        return requestDebugQCriterionIsoRegion(world, origin, requestedSize, requestedSteps, false);
    }

    String requestDebugQCriterionIsoRegion(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            int requestedSteps,
            boolean includeEntities
    ) {
        return requestDebugQCriterionIsoRegion(
                world,
                origin,
                requestedSize,
                requestedSteps,
                includeEntities,
                null
        );
    }

    String requestDebugQCriterionIsoRegion(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            int requestedSteps,
            boolean includeEntities,
            UUID targetEntityId
    ) {
        return requestDebugQCriterionIsoRegion(
                world,
                origin,
                requestedSize,
                requestedSteps,
                includeEntities,
                targetEntityId,
                1.0f
        );
    }

    String requestDebugQCriterionIsoRegion(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            int requestedSteps,
            boolean includeEntities,
            UUID targetEntityId,
            float cellSizeBlocks
    ) {
        drainWorkerAtlases();
        if (world == null || origin == null) {
            return "Client L2 debug Q solve failed: missing client world or origin";
        }
        if (!worker.isNativeLoaded()) {
            return "Client L2 debug Q solve failed: native library not loaded: " + worker.loadError();
        }
        DebugRegionSeed seed = buildDebugRegionSeed(
                world,
                origin,
                requestedSize,
                includeEntities,
                targetEntityId,
                cellSizeBlocks
        );
        int steps = Mth.clamp(requestedSteps, 1, DEBUG_Q_SOLVE_MAX_STEPS);
        stopDebugQCriterionIsoLiveRegion(true);
        resetDebugQIsoStats();
        DebugInlet inlet = debugQInletForTime(world.getGameTime());
        rememberDebugQInlet(inlet);
        visualizer.clearQCriterionIsoFields();
        showDebugQCriterionSeed(seed);
        worker.submitDebugRegionSolve(new DebugRegionSolveCommand(
                worldKey(seed.dimensionId()),
                seed.dimensionId(),
                seed.origin(),
                seed.gridSize(),
                DEBUG_Q_SOLVE_SAMPLE_STRIDE,
                steps,
                seed.solidCells(),
                seed.cellSizeBlocks(),
                seed.packedSolidVoxels(),
                seed.solidMask(),
                seed.flowState(),
                inlet.vx(),
                inlet.vy(),
                inlet.vz()
        ));
        return String.format(
                java.util.Locale.ROOT,
                "Queued debug Q solve at %d %d %d cells=%d span=%.2f cellSize=%.3f steps=%d solidCells=%d entityCells=%d entities=%s target=%s inlet=(%.1f, %.1f, %.1f)",
                seed.origin().getX(),
                seed.origin().getY(),
                seed.origin().getZ(),
                seed.gridSize(),
                seed.spanBlocks(),
                seed.cellSizeBlocks(),
                steps,
                seed.solidCells(),
                seed.entityCells(),
                seed.includeEntities(),
                formatDebugTarget(seed.targetEntityId()),
                inlet.vx(),
                inlet.vy(),
                inlet.vz()
        );
    }

    String requestDebugQCriterionIsoLiveRegion(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            int requestedStepsPerFrame,
            int requestedIntervalTicks
    ) {
        return requestDebugQCriterionIsoLiveRegion(
                world,
                origin,
                requestedSize,
                requestedStepsPerFrame,
                requestedIntervalTicks,
                false
        );
    }

    String requestDebugQCriterionIsoLiveRegion(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            int requestedStepsPerFrame,
            int requestedIntervalTicks,
            boolean includeEntities
    ) {
        return requestDebugQCriterionIsoLiveRegion(
                world,
                origin,
                requestedSize,
                requestedStepsPerFrame,
                requestedIntervalTicks,
                includeEntities,
                null
        );
    }

    String requestDebugQCriterionIsoLiveRegion(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            int requestedStepsPerFrame,
            int requestedIntervalTicks,
            boolean includeEntities,
            UUID targetEntityId
    ) {
        return requestDebugQCriterionIsoLiveRegion(
                world,
                origin,
                requestedSize,
                requestedStepsPerFrame,
                requestedIntervalTicks,
                includeEntities,
                targetEntityId,
                1.0f
        );
    }

    String requestDebugQCriterionIsoLiveRegion(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            int requestedStepsPerFrame,
            int requestedIntervalTicks,
            boolean includeEntities,
            UUID targetEntityId,
            float cellSizeBlocks
    ) {
        drainWorkerAtlases();
        if (world == null || origin == null) {
            return "Client L2 debug Q live failed: missing client world or origin";
        }
        if (!worker.isNativeLoaded()) {
            return "Client L2 debug Q live failed: native library not loaded: " + worker.loadError();
        }
        DebugRegionSeed seed = buildDebugRegionSeed(
                world,
                origin,
                requestedSize,
                includeEntities,
                targetEntityId,
                cellSizeBlocks
        );
        int stepsPerFrame = Mth.clamp(requestedStepsPerFrame, 1, DEBUG_Q_LIVE_MAX_STEPS_PER_FRAME);
        int intervalTicks = Mth.clamp(requestedIntervalTicks, 1, DEBUG_Q_LIVE_MAX_INTERVAL_TICKS);
        resetDebugQIsoStats();
        DebugInlet inlet = debugQInletForTime(world.getGameTime());
        rememberDebugQInlet(inlet);
        visualizer.clearQCriterionIsoFields();
        showDebugQCriterionSeed(seed);
        debugQIsoLiveRequest = new DebugQIsoLiveRequest(
                seed.dimensionId(),
                seed.origin(),
                seed.gridSize(),
                seed.solidCells(),
                seed.entityCells(),
                seed.includeEntities(),
                seed.targetEntityId(),
                seed.cellSizeBlocks(),
                stepsPerFrame,
                intervalTicks
        );
        debugQIsoLiveLastSubmitGameTime = world.getGameTime();
        debugQIsoLiveStepPending = true;
        submitDebugRegionStart(seed, stepsPerFrame, inlet);
        return String.format(
                java.util.Locale.ROOT,
                "Started debug Q live at %d %d %d cells=%d span=%.2f cellSize=%.3f steps/frame=%d intervalTicks=%d solidCells=%d entityCells=%d entities=%s target=%s inlet=(%.1f, %.1f, %.1f)",
                seed.origin().getX(),
                seed.origin().getY(),
                seed.origin().getZ(),
                seed.gridSize(),
                seed.spanBlocks(),
                seed.cellSizeBlocks(),
                stepsPerFrame,
                intervalTicks,
                seed.solidCells(),
                seed.entityCells(),
                seed.includeEntities(),
                formatDebugTarget(seed.targetEntityId()),
                inlet.vx(),
                inlet.vy(),
                inlet.vz()
        );
    }

    String stopDebugQCriterionIsoLiveRegion(boolean submitStop) {
        debugQIsoLiveRequest = null;
        debugQIsoLiveLastSubmitGameTime = Long.MIN_VALUE;
        debugQIsoLiveStepPending = false;
        if (submitStop && worker.isNativeLoaded()) {
            worker.submitDebugRegionStop();
        }
        return "Stopped debug Q live";
    }

    private void resetDebugQIsoStats() {
        debugQIsoLastThreshold = 0.0f;
        debugQIsoLastMaxQ = 0.0f;
        debugQIsoLastPositiveSamples = 0;
        debugQIsoLastPointCount = 0;
        debugQIsoLastTriangleCount = 0;
    }

    private void showDebugQCriterionSeed(DebugRegionSeed seed) {
        visualizer.showDebugQCriterionIsoField(
                seed.dimensionId(),
                seed.origin(),
                DEBUG_Q_SOLVE_SAMPLE_STRIDE,
                seed.gridSize(),
                new int[0],
                new float[0],
                seed.packedSolidVoxels(),
                seed.cellSizeBlocks(),
                0.0f,
                0.0f,
                seed.solidCells()
        );
    }

    private void submitDebugRegionStart(DebugRegionSeed seed, int initialSteps, DebugInlet inlet) {
        worker.submitDebugRegionStart(new DebugRegionStartCommand(
                worldKey(seed.dimensionId()),
                seed.dimensionId(),
                seed.origin(),
                seed.gridSize(),
                DEBUG_Q_SOLVE_SAMPLE_STRIDE,
                initialSteps,
                seed.solidCells(),
                seed.entityCells(),
                seed.includeEntities(),
                seed.cellSizeBlocks(),
                seed.packedSolidVoxels(),
                seed.solidMask(),
                seed.flowState(),
                inlet.vx(),
                inlet.vy(),
                inlet.vz()
        ));
    }

    private DebugInlet debugQInletForTime(long gameTime) {
        float amplitude = debugQInletNoiseAmplitude;
        if (!(amplitude > 0.0f) || !Float.isFinite(amplitude)) {
            return new DebugInlet(DEBUG_Q_SOLVE_INLET_X, DEBUG_Q_SOLVE_INLET_Y, DEBUG_Q_SOLVE_INLET_Z);
        }
        double t = gameTime * 0.11;
        float vy = (float) (amplitude * (
                0.72 * Math.sin(t + 0.37)
                        + 0.28 * Math.sin(t * 1.73 + 2.11)
        ));
        float vz = (float) (amplitude * (
                0.68 * Math.cos(t * 0.91 + 1.19)
                        + 0.32 * Math.sin(t * 1.41 + 4.23)
        ));
        return new DebugInlet(DEBUG_Q_SOLVE_INLET_X, vy, vz);
    }

    private void rememberDebugQInlet(DebugInlet inlet) {
        debugQIsoLastInletVx = inlet.vx();
        debugQIsoLastInletVy = inlet.vy();
        debugQIsoLastInletVz = inlet.vz();
    }

    String setDebugQIsoAutoThresholdFraction(float fraction) {
        if (!Float.isFinite(fraction) || fraction <= 0.0f || fraction > 1.0f) {
            return "Client L2 debug Q threshold failed: auto fraction must be in (0, 1]";
        }
        qIsoFixedThreshold = 0.0f;
        qIsoAutoThresholdFraction = fraction;
        return String.format(
                java.util.Locale.ROOT,
                "Client L2 debug Q threshold set to auto fraction=%.6g",
                qIsoAutoThresholdFraction
        );
    }

    String setDebugQIsoFixedThreshold(float threshold) {
        if (!Float.isFinite(threshold) || threshold < 0.0f || threshold > 1.0e6f) {
            return "Client L2 debug Q threshold failed: fixed threshold must be in [0, 1e6]";
        }
        qIsoFixedThreshold = threshold;
        return threshold > 0.0f
                ? String.format(
                        java.util.Locale.ROOT,
                        "Client L2 debug Q threshold set to fixed=%.6g autoFraction=%.6g",
                        qIsoFixedThreshold,
                        qIsoAutoThresholdFraction
                )
                : String.format(
                        java.util.Locale.ROOT,
                        "Client L2 debug Q threshold set to auto fraction=%.6g",
                        qIsoAutoThresholdFraction
                );
    }

    String resetDebugQIsoThreshold() {
        qIsoFixedThreshold = Q_ISO_FIXED_THRESHOLD;
        qIsoAutoThresholdFraction = Q_ISO_AUTO_THRESHOLD_FRACTION;
        qIsoSolidRejectCells = Q_ISO_SOLID_REJECT_DEFAULT_CELLS;
        return String.format(
                java.util.Locale.ROOT,
                "Client L2 debug Q threshold reset fixed=%.6g autoFraction=%.6g solidRejectCells=%d",
                qIsoFixedThreshold,
                qIsoAutoThresholdFraction,
                qIsoSolidRejectCells
        );
    }

    String setDebugQIsoSolidRejectCells(int cells) {
        qIsoSolidRejectCells = Mth.clamp(cells, 0, Q_ISO_SOLID_REJECT_MAX_CELLS);
        return String.format(
                java.util.Locale.ROOT,
                "Client L2 debug Q solid reject cells=%d",
                qIsoSolidRejectCells
        );
    }

    String setDebugQIsoDisplayMode(String modeName) {
        QIsoDisplayMode mode = switch (modeName == null ? "" : modeName.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "surface", "surfaces", "iso", "isosurface", "triangles" -> QIsoDisplayMode.SURFACE;
            case "voxels", "voxel", "points", "point" -> QIsoDisplayMode.VOXELS;
            default -> null;
        };
        if (mode == null) {
            return "Client L2 debug Q display failed: expected surface or voxels";
        }
        qIsoDisplayMode = mode;
        resetDebugQIsoStats();
        visualizer.clearQCriterionIsoFields();
        return "Client L2 debug Q display=" + mode.statusName;
    }

    String setDebugQInletNoiseAmplitude(float amplitude) {
        if (!Float.isFinite(amplitude) || amplitude < 0.0f || amplitude > DEBUG_Q_INLET_NOISE_MAX_AMPLITUDE) {
            return String.format(
                    java.util.Locale.ROOT,
                    "Client L2 debug Q inlet noise failed: amplitude must be in [0, %.1f]",
                    DEBUG_Q_INLET_NOISE_MAX_AMPLITUDE
            );
        }
        debugQInletNoiseAmplitude = amplitude;
        return String.format(
                java.util.Locale.ROOT,
                "Client L2 debug Q inlet noise amplitude=%.3f",
                debugQInletNoiseAmplitude
        );
    }

    String debugQIsoStatus() {
        DebugQIsoLiveRequest request = debugQIsoLiveRequest;
        if (request == null) {
            return String.format(
                    java.util.Locale.ROOT,
                    "debug Q live=off display=%s thresholdMode=%s fixed=%.6g autoFraction=%.6g solidRejectCells=%d inletNoise=%.3f lastInlet=(%.2f, %.2f, %.2f) lastThreshold=%.6g lastMaxQ=%.6g lastPositiveSamples=%d lastPoints=%d lastTriangles=%d worker=%s",
                    qIsoDisplayMode.statusName,
                    qIsoFixedThreshold > 0.0f ? "fixed" : "auto",
                    qIsoFixedThreshold,
                    qIsoAutoThresholdFraction,
                    qIsoSolidRejectCells,
                    debugQInletNoiseAmplitude,
                    debugQIsoLastInletVx,
                    debugQIsoLastInletVy,
                    debugQIsoLastInletVz,
                    debugQIsoLastThreshold,
                    debugQIsoLastMaxQ,
                    debugQIsoLastPositiveSamples,
                    debugQIsoLastPointCount,
                    debugQIsoLastTriangleCount,
                    worker.status()
            );
        }
        return String.format(
                java.util.Locale.ROOT,
                "debug Q live=on origin=%d %d %d cells=%d span=%.2f cellSize=%.3f solidCells=%d entityCells=%d entities=%s target=%s steps/frame=%d intervalTicks=%d pending=%s display=%s thresholdMode=%s fixed=%.6g autoFraction=%.6g solidRejectCells=%d inletNoise=%.3f lastInlet=(%.2f, %.2f, %.2f) lastThreshold=%.6g lastMaxQ=%.6g lastPositiveSamples=%d lastPoints=%d lastTriangles=%d worker=%s",
                request.origin().getX(),
                request.origin().getY(),
                request.origin().getZ(),
                request.gridSize(),
                request.spanBlocks(),
                request.cellSizeBlocks(),
                request.solidCells(),
                request.entityCells(),
                request.includeEntities(),
                formatDebugTarget(request.targetEntityId()),
                request.stepsPerFrame(),
                request.intervalTicks(),
                debugQIsoLiveStepPending,
                qIsoDisplayMode.statusName,
                qIsoFixedThreshold > 0.0f ? "fixed" : "auto",
                qIsoFixedThreshold,
                qIsoAutoThresholdFraction,
                qIsoSolidRejectCells,
                debugQInletNoiseAmplitude,
                debugQIsoLastInletVx,
                debugQIsoLastInletVy,
                debugQIsoLastInletVz,
                debugQIsoLastThreshold,
                debugQIsoLastMaxQ,
                debugQIsoLastPositiveSamples,
                debugQIsoLastPointCount,
                debugQIsoLastTriangleCount,
                worker.status()
        );
    }

    private static String formatDebugTarget(UUID targetEntityId) {
        return targetEntityId == null ? "all" : targetEntityId.toString();
    }

    private DebugRegionSeed buildDebugRegionSeed(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            boolean includeEntities
    ) {
        return buildDebugRegionSeed(world, origin, requestedSize, includeEntities, null);
    }

    private DebugRegionSeed buildDebugRegionSeed(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            boolean includeEntities,
            UUID targetEntityId
    ) {
        return buildDebugRegionSeed(world, origin, requestedSize, includeEntities, targetEntityId, 1.0f);
    }

    private DebugRegionSeed buildDebugRegionSeed(
            ClientLevel world,
            BlockPos origin,
            int requestedSize,
            boolean includeEntities,
            UUID targetEntityId,
            float requestedCellSizeBlocks
    ) {
        Identifier dimensionId = world.dimension().identifier();
        BlockPos immutableOrigin = origin.immutable();
        int gridSize = Mth.clamp(requestedSize, DEBUG_Q_SOLVE_MIN_SIZE, DEBUG_Q_SOLVE_MAX_SIZE);
        float cellSizeBlocks = Mth.clamp(
                Float.isFinite(requestedCellSizeBlocks) ? requestedCellSizeBlocks : 1.0f,
                0.03125f,
                4.0f
        );
        int cells = gridSize * gridSize * gridSize;
        byte[] solidMask = new byte[cells];
        float[] initialFlowState = new float[cells * FLOW_CHANNELS];
        int[] packedSolidVoxels = new int[Math.min(cells, DEBUG_Q_MASK_MAX_RENDER_CELLS)];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int solidCells = 0;
        int writtenSolidVoxels = 0;
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    cursor.set(
                            (int) Math.floor(immutableOrigin.getX() + (x + 0.5f) * cellSizeBlocks),
                            (int) Math.floor(immutableOrigin.getY() + (y + 0.5f) * cellSizeBlocks),
                            (int) Math.floor(immutableOrigin.getZ() + (z + 0.5f) * cellSizeBlocks)
                    );
                    int cell = cellIndex(gridSize, x, y, z);
                    if (isSolidObstacle(world, cursor, world.getBlockState(cursor))) {
                        solidMask[cell] = 1;
                        solidCells++;
                        if (writtenSolidVoxels < packedSolidVoxels.length) {
                            packedSolidVoxels[writtenSolidVoxels++] = packDebugVoxel(x, y, z);
                        }
                        continue;
                    }
                    int base = cell * FLOW_CHANNELS;
                    initialFlowState[base] = DEBUG_Q_SOLVE_INLET_X;
                    initialFlowState[base + 1] = DEBUG_Q_SOLVE_INLET_Y;
                    initialFlowState[base + 2] = DEBUG_Q_SOLVE_INLET_Z;
                    initialFlowState[base + 3] = 0.0f;
                }
            }
        }
        DebugEntityVoxelizeResult entityVoxelize = includeEntities
                ? voxelizeDebugRegionEntities(
                        world,
                        immutableOrigin,
                        gridSize,
                        cellSizeBlocks,
                        solidMask,
                        initialFlowState,
                        packedSolidVoxels,
                        writtenSolidVoxels,
                        targetEntityId
                )
                : new DebugEntityVoxelizeResult(0, writtenSolidVoxels);
        solidCells += entityVoxelize.entityCells();
        int entityCells = entityVoxelize.entityCells();
        writtenSolidVoxels = entityVoxelize.writtenSolidVoxels();
        if (writtenSolidVoxels < packedSolidVoxels.length) {
            packedSolidVoxels = java.util.Arrays.copyOf(packedSolidVoxels, writtenSolidVoxels);
        }
        return new DebugRegionSeed(
                dimensionId,
                immutableOrigin,
                gridSize,
                solidCells,
                entityCells,
                includeEntities,
                targetEntityId,
                cellSizeBlocks,
                packedSolidVoxels,
                solidMask,
                initialFlowState
        );
    }

    private DebugEntityVoxelizeResult voxelizeDebugRegionEntities(
            ClientLevel world,
            BlockPos origin,
            int gridSize,
            float cellSizeBlocks,
            byte[] solidMask,
            float[] initialFlowState,
            int[] packedSolidVoxels,
            int writtenSolidVoxels,
            UUID targetEntityId
    ) {
        AABB regionBox = new AABB(
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                origin.getX() + gridSize * cellSizeBlocks,
                origin.getY() + gridSize * cellSizeBlocks,
                origin.getZ() + gridSize * cellSizeBlocks
        );
        Minecraft client = Minecraft.getInstance();
        Entity localPlayer = client == null ? null : client.player;
        int entityCells = 0;
        for (Entity entity : world.getEntities(
                (Entity) null,
                regionBox,
                candidate -> candidate != localPlayer
                        && !candidate.isRemoved()
                        && !candidate.isSpectator()
                        && (targetEntityId == null || targetEntityId.equals(candidate.getUUID()))
        )) {
            AABB box = entity.getBoundingBox();
            double minX = Math.max(box.minX, regionBox.minX);
            double minY = Math.max(box.minY, regionBox.minY);
            double minZ = Math.max(box.minZ, regionBox.minZ);
            double maxX = Math.min(box.maxX, regionBox.maxX);
            double maxY = Math.min(box.maxY, regionBox.maxY);
            double maxZ = Math.min(box.maxZ, regionBox.maxZ);
            if (!(maxX > minX) || !(maxY > minY) || !(maxZ > minZ)) {
                continue;
            }
            int x0 = Mth.clamp((int) Math.floor((minX - origin.getX()) / cellSizeBlocks), 0, gridSize - 1);
            int y0 = Mth.clamp((int) Math.floor((minY - origin.getY()) / cellSizeBlocks), 0, gridSize - 1);
            int z0 = Mth.clamp((int) Math.floor((minZ - origin.getZ()) / cellSizeBlocks), 0, gridSize - 1);
            int x1 = Mth.clamp((int) Math.ceil((maxX - origin.getX()) / cellSizeBlocks) - 1, 0, gridSize - 1);
            int y1 = Mth.clamp((int) Math.ceil((maxY - origin.getY()) / cellSizeBlocks) - 1, 0, gridSize - 1);
            int z1 = Mth.clamp((int) Math.ceil((maxZ - origin.getZ()) / cellSizeBlocks) - 1, 0, gridSize - 1);
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        int cell = cellIndex(gridSize, x, y, z);
                        if (solidMask[cell] != 0) {
                            continue;
                        }
                        solidMask[cell] = 1;
                        entityCells++;
                        int base = cell * FLOW_CHANNELS;
                        initialFlowState[base] = 0.0f;
                        initialFlowState[base + 1] = 0.0f;
                        initialFlowState[base + 2] = 0.0f;
                        initialFlowState[base + 3] = 0.0f;
                        if (writtenSolidVoxels < packedSolidVoxels.length) {
                            packedSolidVoxels[writtenSolidVoxels++] = packDebugVoxel(x, y, z);
                        }
                    }
                }
            }
        }
        return new DebugEntityVoxelizeResult(entityCells, writtenSolidVoxels);
    }

    private Entity findDebugQIsoTargetEntity(ClientLevel world, DebugQIsoLiveRequest request) {
        UUID targetEntityId = request.targetEntityId();
        if (targetEntityId == null) {
            return null;
        }
        float spanBlocks = request.spanBlocks();
        double expand = Math.max(16.0, spanBlocks * 2.0);
        BlockPos origin = request.origin();
        AABB searchBox = new AABB(
                origin.getX() - expand,
                origin.getY() - expand,
                origin.getZ() - expand,
                origin.getX() + spanBlocks + expand,
                origin.getY() + spanBlocks + expand,
                origin.getZ() + spanBlocks + expand
        );
        for (Entity candidate : world.getEntities(
                (Entity) null,
                searchBox,
                entity -> targetEntityId.equals(entity.getUUID()) && !entity.isRemoved() && !entity.isSpectator()
        )) {
            return candidate;
        }
        return null;
    }

    private boolean shouldRecenterDebugQIso(DebugQIsoLiveRequest request, Entity targetEntity) {
        float spanBlocks = request.spanBlocks();
        BlockPos origin = request.origin();
        double patchCenterX = origin.getX() + spanBlocks * 0.5;
        double patchCenterY = origin.getY() + spanBlocks * 0.5;
        double patchCenterZ = origin.getZ() + spanBlocks * 0.5;
        AABB targetBox = targetEntity.getBoundingBox();
        double targetCenterX = (targetBox.minX + targetBox.maxX) * 0.5;
        double targetCenterY = (targetBox.minY + targetBox.maxY) * 0.5;
        double targetCenterZ = (targetBox.minZ + targetBox.maxZ) * 0.5;
        double dx = targetCenterX - patchCenterX;
        double dy = targetCenterY - patchCenterY;
        double dz = targetCenterZ - patchCenterZ;
        double threshold = Math.max(1.0, spanBlocks * 0.25);
        return dx * dx + dy * dy + dz * dz > threshold * threshold;
    }

    private BlockPos centeredDebugRegionOrigin(Entity entity, float spanBlocks) {
        AABB targetBox = entity.getBoundingBox();
        double halfSpan = spanBlocks * 0.5;
        return new BlockPos(
                (int) Math.floor((targetBox.minX + targetBox.maxX) * 0.5 - halfSpan),
                (int) Math.floor((targetBox.minY + targetBox.maxY) * 0.5 - halfSpan),
                (int) Math.floor((targetBox.minZ + targetBox.maxZ) * 0.5 - halfSpan)
        );
    }

    private void tickDebugQCriterionIsoLive(ClientLevel world) {
        DebugQIsoLiveRequest request = debugQIsoLiveRequest;
        if (request == null || world == null || debugQIsoLiveStepPending) {
            return;
        }
        if (!request.dimensionId().equals(world.dimension().identifier())) {
            return;
        }
        long gameTime = world.getGameTime();
        if (debugQIsoLiveLastSubmitGameTime != Long.MIN_VALUE
                && gameTime - debugQIsoLiveLastSubmitGameTime < request.intervalTicks()) {
            return;
        }
        DebugRegionSeed refreshedSeed = null;
        boolean restartSolver = false;
        DebugInlet inlet = debugQInletForTime(gameTime);
        rememberDebugQInlet(inlet);
        if (request.includeEntities()) {
            BlockPos refreshOrigin = request.origin();
            Entity targetEntity = findDebugQIsoTargetEntity(world, request);
            if (targetEntity != null && shouldRecenterDebugQIso(request, targetEntity)) {
                refreshOrigin = centeredDebugRegionOrigin(targetEntity, request.spanBlocks());
                restartSolver = true;
            }
            refreshedSeed = buildDebugRegionSeed(
                    world,
                    refreshOrigin,
                    request.gridSize(),
                    true,
                    request.targetEntityId(),
                    request.cellSizeBlocks()
            );
            debugQIsoLiveRequest = new DebugQIsoLiveRequest(
                    refreshedSeed.dimensionId(),
                    refreshedSeed.origin(),
                    refreshedSeed.gridSize(),
                    refreshedSeed.solidCells(),
                    refreshedSeed.entityCells(),
                    true,
                    refreshedSeed.targetEntityId(),
                    refreshedSeed.cellSizeBlocks(),
                    request.stepsPerFrame(),
                    request.intervalTicks()
            );
        }
        debugQIsoLiveLastSubmitGameTime = gameTime;
        debugQIsoLiveStepPending = true;
        if (restartSolver && refreshedSeed != null) {
            visualizer.clearQCriterionIsoFields();
            showDebugQCriterionSeed(refreshedSeed);
            submitDebugRegionStart(refreshedSeed, request.stepsPerFrame(), inlet);
            return;
        }
        if (refreshedSeed == null) {
            worker.submitDebugRegionStep(new DebugRegionStepCommand(
                    request.stepsPerFrame(),
                    request.solidCells(),
                    request.entityCells(),
                    request.includeEntities(),
                    request.cellSizeBlocks(),
                    new int[0],
                    null,
                    inlet.vx(),
                    inlet.vy(),
                    inlet.vz()
            ));
            return;
        }
        worker.submitDebugRegionStep(new DebugRegionStepCommand(
                request.stepsPerFrame(),
                refreshedSeed.solidCells(),
                refreshedSeed.entityCells(),
                refreshedSeed.includeEntities(),
                refreshedSeed.cellSizeBlocks(),
                refreshedSeed.packedSolidVoxels(),
                refreshedSeed.solidMask(),
                inlet.vx(),
                inlet.vy(),
                inlet.vz()
        ));
    }

    private void drainWorkerAtlases() {
        LocalAtlasSnapshot snapshot;
        while ((snapshot = worker.pollAtlas()) != null) {
            visualizer.onLocalFlowField(
                    snapshot.dimensionId(),
                    snapshot.origin(),
                    snapshot.sampleStride(),
                    snapshot.packedFlow()
            );
        }
        LocalQIsoSnapshot qSnapshot;
        while ((qSnapshot = worker.pollQIso()) != null) {
            if (qSnapshot.debug()) {
                debugQIsoLiveStepPending = false;
                debugQIsoLastThreshold = qSnapshot.threshold();
                debugQIsoLastMaxQ = qSnapshot.maxQ();
                debugQIsoLastPositiveSamples = qSnapshot.positiveSamples();
                debugQIsoLastPointCount = qSnapshot.packedPoints().length;
                debugQIsoLastTriangleCount = qSnapshot.triangleVertices().length / 9;
                visualizer.showDebugQCriterionIsoField(
                        qSnapshot.dimensionId(),
                        qSnapshot.origin(),
                        qSnapshot.sampleStride(),
                        qSnapshot.atlasResolution(),
                        qSnapshot.packedPoints(),
                        qSnapshot.triangleVertices(),
                        qSnapshot.packedSolidVoxels(),
                        qSnapshot.cellSizeBlocks(),
                        qSnapshot.threshold(),
                        qSnapshot.maxQ(),
                        qSnapshot.positiveSamples()
                );
            } else {
                visualizer.onLocalQCriterionIsoField(
                        qSnapshot.dimensionId(),
                        qSnapshot.origin(),
                        qSnapshot.sampleStride(),
                        qSnapshot.atlasResolution(),
                        qSnapshot.packedPoints(),
                        qSnapshot.triangleVertices(),
                        qSnapshot.threshold(),
                        qSnapshot.maxQ(),
                        qSnapshot.positiveSamples()
                );
            }
        }
    }

    private PublishTarget[] publishTargets(Identifier dimensionId, boolean publish) {
        if (!publish || activeBrickCount <= 0) {
            return new PublishTarget[0];
        }
        PublishTarget[] targets = new PublishTarget[activeBrickCount];
        int count = 0;
        for (int attempts = 0; attempts < activeBrickCount; attempts++) {
            int index = (publishCursor + attempts) % activeBrickCount;
            if (!activeBrickReady[index]) {
                continue;
            }
            int brickX = activeBrickX[index];
            int brickY = activeBrickY[index];
            int brickZ = activeBrickZ[index];
            targets[count++] = new PublishTarget(dimensionId, brickOrigin(brickX, brickY, brickZ), brickX, brickY, brickZ);
        }
        publishCursor = activeBrickCount <= 0 ? 0 : (publishCursor + 1) % activeBrickCount;
        return java.util.Arrays.copyOf(targets, count);
    }

    private void maybeSubmitStressDeltas(Identifier dimensionId, long clientGameTime) {
        if (stressMode == StressMode.OFF || worldKey == 0L || activeBrickCount <= 0) {
            return;
        }
        if (activeDimension == null || !activeDimension.equals(dimensionId)) {
            return;
        }
        if (lastStressSubmitGameTime != Long.MIN_VALUE
                && clientGameTime - lastStressSubmitGameTime < STRESS_INTERVAL_TICKS) {
            return;
        }
        if (stressStaticSubmittedForActiveSet && stressModeIsStatic(stressMode)) {
            return;
        }
        if (worker.queueSize() > STRESS_QUEUE_BACKLOG_LIMIT) {
            return;
        }
        int activeIndex = firstReadyActiveBrickIndex();
        if (activeIndex < 0) {
            return;
        }
        BlockPos origin = brickOrigin(
                activeBrickX[activeIndex],
                activeBrickY[activeIndex],
                activeBrickZ[activeIndex]
        );
        NativeSimulationBridge.WorldDelta[] deltas = new NativeSimulationBridge.WorldDelta[STRESS_PATCHES_PER_TICK];
        int count = 0;
        int fanCells = 0;
        int heatCells = 0;
        int dirtyCells = 0;
        for (int i = 0; i < STRESS_PATCHES_PER_TICK; i++) {
            StressMode cellMode = stressCellMode(i);
            int seed = stressSeed(activeIndex, clientGameTime, i);
            int x = stressLocalCoord(seed);
            int y = stressLocalCoord(seed >>> 7);
            int z = stressLocalCoord(seed >>> 17);
            boolean solid = false;
            byte syntheticSurfaceKind = 0;
            float syntheticEmitterPower = 0.0f;
            int syntheticOpenFaceMask = ALL_OPEN_FACE_MASK;

            if (cellMode == StressMode.FAN) {
                syntheticSurfaceKind = fanSurfaceKind(stressFanDirection(i, clientGameTime));
                fanCells++;
            } else if (cellMode == StressMode.THERMAL) {
                syntheticEmitterPower = THERMAL_EMITTER_POWER_FIRE_W;
                heatCells++;
            } else if (cellMode == StressMode.DIRTY) {
                solid = (stressMix(seed ^ (int) (clientGameTime / 8L)) & 1) == 0;
                syntheticOpenFaceMask = solid ? 0 : ALL_OPEN_FACE_MASK;
                dirtyCells++;
            }

            int packedState = (solid ? 1 : 0)
                    | ((Byte.toUnsignedInt(syntheticSurfaceKind) & 0xFF) << 8);
            deltas[count++] = new NativeSimulationBridge.WorldDelta(
                    NativeSimulationBridge.WORLD_DELTA_BRICK_STATIC_CELL_PATCH,
                    origin.getX() + x,
                    origin.getY() + y,
                    origin.getZ() + z,
                    (int) worldKey,
                    packedState,
                    syntheticOpenFaceMask,
                    0,
                    syntheticEmitterPower,
                    0.0f,
                    0.0f,
                    0.0f
            );
        }
        if (count == 0) {
            return;
        }
        NativeSimulationBridge.WorldDelta[] submitted = count == deltas.length
                ? deltas
                : java.util.Arrays.copyOf(deltas, count);
        if (stressStartedGameTime == Long.MIN_VALUE) {
            stressStartedGameTime = clientGameTime;
        }
        lastStressSubmitGameTime = clientGameTime;
        stressSubmittedTicks++;
        stressSubmittedPatches += submitted.length;
        stressSubmittedFanCells += fanCells;
        stressSubmittedHeatCells += heatCells;
        stressSubmittedDirtyCells += dirtyCells;
        lastStaticPatchCount = submitted.length;
        lastFanPatchCellCount = fanCells;
        lastHeatPatchCellCount = heatCells;
        if (stressModeIsStatic(stressMode)) {
            stressStaticSubmittedForActiveSet = true;
        }
        worker.submitWorldDeltas(worldKey, submitted);
    }

    private boolean stressModeIsStatic(StressMode mode) {
        return mode == StressMode.FAN || mode == StressMode.THERMAL;
    }

    private StressMode stressCellMode(int index) {
        if (stressMode != StressMode.MIXED) {
            return stressMode;
        }
        return switch (index & 3) {
            case 0 -> StressMode.FAN;
            case 1 -> StressMode.THERMAL;
            default -> StressMode.DIRTY;
        };
    }

    private int firstReadyActiveBrickIndex() {
        for (int index = 0; index < activeBrickCount; index++) {
            if (activeBrickReady[index]) {
                return index;
            }
        }
        return -1;
    }

    private int stressSeed(int activeIndex, long clientGameTime, int index) {
        int seed = (int) clientGameTime;
        seed ^= index * 0x9E3779B9;
        seed ^= activeBrickX[activeIndex] * 0x85EBCA6B;
        seed ^= activeBrickY[activeIndex] * 0xC2B2AE35;
        seed ^= activeBrickZ[activeIndex] * 0x27D4EB2D;
        return stressMix(seed);
    }

    private int stressLocalCoord(int seed) {
        int innerSpan = Math.max(1, BRICK_SIZE - 4);
        return 2 + Math.floorMod(stressMix(seed), innerSpan);
    }

    private Direction stressFanDirection(int index, long clientGameTime) {
        Direction[] directions = Direction.values();
        return directions[Math.floorMod(index + (int) clientGameTime, directions.length)];
    }

    private static int stressMix(int value) {
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        value ^= value >>> 16;
        return value;
    }

    private boolean shouldSuspendForFastMovement(float horizontalSpeedMetersPerSecond, long clientGameTime) {
        if (horizontalSpeedMetersPerSecond > AeroWindSamplingRules.FAST_PLAYER_HORIZONTAL_SPEED_THRESHOLD_MPS) {
            return true;
        }
        if (fastSuspendUntilGameTime != Long.MIN_VALUE && clientGameTime < fastSuspendUntilGameTime) {
            return horizontalSpeedMetersPerSecond > FAST_RESUME_HORIZONTAL_SPEED_MPS;
        }
        fastSuspendUntilGameTime = Long.MIN_VALUE;
        return false;
    }

    private void suspendForFastMovement(long clientGameTime) {
        fastSuspendUntilGameTime = clientGameTime + FAST_SUSPEND_COOLDOWN_TICKS;
        if (activeBrickCount > 0 || activeOrigin != null) {
            resetActiveBrick();
        } else {
            visualizer.clearLocalFlowFields();
        }
    }

    private boolean activeSetMatches(
            int coreBrickX,
            int coreBrickY,
            int coreBrickZ,
            int localX,
            int localY,
            int localZ
    ) {
        int expectedIndex = 0;
        if (!activeBrickMatches(expectedIndex++, coreBrickX, coreBrickY, coreBrickZ)) {
            return false;
        }
        int[] neighborOffset = MAX_CLIENT_ACTIVE_BRICKS > 1
                ? nearestBoundaryNeighborOffset(localX, localY, localZ)
                : null;
        if (neighborOffset != null
                && !activeBrickMatches(
                expectedIndex++,
                coreBrickX + neighborOffset[0],
                coreBrickY + neighborOffset[1],
                coreBrickZ + neighborOffset[2]
        )) {
            return false;
        }
        return activeBrickCount == expectedIndex;
    }

    private int[] nearestBoundaryNeighborOffset(int localX, int localY, int localZ) {
        int bestDistance = COUPLING_BAND_CELLS;
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        if (localX < bestDistance) {
            bestDistance = localX;
            bestX = -1;
            bestY = 0;
            bestZ = 0;
        }
        int distance = BRICK_SIZE - 1 - localX;
        if (distance < bestDistance) {
            bestDistance = distance;
            bestX = 1;
            bestY = 0;
            bestZ = 0;
        }
        if (localY < bestDistance) {
            bestDistance = localY;
            bestX = 0;
            bestY = -1;
            bestZ = 0;
        }
        distance = BRICK_SIZE - 1 - localY;
        if (distance < bestDistance) {
            bestDistance = distance;
            bestX = 0;
            bestY = 1;
            bestZ = 0;
        }
        if (localZ < bestDistance) {
            bestDistance = localZ;
            bestX = 0;
            bestY = 0;
            bestZ = -1;
        }
        distance = BRICK_SIZE - 1 - localZ;
        if (distance < bestDistance) {
            bestDistance = distance;
            bestX = 0;
            bestY = 0;
            bestZ = 1;
        }
        return bestDistance < COUPLING_BAND_CELLS ? new int[]{bestX, bestY, bestZ} : null;
    }

    private boolean activeBrickMatches(int index, int brickX, int brickY, int brickZ) {
        return index < activeBrickCount
                && activeBrickX[index] == brickX
                && activeBrickY[index] == brickY
                && activeBrickZ[index] == brickZ;
    }

    private void buildActiveBrickSet(
            int coreBrickX,
            int coreBrickY,
            int coreBrickZ,
            int localX,
            int localY,
            int localZ
    ) {
        int oldActiveBrickCount = activeBrickCount;
        int[] oldActiveBrickX = java.util.Arrays.copyOf(activeBrickX, oldActiveBrickCount);
        int[] oldActiveBrickY = java.util.Arrays.copyOf(activeBrickY, oldActiveBrickCount);
        int[] oldActiveBrickZ = java.util.Arrays.copyOf(activeBrickZ, oldActiveBrickCount);
        boolean[] oldActiveBrickReady = java.util.Arrays.copyOf(activeBrickReady, oldActiveBrickCount);
        activeBrickCount = 0;
        prepareCursor = 0;
        refreshCursor = 0;
        publishCursor = 0;
        activeHintCoords = new int[MAX_CLIENT_ACTIVE_BRICKS * NativeSimulationBridge.BRICK_HINT_COORDS_PER_BRICK];
        java.util.Arrays.fill(activeBrickReady, false);
        java.util.Arrays.fill(activeBrickRefreshPending, false);
        java.util.Arrays.fill(activeBrickBoundaryRefreshPending, false);
        addActiveBrick(
                coreBrickX,
                coreBrickY,
                coreBrickZ,
                oldActiveBrickX,
                oldActiveBrickY,
                oldActiveBrickZ,
                oldActiveBrickReady
        );
        int[] neighborOffset = MAX_CLIENT_ACTIVE_BRICKS > 1
                ? nearestBoundaryNeighborOffset(localX, localY, localZ)
                : null;
        if (neighborOffset != null) {
            addActiveBrick(
                    coreBrickX + neighborOffset[0],
                    coreBrickY + neighborOffset[1],
                    coreBrickZ + neighborOffset[2],
                    oldActiveBrickX,
                    oldActiveBrickY,
                    oldActiveBrickZ,
                    oldActiveBrickReady
            );
        }
        activeHintCoords = java.util.Arrays.copyOf(
                activeHintCoords,
                activeBrickCount * NativeSimulationBridge.BRICK_HINT_COORDS_PER_BRICK
        );
    }

    private void addActiveBrick(
            int brickX,
            int brickY,
            int brickZ,
            int[] oldActiveBrickX,
            int[] oldActiveBrickY,
            int[] oldActiveBrickZ,
            boolean[] oldActiveBrickReady
    ) {
        if (activeBrickCount >= MAX_CLIENT_ACTIVE_BRICKS) {
            return;
        }
        int index = activeBrickCount++;
        activeBrickX[index] = brickX;
        activeBrickY[index] = brickY;
        activeBrickZ[index] = brickZ;
        activeBrickReady[index] = wasActiveBrickReady(
                brickX,
                brickY,
                brickZ,
                oldActiveBrickX,
                oldActiveBrickY,
                oldActiveBrickZ,
                oldActiveBrickReady
        );
        activeBrickBoundaryRefreshPending[index] = true;
        int hintBase = index * NativeSimulationBridge.BRICK_HINT_COORDS_PER_BRICK;
        activeHintCoords[hintBase] = brickX;
        activeHintCoords[hintBase + 1] = brickY;
        activeHintCoords[hintBase + 2] = brickZ;
    }

    private boolean wasActiveBrickReady(
            int brickX,
            int brickY,
            int brickZ,
            int[] oldActiveBrickX,
            int[] oldActiveBrickY,
            int[] oldActiveBrickZ,
            boolean[] oldActiveBrickReady
    ) {
        for (int index = 0; index < oldActiveBrickReady.length; index++) {
            if (oldActiveBrickReady[index]
                    && oldActiveBrickX[index] == brickX
                    && oldActiveBrickY[index] == brickY
                    && oldActiveBrickZ[index] == brickZ) {
                return true;
            }
        }
        return false;
    }

    private boolean prepareActiveBricks(Minecraft client, ClientLevel world, Identifier dimensionId) {
        if (activeBrickCount <= 0) {
            return false;
        }
        for (int attempts = 0; attempts < activeBrickCount; attempts++) {
            int index = (prepareCursor + attempts) % activeBrickCount;
            if (activeBrickReady[index]) {
                continue;
            }
            BrickPreparationResult result = uploadAndSeedActiveBrick(client, world, dimensionId, index);
            if (result == BrickPreparationResult.IN_PROGRESS) {
                return false;
            }
            if (result == BrickPreparationResult.FAILED) {
                return false;
            }
            activeBrickReady[index] = true;
            activeBrickBoundaryRefreshPending[index] = false;
            prepareCursor = (index + 1) % activeBrickCount;
            return false;
        }
        return true;
    }

    private boolean hasReadyActiveBrick() {
        for (int index = 0; index < activeBrickCount; index++) {
            if (activeBrickReady[index]) {
                return true;
            }
        }
        return false;
    }

    private BrickPreparationResult uploadAndSeedActiveBrick(
            Minecraft client,
            ClientLevel world,
            Identifier dimensionId,
            int activeIndex
    ) {
        int brickX = activeBrickX[activeIndex];
        int brickY = activeBrickY[activeIndex];
        int brickZ = activeBrickZ[activeIndex];
        BlockPos origin = brickOrigin(brickX, brickY, brickZ);
        if (!stagedPreparationMatches(activeIndex, dimensionId, brickX, brickY, brickZ)) {
            beginStagedPreparation(activeIndex, dimensionId, origin, brickX, brickY, brickZ);
        }
        if (!buildStagedStaticCells(world)) {
            return BrickPreparationResult.IN_PROGRESS;
        }
        cacheStagedStaticBrickIfNeeded();
        stagedStaticUploaded = true;
        if (!buildStagedCoarseSeedCells(dimensionId)) {
            return BrickPreparationResult.IN_PROGRESS;
        }
        if (!stagedDynamicUploaded) {
            worker.submitBrickSeed(new BrickSeedCommand(
                    worldKey,
                    brickX,
                    brickY,
                    brickZ,
                    java.util.Arrays.copyOf(obstacle, obstacle.length),
                    java.util.Arrays.copyOf(flowState, flowState.length)
            ));
            stagedDynamicUploaded = true;
            return BrickPreparationResult.IN_PROGRESS;
        }
        cancelStagedPreparation();
        return BrickPreparationResult.COMPLETED;
    }

    private boolean stagedPreparationMatches(
            int activeIndex,
            Identifier dimensionId,
            int brickX,
            int brickY,
            int brickZ
    ) {
        return stagedActiveIndex == activeIndex
                && stagedDimension != null
                && stagedDimension.equals(dimensionId)
                && stagedBrickX == brickX
                && stagedBrickY == brickY
                && stagedBrickZ == brickZ;
    }

    private void beginStagedPreparation(
            int activeIndex,
            Identifier dimensionId,
            BlockPos origin,
            int brickX,
            int brickY,
            int brickZ
    ) {
        stagedActiveIndex = activeIndex;
        stagedDimension = dimensionId;
        stagedOrigin = origin;
        stagedBrickX = brickX;
        stagedBrickY = brickY;
        stagedBrickZ = brickZ;
        stagedStaticCursor = 0;
        stagedSeedCursor = 0;
        stagedStaticUploaded = false;
        stagedDynamicUploaded = false;
        stagedStaticFromCache = false;
        stagedCoarseSeedReady = false;
        stagedSeedVx = 0.0f;
        stagedSeedVy = 0.0f;
        stagedSeedVz = 0.0f;
        stagedSeedPressure = 0.0f;
        java.util.Arrays.fill(obstacle, (byte) 0);
        java.util.Arrays.fill(surfaceKind, (byte) 0);
        java.util.Arrays.fill(openFaceMask, (short) 0);
        java.util.Arrays.fill(emitterPower, 0.0f);
        java.util.Arrays.fill(sourceFanDirection, (byte) 0);
        java.util.Arrays.fill(sourceEmitterPower, 0.0f);
        java.util.Arrays.fill(faceSkyExposure, (byte) 0);
        java.util.Arrays.fill(faceDirectExposure, (byte) 0);
        java.util.Arrays.fill(flowState, 0.0f);
        java.util.Arrays.fill(airTemperature, 0.0f);
        java.util.Arrays.fill(surfaceTemperature, 0.0f);
        StaticBrickSnapshot cached = staticBrickCache.get(new StaticBrickCacheKey(dimensionId, brickX, brickY, brickZ));
        if (cached != null) {
            cached.copyInto(obstacle, surfaceKind, openFaceMask, emitterPower, sourceFanDirection, sourceEmitterPower);
            stagedStaticCursor = CELL_COUNT;
            stagedStaticFromCache = true;
        }
    }

    private boolean buildStagedStaticCells(ClientLevel world) {
        if (stagedOrigin == null) {
            return false;
        }
        long deadline = System.nanoTime() + STATIC_BUILD_NANOS_PER_TICK;
        int end = Math.min(CELL_COUNT, stagedStaticCursor + STATIC_BUILD_CELLS_PER_TICK);
        int built = 0;
        while (stagedStaticCursor < end) {
            int cell = stagedStaticCursor++;
            int x = cell / (BRICK_SIZE * BRICK_SIZE);
            int rem = cell - x * BRICK_SIZE * BRICK_SIZE;
            int y = rem / BRICK_SIZE;
            int z = rem - y * BRICK_SIZE;
            populateStaticCell(world, stagedOrigin, x, y, z);
            built++;
            if ((built & 255) == 0 && System.nanoTime() >= deadline) {
                break;
            }
        }
        return stagedStaticCursor >= CELL_COUNT;
    }

    private void cacheStagedStaticBrickIfNeeded() {
        if (stagedStaticFromCache || stagedDimension == null || stagedOrigin == null) {
            return;
        }
        staticBrickCache.put(
                new StaticBrickCacheKey(stagedDimension, stagedBrickX, stagedBrickY, stagedBrickZ),
                StaticBrickSnapshot.copyFrom(obstacle, surfaceKind, openFaceMask, emitterPower, sourceFanDirection, sourceEmitterPower)
        );
        stagedStaticFromCache = true;
    }

    private boolean buildStagedCoarseSeedCells(Identifier dimensionId) {
        if (stagedOrigin == null) {
            return false;
        }
        if (!stagedCoarseSeedReady) {
            Vec3 center = new Vec3(
                stagedOrigin.getX() + BRICK_SIZE * 0.5,
                stagedOrigin.getY() + BRICK_SIZE * 0.5,
                stagedOrigin.getZ() + BRICK_SIZE * 0.5
            );
            AeroWindSample coarse = visualizer.sampleServerCoarseFlow(dimensionId, center);
            if (!coarse.hasFlow()) {
                return false;
            }
            stagedSeedVx = coarse.velocityX();
            stagedSeedVy = coarse.velocityY();
            stagedSeedVz = coarse.velocityZ();
            stagedSeedPressure = coarse.pressure();
            stagedCoarseSeedReady = true;
        }
        long deadline = System.nanoTime() + COARSE_SEED_NANOS_PER_TICK;
        int built = 0;
        while (stagedSeedCursor < CELL_COUNT && built < COARSE_SEED_CELLS_PER_TICK) {
            int cell = stagedSeedCursor;
            int base = cell * FLOW_CHANNELS;
            if (obstacle[cell] == 0) {
                flowState[base] = stagedSeedVx;
                flowState[base + 1] = stagedSeedVy;
                flowState[base + 2] = stagedSeedVz;
                flowState[base + 3] = stagedSeedPressure;
            } else {
                flowState[base] = 0.0f;
                flowState[base + 1] = 0.0f;
                flowState[base + 2] = 0.0f;
                flowState[base + 3] = 0.0f;
            }
            stagedSeedCursor++;
            built++;
            if ((built & 1023) == 0 && System.nanoTime() >= deadline) {
                break;
            }
        }
        return stagedSeedCursor >= CELL_COUNT;
    }

    private void cancelStagedPreparation() {
        stagedActiveIndex = -1;
        stagedOrigin = null;
        stagedDimension = null;
        stagedBrickX = 0;
        stagedBrickY = 0;
        stagedBrickZ = 0;
        stagedStaticCursor = 0;
        stagedSeedCursor = 0;
        stagedStaticUploaded = false;
        stagedDynamicUploaded = false;
        stagedStaticFromCache = false;
        stagedCoarseSeedReady = false;
        stagedSeedVx = 0.0f;
        stagedSeedVy = 0.0f;
        stagedSeedVz = 0.0f;
        stagedSeedPressure = 0.0f;
    }

    private boolean refreshActiveBrickStatic(Minecraft client, ClientLevel world) {
        if (STATIC_REFRESH_TICKS <= 0) {
            java.util.Arrays.fill(activeBrickRefreshPending, false);
            return false;
        }
        if (!hasRefreshPending()) {
            if (ticksSinceStaticRefresh++ < STATIC_REFRESH_TICKS) {
                return false;
            }
            java.util.Arrays.fill(activeBrickRefreshPending, 0, activeBrickCount, true);
            refreshCursor = 0;
            ticksSinceStaticRefresh = 0;
        }
        for (int attempts = 0; attempts < activeBrickCount; attempts++) {
            int index = (refreshCursor + attempts) % activeBrickCount;
            if (!activeBrickRefreshPending[index]) {
                continue;
            }
            int brickX = activeBrickX[index];
            int brickY = activeBrickY[index];
            int brickZ = activeBrickZ[index];
            uploadStaticBrick(world, brickOrigin(brickX, brickY, brickZ), brickX, brickY, brickZ);
            activeBrickRefreshPending[index] = false;
            refreshCursor = (index + 1) % activeBrickCount;
            return true;
        }
        return false;
    }

    private boolean hasRefreshPending() {
        for (int index = 0; index < activeBrickCount; index++) {
            if (activeBrickRefreshPending[index]) {
                return true;
            }
        }
        return false;
    }

    private void markBoundaryRefreshPending() {
        for (int index = 0; index < activeBrickCount; index++) {
            if (activeBrickReady[index]) {
                activeBrickBoundaryRefreshPending[index] = true;
            }
        }
    }

    private boolean refreshActiveBrickBoundaryReference(
            Minecraft client,
            Identifier dimensionId,
            long clientGameTime
    ) {
        if (!hasBoundaryRefreshPending() && boundaryRefreshActiveIndex < 0) {
            return false;
        }
        if (lastStaticPatchSubmitClientGameTime != Long.MIN_VALUE
                && clientGameTime - lastStaticPatchSubmitClientGameTime < BOUNDARY_REFRESH_AFTER_STATIC_PATCH_COOLDOWN_TICKS) {
            return false;
        }
        if (stagedActiveIndex >= 0) {
            return false;
        }
        if (boundaryRefreshActiveIndex < 0
                && lastBoundaryRefreshClientGameTime != Long.MIN_VALUE
                && clientGameTime - lastBoundaryRefreshClientGameTime < BOUNDARY_REFERENCE_REFRESH_MIN_TICKS) {
            return false;
        }
        for (int attempts = 0; attempts < activeBrickCount; attempts++) {
            int index = boundaryRefreshActiveIndex >= 0
                    ? boundaryRefreshActiveIndex
                    : (refreshCursor + attempts) % activeBrickCount;
            if (!activeBrickBoundaryRefreshPending[index]) {
                if (boundaryRefreshActiveIndex >= 0) {
                    cancelBoundaryReferenceRefresh();
                }
                continue;
            }
            if (!activeBrickReady[index]) {
                activeBrickBoundaryRefreshPending[index] = false;
                if (boundaryRefreshActiveIndex >= 0) {
                    cancelBoundaryReferenceRefresh();
                }
                continue;
            }
            int brickX = activeBrickX[index];
            int brickY = activeBrickY[index];
            int brickZ = activeBrickZ[index];
            BlockPos origin = brickOrigin(brickX, brickY, brickZ);
            if (!boundaryReferenceRefreshMatches(index, dimensionId, brickX, brickY, brickZ)) {
                beginBoundaryReferenceRefresh(index, dimensionId, origin, brickX, brickY, brickZ);
            }
            BoundaryReferenceBuildResult result = buildBoundaryReferenceCells(dimensionId);
            if (result == BoundaryReferenceBuildResult.WAITING_FOR_COARSE) {
                maybeLog(client, "client L2 boundary refresh waiting for coarse field");
                return false;
            }
            if (result == BoundaryReferenceBuildResult.IN_PROGRESS) {
                return false;
            }
            worker.submitBoundaryReference(new BoundaryReferenceCommand(
                    worldKey,
                    brickX,
                    brickY,
                    brickZ,
                    java.util.Arrays.copyOf(flowState, flowState.length),
                    boundaryRefreshMaxCoarseSpeed
            ));
            activeBrickBoundaryRefreshPending[index] = false;
            refreshCursor = (index + 1) % activeBrickCount;
            lastBoundaryRefreshClientGameTime = clientGameTime;
            cancelBoundaryReferenceRefresh();
            return false;
        }
        return false;
    }

    private boolean boundaryReferenceRefreshMatches(
            int activeIndex,
            Identifier dimensionId,
            int brickX,
            int brickY,
            int brickZ
    ) {
        return boundaryRefreshActiveIndex == activeIndex
                && boundaryRefreshDimension != null
                && boundaryRefreshDimension.equals(dimensionId)
                && boundaryRefreshBrickX == brickX
                && boundaryRefreshBrickY == brickY
                && boundaryRefreshBrickZ == brickZ;
    }

    private void beginBoundaryReferenceRefresh(
            int activeIndex,
            Identifier dimensionId,
            BlockPos origin,
            int brickX,
            int brickY,
            int brickZ
    ) {
        boundaryRefreshActiveIndex = activeIndex;
        boundaryRefreshDimension = dimensionId;
        boundaryRefreshOrigin = origin;
        boundaryRefreshBrickX = brickX;
        boundaryRefreshBrickY = brickY;
        boundaryRefreshBrickZ = brickZ;
        boundaryRefreshCursor = 0;
        boundaryRefreshMaxCoarseSpeed = 0.0f;
        java.util.Arrays.fill(flowState, 0.0f);
    }

    private BoundaryReferenceBuildResult buildBoundaryReferenceCells(Identifier dimensionId) {
        if (boundaryRefreshOrigin == null) {
            return BoundaryReferenceBuildResult.WAITING_FOR_COARSE;
        }
        long deadline = System.nanoTime() + BOUNDARY_REFERENCE_NANOS_PER_TICK;
        int built = 0;
        while (boundaryRefreshCursor < CELL_COUNT && built < BOUNDARY_REFERENCE_CELLS_PER_TICK) {
            int cell = boundaryRefreshCursor;
            int x = cell / (BRICK_SIZE * BRICK_SIZE);
            int rem = cell - x * BRICK_SIZE * BRICK_SIZE;
            int y = rem / BRICK_SIZE;
            int z = rem - y * BRICK_SIZE;
            int base = cell * FLOW_CHANNELS;
            if (obstacle[cell] == 0 && isBoundaryReferenceCell(x, y, z)) {
                Vec3 pos = new Vec3(
                    boundaryRefreshOrigin.getX() + x + 0.5,
                    boundaryRefreshOrigin.getY() + y + 0.5,
                    boundaryRefreshOrigin.getZ() + z + 0.5
                );
                AeroWindSample coarse = visualizer.sampleServerCoarseFlow(dimensionId, pos);
                if (!coarse.hasFlow()) {
                    return BoundaryReferenceBuildResult.WAITING_FOR_COARSE;
                }
                flowState[base] = coarse.velocityX();
                flowState[base + 1] = coarse.velocityY();
                flowState[base + 2] = coarse.velocityZ();
                flowState[base + 3] = coarse.pressure();
                float speed = (float) AeroMinecraftVectors.velocity(coarse).length();
                if (Float.isFinite(speed) && speed > boundaryRefreshMaxCoarseSpeed) {
                    boundaryRefreshMaxCoarseSpeed = speed;
                }
            } else {
                flowState[base] = 0.0f;
                flowState[base + 1] = 0.0f;
                flowState[base + 2] = 0.0f;
                flowState[base + 3] = 0.0f;
            }
            boundaryRefreshCursor++;
            built++;
            if ((built & 255) == 0 && System.nanoTime() >= deadline) {
                break;
            }
        }
        return boundaryRefreshCursor >= CELL_COUNT
                ? BoundaryReferenceBuildResult.COMPLETED
                : BoundaryReferenceBuildResult.IN_PROGRESS;
    }

    private boolean isBoundaryReferenceCell(int x, int y, int z) {
        int layers = Math.min(8, BRICK_SIZE);
        return x < layers
            || y < layers
            || z < layers
            || x >= BRICK_SIZE - layers
            || y >= BRICK_SIZE - layers
            || z >= BRICK_SIZE - layers;
    }

    private void cancelBoundaryReferenceRefresh() {
        boundaryRefreshActiveIndex = -1;
        boundaryRefreshDimension = null;
        boundaryRefreshOrigin = null;
        boundaryRefreshBrickX = 0;
        boundaryRefreshBrickY = 0;
        boundaryRefreshBrickZ = 0;
        boundaryRefreshCursor = 0;
        boundaryRefreshMaxCoarseSpeed = 0.0f;
    }

    private boolean hasBoundaryRefreshPending() {
        for (int index = 0; index < activeBrickCount; index++) {
            if (activeBrickBoundaryRefreshPending[index]) {
                return true;
            }
        }
        return false;
    }

    private boolean uploadStaticBrick(ClientLevel world, BlockPos origin, int brickX, int brickY, int brickZ) {
        StaticBrickCacheKey key = activeDimension == null ? null : new StaticBrickCacheKey(activeDimension, brickX, brickY, brickZ);
        StaticBrickSnapshot cached = key == null ? null : staticBrickCache.get(key);
        if (cached != null) {
            cached.copyInto(obstacle, surfaceKind, openFaceMask, emitterPower, sourceFanDirection, sourceEmitterPower);
            java.util.Arrays.fill(faceSkyExposure, (byte) 0);
            java.util.Arrays.fill(faceDirectExposure, (byte) 0);
            return true;
        }
        populateStaticBrickArrays(world, origin);
        if (key != null) {
            staticBrickCache.put(key, StaticBrickSnapshot.copyFrom(obstacle, surfaceKind, openFaceMask, emitterPower, sourceFanDirection, sourceEmitterPower));
        }
        return true;
    }

    private void queueStaticPatchPositions(
            Identifier dimensionId,
            long patchWorldKey,
            long clientGameTime,
            BlockPos center,
            BlockState oldState,
            BlockState newState
    ) {
        if (pendingStaticPatchWorldKey != patchWorldKey
                || pendingStaticPatchDimension == null
                || !pendingStaticPatchDimension.equals(dimensionId)) {
            pendingSourcePatches.clear();
            pendingStaticPatchDimension = dimensionId;
            pendingStaticPatchWorldKey = patchWorldKey;
            pendingStaticPatchFirstChangeGameTime = Long.MIN_VALUE;
            pendingStaticPatchLastChangeGameTime = Long.MIN_VALUE;
            pendingStaticPatchSourceChanges = 0;
        }
        if (pendingStaticPatchFirstChangeGameTime == Long.MIN_VALUE) {
            pendingStaticPatchFirstChangeGameTime = clientGameTime;
        }
        pendingStaticPatchLastChangeGameTime = clientGameTime;
        pendingStaticPatchSourceChanges++;
        BlockPos key = center.immutable();
        PendingSourcePatch existing = pendingSourcePatches.get(key);
        pendingSourcePatches.put(
                key,
                new PendingSourcePatch(key, existing == null ? oldState : existing.oldState(), newState)
        );
        lastStaticPatchCount = pendingSourcePatches.size();
        lastFanPatchCellCount = 0;
        lastHeatPatchCellCount = 0;
    }

    private StaticPatchFlushResult flushPendingStaticPatches(ClientLevel world, Identifier dimensionId, long clientGameTime) {
        if (pendingSourcePatches.isEmpty()) {
            return StaticPatchFlushResult.NONE;
        }
        if (activeDimension == null
                || !activeDimension.equals(dimensionId)
                || pendingStaticPatchDimension == null
                || !pendingStaticPatchDimension.equals(dimensionId)
                || pendingStaticPatchWorldKey != worldKey
                || worldKey == 0L) {
            clearPendingStaticPatches();
            return StaticPatchFlushResult.NONE;
        }
        if (activeBrickCount <= 0 || !hasReadyActiveBrick()) {
            return StaticPatchFlushResult.NONE;
        }
        if (shouldDelayPendingStaticPatches(clientGameTime)) {
            return StaticPatchFlushResult.DELAYED;
        }
        NativeSimulationBridge.WorldDelta[] deltas = new NativeSimulationBridge.WorldDelta[pendingSourcePatches.size()];
        int count = 0;
        int fanSourcePatches = 0;
        int heatSourcePatches = 0;
        for (PendingSourcePatch patch : pendingSourcePatches.values()) {
            NativeSimulationBridge.WorldDelta delta = buildStaticSourcePatchDelta(world, patch);
            deltas[count++] = delta;
            int oldFanDirection = (delta.data1() >> 8) & 0xFF;
            int newFanDirection = (delta.data1() >> 16) & 0xFF;
            if (oldFanDirection != 0 || newFanDirection != 0) {
                fanSourcePatches++;
            }
            if (delta.value0() > 0.0f || delta.value1() > 0.0f) {
                heatSourcePatches++;
            }
            refreshLocalStaticCellIfActive(world, patch.pos());
        }
        clearPendingStaticPatches();
        if (count == 0) {
            return StaticPatchFlushResult.NONE;
        }
        NativeSimulationBridge.WorldDelta[] submitted = count == deltas.length
                ? deltas
                : java.util.Arrays.copyOf(deltas, count);
        lastStaticPatchCount = submitted.length;
        lastFanPatchCellCount = fanSourcePatches;
        lastHeatPatchCellCount = heatSourcePatches;
        lastStaticPatchSubmitClientGameTime = clientGameTime;
        invalidateActiveBricksForStaticChange();
        return StaticPatchFlushResult.SUBMITTED;
    }

    private void invalidateActiveBricksForStaticChange() {
        if (activeBrickCount <= 0) {
            return;
        }
        java.util.Arrays.fill(activeBrickReady, 0, activeBrickCount, false);
        java.util.Arrays.fill(activeBrickRefreshPending, 0, activeBrickCount, false);
        java.util.Arrays.fill(activeBrickBoundaryRefreshPending, 0, activeBrickCount, false);
        activeHintUploaded = false;
        prepareCursor = 0;
        refreshCursor = 0;
        publishCursor = 0;
        cancelStagedPreparation();
        cancelBoundaryReferenceRefresh();
        lastSolveClientGameTime = Long.MIN_VALUE;
        lastPublishedClientGameTime = Long.MIN_VALUE;
        lastBoundaryRefreshClientGameTime = Long.MIN_VALUE;
        stressStaticSubmittedForActiveSet = false;
        visualizer.clearLocalFlowFields();
        worker.reset();
    }

    private boolean shouldDelayPendingStaticPatches(long clientGameTime) {
        if (STATIC_PATCH_DEBOUNCE_TICKS <= 0) {
            return false;
        }
        boolean bulkPatch = pendingStaticPatchSourceChanges >= STATIC_PATCH_BULK_CHANGE_THRESHOLD
                || pendingSourcePatches.size() >= STATIC_PATCH_BULK_CELL_THRESHOLD;
        if (!bulkPatch
                || pendingStaticPatchFirstChangeGameTime == Long.MIN_VALUE
                || pendingStaticPatchLastChangeGameTime == Long.MIN_VALUE) {
            return false;
        }
        long sinceFirstChange = clientGameTime - pendingStaticPatchFirstChangeGameTime;
        long sinceLastChange = clientGameTime - pendingStaticPatchLastChangeGameTime;
        if (sinceFirstChange < 0L || sinceLastChange < 0L) {
            return false;
        }
        return sinceLastChange < STATIC_PATCH_DEBOUNCE_TICKS
                && sinceFirstChange < STATIC_PATCH_MAX_DEBOUNCE_TICKS;
    }

    private void addStaticPatchPositionsForChange(
            java.util.LinkedHashSet<BlockPos> positions,
            BlockPos center,
            BlockState oldState,
            BlockState newState
    ) {
        addStaticPatchPosition(positions, center);
        for (Direction direction : Direction.values()) {
            addStaticPatchPosition(positions, center.relative(direction));
        }
        addFanOcclusionPatchPositions(positions, center);
        addHeatOcclusionPatchPositions(positions, center);
        addForcingSourcePatchPositions(positions, center, oldState);
        addForcingSourcePatchPositions(positions, center, newState);
    }

    private void addStaticPatchPosition(java.util.LinkedHashSet<BlockPos> positions, BlockPos pos) {
        positions.add(pos.immutable());
    }

    private void addFanOcclusionPatchPositions(java.util.LinkedHashSet<BlockPos> positions, BlockPos center) {
        for (Direction direction : Direction.values()) {
            for (int distance = 1; distance <= FAN_FORCE_LENGTH_CELLS; distance++) {
                addFanDiskPatchPositions(positions, offset(center, direction, distance), direction);
            }
        }
    }

    private void addHeatOcclusionPatchPositions(java.util.LinkedHashSet<BlockPos> positions, BlockPos center) {
        for (int distance = 1; distance <= HEAT_PLUME_HEIGHT_CELLS; distance++) {
            addStaticPatchPosition(positions, offset(center, Direction.UP, distance));
        }
    }

    private void addForcingSourcePatchPositions(
            java.util.LinkedHashSet<BlockPos> positions,
            BlockPos center,
            BlockState state
    ) {
        if (state == null) {
            return;
        }
        if (AeroBlockBehaviors.isFan(state)) {
            Direction direction = AeroBlockBehaviors.fanFacing(state);
            addFanFootprintPatchPositions(positions, center, direction);
        }
        if (sampleEmitterThermalPowerWatts(state) > 0.0f) {
            for (int distance = 0; distance <= HEAT_PLUME_HEIGHT_CELLS; distance++) {
                addStaticPatchPosition(positions, offset(center, Direction.UP, distance));
            }
        }
    }

    private BlockPos offset(BlockPos pos, Direction direction, int distance) {
        return new BlockPos(
                pos.getX() + direction.getStepX() * distance,
                pos.getY() + direction.getStepY() * distance,
                pos.getZ() + direction.getStepZ() * distance
        );
    }

    private void addFanFootprintPatchPositions(
            java.util.LinkedHashSet<BlockPos> positions,
            BlockPos fanPos,
            Direction direction
    ) {
        for (int distance = 1; distance <= FAN_FORCE_LENGTH_CELLS; distance++) {
            addFanDiskPatchPositions(positions, offset(fanPos, direction, distance), direction);
        }
    }

    private void addFanDiskPatchPositions(
            java.util.LinkedHashSet<BlockPos> positions,
            BlockPos center,
            Direction direction
    ) {
        Direction.Axis axis = direction.getAxis();
        for (int a = -FAN_FORCE_RADIUS_CELLS; a <= FAN_FORCE_RADIUS_CELLS; a++) {
            for (int b = -FAN_FORCE_RADIUS_CELLS; b <= FAN_FORCE_RADIUS_CELLS; b++) {
                addStaticPatchPosition(positions, offsetPerpendicular(center, axis, a, b));
            }
        }
    }

    private BlockPos offsetPerpendicular(BlockPos pos, Direction.Axis axis, int a, int b) {
        return switch (axis) {
            case X -> new BlockPos(pos.getX(), pos.getY() + a, pos.getZ() + b);
            case Y -> new BlockPos(pos.getX() + a, pos.getY(), pos.getZ() + b);
            case Z -> new BlockPos(pos.getX() + a, pos.getY() + b, pos.getZ());
        };
    }

    private boolean blockPatchTouchesActiveBrick(BlockPos center) {
        if (blockInActiveBrick(center)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (blockInActiveBrick(center.relative(direction))) {
                return true;
            }
            for (int distance = 1; distance <= FAN_FORCE_LENGTH_CELLS; distance++) {
                BlockPos fanCenter = offset(center, direction, distance);
                Direction.Axis axis = direction.getAxis();
                for (int a = -FAN_FORCE_RADIUS_CELLS; a <= FAN_FORCE_RADIUS_CELLS; a++) {
                    for (int b = -FAN_FORCE_RADIUS_CELLS; b <= FAN_FORCE_RADIUS_CELLS; b++) {
                        if (blockInActiveBrick(offsetPerpendicular(fanCenter, axis, a, b))) {
                            return true;
                        }
                    }
                }
            }
        }
        for (int distance = 2; distance <= HEAT_PLUME_HEIGHT_CELLS; distance++) {
            if (blockInActiveBrick(offset(center, Direction.UP, distance))) {
                return true;
            }
        }
        return false;
    }

    private boolean blockInActiveBrick(BlockPos pos) {
        int brickX = Math.floorDiv(pos.getX(), BRICK_SIZE);
        int brickY = Math.floorDiv(pos.getY(), BRICK_SIZE);
        int brickZ = Math.floorDiv(pos.getZ(), BRICK_SIZE);
        for (int index = 0; index < activeBrickCount; index++) {
            if (activeBrickX[index] == brickX && activeBrickY[index] == brickY && activeBrickZ[index] == brickZ) {
                return true;
            }
        }
        return false;
    }

    private NativeSimulationBridge.WorldDelta buildStaticCellPatchDelta(ClientLevel world, BlockPos pos) {
        StaticCellSample sample = sampleStaticCell(world, pos);
        int packedState = (sample.solid() ? 1 : 0)
                | ((Byte.toUnsignedInt(sample.surfaceKind()) & 0xFF) << 8);
        return new NativeSimulationBridge.WorldDelta(
                NativeSimulationBridge.WORLD_DELTA_BRICK_STATIC_CELL_PATCH,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                (int) worldKey,
                packedState,
                Short.toUnsignedInt(sample.openFaceMask()),
                0,
                sample.emitterPowerWatts(),
                0.0f,
                0.0f,
                0.0f
        );
    }

    private NativeSimulationBridge.WorldDelta buildStaticSourcePatchDelta(ClientLevel world, PendingSourcePatch patch) {
        BlockPos pos = patch.pos();
        boolean oldSolid = sourceSolidForState(world, pos, patch.oldState());
        boolean newSolid = sourceSolidForState(world, pos, patch.newState());
        int oldFanDirection = sourceFanDirectionCodeForState(patch.oldState());
        int newFanDirection = sourceFanDirectionCodeForState(patch.newState());
        float oldEmitterPower = sourceEmitterPowerForState(patch.oldState());
        float newEmitterPower = sourceEmitterPowerForState(patch.newState());
        int packedState = (oldSolid ? 1 : 0)
                | (newSolid ? 2 : 0)
                | ((oldFanDirection & 0xFF) << 8)
                | ((newFanDirection & 0xFF) << 16);
        return new NativeSimulationBridge.WorldDelta(
                NativeSimulationBridge.WORLD_DELTA_BRICK_STATIC_SOURCE_PATCH,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                (int) worldKey,
                packedState,
                0,
                0,
                oldEmitterPower,
                newEmitterPower,
                0.0f,
                0.0f
        );
    }

    private void refreshLocalStaticCellIfActive(ClientLevel world, BlockPos pos) {
        for (int index = 0; index < activeBrickCount; index++) {
            int brickX = Math.floorDiv(pos.getX(), BRICK_SIZE);
            int brickY = Math.floorDiv(pos.getY(), BRICK_SIZE);
            int brickZ = Math.floorDiv(pos.getZ(), BRICK_SIZE);
            if (activeBrickX[index] != brickX || activeBrickY[index] != brickY || activeBrickZ[index] != brickZ) {
                continue;
            }
            BlockPos origin = brickOrigin(brickX, brickY, brickZ);
            int localX = pos.getX() - origin.getX();
            int localY = pos.getY() - origin.getY();
            int localZ = pos.getZ() - origin.getZ();
            if (localX >= 0 && localY >= 0 && localZ >= 0
                    && localX < BRICK_SIZE && localY < BRICK_SIZE && localZ < BRICK_SIZE) {
                populateStaticCell(world, origin, localX, localY, localZ);
            }
        }
    }

    private void invalidateStaticCacheForPatchFootprint(
            Identifier dimensionId,
            BlockPos center,
            BlockState oldState,
            BlockState newState
    ) {
        java.util.LinkedHashSet<BlockPos> positions = new java.util.LinkedHashSet<>();
        addStaticPatchPositionsForChange(positions, center, oldState, newState);
        for (BlockPos pos : positions) {
            invalidateStaticCacheForBlock(dimensionId, pos);
        }
    }

    private void invalidateStaticCacheForBlock(Identifier dimensionId, BlockPos pos) {
        staticBrickCache.remove(new StaticBrickCacheKey(
                dimensionId,
                Math.floorDiv(pos.getX(), BRICK_SIZE),
                Math.floorDiv(pos.getY(), BRICK_SIZE),
                Math.floorDiv(pos.getZ(), BRICK_SIZE)
        ));
    }

    private void markActiveBrickStaticRefreshPending(BlockPos pos) {
        int brickX = Math.floorDiv(pos.getX(), BRICK_SIZE);
        int brickY = Math.floorDiv(pos.getY(), BRICK_SIZE);
        int brickZ = Math.floorDiv(pos.getZ(), BRICK_SIZE);
        for (int index = 0; index < activeBrickCount; index++) {
            if (activeBrickX[index] == brickX && activeBrickY[index] == brickY && activeBrickZ[index] == brickZ) {
                activeBrickRefreshPending[index] = true;
                activeBrickBoundaryRefreshPending[index] = true;
                ticksSinceStaticRefresh = 0;
            }
        }
    }

    private void markAllActiveBricksStaticRefreshPending() {
        for (int index = 0; index < activeBrickCount; index++) {
            activeBrickRefreshPending[index] = true;
            activeBrickBoundaryRefreshPending[index] = true;
        }
        staticBrickCache.clear();
        ticksSinceStaticRefresh = 0;
    }

    private void populateStaticBrickArrays(ClientLevel world, BlockPos origin) {
        java.util.Arrays.fill(obstacle, (byte) 0);
        java.util.Arrays.fill(surfaceKind, (byte) 0);
        java.util.Arrays.fill(openFaceMask, (short) 0);
        java.util.Arrays.fill(emitterPower, 0.0f);
        java.util.Arrays.fill(sourceFanDirection, (byte) 0);
        java.util.Arrays.fill(sourceEmitterPower, 0.0f);
        java.util.Arrays.fill(faceSkyExposure, (byte) 0);
        java.util.Arrays.fill(faceDirectExposure, (byte) 0);

        for (int x = 0; x < BRICK_SIZE; x++) {
            for (int y = 0; y < BRICK_SIZE; y++) {
                for (int z = 0; z < BRICK_SIZE; z++) {
                    populateStaticCell(world, origin, x, y, z);
                }
            }
        }
    }

    private void populateStaticCell(ClientLevel world, BlockPos origin, int x, int y, int z) {
        staticCursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
        int cell = cellIndex(x, y, z);
        StaticCellSample sample = sampleStaticCell(world, staticCursor);
        obstacle[cell] = sample.solid() ? (byte) 1 : (byte) 0;
        surfaceKind[cell] = sample.surfaceKind();
        openFaceMask[cell] = sample.openFaceMask();
        emitterPower[cell] = sample.emitterPowerWatts();
        sourceFanDirection[cell] = sample.sourceFanDirection();
        sourceEmitterPower[cell] = sample.sourceEmitterPowerWatts();
    }

    private StaticCellSample sampleStaticCell(ClientLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        boolean solid = isSolidObstacle(world, pos, state);
        short mask = 0;
        if (!solid) {
            for (Direction direction : Direction.values()) {
                staticNeighbor.set(
                        pos.getX() + direction.getStepX(),
                        pos.getY() + direction.getStepY(),
                        pos.getZ() + direction.getStepZ()
                );
                if (!isSolidObstacle(world, staticNeighbor, world.getBlockState(staticNeighbor))) {
                    mask = (short) (mask | (1 << direction.ordinal()));
                }
            }
        }
        byte kind = solid ? (byte) 0 : fanSurfaceKindForCell(world, pos);
        float emitter = solid ? 0.0f : emitterPowerForCell(world, pos, state);
        return new StaticCellSample(
                solid,
                kind,
                mask,
                emitter,
                (byte) sourceFanDirectionCodeForState(state),
                sourceEmitterPowerForState(state)
        );
    }

    private byte fanSurfaceKindForCell(ClientLevel world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            Direction.Axis axis = direction.getAxis();
            for (int distance = 1; distance <= FAN_FORCE_LENGTH_CELLS; distance++) {
                BlockPos axialOrigin = offset(pos, direction.getOpposite(), distance);
                for (int a = -FAN_FORCE_RADIUS_CELLS; a <= FAN_FORCE_RADIUS_CELLS; a++) {
                    for (int b = -FAN_FORCE_RADIUS_CELLS; b <= FAN_FORCE_RADIUS_CELLS; b++) {
                        BlockPos fanPos = offsetPerpendicular(axialOrigin, axis, -a, -b);
                        BlockState fanState = world.getBlockState(fanPos);
                        if (!AeroBlockBehaviors.isFan(fanState)
                                || AeroBlockBehaviors.fanFacing(fanState) != direction) {
                            continue;
                        }
                        if (!fanPathClear(world, fanPos, direction, distance, a, b)) {
                            continue;
                        }
                        return fanSurfaceKind(direction);
                    }
                }
            }
        }
        return 0;
    }

    private boolean fanPathClear(ClientLevel world, BlockPos fanPos, Direction direction, int distance, int a, int b) {
        BlockPos laneStart = offsetPerpendicular(fanPos, direction.getAxis(), a, b);
        for (int step = 1; step < distance; step++) {
            staticNeighbor.set(
                    laneStart.getX() + direction.getStepX() * step,
                    laneStart.getY() + direction.getStepY() * step,
                    laneStart.getZ() + direction.getStepZ() * step
            );
            if (isSolidObstacle(world, staticNeighbor, world.getBlockState(staticNeighbor))) {
                return false;
            }
        }
        return true;
    }

    private byte fanSurfaceKind(Direction direction) {
        return switch (direction) {
            case WEST -> SURFACE_KIND_FAN_X_NEG;
            case EAST -> SURFACE_KIND_FAN_X_POS;
            case DOWN -> SURFACE_KIND_FAN_Y_NEG;
            case UP -> SURFACE_KIND_FAN_Y_POS;
            case NORTH -> SURFACE_KIND_FAN_Z_NEG;
            case SOUTH -> SURFACE_KIND_FAN_Z_POS;
        };
    }

    private int sourceFanDirectionCodeForState(BlockState state) {
        if (state == null || !AeroBlockBehaviors.isFan(state)) {
            return 0;
        }
        Direction direction = AeroBlockBehaviors.fanFacing(state);
        return switch (direction) {
            case WEST -> 1;
            case EAST -> 2;
            case DOWN -> 3;
            case UP -> 4;
            case NORTH -> 5;
            case SOUTH -> 6;
        };
    }

    private float sourceEmitterPowerForState(BlockState state) {
        return state == null ? 0.0f : sampleEmitterThermalPowerWatts(state);
    }

    private boolean sourceSolidForState(ClientLevel world, BlockPos pos, BlockState state) {
        return state != null && isSolidObstacle(world, pos, state);
    }

    private boolean isFanSurfaceKind(int surfaceKind) {
        return surfaceKind >= Byte.toUnsignedInt(SURFACE_KIND_FAN_X_NEG)
                && surfaceKind <= Byte.toUnsignedInt(SURFACE_KIND_FAN_Z_POS);
    }

    private float emitterPowerForCell(ClientLevel world, BlockPos pos, BlockState state) {
        float directPower = sampleEmitterThermalPowerWatts(state);
        if (directPower > 0.0f) {
            return directPower;
        }
        float coupledPower = 0.0f;
        for (int distance = 1; distance <= HEAT_PLUME_HEIGHT_CELLS; distance++) {
            int sourceY = pos.getY() - distance;
            staticNeighbor.set(pos.getX(), sourceY, pos.getZ());
            float belowPower = sampleEmitterThermalPowerWatts(world.getBlockState(staticNeighbor));
            if (belowPower <= 0.0f || !heatPathClear(world, pos.getX(), sourceY, pos.getZ(), pos.getY())) {
                continue;
            }
            float falloff = HEAT_COUPLING_TO_ADJACENT_AIR / (distance * distance);
            coupledPower += belowPower * falloff;
        }
        return coupledPower;
    }

    private boolean heatPathClear(ClientLevel world, int sourceX, int sourceY, int sourceZ, int targetY) {
        for (int y = sourceY + 1; y < targetY; y++) {
            staticNeighbor.set(sourceX, y, sourceZ);
            if (isSolidObstacle(world, staticNeighbor, world.getBlockState(staticNeighbor))) {
                return false;
            }
        }
        return true;
    }

    private float sampleEmitterThermalPowerWatts(BlockState state) {
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

    private boolean isSolidObstacle(ClientLevel world, BlockPos pos, BlockState state) {
        if (state.isAir() || AeroBlockBehaviors.isDuct(state)) {
            return false;
        }
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static short quantizeSignedToShort(float value, float range) {
        if (!(range > 0.0f) || !Float.isFinite(value)) {
            return 0;
        }
        float normalized = Mth.clamp(value / range, -1.0f, 1.0f);
        return (short) Math.round(normalized * 32767.0f);
    }

    private static float maxFlowSpeedMetersPerSecond(float[] state) {
        float maxSpeed = 0.0f;
        for (int base = 0; base + 2 < state.length; base += FLOW_CHANNELS) {
            float vx = state[base];
            float vy = state[base + 1];
            float vz = state[base + 2];
            float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (Float.isFinite(speed) && speed > maxSpeed) {
                maxSpeed = speed;
            }
        }
        return maxSpeed;
    }

    private BlockPos brickOrigin(BlockPos pos) {
        return new BlockPos(
                Math.floorDiv(pos.getX(), BRICK_SIZE) * BRICK_SIZE,
                Math.floorDiv(pos.getY(), BRICK_SIZE) * BRICK_SIZE,
                Math.floorDiv(pos.getZ(), BRICK_SIZE) * BRICK_SIZE
        );
    }

    private BlockPos brickOrigin(int brickX, int brickY, int brickZ) {
        return new BlockPos(
                brickX * BRICK_SIZE,
                brickY * BRICK_SIZE,
                brickZ * BRICK_SIZE
        );
    }

    private int cellIndex(int x, int y, int z) {
        return cellIndex(BRICK_SIZE, x, y, z);
    }

    private static int cellIndex(int gridSize, int x, int y, int z) {
        return (x * gridSize + y) * gridSize + z;
    }

    private static int packDebugVoxel(int x, int y, int z) {
        return (x & 0xFF)
                | ((y & 0xFF) << 8)
                | ((z & 0xFF) << 16);
    }

    private long worldKey(Identifier dimensionId) {
        long value = dimensionId.hashCode();
        return value == 0L ? 1L : value;
    }

    private record StaticBrickCacheKey(Identifier dimensionId, int brickX, int brickY, int brickZ) {
    }

    private record StaticCellSample(
            boolean solid,
            byte surfaceKind,
            short openFaceMask,
            float emitterPowerWatts,
            byte sourceFanDirection,
            float sourceEmitterPowerWatts
    ) {
    }

    private record StaticBrickSnapshot(
            byte[] obstacle,
            byte[] surfaceKind,
            short[] openFaceMask,
            float[] emitterPower,
            byte[] sourceFanDirection,
            float[] sourceEmitterPower
    ) {
        static StaticBrickSnapshot copyFrom(
                byte[] obstacle,
                byte[] surfaceKind,
                short[] openFaceMask,
                float[] emitterPower,
                byte[] sourceFanDirection,
                float[] sourceEmitterPower
        ) {
            return new StaticBrickSnapshot(
                    java.util.Arrays.copyOf(obstacle, obstacle.length),
                    java.util.Arrays.copyOf(surfaceKind, surfaceKind.length),
                    java.util.Arrays.copyOf(openFaceMask, openFaceMask.length),
                    java.util.Arrays.copyOf(emitterPower, emitterPower.length),
                    java.util.Arrays.copyOf(sourceFanDirection, sourceFanDirection.length),
                    java.util.Arrays.copyOf(sourceEmitterPower, sourceEmitterPower.length)
            );
        }

        void copyInto(
                byte[] outObstacle,
                byte[] outSurfaceKind,
                short[] outOpenFaceMask,
                float[] outEmitterPower,
                byte[] outSourceFanDirection,
                float[] outSourceEmitterPower
        ) {
            System.arraycopy(obstacle, 0, outObstacle, 0, Math.min(obstacle.length, outObstacle.length));
            System.arraycopy(surfaceKind, 0, outSurfaceKind, 0, Math.min(surfaceKind.length, outSurfaceKind.length));
            System.arraycopy(openFaceMask, 0, outOpenFaceMask, 0, Math.min(openFaceMask.length, outOpenFaceMask.length));
            System.arraycopy(emitterPower, 0, outEmitterPower, 0, Math.min(emitterPower.length, outEmitterPower.length));
            System.arraycopy(
                    sourceFanDirection,
                    0,
                    outSourceFanDirection,
                    0,
                    Math.min(sourceFanDirection.length, outSourceFanDirection.length)
            );
            System.arraycopy(
                    sourceEmitterPower,
                    0,
                    outSourceEmitterPower,
                    0,
                    Math.min(sourceEmitterPower.length, outSourceEmitterPower.length)
            );
        }
    }

    private record PendingSourcePatch(BlockPos pos, BlockState oldState, BlockState newState) {
    }

    private enum BrickPreparationResult {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    private enum BoundaryReferenceBuildResult {
        IN_PROGRESS,
        COMPLETED,
        WAITING_FOR_COARSE
    }

    private interface WorkerCommand {
    }

    private record ActiveHintsCommand(long worldKey, int[] activeHintCoords) implements WorkerCommand {
    }

    private record WorldDeltasCommand(long worldKey,
                                      NativeSimulationBridge.WorldDelta[] deltas) implements WorkerCommand {
    }

    private record BrickSeedCommand(
            long worldKey,
            int brickX,
            int brickY,
            int brickZ,
            byte[] obstacle,
            float[] flowState
    ) implements WorkerCommand {
    }

    private record DebugRegionSolveCommand(
            long worldKey,
            Identifier dimensionId,
            BlockPos origin,
            int gridSize,
            int sampleStride,
            int steps,
            int solidCells,
            float cellSizeBlocks,
            int[] packedSolidVoxels,
            byte[] obstacle,
            float[] flowState,
            float inletVx,
            float inletVy,
            float inletVz
    ) implements WorkerCommand {
    }

    private record DebugRegionStartCommand(
            long worldKey,
            Identifier dimensionId,
            BlockPos origin,
            int gridSize,
            int sampleStride,
            int initialSteps,
            int solidCells,
            int entityCells,
            boolean includeEntities,
            float cellSizeBlocks,
            int[] packedSolidVoxels,
            byte[] obstacle,
            float[] flowState,
            float inletVx,
            float inletVy,
            float inletVz
    ) implements WorkerCommand {
    }

    private record DebugRegionStepCommand(
            int steps,
            int solidCells,
            int entityCells,
            boolean includeEntities,
            float cellSizeBlocks,
            int[] packedSolidVoxels,
            byte[] obstacle,
            float inletVx,
            float inletVy,
            float inletVz
    ) implements WorkerCommand {
    }

    private record DebugRegionStopCommand() implements WorkerCommand {
    }

    private record BoundaryReferenceCommand(
            long worldKey,
            int brickX,
            int brickY,
            int brickZ,
            float[] flowState,
            float maxCoarseSpeedMetersPerSecond
    ) implements WorkerCommand {
    }

    private record StepCommand(
            long worldKey,
            PublishTarget[] publishTargets,
            int stepCount,
            boolean publishQIso
    ) implements WorkerCommand {
    }

    private record ResetCommand() implements WorkerCommand {
    }

    private record CloseCommand() implements WorkerCommand {
    }

    private record PublishTarget(Identifier dimensionId, BlockPos origin, int brickX, int brickY, int brickZ) {
    }

    private record LocalAtlasSnapshot(Identifier dimensionId, BlockPos origin, int sampleStride, short[] packedFlow) {
    }

    private record LocalQIsoSnapshot(
            Identifier dimensionId,
            BlockPos origin,
            int sampleStride,
            int atlasResolution,
            int[] packedPoints,
            float[] triangleVertices,
            int[] packedSolidVoxels,
            float cellSizeBlocks,
            float threshold,
            float maxQ,
            int positiveSamples,
            boolean debug
    ) {
    }

    private record WorkerSolverKey(long worldKey, int brickX, int brickY, int brickZ) {
    }

    private record FlowBoundary(float vx, float vy, float vz) {
    }

    private record DebugInlet(float vx, float vy, float vz) {
    }

    private record QField(float[] values, float maxQ, int positiveSamples) {
    }

    private record DebugRegionSeed(
            Identifier dimensionId,
            BlockPos origin,
            int gridSize,
            int solidCells,
            int entityCells,
            boolean includeEntities,
            UUID targetEntityId,
            float cellSizeBlocks,
            int[] packedSolidVoxels,
            byte[] solidMask,
            float[] flowState
    ) {
        float spanBlocks() {
            return gridSize * cellSizeBlocks;
        }
    }

    private record DebugEntityVoxelizeResult(int entityCells, int writtenSolidVoxels) {
    }

    private record DebugQIsoLiveRequest(
            Identifier dimensionId,
            BlockPos origin,
            int gridSize,
            int solidCells,
            int entityCells,
            boolean includeEntities,
            UUID targetEntityId,
            float cellSizeBlocks,
            int stepsPerFrame,
            int intervalTicks
    ) {
        float spanBlocks() {
            return gridSize * cellSizeBlocks;
        }
    }

    private static final class WorkerSolver {
        private final long handle;
        private float inletVx;
        private float inletVy;
        private float inletVz;

        private WorkerSolver(long handle, float inletVx, float inletVy, float inletVz) {
            this.handle = handle;
            this.inletVx = inletVx;
            this.inletVy = inletVy;
            this.inletVz = inletVz;
        }
    }

    private static final class DebugRegionSolver {
        private final long handle;
        private final long worldKey;
        private final Identifier dimensionId;
        private final BlockPos origin;
        private final int gridSize;
        private final int sampleStride;
        private int solidCells;
        private int entityCells;
        private boolean includeEntities;
        private float cellSizeBlocks;
        private int[] packedSolidVoxels;
        private byte[] solidMask;
        private float inletVx;
        private float inletVy;
        private float inletVz;

        private DebugRegionSolver(
                long handle,
                long worldKey,
                Identifier dimensionId,
                BlockPos origin,
                int gridSize,
                int sampleStride,
                int solidCells,
                int entityCells,
                boolean includeEntities,
                float cellSizeBlocks,
                int[] packedSolidVoxels,
                byte[] solidMask,
                float inletVx,
                float inletVy,
                float inletVz
        ) {
            this.handle = handle;
            this.worldKey = worldKey;
            this.dimensionId = dimensionId;
            this.origin = origin;
            this.gridSize = gridSize;
            this.sampleStride = sampleStride;
            this.solidCells = solidCells;
            this.entityCells = entityCells;
            this.includeEntities = includeEntities;
            this.cellSizeBlocks = cellSizeBlocks;
            this.packedSolidVoxels = packedSolidVoxels == null
                    ? new int[0]
                    : java.util.Arrays.copyOf(packedSolidVoxels, packedSolidVoxels.length);
            this.solidMask = solidMask == null
                    ? new byte[0]
                    : java.util.Arrays.copyOf(solidMask, solidMask.length);
            this.inletVx = inletVx;
            this.inletVy = inletVy;
            this.inletVz = inletVz;
        }
    }

    private record WorkerDeltaKey(int type, int x, int y, int z, int data0) {
        static WorkerDeltaKey of(NativeSimulationBridge.WorldDelta delta) {
            return new WorkerDeltaKey(delta.type(), delta.x(), delta.y(), delta.z(), delta.data0());
        }
    }

    private final class ClientL2Worker {
        private final NativeSimulationBridge bridge = new NativeSimulationBridge();
        private final BlockingQueue<WorkerCommand> commands = new ArrayBlockingQueue<>(WORKER_QUEUE_CAPACITY);
        private final ConcurrentLinkedQueue<LocalAtlasSnapshot> atlases = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<LocalQIsoSnapshot> qIsoSnapshots = new ConcurrentLinkedQueue<>();
        private final LinkedHashMap<WorkerSolverKey, WorkerSolver> solvers = new LinkedHashMap<>();
        private DebugRegionSolver debugRegionSolver;
        private volatile boolean running;
        private volatile String lastError = "-";
        private volatile String lastRuntimeInfo = "-";
        private volatile long processedCommands;
        private volatile long droppedCommands;
        private volatile long publishedAtlases;
        private volatile long publishedQIsoSnapshots;
        private volatile long nativeQIsoSnapshots;
        private volatile long fallbackQIsoSnapshots;
        private volatile long lastStepNanos;
        private volatile long lastPublishNanos;
        private volatile long lastQIsoNanos;
        private Thread thread;

        boolean isNativeLoaded() {
            return bridge.isLoaded();
        }

        String loadError() {
            return bridge.getLoadError();
        }

        void submitActiveHints(long worldKey, int[] activeHintCoords) {
            offer(new ActiveHintsCommand(worldKey, java.util.Arrays.copyOf(activeHintCoords, activeHintCoords.length)));
        }

        void submitWorldDeltas(long worldKey, NativeSimulationBridge.WorldDelta[] deltas) {
            offer(new WorldDeltasCommand(worldKey, java.util.Arrays.copyOf(deltas, deltas.length)));
        }

        void submitBrickSeed(BrickSeedCommand command) {
            offer(command);
        }

        void submitDebugRegionSolve(DebugRegionSolveCommand command) {
            offer(command);
        }

        void submitDebugRegionStart(DebugRegionStartCommand command) {
            offer(command);
        }

        void submitDebugRegionStep(DebugRegionStepCommand command) {
            offer(command);
        }

        void submitDebugRegionStop() {
            offer(new DebugRegionStopCommand());
        }

        void submitBoundaryReference(BoundaryReferenceCommand command) {
            offer(command);
        }

        void requestStep(long worldKey, PublishTarget[] publishTargets, int stepCount, boolean publishQIso) {
            offer(new StepCommand(worldKey, publishTargets, stepCount, publishQIso));
        }

        LocalAtlasSnapshot pollAtlas() {
            return atlases.poll();
        }

        LocalQIsoSnapshot pollQIso() {
            return qIsoSnapshots.poll();
        }

        int queueSize() {
            return commands.size();
        }

        void reset() {
            commands.clear();
            atlases.clear();
            qIsoSnapshots.clear();
            if (running) {
                offer(new ResetCommand());
            }
        }

        void close() {
            commands.clear();
            atlases.clear();
            qIsoSnapshots.clear();
            if (!running) {
                releaseService();
                return;
            }
            offer(new CloseCommand());
        }

        String status() {
            return "running=" + running
                    + ",queue=" + commands.size()
                    + ",atlases=" + atlases.size()
                    + ",qIso=" + qIsoSnapshots.size()
                    + ",processed=" + processedCommands
                    + ",dropped=" + droppedCommands
                    + ",published=" + publishedAtlases
                    + ",publishedQIso=" + publishedQIsoSnapshots
                    + ",nativeQIso=" + nativeQIsoSnapshots
                    + ",fallbackQIso=" + fallbackQIsoSnapshots
                    + ",lastStepMs=" + formatMillis(lastStepNanos)
                    + ",lastPublishMs=" + formatMillis(lastPublishNanos)
                    + ",lastQIsoMs=" + formatMillis(lastQIsoNanos)
                    + ",solvers=" + solvers.size()
                    + ",debugLive=" + debugRegionStatus()
                    + ",runtime=" + lastRuntimeInfo
                    + ",error=" + lastError;
        }

        private String debugRegionStatus() {
            DebugRegionSolver solver = debugRegionSolver;
            if (solver == null) {
                return "false";
            }
            return "true:solidCells=" + solver.solidCells
                    + ":entityCells=" + solver.entityCells
                    + ":entities=" + solver.includeEntities
                    + ":cellSize=" + String.format(java.util.Locale.ROOT, "%.3f", solver.cellSizeBlocks);
        }

        private void offer(WorkerCommand command) {
            if (!bridge.isLoaded()) {
                lastError = bridge.getLoadError();
                return;
            }
            startIfNeeded();
            if (command instanceof StepCommand) {
                droppedCommands += removeQueuedStepCommands();
            } else if (command instanceof DebugRegionStepCommand) {
                droppedCommands += removeQueuedDebugRegionStepCommands();
            } else if (command instanceof WorldDeltasCommand worldDeltas) {
                command = coalesceQueuedWorldDeltas(worldDeltas);
            } else if (isPriorityCommand(command)) {
                droppedCommands += removeQueuedStepCommands();
                droppedCommands += removeQueuedDebugRegionStepCommands();
            }
            if (!commands.offer(command)) {
                if (command instanceof StepCommand) {
                    droppedCommands++;
                    return;
                }
                if (!removeOneQueuedStepCommand()) {
                    commands.poll();
                }
                droppedCommands++;
                commands.offer(command);
            }
        }

        private boolean isPriorityCommand(WorkerCommand command) {
            return command instanceof ActiveHintsCommand
                    || command instanceof WorldDeltasCommand
                    || command instanceof BrickSeedCommand
                    || command instanceof DebugRegionSolveCommand
                    || command instanceof DebugRegionStartCommand
                    || command instanceof DebugRegionStopCommand
                    || command instanceof BoundaryReferenceCommand
                    || command instanceof ResetCommand
                    || command instanceof CloseCommand;
        }

        private WorldDeltasCommand coalesceQueuedWorldDeltas(WorldDeltasCommand incoming) {
            java.util.ArrayList<NativeSimulationBridge.WorldDelta> merged = new java.util.ArrayList<>(incoming.deltas().length);
            for (WorkerCommand queued : commands.toArray(new WorkerCommand[0])) {
                if (queued instanceof WorldDeltasCommand existing
                        && existing.worldKey() == incoming.worldKey()
                        && commands.remove(queued)) {
                    java.util.Collections.addAll(merged, existing.deltas());
                }
            }
            java.util.Collections.addAll(merged, incoming.deltas());
            return new WorldDeltasCommand(incoming.worldKey(), coalesceWorldDeltas(merged));
        }

        private NativeSimulationBridge.WorldDelta[] coalesceWorldDeltas(
                java.util.List<NativeSimulationBridge.WorldDelta> deltas
        ) {
            LinkedHashMap<WorkerDeltaKey, NativeSimulationBridge.WorldDelta> byCell = new LinkedHashMap<>();
            for (NativeSimulationBridge.WorldDelta delta : deltas) {
                WorkerDeltaKey key = WorkerDeltaKey.of(delta);
                NativeSimulationBridge.WorldDelta existing = byCell.get(key);
                if (existing == null) {
                    byCell.put(key, delta);
                    continue;
                }
                if (delta.type() == NativeSimulationBridge.WORLD_DELTA_BRICK_STATIC_SOURCE_PATCH) {
                    int packedState = (existing.data1() & 0x000001)
                            | (delta.data1() & 0x000002)
                            | (existing.data1() & 0x00FF00)
                            | (delta.data1() & 0xFF0000);
                    byCell.put(key, new NativeSimulationBridge.WorldDelta(
                            delta.type(),
                            delta.x(),
                            delta.y(),
                            delta.z(),
                            delta.data0(),
                            packedState,
                            delta.data2(),
                            delta.data3(),
                            existing.value0(),
                            delta.value1(),
                            delta.value2(),
                            delta.value3()
                    ));
                } else {
                    byCell.put(key, delta);
                }
            }
            return byCell.values().toArray(new NativeSimulationBridge.WorldDelta[0]);
        }

        private int removeQueuedStepCommands() {
            int removed = 0;
            for (WorkerCommand queued : commands.toArray(new WorkerCommand[0])) {
                if (queued instanceof StepCommand && commands.remove(queued)) {
                    removed++;
                }
            }
            return removed;
        }

        private boolean removeOneQueuedStepCommand() {
            for (WorkerCommand queued : commands.toArray(new WorkerCommand[0])) {
                if (queued instanceof StepCommand && commands.remove(queued)) {
                    return true;
                }
            }
            return false;
        }

        private int removeQueuedDebugRegionStepCommands() {
            int removed = 0;
            for (WorkerCommand queued : commands.toArray(new WorkerCommand[0])) {
                if (queued instanceof DebugRegionStepCommand && commands.remove(queued)) {
                    removed++;
                }
            }
            return removed;
        }

        private void startIfNeeded() {
            if (running) {
                return;
            }
            running = true;
            thread = new Thread(this::runLoop, "a4mc-client-l2-worker");
            thread.setDaemon(true);
            thread.start();
        }

        private void runLoop() {
            try {
                while (running) {
                    WorkerCommand command = commands.take();
                    processedCommands++;
                    if (command instanceof CloseCommand) {
                        running = false;
                        break;
                    }
                    if (command instanceof ResetCommand) {
                        handleReset();
                    } else if (command instanceof ActiveHintsCommand activeHints) {
                        handleActiveHints(activeHints);
                    } else if (command instanceof WorldDeltasCommand worldDeltas) {
                        handleWorldDeltas(worldDeltas);
                    } else if (command instanceof BrickSeedCommand brickSeed) {
                        handleBrickSeed(brickSeed);
                    } else if (command instanceof DebugRegionSolveCommand debugRegionSolve) {
                        handleDebugRegionSolve(debugRegionSolve);
                    } else if (command instanceof DebugRegionStartCommand debugRegionStart) {
                        handleDebugRegionStart(debugRegionStart);
                    } else if (command instanceof DebugRegionStepCommand debugRegionStep) {
                        handleDebugRegionStep(debugRegionStep);
                    } else if (command instanceof DebugRegionStopCommand) {
                        handleDebugRegionStop();
                    } else if (command instanceof BoundaryReferenceCommand boundaryReference) {
                        handleBoundaryReference(boundaryReference);
                    } else if (command instanceof StepCommand step) {
                        handleStep(step);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                LOGGER.warn("Client L2 worker stopped after unexpected error", t);
            } finally {
                releaseService();
                running = false;
            }
        }

        private void handleReset() {
            releaseService();
            atlases.clear();
            qIsoSnapshots.clear();
            debugRegionSolver = null;
            lastRuntimeInfo = "-";
            lastError = "-";
        }

        private boolean ensureRuntime(long worldKey) {
            if (!bridge.isLoaded()) {
                lastError = bridge.getLoadError();
                return false;
            }
            lastRuntimeInfo = bridge.runtimeInfo();
            return true;
        }

        private void handleActiveHints(ActiveHintsCommand command) {
            ensureRuntime(command.worldKey());
        }

        private void handleWorldDeltas(WorldDeltasCommand command) {
            ensureRuntime(command.worldKey());
        }

        private void handleBrickSeed(BrickSeedCommand command) {
            if (!ensureRuntime(command.worldKey())) {
                return;
            }
            WorkerSolverKey key = new WorkerSolverKey(
                    command.worldKey(),
                    command.brickX(),
                    command.brickY(),
                    command.brickZ()
            );
            WorkerSolver solver = solvers.get(key);
            if (solver == null) {
                long handle = bridge.createWindTunnelSolver(BRICK_SIZE, BRICK_SIZE, BRICK_SIZE, DX_METERS, DT_SECONDS);
                if (handle == 0L) {
                    lastError = "createWindTunnelSolver failed: " + bridge.windTunnelLastError();
                    return;
                }
                FlowBoundary boundary = boundaryFromFlowState(command.flowState(), command.obstacle());
                solver = new WorkerSolver(handle, boundary.vx(), boundary.vy(), boundary.vz());
                solvers.put(key, solver);
            } else {
                FlowBoundary boundary = boundaryFromFlowState(command.flowState(), command.obstacle());
                solver.inletVx = boundary.vx();
                solver.inletVy = boundary.vy();
                solver.inletVz = boundary.vz();
            }
            if (!bridge.setWindTunnelSolidMask(
                    solver.handle,
                    BRICK_SIZE,
                    BRICK_SIZE,
                    BRICK_SIZE,
                    command.obstacle()
            )) {
                lastError = "setWindTunnelSolidMask failed: " + bridge.windTunnelLastError();
                return;
            }
            if (!bridge.setWindTunnelFlowState(
                    solver.handle,
                    BRICK_SIZE,
                    BRICK_SIZE,
                    BRICK_SIZE,
                    command.flowState()
            )) {
                lastError = "setWindTunnelFlowState failed: " + bridge.windTunnelLastError();
                return;
            }
            lastRuntimeInfo = bridge.runtimeInfo();
        }

        private void handleDebugRegionSolve(DebugRegionSolveCommand command) {
            if (!ensureRuntime(command.worldKey())) {
                return;
            }
            int gridSize = Mth.clamp(command.gridSize(), DEBUG_Q_SOLVE_MIN_SIZE, DEBUG_Q_SOLVE_MAX_SIZE);
            int sampleStride = Math.max(1, command.sampleStride());
            int cells = gridSize * gridSize * gridSize;
            if (command.obstacle() == null
                    || command.obstacle().length != cells
                    || command.flowState() == null
                    || command.flowState().length != cells * FLOW_CHANNELS) {
                lastError = "debug Q solve failed: invalid solid mask or flow state";
                return;
            }
            long handle = 0L;
            try {
                handle = bridge.createWindTunnelSolver(
                        gridSize,
                        gridSize,
                        gridSize,
                        command.cellSizeBlocks(),
                        DT_SECONDS
                );
                if (handle == 0L) {
                    lastError = "debug createWindTunnelSolver failed: " + bridge.windTunnelLastError();
                    return;
                }
                if (!bridge.setWindTunnelSolidMask(
                        handle,
                        gridSize,
                        gridSize,
                        gridSize,
                        command.obstacle()
                )) {
                    lastError = "debug setWindTunnelSolidMask failed: " + bridge.windTunnelLastError();
                    return;
                }
                if (!bridge.setWindTunnelFlowState(
                        handle,
                        gridSize,
                        gridSize,
                        gridSize,
                        command.flowState()
                )) {
                    lastError = "debug setWindTunnelFlowState failed: " + bridge.windTunnelLastError();
                    return;
                }
                long stepStart = System.nanoTime();
                if (!bridge.advanceWindTunnel(
                        handle,
                        Math.max(1, command.steps()),
                        command.inletVx(),
                        command.inletVy(),
                        command.inletVz(),
                        AerodynamicSolver.DEFAULT_AIR_DENSITY_KG_M3,
                        AerodynamicSolver.DEFAULT_AIR_KINEMATIC_VISCOSITY_M2_S
                )) {
                    lastError = "debug advanceWindTunnel failed: " + bridge.windTunnelLastError();
                    return;
                }
                lastStepNanos = System.nanoTime() - stepStart;

                PublishTarget target = new PublishTarget(command.dimensionId(), command.origin(), 0, 0, 0);
                long qStart = System.nanoTime();
                LocalQIsoSnapshot qSnapshot = buildNativeQIsoSnapshot(
                        handle,
                        target,
                        gridSize,
                        sampleStride,
                        command.cellSizeBlocks(),
                        true
                );
                if (qSnapshot == null) {
                    float[] flowAtlas = new float[packedValueCount(gridSize, sampleStride)];
                    if (!bridge.extractWindTunnelFlowAtlas(
                            handle,
                            gridSize,
                            gridSize,
                            gridSize,
                            sampleStride,
                            flowAtlas
                    )) {
                        lastError = "debug extractWindTunnelFlowAtlas failed: " + bridge.windTunnelLastError();
                        return;
                    }
                    qSnapshot = buildQIsoSnapshot(
                            target,
                            gridSize,
                            sampleStride,
                            command.cellSizeBlocks(),
                            flowAtlas,
                            true
                    );
                    fallbackQIsoSnapshots++;
                } else {
                    nativeQIsoSnapshots++;
                }
                qSnapshot = withDebugSolidMask(qSnapshot, command.packedSolidVoxels(), command.obstacle());
                lastQIsoNanos = System.nanoTime() - qStart;
                qIsoSnapshots.offer(qSnapshot);
                publishedQIsoSnapshots++;
                lastRuntimeInfo = bridge.runtimeInfo();
                lastError = "-";
            } finally {
                if (handle != 0L) {
                    bridge.destroyWindTunnelSolver(handle);
                }
            }
        }

        private void handleDebugRegionStart(DebugRegionStartCommand command) {
            if (!ensureRuntime(command.worldKey())) {
                return;
            }
            int gridSize = Mth.clamp(command.gridSize(), DEBUG_Q_SOLVE_MIN_SIZE, DEBUG_Q_SOLVE_MAX_SIZE);
            int sampleStride = Math.max(1, command.sampleStride());
            int cells = gridSize * gridSize * gridSize;
            if (command.obstacle() == null
                    || command.obstacle().length != cells
                    || command.flowState() == null
                    || command.flowState().length != cells * FLOW_CHANNELS) {
                lastError = "debug Q live failed: invalid solid mask or flow state";
                return;
            }
            destroyDebugRegionSolver();
            long handle = bridge.createWindTunnelSolver(
                    gridSize,
                    gridSize,
                    gridSize,
                    command.cellSizeBlocks(),
                    DT_SECONDS
            );
            if (handle == 0L) {
                lastError = "debug live createWindTunnelSolver failed: " + bridge.windTunnelLastError();
                return;
            }
            if (!bridge.setWindTunnelSolidMask(
                    handle,
                    gridSize,
                    gridSize,
                    gridSize,
                    command.obstacle()
            )) {
                lastError = "debug live setWindTunnelSolidMask failed: " + bridge.windTunnelLastError();
                bridge.destroyWindTunnelSolver(handle);
                return;
            }
            if (!bridge.setWindTunnelFlowState(
                    handle,
                    gridSize,
                    gridSize,
                    gridSize,
                    command.flowState()
            )) {
                lastError = "debug live setWindTunnelFlowState failed: " + bridge.windTunnelLastError();
                bridge.destroyWindTunnelSolver(handle);
                return;
            }
            debugRegionSolver = new DebugRegionSolver(
                    handle,
                    command.worldKey(),
                    command.dimensionId(),
                    command.origin().immutable(),
                    gridSize,
                    sampleStride,
                    command.solidCells(),
                    command.entityCells(),
                    command.includeEntities(),
                    command.cellSizeBlocks(),
                    command.packedSolidVoxels(),
                    command.obstacle(),
                    command.inletVx(),
                    command.inletVy(),
                    command.inletVz()
            );
            advanceAndPublishDebugRegion(debugRegionSolver, Math.max(1, command.initialSteps()));
        }

        private void handleDebugRegionStep(DebugRegionStepCommand command) {
            DebugRegionSolver solver = debugRegionSolver;
            if (solver == null || !ensureRuntime(solver.worldKey)) {
                return;
            }
            if (!refreshDebugRegionMask(solver, command)) {
                return;
            }
            solver.inletVx = command.inletVx();
            solver.inletVy = command.inletVy();
            solver.inletVz = command.inletVz();
            advanceAndPublishDebugRegion(solver, Math.max(1, command.steps()));
        }

        private boolean refreshDebugRegionMask(DebugRegionSolver solver, DebugRegionStepCommand command) {
            if (command.obstacle() == null) {
                return true;
            }
            int cells = solver.gridSize * solver.gridSize * solver.gridSize;
            if (command.obstacle().length != cells) {
                lastError = "debug live mask refresh failed: invalid solid mask";
                return false;
            }
            if (!bridge.setWindTunnelSolidMask(
                    solver.handle,
                    solver.gridSize,
                    solver.gridSize,
                    solver.gridSize,
                    command.obstacle()
            )) {
                lastError = "debug live setWindTunnelSolidMask refresh failed: " + bridge.windTunnelLastError();
                return false;
            }
            solver.solidCells = Math.max(0, command.solidCells());
            solver.entityCells = Math.max(0, command.entityCells());
            solver.includeEntities = command.includeEntities();
            solver.cellSizeBlocks = command.cellSizeBlocks();
            solver.packedSolidVoxels = command.packedSolidVoxels() == null
                    ? new int[0]
                    : java.util.Arrays.copyOf(command.packedSolidVoxels(), command.packedSolidVoxels().length);
            solver.solidMask = java.util.Arrays.copyOf(command.obstacle(), command.obstacle().length);
            return true;
        }

        private void handleDebugRegionStop() {
            destroyDebugRegionSolver();
        }

        private void advanceAndPublishDebugRegion(DebugRegionSolver solver, int steps) {
            long stepStart = System.nanoTime();
            if (!bridge.advanceWindTunnel(
                    solver.handle,
                    steps,
                    solver.inletVx,
                    solver.inletVy,
                    solver.inletVz,
                    AerodynamicSolver.DEFAULT_AIR_DENSITY_KG_M3,
                    AerodynamicSolver.DEFAULT_AIR_KINEMATIC_VISCOSITY_M2_S
            )) {
                lastError = "debug live advanceWindTunnel failed: " + bridge.windTunnelLastError();
                return;
            }
            lastStepNanos = System.nanoTime() - stepStart;

            PublishTarget target = new PublishTarget(solver.dimensionId, solver.origin, 0, 0, 0);
            long qStart = System.nanoTime();
            LocalQIsoSnapshot qSnapshot = buildNativeQIsoSnapshot(
                    solver.handle,
                    target,
                    solver.gridSize,
                    solver.sampleStride,
                    solver.cellSizeBlocks,
                    true
            );
            if (qSnapshot == null) {
                float[] flowAtlas = new float[packedValueCount(solver.gridSize, solver.sampleStride)];
                if (!bridge.extractWindTunnelFlowAtlas(
                        solver.handle,
                        solver.gridSize,
                        solver.gridSize,
                        solver.gridSize,
                        solver.sampleStride,
                        flowAtlas
                )) {
                    lastError = "debug live extractWindTunnelFlowAtlas failed: " + bridge.windTunnelLastError();
                    return;
                }
                qSnapshot = buildQIsoSnapshot(
                        target,
                        solver.gridSize,
                        solver.sampleStride,
                        solver.cellSizeBlocks,
                        flowAtlas,
                        true
                );
                fallbackQIsoSnapshots++;
            } else {
                nativeQIsoSnapshots++;
            }
            qSnapshot = withDebugSolidMask(qSnapshot, solver.packedSolidVoxels, solver.solidMask);
            lastQIsoNanos = System.nanoTime() - qStart;
            qIsoSnapshots.offer(qSnapshot);
            publishedQIsoSnapshots++;
            lastRuntimeInfo = bridge.runtimeInfo();
            lastError = "-";
        }

        private void handleBoundaryReference(BoundaryReferenceCommand command) {
            if (!ensureRuntime(command.worldKey())) {
                return;
            }
            WorkerSolver solver = solvers.get(new WorkerSolverKey(
                    command.worldKey(),
                    command.brickX(),
                    command.brickY(),
                    command.brickZ()
            ));
            if (solver == null) {
                return;
            }
            FlowBoundary boundary = boundaryFromFlowState(command.flowState(), null);
            if (command.maxCoarseSpeedMetersPerSecond() >= COARSE_RESEED_MIN_SPEED_MPS) {
                if (!bridge.setWindTunnelFlowState(
                        solver.handle,
                        BRICK_SIZE,
                        BRICK_SIZE,
                        BRICK_SIZE,
                        command.flowState()
                )) {
                    lastError = "boundary setWindTunnelFlowState failed: " + bridge.windTunnelLastError();
                    return;
                }
            }
            solver.inletVx = boundary.vx();
            solver.inletVy = boundary.vy();
            solver.inletVz = boundary.vz();
            lastRuntimeInfo = bridge.runtimeInfo();
        }

        private void handleStep(StepCommand command) {
            if (!ensureRuntime(command.worldKey())) {
                return;
            }
            long start = System.nanoTime();
            int steps = Math.max(1, command.stepCount());
            for (Map.Entry<WorkerSolverKey, WorkerSolver> entry : solvers.entrySet()) {
                if (entry.getKey().worldKey() != command.worldKey()) {
                    continue;
                }
                WorkerSolver solver = entry.getValue();
                if (!bridge.advanceWindTunnel(
                        solver.handle,
                        steps,
                        solver.inletVx,
                        solver.inletVy,
                        solver.inletVz,
                        AerodynamicSolver.DEFAULT_AIR_DENSITY_KG_M3,
                        AerodynamicSolver.DEFAULT_AIR_KINEMATIC_VISCOSITY_M2_S
                )) {
                    lastError = "advanceWindTunnel failed: " + bridge.windTunnelLastError();
                    return;
                }
            }
            lastStepNanos = System.nanoTime() - start;
            if (command.publishTargets().length > 0) {
                publishTargets(command.worldKey(), command.publishTargets(), command.publishQIso());
            }
            lastRuntimeInfo = bridge.runtimeInfo();
        }

        private void publishTargets(long worldKey, PublishTarget[] targets, boolean publishQIso) {
            long start = System.nanoTime();
            for (PublishTarget target : targets) {
                int sampleStride = LOCAL_PUBLISH_SAMPLE_STRIDE;
                short[] packedFlow = new short[packedValueCount(BRICK_SIZE, sampleStride)];
                float[] flowAtlas = new float[packedFlow.length];
                WorkerSolver solver = solvers.get(new WorkerSolverKey(
                        worldKey,
                        target.brickX(),
                        target.brickY(),
                        target.brickZ()
                ));
                if (solver == null) {
                    continue;
                }
                if (!bridge.extractWindTunnelFlowAtlas(
                        solver.handle,
                        BRICK_SIZE,
                        BRICK_SIZE,
                        BRICK_SIZE,
                        sampleStride,
                        flowAtlas
                )) {
                    lastError = "extractWindTunnelFlowAtlas failed: " + bridge.windTunnelLastError();
                    continue;
                }
                if (publishQIso) {
                    long qStart = System.nanoTime();
                    LocalQIsoSnapshot qSnapshot = buildNativeQIsoSnapshot(solver.handle, target, sampleStride);
                    if (qSnapshot == null) {
                        qSnapshot = buildQIsoSnapshot(target, sampleStride, flowAtlas);
                        fallbackQIsoSnapshots++;
                    } else {
                        nativeQIsoSnapshots++;
                    }
                    lastQIsoNanos = System.nanoTime() - qStart;
                    qIsoSnapshots.offer(qSnapshot);
                    publishedQIsoSnapshots++;
                }
                packFlowAtlas(flowAtlas, packedFlow);
                atlases.offer(new LocalAtlasSnapshot(target.dimensionId(), target.origin(), sampleStride, packedFlow));
                publishedAtlases++;
            }
            lastPublishNanos = System.nanoTime() - start;
        }

        private LocalQIsoSnapshot buildNativeQIsoSnapshot(
                long solverHandle,
                PublishTarget target,
                int sampleStride
        ) {
            return buildNativeQIsoSnapshot(solverHandle, target, BRICK_SIZE, sampleStride, 1.0f, false);
        }

        private LocalQIsoSnapshot buildNativeQIsoSnapshot(
                long solverHandle,
                PublishTarget target,
                int gridSize,
                int sampleStride,
                float cellSizeBlocks,
                boolean debug
        ) {
            int atlasResolution = (gridSize + sampleStride - 1) / sampleStride;
            if (qIsoDisplayMode == QIsoDisplayMode.VOXELS) {
                return buildNativeQIsoPointSnapshot(
                        solverHandle,
                        target,
                        gridSize,
                        sampleStride,
                        cellSizeBlocks,
                        debug,
                        atlasResolution
                );
            }
            NativeSimulationBridge.WindTunnelQCriterionIsoTriangles nativeQ =
                    bridge.extractWindTunnelQCriterionIsoTriangles(
                            solverHandle,
                            gridSize,
                            gridSize,
                            gridSize,
                            sampleStride,
                            qIsoFixedThreshold,
                            qIsoAutoThresholdFraction,
                            Q_ISO_SCALE,
                            Q_ISO_MAX_TRIANGLES
                    );
            if (nativeQ == null) {
                return null;
            }
            float[] triangleVertices = nativeQ.triangleVertices() == null ? new float[0] : nativeQ.triangleVertices();
            return new LocalQIsoSnapshot(
                    target.dimensionId(),
                    target.origin(),
                    sampleStride,
                    atlasResolution,
                    new int[0],
                    triangleVertices,
                    new int[0],
                    cellSizeBlocks,
                    nativeQ.threshold(),
                    nativeQ.maxQ(),
                    nativeQ.positiveSamples(),
                    debug
            );
        }

        private LocalQIsoSnapshot buildNativeQIsoPointSnapshot(
                long solverHandle,
                PublishTarget target,
                int gridSize,
                int sampleStride,
                float cellSizeBlocks,
                boolean debug,
                int atlasResolution
        ) {
            NativeSimulationBridge.WindTunnelQCriterionIsoPoints nativeQ =
                    bridge.extractWindTunnelQCriterionIsoPoints(
                            solverHandle,
                            gridSize,
                            gridSize,
                            gridSize,
                            sampleStride,
                            qIsoFixedThreshold,
                            qIsoAutoThresholdFraction,
                            Q_ISO_SCALE,
                            Q_ISO_MAX_POINTS
                    );
            if (nativeQ == null) {
                return null;
            }
            int[] packedPoints = nativeQ.packedPoints() == null ? new int[0] : nativeQ.packedPoints();
            return new LocalQIsoSnapshot(
                    target.dimensionId(),
                    target.origin(),
                    sampleStride,
                    atlasResolution,
                    packedPoints,
                    new float[0],
                    new int[0],
                    cellSizeBlocks,
                    nativeQ.threshold(),
                    nativeQ.maxQ(),
                    nativeQ.positiveSamples(),
                    debug
            );
        }

        private LocalQIsoSnapshot withDebugSolidMask(
                LocalQIsoSnapshot snapshot,
                int[] packedSolidVoxels,
                byte[] solidMask
        ) {
            if (snapshot == null) {
                return null;
            }
            int[] packedSolidCopy = packedSolidVoxels == null
                    ? new int[0]
                    : java.util.Arrays.copyOf(packedSolidVoxels, packedSolidVoxels.length);
            LocalQIsoSnapshot withMask = new LocalQIsoSnapshot(
                    snapshot.dimensionId(),
                    snapshot.origin(),
                    snapshot.sampleStride(),
                    snapshot.atlasResolution(),
                    snapshot.packedPoints(),
                    snapshot.triangleVertices(),
                    packedSolidCopy,
                    snapshot.cellSizeBlocks(),
                    snapshot.threshold(),
                    snapshot.maxQ(),
                    snapshot.positiveSamples(),
                    snapshot.debug()
            );
            return filterDebugQIsoNearSolid(withMask, solidMask);
        }

        private LocalQIsoSnapshot filterDebugQIsoNearSolid(LocalQIsoSnapshot snapshot, byte[] solidMask) {
            int rejectCells = qIsoSolidRejectCells;
            if (!snapshot.debug()
                    || rejectCells <= 0
                    || solidMask == null
                    || solidMask.length == 0
                    || (snapshot.packedPoints().length == 0 && snapshot.triangleVertices().length == 0)) {
                return snapshot;
            }
            int maskResolution = cubeResolution(solidMask.length);
            if (maskResolution <= 0) {
                return snapshot;
            }
            boolean[] rejectMask = buildSolidRejectMask(solidMask, maskResolution, rejectCells);
            int[] points = filterQIsoPointsNearSolid(
                    snapshot.packedPoints(),
                    snapshot.sampleStride(),
                    rejectMask,
                    maskResolution
            );
            float[] triangles = filterQIsoTrianglesNearSolid(
                    snapshot.triangleVertices(),
                    rejectMask,
                    maskResolution
            );
            if (points == snapshot.packedPoints() && triangles == snapshot.triangleVertices()) {
                return snapshot;
            }
            return new LocalQIsoSnapshot(
                    snapshot.dimensionId(),
                    snapshot.origin(),
                    snapshot.sampleStride(),
                    snapshot.atlasResolution(),
                    points,
                    triangles,
                    snapshot.packedSolidVoxels(),
                    snapshot.cellSizeBlocks(),
                    snapshot.threshold(),
                    snapshot.maxQ(),
                    snapshot.positiveSamples(),
                    snapshot.debug()
            );
        }

        private int cubeResolution(int cells) {
            int resolution = (int) Math.round(Math.cbrt(cells));
            return resolution > 0 && resolution * resolution * resolution == cells ? resolution : -1;
        }

        private boolean[] buildSolidRejectMask(byte[] solidMask, int resolution, int rejectCells) {
            boolean[] rejectMask = new boolean[solidMask.length];
            int radius = Mth.clamp(rejectCells, 0, Q_ISO_SOLID_REJECT_MAX_CELLS);
            for (int x = 0; x < resolution; x++) {
                for (int y = 0; y < resolution; y++) {
                    for (int z = 0; z < resolution; z++) {
                        int cell = cellIndex(resolution, x, y, z);
                        if (solidMask[cell] == 0) {
                            continue;
                        }
                        for (int dx = -radius; dx <= radius; dx++) {
                            int nx = x + dx;
                            if (nx < 0 || nx >= resolution) {
                                continue;
                            }
                            for (int dy = -radius; dy <= radius; dy++) {
                                int ny = y + dy;
                                if (ny < 0 || ny >= resolution) {
                                    continue;
                                }
                                for (int dz = -radius; dz <= radius; dz++) {
                                    int nz = z + dz;
                                    if (nz < 0 || nz >= resolution) {
                                        continue;
                                    }
                                    rejectMask[cellIndex(resolution, nx, ny, nz)] = true;
                                }
                            }
                        }
                    }
                }
            }
            return rejectMask;
        }

        private int[] filterQIsoPointsNearSolid(
                int[] packedPoints,
                int sampleStride,
                boolean[] rejectMask,
                int maskResolution
        ) {
            if (packedPoints.length == 0) {
                return packedPoints;
            }
            int[] filtered = new int[packedPoints.length];
            int written = 0;
            int stride = Math.max(1, sampleStride);
            for (int packedPoint : packedPoints) {
                int x = Mth.clamp((packedPoint & 0xFF) * stride, 0, maskResolution - 1);
                int y = Mth.clamp(((packedPoint >>> 8) & 0xFF) * stride, 0, maskResolution - 1);
                int z = Mth.clamp(((packedPoint >>> 16) & 0xFF) * stride, 0, maskResolution - 1);
                if (rejectMask[cellIndex(maskResolution, x, y, z)]) {
                    continue;
                }
                filtered[written++] = packedPoint;
            }
            return written == packedPoints.length ? packedPoints : java.util.Arrays.copyOf(filtered, written);
        }

        private float[] filterQIsoTrianglesNearSolid(
                float[] triangleVertices,
                boolean[] rejectMask,
                int maskResolution
        ) {
            if (triangleVertices.length < 9) {
                return triangleVertices;
            }
            float[] filtered = new float[triangleVertices.length];
            int written = 0;
            for (int i = 0; i + 8 < triangleVertices.length; i += 9) {
                if (qIsoVertexRejected(triangleVertices[i], triangleVertices[i + 1], triangleVertices[i + 2], rejectMask, maskResolution)
                        || qIsoVertexRejected(triangleVertices[i + 3], triangleVertices[i + 4], triangleVertices[i + 5], rejectMask, maskResolution)
                        || qIsoVertexRejected(triangleVertices[i + 6], triangleVertices[i + 7], triangleVertices[i + 8], rejectMask, maskResolution)) {
                    continue;
                }
                System.arraycopy(triangleVertices, i, filtered, written, 9);
                written += 9;
            }
            return written == triangleVertices.length
                    ? triangleVertices
                    : java.util.Arrays.copyOf(filtered, written);
        }

        private boolean qIsoVertexRejected(
                float x,
                float y,
                float z,
                boolean[] rejectMask,
                int maskResolution
        ) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                return true;
            }
            int ix = Mth.clamp((int) Math.floor(x), 0, maskResolution - 1);
            int iy = Mth.clamp((int) Math.floor(y), 0, maskResolution - 1);
            int iz = Mth.clamp((int) Math.floor(z), 0, maskResolution - 1);
            return rejectMask[cellIndex(maskResolution, ix, iy, iz)];
        }

        private int packedValueCount(int brickSize, int sampleStride) {
            int atlasResolution = (brickSize + sampleStride - 1) / sampleStride;
            return atlasResolution * atlasResolution * atlasResolution * PACKED_CHANNELS;
        }

        private FlowBoundary boundaryFromFlowState(float[] flowState, byte[] solidMask) {
            if (flowState == null || flowState.length < FLOW_CHANNELS) {
                return new FlowBoundary(0.0f, 0.0f, 0.0f);
            }
            float sumX = 0.0f;
            float sumY = 0.0f;
            float sumZ = 0.0f;
            int samples = 0;
            int cellCount = Math.min(flowState.length / FLOW_CHANNELS, solidMask == null ? CELL_COUNT : solidMask.length);
            for (int cell = 0; cell < cellCount; cell++) {
                if (solidMask != null && solidMask[cell] != 0) {
                    continue;
                }
                int base = cell * FLOW_CHANNELS;
                float vx = flowState[base];
                float vy = flowState[base + 1];
                float vz = flowState[base + 2];
                if (!Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(vz)) {
                    continue;
                }
                sumX += vx;
                sumY += vy;
                sumZ += vz;
                samples++;
            }
            if (samples <= 0) {
                return new FlowBoundary(0.0f, 0.0f, 0.0f);
            }
            float inv = 1.0f / samples;
            return new FlowBoundary(sumX * inv, sumY * inv, sumZ * inv);
        }

        private void packFlowAtlas(float[] flowAtlas, short[] packedFlow) {
            int values = Math.min(flowAtlas.length, packedFlow.length);
            for (int base = 0; base + 3 < values; base += FLOW_CHANNELS) {
                packedFlow[base] = quantizeSignedToShort(flowAtlas[base], ATLAS_VELOCITY_RANGE);
                packedFlow[base + 1] = quantizeSignedToShort(flowAtlas[base + 1], ATLAS_VELOCITY_RANGE);
                packedFlow[base + 2] = quantizeSignedToShort(flowAtlas[base + 2], ATLAS_VELOCITY_RANGE);
                packedFlow[base + 3] = quantizeSignedToShort(flowAtlas[base + 3], ATLAS_PRESSURE_RANGE);
            }
        }

        private LocalQIsoSnapshot buildQIsoSnapshot(PublishTarget target, int sampleStride, float[] flowAtlas) {
            return buildQIsoSnapshot(target, BRICK_SIZE, sampleStride, 1.0f, flowAtlas, false);
        }

        private LocalQIsoSnapshot buildQIsoSnapshot(
                PublishTarget target,
                int gridSize,
                int sampleStride,
                float cellSizeBlocks,
                float[] flowAtlas,
                boolean debug
        ) {
            int atlasResolution = (gridSize + sampleStride - 1) / sampleStride;
            int cells = atlasResolution * atlasResolution * atlasResolution;
            if (atlasResolution < 3 || flowAtlas == null || flowAtlas.length < cells * FLOW_CHANNELS) {
                return emptyQIsoSnapshot(target, sampleStride, atlasResolution, cellSizeBlocks, debug);
            }
            QField qField = buildQField(atlasResolution, sampleStride, cellSizeBlocks, flowAtlas);
            float[] qValues = qField.values();
            float maxQ = qField.maxQ();
            int positiveSamples = qField.positiveSamples();
            float fixedThreshold = qIsoFixedThreshold;
            float threshold = fixedThreshold > 0.0f
                    ? fixedThreshold
                    : maxQ * qIsoAutoThresholdFraction;
            if (!(threshold > 0.0f) || !(maxQ > threshold) || positiveSamples <= 0) {
                return emptyQIsoSnapshot(
                        target,
                        sampleStride,
                        atlasResolution,
                        cellSizeBlocks,
                        threshold,
                        maxQ,
                        positiveSamples,
                        debug
                );
            }
            int aboveThreshold = 0;
            for (float q : qValues) {
                if (q > threshold) {
                    aboveThreshold++;
                }
            }
            if (aboveThreshold <= 0) {
                return emptyQIsoSnapshot(
                        target,
                        sampleStride,
                        atlasResolution,
                        cellSizeBlocks,
                        threshold,
                        maxQ,
                        positiveSamples,
                        debug
                );
            }
            float[] triangleVertices = qIsoDisplayMode == QIsoDisplayMode.VOXELS
                    ? new float[0]
                    : buildQIsoSurfaceTriangles(qValues, atlasResolution, sampleStride, threshold);
            int keepEvery = Math.max(1, (aboveThreshold + Q_ISO_MAX_POINTS - 1) / Q_ISO_MAX_POINTS);
            int[] packed = new int[Math.min(aboveThreshold, Q_ISO_MAX_POINTS)];
            int seen = 0;
            int written = 0;
            float invRange = 1.0f / Math.max(maxQ - threshold, 1.0e-12f);
            for (int x = 1; x < atlasResolution - 1; x++) {
                for (int y = 1; y < atlasResolution - 1; y++) {
                    for (int z = 1; z < atlasResolution - 1; z++) {
                        float q = qValues[atlasCellIndex(atlasResolution, x, y, z)];
                        if (q <= threshold) {
                            continue;
                        }
                        if ((seen++ % keepEvery) != 0) {
                            continue;
                        }
                        int strength = Mth.clamp(Math.round((q - threshold) * invRange * 255.0f), 1, 255);
                        packed[written++] = packQIsoPoint(x, y, z, strength);
                        if (written >= packed.length) {
                            return new LocalQIsoSnapshot(
                                    target.dimensionId(),
                                    target.origin(),
                                    sampleStride,
                                    atlasResolution,
                                    packed,
                                    triangleVertices,
                                    new int[0],
                                    cellSizeBlocks,
                                    threshold,
                                    maxQ,
                                    positiveSamples,
                                    debug
                            );
                        }
                    }
                }
            }
            if (written < packed.length) {
                packed = java.util.Arrays.copyOf(packed, written);
            }
            return new LocalQIsoSnapshot(
                    target.dimensionId(),
                    target.origin(),
                    sampleStride,
                    atlasResolution,
                    packed,
                    triangleVertices,
                    new int[0],
                    cellSizeBlocks,
                    threshold,
                    maxQ,
                    positiveSamples,
                    debug
            );
        }

        private QField buildQField(
                int atlasResolution,
                int sampleStride,
                float cellSizeBlocks,
                float[] flowAtlas
        ) {
            int cells = atlasResolution * atlasResolution * atlasResolution;
            float[] qValues = new float[cells];
            if (atlasResolution < 3 || flowAtlas == null || flowAtlas.length < cells * FLOW_CHANNELS) {
                return new QField(qValues, 0.0f, 0);
            }
            float maxQ = 0.0f;
            int positiveSamples = 0;
            float invTwoDx = 0.5f / Math.max(DX_METERS * sampleStride * cellSizeBlocks, 1.0e-6f);
            for (int x = 1; x < atlasResolution - 1; x++) {
                for (int y = 1; y < atlasResolution - 1; y++) {
                    for (int z = 1; z < atlasResolution - 1; z++) {
                        float q = qCriterionAt(flowAtlas, atlasResolution, x, y, z, invTwoDx);
                        int cell = atlasCellIndex(atlasResolution, x, y, z);
                        qValues[cell] = q;
                        if (q > 0.0f && Float.isFinite(q)) {
                            positiveSamples++;
                            if (q > maxQ) {
                                maxQ = q;
                            }
                        }
                    }
                }
            }
            return new QField(qValues, maxQ, positiveSamples);
        }

        private float[] buildQIsoSurfaceTriangles(
                float[] qValues,
                int atlasResolution,
                int sampleStride,
                float threshold
        ) {
            int cells = atlasResolution * atlasResolution * atlasResolution;
            if (atlasResolution < 4
                    || qValues == null
                    || qValues.length < cells
                    || !Float.isFinite(threshold)
                    || threshold < 0.0f
                    || Q_ISO_MAX_TRIANGLES <= 0) {
                return new float[0];
            }
            float[] vertices = new float[Q_ISO_MAX_TRIANGLES * 9];
            float[] cubeValues = new float[8];
            float[] tetraValues = new float[4];
            float[] tetraPositions = new float[12];
            float[] points = new float[12];
            int[] inside = new int[4];
            int[] outside = new int[4];
            int triangles = 0;
            int cubeStart = 1;
            int cubeEndExclusive = atlasResolution - 2;
            for (int x = cubeStart; x < cubeEndExclusive && triangles < Q_ISO_MAX_TRIANGLES; x++) {
                for (int y = cubeStart; y < cubeEndExclusive && triangles < Q_ISO_MAX_TRIANGLES; y++) {
                    for (int z = cubeStart; z < cubeEndExclusive && triangles < Q_ISO_MAX_TRIANGLES; z++) {
                        float minQ = Float.POSITIVE_INFINITY;
                        float maxQ = Float.NEGATIVE_INFINITY;
                        for (int vertex = 0; vertex < 8; vertex++) {
                            int offset = vertex * 3;
                            int vx = x + Q_ISO_CUBE_VERTEX_OFFSETS[offset];
                            int vy = y + Q_ISO_CUBE_VERTEX_OFFSETS[offset + 1];
                            int vz = z + Q_ISO_CUBE_VERTEX_OFFSETS[offset + 2];
                            float q = qValues[atlasCellIndex(atlasResolution, vx, vy, vz)];
                            cubeValues[vertex] = q;
                            minQ = Math.min(minQ, q);
                            maxQ = Math.max(maxQ, q);
                        }
                        if (!(maxQ > threshold) || !(minQ <= threshold)) {
                            continue;
                        }
                        for (int tetra = 0; tetra < Q_ISO_TETRAHEDRA.length && triangles < Q_ISO_MAX_TRIANGLES; tetra += 4) {
                            for (int i = 0; i < 4; i++) {
                                int cubeVertex = Q_ISO_TETRAHEDRA[tetra + i];
                                int cubeOffset = cubeVertex * 3;
                                int dst = i * 3;
                                tetraValues[i] = cubeValues[cubeVertex];
                                tetraPositions[dst] = x + Q_ISO_CUBE_VERTEX_OFFSETS[cubeOffset];
                                tetraPositions[dst + 1] = y + Q_ISO_CUBE_VERTEX_OFFSETS[cubeOffset + 1];
                                tetraPositions[dst + 2] = z + Q_ISO_CUBE_VERTEX_OFFSETS[cubeOffset + 2];
                            }
                            triangles = appendQIsoTetraTriangles(
                                    vertices,
                                    triangles,
                                    sampleStride,
                                    threshold,
                                    tetraValues,
                                    tetraPositions,
                                    points,
                                    inside,
                                    outside
                            );
                        }
                    }
                }
            }
            int floats = triangles * 9;
            return floats == vertices.length ? vertices : java.util.Arrays.copyOf(vertices, floats);
        }

        private int appendQIsoTetraTriangles(
                float[] out,
                int triangles,
                int sampleStride,
                float threshold,
                float[] values,
                float[] positions,
                float[] points,
                int[] inside,
                int[] outside
        ) {
            int insideCount = 0;
            int outsideCount = 0;
            for (int i = 0; i < 4; i++) {
                if (values[i] > threshold) {
                    inside[insideCount++] = i;
                } else {
                    outside[outsideCount++] = i;
                }
            }
            if (insideCount == 0 || insideCount == 4 || triangles >= Q_ISO_MAX_TRIANGLES) {
                return triangles;
            }
            if (insideCount == 1) {
                int i0 = inside[0];
                writeQIsoIntersection(values, positions, i0, outside[0], sampleStride, threshold, points, 0);
                writeQIsoIntersection(values, positions, i0, outside[1], sampleStride, threshold, points, 1);
                writeQIsoIntersection(values, positions, i0, outside[2], sampleStride, threshold, points, 2);
                return appendQIsoTriangle(out, triangles, points, 0, 1, 2);
            }
            if (insideCount == 3) {
                int o0 = outside[0];
                writeQIsoIntersection(values, positions, o0, inside[0], sampleStride, threshold, points, 0);
                writeQIsoIntersection(values, positions, o0, inside[1], sampleStride, threshold, points, 1);
                writeQIsoIntersection(values, positions, o0, inside[2], sampleStride, threshold, points, 2);
                return appendQIsoTriangle(out, triangles, points, 0, 2, 1);
            }

            int i0 = inside[0];
            int i1 = inside[1];
            int o0 = outside[0];
            int o1 = outside[1];
            writeQIsoIntersection(values, positions, i0, o0, sampleStride, threshold, points, 0);
            writeQIsoIntersection(values, positions, i1, o0, sampleStride, threshold, points, 1);
            writeQIsoIntersection(values, positions, i1, o1, sampleStride, threshold, points, 2);
            writeQIsoIntersection(values, positions, i0, o1, sampleStride, threshold, points, 3);
            triangles = appendQIsoTriangle(out, triangles, points, 0, 1, 2);
            return appendQIsoTriangle(out, triangles, points, 0, 2, 3);
        }

        private void writeQIsoIntersection(
                float[] values,
                float[] positions,
                int a,
                int b,
                int sampleStride,
                float threshold,
                float[] points,
                int pointIndex
        ) {
            float valueA = values[a];
            float valueB = values[b];
            float denom = valueB - valueA;
            float t = Math.abs(denom) <= 1.0e-12f ? 0.5f : (threshold - valueA) / denom;
            t = Mth.clamp(t, 0.0f, 1.0f);
            int aBase = a * 3;
            int bBase = b * 3;
            int dst = pointIndex * 3;
            points[dst] = (positions[aBase] + (positions[bBase] - positions[aBase]) * t) * sampleStride;
            points[dst + 1] = (positions[aBase + 1] + (positions[bBase + 1] - positions[aBase + 1]) * t) * sampleStride;
            points[dst + 2] = (positions[aBase + 2] + (positions[bBase + 2] - positions[aBase + 2]) * t) * sampleStride;
        }

        private int appendQIsoTriangle(float[] out, int triangles, float[] points, int a, int b, int c) {
            if (triangles >= Q_ISO_MAX_TRIANGLES) {
                return triangles;
            }
            int dst = triangles * 9;
            copyQIsoPoint(points, a, out, dst);
            copyQIsoPoint(points, b, out, dst + 3);
            copyQIsoPoint(points, c, out, dst + 6);
            return triangles + 1;
        }

        private void copyQIsoPoint(float[] src, int point, float[] dst, int dstBase) {
            int srcBase = point * 3;
            dst[dstBase] = src[srcBase];
            dst[dstBase + 1] = src[srcBase + 1];
            dst[dstBase + 2] = src[srcBase + 2];
        }

        private LocalQIsoSnapshot emptyQIsoSnapshot(PublishTarget target, int sampleStride, int atlasResolution) {
            return emptyQIsoSnapshot(target, sampleStride, atlasResolution, 1.0f, false);
        }

        private LocalQIsoSnapshot emptyQIsoSnapshot(
                PublishTarget target,
                int sampleStride,
                int atlasResolution,
                boolean debug
        ) {
            return emptyQIsoSnapshot(target, sampleStride, atlasResolution, 1.0f, 0.0f, 0.0f, 0, debug);
        }

        private LocalQIsoSnapshot emptyQIsoSnapshot(
                PublishTarget target,
                int sampleStride,
                int atlasResolution,
                float cellSizeBlocks,
                boolean debug
        ) {
            return emptyQIsoSnapshot(target, sampleStride, atlasResolution, cellSizeBlocks, 0.0f, 0.0f, 0, debug);
        }

        private LocalQIsoSnapshot emptyQIsoSnapshot(
                PublishTarget target,
                int sampleStride,
                int atlasResolution,
                float threshold,
                float maxQ,
                int positiveSamples
        ) {
            return emptyQIsoSnapshot(target, sampleStride, atlasResolution, 1.0f, threshold, maxQ, positiveSamples, false);
        }

        private LocalQIsoSnapshot emptyQIsoSnapshot(
                PublishTarget target,
                int sampleStride,
                int atlasResolution,
                float cellSizeBlocks,
                float threshold,
                float maxQ,
                int positiveSamples,
                boolean debug
        ) {
            return new LocalQIsoSnapshot(
                    target.dimensionId(),
                    target.origin(),
                    sampleStride,
                    atlasResolution,
                    new int[0],
                    new float[0],
                    new int[0],
                    cellSizeBlocks,
                    threshold,
                    maxQ,
                    positiveSamples,
                    debug
            );
        }

        private float qCriterionAt(
                float[] flowAtlas,
                int resolution,
                int x,
                int y,
                int z,
                float invTwoDx
        ) {
            float duDx = (velocityComponent(flowAtlas, resolution, x + 1, y, z, 0)
                    - velocityComponent(flowAtlas, resolution, x - 1, y, z, 0)) * invTwoDx;
            float duDy = (velocityComponent(flowAtlas, resolution, x, y + 1, z, 0)
                    - velocityComponent(flowAtlas, resolution, x, y - 1, z, 0)) * invTwoDx;
            float duDz = (velocityComponent(flowAtlas, resolution, x, y, z + 1, 0)
                    - velocityComponent(flowAtlas, resolution, x, y, z - 1, 0)) * invTwoDx;

            float dvDx = (velocityComponent(flowAtlas, resolution, x + 1, y, z, 1)
                    - velocityComponent(flowAtlas, resolution, x - 1, y, z, 1)) * invTwoDx;
            float dvDy = (velocityComponent(flowAtlas, resolution, x, y + 1, z, 1)
                    - velocityComponent(flowAtlas, resolution, x, y - 1, z, 1)) * invTwoDx;
            float dvDz = (velocityComponent(flowAtlas, resolution, x, y, z + 1, 1)
                    - velocityComponent(flowAtlas, resolution, x, y, z - 1, 1)) * invTwoDx;

            float dwDx = (velocityComponent(flowAtlas, resolution, x + 1, y, z, 2)
                    - velocityComponent(flowAtlas, resolution, x - 1, y, z, 2)) * invTwoDx;
            float dwDy = (velocityComponent(flowAtlas, resolution, x, y + 1, z, 2)
                    - velocityComponent(flowAtlas, resolution, x, y - 1, z, 2)) * invTwoDx;
            float dwDz = (velocityComponent(flowAtlas, resolution, x, y, z + 1, 2)
                    - velocityComponent(flowAtlas, resolution, x, y, z - 1, 2)) * invTwoDx;

            float s11 = duDx;
            float s22 = dvDy;
            float s33 = dwDz;
            float s12 = 0.5f * (duDy + dvDx);
            float s13 = 0.5f * (duDz + dwDx);
            float s23 = 0.5f * (dvDz + dwDy);

            float o12 = 0.5f * (duDy - dvDx);
            float o13 = 0.5f * (duDz - dwDx);
            float o23 = 0.5f * (dvDz - dwDy);

            float strainNorm2 = s11 * s11 + s22 * s22 + s33 * s33
                    + 2.0f * (s12 * s12 + s13 * s13 + s23 * s23);
            float rotationNorm2 = 2.0f * (o12 * o12 + o13 * o13 + o23 * o23);
            float q = 0.5f * (rotationNorm2 - strainNorm2);
            return Float.isFinite(q) ? q : 0.0f;
        }

        private float velocityComponent(float[] flowAtlas, int resolution, int x, int y, int z, int component) {
            int base = atlasCellIndex(resolution, x, y, z) * FLOW_CHANNELS + component;
            return base >= 0 && base < flowAtlas.length ? flowAtlas[base] : 0.0f;
        }

        private int atlasCellIndex(int resolution, int x, int y, int z) {
            return (x * resolution + y) * resolution + z;
        }

        private int packQIsoPoint(int x, int y, int z, int strength) {
            return (x & 0xFF)
                    | ((y & 0xFF) << 8)
                    | ((z & 0xFF) << 16)
                    | ((strength & 0xFF) << 24);
        }

        private void releaseService() {
            destroyDebugRegionSolver();
            for (WorkerSolver solver : solvers.values()) {
                bridge.destroyWindTunnelSolver(solver.handle);
            }
            solvers.clear();
        }

        private void destroyDebugRegionSolver() {
            DebugRegionSolver solver = debugRegionSolver;
            debugRegionSolver = null;
            if (solver != null) {
                bridge.destroyWindTunnelSolver(solver.handle);
            }
        }
    }

    private void resetActiveBrick() {
        activeOrigin = null;
        activeDimension = null;
        activeHintUploaded = false;
        activeBrickCount = 0;
        clearPendingStaticPatches();
        prepareCursor = 0;
        refreshCursor = 0;
        publishCursor = 0;
        java.util.Arrays.fill(activeBrickReady, false);
        java.util.Arrays.fill(activeBrickRefreshPending, false);
        java.util.Arrays.fill(activeBrickBoundaryRefreshPending, false);
        cancelStagedPreparation();
        cancelBoundaryReferenceRefresh();
        lastServerTick = Long.MIN_VALUE;
        lastProcessedClientGameTime = Long.MIN_VALUE;
        lastSolveClientGameTime = Long.MIN_VALUE;
        lastPublishedClientGameTime = Long.MIN_VALUE;
        lastBoundaryRefreshClientGameTime = Long.MIN_VALUE;
        lastStaticPatchSubmitClientGameTime = Long.MIN_VALUE;
        visualizer.clearLocalFlowFields();
    }

    public void close() {
        resetActiveBrick();
        clientSolveDisabled = false;
        staticBrickCache.clear();
        worker.close();
        fastSuspendUntilGameTime = Long.MIN_VALUE;
    }

    void releaseActivePatch() {
        resetActiveBrick();
        worker.reset();
        fastSuspendUntilGameTime = Long.MIN_VALUE;
    }

    private void clearPendingStaticPatches() {
        pendingSourcePatches.clear();
        pendingStaticPatchDimension = null;
        pendingStaticPatchWorldKey = 0L;
        pendingStaticPatchFirstChangeGameTime = Long.MIN_VALUE;
        pendingStaticPatchLastChangeGameTime = Long.MIN_VALUE;
        pendingStaticPatchSourceChanges = 0;
    }

    String status() {
        if (!experimentalEnabled) {
            return "client L2 localSolve=off mode=" + CLIENT_L2_MODE.statusName();
        }
        return "client L2 localSolve=on mode=" + CLIENT_L2_MODE.statusName()
            + " experimental=" + CLIENT_L2_MODE.experimental()
            + " streaming=" + streamingEnabled
            + " disabled=" + clientSolveDisabled
            + " brickSize=" + BRICK_SIZE
            + " cells=" + CELL_COUNT
            + " activeBricks=" + activeBrickCount
            + " worker=" + worker.status()
            + " staticCache=" + staticBrickCache.size()
            + "/" + STATIC_CACHE_MAX_BRICKS
            + " staticPatches=" + lastStaticPatchCount
            + " pendingStaticPatches=" + pendingSourcePatches.size()
            + " pendingStaticPatchChanges=" + pendingStaticPatchSourceChanges
            + " fanPatchCells=" + lastFanPatchCellCount
            + " heatPatchCells=" + lastHeatPatchCellCount
            + " stress=" + stressStatus()
            + " solveInterval=" + SOLVE_INTERVAL_TICKS
            + " publishInterval=" + LOCAL_PUBLISH_INTERVAL_TICKS
            + " publishStride=" + LOCAL_PUBLISH_SAMPLE_STRIDE
            + " maxActive=" + MAX_CLIENT_ACTIVE_BRICKS
            + " prepBudget=" + STATIC_BUILD_CELLS_PER_TICK + "/" + (STATIC_BUILD_NANOS_PER_TICK / 1000L) + "us"
            + " seedBudget=" + COARSE_SEED_CELLS_PER_TICK + "/" + (COARSE_SEED_NANOS_PER_TICK / 1000L) + "us"
            + " boundaryBudget=" + BOUNDARY_REFERENCE_CELLS_PER_TICK + "/" + (BOUNDARY_REFERENCE_NANOS_PER_TICK / 1000L) + "us"
            + " prep=" + stagedPreparationStatus()
            + " boundaryPrep=" + boundaryReferenceRefreshStatus()
            + " fastSuspendUntil=" + fastSuspendUntilGameTime
            + " lastServerTick=" + lastServerTick;
    }

    String setStressMode(String modeName) {
        StressMode requested = StressMode.parse(modeName);
        if (stressMode != requested) {
            markAllActiveBricksStaticRefreshPending();
        }
        stressMode = requested;
        stressStartedGameTime = Long.MIN_VALUE;
        lastStressSubmitGameTime = Long.MIN_VALUE;
        stressSubmittedTicks = 0L;
        stressSubmittedPatches = 0L;
        stressSubmittedFanCells = 0L;
        stressSubmittedHeatCells = 0L;
        stressSubmittedDirtyCells = 0L;
        stressStaticSubmittedForActiveSet = false;
        return "Client L2 stress " + requested.name().toLowerCase(java.util.Locale.ROOT);
    }

    String stressStatus() {
        return stressMode.name().toLowerCase(java.util.Locale.ROOT)
                + ":ticks=" + stressSubmittedTicks
                + ":patches=" + stressSubmittedPatches
                + ":fan=" + stressSubmittedFanCells
                + ":heat=" + stressSubmittedHeatCells
                + ":dirty=" + stressSubmittedDirtyCells
                + ":staticSubmitted=" + stressStaticSubmittedForActiveSet
                + ":patchesPerTick=" + STRESS_PATCHES_PER_TICK
                + ":interval=" + STRESS_INTERVAL_TICKS
                + ":queueLimit=" + STRESS_QUEUE_BACKLOG_LIMIT;
    }

    private static String formatMillis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }

    private String stagedPreparationStatus() {
        if (stagedActiveIndex < 0) {
            return "idle";
        }
        return stagedBrickX + "," + stagedBrickY + "," + stagedBrickZ
                + ":static=" + stagedStaticCursor + "/" + CELL_COUNT
                + ":seed=" + stagedSeedCursor + "/" + CELL_COUNT
                + ":staticUploaded=" + stagedStaticUploaded
                + ":dynamicUploaded=" + stagedDynamicUploaded;
    }

    private String boundaryReferenceRefreshStatus() {
        if (boundaryRefreshActiveIndex < 0) {
            return "idle";
        }
        return boundaryRefreshBrickX + "," + boundaryRefreshBrickY + "," + boundaryRefreshBrickZ
                + ":cells=" + boundaryRefreshCursor + "/" + CELL_COUNT
                + ":maxCoarse=" + String.format("%.3f", boundaryRefreshMaxCoarseSpeed);
    }

    void setExperimentalEnabled(boolean enabled) {
        if (CLIENT_L2_MODE == SolverMode.OFF) {
            enabled = false;
        }
        if (experimentalEnabled == enabled) {
            return;
        }
        experimentalEnabled = enabled;
        clientSolveDisabled = false;
        fastSuspendUntilGameTime = Long.MIN_VALUE;
        resetActiveBrick();
        if (!enabled) {
            staticBrickCache.clear();
            worker.reset();
        }
        LOGGER.info("Client L2 local solve {}", enabled ? "enabled" : "disabled");
    }

    boolean isExperimentalEnabled() {
        return experimentalEnabled;
    }

    boolean hasActivePatch() {
        return activeBrickCount > 0 && activeOrigin != null && activeDimension != null;
    }

    boolean hasReadyLocalFlow() {
        return hasActivePatch() && hasReadyActiveBrick() && lastPublishedClientGameTime != Long.MIN_VALUE;
    }

    private void disableClientSolve(Minecraft client, String reason) {
        clientSolveDisabled = true;
        resetActiveBrick();
        maybeLog(client, "disabled client L2: " + reason);
    }

    private void maybeLog(Minecraft client, String message) {
        if (client.level == null) {
            return;
        }
        long now = client.level.getGameTime();
        if (lastDiagnosticGameTime == Long.MIN_VALUE || now - lastDiagnosticGameTime >= 100) {
            LOGGER.info("Client L2 idle: {}", message);
            lastDiagnosticGameTime = now;
        }
    }
}
