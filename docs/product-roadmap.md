# Product Roadmap

## Product Thesis

`aerodynamics4mc` should not sell itself as a solver stack.

After community feedback, the player-facing promise is simpler:

**Minecraft air and weather look physically alive, and wind can power a few clear toys.**

That means the mod should make these three things true:

- air is **readable**
- air is **self-consistent**
- air is **spectacular first, useful where it naturally fits**

If the player only learns that the code contains `L0/L1/L2`, the mod has failed.
If the player sees storms, dust, leaves, smoke, rain, and wind-driven vehicles behaving as if air actually moves,
the mod has succeeded.

## What The Mod Is

The mod is best positioned as:

**a cinematic wind, weather, and airflow visual mod with optional wind-powered gameplay**

It sits closer to:

- Ambient Sounds
- shaderpacks
- weather mods
- Physics Mod-style visual simulation
- occasional vehicle or automation addons that want wind data

than to:

- aircraft construction mods
- hard-simulation vehicle mods
- scientific CFD tools

This does not forbid future integrations with flight or vehicle mods, but that should not define the core product.

## What The Mod Is Not

These are not the mainline product goals right now:

- building a general aircraft design sandbox
- competing with Valkyrien Skies as a vehicle platform
- making Create: Aeronautics aircraft harder to fly by default
- replacing `L0/L1/L2` with neural solvers
- adding a new `L3` just because ML is available
- shipping patched copies of third-party shaderpacks

Those directions either duplicate existing ecosystems or add technical weight without improving player-facing value enough.

## Core Player Fantasy

The player should gradually realize:

- wind has direction and structure
- storms have visible wind, dust, sound, and particle structure
- storms have precursors and local consequences
- terrain shapes the air in ways that can be seen
- wind can drive simple things such as sails

The strongest version of the mod is not “the most advanced CFD.”
It is “Minecraft air is finally visible.”

## Current vNext Direction

### vNext A: Cinematic Weather Update

Primary doc: [`docs/vnext-cinematic-weather-update.md`](vnext-cinematic-weather-update.md)

Make wind and storms a visual spectacle:

- dust streaks,
- leaves and grass motes,
- smoke and fire response,
- rain and snow slant,
- wind ambience,
- storm precursors,
- rare tornado / vortex presentation.

This is the next mainline because it matches the strongest community signal: players want high-end air and
weather visuals, not aircraft CFD.

### vNext B: Sailing Prototype

Primary doc: [`docs/vnext-sailing-prototype.md`](vnext-sailing-prototype.md)

Prove one concrete wind-powered gameplay loop:

- use `GameplayWindSample`,
- model apparent wind,
- offer automatic sail trim by default,
- keep the vehicle forgiving,
- do not use L2 CFD as the runtime boat physics source.

This comes before aircraft coupling because sailing naturally expects wind to matter.

## Roadmap Principles

### 1. Prioritize perception over hidden fidelity

A physically richer system that cannot be seen or used is lower priority than a simpler system with strong feedback.

### 2. Prefer visible spectacle over hidden utility

If a feature is mostly hidden behind instruments, it is lower priority than visible wind traces, storm motion, or
wind-driven travel.

### 3. Make the player discover rules naturally

The player should infer air behavior from repeated world interactions.
The mod should not depend on the player reading a technical manual.

### 4. Keep the physics stack as infrastructure

`L0/L1/L2` exist to support the world fantasy.
They are not the feature list.

## Roadmap Phases

## Phase 1: Make Air Readable

Goal:

The player should be able to see and hear that air is behaving differently from place to place.

Deliverables:

- foliage motion that combines stable background wind with local `L2` boosts
- directional smoke and steam
- directional fire/flame behavior
- rain streak tilt and storm gust cues
- stronger wind/audio ambience in exposed places
- minimal debug/readability tools for development:
  - keep `/aero dumpdata`
  - keep snapshot scripts stable
  - optionally add one very small in-game indicator block/item later

Why this phase comes first:

Right now most of the system is still “backend truth.”
This phase turns it into something players can notice without explanation.

Success criteria:

- players can tell sheltered and exposed places apart without opening debug overlays
- vegetation and smoke make strong flows obvious
- storms feel spatially structured rather than globally cosmetic

