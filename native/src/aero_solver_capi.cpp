#include "aero_solver_capi.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr int kInputChannels = 11;
constexpr int kOutputChannels = AERO_SOLVER_FLOW_CHANNELS;
constexpr int kChannelObstacle = 0;
constexpr int kChannelFanMask = 1;
constexpr int kChannelFanVx = 2;
constexpr int kChannelFanVy = 3;
constexpr int kChannelFanVz = 4;
constexpr int kChannelStateVx = 5;
constexpr int kChannelStateVy = 6;
constexpr int kChannelStateVz = 7;
constexpr int kChannelStateP = 8;
constexpr int kChannelThermalSource = 9;
constexpr int kChannelStateTemp = 10;
constexpr float kDefaultDxMeters = 1.0f;
constexpr float kDefaultDtSeconds = 0.05f;
constexpr float kDefaultDensityKgM3 = 1.225f;
constexpr float kDefaultViscosityM2S = 1.5e-5f;
constexpr float kMinLatticeNu = 1.0e-7f;
constexpr float kMaxLatticeNu = 0.12f;

struct SolverContext {
    long long handle = 0;
    long long context_key = 0;
    AeroGridDesc grid{};
    int cells = 0;
    bool owns_runtime_lock = false;
    bool custom_flow_state = false;
    bool packet_dirty = true;
    bool native_payload_uploaded = false;
    AeroBoundaryDesc packet_boundary{};
    AeroBoundaryDesc force_boundary{};
    std::vector<uint8_t> solid_mask;
    std::vector<float> packet;
};

struct SolverSpinMutex {
    std::atomic_flag flag = ATOMIC_FLAG_INIT;

    void lock() noexcept {
        while (flag.test_and_set(std::memory_order_acquire)) {
        }
    }

    void unlock() noexcept {
        flag.clear(std::memory_order_release);
    }
};

struct SolverGlobals {
    SolverSpinMutex mutex;
    std::unordered_map<long long, std::unique_ptr<SolverContext>> contexts;
    long long next_handle = 1;
    std::string last_error;
};

SolverGlobals& solver_globals() {
    static SolverGlobals* globals = new SolverGlobals();
    return *globals;
}

void set_error(const char* message) {
    solver_globals().last_error = message ? message : "unknown solver error";
}

void set_error(const std::string& message) {
    solver_globals().last_error = message;
}

void clear_error() {
    solver_globals().last_error.clear();
}

void force_distribution_readback_solver_mode() {
    aero_lbm_set_realtime_solver_mode(AERO_LBM_REALTIME_SOLVER_CLASSIC_D3Q27);
}

struct ScopedRuntimeLock {
    bool locked = false;

    ScopedRuntimeLock() : locked(true) {
        aero_lbm_runtime_lock();
    }

    ~ScopedRuntimeLock() {
        if (locked) {
            aero_lbm_runtime_unlock();
        }
    }

    void transfer() {
        locked = false;
    }
};

void release_context_locked(SolverContext& ctx) {
    aero_lbm_release_context(ctx.context_key);
    if (ctx.owns_runtime_lock) {
        ctx.owns_runtime_lock = false;
        aero_lbm_runtime_unlock();
    }
}

void clear_contexts_locked(SolverGlobals& globals) {
    for (auto& entry : globals.contexts) {
        if (entry.second) {
            release_context_locked(*entry.second);
        }
    }
    globals.contexts.clear();
}

bool valid_grid(const AeroGridDesc& grid, int* out_cells) {
    if (grid.nx <= 0 || grid.ny <= 0 || grid.nz <= 0) {
        set_error("invalid grid dimensions");
        return false;
    }
    if (!std::isfinite(grid.dx) || grid.dx <= 0.0f || !std::isfinite(grid.dt) || grid.dt <= 0.0f) {
        set_error("invalid grid dx/dt");
        return false;
    }
    const int64_t cells = static_cast<int64_t>(grid.nx) * grid.ny * grid.nz;
    if (cells <= 0 || cells > static_cast<int64_t>(std::numeric_limits<int>::max())) {
        set_error("grid cell count overflows int");
        return false;
    }
    if (out_cells) {
        *out_cells = static_cast<int>(cells);
    }
    return true;
}

