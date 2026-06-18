package com.aerodynamics4mc.api;

import java.util.Arrays;

public final class AeroL2Request {
    public static final int FLOW_CHANNELS = 4;
    public static final int FLOW_VELOCITY_X = 0;
    public static final int FLOW_VELOCITY_Y = 1;
    public static final int FLOW_VELOCITY_Z = 2;
    public static final int FLOW_PRESSURE = 3;
    public static final float DEFAULT_CELL_SIZE_METERS = 1.0f;
    public static final float DEFAULT_TIME_STEP_SECONDS = 0.05f;
    public static final float DEFAULT_AIR_DENSITY_KG_M3 = 1.225f;
    public static final float DEFAULT_AIR_KINEMATIC_VISCOSITY_M2_S = 1.5e-5f;

    private final int nx;
    private final int ny;
    private final int nz;
    private final int cells;
    private final float dxMeters;
    private final float dtSeconds;
    private final int steps;
    private final int sampleStride;
    private final float inletVx;
    private final float inletVy;
    private final float inletVz;
    private final float densityKgM3;
    private final float kinematicViscosityM2S;
    private final byte[] solidMask;
    private final float[] initialFlowState;
    private final boolean outputFlowAtlas;
    private final boolean computeForceMoment;
    private final float referenceX;
    private final float referenceY;
    private final float referenceZ;

    private AeroL2Request(Builder builder) {
        int cellCount = checkedCellCount(builder.nx, builder.ny, builder.nz);
        this.nx = builder.nx;
        this.ny = builder.ny;
        this.nz = builder.nz;
        this.cells = cellCount;
        this.dxMeters = requirePositiveFinite("dxMeters", builder.dxMeters);
        this.dtSeconds = requirePositiveFinite("dtSeconds", builder.dtSeconds);
        this.steps = requirePositive("steps", builder.steps);
        this.sampleStride = requirePositive("sampleStride", builder.sampleStride);
        this.inletVx = requireFinite("inletVx", builder.inletVx);
        this.inletVy = requireFinite("inletVy", builder.inletVy);
        this.inletVz = requireFinite("inletVz", builder.inletVz);
        this.densityKgM3 = requirePositiveFinite("densityKgM3", builder.densityKgM3);
        this.kinematicViscosityM2S = requirePositiveFinite(
            "kinematicViscosityM2S",
            builder.kinematicViscosityM2S
        );
        this.solidMask = copyMask(builder.solidMask, cellCount);
        this.initialFlowState = copyFlowState(builder.initialFlowState, cellCount);
        this.outputFlowAtlas = builder.outputFlowAtlas;
        this.computeForceMoment = builder.computeForceMoment;
        this.referenceX = Float.isFinite(builder.referenceX) ? builder.referenceX : 0.5f * nx * dxMeters;
        this.referenceY = Float.isFinite(builder.referenceY) ? builder.referenceY : 0.5f * ny * dxMeters;
        this.referenceZ = Float.isFinite(builder.referenceZ) ? builder.referenceZ : 0.5f * nz * dxMeters;
    }

    public static Builder builder(int nx, int ny, int nz) {
        return new Builder(nx, ny, nz);
    }

    public int nx() {
        return nx;
    }

    public int ny() {
        return ny;
    }

    public int nz() {
        return nz;
    }

    public int cells() {
        return cells;
    }

    public float dxMeters() {
        return dxMeters;
    }

    public float dtSeconds() {
        return dtSeconds;
    }

    public int steps() {
        return steps;
    }

    public int sampleStride() {
        return sampleStride;
    }

    public float inletVx() {
        return inletVx;
    }

    public float inletVy() {
        return inletVy;
    }

    public float inletVz() {
        return inletVz;
    }

    public A4mcVec3 inletVelocity() {
        return new A4mcVec3(inletVx, inletVy, inletVz);
    }

    public float densityKgM3() {
        return densityKgM3;
    }

    public float kinematicViscosityM2S() {
        return kinematicViscosityM2S;
    }

    public boolean hasSolidMask() {
        return solidMask != null;
    }

    public byte[] solidMask() {
        return solidMask == null ? null : Arrays.copyOf(solidMask, solidMask.length);
    }

    public boolean hasInitialFlowState() {
        return initialFlowState != null;
    }

    public float[] initialFlowState() {
        return initialFlowState == null ? null : Arrays.copyOf(initialFlowState, initialFlowState.length);
    }

    public boolean outputFlowAtlas() {
        return outputFlowAtlas;
    }

