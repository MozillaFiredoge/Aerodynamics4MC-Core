# Wind System Overview / 风系统总纲

> **Audience / 适用读者**: maintainers and contributors who need to understand, modify, or extend
> Aerodynamics4MC's wind generation. / 需要理解、修改或扩展 Aerodynamics4MC 风场产生逻辑的维护者与贡献者。
>
> **Scope / 范围**: this document is the single entry point for the wind system. It tells you *what* runs,
> *where* it lives, *how* the math works, and *which* knobs to turn. Detailed design notes for individual
> subsystems live in sibling files under `docs/` (linked at the bottom). / 本文是风系统的总入口，覆盖
> 「跑什么、在哪、原理是什么、调哪个常量」。各子系统的深度设计文档在 `docs/` 同级目录中（文末汇总）。
>
> **Status / 状态**: written 2026-05-03 against the code on `main`. Cross-checked file:line references are
> from `fabric-mod/src/main/java/com/aerodynamics4mc/runtime/`. / 2026-05-03 基于 `main` 分支代码写成，
> 文件:行号引用均已与代码核对。

---

## 1. 三层嵌套与 WorldScaleDriver / Three-Layer Nesting

The mod runs **four cooperating components**, organized as one driver and three nested grids:

本 mod 由 **四个协同组件** 构成，组织为「一个驱动器 + 三层嵌套网格」：

| Component | Java class | Role | 角色 | Resolution / 分辨率 | Trust / 信任级别 |
|-----------|-----------|------|------|---------------------|-----------------|
| **WorldScaleDriver** | `runtime.WorldScaleDriver` | Planetary-scale weather driver: cyclones, convective clusters, tornadoes, storm activity, planetary waves | 行星尺度天气驱动：气旋、对流团、龙卷、风暴活动度、行星波 | 384×384 「pressure-domain cells」, 256 blocks/cell, 1 layer | Server-authoritative |
| **L0 — BackgroundMetGrid** | `runtime.BackgroundMetGrid` | Coarse synoptic field consumed by all downstream layers; semi-Lagrangian advect-diffuse-relax with geostrophic adjustment, terrain drag, roughness | 下游各层共用的「天气尺度」粗网格；半拉格朗日 advect-diffuse-relax + 地转调整 + 地形阻力 + 粗糙度 | **41×41** cells, 256 blocks/cell, 1 layer | Server-authoritative |
| **L1 — MesoscaleGrid** | `runtime.MesoscaleGrid` | Layered terrain-following meso-α/β grid; ABL shear, Ekman turning, terrain bounce-back, gust/turbulence diagnostics; produces L2 boundary forcing | 地形跟随的中尺度分层网格；ABL 风切变、Ekman 偏转、地形反弹、湍流/阵风诊断；产出给 L2 的边界 forcing | **33×33×8** cells, 64×64×40 blocks/cell | Server-authoritative |
| **L2 — Native LBM** | `runtime.NativeSimulationBridge` (server) / `client.ClientL2Solver` (client) | High-resolution voxel CFD: D3Q27 cumulant LBM + SGS + Boussinesq; obstacles, fans, thermal sources | 高分辨率体素 CFD：D3Q27 cumulant LBM + SGS + Boussinesq；障碍物、风扇、热源 | Server: 64³ window, 1 block/voxel · Client: 32³ brick | **Client-local by default** (server-authoritative L2 is disabled) |

> ⚠ **Naming caveat / 命名澄清**: Older docs sometimes called the world-scale driver "L0" and the
> background grid "L1". This document and `CLAUDE.md` use the **current code naming**: `BackgroundMetGrid` *is* L0;
> `MesoscaleGrid` is L1; the native LBM is L2; `WorldScaleDriver` is the driver above L0. /
> 旧文档有时把驱动器叫 "L0"、把背景网格叫 "L1"。本文与 `CLAUDE.md` 一致地采用 **当前代码** 的命名：
> `BackgroundMetGrid` = L0，`MesoscaleGrid` = L1，native LBM = L2，`WorldScaleDriver` 在 L0 之上。

### 1.1 Top-level data flow / 顶层数据流

```
                  ┌──────────────────────┐
                  │  WorldScaleDriver    │  ← seed, time-of-day, rain/thunder, prior L1 feedback
                  │  cyclones / clusters │
                  │  tornadoes / waves   │
                  └──────────┬───────────┘
                             │  per-cell sample(cellX, cellZ):
                             │   target u/v, pressure anomaly,
                             │   T bias, humidity, convective
                             │   inflow, tornado wind …
                             ▼
                  ┌──────────────────────┐
                  │  L0 BackgroundMetGrid│  ← terrain height, roughness, biome temp
                  │  41×41 / 256 b/cell  │
                  │  advect → diffuse →  │
                  │  geostrophic adjust  │
                  │  → terrain drag →    │
                  │  → roughness drag    │
                  └──────────┬───────────┘
                             │  sample(BlockPos):
                             │   wind, pressure, T, humidity,
                             │   ABL diagnostics
                             ▼
                  ┌──────────────────────┐
                  │  L1 MesoscaleGrid    │  ← terrain mask, block-entity heat sources
                  │  33×33×8             │
                  │  ABL shear / Ekman   │
                  │  terrain follow      │
                  │  builds L2 forcing   │
                  └──────────┬───────────┘
                             │  NestedBoundaryCoupler:
                             │   sponge-layer Dirichlet BC
                             │   into 64³ native window
                             ▼
                  ┌──────────────────────┐
                  │  L2 Native LBM       │  ← block obstacle mask, fan/duct blocks,
                  │  D3Q27 cumulant      │     lava/torch/campfire heat
                  │  64³ server window   │
                  │  (or 32³ client      │
                  │   bricks)            │
                  └──────────┬───────────┘
                             │  L2 → L1 nested feedback (instability,
                             │   lift, low-level shear, moisture
                             │   convergence) — every L2_TO_L1_FEEDBACK_STEPS
                             ▼
                  L1 diagnostics summary back to WorldScaleDriver
                   for storm activity / tornado spawning
```