SolverContext* lookup_context(long long handle) {
    auto& contexts = solver_globals().contexts;
    auto it = contexts.find(handle);
    if (it == contexts.end() || !it->second) {
        set_error("invalid solver handle");
        return nullptr;
    }
    return it->second.get();
}

float finite_or(float value, float fallback) {
    return std::isfinite(value) ? value : fallback;
}

float lattice_velocity_scale(const AeroGridDesc& grid) {
    return grid.dx / grid.dt;
}

float velocity_mps_to_lattice(const AeroGridDesc& grid, float velocity_mps) {
    return finite_or(velocity_mps, 0.0f) / lattice_velocity_scale(grid);
}

float pressure_proxy(float pressure) {
    return finite_or(pressure, 0.0f);
}

bool same_boundary_for_packet(const AeroBoundaryDesc& a, const AeroBoundaryDesc& b) {
    return a.mode == b.mode
        && a.inlet_vx == b.inlet_vx
        && a.inlet_vy == b.inlet_vy
        && a.inlet_vz == b.inlet_vz
        && a.outlet_pressure == b.outlet_pressure;
}

std::size_t packet_base(std::size_t cell) {
    return cell * static_cast<std::size_t>(kInputChannels);
}

void write_packet_flow_cell(
    SolverContext& ctx,
    std::size_t cell,
    float vx_mps,
    float vy_mps,
    float vz_mps,
    float pressure
) {
    const std::size_t base = packet_base(cell);
    ctx.packet[base + kChannelStateVx] = velocity_mps_to_lattice(ctx.grid, vx_mps);
    ctx.packet[base + kChannelStateVy] = velocity_mps_to_lattice(ctx.grid, vy_mps);
    ctx.packet[base + kChannelStateVz] = velocity_mps_to_lattice(ctx.grid, vz_mps);
    ctx.packet[base + kChannelStateP] = pressure_proxy(pressure);
}

void initialize_packet(SolverContext& ctx, const AeroBoundaryDesc& boundary, bool overwrite_flow_state) {
    const float vx = finite_or(boundary.inlet_vx, 0.0f);
    const float vy = finite_or(boundary.inlet_vy, 0.0f);
    const float vz = finite_or(boundary.inlet_vz, 0.0f);
    const float pressure = pressure_proxy(boundary.outlet_pressure);
    for (int cell = 0; cell < ctx.cells; ++cell) {
        const std::size_t index = static_cast<std::size_t>(cell);
        const std::size_t base = packet_base(index);
        ctx.packet[base + kChannelObstacle] = ctx.solid_mask[index] != 0 ? 1.0f : 0.0f;
        ctx.packet[base + kChannelFanMask] = 0.0f;
        ctx.packet[base + kChannelFanVx] = 0.0f;
        ctx.packet[base + kChannelFanVy] = 0.0f;
        ctx.packet[base + kChannelFanVz] = 0.0f;
        ctx.packet[base + kChannelThermalSource] = 0.0f;
        ctx.packet[base + kChannelStateTemp] = 0.0f;
        if (ctx.solid_mask[index] != 0) {
            write_packet_flow_cell(ctx, index, 0.0f, 0.0f, 0.0f, 0.0f);
        } else if (overwrite_flow_state) {
            write_packet_flow_cell(ctx, index, vx, vy, vz, pressure);
        }
    }
}

float max_velocity_from_output(int cells, const float* flow, int value_count) {
    if (!flow || value_count < cells * kOutputChannels) {
        return 0.0f;
    }
    float max_velocity = 0.0f;
    for (int cell = 0; cell < cells; ++cell) {
        const std::size_t base = static_cast<std::size_t>(cell) * kOutputChannels;
        const float vx = flow[base + 0];
        const float vy = flow[base + 1];
        const float vz = flow[base + 2];
        const float speed = std::sqrt(vx * vx + vy * vy + vz * vz);
        if (std::isfinite(speed)) {
            max_velocity = std::max(max_velocity, speed);
        }
    }
    return max_velocity;
}