    public boolean computeForceMoment() {
        return computeForceMoment;
    }

    public float referenceX() {
        return referenceX;
    }

    public float referenceY() {
        return referenceY;
    }

    public float referenceZ() {
        return referenceZ;
    }

    public A4mcVec3 forceMomentReference() {
        return new A4mcVec3(referenceX, referenceY, referenceZ);
    }

    public int flowStateValueCount() {
        return checkedFlowValueCount(cells);
    }

    public int atlasNx() {
        return atlasResolution(nx, sampleStride);
    }

    public int atlasNy() {
        return atlasResolution(ny, sampleStride);
    }

    public int atlasNz() {
        return atlasResolution(nz, sampleStride);
    }

    public int atlasCells() {
        return checkedCellCount(atlasNx(), atlasNy(), atlasNz());
    }

    public int atlasValueCount() {
        return checkedFlowValueCount(atlasCells());
    }

    public int cellIndex(int x, int y, int z) {
        return cellIndex(nx, ny, nz, x, y, z);
    }

    public int flowBase(int x, int y, int z) {
        return flowBase(nx, ny, nz, x, y, z);
    }

    public static int cellCount(int nx, int ny, int nz) {
        return checkedCellCount(nx, ny, nz);
    }

    public static int flowStateValueCount(int nx, int ny, int nz) {
        return checkedFlowValueCount(checkedCellCount(nx, ny, nz));
    }

    public static int cellIndex(int nx, int ny, int nz, int x, int y, int z) {
        checkedCellCount(nx, ny, nz);
        requireInRange("x", x, nx);
        requireInRange("y", y, ny);
        requireInRange("z", z, nz);
        return (x * ny + y) * nz + z;
    }

    public static int flowBase(int nx, int ny, int nz, int x, int y, int z) {
        return checkedFlowBase(cellIndex(nx, ny, nz, x, y, z));
    }

    public static byte[] createSolidMask(int nx, int ny, int nz) {
        return new byte[checkedCellCount(nx, ny, nz)];
    }

    public static float[] createFlowState(int nx, int ny, int nz) {
        return new float[flowStateValueCount(nx, ny, nz)];
    }

    public static void fillUniformFlow(
        float[] flowState,
        float vx,
        float vy,
        float vz,
        float pressure
    ) {
        fillUniformFlow(flowState, null, vx, vy, vz, pressure);
    }

    public static void fillUniformFlow(
        float[] flowState,
        byte[] solidMask,
        float vx,
        float vy,
        float vz,
        float pressure
    ) {
        requireFinite("vx", vx);
        requireFinite("vy", vy);
        requireFinite("vz", vz);
        requireFinite("pressure", pressure);
        int cells = checkedFlowStateCells(flowState);
        if (solidMask != null && solidMask.length != cells) {
            throw new IllegalArgumentException("solid mask length must be " + cells);
        }
        for (int cell = 0; cell < cells; cell++) {
            int base = checkedFlowBase(cell);
            if (solidMask != null && solidMask[cell] != 0) {
                flowState[base] = 0.0f;
                flowState[base + 1] = 0.0f;
                flowState[base + 2] = 0.0f;
                flowState[base + 3] = 0.0f;
                continue;
            }
            flowState[base] = vx;
            flowState[base + 1] = vy;
            flowState[base + 2] = vz;
            flowState[base + 3] = pressure;
        }
    }

    public static int atlasValueCount(int nx, int ny, int nz, int sampleStride) {
        requirePositive("sampleStride", sampleStride);
        return checkedFlowValueCount(checkedCellCount(
            atlasResolution(nx, sampleStride),
            atlasResolution(ny, sampleStride),
            atlasResolution(nz, sampleStride)
        ));
    }

    private static int atlasResolution(int cells, int sampleStride) {
        requirePositive("cells", cells);
        requirePositive("sampleStride", sampleStride);
        return (cells + sampleStride - 1) / sampleStride;
    }

    private static int checkedFlowStateCells(float[] flowState) {
        if (flowState == null || flowState.length == 0 || flowState.length % FLOW_CHANNELS != 0) {
            throw new IllegalArgumentException("flow state length must be a positive multiple of " + FLOW_CHANNELS);
        }
        return flowState.length / FLOW_CHANNELS;
    }

    private static int checkedFlowValueCount(int cells) {
        long values = (long) cells * FLOW_CHANNELS;
        if (values > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("flow value count overflows int");
        }
        return (int) values;
    }

