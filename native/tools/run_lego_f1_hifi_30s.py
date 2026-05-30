#!/usr/bin/env python3
"""Run the high-fidelity LEGO F1 wind tunnel and post-process Q frames in place.

This server runner is intended for long GPU runs where downloading raw velocity
fields is impractical. It reads each output frame back once, computes
Q-criterion on the server, writes selected Q/render artifacts, and discards the
raw flow field by default.
"""

from __future__ import annotations

import argparse
import csv
import ctypes
import ctypes.util
import gc
import json
import math
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path
from time import perf_counter

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import benchmark_solver_dll as bench  # noqa: E402
import postprocess_q_criterion as qpost  # noqa: E402


PRESETS = {
    "h0": {
        "nx": 512,
        "ny": 192,
        "nz": 128,
        "description": "H0 formal wind-tunnel target, about 128 car-length cells.",
    },
    "standard": {
        "nx": 512,
        "ny": 192,
        "nz": 256,
        "description": "Deeper-wake standard target; roughly double H0 memory and time.",
    },
    "h1": {
        "nx": 768,
        "ny": 288,
        "nz": 192,
        "description": "High-memory presentation target.",
    },
    "h2": {
        "nx": 1024,
        "ny": 384,
        "nz": 256,
        "description": "Dense 32GB-class target; use only after h1 is stable.",
    },
    "h3": {
        "nx": 1280,
        "ny": 480,
        "nz": 320,
        "description": "Dense 80GB-class target with about 320 car-length cells.",
    },
}

DEFAULT_MESH_FIT_BOX = (0.10, 0.35, 0.37, 0.63, 0.12, 0.34)
DEFAULT_AXIS_MAP = ("y", "x", "z")
DEFAULT_CHANNEL_LAYOUT = ["vx", "vy", "vz", "scalar"]


def parse_q_outputs(values: list[str] | None) -> set[str]:
    if not values:
        return {"preview"}
    outputs: set[str] = set()
    for value in values:
        for part in value.split(","):
            item = part.strip().lower()
            if not item:
                continue
            if item == "all":
                outputs.update({"preview", "npz", "vti", "render"})
            else:
                outputs.add(item)
    invalid = outputs - {"preview", "npz", "vti", "render"}
    if invalid:
        raise argparse.ArgumentTypeError(f"invalid q output(s): {', '.join(sorted(invalid))}")
    return outputs


def library_available(name: str) -> bool:
    if ctypes.util.find_library(name):
        return True
    library_names = (f"lib{name}.so", f"lib{name}.so.1")
    for directory in os.environ.get("LD_LIBRARY_PATH", "").split(os.pathsep):
        if not directory:
            continue
        base = Path(directory)
        if any((base / library_name).exists() for library_name in library_names):
            return True
    return False


