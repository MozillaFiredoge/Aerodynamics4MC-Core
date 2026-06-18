package com.aerodynamics4mc.runtime;

import java.util.Arrays;

public final class AerodynamicSolver implements AutoCloseable {
    public static final float DEFAULT_AIR_DENSITY_KG_M3 = 1.225f;
    public static final float DEFAULT_AIR_KINEMATIC_VISCOSITY_M2_S = 1.5e-5f;

    private final NativeSimulationBridge bridge;
    private final int nx;
    private final int ny;
    private final int nz;
    private final int cells;
    private final float dxMeters;
    private final float dtSeconds;
    private final float defaultReferenceX;
    private final float defaultReferenceY;
    private final float defaultReferenceZ;
    private long handle;
    private boolean closed;

    public AerodynamicSolver(int nx, int ny, int nz, float dxMeters, float dtSeconds) {
        this(new NativeSimulationBridge(), nx, ny, nz, dxMeters, dtSeconds);
    }

    public AerodynamicSolver(
        NativeSimulationBridge bridge,
        int nx,
        int ny,
        int nz,
        float dxMeters,
        float dtSeconds
    ) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge must not be null");
        }
        if (nx <= 0 || ny <= 0 || nz <= 0) {
            throw new IllegalArgumentException("grid dimensions must be positive");
        }
        long cellCount = (long) nx * (long) ny * (long) nz;
        if (cellCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("grid cell count overflows int");
        }
        if (!Float.isFinite(dxMeters) || dxMeters <= 0.0f || !Float.isFinite(dtSeconds) || dtSeconds <= 0.0f) {
            throw new IllegalArgumentException("dx/dt must be finite and positive");
        }
        this.bridge = bridge;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.cells = (int) cellCount;
        this.dxMeters = dxMeters;
        this.dtSeconds = dtSeconds;
        this.defaultReferenceX = 0.5f * nx * dxMeters;
        this.defaultReferenceY = 0.5f * ny * dxMeters;
        this.defaultReferenceZ = 0.5f * nz * dxMeters;
        this.handle = bridge.createWindTunnelSolver(nx, ny, nz, dxMeters, dtSeconds);
        if (this.handle == 0L) {
            throw new IllegalStateException(
                "failed to create native aerodynamic solver: " + bridge.windTunnelLastError()
            );
        }
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

    public void setSolidMask(byte[] solidMask) {
        ensureOpen();
        if (solidMask == null || solidMask.length != cells) {
            throw new IllegalArgumentException("solid mask length must be " + cells);
        }
        byte[] copy = Arrays.copyOf(solidMask, solidMask.length);
        if (!bridge.setWindTunnelSolidMask(handle, nx, ny, nz, copy)) {
            throw new IllegalStateException("failed to upload solid mask: " + bridge.windTunnelLastError());
        }
    }

    public void setFlowState(float[] flowState) {
        ensureOpen();
        if (flowState == null || flowState.length != cells * NativeSimulationBridge.FLOW_STATE_CHANNELS) {
            throw new IllegalArgumentException(
                "flow state length must be " + (cells * NativeSimulationBridge.FLOW_STATE_CHANNELS)
            );
        }
        float[] copy = Arrays.copyOf(flowState, flowState.length);
        if (!bridge.setWindTunnelFlowState(handle, nx, ny, nz, copy)) {
            throw new IllegalStateException("failed to upload flow state: " + bridge.windTunnelLastError());
        }
    }

    public void advance(int steps, float inletVx, float inletVy, float inletVz) {
        advance(
            steps,
            inletVx,
            inletVy,
            inletVz,
            DEFAULT_AIR_DENSITY_KG_M3,
            DEFAULT_AIR_KINEMATIC_VISCOSITY_M2_S
        );
    }

    public void advance(
        int steps,
        float inletVx,
        float inletVy,
        float inletVz,
        float densityKgM3,
        float kinematicViscosityM2S
    ) {
        ensureOpen();
        if (!bridge.advanceWindTunnel(
                handle,
                steps,
                inletVx,
                inletVy,
                inletVz,
                densityKgM3,
                kinematicViscosityM2S)) {
            throw new IllegalStateException(
                "failed to advance aerodynamic solver: " + bridge.windTunnelLastError()
            );
        }
    }

    public NativeSimulationBridge.WindTunnelForceMoment forceMoment() {
        return forceMoment(defaultReferenceX, defaultReferenceY, defaultReferenceZ);
    }

    public NativeSimulationBridge.WindTunnelForceMoment forceMoment(
        float referenceX,
        float referenceY,
        float referenceZ
    ) {
        ensureOpen();
        NativeSimulationBridge.WindTunnelForceMoment result =
            bridge.computeWindTunnelForceMoment(handle, referenceX, referenceY, referenceZ);
        if (result == null) {
            throw new IllegalStateException(
                "failed to compute aerodynamic force/moment: " + bridge.windTunnelLastError()
            );
        }
        return result;
    }

    public int flowAtlasValueCount(int sampleStride) {
        if (sampleStride <= 0) {
            throw new IllegalArgumentException("sample stride must be positive");
        }
        int atlasNx = (nx + sampleStride - 1) / sampleStride;
        int atlasNy = (ny + sampleStride - 1) / sampleStride;
        int atlasNz = (nz + sampleStride - 1) / sampleStride;
        return atlasNx * atlasNy * atlasNz * NativeSimulationBridge.FLOW_STATE_CHANNELS;
    }

    public void extractFlowAtlas(int sampleStride, float[] outFlowAtlas) {
        ensureOpen();
        int expectedValues = flowAtlasValueCount(sampleStride);
        if (outFlowAtlas == null || outFlowAtlas.length != expectedValues) {
            throw new IllegalArgumentException("flow atlas length must be " + expectedValues);
        }
        if (!bridge.extractWindTunnelFlowAtlas(handle, nx, ny, nz, sampleStride, outFlowAtlas)) {
            throw new IllegalStateException("failed to extract flow atlas: " + bridge.windTunnelLastError());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        long oldHandle = handle;
        handle = 0L;
        bridge.destroyWindTunnelSolver(oldHandle);
    }

    private void ensureOpen() {
        if (closed || handle == 0L) {
            throw new IllegalStateException("aerodynamic solver is closed");
        }
    }
}
