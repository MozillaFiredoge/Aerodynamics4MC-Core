package com.aerodynamics4mc.api;

import java.util.Arrays;

public final class AeroL2Result {
    private final Status status;
    private final int nx;
    private final int ny;
    private final int nz;
    private final int sampleStride;
    private final float[] flowAtlas;
    private final AeroL2ForceMoment forceMoment;
    private final String message;
    private final String runtimeInfo;

    private AeroL2Result(
        Status status,
        int nx,
        int ny,
        int nz,
        int sampleStride,
        float[] flowAtlas,
        AeroL2ForceMoment forceMoment,
        String message,
        String runtimeInfo
    ) {
        this.status = status == null ? Status.FAILED : status;
        this.nx = Math.max(0, nx);
        this.ny = Math.max(0, ny);
        this.nz = Math.max(0, nz);
        this.sampleStride = Math.max(1, sampleStride);
        this.flowAtlas = flowAtlas == null ? new float[0] : Arrays.copyOf(flowAtlas, flowAtlas.length);
        this.forceMoment = forceMoment;
        this.message = message == null ? "" : message;
        this.runtimeInfo = runtimeInfo == null ? "" : runtimeInfo;
    }

    public static AeroL2Result success(
        AeroL2Request request,
        float[] flowAtlas,
        AeroL2ForceMoment forceMoment,
        String runtimeInfo
    ) {
        if (request == null) {
            return failure(null, "request must not be null", runtimeInfo);
        }
        if (request.outputFlowAtlas() && (flowAtlas == null || flowAtlas.length != request.atlasValueCount())) {
            return failure(request, "flow atlas length must be " + request.atlasValueCount(), runtimeInfo);
        }
        if (!request.outputFlowAtlas()
            && flowAtlas != null
            && flowAtlas.length != 0
            && flowAtlas.length != request.atlasValueCount()) {
            return failure(request, "flow atlas length must be 0 or " + request.atlasValueCount(), runtimeInfo);
        }
        return new AeroL2Result(
            Status.OK,
            request.nx(),
            request.ny(),
            request.nz(),
            request.sampleStride(),
            flowAtlas,
            forceMoment,
            "",
            runtimeInfo
        );
    }

    public static AeroL2Result unavailable(String message) {
        return new AeroL2Result(Status.UNAVAILABLE, 0, 0, 0, 1, null, null, message, "");
    }

    public static AeroL2Result failure(AeroL2Request request, String message, String runtimeInfo) {
        return new AeroL2Result(
            Status.FAILED,
            request == null ? 0 : request.nx(),
            request == null ? 0 : request.ny(),
            request == null ? 0 : request.nz(),
            request == null ? 1 : request.sampleStride(),
            null,
            null,
            message,
            runtimeInfo
        );
    }

    public Status status() {
        return status;
    }

    public boolean succeeded() {
        return status == Status.OK;
    }

    public boolean available() {
        return status != Status.UNAVAILABLE;
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

    public int sampleStride() {
        return sampleStride;
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
        return atlasNx() * atlasNy() * atlasNz();
    }

    public int atlasValueCount() {
        return flowAtlas.length;
    }

    public boolean hasFlowAtlas() {
        return flowAtlas.length > 0;
    }

    public float[] flowAtlas() {
        return Arrays.copyOf(flowAtlas, flowAtlas.length);
    }

    public AeroL2ForceMoment forceMoment() {
        return forceMoment;
    }

    public boolean hasForceMoment() {
        return forceMoment != null;
    }

    public String message() {
        return message;
    }

    public String runtimeInfo() {
        return runtimeInfo;
    }

    public A4mcVec3 velocityAt(int sampleX, int sampleY, int sampleZ) {
        int base = sampleBase(sampleX, sampleY, sampleZ);
        return new A4mcVec3(flowAtlas[base], flowAtlas[base + 1], flowAtlas[base + 2]);
    }

    public float pressureAt(int sampleX, int sampleY, int sampleZ) {
        return flowAtlas[sampleBase(sampleX, sampleY, sampleZ) + AeroL2Request.FLOW_PRESSURE];
    }

    public float flowValueAt(int sampleX, int sampleY, int sampleZ, int channel) {
        if (channel < 0 || channel >= AeroL2Request.FLOW_CHANNELS) {
            throw new IndexOutOfBoundsException("channel must be in [0, " + AeroL2Request.FLOW_CHANNELS + ")");
        }
        return flowAtlas[sampleBase(sampleX, sampleY, sampleZ) + channel];
    }

    private int sampleBase(int sampleX, int sampleY, int sampleZ) {
        if (!hasFlowAtlas()) {
            throw new IllegalStateException("result does not contain a flow atlas");
        }
        int ax = atlasNx();
        int ay = atlasNy();
        int az = atlasNz();
        if (sampleX < 0 || sampleX >= ax || sampleY < 0 || sampleY >= ay || sampleZ < 0 || sampleZ >= az) {
            throw new IndexOutOfBoundsException("sample coordinate outside atlas");
        }
        return ((sampleX * ay + sampleY) * az + sampleZ) * AeroL2Request.FLOW_CHANNELS;
    }

    private static int atlasResolution(int cells, int sampleStride) {
        if (cells <= 0) {
            return 0;
        }
        return (cells + sampleStride - 1) / sampleStride;
    }

    public enum Status {
        OK,
        UNAVAILABLE,
        FAILED
    }
}