Coarse-wind sync to clients: every server tick when state changes the server broadcasts an
`AeroCoarseWindPacket` carrying L1 wind sampled on a 32-block grid (`COARSE_WIND_SYNC_CELL_SIZE_BLOCKS`).
Clients use that payload to seed their own `ClientL2Solver` and to answer `AeroClientWindApi` queries.

### 1.2 Per-tick orchestration / 每 tick 编排

Driven from `AeroServerRuntime` (the file is large; the wind path centers on the server tick handler that
calls `driver.advance(...)`, `BackgroundMetGrid.refresh(...)`, `MesoscaleGrid.refresh(...)`,
`MesoscaleGrid.runPendingSteps(...)`).

| Step | Component | Cadence | Notes |
|------|-----------|---------|-------|
| 1 | Sample diagnostics from `MesoscaleGrid` | every tick | Feedback to driver: instability, low-level shear, moisture convergence |
| 2 | `WorldScaleDriver.advance(...)` | every tick (`SOLVER_STEP_SECONDS = 0.05`) | Update cyclones, convective clusters, tornadoes, storm activity, base flow relaxation |
| 3 | `BackgroundMetGrid.refresh(...)` | every `BACKGROUND_MET_REFRESH_TICKS` (= `MESOSCALE_REFRESH_TICKS × 4`) | Advect–diffuse–geostrophic–terrain–roughness on 41×41 grid |
| 4 | `MesoscaleGrid.refresh(...)` | every `MESOSCALE_REFRESH_TICKS` (≈ `MESOSCALE_MET_CELL_SIZE_BLOCKS = 64` ticks) | Rebuild L1 layers + L2 forcing payload from terrain, blocks, L0 sample |
| 5 | `MesoscaleGrid.applyPendingNestedFeedback(...)` | every `L2_TO_L1_FEEDBACK_STEPS` | Push native L2 vorticity / divergence back into L1 cells |
| 6 | `MesoscaleGrid.runPendingSteps(...)` (native LBM) | every tick (accumulator) | Step the D3Q27 solver as many times as `accumulatedDt / solverDt` allows |
| 7 | Broadcast `AeroCoarseWindPacket` | when L1 changed | Used by `AeroClientWindApi` and `ClientL2Solver` |

Constants are declared near the top of `AeroServerRuntime.java` (lines 81–266). The load-bearing ones:

| Constant | Value | File:line |
|----------|-------|-----------|
| `SOLVER_STEP_SECONDS` | 0.05 | `AeroServerRuntime.java` (top of file) |
| `GRID_SIZE` | 64 | `AeroServerRuntime.java:81` |
| `SERVER_AUTHORITATIVE_L2_ENABLED` | `false` | `AeroServerRuntime.java:123` |
| `COARSE_WIND_SYNC_CELL_SIZE_BLOCKS` | 32 | `AeroServerRuntime.java:135` |
| `BACKGROUND_MET_CELL_SIZE_BLOCKS` | 256 | `AeroServerRuntime.java:208` |
| `BACKGROUND_MET_RADIUS_CELLS` | 20 | `AeroServerRuntime.java:209` |
| `MESOSCALE_MET_CELL_SIZE_BLOCKS` | 64 | `AeroServerRuntime.java:210` |
| `MESOSCALE_MET_RADIUS_CELLS` | 16 | `AeroServerRuntime.java:211` |
| `MESOSCALE_MET_LAYER_HEIGHT_BLOCKS` | 40 | `AeroServerRuntime.java:212` |
| `MESOSCALE_MET_MAX_LAYERS` | `max(1, 320 / 40)` = 8 | `AeroServerRuntime.java:213` |
| `MESOSCALE_REFRESH_TICKS` | `round(64 × 0.05 / 0.05)` = 64 | `AeroServerRuntime.java:216` |
| `BACKGROUND_MET_REFRESH_TICKS` | `MESOSCALE_REFRESH_TICKS × 4` = 256 | `AeroServerRuntime.java:220` |
| `L2_TO_L1_FEEDBACK_STEPS` | `MESOSCALE_REFRESH_TICKS` = 64 | `AeroServerRuntime.java:217` |

So in human terms: L0 refreshes every ~12.8 s, L1 every ~3.2 s, L2 steps continuously (up to a few steps
per server tick), the WorldScaleDriver advances every 50 ms.

L0 每 ~12.8 s 刷新一次，L1 每 ~3.2 s 刷新一次，L2 持续步进（每服务端 tick 最多几步），驱动器每 50 ms 推进。

---

## 2. WorldScaleDriver — Planetary-scale Weather / 行星尺度天气

**File**: `fabric-mod/src/main/java/com/aerodynamics4mc/runtime/WorldScaleDriver.java` (~1817 lines).

### 2.1 What it produces / 输出

`WorldScaleDriver.sample(cellX, cellZ)` returns a `Sample` record (around line 1129–1146) used by L0:

