#!/usr/bin/env python3
"""Compute and render Q-criterion from benchmark_solver_dll.py flow snapshots."""

from __future__ import annotations

import argparse
import json
import math
import os
import tempfile
import zipfile
from pathlib import Path

import numpy as np


def read_npz_metadata(path: Path) -> dict[str, object]:
    try:
        with zipfile.ZipFile(path, "r") as archive:
            if "metadata.json" not in archive.namelist():
                return {}
            return json.loads(archive.read("metadata.json").decode("utf-8"))
    except (OSError, json.JSONDecodeError, zipfile.BadZipFile):
        return {}


def metadata_dx(metadata: dict[str, object], fallback: float) -> float:
    physics = metadata.get("physics")
    if isinstance(physics, dict):
        value = physics.get("dx_m")
        if isinstance(value, (float, int)) and value > 0:
            return float(value)
    return fallback


def load_velocity(path: Path) -> tuple[np.ndarray, dict[str, object]]:
    metadata = read_npz_metadata(path)
    with np.load(path) as payload:
        if "flow" in payload:
            flow = np.asarray(payload["flow"], dtype=np.float32)
            if flow.ndim != 4 or flow.shape[-1] < 3:
                raise ValueError(f"flow array must have shape (nx, ny, nz, channels>=3): {path}")
            return np.ascontiguousarray(flow[..., :3]), metadata
        if "velocity" in payload:
            velocity = np.asarray(payload["velocity"], dtype=np.float32)
            if velocity.ndim != 4 or velocity.shape[-1] != 3:
                raise ValueError(f"velocity array must have shape (nx, ny, nz, 3): {path}")
            return np.ascontiguousarray(velocity), metadata
        if "velocity_t" in payload:
            velocity = np.asarray(payload["velocity_t"], dtype=np.float32)
            if velocity.ndim != 4 or velocity.shape[-1] != 3:
                raise ValueError(f"velocity_t array must have shape (nx, ny, nz, 3): {path}")
            return np.ascontiguousarray(velocity), metadata
    raise ValueError(f"no flow/velocity array found in {path}")


def load_solid_mask(path: Path | None, shape: tuple[int, int, int]) -> np.ndarray | None:
    if path is None:
        return None
    with np.load(path) as payload:
        if "solid_mask" not in payload:
            raise ValueError(f"solid mask NPZ does not contain solid_mask.npy: {path}")
        mask = np.asarray(payload["solid_mask"], dtype=np.uint8)
    if mask.shape != shape:
        raise ValueError(f"solid mask shape {mask.shape} does not match velocity shape {shape}")
    return mask


def compute_q_criterion(velocity: np.ndarray, dx: float, solid_mask: np.ndarray | None) -> tuple[np.ndarray, np.ndarray]:
    if solid_mask is not None:
        velocity = velocity.copy()
        velocity[solid_mask != 0] = 0.0

    edge_order = 2 if min(velocity.shape[:3]) > 2 else 1
    du_dx, du_dy, du_dz = np.gradient(velocity[..., 0], dx, dx, dx, edge_order=edge_order)
    dv_dx, dv_dy, dv_dz = np.gradient(velocity[..., 1], dx, dx, dx, edge_order=edge_order)
    dw_dx, dw_dy, dw_dz = np.gradient(velocity[..., 2], dx, dx, dx, edge_order=edge_order)

    s11 = du_dx
    s22 = dv_dy
    s33 = dw_dz
    s12 = 0.5 * (du_dy + dv_dx)
    s13 = 0.5 * (du_dz + dw_dx)
    s23 = 0.5 * (dv_dz + dw_dy)

    o12 = 0.5 * (du_dy - dv_dx)
    o13 = 0.5 * (du_dz - dw_dx)
    o23 = 0.5 * (dv_dz - dw_dy)

    strain_norm2 = s11 * s11 + s22 * s22 + s33 * s33 + 2.0 * (s12 * s12 + s13 * s13 + s23 * s23)
    rotation_norm2 = 2.0 * (o12 * o12 + o13 * o13 + o23 * o23)
    q = np.asarray(0.5 * (rotation_norm2 - strain_norm2), dtype=np.float32)
    speed = np.linalg.norm(velocity, axis=-1).astype(np.float32)
    if solid_mask is not None:
        q[solid_mask != 0] = 0.0
        speed[solid_mask != 0] = 0.0
    return q, speed


