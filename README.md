![logo](docs/dsotm_v19_default_288.png)

[![Build Status](https://github.com/MozillaFiredoge/Aerodynamics4MC-Fabric/workflows/build/badge.svg)](https://github.com/MozillaFiredoge/Aerodynamics4MC-Fabric/actions)
[![Native Matrix](https://github.com/MozillaFiredoge/Aerodynamics4MC-Fabric/workflows/native-matrix/badge.svg)](https://github.com/MozillaFiredoge/Aerodynamics4MC-Fabric/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
    <a href="https://modrinth.com/mod/Aerodynamics4MC">
        <img src="https://img.shields.io/modrinth/dt/Aerodynamics4MC?logo=modrinth&amp;label=&amp;suffix=%20&amp;style=flat&amp;color=242629&amp;labelColor=5CA424&amp;logoColor=1C1C1C" alt="Modrinth Download"/>
    </a>
> **Aerodynamics4MC** brings a multi‑scale, real‑time wind and weather system to Minecraft. Coarse weather is server‑authoritative, an optional high‑resolution CFD layer runs on the client for visualization, and external mods consume wind through a clean public API without touching internal solver buffers.
---

## 🔌 Wind Sampling API (for Mod Developers)

Other mods consume wind through the standalone `aerodynamics4mc-api` artifact. The API module is Minecraft-free: public signatures use stable `A4mc*` value types instead of Mojang/Yarn classes, so integrations are less sensitive to Minecraft or loader mapping changes.

Add the GitHub Pages Maven repository:

```kotlin
repositories {
    maven("https://mozillafiredoge.github.io/Aerodynamics4MC-Fabric/maven/")
}
```

Depend on the API for compilation only. The main Aerodynamics4MC mod embeds and provides the API at runtime:

```kotlin
dependencies {
    compileOnly("io.github.mozillafiredoge:aerodynamics4mc-api:0.2.0")
}
```

The distributed mod jar remains an all-in-one gameplay jar for players. Its built-in blocks, particles, vehicles, and client visual code are kept behind internal source-set boundaries, but they are not a public integration surface. Add-on mods should depend only on the published API artifact and should not import `com.aerodynamics4mc.block.*`, `com.aerodynamics4mc.particle.*`, `com.aerodynamics4mc.vehicle.*`, `com.aerodynamics4mc.client.*`, or loader-specific internals. This keeps integrations portable across Minecraft versions and mod loaders.

**Server‑side sampling:**

```java
import com.aerodynamics4mc.api.A4mcBlockPos;
import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroWindApi;
import com.aerodynamics4mc.api.GameplayWindSample;
import com.aerodynamics4mc.api.SamplePolicy;

A4mcWorldRef world = A4mcWorldRef.server(A4mcId.of("minecraft", "overworld"));
A4mcBlockPos position = A4mcBlockPos.of(x, y, z);

GameplayWindSample wind = AeroWindApi.sampleGameplay(
    world,
    position,
    SamplePolicy.GAMEPLAY_SERVER_ONLY
);

if (wind.isTrustedForGameplay()) {
    A4mcVec3 mean = wind.meanVelocityVector();
    A4mcVec3 effective = wind.effectiveVelocityVector();
    float turbulence = wind.turbulenceIntensity();
}
```

**Client‑side sampling (visuals):**

```java
import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.client.AeroClientWindApi;

A4mcWorldRef world = A4mcWorldRef.client(A4mcId.of("minecraft", "overworld"));
A4mcVec3 position = A4mcVec3.of(x, y, z);

AeroWindSample sample = AeroClientWindApi.sample(
    world,
    position,
    SamplePolicy.CLIENT_LOCAL_PREFERRED
);
A4mcVec3 visualDrift = sample.effectiveVelocityVector();
```

**Recommended integration patterns:**

- Aircraft, airships, turbines, gameplay logic → `AeroWindApi.sampleGameplay(...)` + `isTrustedForGameplay()` check.
- Built-in example → place a **Wind Turbine Probe** to see `GameplayWindSample` become redstone output.
- Client particles (smoke, steam, dust) → `SamplePolicy.CLIENT_LOCAL_PREFERRED`.
- Engineering overlays and diagnostics → `AeroWindApi.sample(...)` + `SamplePolicy.VISUAL_LOCAL_FIRST`.
- If your mod is intentionally tied to the same Aerodynamics4MC Minecraft build, the main mod also exposes `com.aerodynamics4mc.api.minecraft.*` bridge helpers for Minecraft `Level`, `Player`, `BlockPos`, and `Vec3`. Cross-version integrations should prefer the pure `A4mc*` API.

Full API contract: [`docs/wind-sampling-api.md`](docs/wind-sampling-api.md)

---

## 🔧Native Code(IMPORTANT, NECESSARY)
Manual native library build:

```bash
cd native
cmake -S . -B build
cmake --build build -j
```

See [`native/README.md`](native/README.md) for cross‑platform superbuild details. If you don’t build the native library, the mod will attempt to use an embedded pre‑built binary.

---

## ⚠️ Known Boundaries

| Scenario                       | Current State                                          |
|--------------------------------|--------------------------------------------------------|
| Near‑sonic aircraft            | Not supported by the in‑game solver.                   |
| Dynamic propeller geometry     | Not in the public wind‑tunnel API.                     |
| Multiplayer L2 feedback        | Requires future validation/aggregation design.         |
| Server‑authoritative L2        | Not the default path; diagnostic use only.             |
| Voxel‑to‑airfoil design        | Out of scope for the runtime API.                      |

For Create:Aeronautics‑style integration, use `SERVER_COARSE_ONLY` for world wind and a separate vehicle‑relative aerodynamic model for wing/propeller coefficients.

---

## 📚 Documentation Map

**Wind system (start here):**

| Document                                                              | Purpose                                    |
|-----------------------------------------------------------------------|--------------------------------------------|
| 📘 [`docs/wind-system-overview.md`](docs/wind-system-overview.md)     | **Authoritative wind‑system overview**     |
| [`docs/release-roadmap.md`](docs/release-roadmap.md)                  | Release roadmap and version boundaries     |
| [`docs/vnext-cinematic-weather-update.md`](docs/vnext-cinematic-weather-update.md) | Current vNext visual-weather plan |
| [`docs/vnext-sailing-prototype.md`](docs/vnext-sailing-prototype.md) | Current vNext wind-powered vehicle prototype |
| [`docs/wind-sampling-api.md`](docs/wind-sampling-api.md)             | Public sampling API contract               |
| [`docs/world-scale-weather-design.md`](docs/world-scale-weather-design.md) | Driver phenomenology (older naming)   |
| [`docs/wind-shear-weather-roadmap.md`](docs/wind-shear-weather-roadmap.md) | ABL / wind‑shear roadmap             |
| [`docs/player-facing-wind-design.md`](docs/player-facing-wind-design.md) | Product philosophy                     |

**Native solver:**

| Document                                                                   | Purpose                      |
|----------------------------------------------------------------------------|------------------------------|
| [`native/README.md`](native/README.md)                                     | Native build details         |
| [`docs/native-jni-interface-reference.md`](docs/native-jni-interface-reference.md) | JNI / channel‑layout ref |
| [`docs/native-physics-engine-todo.md`](docs/native-physics-engine-todo.md) | Native solver to‑do          |
| [`native/docs/wind_tunnel_solver_api.md`](native/docs/wind_tunnel_solver_api.md) | Standalone C ABI        |

**Integration & legacy:**

| Document                                                                         | Purpose                        |
|----------------------------------------------------------------------------------|--------------------------------|
| [`docs/shaderpack-wind-compat-design.md`](docs/shaderpack-wind-compat-design.md) | Iris / BSL bridge design       |
| [`docs/on-demand-l2-prefetch-design.md`](docs/on-demand-l2-prefetch-design.md)   | Brick‑prefetch (aspirational)  |
| [`docs/local-air-patch-design.md`](docs/local-air-patch-design.md)               | Legacy patch concept (deprecated) |
| [`docs/phase2-completion-log.md`](docs/phase2-completion-log.md) etc.            | Historical milestones          |

---

## 📄 License

MIT. See repository license files for details.
[![Native Matrix](https://github.com/MozillaFiredoge/Aerodynamics4MC-Fabric/workflows/native-matrix/badge.svg)](https://github.com/MozillaFiredoge/Aerodynamics4MC-Fabric/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
