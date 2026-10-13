# Wind Sampling API

## Custom coarse terrain (0.2.2)

`AeroTerrainApi` supplies optional terrain inputs to the normal server-authoritative L0/L1
atmosphere. The public API module has no Minecraft dependency. It does not grant access to
solver buffers or replace the atmosphere authority.

```java
AutoCloseable registration = AeroTerrainApi.registerProvider(
    A4mcId.parse("example:custom_islands"),
    (world, blockX, blockZ) -> {
        if (!ownsColumn(world, blockX, blockZ)) {
            return AeroTerrainSample.UNAVAILABLE;
        }
        return AeroTerrainSample.available(
            274.0f, 0.8f, 0.05f, AeroTerrainSurfaceClass.PLAINS);
    });
```

Height is the first free Y coordinate in blocks, matching the vanilla worldgen height query;
biome temperature uses Minecraft biome units and roughness length is in meters. Available
samples require finite values, non-negative roughness and a known surface class. Return
`UNAVAILABLE` only for columns your integration does not own. Unclaimed columns use the
existing worldgen provider and its fallback.

Register during mod initialization before sampling begins. Registration is process-wide;
check the dimension and, when needed, `world.platformHandle()` to distinguish server worlds.
Providers run on the atmosphere simulation thread, including for unloaded chunks. They must
read thread-safe terrain snapshots without loading chunks or waiting on the server thread.
Publish changed terrain before calling `AeroTerrainApi.invalidateTerrain()`. This invalidates
coarse terrain caches across all server worlds on the next refresh; batch updates rather
than invalidating once per column. It does not rebuild L2 block geometry.

Registration and closing its handle also invalidate coarse terrain caches. Closing a handle
is idempotent and cannot remove a later registration with the same ID. Provider IDs must be
unique. Multiple providers claiming one column, null samples and provider exceptions are
reported with provider/column context instead of silently changing the terrain authority.
A callback failure can stop the atmosphere coordinator; repair the integration rather than
relying on a fallback for claimed columns.

## Atmosphere settings

`AeroAtmosphereApi` lets server integrations configure each dimension independently. The API
module remains Minecraft-free. Without overrides, temperature lapse and the legacy Coriolis
model retain their existing behavior.

```java
A4mcId dimension = A4mcId.of("minecraft", "overworld");
AeroAtmosphereApi.setAltitudeLapseRate(dimension, 0.14f);

AutoCloseable coriolis = AeroAtmosphereApi.registerCoriolisProvider(
    dimension,
    (world, blockX, blockZ) -> {
        if (!ownsWorld(world.platformHandle())) {
            return java.util.OptionalDouble.empty();
        }
        double latitude = planetSnapshot.latitudeDegrees(blockX, blockZ);
        return java.util.OptionalDouble.of(Math.sin(Math.toRadians(latitude)));
    });
```

The lapse rate is a finite, non-negative value in **K per block**. Zero disables the altitude
lapse; the default is `0.0065`. L0 terrain temperature, L1 layer temperature and instability
thresholds, and thermal fallback sampling all use this setting. Set it before atmosphere
initialization for initial temperatures to use the new rate. Runtime changes rebuild forcing
at the next grid refresh; existing temperatures continue relaxing toward the new targets.
Use `clearAltitudeLapseRate(dimension)` when the integration unloads to restore the default.

The Coriolis provider returns a **dimensionless normalized factor in [-1, 1]**, not a physical
frequency in inverse seconds. Positive values indicate the northern hemisphere, negative values
the southern hemisphere, and a custom zero disables Coriolis turning. Both L0 pressure-derived
wind and L1 Ekman turning query this provider using block coordinates. L0 retains its existing
minimum strength when dividing by the factor. Empty results retain the caller's legacy model,
including its original cell size and zero-sign convention. Registered wrapping-world coordinates
are canonicalized before calling the provider.

There is one Coriolis authority per dimension. Duplicate registrations, invalid factors, null
results and callback exceptions are errors rather than silent fallbacks. The close handle is
idempotent and cannot remove a later registration. Like terrain providers, callbacks run on the
simulation thread and must use thread-safe snapshots without loading chunks or waiting for the
server thread. Register during initialization, close the handle on unload, and publish changed
provider state before calling `AeroAtmosphereApi.invalidateAtmosphere()`. Settings are process-wide;
use the world handle when one process hosts multiple servers with the same dimension ID.

