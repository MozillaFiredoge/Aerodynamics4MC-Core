package com.aerodynamics4mc.api;

import java.util.Objects;

public final class A4mcWorldRef {
    private final A4mcId dimensionId;
    private final Side side;
    private final Object platformHandle;

    public A4mcWorldRef(A4mcId dimensionId, Side side) {
        this(dimensionId, side, null);
    }

    public A4mcWorldRef(A4mcId dimensionId, Side side, Object platformHandle) {
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.side = side == null ? Side.UNKNOWN : side;
        this.platformHandle = platformHandle;
    }

    public static A4mcWorldRef server(A4mcId dimensionId) {
        return new A4mcWorldRef(dimensionId, Side.SERVER);
    }

    public static A4mcWorldRef server(A4mcId dimensionId, Object platformHandle) {
        return new A4mcWorldRef(dimensionId, Side.SERVER, platformHandle);
    }

    public static A4mcWorldRef client(A4mcId dimensionId) {
        return new A4mcWorldRef(dimensionId, Side.CLIENT);
    }

    public static A4mcWorldRef client(A4mcId dimensionId, Object platformHandle) {
        return new A4mcWorldRef(dimensionId, Side.CLIENT, platformHandle);
    }

    public static A4mcWorldRef unknown(A4mcId dimensionId) {
        return new A4mcWorldRef(dimensionId, Side.UNKNOWN);
    }

    public A4mcId dimensionId() {
        return dimensionId;
    }

    public Side side() {
        return side;
    }

    public Object platformHandle() {
        return platformHandle;
    }

    public boolean isServerSide() {
        return side == Side.SERVER;
    }

    public boolean isClientSide() {
        return side == Side.CLIENT;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof A4mcWorldRef ref)) {
            return false;
        }
        return dimensionId.equals(ref.dimensionId) && side == ref.side;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimensionId, side);
    }

    @Override
    public String toString() {
        return side + ":" + dimensionId;
    }

    public enum Side {
        SERVER,
        CLIENT,
        UNKNOWN
    }
}
