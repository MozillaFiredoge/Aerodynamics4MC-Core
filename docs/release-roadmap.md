# Aerodynamics4MC Release Roadmap

This document is the release-facing roadmap for Aerodynamics4MC. It defines what the mod is, what the public API should promise, and how future work should be prioritized.

Target first public stable release: **v0.1 on 2026-05-04**.

## Product Definition

Aerodynamics4MC is a Minecraft weather and airflow mod that makes wind observable, predictable, and usable as a gameplay resource.

The current mainline is a high-end wind and weather visual mod with optional wind-powered toys.

The mod is not primarily an aircraft CFD mod. The solvers exist to support player-facing systems:

- weather that has spatial structure,
- terrain-shaped wind,
- cinematic air movement through particles, rain, smoke, sound, and storms,
- optional local airflow affected by buildings, heat, and machines,
- stable wind APIs for other mods.

After community feedback, the immediate vNext sequence is:

1. [`vNext: Cinematic Weather Update`](vnext-cinematic-weather-update.md) — strengthen weather and wind as visual spectacle.
2. [`vNext: Sailing Prototype`](vnext-sailing-prototype.md) — prove wind as a simple vehicle resource without touching aircraft handling.

Older weather-station, wind-power, and ventilation phases remain valid later work, but they are no longer the next
mainline priority.

## Maintenance Principles

### 1. Gameplay Uses `GameplayWindSample`

All gameplay-facing systems should depend on `GameplayWindSample`.

Examples:

- full wind-turbine progression and balance,
- flight feedback,
- weather stations,
- smoke and gas mechanics,
- ventilation,
- Create-style integrations,
- future forecast products.

`AeroWindSample` remains available as the raw/debug sampling record, but gameplay should not directly depend on raw LBM values.

### 2. L0/L1 Are The Authoritative Weather Resource

L0/L1 provide the server-authoritative world wind:

- mean wind,
- gust potential,
- pressure,
- temperature,
- humidity,
- shear,
- turbulence,
- updraft proxies,
- confidence.

These layers should remain cheap, deterministic, and reliable on multiplayer servers.

### 3. L2 Is A Local Modifier

L2 should be treated as local voxel airflow, not as the main source of world weather.

L2 is responsible for:

- shelter behind buildings,
- duct and chimney flow,
- local fan jets,
- heat plume correction,
- wake/turbulence near obstacles,
- visualization.

Client-local L2 is useful for visuals and local feedback. It must not become a trusted server gameplay source without validation.

### 4. Player Experience Wins Over Hidden Fidelity

If a physically richer change cannot be observed, predicted, or used by players, it is lower priority than a simpler rule with clear feedback.

The main success metric is not MLUPS, solver order, or strict CFD realism. The main success metric is whether players can learn and use wind.

## v0.1: Playable Wind API Release

Target date: **2026-05-04**.

### Goal

Ship a stable first public version that proves the core premise:

> Minecraft has a usable wind field, and other systems can safely sample it.

### Included

- Server-authoritative L0/L1 weather wind.
- Client-local L2 marked experimental and not required for gameplay.
- `GameplayWindSample` API as the recommended gameplay contract.
- `AeroWindSample` retained for raw/debug sampling.
- Wind meter displays `GameplayWindSample` for field inspection.
- Wind Turbine Probe demonstrates Gameplay API consumption with redstone output.
- Viridis vector/streamline visualization for local flow debugging.
- Native runtime packaged for supported desktop platforms.
- Public documentation for installation, API usage, and architecture.

### Release Checklist

Must be true before tagging v0.1:

- `./gradlew compileJava compileClientJava processResources processClientResources` passes.
- Native build passes locally.
- GitHub Actions native matrix passes on Windows, Linux, and macOS.
- No known startup crash on systems without OpenCL.
- The runtime starts automatically; `/aero status`, `/aero dump_l1`, and GameplayWindSample wind meter output work in a clean world.
- Wind Turbine Probe updates output and emits redstone in a clean world.
- Default gameplay path does not require server-authoritative L2.
- Client L2 is clearly labeled experimental.
- README links to the release roadmap and wind sampling API.
- `GameplayWindSample` is documented as the gameplay-facing API.
- Known limitations are listed in the release notes.

### v0.1 Non-Goals

Do not block v0.1 on:

- wind turbines,
- weather stations,
- forecast maps,
- Create integration,
- aircraft aerodynamic calculation,
- physically complete NWP,
- full L2 gameplay coupling,
- local mesh refinement,
- offline CFD service.

These belong to later versions.

## vNext A: Cinematic Weather Update

### Goal

Make wind and storms visually compelling without requiring players to care about solver details.

Full scope: [`docs/vnext-cinematic-weather-update.md`](vnext-cinematic-weather-update.md).

### Planned Work

- Strengthen dust, leaves, grass, smoke, rain, snow, fire, and wind ambience.
- Use L1/L0 as the default visual weather source.
- Let client-local L2 add local detail where available, but never require it for ordinary weather visuals.
- Add storm precursors:
  - pressure drop,
  - wind shift,
  - gust pulses,
  - stronger exposed-place ambience.
- Improve rare tornado / vortex presentation as visual weather, not as default world destruction.

### Success Criteria

- Strong wind is visible without debug overlays.
- Storms have readable visual precursors.
- Dust, leaves, smoke, rain, and sound agree on wind direction.
- Disabling client L2 still leaves convincing weather visuals.

## vNext B: Sailing Prototype

### Goal