void clear_force_moment(AeroForceMoment& out, const float* reference_point) {
    for (int axis = 0; axis < 3; ++axis) {
        out.force[axis] = 0.0f;
        out.moment[axis] = 0.0f;
        out.center_of_pressure[axis] = reference_point ? finite_or(reference_point[axis], 0.0f) : 0.0f;
        out.reference_point[axis] = reference_point ? finite_or(reference_point[axis], 0.0f) : 0.0f;
    }
    out.surface_link_count = 0;
    out.status = AERO_SOLVER_STATUS_ERROR;
}

AeroLbmBoundaryFaceConfig make_face(int hydro_kind, int thermal_kind) {
    AeroLbmBoundaryFaceConfig face{};
    face.hydrodynamic_kind = hydro_kind;
    face.thermal_kind = thermal_kind;
    return face;
}

void set_all_faces(AeroLbmBenchmarkConfig& cfg, int hydro_kind, int thermal_kind) {
    cfg.x_min = make_face(hydro_kind, thermal_kind);
    cfg.x_max = make_face(hydro_kind, thermal_kind);
    cfg.y_min = make_face(hydro_kind, thermal_kind);
    cfg.y_max = make_face(hydro_kind, thermal_kind);
    cfg.z_min = make_face(hydro_kind, thermal_kind);
    cfg.z_max = make_face(hydro_kind, thermal_kind);
}

float boundary_speed_lattice(const SolverContext& ctx, const AeroBoundaryDesc& boundary) {
    const float vx = velocity_mps_to_lattice(ctx.grid, boundary.inlet_vx);
    const float vy = velocity_mps_to_lattice(ctx.grid, boundary.inlet_vy);
    const float vz = velocity_mps_to_lattice(ctx.grid, boundary.inlet_vz);
    return std::sqrt(vx * vx + vy * vy + vz * vz);
}

float lattice_viscosity(const SolverContext& ctx, const AeroBoundaryDesc& boundary) {
    const float viscosity = finite_or(boundary.viscosity, kDefaultViscosityM2S);
    const float nu = viscosity > 0.0f ? viscosity * ctx.grid.dt / (ctx.grid.dx * ctx.grid.dx) : kDefaultViscosityM2S;
    return std::clamp(nu, kMinLatticeNu, kMaxLatticeNu);
}

