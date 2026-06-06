# Wind-Tunnel Solver C API

This is the minimal C ABI intended for external validation tools that want to call the native LBM solver without depending on Minecraft, Fabric, JNI, or Create: Aeronautics internals.

Header:

```c
#include "aero_solver_capi.h"
```

The functions are exported from the same native library as the existing solver:

- Windows: `aero_lbm.dll`
- Linux: `libaero_lbm.so`
- macOS: `libaero_lbm.dylib`

## Scope

This API is a low-Mach static wind-tunnel interface.

It currently supports:

- Rectangular solver regions.
- Static voxel solid masks.
- Wind-tunnel boundary conditions.
- Optional previous macro flow field for restart/initialization.
- Output macro velocity and pressure-proxy fields.
- Distribution-level momentum-exchange force/moment readback for static voxel solids.

It does not yet support:

- Dynamic/moving boundaries.
- Near-sonic or compressible CFD.
- Propeller rotation.
- Curved-wall/SDF geometry correction.
- GPU-side force/moment reduction for the compact and fp16 realtime paths.
- Multiple independent grid shapes in the same process.

For force/moment readback, the high-level wind-tunnel wrapper currently pins the runtime solver mode to classic D3Q27. This keeps the D3Q27 distributions available for wall-link momentum exchange. The compact and D3Q27 fp16 inplace realtime paths remain useful for flow-field throughput, but they cannot yet produce this force/moment readback without an additional GPU reduction or host-side fp16 distribution decoder.

## Coordinate Convention

The solver region is indexed as:

```text
x: primary wind-tunnel direction
y: up
z: right
```

For a typical wind-tunnel run, put the aircraft/geometry in the middle of the region and use positive `inlet_vx`.

## Data Layout

All 3D fields are flattened in x-major order:

```c
cell = (x * ny + y) * nz + z;
```

The flow layout is 4 floats per cell:

```text
flow[cell * 4 + 0] = vx, m/s
flow[cell * 4 + 1] = vy, m/s
flow[cell * 4 + 2] = vz, m/s
flow[cell * 4 + 3] = pressure proxy, dimensionless rho_delta
```

`solid_mask[cell]` is:

```text
0 = fluid
non-zero = solid obstacle
```

## Minimal Usage

```c
#include "aero_solver_capi.h"

int run_case(const uint8_t* solid, float* out_flow) {
    AeroGridDesc grid;
    aero_solver_default_grid(&grid);
    grid.nx = 64;
    grid.ny = 64;
    grid.nz = 64;
    grid.dx = 1.0f;
    grid.dt = 0.05f;

    long long solver = 0;
    if (!aero_solver_create_with_grid(&grid, &solver)) {
        return 0;
    }

    const int cells = grid.nx * grid.ny * grid.nz;
    if (!aero_solver_set_solid_mask(solver, solid, cells)) {
        aero_solver_destroy(solver);
        return 0;
    }

    AeroBoundaryDesc boundary;
    aero_solver_default_boundary(&boundary);
    boundary.mode = AERO_SOLVER_BOUNDARY_WIND_TUNNEL;
    boundary.inlet_vx = 5.0f;        // m/s
    boundary.inlet_vy = 0.0f;
    boundary.inlet_vz = 0.0f;
    boundary.outlet_pressure = 0.0f; // dimensionless rho_delta
    boundary.density = 1.225f;       // kg/m^3, force/moment scale
    boundary.viscosity = 1.5e-5f;    // m^2/s

    const int steps = 256;
    const int ok = aero_solver_step_wind_tunnel(
        solver,
        &boundary,
        steps,
        out_flow,
        cells * AERO_SOLVER_FLOW_CHANNELS
    );

    aero_solver_destroy(solver);
    return ok;
}
```

## Restart From Previous Flow

Use `aero_solver_set_flow_state` to initialize/restart a solver from a previous macro field:

```c
aero_solver_set_flow_state(solver, prev_flow, cells * 4);
```

Important: this releases the internal LBM context and uses `prev_flow` as the next initialization state. Do not call it before every small step if you want continuous LBM time integration. For continuous integration, create once, set the solid mask once, then repeatedly call `aero_solver_step_wind_tunnel`.

## Advance Without Readback

Use this when benchmarking pure solver throughput or when you only need to read the flow field every N steps:

```c
int ok = aero_solver_advance_wind_tunnel(solver, &boundary, steps);
```

This advances the same persistent LBM context but does not run the output/readback path. After one normal step uploads the static packet, unchanged wind-tunnel calls can reuse the GPU-resident payload through the cached native step path.

## Force / Moment Readback

Use `aero_solver_compute_force_moment` after one or more wind-tunnel steps:

