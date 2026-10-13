package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.api.*;
import com.aerodynamics4mc.network.packet.AeroCoarseWindPacket;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.OptionalDouble;
import java.util.UUID;

/** Exercise physical wind limits and drag with production weather grids and native transport. */
public final class WindSpeedRuntimeTest {
    public static void run(ServerLevel world) throws Exception {
        var key = world.dimension().location();
        var dimension = A4mcId.of(key.getNamespace(),key.getPath());
        boolean originalDrag = AeroAtmosphereApi.backgroundWindDragEnabled(dimension);
        float[] target = {40,0};
        SeedTerrainProvider forest = (w,x,z) -> new SeedTerrainProvider.TerrainSample(world.getSeaLevel(),0.8f,0.9f,(byte)2);
        try (var wind = AeroAtmosphereApi.registerBackgroundWindProvider(dimension,
                (w,x,z) -> AeroBackgroundWindSample.available(target[0],target[1]));
             var coriolis = AeroAtmosphereApi.registerCoriolisProvider(dimension,(w,x,z) -> OptionalDouble.of(0))) {
            AeroAtmosphereApi.setBackgroundWindDragEnabled(dimension,true);
            var dragGrid = new BackgroundMetGrid(256,2,1);
            for (int tick=1; tick<=10; tick++) dragGrid.refresh(world,null,BlockPos.ZERO,tick*256L,0.05f,forest,null);
            float slowed = dragGrid.sample(BlockPos.ZERO).backgroundWindX();
            check(slowed < 39, "Default extra friction still slows forest wind: " + slowed);
            AeroAtmosphereApi.setBackgroundWindDragEnabled(dimension,false);
            for (int tick=11; tick<=50; tick++) dragGrid.refresh(world,null,BlockPos.ZERO,tick*256L,0.05f,forest,null);
            near(dragGrid.sample(BlockPos.ZERO).backgroundWindX(),40,0.02f,"Disabling drag restores external wind on an existing grid");
            target[1]=40; AeroAtmosphereApi.invalidateAtmosphere();
            var diagonal = new BackgroundMetGrid(256,2,1);
            diagonal.refresh(world,null,BlockPos.ZERO,1,0.05f,forest,null);
            var sample = diagonal.sample(BlockPos.ZERO);
            near((float)Math.hypot(sample.backgroundWindX(),sample.backgroundWindZ()),40,0.002f,"Diagonal external wind is limited by magnitude");
            near(sample.backgroundWindX(),sample.backgroundWindZ(),0.002f,"Limiting preserves direction");
            target[0]=100; target[1]=0; AeroAtmosphereApi.invalidateAtmosphere();
            var background = new BackgroundMetGrid(256,2,1);
            SeedTerrainProvider flat = (w,x,z) -> new SeedTerrainProvider.TerrainSample(world.getSeaLevel(),0.8f,0,(byte)1);
            background.refresh(world,null,BlockPos.ZERO,1,0.05f,flat,null);
            near(background.sample(BlockPos.ZERO).backgroundWindX(),40,0.002f,"Overspeed external target saturates at 40");
            try (var grid = new MesoscaleGrid(64,1,40,8,AeroServerRuntime.MESOSCALE_STEP_SECONDS,100)) {
                grid.refresh(world,BlockPos.ZERO,1,0.05f,flat,background);
                var snapshot = grid.snapshot();
                check(40 / (64 / snapshot.stepSeconds()) <= 0.35f * 0.9f + 0.00001f,"L1 transport retains its stability margin");
                check(snapshot.forcingBackgroundWindX()[snapshot.index(1,7,1)] > 39,"Aloft forcing is not clipped at old L1 limit");
                for (int step=1; step<=240; step++) {
                    grid.refresh(world,BlockPos.ZERO,1+step*10L,0.05f,flat,background);
                    grid.runPendingSteps();
                    snapshot = grid.snapshot();
                    for (int i=0; i<snapshot.windX().length; i++) {
                        double speed = Math.sqrt((double)snapshot.windX()[i]*snapshot.windX()[i]
                            + (double)snapshot.windY()[i]*snapshot.windY()[i] + (double)snapshot.windZ()[i]*snapshot.windZ()[i]);
                        check(Double.isFinite(speed) && speed <= 40.002,"Native weather remains finite and bounded: " + speed);
                        check(Float.isFinite(snapshot.ambientAirTemperatureKelvin()[i]),"Native temperature remains finite");
                    }
                }
                var context = MesoscaleGrid.class.getDeclaredField("nativeContextKey"); context.setAccessible(true);
                check(context.getLong(grid) != 0,"Test must exercise the native solver");
                float aloft = snapshot.windX()[snapshot.index(1,7,1)];
                check(aloft > 20,"Native wind exceeds all old solver caps: " + aloft);
                checkPacket(world,background,grid);
                target[0]=40; target[1]=40; AeroAtmosphereApi.invalidateAtmosphere();
                background.refresh(world,null,BlockPos.ZERO,2402,0.05f,flat,null);
                for (int step=1; step<=120; step++) {
                    grid.refresh(world,BlockPos.ZERO,2401+step*10L,0.05f,flat,background);
                    grid.runPendingSteps();
                    snapshot = grid.snapshot();
                    for (int i=0; i<snapshot.windX().length; i++) {
                        double speed = Math.sqrt((double)snapshot.windX()[i]*snapshot.windX()[i]
                            + (double)snapshot.windY()[i]*snapshot.windY()[i] + (double)snapshot.windZ()[i]*snapshot.windZ()[i]);
                        check(Double.isFinite(speed) && speed <= 40.002,"Diagonal native weather remains bounded: " + speed);
                    }
                }
                int top = snapshot.index(1,7,1);
                check(snapshot.windZ()[top]>20 && snapshot.windX()[top]>20,"Native solver adopts the diagonal strong-wind direction");
                System.out.printf("COARSE_GRID_WIND40_PASS: forest drag %.3f -> 40 m/s, diagonal cap, native 240 steps (aloft %.3f m/s), packet range%n",slowed,aloft);
                System.out.printf("COARSE_GRID_WIND40_DIAGONAL_PASS: native 120 steps, aloft (%.3f, %.3f) m/s%n",snapshot.windX()[top],snapshot.windZ()[top]);
            }
        } finally { AeroAtmosphereApi.setBackgroundWindDragEnabled(dimension,originalDrag); }
    }