bool configure_benchmark_boundary(const SolverContext& ctx, const AeroBoundaryDesc& boundary) {
    AeroLbmBenchmarkConfig cfg{};
    aero_lbm_benchmark_default_config(&cfg);
    cfg.enabled = 1;
    cfg.preset = AERO_LBM_BENCHMARK_PRESET_NONE;
    cfg.flags |=
        AERO_LBM_BENCHMARK_FLAG_DISABLE_FAN_FORCING
        | AERO_LBM_BENCHMARK_FLAG_DISABLE_FAN_NOISE
        | AERO_LBM_BENCHMARK_FLAG_DISABLE_SPONGE
        | AERO_LBM_BENCHMARK_FLAG_DISABLE_OBSTACLE_BOUNCE_BLEND
        | AERO_LBM_BENCHMARK_FLAG_DISABLE_SGS
        | AERO_LBM_BENCHMARK_FLAG_DISABLE_INTERNAL_THERMAL_SOURCE
        | AERO_LBM_BENCHMARK_FLAG_DISABLE_BUOYANCY;
    cfg.flags &= ~static_cast<uint32_t>(AERO_LBM_BENCHMARK_FLAG_DISABLE_CONVECTIVE_OUTFLOW);
    cfg.reference_density = std::max(1.0e-6f, finite_or(boundary.density, kDefaultDensityKgM3));
    cfg.reference_temperature = 0.0f;
    cfg.reference_length = 1.0f;
    cfg.initial_velocity[0] = velocity_mps_to_lattice(ctx.grid, boundary.inlet_vx);
    cfg.initial_velocity[1] = velocity_mps_to_lattice(ctx.grid, boundary.inlet_vy);
    cfg.initial_velocity[2] = velocity_mps_to_lattice(ctx.grid, boundary.inlet_vz);
    cfg.initial_pressure = pressure_proxy(boundary.outlet_pressure);
    const float speed = std::max(boundary_speed_lattice(ctx, boundary), 1.0e-6f);
    cfg.reynolds_number = std::max(1.0e-6f, speed / lattice_viscosity(ctx, boundary));
    cfg.mach_number = std::clamp(speed / std::sqrt(1.0f / 3.0f), 1.0e-4f, 0.30f);

    const int mode = boundary.mode == 0 ? AERO_SOLVER_BOUNDARY_WIND_TUNNEL : boundary.mode;
    switch (mode) {
        case AERO_SOLVER_BOUNDARY_CLOSED:
            set_all_faces(cfg, AERO_LBM_HYDRO_BOUNDARY_BOUNCE_BACK, AERO_LBM_THERMAL_BOUNDARY_DISABLED);
            break;
        case AERO_SOLVER_BOUNDARY_PERIODIC:
            set_all_faces(cfg, AERO_LBM_HYDRO_BOUNDARY_PERIODIC, AERO_LBM_THERMAL_BOUNDARY_DISABLED);
            break;
        case AERO_SOLVER_BOUNDARY_WIND_TUNNEL_PRESSURE_OUTLET:
            set_all_faces(cfg, AERO_LBM_HYDRO_BOUNDARY_SYMMETRY, AERO_LBM_THERMAL_BOUNDARY_DISABLED);
            cfg.x_min = make_face(AERO_LBM_HYDRO_BOUNDARY_VELOCITY_DIRICHLET, AERO_LBM_THERMAL_BOUNDARY_DISABLED);
            cfg.x_max = make_face(AERO_LBM_HYDRO_BOUNDARY_PRESSURE_DIRICHLET, AERO_LBM_THERMAL_BOUNDARY_DISABLED);
            cfg.x_max.pressure = pressure_proxy(boundary.outlet_pressure);
            break;
        case AERO_SOLVER_BOUNDARY_WIND_TUNNEL:
            set_all_faces(cfg, AERO_LBM_HYDRO_BOUNDARY_SYMMETRY, AERO_LBM_THERMAL_BOUNDARY_DISABLED);
            cfg.x_min = make_face(AERO_LBM_HYDRO_BOUNDARY_VELOCITY_DIRICHLET, AERO_LBM_THERMAL_BOUNDARY_DISABLED);
            cfg.x_max = make_face(AERO_LBM_HYDRO_BOUNDARY_CONVECTIVE_OUTFLOW, AERO_LBM_THERMAL_BOUNDARY_DISABLED);
            break;
        default:
            set_error("invalid wind-tunnel boundary mode");
            return false;
    }
    cfg.x_min.velocity[0] = cfg.initial_velocity[0];
    cfg.x_min.velocity[1] = cfg.initial_velocity[1];
    cfg.x_min.velocity[2] = cfg.initial_velocity[2];
    return aero_lbm_benchmark_set_config(&cfg) != 0;
}

bool native_runtime_is_opencl() {
    const char* info = aero_lbm_runtime_info();
    return info && std::strncmp(info, "opencl|", 7) == 0;
}

