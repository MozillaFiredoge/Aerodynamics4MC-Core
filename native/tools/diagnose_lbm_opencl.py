#!/usr/bin/env python3
"""Measure shear decay and inspect the actual embedded classic OpenCL kernels.

Uses Python's standard library and the system OpenCL loader, not the JNI DLL.
No CPU fallback. WSL execution is refused; --extract-only is safe there.
See native/docs/solver-diagnostics.md for interpretation and Windows commands.
"""

from __future__ import annotations

import argparse
import csv
import ctypes as C
import ctypes.util
import hashlib
import json
import math
import platform
import re
import statistics
import sys
from pathlib import Path


KERNELS = {
    "forced": "stream_collide_hydro_forced_step",
    "benchmark": "stream_collide_hydro_benchmark_step",
    "tgv": "stream_collide_tgv_step",
}
H, U, I, Z, Q = C.c_void_p, C.c_uint, C.c_int, C.c_size_t, C.c_uint64
F = C.c_float


def extract_source(path: Path, selected: str) -> tuple[str, str]:
    text = path.read_text(encoding="utf-8")
    block = text.split("const char* kOpenClSourceParts[] = {", 1)[1]
    block = block.split("cl_uint opencl_source_part_count()", 1)[0]
    parts = re.findall(r'R"CLC\((.*?)\)CLC"', block, re.S)
    if not parts:
        raise ValueError("No embedded OpenCL source parts found")
    original = "".join(parts)  # OpenCL concatenates the strings without separators.
    source = original
    keep = {KERNELS[selected], "init_distributions", "output_macro"}
    found = set()
    # Remove unrelated kernel entry points, retaining all helper functions.
    # Ignore braces inside comments/strings while finding each function's end.
    token = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[{}]', re.S)
    matches = list(re.finditer(r"\bkernel\s+void\s+(\w+)\s*\(", source))
    for match in reversed(matches):
        name = match.group(1)
        found.add(name)
        start = source.index("{", match.end())
        depth = 0
        end = None
        for brace in token.finditer(source, start):
            if brace.group() == "{":
                depth += 1
            elif brace.group() == "}":
                depth -= 1
                if depth == 0:
                    end = brace.end()
                    break
        if end is None:
            raise ValueError(f"Unterminated kernel: {name}")
        if name not in keep:
            removed = source[match.start():end]
            source = source[:match.start()] + "\n" * removed.count("\n") + source[end:]
    if not keep <= found:
        raise ValueError(f"Missing expected kernels: {keep - found}")
    return source, hashlib.sha256(original.encode()).hexdigest()


