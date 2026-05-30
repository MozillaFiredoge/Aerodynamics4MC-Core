#!/usr/bin/env python3
"""Benchmark the external aero_solver C API from a built native DLL.

Example on Windows:

    py native\\tools\\benchmark_solver_dll.py --dll path\\to\\aero_lbm.dll --grid 128 --steps-per-frame 1 --frames 120
    py native\\tools\\benchmark_solver_dll.py --dll path\\to\\aero_lbm.dll --grid 128 --no-readback

The script uses ctypes only. It does not require Minecraft, Fabric, Java, or JNI.
"""

from __future__ import annotations

import argparse
import binascii
import csv
import ctypes
import json
import math
import os
import platform
import struct
import sys
import zipfile
import zlib
from pathlib import Path
from time import perf_counter


AERO_SOLVER_BOUNDARY_WIND_TUNNEL = 1
AERO_SOLVER_FLOW_CHANNELS = 4
DEFAULT_MESH_FIT_BOX = (0.22, 0.62, 0.25, 0.75, 0.18, 0.56)
DEFAULT_MESH_AXIS_MAP = ("x", "y", "z")


BASE_DEFAULTS = {
    "grid": 128,
    "dx": 1.0,
    "dt": 0.05,
    "velocity": 2.0,
    "steps_per_frame": 1,
    "frames": 120,
    "warmup": 8,
    "obstacle": "sphere",
    "obstacle_radius_ratio": 0.08,
}


SCENARIO_PRESETS = {
    "a4mc-1s-dev": {
        "nx": 128,
        "ny": 48,
        "nz": 64,
        "dx": 1.0,
        "dt": 1.0 / 60.0,
        "velocity": 2.0,
        "steps_per_frame": 1,
        "frames": 60,
        "warmup": 8,
        "obstacle": "voxel-car",
        "obstacle_radius_ratio": 0.08,
        "description": "Small A4MC 1-second voxel wind-tunnel showcase preset.",
    },
    "a4mc-1s-standard": {
        "nx": 512,
        "ny": 192,
        "nz": 256,
        "dx": 1.0,
        "dt": 1.0 / 60.0,
        "velocity": 2.0,
        "steps_per_frame": 1,
        "frames": 60,
        "warmup": 8,
        "obstacle": "voxel-car",
        "obstacle_radius_ratio": 0.08,
        "description": "Standard A4MC 1-second voxel wind-tunnel showcase preset.",
    },
    "a4mc-1s-hero": {
        "nx": 768,
        "ny": 288,
        "nz": 384,
        "dx": 1.0,
        "dt": 1.0 / 60.0,
        "velocity": 2.0,
        "steps_per_frame": 1,
        "frames": 60,
        "warmup": 8,
        "obstacle": "voxel-car",
        "obstacle_radius_ratio": 0.08,
        "description": "High-memory A4MC 1-second voxel wind-tunnel showcase preset.",
    },
}


class AeroBoundaryDesc(ctypes.Structure):
    _fields_ = [
        ("mode", ctypes.c_int),
        ("inlet_vx", ctypes.c_float),
        ("inlet_vy", ctypes.c_float),
        ("inlet_vz", ctypes.c_float),
        ("outlet_pressure", ctypes.c_float),
        ("density", ctypes.c_float),
        ("viscosity", ctypes.c_float),
    ]


def load_library(path: Path) -> ctypes.CDLL:
    if not path.exists():
        raise FileNotFoundError(f"DLL not found: {path}")
    if os.name == "nt":
        return ctypes.WinDLL(str(path))
    return ctypes.CDLL(str(path))


def configure_api(lib: ctypes.CDLL) -> None:
    lib.aero_solver_create.argtypes = [
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_float,
        ctypes.c_float,
        ctypes.POINTER(ctypes.c_longlong),
    ]
    lib.aero_solver_create.restype = ctypes.c_int

    lib.aero_solver_set_solid_mask.argtypes = [
        ctypes.c_longlong,
        ctypes.POINTER(ctypes.c_uint8),
        ctypes.c_int,
    ]
    lib.aero_solver_set_solid_mask.restype = ctypes.c_int

    lib.aero_solver_set_flow_state.argtypes = [
        ctypes.c_longlong,
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_int,
    ]
    lib.aero_solver_set_flow_state.restype = ctypes.c_int

    lib.aero_solver_step_wind_tunnel.argtypes = [
        ctypes.c_longlong,
        ctypes.POINTER(AeroBoundaryDesc),
        ctypes.c_int,
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_int,
    ]
    lib.aero_solver_step_wind_tunnel.restype = ctypes.c_int

    try:
        lib.aero_solver_advance_wind_tunnel.argtypes = [
            ctypes.c_longlong,
            ctypes.POINTER(AeroBoundaryDesc),
            ctypes.c_int,
        ]
        lib.aero_solver_advance_wind_tunnel.restype = ctypes.c_int
    except AttributeError:
        pass

    try:
        lib.aero_solver_extract_flow_atlas.argtypes = [
            ctypes.c_longlong,
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_float),
            ctypes.c_int,
        ]
        lib.aero_solver_extract_flow_atlas.restype = ctypes.c_int
    except AttributeError:
        pass

    lib.aero_solver_destroy.argtypes = [ctypes.c_longlong]
    lib.aero_solver_destroy.restype = None

    lib.aero_solver_last_error.argtypes = []
    lib.aero_solver_last_error.restype = ctypes.c_char_p

    lib.aero_solver_runtime_info.argtypes = []
    lib.aero_solver_runtime_info.restype = ctypes.c_char_p

    try:
        lib.aero_solver_finish.argtypes = []
        lib.aero_solver_finish.restype = ctypes.c_int
    except AttributeError:
        pass

    try:
        lib.aero_lbm_reset_timing.argtypes = []
        lib.aero_lbm_reset_timing.restype = None
        lib.aero_lbm_timing_info.argtypes = []
        lib.aero_lbm_timing_info.restype = ctypes.c_char_p
        lib.aero_lbm_memory_info.argtypes = []
        lib.aero_lbm_memory_info.restype = ctypes.c_char_p
    except AttributeError:
        pass


def native_error(lib: ctypes.CDLL) -> str:
    ptr = lib.aero_solver_last_error()
    if not ptr:
        return "unknown native error"
    return ptr.decode("utf-8", errors="replace")


def runtime_info(lib: ctypes.CDLL) -> str:
    ptr = lib.aero_solver_runtime_info()
    if not ptr:
        return "unknown runtime"
    return ptr.decode("utf-8", errors="replace")