    private static void checkPacket(ServerLevel world, BackgroundMetGrid background, MesoscaleGrid grid) throws Exception {
        var oldBackground = AeroServerRuntime.backgroundMetGrids.put(world.dimension(),background);
        var oldGrid = AeroServerRuntime.mesoscaleMetGrids.put(world.dimension(),grid);
        var runtime = AeroServerRuntime.getInstance();
        var player = new ServerPlayer(world.getServer(),world,new GameProfile(UUID.randomUUID(),"Wind40Test"),ClientInformation.createDefault());
        player.setPos(32,world.getSeaLevel()+300,32);
        try {
            var state = invoke(runtime,"coarseWindSyncStateForPlayer",player);
            var packet = (AeroCoarseWindPacket)invoke(runtime,"buildCoarseWindPayloadForPlayer",player,state);
            check(packet != null,"Strong-wind packet exists");
            float maximum = 0;
            for (int i=0; i<packet.getPackedFlow().length; i+=4) {
                float x=AeroWindLimits.decodeCoarseVelocity(packet.getPackedFlow()[i]);
                float y=AeroWindLimits.decodeCoarseVelocity(packet.getPackedFlow()[i+1]);
                float z=AeroWindLimits.decodeCoarseVelocity(packet.getPackedFlow()[i+2]);
                double speed=Math.sqrt((double)x*x+(double)y*y+(double)z*z);
                check(speed<=40.003,"Encoded packet retains vector magnitude cap");
                maximum=Math.max(maximum,x);
            }
            check(maximum>39.9,"L0 strong wind survives actual server packet encoding: "+maximum);
        } finally {
            if(oldBackground==null) AeroServerRuntime.backgroundMetGrids.remove(world.dimension());
            else AeroServerRuntime.backgroundMetGrids.put(world.dimension(),oldBackground);
            if(oldGrid==null) AeroServerRuntime.mesoscaleMetGrids.remove(world.dimension());
            else AeroServerRuntime.mesoscaleMetGrids.put(world.dimension(),oldGrid);
        }
    }
    private static Object invoke(Object target,String name,Object...args) throws Exception {
        for(var method:target.getClass().getDeclaredMethods()) if(method.getName().equals(name)) {
            method.setAccessible(true);return method.invoke(target,args);
        }
        throw new NoSuchMethodException(name);
    }
    private static void near(float actual,float expected,float tolerance,String message) {
        check(Math.abs(actual-expected)<tolerance,message+": "+actual);
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