Prove that wind can drive a simple vehicle in a way players expect and enjoy.

Full scope: [`docs/vnext-sailing-prototype.md`](vnext-sailing-prototype.md).

### Planned Work

- Add a reduced-order 2D sailing force model.
- Use `GameplayWindSample` as the environment input.
- Provide automatic sail trim by default.
- Add simple player feedback for headwind / side wind / tailwind states.
- Keep the prototype independent from aircraft and Create: Aeronautics flight physics.

### Success Criteria

- A player can cross water using wind without reading documentation.
- Favorable wind clearly improves speed.
- Poor wind changes route planning without making control miserable.
- The system does not require local CFD.

## v0.2: Wind Features And Stronger Wind Resources

### Goal

Make wind more player-visible and useful without waiting for fully self-emergent weather.

Status: superseded as the immediate next release by the Cinematic Weather Update. The `WindFeature` idea can still
support storm visuals later.

### Planned Work

- Add a `WindFeature` system in the world-scale driver.
- Initial feature types:
  - gust band,
  - frontal wind shift,
  - thermal bubble,
  - valley/ridge jet,
  - storm outflow.
- Feed features into L0/L1 and then into `GameplayWindSample`.
- Ensure calm, steady, windy, gusty, and stormy states are all reachable.
- Add diagnostics for nearby active wind features.

### Success Criteria

- Players can encounter useful 3-7 m/s wind outside cyclone cores.
- Strong wind has readable causes and spatial structure.
- Gusts are predictable enough for gameplay, not pure random noise.

## v0.3: Weather Stations And Forecast Confidence

### Goal

Turn wind from a hidden field into an observable weather system.

### Planned Work

- Add weather station block/entity.
- Track time series:
  - mean wind,
  - gust,
  - pressure,
  - temperature,
  - humidity,
  - turbulence,
  - confidence.
- Add station quality model:
  - height,
  - exposure,
  - obstruction,
  - terrain class,
  - sampling history.
- Let station networks improve local forecast confidence.
- Add basic forecast data products for future UI.

### Success Criteria

- Players can build better measurement networks.
- Forecast confidence depends on station placement.
- Weather becomes a planning tool, not only ambience.

## v0.4: Wind Power Gameplay

### Goal

Make wind a resource.

### Planned Work

- Add minimal wind turbine gameplay or expose stable turbine API.
- Power curve based on `GameplayWindSample.meanWind`.
- Penalties:
  - `shelterFactor`,
  - excessive turbulence,
  - poor placement,
  - wake interaction.
- Add turbine diagnostics for players:
  - wind speed,
  - output factor,
  - turbulence penalty,
  - shelter penalty.

### Success Criteria

- Wind farm placement matters.
- Exposed ridges and open plains are valuable.
- Building and terrain decisions affect output in a readable way.

## v0.5: L2 Semantic Local Airflow

### Goal

Make L2 useful for buildings without making gameplay depend on raw voxel CFD.

### Planned Work

Extract semantic local modifiers from L2:

- `shelterFactor`,
- `wakeTurbulence`,
- `jetContribution`,
- `ventilationFlow`,
- `thermalPlume`,
- `ductFlow`,
- `chimneyDraft`.

Feed these into `GameplayWindSample` and local block systems.

### Success Criteria

- Opening a vent improves ventilation.
- Chimneys and heat sources produce useful draft.
- Fans and ducts are predictable.
- Walls and buildings create meaningful wind shadows.

## v0.6: Integration API

### Goal

Make Aerodynamics4MC useful to other mods without exposing internal solver state.

### Planned Work

- Stabilize `com.aerodynamics4mc.api`.
- Document examples for:
  - wind turbines,
  - gliders/aircraft,
  - particles,
  - weather-aware blocks,
  - server-side gameplay.
- Add optional integration hooks:
  - wind consumer registration,
  - local source/obstacle registration,
  - forecast query,
  - turbine/fan metadata.
- Keep Create/Aeronautics integration as an addon or separate module, not as a hard dependency.

### Success Criteria

- Other mods can consume wind with no dependency on `L0`, `L1`, `L2`, packet formats, or native buffers.
- Server-trusted and client-visual paths are clearly separated.

## v1.0: Stable Weather And Airflow Platform

### Goal

Reach a stable release suitable for modpacks and public servers.

### Required Capabilities

- Stable public API.
- Configurable gameplay wind strength.
- Server-safe default performance.
- Weather stations and forecast confidence.
- Wind power or equivalent resource gameplay.
- L2 local airflow semantics.
- Clear documentation.
- Cross-platform native packaging.
- Reasonable fallback when native acceleration is unavailable.

## Deferred Work

These are valid future directions but not part of the main release path before v1.0:

- offline wing/propeller CFD,
- high-resolution dynamic boundary CFD service,
- local adaptive mesh refinement,
- self-emergent full weather simulation,
- full NWP-style data assimilation,
- vehicle-specific aerodynamic solvers,
- paid hosted forecast/CFD service.

They should not block the core mod from becoming a playable wind and weather system.

## Commercial Release Strategy

The public mod should remain open and easy to install.

Commercial value should come from:

- official builds,
- documentation,
- compatibility work,
- server support,
- integration addons,
- hosted forecast/CFD services later,
- community funding,
- technical credibility.

Do not monetize by locking core gameplay features behind a paid build.

## One-Line Direction

Build a stable wind gameplay platform first; let higher-fidelity CFD and advanced integrations grow on top of that platform later.