def reset_native_timing(lib: ctypes.CDLL) -> None:
    reset = getattr(lib, "aero_lbm_reset_timing", None)
    if reset is not None:
        reset()


def native_timing_info(lib: ctypes.CDLL) -> str:
    timing = getattr(lib, "aero_lbm_timing_info", None)
    if timing is None:
        return "native timing unavailable"
    ptr = timing()
    if not ptr:
        return "native timing unavailable"
    return ptr.decode("utf-8", errors="replace")


def native_memory_info(lib: ctypes.CDLL) -> str:
    memory = getattr(lib, "aero_lbm_memory_info", None)
    if memory is None:
        return "native memory unavailable"
    ptr = memory()
    if not ptr:
        return "native memory unavailable"
    return ptr.decode("utf-8", errors="replace")


def cell_index(x: int, y: int, z: int, ny: int, nz: int) -> int:
    return (x * ny + y) * nz + z


def parse_fit_box(value: str) -> tuple[float, float, float, float, float, float]:
    parts = [part.strip() for part in value.split(",")]
    if len(parts) != 6:
        raise argparse.ArgumentTypeError("fit box must have 6 comma-separated values: x0,x1,y0,y1,z0,z1")
    try:
        parsed = tuple(float(part) for part in parts)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("fit box values must be numeric") from exc
    x0, x1, y0, y1, z0, z1 = parsed
    if not (0.0 <= x0 < x1 <= 1.0 and 0.0 <= y0 < y1 <= 1.0 and 0.0 <= z0 < z1 <= 1.0):
        raise argparse.ArgumentTypeError("fit box values must satisfy 0 <= min < max <= 1 for each axis")
    return parsed  # type: ignore[return-value]


def parse_axis_map(value: str) -> tuple[str, str, str]:
    parts = tuple(part.strip().lower() for part in value.split(","))
    if len(parts) != 3 or set(parts) != {"x", "y", "z"}:
        raise argparse.ArgumentTypeError("axis map must be a permutation like x,y,z or y,x,z")
    return parts


def load_stl_triangles(path: Path) -> list[tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]]:
    data = path.read_bytes()
    triangles: list[tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]] = []
    if len(data) >= 84:
        tri_count = struct.unpack_from("<I", data, 80)[0]
        expected = 84 + tri_count * 50
        if expected == len(data):
            offset = 84
            for _ in range(tri_count):
                values = struct.unpack_from("<12fH", data, offset)
                triangles.append((
                    (values[3], values[4], values[5]),
                    (values[6], values[7], values[8]),
                    (values[9], values[10], values[11]),
                ))
                offset += 50
            return triangles

    vertices: list[tuple[float, float, float]] = []
    text = data.decode("utf-8", errors="replace")
    for line in text.splitlines():
        fields = line.strip().split()
        if len(fields) == 4 and fields[0].lower() == "vertex":
            try:
                vertices.append((float(fields[1]), float(fields[2]), float(fields[3])))
            except ValueError:
                pass
    for index in range(0, len(vertices) - 2, 3):
        triangles.append((vertices[index], vertices[index + 1], vertices[index + 2]))
    return triangles


def transformed_mesh_triangles(
    triangles: list[tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]],
    nx: int,
    ny: int,
    nz: int,
    fit_box: tuple[float, float, float, float, float, float],
    axis_map: tuple[str, str, str],
) -> tuple[
    list[tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]],
    dict[str, object],
]:
    axis_index = {"x": 0, "y": 1, "z": 2}
    remapped_triangles = [
        tuple(
            (
                point[axis_index[axis_map[0]]],
                point[axis_index[axis_map[1]]],
                point[axis_index[axis_map[2]]],
            )
            for point in tri
        )
        for tri in triangles
    ]
    points = [point for tri in remapped_triangles for point in tri]
    if not points:
        return [], {"triangles": 0}
    mins = [min(point[axis] for point in points) for axis in range(3)]
    maxs = [max(point[axis] for point in points) for axis in range(3)]
    extents = [max(maxs[axis] - mins[axis], 1.0e-12) for axis in range(3)]
    x0, x1, y0, y1, z0, z1 = fit_box
    fit_min = [x0 * nx, y0 * ny, z0 * nz]
    fit_max = [x1 * nx, y1 * ny, z1 * nz]
    fit_extents = [fit_max[axis] - fit_min[axis] for axis in range(3)]
    scale = min(fit_extents[axis] / extents[axis] for axis in range(3))
    transformed_extent = [extents[axis] * scale for axis in range(3)]
    offset = [
        fit_min[0] + 0.5 * (fit_extents[0] - transformed_extent[0]) - mins[0] * scale,
        fit_min[1] + 0.5 * (fit_extents[1] - transformed_extent[1]) - mins[1] * scale,
        fit_min[2] - mins[2] * scale,
    ]

    transformed = []
    for tri in remapped_triangles:
        transformed.append(tuple(
            (
                point[0] * scale + offset[0],
                point[1] * scale + offset[1],
                point[2] * scale + offset[2],
            )
            for point in tri
        ))
    transformed_points = [point for tri in transformed for point in tri]
    transformed_mins = [min(point[axis] for point in transformed_points) for axis in range(3)]
    transformed_maxs = [max(point[axis] for point in transformed_points) for axis in range(3)]
    metadata = {
        "triangles": len(triangles),
        "source_bounds": {"min": mins, "max": maxs, "extent": extents},
        "grid_bounds": {"min": transformed_mins, "max": transformed_maxs},
        "fit_box": list(fit_box),
        "axis_map": list(axis_map),
        "scale": scale,
    }
    return transformed, metadata


def projected_point_in_triangle(
    px: float,
    pz: float,
    a: tuple[float, float, float],
    b: tuple[float, float, float],
    c: tuple[float, float, float],
) -> tuple[bool, float, float, float]:
    v0x = b[0] - a[0]
    v0z = b[2] - a[2]
    v1x = c[0] - a[0]
    v1z = c[2] - a[2]
    v2x = px - a[0]
    v2z = pz - a[2]
    denom = v0x * v1z - v1x * v0z
    if abs(denom) < 1.0e-10:
        return False, 0.0, 0.0, 0.0
    u = (v2x * v1z - v1x * v2z) / denom
    v = (v0x * v2z - v2x * v0z) / denom
    w = 1.0 - u - v
    eps = 1.0e-6
    return u >= -eps and v >= -eps and w >= -eps, u, v, w