## External wind and the 40 m/s limit

L0/L1 weather and gameplay samples now support a **40 m/s vector magnitude**. Diagonal wind
does not get a larger allowance; mean wind and mean-plus-gust are both bounded. This is an
upper limit, not a new default wind speed. L1 uses a 0.5-second integration step at 64-block
resolution, preserving the native lattice-speed bound and the existing 64-tick terrain refresh
cadence. More solver steps are required for the same amount of simulated time.

External weather models can publish a horizontal mean wind target in m/s:

```java
AutoCloseable wind = AeroAtmosphereApi.registerBackgroundWindProvider(
    dimension, (world, blockX, blockZ) -> {
        if (!ownsWorld(world.platformHandle())) return AeroBackgroundWindSample.UNAVAILABLE;
        return AeroBackgroundWindSample.available(
            planetSnapshot.windX(blockX, blockZ), planetSnapshot.windZ(blockX, blockZ));
    });
// Use this when the external model already includes terrain/roughness drag.
AeroAtmosphereApi.setBackgroundWindDragEnabled(dimension, false);
```

Only claimed columns use the external wind target; other columns retain built-in weather.
Targets must be finite and are limited by magnitude to 40 m/s. Their pressure-derived blend
is bypassed so a second planetary wind model does not replace the supplied direction/speed.
L1 also receives this wind as its aloft target. This hook supplies wind only; built-in thermal,
humidity and storm inputs remain in use.

Additional L0 terrain-form adjustments and post-relaxation roughness drag are **enabled by
default**. Disabling them avoids damping an external target twice. L1 boundary-layer and local
obstruction effects remain active, so near-ground wind can still be weaker than aloft wind.
Changing the option invalidates atmospheric forcing; existing states relax toward the new
target. On unload, close the wind handle and restore `setBackgroundWindDragEnabled(dimension,
true)` (or the previous setting). Wind callbacks have the same ownership, wrapping-coordinate,
thread-safety and lifecycle requirements as Coriolis callbacks.

Coarse wind and map packets use the new range, so clients and servers must run matching updated
versions. The experimental L2 atlas retains its existing transport units; regular client
sampling uses coarse weather when its wind exceeds that atlas's 5.6 m/s range.
`DIAGNOSTIC_ALL_SOURCES` can still inspect the original L2 data.

## Wrapping worlds

Mods that make a dimension repeat horizontally, so that walking off its east edge brings you
back at its west edge, tell A4MC the period:

```java
AeroWorldWrapApi.setPeriod(A4mcId.of("minecraft", "overworld"), 1_048_576);
```

Columns `x` and `x + period` (likewise for `z`) are then the same place. The L0 and L1 grids
follow players across the seam instead of being rebuilt on the far side, positions are matched
to the grids' copy of the world, and the world-scale driver is fitted so that its pressure
systems and planetary waves repeat with the period. Fields sent to clients are moved next to
the client's player, so a wrapping mod may give clients coordinates whole periods away from the
server's.

The period must be a multiple of `AeroWorldWrapApi.PERIOD_GRANULARITY_BLOCKS` (256). Set it
on each side it applies to: on the server when the dimension loads, on the client once the
period is known. Set 0 to clear it. In singleplayer both sides share one setting. Dimensions
without a period behave as before.

## Particle wind rules (0.2.2)

The client creates `config/aerodynamics4mc-particles.json` on first initialization:

```json
{
  "enabled": true,
  "whitelist": [],
  "blacklist": []
}
```

Defaults retain the existing flame, smoke, campfire smoke and supported falling-leaf wind
effects, and include FBP's replacement flame/smoke classes. Whitelist entries add other
particle classes; blacklist entries disable matching built-in or whitelisted classes.
Blacklist takes precedence. Use fully qualified Java class names, with `*` for wildcards,
for example `hantonik.fbp.particle.*` to disable FBP wind. These are runtime particle class
names, not registry IDs; replacement particles often reuse vanilla particle IDs.

Restart the client after editing the file. `enabled: false` disables these particle wind
effects. Generic whitelist support changes particle velocity; mods with special movement
rules may need a dedicated adapter. FBP flame has such an adapter because it ignores its
horizontal velocity. FBP's freeze control and Minecraft pause state remain respected.

Minecraft-free regression checks: `python3 tools/check-integration-contracts.py`.

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
