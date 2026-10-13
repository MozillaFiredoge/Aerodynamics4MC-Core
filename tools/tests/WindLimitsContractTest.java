package com.aerodynamics4mc.api;

public class WindLimitsContractTest {
    public static void main(String[] args) throws Exception {
        for (float value : new float[]{-40,-28.284271f,-20,0,20,28.284271f,40}) {
            float decoded = AeroWindLimits.decodeCoarseVelocity(AeroWindLimits.encodeCoarseVelocity(value));
            check(Math.abs(value-decoded) < 0.0013, "Coarse codec must represent full physical range");
        }
        check(AeroWindLimits.decodeCoarseVelocity(AeroWindLimits.encodeCoarseVelocity(100)) == 40, "Wire overflow saturates");
        for (float requested : new float[]{0.1f,0.5f,3.2f,10}) {
            float dt = AeroWindLimits.stableStepSeconds(64,requested);
            check(40 * dt / 64 <= 0.35f * 0.901f, "40 m/s fits native lattice limit with margin");
        }
        float scale = AeroWindLimits.scale(40,0,40);
        check(Math.abs(Math.hypot(40*scale,40*scale)-40) < 0.001, "Diagonal vector limit");
        AeroWindSample sample = new AeroWindSample(40,0,40,1,AeroWindSample.Level.L1,
            AeroWindSample.Authority.SERVER_AUTHORITATIVE,1,2,3,1,280,0.7f,2,4,0,4,0,0,0,0);
        AeroWindSample limited = AeroWindLimits.limitWeatherSample(sample);
        check(limited.meanVelocityVector().length() <= 40.001, "Mean vector capped");
        check(limited.effectiveVelocityVector().length() <= 40.001, "Gust-inclusive vector capped");
        check(limited.l1Epoch() == 1 && limited.temperatureKelvin() == 280, "Metadata retained");
        GameplayWindSample gameplay = GameplayWindSample.fromRaw(new AeroWindSample(40,0,0,0,
            AeroWindSample.Level.L1,AeroWindSample.Authority.SERVER_AUTHORITATIVE,1,2,3,1));
        check(Math.abs(gameplay.meanVelocityVector().length()-40) < 0.001, "Gameplay no longer clips at 12 m/s");
        A4mcId dimension = A4mcId.of("test","wind");
        var world = A4mcWorldRef.server(dimension);
        check(AeroAtmosphereApi.backgroundWindDragEnabled(dimension), "Additional drag defaults on");
        long revision = AeroAtmosphereApi.atmosphereRevision();
        AeroAtmosphereApi.setBackgroundWindDragEnabled(dimension,false);
        check(AeroAtmosphereApi.atmosphereRevision() > revision, "Drag changes invalidate atmosphere");
        check(!AeroAtmosphereApi.backgroundWindDragEnabled(dimension), "Drag can be disabled");
        check(!AeroAtmosphereApi.sampleBackgroundWind(world,0,0).available(), "No provider preserves normal forcing");
        try (var handle = AeroAtmosphereApi.registerBackgroundWindProvider(dimension,
                (w,x,z)->AeroBackgroundWindSample.available(40,0))) {
            check(AeroAtmosphereApi.sampleBackgroundWind(world,10,10).windX() == 40, "External target reaches the cap");
            try { AeroAtmosphereApi.registerBackgroundWindProvider(dimension,(w,x,z)->AeroBackgroundWindSample.UNAVAILABLE);
                throw new AssertionError("Duplicate provider accepted"); }
            catch (IllegalStateException expected) {}
        } finally { AeroAtmosphereApi.setBackgroundWindDragEnabled(dimension,true); }
        check(!AeroAtmosphereApi.sampleBackgroundWind(world,0,0).available(), "Close restores built-in targets");
        try { AeroBackgroundWindSample.available(Float.NaN,0); throw new AssertionError("NaN accepted"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("Wind limits contracts passed");
    }
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
}