def voxelize_mesh_triangles(
    triangles: list[tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]],
    nx: int,
    ny: int,
    nz: int,
) -> ctypes.Array[ctypes.c_uint8]:
    cells = nx * ny * nz
    mask = (ctypes.c_uint8 * cells)()
    crossings: list[list[float]] = [[] for _ in range(nx * nz)]

    for a, b, c in triangles:
        min_x = clamp_int(int(math.floor(min(a[0], b[0], c[0]) - 0.5)), 0, nx - 1)
        max_x = clamp_int(int(math.ceil(max(a[0], b[0], c[0]) - 0.5)), 0, nx - 1)
        min_z = clamp_int(int(math.floor(min(a[2], b[2], c[2]) - 0.5)), 0, nz - 1)
        max_z = clamp_int(int(math.ceil(max(a[2], b[2], c[2]) - 0.5)), 0, nz - 1)
        for x in range(min_x, max_x + 1):
            px = x + 0.5
            for z in range(min_z, max_z + 1):
                pz = z + 0.5
                inside, u, v, w = projected_point_in_triangle(px, pz, a, b, c)
                if not inside:
                    continue
                y = a[1] * w + b[1] * u + c[1] * v
                if -0.5 <= y <= ny + 0.5:
                    crossings[x * nz + z].append(y)

    for x in range(nx):
        for z in range(nz):
            ys = sorted(crossings[x * nz + z])
            if len(ys) < 2:
                continue
            deduped: list[float] = []
            for value in ys:
                if not deduped or abs(value - deduped[-1]) > 1.0e-4:
                    deduped.append(value)
            for index in range(0, len(deduped) - 1, 2):
                y0 = clamp_int(int(math.ceil(deduped[index] - 0.5)), 0, ny)
                y1 = clamp_int(int(math.floor(deduped[index + 1] - 0.5)) + 1, 0, ny)
                for y in range(y0, y1):
                    mask[cell_index(x, y, z, ny, nz)] = 1
    return mask


def build_mesh_mask(
    mesh_path: Path,
    nx: int,
    ny: int,
    nz: int,
    fit_box: tuple[float, float, float, float, float, float],
    axis_map: tuple[str, str, str],
) -> tuple[ctypes.Array[ctypes.c_uint8], dict[str, object]]:
    if not mesh_path.exists():
        raise FileNotFoundError(f"mesh not found: {mesh_path}")
    if mesh_path.suffix.lower() != ".stl":
        raise ValueError("mesh import currently supports Blender-exported STL files only")
    source_triangles = load_stl_triangles(mesh_path)
    if not source_triangles:
        raise ValueError(f"no triangles found in mesh: {mesh_path}")
    triangles, metadata = transformed_mesh_triangles(source_triangles, nx, ny, nz, fit_box, axis_map)
    mask = voxelize_mesh_triangles(triangles, nx, ny, nz)
    metadata["path"] = str(mesh_path)
    metadata["format"] = "stl"
    return mask, metadata


def clamp_int(value: int, lower: int, upper: int) -> int:
    return max(lower, min(upper, value))


def fill_box(
    mask: ctypes.Array[ctypes.c_uint8],
    nx: int,
    ny: int,
    nz: int,
    x0: int,
    x1: int,
    y0: int,
    y1: int,
    z0: int,
    z1: int,
) -> None:
    x0 = clamp_int(x0, 0, nx)
    x1 = clamp_int(x1, 0, nx)
    y0 = clamp_int(y0, 0, ny)
    y1 = clamp_int(y1, 0, ny)
    z0 = clamp_int(z0, 0, nz)
    z1 = clamp_int(z1, 0, nz)
    for x in range(x0, x1):
        for y in range(y0, y1):
            base = cell_index(x, y, z0, ny, nz)
            for offset in range(z1 - z0):
                mask[base + offset] = 1