def positive_threshold(q: np.ndarray, percentile: float) -> float:
    positive = q[np.isfinite(q) & (q > 0.0)]
    if positive.size == 0:
        return 0.0
    return float(np.percentile(positive, percentile))


def sampled_positive_q(q: np.ndarray, sample_values: int) -> np.ndarray:
    flat = q.reshape(-1)
    if sample_values > 0 and flat.size > sample_values:
        stride = max(1, flat.size // sample_values)
        flat = flat[::stride]
    return flat[np.isfinite(flat) & (flat > 0.0)]


def auto_positive_threshold(
    q: np.ndarray,
    min_percentile: float = 98.0,
    max_percentile: float = 99.3,
    sample_values: int = 2_000_000,
) -> tuple[float, float]:
    if not (0.0 < min_percentile < max_percentile <= 100.0):
        raise ValueError("auto threshold percentiles must satisfy 0 < min < max <= 100")

    positive = sampled_positive_q(q, sample_values)
    if positive.size == 0:
        return 0.0, 0.0

    fallback_percentile = min(max(98.8, min_percentile), max_percentile)
    if positive.size < 64:
        return float(np.percentile(positive, fallback_percentile)), float(fallback_percentile)

    percentiles = np.linspace(min_percentile, max_percentile, 96, dtype=np.float64)
    thresholds = np.percentile(positive, percentiles)
    valid = np.isfinite(thresholds) & (thresholds > 0.0)
    percentiles = percentiles[valid]
    thresholds = thresholds[valid]
    if thresholds.size < 3:
        return float(np.percentile(positive, fallback_percentile)), float(fallback_percentile)

    log_thresholds = np.log10(thresholds.astype(np.float64))
    log_range = float(log_thresholds[-1] - log_thresholds[0])
    if abs(log_range) < 1.0e-12:
        return float(np.percentile(positive, fallback_percentile)), float(fallback_percentile)

    x = (percentiles - percentiles[0]) / max(float(percentiles[-1] - percentiles[0]), 1.0e-12)
    y = (log_thresholds - log_thresholds[0]) / log_range
    points = np.column_stack((x, y))
    start = points[0]
    line = points[-1] - start
    line_norm = max(float(np.linalg.norm(line)), 1.0e-12)
    distances = np.abs(line[0] * (start[1] - points[:, 1]) - (start[0] - points[:, 0]) * line[1]) / line_norm
    index = int(np.argmax(distances))
    return float(thresholds[index]), float(percentiles[index])


def write_preview_png(path: Path, q: np.ndarray, solid_mask: np.ndarray | None, threshold: float) -> None:
    os.environ.setdefault("MPLCONFIGDIR", str(Path(tempfile.gettempdir()) / "a4mc-matplotlib"))
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    q_pos = np.maximum(q, 0.0)
    vmax = max(float(np.percentile(q_pos[q_pos > 0.0], 99.5)) if np.any(q_pos > 0.0) else 1.0, threshold, 1.0e-20)
    slices = [
        ("x mid", q_pos[q.shape[0] // 2, :, :].T),
        ("y mid", q_pos[:, q.shape[1] // 2, :].T),
        ("z mid", q_pos[:, :, q.shape[2] // 2].T),
    ]

    fig, axes = plt.subplots(1, 3, figsize=(13, 4), constrained_layout=True)
    for ax, (title, data) in zip(axes, slices):
        ax.imshow(data, origin="lower", cmap="magma", vmin=0.0, vmax=vmax)
        ax.set_title(title)
        ax.set_xticks([])
        ax.set_yticks([])
    fig.suptitle(f"Q-criterion positive slices, iso={threshold:.4g}")
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=140)
    plt.close(fig)


def make_cell_grid(data: np.ndarray, dx: float):
    import pyvista as pv

    grid = pv.ImageData(
        dimensions=(data.shape[0] + 1, data.shape[1] + 1, data.shape[2] + 1),
        spacing=(dx, dx, dx),
        origin=(0.0, 0.0, 0.0),
    )
    grid.cell_data["q_criterion"] = np.ascontiguousarray(data.ravel(order="F"))
    return grid


def write_vti(path: Path, q: np.ndarray, speed: np.ndarray, dx: float) -> None:
    import pyvista as pv  # noqa: F401

    grid = make_cell_grid(q, dx)
    grid.cell_data["speed"] = np.ascontiguousarray(speed.ravel(order="F"))
    path.parent.mkdir(parents=True, exist_ok=True)
    grid.save(str(path))


def render_q_isosurface(
    path: Path,
    q: np.ndarray,
    solid_mask: np.ndarray | None,
    dx: float,
    threshold: float,
    speed: np.ndarray | None = None,
    camera_view: str = "front-left",
    window_size: tuple[int, int] = (1920, 1080),
    camera_focus: str = "solid",
) -> bool:
    rendered, _ = render_q_isosurface_result(
        path,
        q,
        solid_mask,
        dx,
        threshold,
        speed=speed,
        camera_view=camera_view,
        window_size=window_size,
        camera_focus=camera_focus,
    )
    return rendered


def camera_focus_frame(
    shape: tuple[int, int, int],
    dx: float,
    solid_mask: np.ndarray | None,
    camera_focus: str,
) -> tuple[np.ndarray, float]:
    domain_min = np.zeros(3, dtype=np.float64)
    domain_max = np.array(shape, dtype=np.float64) * float(dx)
    if solid_mask is None or camera_focus == "domain" or not np.any(solid_mask):
        return 0.5 * (domain_min + domain_max), max(float(np.linalg.norm(domain_max - domain_min)), float(dx))

    xs, ys, zs = np.nonzero(solid_mask)
    solid_min = np.array([xs.min(), ys.min(), zs.min()], dtype=np.float64) * float(dx)
    solid_max = (np.array([xs.max(), ys.max(), zs.max()], dtype=np.float64) + 1.0) * float(dx)
    solid_extent = np.maximum(solid_max - solid_min, float(dx))
    length = max(float(solid_extent[0]), float(dx))
    width = max(float(solid_extent[1]), float(dx))
    height = max(float(solid_extent[2]), float(dx))

    if camera_focus == "solid-wake":
        focus_min = solid_min - np.array([0.35 * length, 1.15 * width, 0.85 * height])
        focus_max = solid_max + np.array([2.60 * length, 1.15 * width, 1.10 * height])
    else:
        focus_min = solid_min - np.array([0.55 * length, 1.15 * width, 0.95 * height])
        focus_max = solid_max + np.array([0.75 * length, 1.15 * width, 1.25 * height])

    focus_min = np.maximum(focus_min, domain_min)
    focus_max = np.minimum(focus_max, domain_max)
    focus_extent = np.maximum(focus_max - focus_min, float(dx))
    return 0.5 * (focus_min + focus_max), max(float(np.linalg.norm(focus_extent)), float(dx))


def render_q_isosurface_result(
    path: Path,
    q: np.ndarray,
    solid_mask: np.ndarray | None,
    dx: float,
    threshold: float,
    speed: np.ndarray | None = None,
    cmap: str = "viridis",
    color_by: str = "speed",
    camera_distance: float = 1.15,
    solid_opacity: float = 1.0,
    camera_view: str = "front-left",
    window_size: tuple[int, int] = (1920, 1080),
    camera_focus: str = "solid",
    fallback_percentiles: tuple[float, ...] = (99.0, 97.5, 95.0, 90.0, 85.0, 80.0, 70.0, 60.0, 50.0),
) -> tuple[bool, float]:
    import pyvista as pv

    grid = make_cell_grid(q, dx)
    if speed is not None and speed.shape == q.shape:
        grid.cell_data["speed"] = np.ascontiguousarray(speed.ravel(order="F"))
    point_grid = grid.cell_data_to_point_data()
    point_values = np.asarray(point_grid.point_data["q_criterion"])
    positive = point_values[np.isfinite(point_values) & (point_values > 0.0)]
    candidates = [float(threshold)] if threshold > 0.0 and math.isfinite(threshold) else []
    if positive.size > 0:
        for percentile in fallback_percentiles:
            value = float(np.percentile(positive, percentile))
            if value > 0.0 and math.isfinite(value):
                candidates.append(value)

    contour = None
    used_threshold = 0.0
    seen: set[float] = set()
    for candidate in candidates:
        key = round(candidate, 12)
        if key in seen:
            continue
        seen.add(key)
        attempt = point_grid.contour([candidate], scalars="q_criterion")
        if attempt.n_points > 0:
            contour = attempt
            used_threshold = candidate
            break
    if contour is None:
        return False, 0.0

    plotter = pv.Plotter(off_screen=True, window_size=window_size)
    plotter.set_background((0.015, 0.018, 0.022))
    scalar_name = "speed" if color_by == "speed" and "speed" in contour.point_data else "q_criterion"
    scalar_bar_title = "speed" if scalar_name == "speed" else "Q"
    plotter.add_mesh(
        contour,
        scalars=scalar_name,
        cmap=cmap,
        opacity=0.82,
        show_scalar_bar=True,
        smooth_shading=True,
        scalar_bar_args={"title": scalar_bar_title},
    )

    if solid_mask is not None and np.any(solid_mask):
        solid_grid = pv.ImageData(
            dimensions=(solid_mask.shape[0] + 1, solid_mask.shape[1] + 1, solid_mask.shape[2] + 1),
            spacing=(dx, dx, dx),
            origin=(0.0, 0.0, 0.0),
        )
        solid_grid.cell_data["solid"] = np.ascontiguousarray(solid_mask.astype(np.float32).ravel(order="F"))
        surface = solid_grid.threshold([0.5, 1.5], scalars="solid").extract_surface()
        if surface.n_points == 0:
            surface = solid_grid.cell_data_to_point_data().contour([0.5], scalars="solid")
        if surface.n_points > 0:
            plotter.add_mesh(
                surface,
                color=(0.72, 0.74, 0.78),
                opacity=max(0.0, min(1.0, solid_opacity)),
                smooth_shading=False,
                show_edges=False,
            )
        else:
            print("[q] warning: solid mask surface is empty; car will not be visible")

    center, diagonal = camera_focus_frame(q.shape, float(dx), solid_mask, camera_focus)
    view_directions = {
        "front-left": (-5, -5, 1),
        "front-right": (-1.35, 1.10, 0.72),
        "rear-left": (1.35, -1.10, 0.72),
        "rear-right": (1.35, 1.10, 0.72),
        "side-left": (0.0, -1.55, 0.62),
        "side-right": (0.0, 1.55, 0.62),
    }
    direction = np.array(view_directions.get(camera_view, view_directions["front-left"]), dtype=np.float64)
    direction /= np.linalg.norm(direction)
    camera_position = center + direction * diagonal * max(camera_distance, 0.25)
    plotter.camera_position = (
        tuple(float(v) for v in camera_position),
        tuple(float(v) for v in center),
        (0.0, 0.0, 1.0),
    )
    plotter.camera.clipping_range = (diagonal * 0.01, diagonal * 10.0)
    path.parent.mkdir(parents=True, exist_ok=True)
    plotter.screenshot(str(path))
    plotter.close()
    return True, used_threshold


def collect_flow_paths(path: Path, glob_pattern: str, limit: int) -> list[Path]:
    if path.is_dir():
        paths = sorted(path.glob(glob_pattern))
    else:
        paths = [path]
    if limit > 0:
        paths = paths[:limit]
    if not paths:
        raise FileNotFoundError(f"no flow snapshots found: {path}")
    return paths


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compute Q-criterion from native LBM flow snapshots")
    parser.add_argument("--flow", required=True, type=Path, help="Flow NPZ file or directory of flow_*.npz snapshots")
    parser.add_argument("--flow-glob", default="flow_*.npz", help="Glob used when --flow is a directory")
    parser.add_argument("--solid-mask", type=Path, default=None, help="Optional solid_mask.npz from the benchmark run")
    parser.add_argument("--out-dir", type=Path, default=None, help="Output directory. Defaults to <flow parent>/q_criterion")
    parser.add_argument("--dx", type=float, default=1.0, help="Fallback cell size in meters if not present in metadata")
    parser.add_argument("--threshold", type=float, default=None, help="Absolute Q isosurface threshold")
    parser.add_argument("--threshold-percentile", type=float, default=99.0, help="Positive-Q percentile used when --threshold is omitted")
    parser.add_argument(
        "--threshold-auto",
        action="store_true",
        help="Estimate a positive-Q percentile from the frame distribution instead of using --threshold-percentile",
    )
    parser.add_argument("--threshold-auto-min-percentile", type=float, default=98.0)
    parser.add_argument("--threshold-auto-max-percentile", type=float, default=99.3)
    parser.add_argument("--threshold-auto-sample-values", type=int, default=2_000_000)
    parser.add_argument("--limit", type=int, default=0, help="Limit number of snapshots when processing a directory")
    parser.add_argument("--no-vti", action="store_true", help="Do not write a PyVista/VTK .vti volume")
    parser.add_argument(
        "--render-png",
        action="store_true",
        help="Try to render a Q isosurface PNG with PyVista/VTK. Requires a working off-screen OpenGL setup.",
    )
    parser.add_argument("--no-render", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--strict-render", action="store_true", help="Fail if VTI/render output cannot be written")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.threshold_percentile <= 0.0 or args.threshold_percentile > 100.0:
        raise SystemExit("threshold-percentile must be in (0, 100]")
    if args.threshold_auto and not (0.0 < args.threshold_auto_min_percentile < args.threshold_auto_max_percentile <= 100.0):
        raise SystemExit("threshold-auto percentiles must satisfy 0 < min < max <= 100")
    if args.threshold_auto_sample_values < 0:
        raise SystemExit("threshold-auto-sample-values must be non-negative")

    flow_paths = collect_flow_paths(args.flow, args.flow_glob, args.limit)
    out_dir = args.out_dir or (args.flow if args.flow.is_dir() else args.flow.parent) / "q_criterion"
    out_dir.mkdir(parents=True, exist_ok=True)

    auto_mask = args.solid_mask
    if auto_mask is None:
        candidate = args.flow.parent / "solid_mask.npz"
        if candidate.exists():
            auto_mask = candidate

    for index, flow_path in enumerate(flow_paths):
        velocity, metadata = load_velocity(flow_path)
        dx = metadata_dx(metadata, args.dx)
        solid_mask = load_solid_mask(auto_mask, velocity.shape[:3]) if auto_mask is not None else None
        q, speed = compute_q_criterion(velocity, dx, solid_mask)
        selected_percentile = args.threshold_percentile
        if args.threshold is not None:
            threshold = float(args.threshold)
        elif args.threshold_auto:
            threshold, selected_percentile = auto_positive_threshold(
                q,
                args.threshold_auto_min_percentile,
                args.threshold_auto_max_percentile,
                args.threshold_auto_sample_values,
            )
        else:
            threshold = positive_threshold(q, args.threshold_percentile)

        stem = "q_criterion" if len(flow_paths) == 1 else f"{flow_path.stem}_q"
        q_path = out_dir / f"{stem}.npz"
        np.savez_compressed(
            q_path,
            q_criterion=q,
            speed=speed,
            metadata=np.array(
                json.dumps(
                    {
                        "schema_version": 1,
                        "source_flow": str(flow_path),
                        "solid_mask": str(auto_mask) if auto_mask is not None else "",
                        "dx_m": dx,
                        "threshold": threshold,
                        "threshold_percentile": selected_percentile,
                        "threshold_mode": "auto" if args.threshold_auto and args.threshold is None else "fixed" if args.threshold is not None else "percentile",
                        "q_min": float(np.nanmin(q)),
                        "q_max": float(np.nanmax(q)),
                        "q_positive_cells": int(np.count_nonzero(q > 0.0)),
                    },
                    indent=2,
                )
            ),
        )

        preview_path = out_dir / f"{stem}_preview.png"
        write_preview_png(preview_path, q, solid_mask, threshold)

        print(f"[q] {index + 1}/{len(flow_paths)} flow={flow_path}")
        print(f"[q] wrote={q_path}")
        print(f"[q] preview={preview_path}")
        print(
            f"[q] threshold={threshold:.9g} percentile={selected_percentile:.4g} "
            f"q_range=({float(np.nanmin(q)):.9g}, {float(np.nanmax(q)):.9g})"
        )

        if not args.no_vti:
            try:
                vti_path = out_dir / f"{stem}.vti"
                write_vti(vti_path, q, speed, dx)
                print(f"[q] vti={vti_path}")
            except Exception as exc:
                if args.strict_render:
                    raise
                print(f"[q] warning: VTI export skipped: {exc}")

        if args.render_png and not args.no_render and threshold > 0.0 and math.isfinite(threshold):
            try:
                render_path = out_dir / f"{stem}_isosurface.png"
                if render_q_isosurface(render_path, q, solid_mask, dx, threshold, speed=speed):
                    print(f"[q] render={render_path}")
                else:
                    print("[q] warning: Q isosurface is empty at the selected threshold")
            except Exception as exc:
                if args.strict_render:
                    raise
                print(f"[q] warning: isosurface render skipped: {exc}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
