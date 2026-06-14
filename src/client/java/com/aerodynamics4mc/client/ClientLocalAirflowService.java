package com.aerodynamics4mc.client;

import com.aerodynamics4mc.network.ClientServerboundPacketSender;
import com.aerodynamics4mc.network.packet.AeroClientL2PreferencePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ClientLocalAirflowService {
    private static final int DEFAULT_RADIUS_BLOCKS = 32;
    private static final int DEFAULT_DURATION_TICKS = 80;
    private static final int COOLDOWN_TICKS = 40;
    private static final int MAX_DURATION_TICKS = 20 * 60 * 5;

    private final ClientL2Solver solver;
    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private State state = State.INACTIVE;
    private long nextLeaseId = 1L;
    private boolean streamingEnabled;
    private boolean serverPreferenceSent;
    private long cooldownUntilGameTime = Long.MIN_VALUE;
    private BlockPos activeAnchor;
    private String activeReason = "none";
    private int activeRadiusBlocks;

    ClientLocalAirflowService(ClientL2Solver solver) {
        this.solver = Objects.requireNonNull(solver, "solver");
    }

    public String requestPatch(ClientLevel world, BlockPos anchor, int radiusBlocks, int durationTicks, String reason) {
        String leaseId = "lease-" + nextLeaseId++;
        requestPatch(leaseId, world, anchor, radiusBlocks, durationTicks, reason);
        return leaseId;
    }

    public void requestPatch(String leaseId, ClientLevel world, BlockPos anchor, int radiusBlocks, int durationTicks, String reason) {
        if (leaseId == null || leaseId.isBlank() || world == null || anchor == null) {
            return;
        }
        int clampedDuration = Mth.clamp(durationTicks <= 0 ? DEFAULT_DURATION_TICKS : durationTicks, 1, MAX_DURATION_TICKS);
        int clampedRadius = Math.max(1, radiusBlocks <= 0 ? DEFAULT_RADIUS_BLOCKS : radiusBlocks);
        long expiresAtGameTime = world.getGameTime() + clampedDuration;
        leases.put(
                leaseId,
                new Lease(
                        world.dimension().identifier(),
                        anchor.immutable(),
                        clampedRadius,
                        expiresAtGameTime,
                        sanitizeReason(reason)
                )
        );
    }

    public void releasePatch(String leaseId) {
        if (leaseId == null) {
            return;
        }
        leases.remove(leaseId);
    }

    void onRuntimeState(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
        solver.onRuntimeState(streamingEnabled);
        if (!streamingEnabled) {
            clearLeases();
            stopActivePatch();
        }
        syncServerPreference(false);
    }

    public void onClientTick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            solver.onIdleClientTick();
            syncServerPreference(false);
            return;
        }

        ClientLevel world = client.level;
        long gameTime = world.getGameTime();
        pruneExpiredLeases(gameTime);
        Lease lease = selectLease(world, client.player.position());
        boolean shouldRun = streamingEnabled && solver.isExperimentalEnabled() && lease != null;
        if (shouldRun) {
            activeAnchor = lease.anchor();
            activeReason = lease.reason();
            activeRadiusBlocks = lease.radiusBlocks();
            cooldownUntilGameTime = gameTime + COOLDOWN_TICKS;
            if (state == State.INACTIVE || state == State.COOLDOWN) {
                state = State.WARMING;
            }
            solver.onClientTick(client, activeAnchor);
            state = solver.hasReadyLocalFlow() ? State.ACTIVE : State.WARMING;
            syncServerPreference(true);
            return;
        }

        solver.onIdleClientTick();
        syncServerPreference(false);
        if (state == State.WARMING || state == State.ACTIVE) {
            state = State.COOLDOWN;
            cooldownUntilGameTime = gameTime + COOLDOWN_TICKS;
        }
        if (state == State.COOLDOWN && gameTime >= cooldownUntilGameTime) {
            stopActivePatch();
        }
    }

    public void close() {
        clearLeases();
        clearActiveState();
        solver.close();
        serverPreferenceSent = false;
    }

    void setEnabled(boolean enabled) {
        solver.setExperimentalEnabled(enabled);
        if (!enabled) {
            clearLeases();
            clearActiveState();
            syncServerPreference(false);
        }
    }

    boolean isEnabled() {
        return solver.isExperimentalEnabled();
    }

    boolean isRunningPatch() {
        return state == State.WARMING || state == State.ACTIVE;
    }

    String status() {
        return "localAirflow state=" + state.name().toLowerCase(java.util.Locale.ROOT)
                + " enabled=" + solver.isExperimentalEnabled()
                + " streaming=" + streamingEnabled
                + " leases=" + leases.size()
                + " activeReason=" + activeReason
                + " activeAnchor=" + formatPos(activeAnchor)
                + " activeRadius=" + activeRadiusBlocks
                + " serverPreference=" + serverPreferenceSent
                + " cooldownUntil=" + cooldownUntilGameTime;
    }

    private void clearLeases() {
        leases.clear();
    }

    private void stopActivePatch() {
        clearActiveState();
        solver.releaseActivePatch();
    }

    private void clearActiveState() {
        state = State.INACTIVE;
        activeAnchor = null;
        activeReason = "none";
        activeRadiusBlocks = 0;
        cooldownUntilGameTime = Long.MIN_VALUE;
    }

    private void pruneExpiredLeases(long gameTime) {
        Iterator<Map.Entry<String, Lease>> iterator = leases.entrySet().iterator();
        while (iterator.hasNext()) {
            Lease lease = iterator.next().getValue();
            if (lease.expiresAtGameTime() <= gameTime) {
                iterator.remove();
            }
        }
    }

    private Lease selectLease(ClientLevel world, Vec3 playerPosition) {
        Identifier dimensionId = world.dimension().identifier();
        Lease best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Lease lease : leases.values()) {
            if (!lease.dimensionId().equals(dimensionId)) {
                continue;
            }
            double distance = distanceToCenterSqr(lease.anchor(), playerPosition);
            if (distance < bestDistance) {
                best = lease;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double distanceToCenterSqr(BlockPos pos, Vec3 point) {
        double dx = pos.getX() + 0.5 - point.x;
        double dy = pos.getY() + 0.5 - point.y;
        double dz = pos.getZ() + 0.5 - point.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static String formatPos(BlockPos pos) {
        if (pos == null) {
            return "none";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private void syncServerPreference(boolean enabled) {
        if (serverPreferenceSent == enabled) {
            return;
        }
        serverPreferenceSent = enabled;
        ClientServerboundPacketSender.send(new AeroClientL2PreferencePacket(enabled));
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        return reason.length() <= 48 ? reason : reason.substring(0, 48);
    }

    private enum State {
        INACTIVE,
        WARMING,
        ACTIVE,
        COOLDOWN
    }

    private record Lease(
            Identifier dimensionId,
            BlockPos anchor,
            int radiusBlocks,
            long expiresAtGameTime,
            String reason
    ) {
    }
}