```c
float reference_point[3] = {32.0f, 32.0f, 32.0f};
AeroForceMoment fm = {0};
int ok = aero_solver_compute_force_moment(solver, reference_point, &fm);
```

The output is in the solver grid coordinate system:

```text
force[]              momentum-exchange force on the solid body, N
moment[]             momentum-exchange moment about reference_point, N*m
center_of_pressure[] force-magnitude weighted D3Q27 wall-link center, m
reference_point[]    copied reference point, m
surface_link_count   number of solid-fluid D3Q27 links integrated
```

The native implementation integrates the distributions used by the most recent bounce-back step:

```text
dF_lattice = 2 * f_i * c_i
dF_N       = dF_lattice * density * dx^4 / dt^2
dM_Nm      = (link_midpoint - reference_point) cross dF_N
```

This is a wall-link momentum-exchange method, not a pressure-proxy macro-field estimate. It includes all D3Q27 links, including diagonal links. Call it after at least one `aero_solver_step_wind_tunnel` or `aero_solver_advance_wind_tunnel` call.

## Convenience One-Shot API

For simple validation tools, use:

```c
AeroStepInput input = {0};
AeroStepOutput output = {0};

input.solid_mask = solid;
input.prev_flow = NULL;
input.grid = grid;
input.boundary = boundary;
input.steps = 256;

output.flow_out = out_flow;

int ok = aero_solver_run_wind_tunnel(&input, &output);
```

This creates a temporary solver, runs it, writes `output.flow_out`, sets `output.max_velocity`, and destroys the solver.

## Boundary Modes

```c
AERO_SOLVER_BOUNDARY_WIND_TUNNEL
```

Uses:

- `x-`: velocity inlet
- `x+`: convective outflow
- `y-/y+/z-/z+`: symmetry/slip-like side boundaries

```c
AERO_SOLVER_BOUNDARY_WIND_TUNNEL_PRESSURE_OUTLET
```

Uses:

- `x-`: velocity inlet
- `x+`: pressure outlet using `outlet_pressure`
- `y-/y+/z-/z+`: symmetry/slip-like side boundaries

```c
AERO_SOLVER_BOUNDARY_CLOSED
```

All faces are bounce-back walls.

```c
AERO_SOLVER_BOUNDARY_PERIODIC
```

All faces are periodic.

## Units

Public API velocities are meters per second.

Internally the solver uses lattice velocity:

```text
u_lattice = u_mps * dt / dx
u_mps = u_lattice * dx / dt
```

The pressure channel is currently a dimensionless pressure proxy:

```text
pressure_proxy = rho - 1
```

Do not interpret it as Pascals in this MVP API.

Force/moment readback is converted from lattice force using:

```text
force_scale = density * dx^4 / dt^2
```

`density`, `dx`, and `dt` therefore affect force and moment magnitudes directly.

## Current Limitations

- The underlying native solver has global grid/runtime configuration. Treat this API as single active grid shape per process for now.
- The API is serialized internally with a mutex. It is safe from concurrent calls, but not designed for parallel throughput yet.
- Boundary conditions are configured through the existing benchmark-mode path. This is appropriate for wind-tunnel validation, but not a final aerodynamic analysis ABI.
- Geometry is voxel stair-stepped. The link midpoint assumes halfway bounce-back; there is no curved wall, SDF intersection, or immersed-boundary correction yet.
- Force/moment currently assumes static no-slip solids. Moving and rotating body wall velocity is not included in the momentum-exchange formula yet.
- The current force readback supports CPU and classic D3Q27 OpenCL states. Compact macro and D3Q27 fp16 inplace paths need a dedicated GPU reduction or host parity decoder before they can report force/moment.
- Classic OpenCL force readback reads a full D3Q27 distribution buffer to the host. This is acceptable for 32^3 validation, but too expensive to treat as final for 128^3 realtime feedback.
- Coefficients still need validation against canonical cases such as sphere/cylinder drag, grid convergence, and reference-domain-size sweeps before they should be treated as engineering numbers.
- The solver remains low-Mach and weakly compressible. Pressure output remains a solver pressure proxy rather than measured Pascals.
- Side boundaries and finite tunnel length can contaminate force/moment unless the domain and boundary modes are validated for the case.
- `aero_solver_step_wind_tunnel` includes full-field output readback and unit conversion. Use `aero_solver_advance_wind_tunnel` for MLUPS-style solver-only benchmarking.

## Debugging

Use:

```c
const char* err = aero_solver_last_error();
const char* runtime = aero_solver_runtime_info();
```

`runtime` should report either an OpenCL backend or CPU fallback.
