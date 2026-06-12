# vNext: Sailing Prototype

## Position

Sailing is the first gameplay loop where wind naturally matters.

Unlike aircraft, players expect wind to affect a sailboat. Wind influence is not a surprise difficulty spike; it
is the point of the vehicle.

The prototype should prove:

> A player can read the wind, set or trust the sail, and move across water because of real world wind.

## Product Goal

Add a small, understandable wind-powered vehicle prototype.

The player should be able to:

- place or use a sailboat,
- see wind direction,
- move faster with favorable wind,
- struggle or tack under poor wind,
- optionally use automatic sail trim,
- understand the result without knowing aerodynamics.

## Why Sailing Comes Before Aircraft Coupling

Aircraft coupling is risky:

- players may read wind forces as broken controls,
- Aeronautics already owns its flight physics,
- accurate aircraft CFD is outside the current scope,
- bad coupling would damage compatibility expectations.

Sailing is safer:

- wind is expected to be the main driver,
- lower speeds make control forgiving,
- a 2D apparent-wind model is enough for useful gameplay,
- the same system can later support Create-style sail blocks or larger ships.

## Core Model

The first version should use a reduced-order 2D sailing model:

```text
apparentWind = worldWind - boatVelocity
sailForce    = f(apparentWindSpeed, angleToSail, sailArea, trim)
hullDrag     = f(forwardSpeed, lateralSlip)
rudderTurn   = f(speed, steeringInput)
```

Use `GameplayWindSample` as the environment input.

Do not use the cumulant D3Q27 solver as the runtime boat physics source.

## Controls

The prototype should default to approachable controls:

- steering controls heading,
- sail trim is automatic by default,
- manual trim is optional,
- wind HUD or item feedback shows headwind / beam reach / tailwind style states.

Manual trim can be added as an advanced mode, but it must not be required for the first playable prototype.

## Tuning Targets

The boat should feel:

- faster than a vanilla boat in good wind,
- slower or awkward directly upwind,
- stable enough for casual play,
- responsive enough to demonstrate wind as a resource.

Avoid:

- hard simulation stalls,
- constant capsizing,
- mandatory tacking for casual travel,
- requiring aviation or sailing knowledge.

## Integration Boundaries

This is not a Create: Aeronautics flight integration.

Possible later integrations:

- Create contraption sail blocks,
- larger ship hulls,
- autopilot or redstone sail controls,
- route planning with the meteorological map.

Out of scope for the prototype:

- accurate keel dynamics,
- full naval architecture,
- wave simulation,
- rigid-body ship construction,
- fluid-structure CFD.

## Implementation Order

1. Add a pure Java sailing force model with unit-style tests.
2. Add a minimal sailboat entity or attachable sail behavior.
3. Add client feedback for wind angle and trim state.
4. Tune automatic trim and speed caps.
5. Add optional manual trim.
6. Consider Create or Aeronautics compatibility only after the standalone prototype feels good.

## Acceptance Criteria

The prototype is successful when:

- a player can cross water using wind without reading documentation,
- favorable wind clearly improves speed,
- poor wind clearly changes route planning,
- automatic trim prevents the system from feeling punishing,
- the model uses Aerodynamics4MC wind data but does not require local CFD.

