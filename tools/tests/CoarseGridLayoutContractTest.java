package com.aerodynamics4mc.runtime;

public final class CoarseGridLayoutContractTest {
    public static void main(String[] args) {
        for (int width : new int[]{3, 5, 33}) for (int layers : new int[]{1, 4, 8}) {
            int[] markers = new int[width * layers * width];
            for (int x=0; x<width; x++) for (int layer=0; layer<layers; layer++) for (int z=0; z<width; z++) {
                // Fill using the independently specified X/layer/Z array order.
                markers[(x * layers + layer) * width + z] = layer * 1000 + z * 10 + x % 10;
            }
            for (int x=0; x<width; x++) for (int z=0; z<width; z++) for (int layer=0; layer<layers; layer++) {
                int actual = markers[CoarseGridLayout.index(x, layer, z, layers, width)];
                if (actual != layer * 1000 + z * 10 + x % 10) throw new AssertionError("Layers/Z mixed");
            }
        }
        System.out.println("Coarse grid layout contracts passed");
    }
}