def render_backend_status() -> tuple[bool, str]:
    if platform.system() != "Linux":
        return True, "non-Linux platform"
    if os.environ.get("DISPLAY"):
        return True, "DISPLAY is set"
    if library_available("EGL"):
        return True, "libEGL is available"
    if library_available("OSMesa"):
        return True, "libOSMesa is available"
    return False, "no DISPLAY and neither libEGL nor libOSMesa is available"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Server-side 30 m/s LEGO F1 wind-tunnel runner with streamed Q-criterion output"
    )
    parser.add_argument("--dll", type=Path, default=Path("fabric-mod/native/build/libaero_lbm.so"))
    parser.add_argument("--mesh", type=Path, default=Path("experiments/assets/lego_f1.stl"))
    parser.add_argument("--output-dir", type=Path, default=Path("outputs/a4mc_lego_f1_30ms_30s_h0"))
    parser.add_argument("--preset", choices=sorted(PRESETS), default="h0")
    parser.add_argument("--nx", type=int, default=None)
    parser.add_argument("--ny", type=int, default=None)
    parser.add_argument("--nz", type=int, default=None)
    parser.add_argument("--mesh-fit-box", type=bench.parse_fit_box, default=DEFAULT_MESH_FIT_BOX)
    parser.add_argument("--mesh-axis-map", type=bench.parse_axis_map, default=DEFAULT_AXIS_MAP)
    parser.add_argument("--solid-dilate", type=int, default=1)
    parser.add_argument("--physical-car-length", type=float, default=0.12, help="Physical car length in meters")
    parser.add_argument("--air-nu", type=float, default=1.5e-5, help="Air kinematic viscosity in m^2/s")
    parser.add_argument("--velocity", type=float, default=30.0, help="Inlet velocity in m/s")
    parser.add_argument("--seconds", type=float, default=30.0, help="Physical output duration after warmup")
    parser.add_argument(
        "--video-seconds",
        type=float,
        default=None,
        help="Playback duration used to choose frame count. Defaults to --seconds for real-time playback.",
    )
    parser.add_argument("--fps", type=float, default=60.0)
    parser.add_argument("--target-lattice-velocity", type=float, default=0.05)
    parser.add_argument("--dx", type=float, default=None, help="Override cell size in meters")
    parser.add_argument("--dt", type=float, default=None, help="Override solver time step in seconds")
    parser.add_argument("--steps-per-frame", type=int, default=None)
    parser.add_argument("--frames", type=int, default=None)
    parser.add_argument("--warmup-seconds", type=float, default=0.02)
    parser.add_argument(
        "--warmup-mode",
        choices=("readback", "advance", "none"),
        default="readback",
        help="Warmup synchronization mode. readback is more robust on NVIDIA OpenCL; advance is faster but uses clFinish.",
    )
    parser.add_argument(
        "--warmup-chunk-steps",
        type=int,
        default=2048,
        help="Maximum warmup steps submitted in one native call. Lower values are safer for large h2/h3 runs.",
    )
    parser.add_argument("--density", type=float, default=1.225)
    parser.add_argument("--q-output", action="append", default=None, help="preview,npz,vti,render,all. Default: preview")
    parser.add_argument("--q-frame-every", type=int, default=1)
    parser.add_argument(
        "--readback-stride",
        type=int,
        default=1,
        help=(
            "Read back every Nth cell for Q/render frames. The solver still runs at full grid resolution; "
            "use 2 or 3 for h3 when full-field readback is too large."
        ),
    )
    parser.add_argument("--q-threshold", type=float, default=None, help="Fixed Q threshold for rendering")
    parser.add_argument("--q-threshold-percentile", type=float, default=95.0)
    parser.add_argument(
        "--q-threshold-auto",
        action="store_true",
        help="Estimate a positive-Q percentile from each rendered frame instead of using --q-threshold-percentile",
    )
    parser.add_argument("--q-threshold-auto-min-percentile", type=float, default=98.0)
    parser.add_argument("--q-threshold-auto-max-percentile", type=float, default=99.3)
    parser.add_argument("--q-threshold-auto-sample-values", type=int, default=2_000_000)
    parser.add_argument(
        "--q-threshold-mode",
        choices=("first", "frame"),
        default="first",
        help="Use first output frame's percentile as a fixed threshold, or recompute per frame.",
    )
    parser.add_argument("--render-cmap", default="viridis", help="PyVista colormap for Q isosurface rendering")
    parser.add_argument(
        "--render-color-by",
        choices=("speed", "q"),
        default="speed",
        help="Scalar used to color the Q isosurface. speed gives a real viridis gradient; q is mostly constant.",
    )
    parser.add_argument(
        "--render-camera-distance",
        type=float,
        default=1.15,
        help="Camera distance multiplier relative to the domain diagonal. Smaller is closer.",
    )
    parser.add_argument(
        "--render-camera-view",
        choices=("front-left", "front-right", "rear-left", "rear-right", "side-left", "side-right"),
        default="front-left",
        help="Fixed camera view. front-* looks from the inlet/front side.",
    )
    parser.add_argument(
        "--render-camera-focus",
        choices=("domain", "solid", "solid-wake"),
        default="solid",
        help="Framing target for the fixed camera.",
    )
    parser.add_argument("--render-width", type=int, default=1920, help="Rendered PNG frame width in pixels")
    parser.add_argument("--render-height", type=int, default=1080, help="Rendered PNG frame height in pixels")
    parser.add_argument(
        "--render-solid-opacity",
        type=float,
        default=1.0,
        help="Opacity for the voxelized car surface.",
    )
    parser.add_argument("--include-speed", action="store_true", help="Include speed in per-frame Q NPZ files")
    parser.add_argument("--save-raw-flow", action="store_true", help="Also write raw flow_XXXXXX.npz files. Very large.")
    parser.add_argument("--scan-output", action="store_true", help="Scan full output field for NaN/max speed each frame")
    parser.add_argument("--require-runtime-substring", default="cumulant-d3q27")
    parser.add_argument(
        "--allow-cpu-fallback",
        action="store_true",
        help="Allow native CPU fallback if OpenCL fails. Do not use for large h1/h2/h3 runs.",
    )
    parser.add_argument("--respect-env", action="store_true", help="Do not force the classic OpenCL solver env vars")
    parser.add_argument("--encode-video", action="store_true", help="Encode PNG frames into MP4 with ffmpeg after the run")
    parser.add_argument("--video-source", choices=("preview", "render"), default="render")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--dry-run", action="store_true", help="Compute settings and write preflight artifacts, but do not run")
    return parser.parse_args()


def selected_grid(args: argparse.Namespace) -> tuple[int, int, int, str]:
    preset = PRESETS[args.preset]
    nx = args.nx if args.nx is not None else preset["nx"]
    ny = args.ny if args.ny is not None else preset["ny"]
    nz = args.nz if args.nz is not None else preset["nz"]
    if min(nx, ny, nz) <= 0:
        raise SystemExit("grid dimensions must be positive")
    return nx, ny, nz, preset["description"]


def ceil_div(value: int, divisor: int) -> int:
    return (value + divisor - 1) // divisor