| Field | Meaning / 含义 |
|-------|---------------|
| `targetWindX`, `targetWindZ` | Target horizontal wind in m/s before geostrophic adjustment / 地转调整前的目标水平风 |
| `pressureAnomalyPa` | Sea-level pressure anomaly (Pa) / 海平面气压异常 |
| `temperatureBiasKelvin` | Air-mass temperature offset / 气团温度偏置 |
| `humidity` | Normalized [0, 1] / 归一化湿度 |
| `stormActivity` | Storm-activity proxy [0, 1] / 风暴活动度 |
| `convectiveHeatingKelvin`, `convectiveMoistening`, `convectiveInflowX/Z`, `convectiveEnvelope` | Convective-cluster forcing / 对流团强迫 |
| `tornadoWindX/Z`, `tornadoHeatingKelvin`, `tornadoMoistening`, `tornadoUpdraftProxy` | Tornado-vortex forcing / 龙卷强迫 |

### 2.2 Generation principles / 产生原理

Per-cell wind is the sum of three contributions:

每格风由三部分加和得到：

```text
u_target = baseFlow.x
         + waveScale · ( 0.90·sin(φ) + 0.35·sin(ψ) )      // planetary waves
         + Σ cyclone_swirl_and_radial_contribution(cell)   // up to 6 cyclones
         + Σ convective_inflow_contribution(cell)          // up to 6 convective clusters
         + Σ tornado_contribution(cell)                    // up to 2 active tornadoes
clamp to ±MAX_DRIVER_WIND_MPS = ±12 m/s
```

- **Base flow** (`baseFlowX`, `baseFlowZ`): a slowly-relaxing steering current. Relaxes toward a forced
  target at `BASE_FLOW_RELAX_PER_SECOND = 1/900 s⁻¹` (i.e., ≈15-minute timescale).
  慢速松弛的引导气流，时间常数 ~15 分钟。
- **Planetary waves**: deterministic sinusoidal modulation of `(cellX, cellZ)` with spatial wavelengths
  `DRIVER_SPATIAL_SCALE_X = 0.11`, `DRIVER_SPATIAL_SCALE_Z = 0.09`, scaled by a synoptic-lull factor
  `∈ [SYNOPTIC_LULL_MIN_FACTOR = 0.18, 1.0]` so calm periods occur naturally.
  确定性正弦行星波，含 0.18~1.0 的「天气间歇」因子。
- **Cyclones** (`CycloneCell`, lines ~1263–1419): up to `DEFAULT_CYCLONE_CELL_COUNT = 6` semi-permanent
  pressure systems; each emits a swirl + radial wind envelope and a pressure anomaly:
  ```
  swirl_outer    = 10 m/s · intensity · 0.55 · Gaussian(outerNorm) · CoriolisSign
  swirl_core     = 10 m/s · intensity · 1.40 · Gaussian(coreNorm)  · CoriolisSign
  radial_outer   = 3  m/s · intensity · 0.70 · Gaussian(outerNorm)
  radial_core    = 3  m/s · intensity · 1.20 · Gaussian(coreNorm)
  ΔP             = ±1350 Pa · intensity · envelope
  ```
  Lifecycle phase advances at `CYCLONE_CELL_LIFECYCLE_RADIANS_PER_SECOND = TAU / (24000·2/20)`.
- **Convective clusters** (`ConvectiveCluster`, lines ~1421–1682): up to 6 clusters orbiting cyclones,
  producing low-level convergence (peak `CONVECTIVE_CLUSTER_MAX_CONVERGENCE_MPS = 4 m/s`), warm bias,
  and a thermal-low pressure dip up to `CONVECTIVE_CLUSTER_THERMAL_LOW_PA = 240 Pa`.
- **Tornadoes** (`TornadoVortex`, lines ~1684–1816): spawned inside active clusters when
  `stormActivity > TORNADO_MIN_STORM_ACTIVITY = 0.35` and environmental support
  `≥ TORNADO_MIN_SUPPORT = 0.75`. Up to `MAX_ACTIVE_TORNADO_VORTICES = 2` simultaneous; lifetime
  ∈ `[45, 140] s`; tangential wind 18–42 m/s; core radius 10–24 blocks; influence radius 48–128 blocks.

Storm activity (`stormActivity ∈ [0, 1]`) is itself relaxed at `STORM_RELAX_PER_SECOND = 1/600 s⁻¹` and
modulated by L1 feedback (`maxInstabilityProxy`, `meanLowLevelShear`, `maxPositiveMoistureConvergence`)
collected at the start of each tick.

`stormActivity` 自身以 1/600 s⁻¹ 松弛，并被 L1 反馈（不稳定、低层风切、水汽辐合）调制。

### 2.3 Tunable knobs / 可调常量

All in `WorldScaleDriver.java` near the top:

| Name | Value | What changing it does / 调整效果 |
|------|-------|-------------------------------|
| `BASE_FLOW_RELAX_PER_SECOND` | 1/900 | Slower → smoother, more inertial steering wind / 越小风向越「黏」 |
| `STORM_RELAX_PER_SECOND` | 1/600 | Smoothing of storm intensity over time / 风暴强度平滑窗口 |
| `MAX_DRIVER_WIND_MPS` | 12 | Hard cap on driver wind / 驱动器风速上限 |
| `SYNOPTIC_LULL_MIN_FACTOR` | 0.18 | Lower → calmer lulls between weather events / 越小风暴间歇越静 |
| `CYCLONE_CELL_MAX_SWIRL_MPS` | 10 | Peak tangential cyclone wind / 气旋切向峰值风速 |
| `CYCLONE_CELL_MAX_PRESSURE_ANOMALY_PA` | 1350 | Peak ΔP, drives geostrophic wind in L0 / 通过 L0 地转关系决定派生风 |
| `CONVECTIVE_CLUSTER_MAX_CONVERGENCE_MPS` | 4 | Peak inflow at cluster edge / 对流团边缘汇聚风 |
| `TORNADO_MIN_SUPPORT` | 0.75 | Lower → more tornado spawns / 越小越易生成龙卷 |
| `TORNADO_MIN_LIFETIME_SECONDS` / `..._MAX_...` | 45 / 140 | Longevity bounds / 寿命范围 |