    private static int checkedFlowBase(int cell) {
        long base = (long) cell * FLOW_CHANNELS;
        if (base > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("flow base index overflows int");
        }
        return (int) base;
    }

    private static byte[] copyMask(byte[] mask, int cells) {
        if (mask == null) {
            return null;
        }
        if (mask.length != cells) {
            throw new IllegalArgumentException("solid mask length must be " + cells);
        }
        return Arrays.copyOf(mask, mask.length);
    }

    private static float[] copyFlowState(float[] flowState, int cells) {
        if (flowState == null) {
            return null;
        }
        int expected = checkedFlowValueCount(cells);
        if (flowState.length != expected) {
            throw new IllegalArgumentException("initial flow state length must be " + expected);
        }
        return Arrays.copyOf(flowState, flowState.length);
    }

    private static int checkedCellCount(int nx, int ny, int nz) {
        requirePositive("nx", nx);
        requirePositive("ny", ny);
        requirePositive("nz", nz);
        long cells = (long) nx * (long) ny * (long) nz;
        if (cells > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("grid cell count overflows int");
        }
        return (int) cells;
    }

    private static int requireInRange(String name, int value, int limit) {
        if (value < 0 || value >= limit) {
            throw new IndexOutOfBoundsException(name + " must be in [0, " + limit + ")");
        }
        return value;
    }

    private static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static float requirePositiveFinite(String name, float value) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
        return value;
    }

    private static float requireFinite(String name, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    public static final class Builder {
        private final int nx;
        private final int ny;
        private final int nz;
        private float dxMeters = DEFAULT_CELL_SIZE_METERS;
        private float dtSeconds = DEFAULT_TIME_STEP_SECONDS;
        private int steps = 1;
        private int sampleStride = 1;
        private float inletVx;
        private float inletVy;
        private float inletVz;
        private float densityKgM3 = DEFAULT_AIR_DENSITY_KG_M3;
        private float kinematicViscosityM2S = DEFAULT_AIR_KINEMATIC_VISCOSITY_M2_S;
        private byte[] solidMask;
        private float[] initialFlowState;
        private boolean outputFlowAtlas = true;
        private boolean computeForceMoment;
        private float referenceX = Float.NaN;
        private float referenceY = Float.NaN;
        private float referenceZ = Float.NaN;

        private Builder(int nx, int ny, int nz) {
            checkedCellCount(nx, ny, nz);
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }

        public Builder cellSizeMeters(float dxMeters) {
            this.dxMeters = dxMeters;
            return this;
        }

        public Builder timeStepSeconds(float dtSeconds) {
            this.dtSeconds = dtSeconds;
            return this;
        }

        public Builder steps(int steps) {
            this.steps = steps;
            return this;
        }

        public Builder sampleStride(int sampleStride) {
            this.sampleStride = sampleStride;
            return this;
        }

        public Builder inlet(float vx, float vy, float vz) {
            this.inletVx = vx;
            this.inletVy = vy;
            this.inletVz = vz;
            return this;
        }

        public Builder inlet(A4mcVec3 velocity) {
            A4mcVec3 safeVelocity = velocity == null ? A4mcVec3.ZERO : velocity;
            return inlet((float) safeVelocity.x(), (float) safeVelocity.y(), (float) safeVelocity.z());
        }

        public Builder air(float densityKgM3, float kinematicViscosityM2S) {
            this.densityKgM3 = densityKgM3;
            this.kinematicViscosityM2S = kinematicViscosityM2S;
            return this;
        }

        public Builder solidMask(byte[] solidMask) {
            this.solidMask = solidMask;
            return this;
        }

        public Builder initialFlowState(float[] initialFlowState) {
            this.initialFlowState = initialFlowState;
            return this;
        }

        public Builder outputFlowAtlas(boolean outputFlowAtlas) {
            this.outputFlowAtlas = outputFlowAtlas;
            return this;
        }

        public Builder computeForceMoment(boolean computeForceMoment) {
            this.computeForceMoment = computeForceMoment;
            return this;
        }

        public Builder forceMomentReference(float x, float y, float z) {
            this.computeForceMoment = true;
            this.referenceX = x;
            this.referenceY = y;
            this.referenceZ = z;
            return this;
        }

        public Builder forceMomentReference(A4mcVec3 reference) {
            A4mcVec3 safeReference = reference == null ? A4mcVec3.ZERO : reference;
            return forceMomentReference((float) safeReference.x(), (float) safeReference.y(), (float) safeReference.z());
        }

        public AeroL2Request build() {
            return new AeroL2Request(this);
        }
    }
}