def grid_extent_x(metadata: dict[str, object]) -> float:
    bounds = metadata.get("grid_bounds")
    if not isinstance(bounds, dict):
        raise ValueError("mesh metadata does not contain grid_bounds")
    mins = bounds.get("min")
    maxs = bounds.get("max")
    if not isinstance(mins, list) or not isinstance(maxs, list) or len(mins) < 1 or len(maxs) < 1:
        raise ValueError("mesh metadata contains invalid grid_bounds")
    return max(float(maxs[0]) - float(mins[0]), 1.0)


def compute_timing(
    args: argparse.Namespace,
    car_cells: float,
) -> dict[str, float | int]:
    dx = args.dx if args.dx is not None else args.physical_car_length / car_cells
    dt = args.dt if args.dt is not None else args.target_lattice_velocity * dx / args.velocity
    playback_seconds = args.video_seconds if args.video_seconds is not None else args.seconds
    frames = args.frames if args.frames is not None else max(1, int(round(playback_seconds * args.fps)))
    steps_per_frame = args.steps_per_frame
    if steps_per_frame is None:
        frame_dt = args.seconds / max(frames, 1)
        steps_per_frame = max(1, int(round(frame_dt / dt)))
    simulated_seconds = frames * steps_per_frame * dt
    warmup_steps = max(0, int(round(args.warmup_seconds / dt)))
    lattice_velocity = args.velocity * dt / dx
    lattice_nu = args.air_nu * dt / (dx * dx)
    car_re = args.velocity * args.physical_car_length / args.air_nu
    cell_re = lattice_velocity / max(lattice_nu, 1.0e-30)
    mach_lattice = lattice_velocity / math.sqrt(1.0 / 3.0)
    return {
        "dx": dx,
        "dt": dt,
        "frames": frames,
        "steps_per_frame": steps_per_frame,
        "total_steps": frames * steps_per_frame,
        "simulated_seconds": simulated_seconds,
        "video_seconds": playback_seconds,
        "playback_slowdown": playback_seconds / simulated_seconds if simulated_seconds > 0.0 else 0.0,
        "warmup_steps": warmup_steps,
        "lattice_velocity": lattice_velocity,
        "lattice_nu": lattice_nu,
        "car_reynolds": car_re,
        "cell_reynolds": cell_re,
        "mach_lattice": mach_lattice,
    }


def write_json(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, default=bench.json_default), encoding="utf-8")


def write_metrics_header(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "frame",
                "sim_time_s",
                "solve_ms",
                "readback_ms",
                "q_ms",
                "write_ms",
                "threshold",
                "threshold_percentile",
                "render_threshold",
                "q_min",
                "q_max",
                "q_positive_cells",
                "max_speed_mps",
                "non_finite_values",
            ],
        )
        writer.writeheader()


def append_metric(path: Path, row: dict[str, object]) -> None:
    with path.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(row.keys()))
        writer.writerow(row)


def positive_threshold(q: np.ndarray, percentile: float) -> float:
    return qpost.positive_threshold(q, percentile)


def auto_positive_threshold(
    q: np.ndarray,
    min_percentile: float,
    max_percentile: float,
    sample_values: int,
) -> tuple[float, float]:
    return qpost.auto_positive_threshold(q, min_percentile, max_percentile, sample_values)


def write_q_npz(
    path: Path,
    q: np.ndarray,
    speed: np.ndarray,
    include_speed: bool,
    metadata: dict[str, object],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload: dict[str, object] = {
        "q_criterion": q,
        "metadata": np.array(json.dumps(metadata, indent=2)),
    }
    if include_speed:
        payload["speed"] = speed
    np.savez_compressed(path, **payload)


def encode_video(ffmpeg: str, frame_dir: Path, output_path: Path, fps: float) -> bool:
    if shutil.which(ffmpeg) is None:
        print(f"[server] ffmpeg not found: {ffmpeg}")
        return False
    pattern = frame_dir / "q_%06d.png"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        ffmpeg,
        "-y",
        "-framerate",
        f"{fps:g}",
        "-i",
        str(pattern),
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        str(output_path),
    ]
    print("[server] encoding_video=" + " ".join(cmd))
    completed = subprocess.run(cmd, check=False)
    return completed.returncode == 0