bool step_solver_locked(
    SolverContext& ctx,
    const AeroBoundaryDesc& boundary,
    int steps,
    float* out_flow,
    int out_value_count
) {
    if (steps <= 0) {
        set_error("steps must be positive");
        return false;
    }
    const bool wants_output = out_flow != nullptr;
    if (wants_output && out_value_count != ctx.cells * kOutputChannels) {
        set_error("invalid output flow buffer");
        return false;
    }
    if (!configure_benchmark_boundary(ctx, boundary)) {
        if (solver_globals().last_error.empty()) {
            set_error(std::string("failed to configure boundary: ") + aero_lbm_last_error());
        }
        return false;
    }
    force_distribution_readback_solver_mode();
    const bool overwrite_flow_state = !ctx.custom_flow_state;
    const bool boundary_changes_packet = overwrite_flow_state && !same_boundary_for_packet(ctx.packet_boundary, boundary);
    if (ctx.packet_dirty || boundary_changes_packet) {
        initialize_packet(ctx, boundary, overwrite_flow_state);
        ctx.packet_boundary = boundary;
        ctx.packet_dirty = false;
        ctx.native_payload_uploaded = false;
    }
    const float output_velocity_scale = lattice_velocity_scale(ctx.grid);
    for (int step = 0; step < steps; ++step) {
        float* step_output = wants_output && step + 1 == steps ? out_flow : nullptr;
        bool step_ok = false;
        if (ctx.native_payload_uploaded && native_runtime_is_opencl()) {
            step_ok = aero_lbm_step_rect_cached_scaled(
                ctx.grid.nx,
                ctx.grid.ny,
                ctx.grid.nz,
                ctx.context_key,
                output_velocity_scale,
                step_output
            ) != 0;
        }
        if (!step_ok) {
            step_ok = aero_lbm_step_rect_scaled(
                ctx.packet.data(),
                ctx.grid.nx,
                ctx.grid.ny,
                ctx.grid.nz,
                ctx.context_key,
                output_velocity_scale,
                step_output
            ) != 0;
            if (!step_ok) {
                set_error(std::string("aero_lbm_step_rect failed: ") + aero_lbm_last_error());
                return false;
            }
            ctx.native_payload_uploaded = native_runtime_is_opencl();
        }
    }
    ctx.force_boundary = boundary;
    return true;
}

}  // namespace