### 2.4 If you need to change how L0 wind is generated / 修改 L0 风的产生方式

1. **New phenomenon class** (e.g., a front, monsoon, sea breeze): add a sibling to `CycloneCell` /
   `ConvectiveCluster` / `TornadoVortex`, store a list of active instances on the driver, advance them in
   `advance(...)`, and add their contribution inside `sample(cellX, cellZ)`. Then expose any new
   forcing fields on the `Sample` record so L0 / L1 can read them.
2. **Different synoptic forcing law**: edit the planetary-wave block at the top of `sample(...)` and the
   relaxation step in `advance(...)`. Keep the `MAX_DRIVER_WIND_MPS` clamp; downstream layers assume it.
3. **Coupling to a real heightmap or ocean mask**: the driver currently does not see terrain — feed
   `BackgroundMetGrid` with a richer `SeedTerrainProvider` (`HashedSeedTerrainProvider` /
   `WorldgenSeedTerrainProvider`) instead.

---

## 3. L0 — `BackgroundMetGrid` / 背景天气网格

**File**: `fabric-mod/src/main/java/com/aerodynamics4mc/runtime/BackgroundMetGrid.java` (~1179 lines).

### 3.1 Geometry / 几何

- Horizontal: `(2 · BACKGROUND_MET_RADIUS_CELLS + 1)² = 41 × 41` cells.
- Cell size: `BACKGROUND_MET_CELL_SIZE_BLOCKS = 256` blocks → active footprint ≈ 10.5 × 10.5 km.
- Vertical: 1 column-integrated layer (surface, deep ground, and air column tracked separately as scalars
  in `CellState`).
- Anchored to player focus; cells re-seed when the focus moves.
- Refresh: every `BACKGROUND_MET_REFRESH_TICKS = 256` server ticks (≈ 12.8 s).

### 3.2 Update step / 更新步骤

The core loop is in `BackgroundMetGrid.advanceDynamicField(...)` (around lines 474–579). Each refresh:

1. **Semi-Lagrangian advect**: backtrace each cell along its previous-tick wind, bilinear-interpolate
   the previous state at that point.
2. **Diffuse**: blend the advected state with a 4-neighbor mean (`wind` blend ≈ 0.16, `pressure` blend
   ≈ 0.10).