def print_estimate(
    nx: int,
    ny: int,
    nz: int,
    readback_nx: int,
    readback_ny: int,
    readback_nz: int,
    readback_stride: int,
    velocity_mps: float,
    timing: dict[str, float | int],
    q_outputs: set[str],
) -> None:
    cells = nx * ny * nz
    readback_cells = readback_nx * readback_ny * readback_nz
    raw_flow_bytes = readback_cells * bench.AERO_SOLVER_FLOW_CHANNELS * 4
    q_bytes = readback_cells * 4
    estimated_solver_bytes = cells * 288
    frames = int(timing["frames"])
    print(f"[server] grid={nx}x{ny}x{nz} cells={cells:,}")
    if readback_stride > 1:
        print(
            f"[server] q_readback_grid={readback_nx}x{readback_ny}x{readback_nz} "
            f"cells={readback_cells:,} stride={readback_stride}"
        )
    print(f"[server] dx={timing['dx']:.9g}m dt={timing['dt']:.9g}s")
    print(
        f"[server] velocity={velocity_mps:g}m/s lattice_u={timing['lattice_velocity']:.6g} "
        f"lattice_nu={timing['lattice_nu']:.6g} mach_lattice={timing['mach_lattice']:.6g}"
    )
    print(
        f"[server] car_Re={timing['car_reynolds']:.3g} "
        f"steps_per_frame={timing['steps_per_frame']} frames={frames} "
        f"simulated_seconds={timing['simulated_seconds']:.9g}"
    )
    print(f"[server] raw_flow_per_q_readback={raw_flow_bytes / (1024 ** 2):.1f} MiB")
    print(f"[server] q_scalar_per_q_frame={q_bytes / (1024 ** 2):.1f} MiB before compression")
    print(f"[server] estimated_classic_solver_memory={estimated_solver_bytes / (1024 ** 3):.1f} GiB")
    if "npz" in q_outputs:
        print("[server] warning: per-frame Q NPZ output can still be very large over 30s")
    if timing["lattice_nu"] < 1.0e-4:
        print("[server] warning: lattice_nu is very low; this is a high-Re visual run, not a strict V&V case")