## Phase 2: Make Air Useful In Building Gameplay

Goal:

The player should be able to exploit airflow and heat in construction.

Deliverables:

- chimney / vent / exhaust behavior
- indoor vs outdoor ventilation differences
- heat accumulation in enclosed spaces
- greenhouse / shelter / windbreak behavior
- ducts and fans that are easier to reason about from in-world feedback

Target player behaviors:

- placing vents to improve airflow
- using chimneys to remove smoke and hot air
- designing shelters for storms or cold winds
- building around terrain wind shadows and ridge gusts

Success criteria:

- there are clear build choices that feel better or worse because of air behavior
- players can improve a build by reasoning about circulation
- the mod creates new architectural decisions instead of only visual ambience

## Phase 3: Make Weather Feel Like A System

Goal:

Weather should stop feeling like a skybox event and start feeling like a spatial environmental process.

Deliverables:

- cyclone / convective structure made perceptible through the world
- pre-storm cues:
  - wind shift
  - stronger gusts
  - humidity / haze / audio changes
- rare tornadoes kept as exceptional events
- storm-safe vs storm-exposed builds becoming legible

Important constraint:

Tornadoes should stay rare and meaningful.
They are not the backbone of the product.

Success criteria:

- players can notice weather changing before the visible event peaks
- terrain and buildings matter during storms
- tornadoes feel like rare outcomes of a broader system, not random special effects

## Phase 4: Lightweight Gameplay Coupling

Goal:

Air starts to matter to motion and survival in restrained ways.

Deliverables:

- light gliding / updraft interaction
- light projectile drift
- wind chill / heat stress style feedback if it remains readable
- selective entity/environment coupling where it adds clarity

Guardrail:

Do not let this phase turn into constant player-control frustration.
The world should feel more believable, not more annoying.

Success criteria:

- the player can use or compensate for airflow in limited but meaningful ways
- effects are noticeable in interesting contexts, not constantly intrusive

## Phase 5: Public Integration Surface

Goal:

Other mods can consume the air world without this mod becoming a vehicle platform itself.

Deliverables:

- stable local wind sampling API
- documented shader/runtime bridge contract
- optional interoperability points for:
  - gliders
  - projectiles
  - weather-aware blocks
  - shaderpacks

This is the correct place to support vehicle or flight mods indirectly.
It is not necessary to own that gameplay ourselves.

## Immediate Next Steps

The highest-value next work is:

1. Make cinematic wind presence stronger:
   - dust, leaves, grass, smoke, fire, rain, snow, and ambience.
2. Express storm precursors through visible effects:
   - wind shift, gust pulses, pressure drop, exposed-place audio.
3. Keep aircraft effects visual-only by default:
   - no default handling changes for Create: Aeronautics or other aircraft mods.
4. Start the sailing prototype with a reduced-order model:
   - apparent wind, automatic trim, forgiving control.

## Current Recommended Backlog

### Do next

- implement the Cinematic Weather Update in small visual slices
- add a minimal storm/gust amplification path for existing wind presence effects
- make rain/snow/fire/smoke agree better with wind
- write the sailing force model before any boat entity work
- maintain `dumpdata` and snapshot tooling, but do not make debug overlays the main product path

### Defer until later

- generalized monolithic inspection tooling beyond what is needed to validate the patch
- always-on client-local `128^3` CFD as a default gameplay layer
- cloud rendering
- ML `L3`
- aircraft gameplay or aircraft force coupling
- broad entity physics coupling
- generalized shaderpack family support beyond Iris/BSL
- weather stations, building ventilation, and wind-power progression unless they support the new visual/sailing line

## ML Position

Machine learning is still allowed, but it should be subordinate to the product roadmap.

The right future use of ML is:

- short-horizon local refinement
- subgrid turbulence / wake enhancement
- derived environmental proxies

The wrong use of ML right now is:

- adding an `L3` before the current system has enough player-facing consumers

In short:

**first make the world look alive, then decide which gameplay loops deserve deeper physics.**

## One-Sentence Roadmap Summary

The next version of the mod should focus on making air **visible and cinematic**, then prove wind as gameplay through
**sailing**, before taking on broader systems again.