extern "C" {

AERO_LBM_CAPI_EXPORT void aero_solver_default_grid(AeroGridDesc* out_grid) {
    if (!out_grid) return;
    out_grid->nx = 64;
    out_grid->ny = 64;
    out_grid->nz = 64;
    out_grid->dx = kDefaultDxMeters;
    out_grid->dt = kDefaultDtSeconds;
}

AERO_LBM_CAPI_EXPORT void aero_solver_default_boundary(AeroBoundaryDesc* out_boundary) {
    if (!out_boundary) return;
    out_boundary->mode = AERO_SOLVER_BOUNDARY_WIND_TUNNEL;
    out_boundary->inlet_vx = 5.0f;
    out_boundary->inlet_vy = 0.0f;
    out_boundary->inlet_vz = 0.0f;
    out_boundary->outlet_pressure = 0.0f;
    out_boundary->density = kDefaultDensityKgM3;
    out_boundary->viscosity = kDefaultViscosityM2S;
}

AERO_LBM_CAPI_EXPORT int aero_solver_create(
    int nx,
    int ny,
    int nz,
    float dx,
    float dt,
    long long* out_handle
) {
    AeroGridDesc grid{};
    grid.nx = nx;
    grid.ny = ny;
    grid.nz = nz;
    grid.dx = dx;
    grid.dt = dt;
    return aero_solver_create_with_grid(&grid, out_handle);
}

AERO_LBM_CAPI_EXPORT int aero_solver_create_with_grid(
    const AeroGridDesc* grid,
    long long* out_handle
) {
    SolverGlobals& globals = solver_globals();
    std::lock_guard<SolverSpinMutex> lock(globals.mutex);
    clear_error();
    if (!grid || !out_handle) {
        set_error("missing grid or output handle");
        return AERO_SOLVER_STATUS_ERROR;
    }
    int cells = 0;
    if (!valid_grid(*grid, &cells)) {
        return AERO_SOLVER_STATUS_ERROR;
    }
    clear_contexts_locked(globals);
    ScopedRuntimeLock runtime_lock;
    force_distribution_readback_solver_mode();
    if (!aero_lbm_init_rect(grid->nx, grid->ny, grid->nz, kInputChannels, kOutputChannels)) {
        set_error(std::string("aero_lbm_init_rect failed: ") + aero_lbm_last_error());
        return AERO_SOLVER_STATUS_ERROR;
    }

    auto ctx = std::make_unique<SolverContext>();
    ctx->handle = globals.next_handle++;
    if (globals.next_handle <= 0) {
        globals.next_handle = 1;
    }
    ctx->context_key = ctx->handle;
    ctx->grid = *grid;
    ctx->cells = cells;
    ctx->owns_runtime_lock = true;
    runtime_lock.transfer();
    ctx->solid_mask.assign(static_cast<std::size_t>(cells), 0u);
    ctx->packet.assign(static_cast<std::size_t>(cells) * kInputChannels, 0.0f);
    AeroBoundaryDesc boundary{};
    aero_solver_default_boundary(&boundary);
    initialize_packet(*ctx, boundary, true);
    ctx->packet_boundary = boundary;
    ctx->force_boundary = boundary;
    ctx->packet_dirty = false;
    ctx->native_payload_uploaded = false;

    const long long handle = ctx->handle;
    globals.contexts.emplace(handle, std::move(ctx));
    *out_handle = handle;
    return AERO_SOLVER_STATUS_OK;
}

AERO_LBM_CAPI_EXPORT int aero_solver_set_solid_mask(
    long long handle,
    const uint8_t* solid_mask,
    int cell_count
) {
    SolverGlobals& globals = solver_globals();
    std::lock_guard<SolverSpinMutex> lock(globals.mutex);
    clear_error();
    SolverContext* ctx = lookup_context(handle);
    if (!ctx) {
        return AERO_SOLVER_STATUS_ERROR;
    }
    if (!solid_mask || cell_count != ctx->cells) {
        set_error("invalid solid mask buffer");
        return AERO_SOLVER_STATUS_ERROR;
    }
    ctx->solid_mask.assign(solid_mask, solid_mask + cell_count);
    ctx->custom_flow_state = false;
    ctx->packet_dirty = true;
    ctx->native_payload_uploaded = false;
    aero_lbm_release_context(ctx->context_key);
    return AERO_SOLVER_STATUS_OK;
}

AERO_LBM_CAPI_EXPORT int aero_solver_set_flow_state(
    long long handle,
    const float* flow,
    int value_count
) {
    SolverGlobals& globals = solver_globals();
    std::lock_guard<SolverSpinMutex> lock(globals.mutex);
    clear_error();
    SolverContext* ctx = lookup_context(handle);
    if (!ctx) {
        return AERO_SOLVER_STATUS_ERROR;
    }
    if (!flow || value_count != ctx->cells * kOutputChannels) {
        set_error("invalid flow state buffer");
        return AERO_SOLVER_STATUS_ERROR;
    }
    for (int cell = 0; cell < ctx->cells; ++cell) {
        const std::size_t index = static_cast<std::size_t>(cell);
        const std::size_t flow_base = index * kOutputChannels;
        if (ctx->solid_mask[index] != 0) {
            write_packet_flow_cell(*ctx, index, 0.0f, 0.0f, 0.0f, 0.0f);
            continue;
        }
        write_packet_flow_cell(
            *ctx,
            index,
            flow[flow_base + 0],
            flow[flow_base + 1],
            flow[flow_base + 2],
            flow[flow_base + 3]
        );
    }
    ctx->custom_flow_state = true;
    ctx->packet_dirty = false;
    ctx->native_payload_uploaded = false;
    aero_lbm_release_context(ctx->context_key);
    return AERO_SOLVER_STATUS_OK;
}

AERO_LBM_CAPI_EXPORT int aero_solver_step_wind_tunnel(
    long long handle,
    const AeroBoundaryDesc* boundary,
    int steps,
    float* out_flow,
    int out_value_count
) {
    SolverGlobals& globals = solver_globals();
    std::lock_guard<SolverSpinMutex> lock(globals.mutex);
    clear_error();
    SolverContext* ctx = lookup_context(handle);
    if (!ctx) {
        return AERO_SOLVER_STATUS_ERROR;
    }
    AeroBoundaryDesc effective_boundary{};
    aero_solver_default_boundary(&effective_boundary);
    if (boundary) {
        effective_boundary = *boundary;
    }
    return step_solver_locked(*ctx, effective_boundary, steps, out_flow, out_value_count)
        ? AERO_SOLVER_STATUS_OK
        : AERO_SOLVER_STATUS_ERROR;
}

AERO_LBM_CAPI_EXPORT int aero_solver_advance_wind_tunnel(
    long long handle,
    const AeroBoundaryDesc* boundary,
    int steps
) {
    SolverGlobals& globals = solver_globals();
    std::lock_guard<SolverSpinMutex> lock(globals.mutex);
    clear_error();
    SolverContext* ctx = lookup_context(handle);
    if (!ctx) {
        return AERO_SOLVER_STATUS_ERROR;
    }
    AeroBoundaryDesc effective_boundary{};
    aero_solver_default_boundary(&effective_boundary);
    if (boundary) {
        effective_boundary = *boundary;
    }
    return step_solver_locked(*ctx, effective_boundary, steps, nullptr, 0)
        ? AERO_SOLVER_STATUS_OK
        : AERO_SOLVER_STATUS_ERROR;
}

AERO_LBM_CAPI_EXPORT int aero_solver_extract_flow_atlas(
    long long handle,
    int stride,
    float* out_flow_atlas,
    int out_value_count
) {
    SolverGlobals& globals = solver_globals();
    std::lock_guard<SolverSpinMutex> lock(globals.mutex);
    clear_error();
    SolverContext* ctx = lookup_context(handle);
    if (!ctx) {
        return AERO_SOLVER_STATUS_ERROR;
    }
    if (stride <= 0) {
        set_error("stride must be positive");
        return AERO_SOLVER_STATUS_ERROR;
    }
    const int sx = (ctx->grid.nx + stride - 1) / stride;
    const int sy = (ctx->grid.ny + stride - 1) / stride;
    const int sz = (ctx->grid.nz + stride - 1) / stride;
    const int64_t values = static_cast<int64_t>(sx) * sy * sz * kOutputChannels;
    if (!out_flow_atlas || values <= 0 || values > std::numeric_limits<int>::max()
        || out_value_count != static_cast<int>(values)) {
        set_error("invalid flow atlas buffer");
        return AERO_SOLVER_STATUS_ERROR;
    }
    if (!aero_lbm_extract_flow_atlas_rect(
            ctx->grid.nx,
            ctx->grid.ny,
            ctx->grid.nz,
            ctx->context_key,
            stride,
            out_flow_atlas,
            out_value_count)) {
        set_error(std::string("aero_lbm_extract_flow_atlas_rect failed: ") + aero_lbm_last_error());
        return AERO_SOLVER_STATUS_ERROR;
    }
    return AERO_SOLVER_STATUS_OK;
}

AERO_LBM_CAPI_EXPORT int aero_solver_compute_force_moment(
    long long handle,
    const float* reference_point,
    AeroForceMoment* out_force_moment
) {
    SolverGlobals& globals = solver_globals();
    std::lock_guard<SolverSpinMutex> lock(globals.mutex);
    clear_error();
    SolverContext* ctx = lookup_context(handle);
    if (!ctx) {
        return AERO_SOLVER_STATUS_ERROR;
    }
    if (!out_force_moment) {
        set_error("missing force/moment output");
        return AERO_SOLVER_STATUS_ERROR;
    }
    clear_force_moment(*out_force_moment, reference_point);
    float values[AERO_LBM_FORCE_MOMENT_FLOATS] = {};
    int surface_link_count = 0;
    const float density = std::max(1.0e-6f, finite_or(ctx->force_boundary.density, kDefaultDensityKgM3));
    if (!aero_lbm_compute_momentum_exchange_force_moment_rect(
            ctx->grid.nx,
            ctx->grid.ny,
            ctx->grid.nz,
            ctx->context_key,
            ctx->solid_mask.data(),
            ctx->grid.dx,
            ctx->grid.dt,
            density,
            reference_point,
            values,
            &surface_link_count)) {
        set_error(std::string("momentum-exchange force/moment failed: ") + aero_lbm_last_error());
        return AERO_SOLVER_STATUS_ERROR;
    }
    for (int axis = 0; axis < 3; ++axis) {
        out_force_moment->force[axis] = values[axis];
        out_force_moment->moment[axis] = values[3 + axis];
        out_force_moment->center_of_pressure[axis] = values[6 + axis];
        out_force_moment->reference_point[axis] = values[9 + axis];
    }
    out_force_moment->surface_link_count = surface_link_count;
    out_force_moment->status = AERO_SOLVER_STATUS_OK;
    return AERO_SOLVER_STATUS_OK;
}

AERO_LBM_CAPI_EXPORT int aero_solver_run_wind_tunnel(
    const AeroStepInput* input,
    AeroStepOutput* output
) {
    if (!input || !output || !output->flow_out) {
        SolverGlobals& globals = solver_globals();
        std::lock_guard<SolverSpinMutex> lock(globals.mutex);
        set_error("missing step input or output");
        return AERO_SOLVER_STATUS_ERROR;
    }
    long long handle = 0;
    if (!aero_solver_create_with_grid(&input->grid, &handle)) {
        output->status = AERO_SOLVER_STATUS_ERROR;
        return AERO_SOLVER_STATUS_ERROR;
    }
    const int cells = input->grid.nx * input->grid.ny * input->grid.nz;
    if (input->solid_mask && !aero_solver_set_solid_mask(handle, input->solid_mask, cells)) {
        aero_solver_destroy(handle);
        output->status = AERO_SOLVER_STATUS_ERROR;
        return AERO_SOLVER_STATUS_ERROR;
    }
    if (input->prev_flow && !aero_solver_set_flow_state(handle, input->prev_flow, cells * kOutputChannels)) {
        aero_solver_destroy(handle);
        output->status = AERO_SOLVER_STATUS_ERROR;
        return AERO_SOLVER_STATUS_ERROR;
    }
    const int ok = aero_solver_step_wind_tunnel(
        handle,
        &input->boundary,
        input->steps,
        output->flow_out,
        cells * kOutputChannels
    );
    output->max_velocity = ok ? max_velocity_from_output(cells, output->flow_out, cells * kOutputChannels) : 0.0f;
    output->status = ok;
    aero_solver_destroy(handle);
    return ok;
}

AERO_LBM_CAPI_EXPORT void aero_solver_destroy(long long handle) {
    SolverGlobals& globals = solver_globals();
    std::lock_guard<SolverSpinMutex> lock(globals.mutex);
    auto it = globals.contexts.find(handle);
    if (it == globals.contexts.end()) {
        return;
    }
    release_context_locked(*it->second);
    globals.contexts.erase(it);
}

AERO_LBM_CAPI_EXPORT const char* aero_solver_last_error(void) {
    const std::string& error = solver_globals().last_error;
    return error.empty() ? aero_lbm_last_error() : error.c_str();
}

AERO_LBM_CAPI_EXPORT const char* aero_solver_runtime_info(void) {
    return aero_lbm_runtime_info();
}

AERO_LBM_CAPI_EXPORT int aero_solver_finish(void) {
    SolverGlobals& globals = solver_globals();
    std::lock_guard<SolverSpinMutex> lock(globals.mutex);
    clear_error();
    if (!aero_lbm_finish()) {
        set_error(std::string("aero_lbm_finish failed: ") + aero_lbm_last_error());
        return AERO_SOLVER_STATUS_ERROR;
    }
    return AERO_SOLVER_STATUS_OK;
}

}  // extern "C"