def main() -> int:
    args = parse_args()
    q_outputs = parse_q_outputs(args.q_output)
    render_disabled_reason = ""
    if "render" in q_outputs:
        render_available, render_reason = render_backend_status()
        if render_available:
            os.environ.setdefault("PYVISTA_OFF_SCREEN", "true")
        else:
            render_disabled_reason = render_reason
            q_outputs.discard("render")
            if not q_outputs:
                q_outputs.add("preview")
            if args.video_source == "render" and "preview" in q_outputs:
                args.video_source = "preview"
            print(
                "[server] warning: render output disabled: "
                f"{render_reason}. Install EGL/libOSMesa or run under Xvfb to enable PyVista renders."
            )
    if args.readback_stride <= 0:
        raise SystemExit("readback-stride must be positive")
    if args.q_frame_every <= 0:
        raise SystemExit("q-frame-every must be positive")
    if args.solid_dilate < 0:
        raise SystemExit("solid-dilate must be non-negative")
    if args.render_width <= 0 or args.render_height <= 0:
        raise SystemExit("render-width/render-height must be positive")
    if args.warmup_chunk_steps <= 0:
        raise SystemExit("warmup-chunk-steps must be positive")
    if args.q_threshold_percentile <= 0.0 or args.q_threshold_percentile > 100.0:
        raise SystemExit("q-threshold-percentile must be in (0, 100]")
    if args.q_threshold_auto and args.q_threshold is not None:
        print("[server] warning: --q-threshold overrides --q-threshold-auto")
    if args.q_threshold_auto and not (0.0 < args.q_threshold_auto_min_percentile < args.q_threshold_auto_max_percentile <= 100.0):
        raise SystemExit("q-threshold-auto percentiles must satisfy 0 < min < max <= 100")
    if args.q_threshold_auto_sample_values < 0:
        raise SystemExit("q-threshold-auto-sample-values must be non-negative")
    if args.encode_video and args.video_source not in q_outputs:
        print(f"[server] warning: video source {args.video_source!r} is not in q outputs; video encode may have no frames")

    if not args.respect_env:
        os.environ["AERO_LBM_COMPACT_REALTIME"] = "0"
        os.environ["AERO_LBM_D3Q27_FP16_INPLACE"] = "0"
        os.environ["AERO_LBM_REQUIRE_OPENCL"] = "0" if args.allow_cpu_fallback else "1"
        if args.allow_cpu_fallback:
            os.environ["AERO_LBM_CPU_ONLY"] = "0"
        else:
            os.environ.pop("AERO_LBM_CPU_ONLY", None)

    nx, ny, nz, preset_description = selected_grid(args)
    cells = nx * ny * nz
    readback_nx = ceil_div(nx, args.readback_stride)
    readback_ny = ceil_div(ny, args.readback_stride)
    readback_nz = ceil_div(nz, args.readback_stride)
    readback_cells = readback_nx * readback_ny * readback_nz
    full_value_count = cells * bench.AERO_SOLVER_FLOW_CHANNELS
    readback_value_count = readback_cells * bench.AERO_SOLVER_FLOW_CHANNELS

    args.output_dir.mkdir(parents=True, exist_ok=True)
    solid_mask_path = args.output_dir / "solid_mask.npz"
    mask_slices_path = args.output_dir / "mask_slices.png"
    metrics_path = args.output_dir / "metrics.csv"
    manifest_path = args.output_dir / "manifest.json"
    preview_dir = args.output_dir / "q_preview_frames"
    q_npz_dir = args.output_dir / "q_npz_frames"
    q_vti_dir = args.output_dir / "q_vti_frames"
    render_dir = args.output_dir / "q_render_frames"
    raw_flow_dir = args.output_dir / "raw_flow_frames"

    print(f"[server] voxelizing_mesh={args.mesh}")
    solid, mesh_metadata = bench.build_mesh_mask(args.mesh, nx, ny, nz, args.mesh_fit_box, args.mesh_axis_map)
    raw_solid_count = sum(int(value) for value in solid)
    if args.solid_dilate > 0:
        solid = bench.dilate_solid_mask(solid, nx, ny, nz, args.solid_dilate)
    solid_count = sum(int(value) for value in solid)
    solid_ratio = solid_count / cells if cells else 0.0
    solid_np_full = np.ctypeslib.as_array(solid).reshape((nx, ny, nz)).copy()
    solid_np = np.ascontiguousarray(
        solid_np_full[:: args.readback_stride, :: args.readback_stride, :: args.readback_stride]
    )
    car_cells = grid_extent_x(mesh_metadata)
    timing = compute_timing(args, car_cells)
    print_estimate(
        nx,
        ny,
        nz,
        readback_nx,
        readback_ny,
        readback_nz,
        args.readback_stride,
        args.velocity,
        timing,
        q_outputs,
    )

    solid_metadata = {
        "schema_version": 1,
        "grid": {"nx": nx, "ny": ny, "nz": nz, "cells": cells},
        "obstacle": {
            "type": "mesh",
            "raw_solid_cells": raw_solid_count,
            "solid_cells": solid_count,
            "solid_ratio": solid_ratio,
            "solid_dilate": args.solid_dilate,
            "mesh": mesh_metadata,
        },
    }
    bench.write_solid_mask_npz(solid_mask_path, solid, nx, ny, nz, solid_metadata)
    bench.write_solid_mask_slices_png(mask_slices_path, solid, nx, ny, nz)

    manifest: dict[str, object] = {
        "schema_version": 1,
        "kind": "lego_f1_hifi_streamed_q",
        "preset": args.preset,
        "preset_description": preset_description,
        "argv": sys.argv,
        "dll": str(args.dll),
        "mesh": str(args.mesh),
        "grid": {"nx": nx, "ny": ny, "nz": nz, "cells": cells},
        "readback_grid": {
            "nx": readback_nx,
            "ny": readback_ny,
            "nz": readback_nz,
            "cells": readback_cells,
            "stride": args.readback_stride,
        },
        "physics": {
            "physical_car_length_m": args.physical_car_length,
            "car_length_cells": car_cells,
            "air_nu_m2s": args.air_nu,
            "density_kgm3": args.density,
            "inlet_vx_mps": args.velocity,
            **timing,
            "warmup_mode": args.warmup_mode,
            "warmup_chunk_steps": args.warmup_chunk_steps,
        },
        "q": {
            "outputs": sorted(q_outputs),
            "render_disabled_reason": render_disabled_reason,
            "frame_every": args.q_frame_every,
            "threshold": args.q_threshold,
            "threshold_percentile": args.q_threshold_percentile,
            "threshold_auto": args.q_threshold_auto,
            "threshold_auto_min_percentile": args.q_threshold_auto_min_percentile,
            "threshold_auto_max_percentile": args.q_threshold_auto_max_percentile,
            "threshold_auto_sample_values": args.q_threshold_auto_sample_values,
            "threshold_mode": args.q_threshold_mode,
            "readback_stride": args.readback_stride,
            "dx_m": float(timing["dx"]) * args.readback_stride,
            "render_cmap": args.render_cmap,
            "render_color_by": args.render_color_by,
            "render_camera_distance": args.render_camera_distance,
            "render_camera_view": args.render_camera_view,
            "render_camera_focus": args.render_camera_focus,
            "render_width": args.render_width,
            "render_height": args.render_height,
            "render_solid_opacity": args.render_solid_opacity,
            "include_speed": args.include_speed,
        },
        "obstacle": solid_metadata["obstacle"],
        "artifacts": {
            "solid_mask_npz": str(solid_mask_path),
            "mask_slices_png": str(mask_slices_path),
            "metrics_csv": str(metrics_path),
            "preview_frames_dir": str(preview_dir) if "preview" in q_outputs else "",
            "q_npz_frames_dir": str(q_npz_dir) if "npz" in q_outputs else "",
            "q_vti_frames_dir": str(q_vti_dir) if "vti" in q_outputs else "",
            "render_frames_dir": str(render_dir) if "render" in q_outputs else "",
            "raw_flow_frames_dir": str(raw_flow_dir) if args.save_raw_flow else "",
        },
        "environment": {
            "AERO_LBM_COMPACT_REALTIME": os.environ.get("AERO_LBM_COMPACT_REALTIME", ""),
            "AERO_LBM_D3Q27_FP16_INPLACE": os.environ.get("AERO_LBM_D3Q27_FP16_INPLACE", ""),
            "AERO_LBM_REQUIRE_OPENCL": os.environ.get("AERO_LBM_REQUIRE_OPENCL", ""),
            "AERO_LBM_READBACK_CHUNK_MB": os.environ.get("AERO_LBM_READBACK_CHUNK_MB", ""),
            "AERO_LBM_CPU_ONLY": os.environ.get("AERO_LBM_CPU_ONLY", ""),
        },
        "platform": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
            "processor": platform.processor(),
            "python": platform.python_version(),
        },
    }
    write_json(manifest_path, manifest)

    if args.dry_run:
        print(f"[server] dry_run wrote {manifest_path}")
        return 0

    output_dirs = {
        "preview": preview_dir,
        "npz": q_npz_dir,
        "vti": q_vti_dir,
        "render": render_dir,
    }
    for output_name, directory in output_dirs.items():
        if output_name in q_outputs:
            directory.mkdir(parents=True, exist_ok=True)
    if args.save_raw_flow:
        raw_flow_dir.mkdir(parents=True, exist_ok=True)
    write_metrics_header(metrics_path)

    lib = bench.load_library(args.dll)
    bench.configure_api(lib)
    handle = ctypes.c_longlong()
    if not lib.aero_solver_create(nx, ny, nz, ctypes.c_float(timing["dx"]), ctypes.c_float(timing["dt"]), ctypes.byref(handle)):
        raise SystemExit(f"create failed: {bench.native_error(lib)}")

    fixed_threshold = args.q_threshold
    fixed_threshold_percentile: float | None = None
    times_solve: list[float] = []
    times_readback: list[float] = []
    times_q: list[float] = []
    times_write: list[float] = []
    try:
        runtime = bench.runtime_info(lib)
        print(f"[server] runtime={runtime}")
        if not args.allow_cpu_fallback and not runtime.startswith("opencl|"):
            raise SystemExit(
                "OpenCL is required but the native runtime is not using it. "
                f"runtime={runtime!r}; AERO_LBM_CPU_ONLY={os.environ.get('AERO_LBM_CPU_ONLY', '')!r}"
            )
        if args.require_runtime_substring and args.require_runtime_substring not in runtime:
            raise SystemExit(f"runtime requirement failed: missing {args.require_runtime_substring!r} in {runtime!r}")
        manifest["runtime"] = runtime
        write_json(manifest_path, manifest)

        if not lib.aero_solver_set_solid_mask(handle.value, solid, cells):
            raise SystemExit(f"set_solid_mask failed: {bench.native_error(lib)}")

        boundary = bench.AeroBoundaryDesc(
            bench.AERO_SOLVER_BOUNDARY_WIND_TUNNEL,
            ctypes.c_float(args.velocity),
            ctypes.c_float(0.0),
            ctypes.c_float(0.0),
            ctypes.c_float(0.0),
            ctypes.c_float(args.density),
            ctypes.c_float(args.air_nu),
        )
        out_flow = (ctypes.c_float * readback_value_count)()
        flow_np = np.ctypeslib.as_array(out_flow).reshape(
            (readback_nx, readback_ny, readback_nz, bench.AERO_SOLVER_FLOW_CHANNELS)
        )
        scan_values = readback_value_count if args.scan_output else min(readback_value_count, 4096)

        advance = getattr(lib, "aero_solver_advance_wind_tunnel", None)
        finish = getattr(lib, "aero_solver_finish", None)
        extract_flow_atlas = getattr(lib, "aero_solver_extract_flow_atlas", None)
        use_atlas_readback = args.readback_stride > 1
        if use_atlas_readback and (advance is None or extract_flow_atlas is None):
            raise SystemExit("readback-stride > 1 requires aero_solver_advance_wind_tunnel and aero_solver_extract_flow_atlas")
        if use_atlas_readback and args.warmup_mode == "readback":
            print("[server] readback-stride > 1: using advance-only warmup; the next atlas readback synchronizes the queue")
        warmup_steps = 0 if args.warmup_mode == "none" else int(timing["warmup_steps"])
        if warmup_steps > 0:
            print(
                f"[server] warmup_steps={warmup_steps} warmup_mode={args.warmup_mode} "
                f"warmup_chunk_steps={args.warmup_chunk_steps}"
            )
            remaining_warmup = warmup_steps
            warmup_chunk_index = 0
            while remaining_warmup > 0:
                warmup_chunk_index += 1
                chunk_steps = min(remaining_warmup, args.warmup_chunk_steps)
                if use_atlas_readback and advance is not None:
                    if not advance(handle.value, ctypes.byref(boundary), chunk_steps):
                        raise SystemExit(
                            f"warmup advance failed at chunk {warmup_chunk_index}: {bench.native_error(lib)}"
                        )
                elif args.warmup_mode == "advance" and advance is not None and finish is not None:
                    if not advance(handle.value, ctypes.byref(boundary), chunk_steps):
                        raise SystemExit(
                            f"warmup advance failed at chunk {warmup_chunk_index}: {bench.native_error(lib)}"
                        )
                    if not finish():
                        raise SystemExit(
                            f"warmup finish failed at chunk {warmup_chunk_index}: {bench.native_error(lib)}"
                        )
                else:
                    if args.readback_stride != 1:
                        raise SystemExit("full-field warmup readback requires --readback-stride 1")
                    if not lib.aero_solver_step_wind_tunnel(
                        handle.value, ctypes.byref(boundary), chunk_steps, out_flow, full_value_count
                    ):
                        raise SystemExit(
                            f"warmup step failed at chunk {warmup_chunk_index}: {bench.native_error(lib)}"
                        )
                remaining_warmup -= chunk_steps
                if warmup_chunk_index == 1 or remaining_warmup == 0 or warmup_chunk_index % 10 == 0:
                    print(
                        f"[server] warmup_chunk={warmup_chunk_index} "
                        f"done_steps={warmup_steps - remaining_warmup}/{warmup_steps}"
                    )

        bench.reset_native_timing(lib)
        frames = int(timing["frames"])
        steps_per_frame = int(timing["steps_per_frame"])
        print(f"[server] measured_frames={frames} steps_per_frame={steps_per_frame}")

        for frame in range(frames):
            needs_readback = frame % args.q_frame_every == 0
            readback_ms = 0.0
            solve_start = perf_counter()
            if use_atlas_readback:
                assert advance is not None
                assert extract_flow_atlas is not None
                if not advance(handle.value, ctypes.byref(boundary), steps_per_frame):
                    raise SystemExit(f"frame {frame} advance failed: {bench.native_error(lib)}")
                solve_ms = (perf_counter() - solve_start) * 1000.0
                if needs_readback:
                    readback_start = perf_counter()
                    if not extract_flow_atlas(
                        handle.value,
                        args.readback_stride,
                        out_flow,
                        readback_value_count,
                    ):
                        raise SystemExit(f"frame {frame} atlas readback failed: {bench.native_error(lib)}")
                    readback_ms = (perf_counter() - readback_start) * 1000.0
                    times_readback.append(readback_ms)
            else:
                ok = lib.aero_solver_step_wind_tunnel(
                    handle.value,
                    ctypes.byref(boundary),
                    steps_per_frame,
                    out_flow,
                    full_value_count,
                )
                solve_ms = (perf_counter() - solve_start) * 1000.0
                if not ok:
                    raise SystemExit(f"frame {frame} step failed: {bench.native_error(lib)}")
            times_solve.append(solve_ms)

            if needs_readback or not use_atlas_readback:
                max_speed, non_finite = bench.validate_output(out_flow, scan_values)
            else:
                max_speed, non_finite = 0.0, 0
            q_ms = 0.0
            write_ms = 0.0
            threshold = fixed_threshold if fixed_threshold is not None else 0.0
            selected_threshold_percentile = (
                fixed_threshold_percentile
                if fixed_threshold_percentile is not None
                else 0.0 if args.q_threshold is not None else args.q_threshold_percentile
            )
            render_threshold = 0.0
            q_min = q_max = 0.0
            q_positive = 0

            if needs_readback:
                q_start = perf_counter()
                velocity = np.ascontiguousarray(flow_np[..., :3], dtype=np.float32)
                q_dx = float(timing["dx"]) * args.readback_stride
                q, speed = qpost.compute_q_criterion(velocity, q_dx, solid_np)
                q_ms = (perf_counter() - q_start) * 1000.0
                times_q.append(q_ms)

                if fixed_threshold is None or args.q_threshold_mode == "frame":
                    if args.q_threshold_auto and args.q_threshold is None:
                        threshold, selected_threshold_percentile = auto_positive_threshold(
                            q,
                            args.q_threshold_auto_min_percentile,
                            args.q_threshold_auto_max_percentile,
                            args.q_threshold_auto_sample_values,
                        )
                        print(
                            f"[server] auto q threshold frame={frame}: "
                            f"percentile={selected_threshold_percentile:.4g} threshold={threshold:.9g}"
                        )
                    else:
                        threshold = positive_threshold(q, args.q_threshold_percentile)
                        selected_threshold_percentile = args.q_threshold_percentile
                    if args.q_threshold_mode == "first":
                        fixed_threshold = threshold
                        fixed_threshold_percentile = selected_threshold_percentile
                q_min = float(np.nanmin(q))
                q_max = float(np.nanmax(q))
                q_positive = int(np.count_nonzero(q > 0.0))

                frame_metadata = {
                    "schema_version": 1,
                    "frame": frame,
                    "sim_time_s": (frame + 1) * steps_per_frame * float(timing["dt"]),
                    "source": "streamed_native_atlas_readback" if use_atlas_readback else "streamed_native_readback",
                    "dx_m": q_dx,
                    "physics": {"dx_m": q_dx},
                    "solver_grid": {"nx": nx, "ny": ny, "nz": nz, "cells": cells},
                    "readback_grid": {
                        "nx": readback_nx,
                        "ny": readback_ny,
                        "nz": readback_nz,
                        "cells": readback_cells,
                        "stride": args.readback_stride,
                    },
                    "threshold": threshold,
                    "threshold_percentile": selected_threshold_percentile,
                    "threshold_mode": "auto" if args.q_threshold_auto and args.q_threshold is None else args.q_threshold_mode,
                    "channel_layout": DEFAULT_CHANNEL_LAYOUT,
                }
                write_start = perf_counter()
                if args.save_raw_flow:
                    bench.write_flow_npz(
                        raw_flow_dir / f"flow_{frame:06d}.npz",
                        out_flow,
                        readback_nx,
                        readback_ny,
                        readback_nz,
                        frame_metadata,
                    )
                if "npz" in q_outputs:
                    write_q_npz(q_npz_dir / f"q_{frame:06d}.npz", q, speed, args.include_speed, frame_metadata)
                if "vti" in q_outputs:
                    qpost.write_vti(q_vti_dir / f"q_{frame:06d}.vti", q, speed, q_dx)
                if "preview" in q_outputs:
                    qpost.write_preview_png(preview_dir / f"q_{frame:06d}.png", q, solid_np, threshold)
                if "render" in q_outputs:
                    try:
                        rendered, render_threshold = qpost.render_q_isosurface_result(
                            render_dir / f"q_{frame:06d}.png",
                            q,
                            solid_np,
                            q_dx,
                            threshold,
                            speed=speed,
                            cmap=args.render_cmap,
                            color_by=args.render_color_by,
                            camera_distance=args.render_camera_distance,
                            camera_view=args.render_camera_view,
                            camera_focus=args.render_camera_focus,
                            window_size=(args.render_width, args.render_height),
                            solid_opacity=args.render_solid_opacity,
                        )
                        if not rendered:
                            print(f"[server] warning: empty render isosurface at frame={frame} threshold={threshold:.9g}")
                        elif abs(render_threshold - threshold) > max(abs(threshold) * 1.0e-6, 1.0e-12):
                            print(
                                f"[server] render fallback at frame={frame}: "
                                f"threshold={threshold:.9g} render_threshold={render_threshold:.9g}"
                            )
                    except Exception as exc:
                        render_disabled_reason = str(exc)
                        q_outputs.discard("render")
                        manifest["q"]["outputs"] = sorted(q_outputs)
                        manifest["q"]["render_disabled_reason"] = render_disabled_reason
                        write_json(manifest_path, manifest)
                        print(f"[server] warning: render output disabled after failure: {exc}")
                write_ms = (perf_counter() - write_start) * 1000.0
                times_write.append(write_ms)
                del velocity, q, speed
                if frame % 10 == 0:
                    gc.collect()

            sim_time_s = (frame + 1) * steps_per_frame * float(timing["dt"])
            row = {
                "frame": frame,
                "sim_time_s": f"{sim_time_s:.9f}",
                "solve_ms": f"{solve_ms:.6f}",
                "readback_ms": f"{readback_ms:.6f}",
                "q_ms": f"{q_ms:.6f}",
                "write_ms": f"{write_ms:.6f}",
                "threshold": f"{threshold:.9g}",
                "threshold_percentile": f"{selected_threshold_percentile:.6g}",
                "render_threshold": f"{render_threshold:.9g}",
                "q_min": f"{q_min:.9g}",
                "q_max": f"{q_max:.9g}",
                "q_positive_cells": q_positive,
                "max_speed_mps": f"{max_speed:.9f}",
                "non_finite_values": non_finite,
            }
            append_metric(metrics_path, row)
            if frame == 0 or (frame + 1) % 10 == 0 or frame + 1 == frames:
                print(
                    f"[server] frame={frame + 1}/{frames} sim_t={sim_time_s:.4f}s "
                    f"solve={solve_ms:.1f}ms readback={readback_ms:.1f}ms "
                    f"q={q_ms:.1f}ms write={write_ms:.1f}ms "
                    f"q=({q_min:.3g},{q_max:.3g}) threshold={threshold:.3g}"
                )

        summary = {
            "avg_solve_ms": sum(times_solve) / len(times_solve) if times_solve else 0.0,
            "avg_readback_ms": sum(times_readback) / len(times_readback) if times_readback else 0.0,
            "avg_q_ms": sum(times_q) / len(times_q) if times_q else 0.0,
            "avg_write_ms": sum(times_write) / len(times_write) if times_write else 0.0,
            "native_timing": bench.native_timing_info(lib),
            "native_memory": bench.native_memory_info(lib),
            "fixed_threshold": fixed_threshold,
        }
        manifest["summary"] = summary
        write_json(manifest_path, manifest)
        print(f"[server] summary={json.dumps(summary, indent=2)}")

    finally:
        lib.aero_solver_destroy(handle.value)

    if args.encode_video:
        source_dir = render_dir if args.video_source == "render" else preview_dir
        video_path = args.output_dir / f"q_{args.video_source}_{args.fps:g}fps.mp4"
        video_fps = args.fps / args.q_frame_every
        if encode_video(args.ffmpeg, source_dir, video_path, video_fps):
            print(f"[server] wrote_video={video_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
