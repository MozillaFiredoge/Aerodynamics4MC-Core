package com.aerodynamics4mc.api;

import java.util.Objects;
import java.util.UUID;

public final class A4mcPlayerRef {
    private final UUID uuid;
    private final A4mcWorldRef world;
    private final Object platformHandle;

    public A4mcPlayerRef(UUID uuid, A4mcWorldRef world) {
        this(uuid, world, null);
    }

    public A4mcPlayerRef(UUID uuid, A4mcWorldRef world, Object platformHandle) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.world = Objects.requireNonNull(world, "world");
        this.platformHandle = platformHandle;
    }

    public static A4mcPlayerRef server(UUID uuid, A4mcWorldRef world) {
        return new A4mcPlayerRef(uuid, world);
    }

    public static A4mcPlayerRef server(UUID uuid, A4mcWorldRef world, Object platformHandle) {
        return new A4mcPlayerRef(uuid, world, platformHandle);
    }

    public UUID uuid() {
        return uuid;
    }

    public A4mcWorldRef world() {
        return world;
    }

    public Object platformHandle() {
        return platformHandle;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof A4mcPlayerRef ref)) {
            return false;
        }
        return uuid.equals(ref.uuid) && world.equals(ref.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, world);
    }

    @Override
    public String toString() {
        return uuid + "@" + world;
    }
}
