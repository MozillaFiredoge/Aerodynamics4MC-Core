package com.aerodynamics4mc.runtime;

/** L1 snapshot/native state order: X, then layer, then Z. */
final class CoarseGridLayout {
    private CoarseGridLayout() {}

    static int index(int localX, int layer, int localZ, int activeLayers, int gridWidth) {
        return (localX * activeLayers + layer) * gridWidth + localZ;
    }
}