def build_ahmed_mask(nx: int, ny: int, nz: int) -> ctypes.Array[ctypes.c_uint8]:
    cells = nx * ny * nz
    mask = (ctypes.c_uint8 * cells)()
    length = max(4, int(round(nx * 0.30)))
    width = max(4, int(round(ny * 0.34)))
    height = max(3, int(round(nz * 0.22)))
    x0 = int(round(nx * 0.26))
    x1 = min(nx - 2, x0 + length)
    y0 = max(1, ny // 2 - width // 2)
    y1 = min(ny - 1, y0 + width)
    z0 = max(1, int(round(nz * 0.28)))
    z1 = min(nz - 1, z0 + height)
    slant_len = max(2, length // 5)
    slant_drop = max(1, height // 2)

    for x in range(x0, x1):
        if x >= x1 - slant_len:
            t = (x - (x1 - slant_len) + 1) / float(slant_len)
            local_z1 = max(z0 + 1, z1 - int(round(t * slant_drop)))
        else:
            local_z1 = z1
        fill_box(mask, nx, ny, nz, x, x + 1, y0, y1, z0, local_z1)
    return mask


def build_voxel_car_mask(nx: int, ny: int, nz: int) -> ctypes.Array[ctypes.c_uint8]:
    mask = build_ahmed_mask(nx, ny, nz)
    length = max(6, int(round(nx * 0.34)))
    width = max(5, int(round(ny * 0.38)))
    height = max(4, int(round(nz * 0.18)))
    x0 = int(round(nx * 0.24))
    x1 = min(nx - 2, x0 + length)
    y0 = max(1, ny // 2 - width // 2)
    y1 = min(ny - 1, y0 + width)
    z0 = max(1, int(round(nz * 0.24)))
    z1 = min(nz - 1, z0 + height)

    wing_thickness = max(1, nz // 40)
    wing_width = max(width, int(round(ny * 0.52)))
    wy0 = max(1, ny // 2 - wing_width // 2)
    wy1 = min(ny - 1, wy0 + wing_width)
    front_x0 = max(1, x0 - max(2, length // 8))
    rear_x0 = min(nx - 2, x1 - max(2, length // 10))
    fill_box(mask, nx, ny, nz, front_x0, x0 + max(1, length // 12), wy0, wy1, z0, z0 + wing_thickness)
    fill_box(mask, nx, ny, nz, rear_x0, min(nx - 1, rear_x0 + max(2, length // 8)), wy0, wy1, z1, min(nz, z1 + wing_thickness))

    cockpit_len = max(2, length // 5)
    cockpit_width = max(3, width // 2)
    cockpit_height = max(2, height // 2)
    cx0 = x0 + length // 3
    cy0 = ny // 2 - cockpit_width // 2
    fill_box(mask, nx, ny, nz, cx0, cx0 + cockpit_len, cy0, cy0 + cockpit_width, z1, z1 + cockpit_height)

    wheel_radius = max(1, min(nx, ny, nz) // 24)
    wheel_half_width = max(1, ny // 40)
    wheel_z = max(1, z0)
    wheel_x_positions = [x0 + length // 5, x0 + (length * 4) // 5]
    wheel_y_positions = [max(1, y0 - wheel_half_width), min(ny - 2, y1 + wheel_half_width)]
    r2 = wheel_radius * wheel_radius
    for wx in wheel_x_positions:
        for wy in wheel_y_positions:
            for x in range(max(0, wx - wheel_radius), min(nx, wx + wheel_radius + 1)):
                dx = x - wx
                for y in range(max(0, wy - wheel_half_width), min(ny, wy + wheel_half_width + 1)):
                    for z in range(max(0, wheel_z - wheel_radius), min(nz, wheel_z + wheel_radius + 1)):
                        dz = z - wheel_z
                        if dx * dx + dz * dz <= r2:
                            mask[cell_index(x, y, z, ny, nz)] = 1
    return mask


def build_obstacle_mask(
    nx: int,
    ny: int,
    nz: int,
    obstacle: str,
    radius_ratio: float,
) -> ctypes.Array[ctypes.c_uint8]:
    cells = nx * ny * nz
    mask = (ctypes.c_uint8 * cells)()
    if obstacle == "none":
        return mask

    if obstacle == "ahmed":
        return build_ahmed_mask(nx, ny, nz)

    if obstacle == "voxel-car":
        return build_voxel_car_mask(nx, ny, nz)

    cx = nx // 3
    cy = ny // 2
    cz = nz // 2
    radius = max(1.0, min(nx, ny, nz) * radius_ratio)

    if obstacle == "cube":
        half = max(1, int(round(radius)))
        for x in range(max(0, cx - half), min(nx, cx + half + 1)):
            for y in range(max(0, cy - half), min(ny, cy + half + 1)):
                for z in range(max(0, cz - half), min(nz, cz + half + 1)):
                    mask[cell_index(x, y, z, ny, nz)] = 1
        return mask

    if obstacle == "sphere":
        r2 = radius * radius
        xmin = max(0, int(math.floor(cx - radius)))
        xmax = min(nx - 1, int(math.ceil(cx + radius)))
        ymin = max(0, int(math.floor(cy - radius)))
        ymax = min(ny - 1, int(math.ceil(cy + radius)))
        zmin = max(0, int(math.floor(cz - radius)))
        zmax = min(nz - 1, int(math.ceil(cz + radius)))
        for x in range(xmin, xmax + 1):
            for y in range(ymin, ymax + 1):
                for z in range(zmin, zmax + 1):
                    dx = x - cx
                    dy = y - cy
                    dz = z - cz
                    if dx * dx + dy * dy + dz * dz <= r2:
                        mask[cell_index(x, y, z, ny, nz)] = 1
        return mask

    if obstacle == "cylinder":
        r2 = radius * radius
        for x in range(max(0, cx - 1), min(nx, cx + 2)):
            for y in range(ny):
                dy = y - cy
                for z in range(nz):
                    dz = z - cz
                    if dy * dy + dz * dz <= r2:
                        mask[cell_index(x, y, z, ny, nz)] = 1
        return mask

    raise ValueError(f"unknown obstacle: {obstacle}")


def dilate_solid_mask(
    mask: ctypes.Array[ctypes.c_uint8],
    nx: int,
    ny: int,
    nz: int,
    radius: int,
) -> ctypes.Array[ctypes.c_uint8]:
    if radius <= 0:
        return mask
    cells = nx * ny * nz
    source = [int(value) for value in mask]
    target = (ctypes.c_uint8 * cells)()
    yz = ny * nz
    for cell, value in enumerate(source):
        if value == 0:
            continue
        x = cell // yz
        rem = cell - x * yz
        y = rem // nz
        z = rem - y * nz
        for dx in range(-radius, radius + 1):
            xx = x + dx
            if xx < 0 or xx >= nx:
                continue
            for dy in range(-radius, radius + 1):
                yy = y + dy
                if yy < 0 or yy >= ny:
                    continue
                base = cell_index(xx, yy, 0, ny, nz)
                for dz in range(-radius, radius + 1):
                    zz = z + dz
                    if 0 <= zz < nz:
                        target[base + zz] = 1
    return target


def validate_output(flow: ctypes.Array[ctypes.c_float], max_scan_values: int) -> tuple[float, int]:
    max_speed = 0.0
    non_finite = 0
    value_count = min(len(flow), max_scan_values)
    cell_count = value_count // AERO_SOLVER_FLOW_CHANNELS
    for cell in range(cell_count):
        base = cell * AERO_SOLVER_FLOW_CHANNELS
        vx = float(flow[base])
        vy = float(flow[base + 1])
        vz = float(flow[base + 2])
        if not (math.isfinite(vx) and math.isfinite(vy) and math.isfinite(vz) and math.isfinite(float(flow[base + 3]))):
            non_finite += 1
            continue
        max_speed = max(max_speed, math.sqrt(vx * vx + vy * vy + vz * vz))
    return max_speed, non_finite


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * q))))
    return ordered[index]


def apply_scenario_defaults(args: argparse.Namespace) -> dict:
    preset = SCENARIO_PRESETS.get(args.scenario, {})
    scenario_grid = preset.get("grid")
    base_grid = scenario_grid if scenario_grid is not None else BASE_DEFAULTS["grid"]
    selected_grid = args.grid if args.grid is not None else base_grid

    for name in ("dx", "dt", "velocity", "steps_per_frame", "frames", "warmup", "obstacle", "obstacle_radius_ratio"):
        if getattr(args, name) is None:
            setattr(args, name, preset.get(name, BASE_DEFAULTS[name]))

    if args.nx is None:
        args.nx = selected_grid if args.grid is not None else preset.get("nx", selected_grid)
    if args.ny is None:
        args.ny = selected_grid if args.grid is not None else preset.get("ny", selected_grid)
    if args.nz is None:
        args.nz = selected_grid if args.grid is not None else preset.get("nz", selected_grid)

    if args.grid is None:
        args.grid = selected_grid
    return preset


def json_default(value: object) -> object:
    if isinstance(value, Path):
        return str(value)
    raise TypeError(f"Object of type {type(value).__name__} is not JSON serializable")


def write_metrics_csv(path: Path, rows: list[dict[str, object]]) -> None:
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "frame",
        "kind",
        "elapsed_ms",
        "max_speed_mps",
        "non_finite_values",
    ]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def write_manifest(path: Path, manifest: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, indent=2, default=json_default), encoding="utf-8")


def resolve_artifact_path(value: Path | None, output_dir: Path | None, default_name: str) -> Path | None:
    if value is None:
        return None
    path = Path(default_name) if str(value) == "" else value
    if not path.is_absolute() and output_dir is not None:
        path = output_dir / path
    return path


def npy_uint8_bytes(mask: ctypes.Array[ctypes.c_uint8], nx: int, ny: int, nz: int) -> bytes:
    header = {
        "descr": "|u1",
        "fortran_order": False,
        "shape": (nx, ny, nz),
    }
    header_text = repr(header)
    header_body = header_text.encode("latin1")
    padding = (16 - ((10 + len(header_body) + 1) % 16)) % 16
    final_header = header_body + b" " * padding + b"\n"
    return b"\x93NUMPY\x01\x00" + struct.pack("<H", len(final_header)) + final_header + bytes(mask)


def npy_float32_bytes(flow: ctypes.Array[ctypes.c_float], shape: tuple[int, ...]) -> bytes:
    header = {
        "descr": "<f4" if sys.byteorder == "little" else ">f4",
        "fortran_order": False,
        "shape": shape,
    }
    header_text = repr(header)
    header_body = header_text.encode("latin1")
    padding = (16 - ((10 + len(header_body) + 1) % 16)) % 16
    final_header = header_body + b" " * padding + b"\n"
    payload = ctypes.string_at(ctypes.addressof(flow), len(flow) * ctypes.sizeof(ctypes.c_float))
    return b"\x93NUMPY\x01\x00" + struct.pack("<H", len(final_header)) + final_header + payload


def write_solid_mask_npz(
    path: Path,
    mask: ctypes.Array[ctypes.c_uint8],
    nx: int,
    ny: int,
    nz: int,
    metadata: dict[str, object],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("solid_mask.npy", npy_uint8_bytes(mask, nx, ny, nz))
        archive.writestr("metadata.json", json.dumps(metadata, indent=2, default=json_default))


def write_flow_npz(
    path: Path,
    flow: ctypes.Array[ctypes.c_float],
    nx: int,
    ny: int,
    nz: int,
    metadata: dict[str, object],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("flow.npy", npy_float32_bytes(flow, (nx, ny, nz, AERO_SOLVER_FLOW_CHANNELS)))
        archive.writestr("metadata.json", json.dumps(metadata, indent=2, default=json_default))


def write_png_rgb(path: Path, width: int, height: int, pixels: bytes) -> None:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", binascii.crc32(kind + data) & 0xffffffff)
        )

    raw = bytearray()
    row_bytes = width * 3
    for y in range(height):
        raw.append(0)
        start = y * row_bytes
        raw.extend(pixels[start:start + row_bytes])
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def projection_panel(
    width: int,
    height: int,
    scale: int,
    occupied: callable,
) -> tuple[int, int, bytearray]:
    out_w = width * scale
    out_h = height * scale
    pixels = bytearray([246, 248, 250] * out_w * out_h)
    for py in range(height):
        for px in range(width):
            color = (22, 27, 34) if occupied(px, py) else (246, 248, 250)
            for sy in range(scale):
                row = (py * scale + sy) * out_w
                for sx in range(scale):
                    dst = (row + px * scale + sx) * 3
                    pixels[dst:dst + 3] = bytes(color)
    return out_w, out_h, pixels


def blit_panel(
    canvas: bytearray,
    canvas_w: int,
    x0: int,
    y0: int,
    panel_w: int,
    panel_h: int,
    panel: bytearray,
) -> None:
    for y in range(panel_h):
        src_start = y * panel_w * 3
        src_end = src_start + panel_w * 3
        dst_start = ((y0 + y) * canvas_w + x0) * 3
        canvas[dst_start:dst_start + panel_w * 3] = panel[src_start:src_end]


def write_solid_mask_slices_png(
    path: Path,
    mask: ctypes.Array[ctypes.c_uint8],
    nx: int,
    ny: int,
    nz: int,
) -> None:
    scale = max(1, min(8, 512 // max(nx, ny, nz)))

    def solid(x: int, y: int, z: int) -> bool:
        return mask[cell_index(x, y, z, ny, nz)] != 0

    top_w, top_h, top = projection_panel(
        nx,
        ny,
        scale,
        lambda px, py: any(solid(px, ny - 1 - py, z) for z in range(nz)),
    )
    side_w, side_h, side = projection_panel(
        nx,
        nz,
        scale,
        lambda px, py: any(solid(px, y, nz - 1 - py) for y in range(ny)),
    )
    front_w, front_h, front = projection_panel(
        ny,
        nz,
        scale,
        lambda px, py: any(solid(x, px, nz - 1 - py) for x in range(nx)),
    )

    margin = 8
    gap = 8
    canvas_w = margin * 2 + top_w + side_w + front_w + gap * 2
    canvas_h = margin * 2 + max(top_h, side_h, front_h)
    canvas = bytearray([235, 238, 242] * canvas_w * canvas_h)
    x = margin
    blit_panel(canvas, canvas_w, x, margin, top_w, top_h, top)
    x += top_w + gap
    blit_panel(canvas, canvas_w, x, margin, side_w, side_h, side)
    x += side_w + gap
    blit_panel(canvas, canvas_w, x, margin, front_w, front_h, front)
    write_png_rgb(path, canvas_w, canvas_h, bytes(canvas))


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark aero_lbm.dll wind-tunnel solver API")
    parser.add_argument("--dll", required=True, type=Path, help="Path to aero_lbm.dll / libaero_lbm.so")
    parser.add_argument(
        "--scenario",
        choices=("manual", *SCENARIO_PRESETS.keys()),
        default="manual",
        help="Named benchmark preset. Manual preserves the original direct-argument behavior.",
    )
    parser.add_argument("--grid", type=int, default=None, help="Cubic grid resolution, e.g. 128")
    parser.add_argument("--nx", type=int, default=None, help="Override x resolution")
    parser.add_argument("--ny", type=int, default=None, help="Override y resolution")
    parser.add_argument("--nz", type=int, default=None, help="Override z resolution")
    parser.add_argument("--dx", type=float, default=None, help="Cell size in meters")
    parser.add_argument("--dt", type=float, default=None, help="Time step in seconds")
    parser.add_argument("--velocity", type=float, default=None, help="Inlet x velocity in m/s")
    parser.add_argument("--steps-per-frame", type=int, default=None, help="LBM steps per measured frame")
    parser.add_argument("--frames", type=int, default=None, help="Measured frames")
    parser.add_argument("--warmup", type=int, default=None, help="Warmup frames before measurement")
    parser.add_argument(
        "--obstacle",
        choices=("none", "cube", "sphere", "cylinder", "ahmed", "voxel-car"),
        default=None,
        help="Simple obstacle inserted near the inlet",
    )
    parser.add_argument("--obstacle-radius-ratio", type=float, default=None, help="Obstacle radius as fraction of grid")
    parser.add_argument(
        "--mesh",
        type=Path,
        default=None,
        help="Optional Blender-exported STL mesh to voxelize into the wind tunnel. Overrides --obstacle.",
    )
    parser.add_argument(
        "--mesh-fit-box",
        type=parse_fit_box,
        default=DEFAULT_MESH_FIT_BOX,
        help="Fractional fit box for --mesh as x0,x1,y0,y1,z0,z1. Default fits a car-like model into the tunnel.",
    )
    parser.add_argument(
        "--mesh-axis-map",
        type=parse_axis_map,
        default=DEFAULT_MESH_AXIS_MAP,
        help="Source mesh axes used as wind-tunnel x,y,z, e.g. x,y,z or y,x,z.",
    )
    parser.add_argument("--scan-output", action="store_true", help="Scan all output values for NaN and max speed each frame")
    parser.add_argument(
        "--no-readback",
        action="store_true",
        help="Measure solver advance only. A final unmeasured readback is still run for sanity checking.",
    )
    parser.add_argument(
        "--async-enqueue-only",
        action="store_true",
        help="Do not clFinish each no-readback frame. This measures enqueue overhead, not real GPU solve time.",
    )
    parser.add_argument(
        "--skip-final-readback",
        action="store_true",
        help="Skip the no-readback sanity readback. Useful for very large throughput-only runs.",
    )
    parser.add_argument("--output-dir", type=Path, default=None, help="Write manifest.json and metrics.csv here")
    parser.add_argument(
        "--dump-solid-mask",
        nargs="?",
        const="solid_mask.npz",
        default=None,
        type=Path,
        help="Write the voxelized solid mask as an NPZ artifact. Omit the path to use solid_mask.npz.",
    )
    parser.add_argument(
        "--dump-mask-slices",
        nargs="?",
        const="mask_slices.png",
        default=None,
        type=Path,
        help="Write a PNG with top/side/front solid-mask projections. Omit the path to use mask_slices.png.",
    )
    parser.add_argument(
        "--dump-final-flow",
        nargs="?",
        const="flow_final.npz",
        default=None,
        type=Path,
        help="Write the final readback flow field as an NPZ artifact. Omit the path to use flow_final.npz.",
    )
    parser.add_argument(
        "--dump-flow-frames",
        type=Path,
        default=None,
        help="Directory for per-frame flow NPZ snapshots. Requires readback mode, so do not combine with --no-readback.",
    )
    parser.add_argument(
        "--flow-frame-every",
        type=int,
        default=1,
        help="Write one per-frame flow snapshot every N measured frames when --dump-flow-frames is set.",
    )
    parser.add_argument(
        "--solid-dilate",
        type=int,
        default=0,
        help="Expand solid cells by this many Chebyshev-neighborhood voxels after voxelization.",
    )
    parser.add_argument("--run-label", default="", help="Optional label stored in manifest.json")
    parser.add_argument(
        "--require-runtime-substring",
        default="",
        help="Fail unless aero_solver_runtime_info contains this substring, e.g. d3q27-fp16-inplace-srt",
    )
    args = parser.parse_args()
    preset = apply_scenario_defaults(args)

    nx = args.nx
    ny = args.ny
    nz = args.nz
    if min(nx, ny, nz) <= 0:
        raise SystemExit("grid dimensions must be positive")
    if args.steps_per_frame <= 0 or args.frames <= 0 or args.warmup < 0:
        raise SystemExit("steps-per-frame/frames/warmup must be positive")
    if args.solid_dilate < 0:
        raise SystemExit("solid-dilate must be non-negative")
    if args.flow_frame_every <= 0:
        raise SystemExit("flow-frame-every must be positive")
    if args.dump_flow_frames is not None and args.no_readback:
        raise SystemExit("--dump-flow-frames requires readback mode; remove --no-readback")
    if args.dump_final_flow is not None and args.no_readback and args.skip_final_readback:
        raise SystemExit("--dump-final-flow requires a final readback; remove --skip-final-readback")

    cells = nx * ny * nz
    value_count = cells * AERO_SOLVER_FLOW_CHANNELS
    simulated_seconds = args.frames * args.steps_per_frame * args.dt
    print(f"[bench] dll={args.dll}")
    print(f"[bench] scenario={args.scenario}")
    print(f"[bench] grid={nx}x{ny}x{nz} cells={cells:,} flow_values={value_count:,}")
    print(f"[bench] dx={args.dx:g}m dt={args.dt:g}s inlet_vx={args.velocity:g}m/s steps/frame={args.steps_per_frame}")
    print(f"[bench] simulated_seconds={simulated_seconds:g}")
    print(f"[bench] readback={'off' if args.no_readback else 'full-field every frame'}")

    lib = load_library(args.dll)
    configure_api(lib)

    handle = ctypes.c_longlong()
    if not lib.aero_solver_create(nx, ny, nz, ctypes.c_float(args.dx), ctypes.c_float(args.dt), ctypes.byref(handle)):
        raise SystemExit(f"create failed: {native_error(lib)}")
    runtime = runtime_info(lib)
    print(f"[bench] runtime={runtime}")
    if args.require_runtime_substring and args.require_runtime_substring not in runtime:
        raise SystemExit(
            f"runtime requirement failed: missing {args.require_runtime_substring!r} in {runtime!r}"
        )

    try:
        mesh_metadata: dict[str, object] | None = None
        if args.mesh:
            print(f"[bench] voxelizing_mesh={args.mesh}")
            solid, mesh_metadata = build_mesh_mask(args.mesh, nx, ny, nz, args.mesh_fit_box, args.mesh_axis_map)
            obstacle_type = "mesh"
        else:
            solid = build_obstacle_mask(nx, ny, nz, args.obstacle, args.obstacle_radius_ratio)
            obstacle_type = args.obstacle
        raw_solid_count = sum(int(value) for value in solid)
        if args.solid_dilate > 0:
            solid = dilate_solid_mask(solid, nx, ny, nz, args.solid_dilate)
        solid_count = sum(int(value) for value in solid)
        solid_ratio = solid_count / cells if cells else 0.0
        print(
            f"[bench] obstacle={obstacle_type} solid_cells={solid_count:,} "
            f"solid_ratio={solid_ratio:.6f} raw_solid_cells={raw_solid_count:,} "
            f"solid_dilate={args.solid_dilate}"
        )
        if mesh_metadata:
            print(f"[bench] mesh_triangles={mesh_metadata.get('triangles')}")
        artifact_paths: dict[str, str] = {}
        flow_frames_dir = args.dump_flow_frames
        if flow_frames_dir is not None and not flow_frames_dir.is_absolute() and args.output_dir is not None:
            flow_frames_dir = args.output_dir / flow_frames_dir
        solid_metadata = {
            "schema_version": 1,
            "grid": {"nx": nx, "ny": ny, "nz": nz, "cells": cells},
            "obstacle": {
                "type": obstacle_type,
                "radius_ratio": args.obstacle_radius_ratio,
                "raw_solid_cells": raw_solid_count,
                "solid_cells": solid_count,
                "solid_ratio": solid_ratio,
                "solid_dilate": args.solid_dilate,
                "mesh": mesh_metadata,
            },
        }
        solid_mask_path = resolve_artifact_path(args.dump_solid_mask, args.output_dir, "solid_mask.npz")
        if solid_mask_path:
            write_solid_mask_npz(solid_mask_path, solid, nx, ny, nz, solid_metadata)
            artifact_paths["solid_mask_npz"] = str(solid_mask_path)
            print(f"[bench] wrote_solid_mask={solid_mask_path}")
        mask_slices_path = resolve_artifact_path(args.dump_mask_slices, args.output_dir, "mask_slices.png")
        if mask_slices_path:
            write_solid_mask_slices_png(mask_slices_path, solid, nx, ny, nz)
            artifact_paths["mask_slices_png"] = str(mask_slices_path)
            print(f"[bench] wrote_mask_slices={mask_slices_path}")
        if not lib.aero_solver_set_solid_mask(handle.value, solid, cells):
            raise SystemExit(f"set_solid_mask failed: {native_error(lib)}")

        boundary = AeroBoundaryDesc(
            AERO_SOLVER_BOUNDARY_WIND_TUNNEL,
            ctypes.c_float(args.velocity),
            ctypes.c_float(0.0),
            ctypes.c_float(0.0),
            ctypes.c_float(0.0),
            ctypes.c_float(1.225),
            ctypes.c_float(1.5e-5),
        )
        out_flow = (ctypes.c_float * value_count)()
        advance = getattr(lib, "aero_solver_advance_wind_tunnel", None)
        finish = getattr(lib, "aero_solver_finish", None)
        if args.no_readback and advance is None:
            raise SystemExit("no-readback mode requires aero_solver_advance_wind_tunnel in the DLL")
        if args.no_readback and not args.async_enqueue_only and finish is None:
            raise SystemExit("synchronous no-readback mode requires aero_solver_finish in the DLL")

        print(f"[bench] warmup_frames={args.warmup}")
        for _ in range(args.warmup):
            if args.no_readback:
                ok = advance(handle.value, ctypes.byref(boundary), args.steps_per_frame)
                if ok and not args.async_enqueue_only:
                    ok = finish()
            else:
                ok = lib.aero_solver_step_wind_tunnel(
                    handle.value,
                    ctypes.byref(boundary),
                    args.steps_per_frame,
                    out_flow,
                    value_count,
                )
            if not ok:
                raise SystemExit(f"warmup step failed: {native_error(lib)}")

        reset_native_timing(lib)
        print(f"[bench] measured_frames={args.frames}")
        times_ms: list[float] = []
        frame_metrics: list[dict[str, object]] = []
        max_speed = 0.0
        non_finite = 0
        scan_values = value_count if args.scan_output else min(value_count, 4096)
        if flow_frames_dir is not None:
            flow_frames_dir.mkdir(parents=True, exist_ok=True)
            artifact_paths["flow_frames_dir"] = str(flow_frames_dir)
        for frame in range(args.frames):
            start = perf_counter()
            if args.no_readback:
                ok = advance(handle.value, ctypes.byref(boundary), args.steps_per_frame)
                if ok and not args.async_enqueue_only:
                    ok = finish()
            else:
                ok = lib.aero_solver_step_wind_tunnel(
                    handle.value,
                    ctypes.byref(boundary),
                    args.steps_per_frame,
                    out_flow,
                    value_count,
                )
            elapsed_ms = (perf_counter() - start) * 1000.0
            if not ok:
                raise SystemExit(f"frame {frame} step failed: {native_error(lib)}")
            times_ms.append(elapsed_ms)
            frame_max = ""
            frame_bad: object = ""
            if not args.no_readback:
                frame_max, frame_bad = validate_output(out_flow, scan_values)
                max_speed = max(max_speed, frame_max)
                non_finite += frame_bad
                if flow_frames_dir is not None and frame % args.flow_frame_every == 0:
                    flow_frame_path = flow_frames_dir / f"flow_{frame:04d}.npz"
                    write_flow_npz(
                        flow_frame_path,
                        out_flow,
                        nx,
                        ny,
                        nz,
                        {
                            "schema_version": 1,
                            "kind": "flow_frame",
                            "frame": frame,
                            "grid": {"nx": nx, "ny": ny, "nz": nz, "channels": AERO_SOLVER_FLOW_CHANNELS},
                            "physics": {
                                "dx_m": args.dx,
                                "dt_s": args.dt,
                                "inlet_vx_mps": args.velocity,
                                "steps_per_frame": args.steps_per_frame,
                                "measured_time_s": (frame + 1) * args.steps_per_frame * args.dt,
                                "total_time_s": (args.warmup + frame + 1) * args.steps_per_frame * args.dt,
                            },
                            "runtime": runtime,
                            "channel_layout": ["vx", "vy", "vz", "scalar"],
                        },
                    )
            frame_metrics.append(
                {
                    "frame": frame,
                    "kind": "measured",
                    "elapsed_ms": f"{elapsed_ms:.6f}",
                    "max_speed_mps": frame_max if frame_max == "" else f"{frame_max:.9f}",
                    "non_finite_values": frame_bad,
                }
            )

        measured_native_timing = native_timing_info(lib)
        final_readback_done = not args.no_readback
        final_native_timing = measured_native_timing
        if args.no_readback and not args.skip_final_readback:
            final_start = perf_counter()
            ok = lib.aero_solver_step_wind_tunnel(
                handle.value,
                ctypes.byref(boundary),
                1,
                out_flow,
                value_count,
            )
            final_elapsed_ms = (perf_counter() - final_start) * 1000.0
            if not ok:
                raise SystemExit(f"final readback failed: {native_error(lib)}")
            max_speed, non_finite = validate_output(out_flow, scan_values)
            final_native_timing = native_timing_info(lib)
            final_readback_done = True
            frame_metrics.append(
                {
                    "frame": "final",
                    "kind": "sanity_readback",
                    "elapsed_ms": f"{final_elapsed_ms:.6f}",
                    "max_speed_mps": f"{max_speed:.9f}",
                    "non_finite_values": non_finite,
                }
            )

        final_flow_path = resolve_artifact_path(args.dump_final_flow, args.output_dir, "flow_final.npz")
        if final_flow_path and final_readback_done:
            write_flow_npz(
                final_flow_path,
                out_flow,
                nx,
                ny,
                nz,
                {
                    "schema_version": 1,
                    "kind": "flow_final",
                    "grid": {"nx": nx, "ny": ny, "nz": nz, "channels": AERO_SOLVER_FLOW_CHANNELS},
                    "physics": {
                        "dx_m": args.dx,
                        "dt_s": args.dt,
                        "inlet_vx_mps": args.velocity,
                        "steps_per_frame": args.steps_per_frame,
                        "frames": args.frames,
                        "warmup_frames": args.warmup,
                        "simulated_seconds": simulated_seconds,
                    },
                    "readback": {
                        "mode": "sanity_final_after_no_readback" if args.no_readback else "last_measured_frame",
                    },
                    "runtime": runtime,
                    "channel_layout": ["vx", "vy", "vz", "scalar"],
                },
            )
            artifact_paths["final_flow_npz"] = str(final_flow_path)
            print(f"[bench] wrote_final_flow={final_flow_path}")

        avg_ms = sum(times_ms) / len(times_ms)
        mlups = cells * args.steps_per_frame / (avg_ms * 1000.0)
        memory_info = native_memory_info(lib)
        print("[bench] result")
        print(f"  avg_ms_per_frame={avg_ms:.3f}")
        print(f"  min_ms={min(times_ms):.3f}")
        print(f"  p50_ms={percentile(times_ms, 0.50):.3f}")
        print(f"  p95_ms={percentile(times_ms, 0.95):.3f}")
        print(f"  max_ms={max(times_ms):.3f}")
        print(f"  avg_lbm_steps_per_second={args.steps_per_frame * 1000.0 / avg_ms:.2f}")
        print(f"  avg_mlups={mlups:.2f}")
        print(f"  max_speed_scanned_mps={max_speed:.6f}")
        print(f"  non_finite_values_scanned={non_finite}")
        if final_readback_done:
            print(f"  first_cell=({out_flow[0]:.6f}, {out_flow[1]:.6f}, {out_flow[2]:.6f}, {out_flow[3]:.6f})")
        else:
            print("  first_cell=unavailable (final readback skipped)")
        print(f"  native_timing={measured_native_timing}")
        print(f"  native_memory={memory_info}")
        if args.no_readback and not args.skip_final_readback:
            print(f"  native_timing_after_final_readback={final_native_timing}")

        if args.output_dir:
            args.output_dir.mkdir(parents=True, exist_ok=True)
            summary = {
                "avg_ms_per_frame": avg_ms,
                "min_ms": min(times_ms),
                "p50_ms": percentile(times_ms, 0.50),
                "p95_ms": percentile(times_ms, 0.95),
                "max_ms": max(times_ms),
                "avg_lbm_steps_per_second": args.steps_per_frame * 1000.0 / avg_ms,
                "avg_mlups": mlups,
                "max_speed_scanned_mps": max_speed,
                "non_finite_values_scanned": non_finite,
                "final_readback_done": final_readback_done,
                "first_cell": [float(out_flow[i]) for i in range(4)] if final_readback_done else None,
                "native_timing": measured_native_timing,
                "native_timing_after_final_readback": final_native_timing if final_readback_done else None,
                "native_memory": memory_info,
            }
            manifest = {
                "schema_version": 1,
                "run_label": args.run_label,
                "scenario": args.scenario,
                "scenario_description": preset.get("description", ""),
                "argv": sys.argv,
                "dll": str(args.dll),
                "runtime": runtime,
                "artifacts": artifact_paths,
                "grid": {"nx": nx, "ny": ny, "nz": nz, "cells": cells},
                "physics": {
                    "dx_m": args.dx,
                    "dt_s": args.dt,
                    "inlet_vx_mps": args.velocity,
                    "simulated_seconds": simulated_seconds,
                    "steps_per_frame": args.steps_per_frame,
                    "frames": args.frames,
                    "warmup_frames": args.warmup,
                },
                "obstacle": {
                    "type": obstacle_type,
                    "radius_ratio": args.obstacle_radius_ratio,
                    "raw_solid_cells": raw_solid_count,
                    "solid_cells": solid_count,
                    "solid_ratio": solid_ratio,
                    "solid_dilate": args.solid_dilate,
                    "mesh": mesh_metadata,
                },
                "readback": {
                    "mode": "none-per-frame" if args.no_readback else "full-field-per-frame",
                    "scan_output": args.scan_output,
                    "scan_values": scan_values,
                    "skip_final_readback": args.skip_final_readback,
                    "async_enqueue_only": args.async_enqueue_only,
                    "dump_final_flow": str(final_flow_path) if final_flow_path else "",
                    "dump_flow_frames": str(flow_frames_dir) if flow_frames_dir else "",
                    "flow_frame_every": args.flow_frame_every,
                },
                "platform": {
                    "system": platform.system(),
                    "release": platform.release(),
                    "machine": platform.machine(),
                    "processor": platform.processor(),
                    "python": platform.python_version(),
                },
                "environment": {
                    "AERO_LBM_D3Q27_FP16_INPLACE": os.environ.get("AERO_LBM_D3Q27_FP16_INPLACE", ""),
                    "AERO_LBM_COMPACT_REALTIME": os.environ.get("AERO_LBM_COMPACT_REALTIME", ""),
                    "AERO_LBM_CPU_ONLY": os.environ.get("AERO_LBM_CPU_ONLY", ""),
                    "POCL_CACHE_DIR": os.environ.get("POCL_CACHE_DIR", ""),
                    "TMPDIR": os.environ.get("TMPDIR", ""),
                },
                "summary": summary,
            }
            write_manifest(args.output_dir / "manifest.json", manifest)
            write_metrics_csv(args.output_dir / "metrics.csv", frame_metrics)
            print(f"[bench] wrote_manifest={args.output_dir / 'manifest.json'}")
            print(f"[bench] wrote_metrics={args.output_dir / 'metrics.csv'}")
    finally:
        lib.aero_solver_destroy(handle.value)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