class OpenCL:
    def __init__(self, device_filter: str):
        if "microsoft" in platform.release().lower():
            raise RuntimeError("GPU execution is disabled in WSL. Run in native Windows; use --extract-only in WSL.")
        if sys.platform == "win32":
            self.lib = C.WinDLL("OpenCL.dll")
        else:
            loader = ctypes.util.find_library("OpenCL")
            if not loader:
                raise RuntimeError("OpenCL loader not found")
            self.lib = C.CDLL(loader)
        self.resources: list[tuple[str, int]] = []
        self.bind()
        self.device = self.choose_gpu(device_filter)
        self.device_name = self.device_string(0x102B)
        self.vendor = self.device_string(0x102C)
        self.driver = self.device_string(0x102D)
        err = I()
        dev = H(self.device)
        self.context = self.own("Context", self.lib.clCreateContext(None, 1, C.byref(dev), None, None, C.byref(err)), err)
        self.queue = self.own("CommandQueue", self.lib.clCreateCommandQueue(self.context, self.device, 2, C.byref(err)), err)

    def bind(self):
        signatures = {
            "GetPlatformIDs": ([U, C.POINTER(H), C.POINTER(U)], I),
            "GetDeviceIDs": ([H, Q, U, C.POINTER(H), C.POINTER(U)], I),
            "GetDeviceInfo": ([H, U, Z, H, C.POINTER(Z)], I),
            "CreateContext": ([H, U, C.POINTER(H), H, H, C.POINTER(I)], H),
            "CreateCommandQueue": ([H, H, Q, C.POINTER(I)], H),
            "CreateProgramWithSource": ([H, U, C.POINTER(C.c_char_p), C.POINTER(Z), C.POINTER(I)], H),
            "BuildProgram": ([H, U, C.POINTER(H), C.c_char_p, H, H], I),
            "GetProgramBuildInfo": ([H, H, U, Z, H, C.POINTER(Z)], I),
            "GetProgramInfo": ([H, U, Z, H, C.POINTER(Z)], I),
            "CreateKernel": ([H, C.c_char_p, C.POINTER(I)], H),
            "GetKernelWorkGroupInfo": ([H, H, U, Z, H, C.POINTER(Z)], I),
            "SetKernelArg": ([H, U, Z, H], I),
            "CreateBuffer": ([H, Q, Z, H, C.POINTER(I)], H),
            "EnqueueWriteBuffer": ([H, H, U, Z, Z, H, U, C.POINTER(H), C.POINTER(H)], I),
            "EnqueueReadBuffer": ([H, H, U, Z, Z, H, U, C.POINTER(H), C.POINTER(H)], I),
            "EnqueueNDRangeKernel": ([H, H, U, C.POINTER(Z), C.POINTER(Z), C.POINTER(Z), U, C.POINTER(H), C.POINTER(H)], I),
            "GetEventProfilingInfo": ([H, U, Z, H, C.POINTER(Z)], I),
            "Finish": ([H], I),
        }
        for resource in ("Context", "CommandQueue", "Program", "Kernel", "MemObject", "Event"):
            signatures["Release" + resource] = ([H], I)
        for name, (args, result) in signatures.items():
            fn = getattr(self.lib, "cl" + name)
            fn.argtypes, fn.restype = args, result

    @staticmethod
    def check(error: int, operation: str):
        if error:
            raise RuntimeError(f"{operation}: OpenCL error {error}")

    def own(self, kind, handle, error):
        self.check(error.value, "clCreate" + kind)
        if not handle:
            raise RuntimeError(f"clCreate{kind} returned a null handle")
        self.resources.append((kind, handle))
        return handle

    def close(self):
        for kind, handle in reversed(self.resources):
            getattr(self.lib, "clRelease" + kind)(handle)
        self.resources.clear()

    def device_string(self, param, device=None):
        device = self.device if device is None else device
        size = Z()
        self.check(self.lib.clGetDeviceInfo(device, param, 0, None, C.byref(size)), "device info size")
        buf = C.create_string_buffer(size.value)
        self.check(self.lib.clGetDeviceInfo(device, param, size, buf, None), "device info")
        return buf.value.decode(errors="replace")

    def choose_gpu(self, device_filter):
        count = U()
        self.check(self.lib.clGetPlatformIDs(0, None, C.byref(count)), "enumerate platforms")
        platforms = (H * count.value)()
        self.check(self.lib.clGetPlatformIDs(count, platforms, None), "get platforms")
        names = []
        for p in platforms:
            count = U()
            error = self.lib.clGetDeviceIDs(p, 4, 0, None, C.byref(count))  # CL_DEVICE_TYPE_GPU only
            if error == -1:  # CL_DEVICE_NOT_FOUND
                continue
            self.check(error, "enumerate GPUs")
            devices = (H * count.value)()
            self.check(self.lib.clGetDeviceIDs(p, 4, count, devices, None), "get GPUs")
            for device in devices:
                name = self.device_string(0x102B, device)
                vendor = self.device_string(0x102C, device)
                names.append(name)
                if device_filter.lower() in (name + " " + vendor).lower():
                    return device
        raise RuntimeError(f"No matching GPU ({device_filter!r}); CPU fallback is disabled. GPUs: {names}")

    def build(self, source: str, out: Path, strict: bool):
        err = I()
        encoded = C.c_char_p(source.encode())
        self.program = self.own("Program", self.lib.clCreateProgramWithSource(self.context, 1, C.byref(encoded), None, C.byref(err)), err)
        options = "" if strict else "-cl-fast-relaxed-math"
        if "nvidia" in self.vendor.lower():
            options += " -cl-nv-verbose"
        dev = H(self.device)
        print(f"Building selected kernel on {self.device_name}; options={options!r}", flush=True)
        error = self.lib.clBuildProgram(self.program, 1, C.byref(dev), options.encode(), None, None)
        if error == -43 and "-cl-nv-verbose" in options:
            options = options.replace("-cl-nv-verbose", "").strip()
            print("Driver rejected -cl-nv-verbose; retrying without the diagnostic option.", flush=True)
            error = self.lib.clBuildProgram(self.program, 1, C.byref(dev), options.encode(), None, None)
        size = Z()
        self.check(self.lib.clGetProgramBuildInfo(self.program, self.device, 0x1183, 0, None, C.byref(size)), "build log size")
        buf = C.create_string_buffer(max(size.value, 1))
        self.check(self.lib.clGetProgramBuildInfo(self.program, self.device, 0x1183, C.sizeof(buf), buf, None), "build log")
        (out / "build.log").write_text(buf.value.decode(errors="replace"), encoding="utf-8")
        self.check(error, f"build (see {out / 'build.log'})")
        size = Z()
        self.check(self.lib.clGetProgramInfo(self.program, 0x1165, C.sizeof(size), C.byref(size), None), "binary size")
        binary = (C.c_ubyte * size.value)()
        pointer = C.cast(binary, H)
        self.check(self.lib.clGetProgramInfo(self.program, 0x1166, C.sizeof(pointer), C.byref(pointer), None), "program binary")
        data = bytes(binary)
        suffix = "ptx" if b".version" in data[:4096] and b".entry" in data else "bin"
        (out / f"program.{suffix}").write_bytes(data)
        return options

    def kernel(self, name):
        err = I()
        return self.own("Kernel", self.lib.clCreateKernel(self.program, name.encode(), C.byref(err)), err)

    def kernel_info(self, kernel):
        result = {}
        for name, param, typ in (("max_work_group_size", 0x11B0, Z), ("preferred_work_group_multiple", 0x11B3, Z), ("local_bytes", 0x11B2, Q), ("private_bytes_reported", 0x11B4, Q)):
            value = typ()
            self.check(self.lib.clGetKernelWorkGroupInfo(kernel, self.device, param, C.sizeof(value), C.byref(value), None), name)
            result[name] = value.value
        return result

    def buffer(self, array):
        err = I()
        mem = self.own("MemObject", self.lib.clCreateBuffer(self.context, 1, C.sizeof(array), None, C.byref(err)), err)
        self.check(self.lib.clEnqueueWriteBuffer(self.queue, mem, 1, 0, C.sizeof(array), array, 0, None, None), "upload")
        return mem

    def args(self, kernel, values):
        for index, value in enumerate(values):
            self.check(self.lib.clSetKernelArg(kernel, index, C.sizeof(value), C.byref(value)), f"kernel argument {index}")

    def launch(self, kernel, cells, local_size=0, profile=False):
        global_size, local = Z(cells), Z(local_size)
        event = H()
        self.check(self.lib.clEnqueueNDRangeKernel(self.queue, kernel, 1, None, C.byref(global_size), C.byref(local) if local_size else None, 0, None, C.byref(event) if profile else None), "launch")
        if not profile:
            return None
        try:
            self.check(self.lib.clFinish(self.queue), "finish measured kernel")
            start, end = Q(), Q()
            self.check(self.lib.clGetEventProfilingInfo(event, 0x1282, C.sizeof(start), C.byref(start), None), "event start")
            self.check(self.lib.clGetEventProfilingInfo(event, 0x1283, C.sizeof(end), C.byref(end), None), "event end")
            return (end.value - start.value) * 1e-6
        finally:
            self.lib.clReleaseEvent(event)

    def read(self, mem, array):
        self.check(self.lib.clEnqueueReadBuffer(self.queue, mem, 1, 0, C.sizeof(array), array, 0, None, None), "readback")


