# Wind Sampling API

## Purpose

This document defines the stable wind sampling contract for gameplay systems and integration with other mods.

Consumers should sample wind through the API facade instead of reading internal `L0`, `L1`, `L2`, visualizer, or native solver state.

For gameplay, prefer `GameplayWindSample`.

Use `AeroWindSample` when you need raw or diagnostic data.

## Public Entry Points

Server side:

```java
import com.aerodynamics4mc.api.AeroWindApi;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.GameplayWindSample;
import com.aerodynamics4mc.api.SamplePolicy;

GameplayWindSample gameplay = AeroWindApi.sampleGameplay(serverWorld, position);
GameplayWindSample gameplay = AeroWindApi.sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);

AeroWindSample sample = AeroWindApi.sample(serverWorld, position);
AeroWindSample sample = AeroWindApi.sample(player, position, SamplePolicy.SERVER_COARSE_ONLY);
```

Client side:

```java
import com.aerodynamics4mc.api.AeroClientWindApi;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;

AeroWindSample sample = AeroClientWindApi.sample(clientWorld, position);
AeroWindSample sample = AeroClientWindApi.sample(clientWorld, position, SamplePolicy.CLIENT_LOCAL_PREFERRED);
```

## Sample Data

`GameplayWindSample` is the recommended gameplay record.

In-game verification:

- The Wind Meter item samples `AeroWindApi.sampleGameplay(...)`.
- Its chat output shows effective wind, mean wind, gust, updraft, turbulence, shear, shelter, confidence, and source authority.
- The optional official content addon's Wind Turbine Probe block samples `AeroWindApi.sampleGameplay(...)`, estimates turbine output, and emits redstone power from `0` to `15`.
- This is the intended player-facing debug surface for v0.1 gameplay integrations.

Gameplay wind:

- `meanX`, `meanY`, `meanZ`: stable mean wind in m/s.
- `gustX`, `gustY`, `gustZ`: bounded gust contribution in m/s.
- `effectiveVelocity()`: mean plus gust, already constrained for gameplay.
- `updraftMetersPerSecond`: vertical usable wind signal.
- `turbulenceIntensity`: gameplay turbulence magnitude.
- `windShearMagnitudePerBlock`: local shear proxy.
- `shelterFactor`: normalized wind shelter from `0` to `1`.
- `pressure`: pressure anomaly or pressure proxy.
- `temperatureKelvin`
- `humidity`
- `ablStability`
- `ablMixingStrength`
- `confidence`: normalized trust/confidence from `0` to `1`.
- `sourceLevel`: dominant source scale: `NONE`, `L0`, `L1`, or `L2`.
- `authority`: trust/source authority.

Important behavior:

- If the raw source is L2, gameplay sampling treats it as a local modifier over L1/L0.
- L2 local deltas are bounded to avoid raw CFD noise dominating gameplay.
- Client-local L2 is not server-trusted.
- `GameplayWindSample` is the stable contract for blocks, machines, flight feedback, and integrations.

`AeroWindSample` is the public sample record.

Core flow:

- `velocityX`, `velocityY`, `velocityZ`: mean wind velocity in m/s.
- `pressure`: pressure anomaly or local solver pressure proxy.
- `level`: source scale: `NONE`, `L0`, `L1`, or `L2`.
- `authority`: trust/source authority: server, client-local, remote, or untrusted.
- `confidence`: normalized confidence from `0` to `1`.

Atmospheric diagnostics:

- `temperatureKelvin`
- `humidity`
- `turbulenceIntensity`
- `gustX`, `gustY`, `gustZ`
- `windShearXPerBlock`, `windShearZPerBlock`
- `ablStability`
- `ablMixingStrength`

Freshness metadata:

- `l1Epoch`
- `worldDeltaEpoch`
- `l2Epoch`
- `freshnessEpoch()`
- `ageTicks(currentTick)`
- `isFresh(currentTick, maxAgeTicks)`

Convenience methods:

