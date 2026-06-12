package com.aerodynamics4mc.vehicle;

import com.aerodynamics4mc.api.GameplayWindSample;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftVectors;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftWindApi;
import com.aerodynamics4mc.block.ModBlocks;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
//? >=1.21.11 {
import net.minecraft.world.entity.vehicle.boat.Boat;
//?} <1.21.11 {
/*import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
*///?}
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class SailboatEntity extends Boat {
    private static final EntityDataAccessor<Float> DATA_SAILING_WIND_X = SynchedEntityData.defineId(
            SailboatEntity.class,
            EntityDataSerializers.FLOAT
    );
    private static final EntityDataAccessor<Float> DATA_SAILING_WIND_Z = SynchedEntityData.defineId(
            SailboatEntity.class,
            EntityDataSerializers.FLOAT
    );
    private static final double WIND_SYNC_EPSILON = 0.01;
    private static final float BASE_RUDDER_TURN_DEGREES = 0.45F;
    private static final float SPEED_RUDDER_TURN_DEGREES = 1.35F;

    private boolean rudderLeft;
    private boolean rudderRight;
    private boolean sailTrimmed;
    private boolean sailReefed;

    public SailboatEntity(EntityType<? extends SailboatEntity> entityType, Level level) {
        //? >=1.21.11 {
        super(entityType, level, () -> ModBlocks.SAILBOAT_ITEM/*? neoforge{ */.get()/*?} */);
        //?} <1.21.11 {
        /*super(entityType, level);
        setVariant(Boat.Type.OAK);
        *///?}
    }

    //? <1.21.11 {
    /*@Override
    public Item getDropItem() {
        return ModBlocks.SAILBOAT_ITEM.get();
    }
    *///?}

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SAILING_WIND_X, 0.0F);
        builder.define(DATA_SAILING_WIND_Z, 0.0F);
    }

    @Override
    public void setInput(boolean pressingLeft, boolean pressingRight, boolean pressingForward, boolean pressingBack) {
        setSailingInputs(pressingLeft, pressingRight, pressingForward, pressingBack);
        super.setInput(false, false, false, false);
    }

    @Override
    public void tick() {
        if (isSailingActive() && !level().isClientSide() && level() instanceof ServerLevel serverLevel) {
            updateServerSailingWind(serverLevel);
        }

        if (isSailingActive()) {
            applySailingControl();
        }

        super.tick();

        if (isSailingActive()) {
            applySailingControl();
        }
    }

    private void updateServerSailingWind(ServerLevel serverLevel) {
        GameplayWindSample wind = AeroMinecraftWindApi.sampleGameplay(serverLevel, position());
        Vec3 targetWind = wind.hasFlow() ? AeroMinecraftVectors.meanVelocity(wind) : Vec3.ZERO;
        setSailingWindMetersPerSecond(SailingPhysics.smoothWind(sailingWindMetersPerSecond(), targetWind));
    }

    private void setSailingInputs(boolean pressingLeft, boolean pressingRight, boolean pressingForward, boolean pressingBack) {
        rudderLeft = pressingLeft;
        rudderRight = pressingRight;
        sailTrimmed = pressingForward;
        sailReefed = pressingBack;
    }

    private void applySailingControl() {
        float rudder = (rudderRight ? 1.0F : 0.0F) - (rudderLeft ? 1.0F : 0.0F);
        if (rudder != 0.0F) {
            float speedScale = (float) Math.min(1.0, horizontalSpeed() / 0.25);
            setYRot(getYRot() + rudder * (BASE_RUDDER_TURN_DEGREES + SPEED_RUDDER_TURN_DEGREES * speedScale));
        }

        Vec3 updatedVelocity = SailingPhysics.step(
                getDeltaMovement(),
                sailingWindMetersPerSecond(),
                getYRot(),
                sailTrimmed,
                sailReefed,
                SailingPhysics.DEFAULT_SPEED_SCALE
        );
        setDeltaMovement(updatedVelocity);
    }

    private boolean isSailingActive() {
        return !onGround() || isInWater();
    }

    private double horizontalSpeed() {
        Vec3 velocity = getDeltaMovement();
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    private Vec3 sailingWindMetersPerSecond() {
        return new Vec3(
                getEntityData().get(DATA_SAILING_WIND_X),
                0.0,
                getEntityData().get(DATA_SAILING_WIND_Z)
        );
    }

    private void setSailingWindMetersPerSecond(Vec3 wind) {
        setSyncedWindComponent(DATA_SAILING_WIND_X, wind.x);
        setSyncedWindComponent(DATA_SAILING_WIND_Z, wind.z);
    }

    private void setSyncedWindComponent(EntityDataAccessor<Float> accessor, double value) {
        float updated = (float) value;
        if (Math.abs(getEntityData().get(accessor) - updated) > WIND_SYNC_EPSILON) {
            getEntityData().set(accessor, updated);
        }
    }
}
