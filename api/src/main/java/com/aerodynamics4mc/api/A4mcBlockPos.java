package com.aerodynamics4mc.api;

public record A4mcBlockPos(int x, int y, int z) {
    public static final A4mcBlockPos ZERO = new A4mcBlockPos(0, 0, 0);

    public static A4mcBlockPos of(int x, int y, int z) {
        return new A4mcBlockPos(x, y, z);
    }

    public A4mcVec3 center() {
        return new A4mcVec3(x + 0.5, y + 0.5, z + 0.5);
    }
}