- `velocity()`
- `meanVelocity()`
- `gustVelocity()`
- `velocityWithGust()`
- `effectiveVelocity()`
- `speedMetersPerSecond()`
- `horizontalSpeedMetersPerSecond()`
- `hasFlow()`
- `hasAtmosphericDiagnostics()`
- `isServerTrusted()`
- `isTrustedForGameplay()`
- `isClientLocal()`
- `isLocalVoxelFlow()`

## Sample Policies

Use `SamplePolicy` to select which layers may be used.

`SERVER_COARSE_ONLY`

- Uses L1 first, then L0.
- Does not use L2.
- Recommended for most server-authoritative gameplay and large vehicles.

`GAMEPLAY_SERVER_ONLY`

- Uses server-trusted L2 if active, otherwise L1/L0.
- Does not use client-local L2.
- Recommended when server-side local voxel flow is explicitly needed.

`SERVER_AGGREGATED_PREFERRED`

- Client path that uses server-published L2 atlas when available, otherwise L1/L0.
- Useful when client-local L2 is disabled.

`CLIENT_LOCAL_PREFERRED`

- Uses client-local L2 when available, otherwise L1/L0.
- Recommended for client visuals and local particle effects.
- Not trusted for server-authoritative gameplay.

`VISUAL_LOCAL_FIRST`

- Debug/visual path that may use client-local L2, server L2, then L1/L0.
- Useful for overlays.

`DIAGNOSTIC_ALL_SOURCES`

- Allows every source.
- Intended for diagnostics only.

## Trust Model

Server-authoritative gameplay should use samples where:

```java
sample.isTrustedForGameplay()
```

Client-local L2 is useful for visuals, local effects, and player feedback, but it is not trusted by the server.

This means:

- aircraft flight physics on the server should prefer `SERVER_COARSE_ONLY`,
- client particles may use `CLIENT_LOCAL_PREFERRED`,
- engineering overlays may use `VISUAL_LOCAL_FIRST`,
- game balance should not depend on unvalidated client-local L2.

## Recommended Consumers

Ambient visuals:

- `SERVER_COARSE_ONLY` or default client sampling.

Foliage and shader wind:

- L1/L0 through default client sampling.

Aircraft cruise flight:

- `SERVER_COARSE_ONLY`.

Aircraft local aerodynamic proxy:

- Use a separate vehicle-relative solver or proxy model.
- Do not depend on world L2 by default.

Smoke, steam, ash, dust:

- `CLIENT_LOCAL_PREFERRED` on the client.
- Fallback to L1/L0 when L2 is absent.

Wind turbines and outdoor machines:

- `AeroWindApi.sampleGameplay(...)`.
- Prefer `SERVER_COARSE_ONLY` unless local obstruction is explicitly part of the gameplay.
- The official content addon's Wind Turbine Probe is the minimal reference implementation: sample gameplay wind, derate by shelter/turbulence/confidence, then map output to redstone.

Building ventilation, ducts, chimneys, fans:

- L2-capable policies are appropriate.
- Prefer client-local L2 for visuals and server L2 only if trusted gameplay requires it.
- Gameplay logic should consume `GameplayWindSample`, not raw L2 velocity.

## Contract Boundaries

Consumers should not:

- read `BackgroundMetGrid` directly,
- read `MesoscaleGrid` directly,
- read native solver buffers directly,
- depend on atlas packet format,
- assume L2 is always active,
- assume client-local L2 is server-trusted.

Consumers may:

- inspect `sample.level()`,
- inspect `sample.authority()`,
- use freshness and confidence,
- gracefully fallback when `!sample.hasFlow()`,
- use `effectiveVelocity()` for visual drift,
- use `meanVelocity()` for physics where gusts should be applied separately.

## Minimal Integration Pattern

```java
GameplayWindSample wind = AeroWindApi.sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
if (!wind.hasFlow()) {
    return;
}

Vec3d meanWind = wind.meanVelocity();
Vec3d effectiveWind = wind.effectiveVelocity();
float turbulence = wind.turbulenceIntensity();
float shelter = wind.shelterFactor();
```

For client particles:

```java
AeroWindSample sample = AeroClientWindApi.sample(world, position, SamplePolicy.CLIENT_LOCAL_PREFERRED);
Vec3d drift = sample.effectiveVelocity();
```
