package com.aerodynamics4mc.client;

import com.aerodynamics4mc.api.*;
import com.aerodynamics4mc.network.packet.AeroCoarseWindPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Tests production client decoding and source selection without opening a game window. */
public final class Wind40ClientContractTest {
    public static void main(String[] args) {
        var visualizer = new AeroVisualizer();
        visualizer.onRuntimeState(new AeroVisualizer.AeroFlowState(true,false,false));
        var dimension = ResourceLocation.fromNamespaceAndPath("test","wind40");
        short[] flow = new short[8*4];
        short[] atmosphere = new short[8*10];
        for(int i=0;i<8;i++) {
            flow[i*4]=AeroWindLimits.encodeCoarseVelocity(40);
            atmosphere[i*10+3]=AeroWindLimits.encodeCoarseVelocity(4);
        }
        var packet = new AeroCoarseWindPacket(dimension,BlockPos.ZERO,32,2,2,2,1,flow,atmosphere);
        visualizer.onCoarseWindField(packet);
        var position = new Vec3(24,24,24);
        var sample = visualizer.sampleServerCoarseFlow(dimension,position);
        near(sample.velocityX(),40,"Coarse velocity decoded at new range");
        near((float)sample.effectiveVelocityVector().length(),40,"Client limits mean plus gust");
        check(sample.isServerTrusted() && sample.level()==AeroWindSample.Level.L1,"Coarse authority retained");
        for(int i=0;i<8;i++) {
            flow[i*4]=AeroWindLimits.encodeCoarseVelocity(28.28427f);
            flow[i*4+2]=AeroWindLimits.encodeCoarseVelocity(28.28427f);
        }
        sample=visualizer.sampleServerCoarseFlow(dimension,position);
        near(sample.speedMetersPerSecond(),40,"Diagonal coarse magnitude survives interpolation");
        for(int i=0;i<8;i++) {flow[i*4]=32767;flow[i*4+2]=0;}
        short[] local = new short[8*4];
        for(int i=0;i<8;i++) local[i*4]=32767;
        visualizer.onLocalFlowField(dimension,BlockPos.ZERO,32,local);
        sample=visualizer.sampleFlow(dimension,position,SamplePolicy.DIAGNOSTIC_ALL_SOURCES);
        near(sample.velocityX(),5.6f,"Local L2 wire units retained");
        check(sample.level()==AeroWindSample.Level.L2,"Diagnostic can inspect L2");
        sample=visualizer.sampleFlow(dimension,position,SamplePolicy.CLIENT_LOCAL_PREFERRED);
        near(sample.velocityX(),40,"Strong weather takes priority over clipped L2");
        check(sample.level()==AeroWindSample.Level.L1,"Normal visuals use strong coarse wind");
        System.out.println("WIND40_CLIENT_PASS: production packet decoder, gust and diagonal limits, L2 units, strong-weather priority");
    }
    private static void near(float actual,float expected,String message) {
        check(Math.abs(actual-expected)<0.003f,message+": "+actual);
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
