package com.aerodynamics4mc.block;

import com.aerodynamics4mc.api.GameplayWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftVectors;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftWindApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class WindVaneBlockEntity extends BlockEntity {
    private static final long SAMPLE_INTERVAL_TICKS = 20L;
    private static final double CALM_HORIZONTAL_SPEED_MPS = 0.15;

    private GameplayWindSample lastSample = GameplayWindSample.ZERO;
    private Direction lastDirection = Direction.NORTH;
    private long lastSampleTick = -1L;

    public WindVaneBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.WIND_VANE_BLOCK_ENTITY/*? neoforge{ */.get()/*?} */, pos, state);
    }

    public static void tick(Level world, BlockPos pos, BlockState state, WindVaneBlockEntity blockEntity) {
        if (!(world instanceof ServerLevel serverWorld)) {
            return;
        }
        long time = serverWorld.getGameTime();
        if (Math.floorMod(time + pos.asLong(), SAMPLE_INTERVAL_TICKS) != 0L) {
            return;
        }
        blockEntity.sampleNow(serverWorld, state);
    }

    public void sampleNow(ServerLevel world, BlockState state) {
        Vec3 samplePos = new Vec3(worldPosition.getX() + 0.5, worldPosition.getY() + 1.4, worldPosition.getZ() + 0.5);
        GameplayWindSample sample = AeroMinecraftWindApi.sampleGameplay(world, samplePos, SamplePolicy.GAMEPLAY_SERVER_ONLY);
        lastSample = sample;
        lastSampleTick = world.getGameTime();
        if (!sample.hasFlow()) {
            setChanged();
            return;
        }

        Vec3 effective = AeroMinecraftVectors.effectiveVelocity(sample);
        double horizontalSpeed = Math.sqrt(effective.x * effective.x + effective.z * effective.z);
        if (!(horizontalSpeed >= CALM_HORIZONTAL_SPEED_MPS)) {
            setChanged();
            return;
        }

        Direction direction = directionFromVector(effective.x, effective.z);
        lastDirection = direction;
        if (state.getValue(WindVaneBlock.FACING) != direction) {
            world.setBlock(worldPosition, state.setValue(WindVaneBlock.FACING, direction), 3);
        }
        setChanged();
    }

    public GameplayWindSample lastSample() {
        return lastSample;
    }

    public Direction lastDirection() {
        return lastDirection;
    }

    public long lastSampleTick() {
        return lastSampleTick;
    }

    public boolean hasSample() {
        return lastSampleTick >= 0L && lastSample.hasFlow();
    }

    private static Direction directionFromVector(double x, double z) {
        if (Math.abs(x) >= Math.abs(z)) {
            return x >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        return z >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }
}