3. **Geostrophic adjust** (`computeGeostrophicWind(...)`, ~lines 814–841):
   ```
   ∂P/∂x ≈ (P_east  − P_west)  / (2 · cellSizeMeters)
   ∂P/∂z ≈ (P_south − P_north) / (2 · cellSizeMeters)
   u_g   = − ∂P/∂z · GEOSTROPHIC_WIND_SCALE_M2_PER_PA_S · CoriolisSign
   v_g   = + ∂P/∂x · GEOSTROPHIC_WIND_SCALE_M2_PER_PA_S · CoriolisSign
   ```
   Then blend with the driver's direct wind: `wind_target = lerp(blend, u_g, u_driver)` where `blend ∈
   [0.18, 0.32]` depending on `|u_g|`.
4. **Terrain form drag** (`applyTerrainFormDrag(...)`, ~lines 743–797):
   uphill drag `clamp(u·∇h · 0.65, 0, 0.55)`, contour deflection `clamp(|∇h|·0.45, 0, 0.55)` — caps the
   wind into ridges and steers it along contours.
5. **Relaxation** to adjusted target at `FLOW_RELAXATION_PER_SECOND = 1/90 s⁻¹` (≈1.5-minute timescale).
6. **Surface roughness drag**: `clamp(dt · (0.0025 + roughness · 0.01), 0, 0.22)`.
7. **Thermal & moisture relaxation**:
   ```
   T_new = relax(T_advected, T_target, dt, 1/1200) + solar_heating·(1−rain) − clear_sky_cooling·(1−cloud)
   H_new = relax(H_advected, H_target, dt, 1/900)  + evap_boost·(T_surf−T_air)·0.01 + rain·0.05
   ```

The result is published per-`BlockPos` via `BackgroundMetGrid.sample(BlockPos)`, which interpolates
between cells.

### 3.3 Outputs / 输出字段（CellState）

`backgroundWindX/Z`, `geostrophicWindX/Z`, `pressureAnomalyPa`, `ambientAirTemperatureKelvin`,
`surfaceTemperatureKelvin`, `deepGroundTemperatureKelvin`, `humidity`, plus pass-through forcing for
convection (`convectiveHeatingKelvin`, `convectiveMoistening`, `convectiveInflowX/Z`) and tornadoes
(`tornadoWindX/Z`, `tornadoHeatingKelvin`, `tornadoMoistening`, `tornadoUpdraftProxy`).

### 3.4 Tunable knobs / 可调常量

| Name | Value | Effect / 效果 |
|------|-------|--------------|
| `FLOW_RELAXATION_PER_SECOND` | 1/90 | L0 wind adaption speed; faster than driver to allow within-storm evolution |
| `GEOSTROPHIC_WIND_SCALE_M2_PER_PA_S` | 12.0 | Pressure-to-wind conversion factor / 气压→风的转换因子 |
| `GEOSTROPHIC_DIRECT_WIND_BLEND` | 0.18 | Floor of L0-direct-wind weight when geostrophic is weak |
| `TERRAIN_FORM_DRAG_SCALE` | 0.65 | Uphill drag intensity / 迎风坡阻力 |
| `TERRAIN_FLOW_DEFLECTION_SCALE` | 0.45 | Contour-following steering / 等高线偏转强度 |
| `MAX_DYNAMIC_WIND_MPS` | 18 | Hard speed cap / 风速上限 |
| `ALTITUDE_LAPSE_RATE_K_PER_BLOCK` | 0.0065 | Air-temperature lapse with altitude / 高度温度递减率 |

### 3.5 If you need to change how L0 wind is generated / 修改 L0 求解

- **New diagnostic field**: extend `CellState` and `Sample`, populate it inside
  `advanceDynamicField(...)`, expose it via a getter on `Sample`, surface it at the API layer
  (`AeroWindSample`).
- **Different solver philosophy** (e.g., spectral, semi-implicit primitive equations): replace
  `advanceDynamicField(...)` and `computeGeostrophicWind(...)`. Keep `sample(BlockPos)` signature stable
  so L1 and the API stay decoupled.
- **Multi-layer L0**: L0 is currently a single layer. If you add layers, also revisit L1's coupling in
  `MesoscaleGrid.refresh(...)` which assumes a single column from L0.

See also `docs/world-scale-weather-design.md` (intent, partially supersedes this section).

---

## 4. L1 — `MesoscaleGrid` / 中尺度网格

**File**: `fabric-mod/src/main/java/com/aerodynamics4mc/runtime/MesoscaleGrid.java` (~2372 lines).

### 4.1 Geometry / 几何

- Horizontal: `(2 · MESOSCALE_MET_RADIUS_CELLS + 1)² = 33 × 33` cells, each `64 × 64` blocks.
- Vertical: up to `MESOSCALE_MET_MAX_LAYERS = 8` layers of `MESOSCALE_MET_LAYER_HEIGHT_BLOCKS = 40` blocks
  each, total 320 blocks. `computeActiveLayers()` chooses the active layer count from the world height.
- Refresh: every `MESOSCALE_REFRESH_TICKS = 64` server ticks (≈ 3.2 s).
- Anchored to player focus; uses `SeedTerrainProvider` (`HashedSeedTerrainProvider` or
  `WorldgenSeedTerrainProvider`) to fetch terrain heights without forcing chunk loads.

### 4.2 What it does / 做什么

`MesoscaleGrid.refresh(world, focus, tickCounter, dtSeconds, provider, background)` is the entry point.
Per refresh it:

1. **Pulls L0 sample** at each L1 cell-center via `background.sample(blockPos)`.
2. **Builds layered profiles**: applies an ABL-aware vertical wind profile (`ABL_NEUTRAL_HEIGHT_BLOCKS`,
   `ABL_STABLE_HEIGHT_BLOCKS`, `ABL_UNSTABLE_HEIGHT_BLOCKS`, Ekman turn capped at
   `ABL_EKMAN_MAX_TURN_RADIANS`).
3. **Applies terrain bounce-back & form drag** at the surface layer using the seed terrain provider.
4. **Computes diagnostics**: turbulence intensity, gust components, wind shear (∂u/∂x, ∂u/∂z),
   instability and lift proxies.
5. **Builds L2 forcing payload** (25 channels — `NATIVE_FORCING_CHANNELS`) consumed by the native LBM:
   obstacle mask, fan mask/velocity, sponge-layer Dirichlet velocity/temperature, thermal sources, etc.
6. **Optionally pushes nested feedback** from L2 (vorticity, divergence) back into L1 cells via
   `applyPendingNestedFeedback(...)`, every `L2_TO_L1_FEEDBACK_STEPS = 64` ticks.

The resulting `DiagnosticsSummary` (`maxInstabilityProxy`, `maxLiftProxy`, `meanLowLevelShear`,
`meanHumidity`, `maxPositiveMoistureConvergence`) is read by `WorldScaleDriver.advance(...)` on the
**next** tick to modulate storm activity and tornado spawning. This closes the up-scale feedback loop.

### 4.3 Outputs / 输出

Stored in `CellColumnState` per L1 cell:

| Field | Per-layer? | Meaning |
|-------|-----------|---------|
| `windX`, `windY`, `windZ` | ✓ | 3D wind in m/s |
| `ambientAirTemperatureKelvin` | ✓ | Air temperature |
| `surfaceTemperatureKelvin` | base | Boundary surface temperature |
| `humidity` | ✓ | Moisture |
| `turbulenceIntensity`, `gustX/Y/Z` | base + sample-time | Stochastic gust contribution |
| `windShearXPerBlock`, `windShearZPerBlock` | base | Horizontal shear gradient |
| `ablStability`, `ablMixingStrength` | column | ABL diagnostics |

Exposed via `MesoscaleGrid.sample(BlockPos)` and ultimately surfaced through `AeroWindApi`.

### 4.4 Native L2 boundary coupling / 与 native L2 的边界耦合

Done in `runtime.NestedBoundaryCoupler` (92 lines, very readable):
- Sponge thickness ≈ 8 cells inside the 64³ window.
- Blend factor `eta = (distance / sponge_layers)²`: at the wall `eta → 1`, force toward L1 value;
  toward the core `eta → 0`, keep the simulated state. This prevents reflections and lets fine
  vortices live without being clipped by Dirichlet BCs.
- Same coupling applies to temperature; humidity is currently L1-side only.

### 4.5 Tunable knobs / 可调常量

| Name | Value | Effect |
|------|-------|--------|
| `MESOSCALE_MET_LAYER_HEIGHT_BLOCKS` | 40 | Vertical resolution; raising it loses ABL detail |
| `MESOSCALE_MET_MAX_LAYERS` | 8 | Vertical extent (8 × 40 = 320 blocks) |
| `ABL_NEUTRAL_HEIGHT_BLOCKS` | 280 | Default ABL thickness |
| `ABL_STABLE_HEIGHT_BLOCKS` | 120 | ABL thickness in stable conditions (cold air, clear sky) |
| `ABL_UNSTABLE_HEIGHT_BLOCKS` | 460 | ABL thickness during convection |
| `ABL_EKMAN_MAX_TURN_RADIANS` | 0.52 | Cap on Coriolis-driven wind turning |
| `L1_TERRAIN_FORM_DRAG` | 0.45 | Terrain drag passed into L2 forcing |
| `L1_THERMAL_SLOPE_WIND_MPS` | 1.10 | Slope-thermal wind contribution (anabatic/katabatic) |

Search for these names in `MesoscaleGrid.java` and `AeroServerRuntime.java` to locate the exact lines.

### 4.6 If you need to change L1 / 修改 L1

- **Wind shear / weather profile**: see `docs/wind-shear-weather-roadmap.md`.
- **Adding a new forcing channel** for native L2: bump `NativeSimulationBridge.NATIVE_FORCING_CHANNELS`,
  populate the channel inside `MesoscaleGrid.buildForcing(...)`, then read it in `aero_lbm_jni.cpp` and
  the OpenCL kernel. Keep channel order documented in `docs/native-jni-interface-reference.md`.
- **Multi-column terrain**: `WorldMirror.java` is the chunk/block introspection cache; extend it before
  asking the grid to read terrain at finer resolution.

See also: `docs/world-scale-weather-design.md`, `docs/wind-shear-weather-roadmap.md`,
`docs/player-facing-wind-design.md`.

---

## 5. L2 — Native LBM / 原生格子玻尔兹曼

**Files**:
- Java bridge: `fabric-mod/src/main/java/com/aerodynamics4mc/runtime/NativeSimulationBridge.java`
  (~1522 lines), `MesoscaleNativeBridge.java`, `NativeLibraryLoader.java`.
- Native source: `fabric-mod/native/src/aero_lbm_jni.cpp`, `kernels.cl`, `lbm_solver.cpp` etc.
- Client mirror: `fabric-mod/src/client/java/com/aerodynamics4mc/client/ClientL2Solver.java`
  (`BRICK_SIZE = 32`).

### 5.1 Numerical method / 数值方法

- **Lattice**: D3Q27.
- **Collision**: cumulant relaxation with subgrid-scale (SGS) closure (Smagorinsky-type, `kSgsC ≈ 0.025`).
- **Thermal**: passive scalar transported by the lattice; Boussinesq buoyancy term injected into the
  momentum equation (constants `kThermalDiffusivity`, `kThermalCooling`, `kBoussinesqBeta`,
  `kBoussinesqForceMax` in `aero_lbm_jni.cpp`).
- **Backend**: OpenCL preferred (GPU); CPU fallback. Force CPU-only with `AERO_LBM_CPU_ONLY=1`.
- **Scale**: 1 voxel = 1 block (`DX_METERS = 1.0`); `dt = 0.05 s`; lattice velocity scale `dx/dt = 20 m/s`.
- **Stability**: enforce `MAX_SAFE_LATTICE_SPEED = 0.28` (in lattice units) — clamp before pushing
  forcing.

### 5.2 Forcing payload (Java → native) / Forcing 通道

The Java side hands the native solver a buffer of `NATIVE_FORCING_CHANNELS = 25` channels per voxel,
plus a state buffer of `FLOW_STATE_CHANNELS = 4` (`ρ, u, v, w`). Channels include the obstacle mask, fan
mask + prescribed velocity, sponge-layer Dirichlet wind/temperature, thermal sources (lava/fire/torches
with rated power in watts), and a few diagnostic slots. Detailed layout: see
`docs/native-jni-interface-reference.md`.

### 5.3 Server vs. client L2 / 服务端 vs 客户端 L2

| Aspect | Server-side L2 (`SERVER_AUTHORITATIVE_L2_ENABLED = true`) | Client-side L2 (default) |
|--------|----------------------------------------------------------|--------------------------|
| Domain | 64³ window (= `GRID_SIZE`) tracked around focus | 32³ bricks (`BRICK_SIZE`) around player; multiple bricks cached LRU |
| Authority | Server-trusted; `AeroWindSample.authority() == SERVER_AUTHORITATIVE` | Client-local; **must not** drive server-side gameplay |
| Forcing | From `MesoscaleGrid.buildForcing(...)` | Seeded from `AeroCoarseWindPacket` (32-block-cell L1 broadcast) |
| Compute | Native LBM via JNI | Native LBM via JNI on the client |
| Visibility | Atlas streaming (currently disabled) | Local visualizer, particles, Iris bridge |

The current release ships **client-local L2 only**. The server-authoritative path exists in code but is
gated off; flipping it on requires re-validating multiplayer feedback aggregation (see
`docs/native-authoritative-l2-runtime-design.md`, **partially stale** vs current code — see §7).

### 5.4 Coarse-wind sync / 粗风同步包

`AeroCoarseWindPacket` (in `net/AeroCoarseWindPacket.java`) is broadcast on L1 changes. It samples the
L1 wind on a `COARSE_WIND_SYNC_CELL_SIZE_BLOCKS = 32`-block grid around the player. The client uses it
for two purposes:

1. Answering `AeroClientWindApi.sample(...)` queries with `SamplePolicy.SERVER_COARSE_ONLY` /
   `SERVER_AGGREGATED_PREFERRED`.
2. Reseeding `ClientL2Solver` sponge boundaries when the player moves between bricks
   (`ClientL2Solver.onCoarseWindField(payload)` → `markBoundaryRefreshPending()`).

### 5.5 If you need to change L2 / 修改 L2

- **New thermal source kind**: add a power constant in `aero_lbm_jni.cpp` (e.g.,
  `kThermalEmitterPower<NewBlock>W`), wire it into `MesoscaleGrid.buildForcing(...)` block-entity scan,
  and add to `ClientL2Solver`'s mirror so the visualization matches.
- **Different LBM model** (e.g., MRT-D3Q19): replace the kernel and the channel layout in
  `NativeSimulationBridge`; bump `NATIVE_FORCING_CHANNELS` and document in
  `docs/native-jni-interface-reference.md`.
- **Server-authoritative L2**: flip `SERVER_AUTHORITATIVE_L2_ENABLED`, then audit every site that
  currently sets `AeroWindSample.authority() = CLIENT_LOCAL` and redo the network atlas streaming path.

See: `fabric-mod/native/README.md`, `docs/native-jni-interface-reference.md`,
`docs/native-physics-engine-todo.md`, `docs/native-authoritative-l2-runtime-design.md` (partially stale).

---

## 6. Public Wind API / 风采样公共 API

Other mods and the visualization layer **must** consume wind through the public API
(`com.aerodynamics4mc.api`), not by reading internal grid classes:

| Type | Where | Purpose |
|------|-------|---------|
| `AeroWindApi` | server | `sample(world, pos)`, `sample(player, pos, policy)` |
| `AeroClientWindApi` | client | `sample(world, pos, policy)` |
| `AeroWindSample` | shared | Velocity, pressure, T, humidity, gust, shear, ABL diagnostics, level (`L0/L1/L2`), authority, confidence, freshness |
| `SamplePolicy` | shared | `SERVER_COARSE_ONLY`, `GAMEPLAY_SERVER_ONLY`, `SERVER_AGGREGATED_PREFERRED`, `CLIENT_LOCAL_PREFERRED`, `VISUAL_LOCAL_FIRST`, `DIAGNOSTIC_ALL_SOURCES` |

Trust rule: `if (!sample.isTrustedForGameplay()) return;` Do not let client-local L2 leak into
server-authoritative gameplay.

Full contract: `docs/wind-sampling-api.md`.

---

## 7. Stale / partially-stale design docs / 旧设计文档与当前代码的偏差

These design docs were written ahead of the current code. They are still useful as **intent**, but check
the code for the source of truth. Update them when you change the relevant subsystem.

| Doc | What's stale | What's still useful |
|-----|--------------|---------------------|
| `docs/native-authoritative-l2-runtime-design.md` | Describes server-authoritative L2 as the active path. Today `SERVER_AUTHORITATIVE_L2_ENABLED = false` and L2 runs client-local. | Architecture and feedback-loop sketch; required when re-enabling server L2. |
| `docs/on-demand-l2-prefetch-design.md` | Prefetch/streaming is largely **not** implemented. Client L2 seeds from coarse-wind payload, not on-demand prefetch. | Long-term plan for predictive brick activation. |
| `docs/local-air-patch-design.md` | "Inspect patch" / "capture L2" commands are deprecated diagnostics, not the release path. | Background on the legacy patch concept. |
| `docs/phase2-completion-log.md`, `docs/phase3-completion-log.md`, `docs/phase3-implementation-plan.md` | Historical milestones, not current architecture. | Useful context for "why is X structured this way?". |
| `docs/world-scale-weather-design.md` | Naming uses "L0" for the world-scale driver and "L1" for the background grid; current code calls the driver `WorldScaleDriver`, the background grid L0, and the mesoscale grid L1. | Phenomenology (cyclones, fronts, monsoons) and physical motivation. |
| `docs/wind-shear-weather-roadmap.md` | Some items are landed, others aspirational. | Forward plan for ABL/shear richness. |
| `docs/shaderpack-wind-compat-design.md` | Iris/BSL bridge is partially landed under `shaderpack-compat/`. | Required reading before editing the Iris bridge. |
| `docs/player-facing-wind-design.md` | Mostly aligned with code intent. | Product philosophy: "make air readable". |
| `docs/wind-sampling-api.md` | Up-to-date as of 2026-05-03. | The API contract — keep this one current. |

> When you change wind generation, **update this overview first**, then the affected sub-doc, then the
> code. `CLAUDE.md` lives at repo root and should stay aligned with this overview's constants table.

---

## 8. Maintainer cheat-sheet / 维护者速查

### 8.1 "I want to change …" / 「我想改…」

| Goal | Edit here first | Also touch |
|------|----------------|-----------|
| How storms spawn / how often tornadoes appear | `WorldScaleDriver.java` (constants §2.3, spawning logic in `advance(...)`) | Possibly `MesoscaleGrid.diagnosticsSummary(...)` if you want different feedback signals |
| Make wind respond faster/slower to weather | `BASE_FLOW_RELAX_PER_SECOND`, `FLOW_RELAXATION_PER_SECOND`, `MESOSCALE_REFRESH_TICKS` | `BACKGROUND_MET_REFRESH_TICKS` (it derives from the L1 cadence) |
| Add a new weather phenomenon (front, sea breeze, monsoon) | New class alongside `CycloneCell` in `WorldScaleDriver.java`; extend `Sample` record | L0 to consume the new field; L1 forcing if L2 should see it |
| Different geostrophic / pressure→wind law | `BackgroundMetGrid.computeGeostrophicWind(...)` (~lines 814–841) | `GEOSTROPHIC_WIND_SCALE_M2_PER_PA_S` |
| Different terrain interaction | `BackgroundMetGrid.applyTerrainFormDrag(...)` (~lines 743–797), `MesoscaleGrid` terrain bounce-back | `SeedTerrainProvider` implementations |
| Add a thermal/mechanical source visible to L2 | `MesoscaleGrid.buildForcing(...)`; mirror in `ClientL2Solver` | `aero_lbm_jni.cpp` if a new channel is needed |
| Re-enable server-authoritative L2 | `SERVER_AUTHORITATIVE_L2_ENABLED` in `AeroServerRuntime.java:123` | Network atlas streaming, validation; see `docs/native-authoritative-l2-runtime-design.md` |
| Change vertical resolution of L1 | `MESOSCALE_MET_LAYER_HEIGHT_BLOCKS`, `MESOSCALE_MET_MAX_LAYERS` (`AeroServerRuntime.java:212–213`) | Anything in `MesoscaleGrid` that loops over layers |
| Change client L2 brick size | `ClientL2Solver.BRICK_SIZE` | Cache size, sponge thickness, atlas packing |

### 8.2 The 10 load-bearing constants / 十大「先动这里」常量

If you're tuning wind generation, change these first and observe — most behavior is downstream of them.

1. `WorldScaleDriver.BASE_FLOW_RELAX_PER_SECOND` — synoptic inertia (1/900 s⁻¹).
2. `WorldScaleDriver.STORM_RELAX_PER_SECOND` — storm-activity smoothing (1/600 s⁻¹).
3. `WorldScaleDriver.CYCLONE_CELL_MAX_PRESSURE_ANOMALY_PA` — cyclone strength (1350 Pa).
4. `WorldScaleDriver.CYCLONE_CELL_MAX_SWIRL_MPS` — peak cyclone tangential wind (10 m/s).
5. `WorldScaleDriver.CONVECTIVE_CLUSTER_MAX_CONVERGENCE_MPS` — gust-front strength (4 m/s).
6. `WorldScaleDriver.TORNADO_MIN_SUPPORT` — tornado spawn threshold (0.75; lower → more).
7. `BackgroundMetGrid.GEOSTROPHIC_WIND_SCALE_M2_PER_PA_S` — pressure → wind gain (12.0).
8. `BackgroundMetGrid.FLOW_RELAXATION_PER_SECOND` — L0 wind responsiveness (1/90 s⁻¹).
9. `AeroServerRuntime.MESOSCALE_REFRESH_TICKS` — L1 cadence (64 ticks; everything downstream rescales).
10. Native `kTauShear` (in `aero_lbm_jni.cpp`) — LBM relaxation; effective viscosity.

### 8.3 Verifying changes / 验证

1. Rebuild native if touched: `cd fabric-mod/native && cmake --build build -j`.
2. `cd fabric-mod && ./gradlew runClient`.
3. In-game: `/aero status` and `/aero dumpdata` inspect the automatically running L0/L1 runtime.
4. Visualize: `python eval_background_snapshot.py …`, `python eval_mesoscale_snapshot.py …`,
   `python eval_l2_capture.py …`. See repo-root `CLAUDE.md` for full command examples.
5. For native solver in isolation: `python eval_native_solver_experiments.py …`.

---

## 9. Document map / 文档地图

Wind-system reading order (start at the top, drill down as needed):

1. **This file** — `docs/wind-system-overview.md` — entry point.
2. `docs/vnext-cinematic-weather-update.md` — current mainline vNext: weather and wind as visual spectacle.
3. `docs/vnext-sailing-prototype.md` — current gameplay prototype: wind-powered sailing.
4. `docs/wind-sampling-api.md` — public API contract (current).
5. `docs/world-scale-weather-design.md` — driver phenomenology (intent; some naming is older).
6. `docs/wind-shear-weather-roadmap.md` — ABL/shear roadmap.
7. `docs/player-facing-wind-design.md` — product philosophy.
8. `docs/native-jni-interface-reference.md` — native channel layout, JNI ABI.
9. `docs/native-physics-engine-todo.md` — open work in the native solver.
10. `docs/native-authoritative-l2-runtime-design.md` — *partially stale*; re-read when re-enabling server L2.
11. `docs/on-demand-l2-prefetch-design.md` — *aspirational*; brick prefetch design.
12. `docs/local-air-patch-design.md` — *legacy*; deprecated patch path.
13. `docs/shaderpack-wind-compat-design.md` — Iris/BSL integration design.
14. `docs/phase2-completion-log.md`, `docs/phase3-completion-log.md`, `docs/phase3-implementation-plan.md` — historical milestones.
15. `fabric-mod/native/README.md` — native build instructions.
16. `fabric-mod/native/docs/wind_tunnel_solver_api.md` — standalone C ABI.
17. `fabric-mod/native/docs/jni_dll_usage_zh.md` — JNI/JNA usage notes (中文).

Repo-root `CLAUDE.md` summarizes the whole project for tooling; keep its constants table aligned with §1.2.