class Case:
    def __init__(self, cl: OpenCL, kind: str, shape, amplitude, mode):
        self.cl, self.kind = cl, kind
        self.nx, self.ny, self.nz = shape
        self.cells = math.prod(shape)
        self.wave = [math.sin(2 * math.pi * mode * y / self.ny) for y in range(self.ny)]
        self.cosine = [math.cos(2 * math.pi * mode * y / self.ny) for y in range(self.ny)]
        payload = (F * (11 * self.cells))()
        for x in range(self.nx):
            for y in range(self.ny):
                for z in range(self.nz):
                    payload[11 * ((x * self.ny + y) * self.nz + z) + 5] = amplitude * self.wave[y]
        self.payload = cl.buffer(payload)
        self.temp = cl.buffer((F * self.cells)())
        self.f = cl.buffer((F * (27 * self.cells))())
        self.g = cl.buffer((F * (27 * self.cells))())
        self.host_output = (F * (4 * self.cells))()
        self.output = cl.buffer(self.host_output)
        self.init = cl.kernel("init_distributions")
        self.hydro = cl.kernel(KERNELS[kind])
        self.macro = cl.kernel("output_macro")
        self.tick = 0

    def reset(self):
        self.cl.args(self.init, [H(self.payload), I(11), I(self.cells), H(self.f), H(self.g)])
        self.cl.launch(self.init, self.cells)
        self.tick = 0

    def step(self, nu, local_size=0, profile=False):
        shape = [I(self.nx), I(self.ny), I(self.nz), I(self.cells)]
        tau = 0.5 + 3 * nu
        faces = [I(1) for _ in range(6)] + [(F * 4)() for _ in range(6)]
        if self.kind == "forced":
            args = [H(self.f), H(self.payload), H(self.temp), I(11), *shape, I(self.tick), I(255), I(7), F(tau), F(tau), F(nu), F(0), F(0), *faces, I(0), H(self.g)]
        elif self.kind == "benchmark":
            args = [H(self.f), H(self.payload), I(11), *shape, I(255), I(7), F(tau), F(tau), F(nu), *faces, I(0), H(self.g)]
        else:
            args = [H(self.f), *shape, F(tau), F(tau), H(self.g)]
        self.cl.args(self.hydro, args)
        elapsed = self.cl.launch(self.hydro, self.cells, local_size, profile)
        self.f, self.g = self.g, self.f
        self.tick += 1
        return elapsed

    def sample(self):
        self.cl.args(self.macro, [H(self.f), H(self.payload), I(11), I(4), I(self.cells), F(1), H(self.output)])
        self.cl.launch(self.macro, self.cells)
        self.cl.read(self.output, self.host_output)
        sine = cosine = rho_sum = 0.0
        max_transverse = 0.0
        for cell in range(self.cells):
            ux, uy, uz, pressure = self.host_output[4 * cell:4 * cell + 4]
            if not all(math.isfinite(v) for v in (ux, uy, uz, pressure)):
                raise RuntimeError(f"Non-finite macro output at step {self.tick}, cell {cell}")
            y = (cell // self.nz) % self.ny
            sine += ux * self.wave[y]
            cosine += ux * self.cosine[y]
            rho_sum += 1 + pressure
            max_transverse = max(max_transverse, abs(uy), abs(uz))
        return {"step": self.tick, "amplitude": 2 * math.hypot(sine, cosine) / self.cells, "mean_rho_proxy": rho_sum / self.cells, "max_transverse_speed": max_transverse}


def fit_decay(rows, discard, k):
    samples = [r for r in rows if r["step"] >= discard and r["amplitude"] > 0]
    if len(samples) < 3:
        raise ValueError("Need at least three positive-amplitude samples after --discard")
    xs = [r["step"] for r in samples]
    ys = [math.log(r["amplitude"]) for r in samples]
    mx, my = statistics.mean(xs), statistics.mean(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    residual = sum((y - my - slope * (x - mx)) ** 2 for x, y in zip(xs, ys))
    total = sum((y - my) ** 2 for y in ys)
    return {"nu_measured": -slope / (k * k), "r_squared": 1 - residual / total if total else None, "log_amplitude_drop": ys[0] - ys[-1]}


def write_csv(path, rows):
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("experiment", choices=("viscosity", "inspect"))
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1] / "src/aero_lbm_jni.cpp")
    parser.add_argument("--out", type=Path, required=True, help="New/empty output directory")
    parser.add_argument("--kernel", choices=KERNELS, default="forced")
    parser.add_argument("--device", default="NVIDIA", help="GPU name/vendor substring; never selects a CPU")
    parser.add_argument("--extract-only", action="store_true", help="Write source and metadata without loading OpenCL")
    parser.add_argument("--strict-math", action="store_true", help="Omit the production -cl-fast-relaxed-math option")
    parser.add_argument("--nu", type=float, nargs="+", default=[0.02, 0.01, 0.004, 0.001, 0.0001])
    parser.add_argument("--ny", type=int, default=32, help="Shear wavelength axis; viscosity grid is 4 x ny x 4")
    parser.add_argument("--mode", type=int, default=1)
    parser.add_argument("--amplitude", type=float, default=0.01)
    parser.add_argument("--steps", type=int, default=1024)
    parser.add_argument("--sample-every", type=int, default=32)
    parser.add_argument("--discard", type=int, default=128, help="Exclude the initial kinetic transient from the fit")
    parser.add_argument("--time-kernel", action="store_true", help="inspect: additionally measure GPU event time")
    parser.add_argument("--grid", type=int, choices=(32, 64, 128), default=32)
    parser.add_argument("--iterations", type=int, default=10)
    parser.add_argument("--local-size", type=int, default=0, help="0 lets the driver choose, as production does")
    args = parser.parse_args()
    if not (16 <= args.ny <= 256 and 1 <= args.mode < args.ny // 2):
        parser.error("Require 16 <= ny <= 256 and 1 <= mode < ny/2")
    if not (0 < args.amplitude <= 0.02) or not all(math.isfinite(n) and 0 < n <= 0.15 for n in args.nu):
        parser.error("Require 0 < amplitude <= 0.02 and finite 0 < nu <= 0.15")
    if args.steps <= 0 or args.sample_every <= 0 or not 0 <= args.discard < args.steps or args.iterations <= 0 or args.local_size < 0:
        parser.error("Invalid step, sampling, iteration or local size settings")
    sample_steps = [0] + [step for step in range(1, args.steps + 1) if step % args.sample_every == 0 or step == args.steps]
    if args.experiment == "viscosity" and sum(step >= max(args.discard, args.steps // 2) for step in sample_steps) < 3:
        parser.error("Need at least three samples in the final half of the run; decrease --sample-every or increase --steps")
    if args.out.exists() and any(args.out.iterdir()):
        parser.error("Output directory must be empty; choose a new run directory")
    source, original_sha = extract_source(args.source, args.kernel)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "kernel.cl").write_text(source, encoding="utf-8")
    metadata = {"source_path": str(args.source.resolve()), "embedded_source_sha256": original_sha, "compiled_source_sha256": hashlib.sha256(source.encode()).hexdigest(), "kernel": KERNELS[args.kernel], "settings": {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()}, "scope": "Direct embedded OpenCL kernel; periodic empty domain, no forces/SGS/nudge/thermal updates. Not the host/JNI integration path."}
    metadata_path = args.out / "metadata.json"
    metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    if args.extract_only:
        print(f"Extracted {len(source.encode())} bytes to {args.out / 'kernel.cl'}; OpenCL was not loaded.")
        return
    cl = None
    try:
        cl = OpenCL(args.device)
        metadata.update(device=cl.device_name, driver=cl.driver, vendor=cl.vendor)
        metadata["build_options"] = cl.build(source, args.out, args.strict_math)
        shape = (4, args.ny, 4) if args.experiment == "viscosity" else (args.grid,) * 3
        # inspect without timing needs no field buffers.
        kernel = cl.kernel(KERNELS[args.kernel])
        metadata["resources"] = cl.kernel_info(kernel)
        print(json.dumps(metadata["resources"], indent=2), flush=True)
        if args.experiment == "inspect" and not args.time_kernel:
            return
        if args.local_size and (math.prod(shape) % args.local_size or args.local_size > metadata["resources"]["max_work_group_size"]):
            raise ValueError("local size must divide cell count and not exceed kernel maximum")
        case = Case(cl, args.kernel, shape, args.amplitude, args.mode)
        if args.experiment == "inspect":
            case.reset()
            for _ in range(5):
                case.step(args.nu[0], args.local_size)
            cl.check(cl.lib.clFinish(cl.queue), "finish warmup")
            times = [case.step(args.nu[0], args.local_size, True) for _ in range(args.iterations)]
            metadata["timing"] = {"gpu_ms": times, "median_gpu_ms": statistics.median(times), "median_mlups": case.cells / (statistics.median(times) * 1000), "distribution_bytes_per_step": case.cells * 216}
            print(json.dumps(metadata["timing"], indent=2))
        else:
            summary = []
            for index, nu in enumerate(args.nu):
                case.reset()
                rows = [case.sample()]
                for step in range(1, args.steps + 1):
                    case.step(nu, args.local_size)
                    if step % args.sample_every == 0 or step == args.steps:
                        rows.append(case.sample())
                write_csv(args.out / f"decay_{index:02d}.csv", rows)
                fit = fit_decay(rows, args.discard, 2 * math.pi * args.mode / args.ny)
                result = {"nu_requested": nu, "tau_passed_fp32": F(0.5 + 3 * nu).value, **fit, "fit_last_half_nu": fit_decay(rows, max(args.discard, args.steps // 2), 2 * math.pi * args.mode / args.ny)["nu_measured"]}
                summary.append(result)
                write_csv(args.out / "viscosity.csv", summary)
                print(json.dumps(result), flush=True)
            metadata["viscosity"] = summary
    finally:
        metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        if cl:
            cl.close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
