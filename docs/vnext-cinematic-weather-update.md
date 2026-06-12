# vNext: Cinematic Weather Update

## Position

This update moves Aerodynamics4MC toward a high-end wind and weather visual mod.

The core promise is not "scientific CFD in normal gameplay". The promise is:

> Minecraft air and weather should look like a physical system.

Players should notice moving air through storms, dust, leaves, rain, smoke, clouds, wind sound, and local flow
visualization. They should not need to understand LBM, L0, L1, L2, cumulant collision, or any internal solver
term.

## Why This Is The Next Mainline

Community feedback points toward visual spectacle much more strongly than toward aircraft CFD:

- players compare the project to Weather 2-style storm mods,
- players compare the project to Physics Mod-style high-end visual simulation,
- very few players ask for CFD-derived aircraft handling,
- aircraft wind forces risk making vehicles feel worse unless they are opt-in.

The mod's strongest public signal has been video appeal. The next release should lean into that instead of
trying to justify every system as long-term survival gameplay.

## Product Goal

Make wind visible at world scale.

The player should be able to stand in a world and understand that:

- a storm is approaching,
- wind direction has shifted,
- strong gust bands are passing through,
- dust, leaves, rain, smoke, and fire are responding to the same air,
- tornadoes and cyclone-like systems have coherent wind structure.

## Primary Features

### Storm Presentation

- rotating storm bands,
- strong wind ambience in exposed places,
- pressure-drop and wind-shift cues before severe weather,
- rain and snow slant tied to local wind,
- gust pulses that move through ground particles and foliage,
- rare tornado / vortex events with readable precursor wind.

### Ground-Level Wind Traces

- dust streaks on sand, red sand, dirt, snow, and exposed ground,
- leaf and grass motes that intensify during gusts,
- stronger spawn rates in exposed terrain,
- lower spawn rates in enclosed or sheltered positions.

### Smoke, Fire, And Heat Plumes

- campfire and torch smoke lean with wind,
- flame particles tilt subtly under strong air,
- hot sources produce local vertical hints,
- local L2 can refine visuals when active, but the default effect must still work from L1/L0.

### Weather Debug As A Creative Tool

Debug visualization remains useful, but it should not be the main product surface.

Keep:

- velocity vectors,
- streamlines,
- dump commands,
- solver diagnostics.

Add only if needed:

- a small cinematic weather status command,
- a creative/admin command to trigger storm visual states for recording videos.

Current command surface:

- `/aero cinematic storm [intensity] [duration_seconds]` applies a client-local visual storm override and visual-wind sample override.
- Omitting `duration_seconds` keeps the override active until `/aero cinematic clear`; passing a positive duration makes it expire.
- `/aero cinematic clear` removes the override.
- This command is for recording and tuning; it does not change server weather or trusted gameplay wind.

## Technical Policy

### Default Source

Use `GameplayWindSample` / coarse server wind for default visual events.

Client-local L2 may add local detail near fans, obstacles, or heat sources, but should not be required for:

- ordinary storm visuals,
- dust and leaf wind traces,
- rain tilt,
- wind ambience,
- tornado precursor effects.

### Authority

Visual effects may use client-local data.

Gameplay-affecting behavior must stay server-trusted and must not depend on client-local L2 unless a later
validation design exists.

### Performance

The update should prefer many cheap, coherent visual hints over a small number of expensive solver showcases.

Targets:

- no always-on high-resolution local CFD requirement,
- particle budgets controlled by exposure, wind speed, and gust pulse,
- server state sync at current coarse-weather cadence unless there is a clear reason to change it.

## Non-Goals

This update does not promise:

- scientifically validated weather simulation,
- accurate hurricane forecasting,
- default aircraft handling changes,
- full block destruction by storms,
- server-authoritative high-resolution L2,
- global Navier-Stokes weather.

Block/entity damage from storms should remain disabled by default or be clearly configurable.

## Implementation Order

1. Strengthen existing wind presence effects.
2. Add storm and gust amplification from local weather data.
3. Add rain/snow tilt and stronger wind ambience.
4. Add admin/creative controls for recording cinematic weather.
5. Add rare tornado/vortex presentation polish.

## Acceptance Criteria

The update is successful when:

- strong wind is visible without opening a debug overlay,
- storms have readable visual precursors,
- dust, leaves, smoke, rain, and sound agree on wind direction,
- disabling client L2 still leaves convincing weather visuals,
- the mod can produce compelling short video demos without relying on aircraft CFD.
