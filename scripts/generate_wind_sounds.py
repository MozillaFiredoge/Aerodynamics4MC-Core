#!/usr/bin/env python3
"""Generate Aerodynamics4MC wind ambience OGG assets.

Examples:
  python3 scripts/generate_wind_sounds.py
  python3 scripts/generate_wind_sounds.py --dry-run
  python3 scripts/generate_wind_sounds.py --only breeze,strong --set breeze.duration=24 --set strong.peak=0.40
  python3 scripts/generate_wind_sounds.py --set leaf.burst_rate_hz=26 --set grass.peak=0.28
  python3 scripts/generate_wind_sounds.py --only gust --set gust.sweep_end_hz=4200

The generator intentionally uses only Python's standard library plus ffmpeg.
Profiles are synthetic noise patches: tune the profile fields, regenerate the
OGG files, then run the mod build to package them.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import shutil
import subprocess
import tempfile
import wave
from array import array
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Iterable


DEFAULT_OUTPUT_DIR = Path("src/main/resources/assets/aerodynamics4mc/sounds/wind")


@dataclass(frozen=True)
class SoundProfile:
    name: str
    file_name: str
    kind: str
    duration: float
    peak: float
    highpass_hz: float
    lowpass_hz: float
    body_lowpass_hz: float = 0.0
    body_mix: float = 0.0
    hiss_highpass_hz: float = 0.0
    hiss_mix: float = 0.0
    lfo_rate_hz: float = 0.0
    lfo_depth: float = 0.0
    lfo2_rate_hz: float = 0.0
    lfo2_depth: float = 0.0
    texture_rate_hz: float = 0.0
    texture_depth: float = 0.0
    burst_rate_hz: float = 0.0
    burst_gain: float = 0.0
    burst_attack_s: float = 0.004
    burst_decay_s: float = 0.08
    burst_bandpass_hz: float = 1600.0
    loop_crossfade_s: float = 1.0
    attack_s: float = 0.08
    release_power: float = 2.4
    sweep_start_hz: float = 700.0
    sweep_end_hz: float = 3200.0
    seed_offset: int = 0


DEFAULT_PROFILES: dict[str, SoundProfile] = {
    "breeze": SoundProfile(
        name="breeze",
        file_name="breeze_loop.ogg",
        kind="loop",
        duration=18.0,
        peak=0.32,
        highpass_hz=95.0,
        lowpass_hz=2100.0,
        body_lowpass_hz=430.0,
        body_mix=0.28,
        hiss_highpass_hz=1800.0,
        hiss_mix=0.05,
        lfo_rate_hz=0.045,
        lfo_depth=0.30,
        lfo2_rate_hz=0.13,
        lfo2_depth=0.12,
        texture_rate_hz=0.22,
        texture_depth=0.13,
        burst_rate_hz=0.035,
        burst_gain=0.10,
        burst_attack_s=0.020,
        burst_decay_s=0.45,
        burst_bandpass_hz=520.0,
        loop_crossfade_s=1.25,
        seed_offset=11,
    ),
    "strong": SoundProfile(
        name="strong",
        file_name="strong_loop.ogg",
        kind="loop",
        duration=18.0,
        peak=0.44,
        highpass_hz=60.0,
        lowpass_hz=4800.0,
        body_lowpass_hz=650.0,
        body_mix=0.35,
        hiss_highpass_hz=2300.0,
        hiss_mix=0.18,
        lfo_rate_hz=0.075,
        lfo_depth=0.40,
        lfo2_rate_hz=0.31,
        lfo2_depth=0.16,
        texture_rate_hz=0.38,
        texture_depth=0.20,
        burst_rate_hz=0.35,
        burst_gain=0.24,
        burst_attack_s=0.025,
        burst_decay_s=0.55,
        burst_bandpass_hz=950.0,
        loop_crossfade_s=1.35,
        seed_offset=29,
    ),
    "leaf": SoundProfile(
        name="leaf",
        file_name="leaf_rustle_loop.ogg",
        kind="loop",
        duration=14.0,
        peak=0.30,
        highpass_hz=620.0,
        lowpass_hz=8200.0,
        hiss_highpass_hz=2600.0,
        hiss_mix=0.30,
        lfo_rate_hz=0.42,
        lfo_depth=0.18,
        lfo2_rate_hz=1.70,
        lfo2_depth=0.08,
        texture_rate_hz=2.80,
        texture_depth=0.22,
        burst_rate_hz=18.0,
        burst_gain=0.55,
        burst_attack_s=0.002,
        burst_decay_s=0.036,
        burst_bandpass_hz=3300.0,
        loop_crossfade_s=0.75,
        seed_offset=43,
    ),
    "grass": SoundProfile(
        name="grass",
        file_name="grass_rustle_loop.ogg",
        kind="loop",
        duration=13.0,
        peak=0.28,
        highpass_hz=760.0,
        lowpass_hz=9200.0,
        hiss_highpass_hz=3000.0,
        hiss_mix=0.24,
        lfo_rate_hz=0.62,
        lfo_depth=0.15,
        lfo2_rate_hz=2.30,
        lfo2_depth=0.10,
        texture_rate_hz=4.20,
        texture_depth=0.28,
        burst_rate_hz=28.0,
        burst_gain=0.48,
        burst_attack_s=0.0015,
        burst_decay_s=0.024,
        burst_bandpass_hz=4200.0,
        loop_crossfade_s=0.65,
        seed_offset=53,
    ),
    "ground": SoundProfile(
        name="ground",
        file_name="ground_wind_loop.ogg",
        kind="loop",
        duration=18.0,
        peak=0.24,
        highpass_hz=240.0,
        lowpass_hz=3600.0,
        body_lowpass_hz=520.0,
        body_mix=0.18,
        hiss_highpass_hz=1300.0,
        hiss_mix=0.12,
        lfo_rate_hz=0.055,
        lfo_depth=0.22,
        lfo2_rate_hz=0.21,
        lfo2_depth=0.09,
        texture_rate_hz=0.80,
        texture_depth=0.12,
        burst_rate_hz=0.70,
        burst_gain=0.08,
        burst_attack_s=0.018,
        burst_decay_s=0.18,
        burst_bandpass_hz=1250.0,
        loop_crossfade_s=1.10,
        seed_offset=61,
    ),
    "gust": SoundProfile(
        name="gust",
        file_name="gust_whoosh_1.ogg",
        kind="gust",
        duration=2.35,
        peak=0.56,
        highpass_hz=135.0,
        lowpass_hz=6400.0,
        body_lowpass_hz=520.0,
        body_mix=0.32,
        hiss_highpass_hz=2200.0,
        hiss_mix=0.20,
        lfo_rate_hz=0.85,
        lfo_depth=0.18,
        texture_rate_hz=3.30,
        texture_depth=0.10,
        burst_rate_hz=2.4,
        burst_gain=0.12,
        burst_attack_s=0.008,
        burst_decay_s=0.18,
        burst_bandpass_hz=1700.0,
        attack_s=0.18,
        release_power=2.1,
        sweep_start_hz=560.0,
        sweep_end_hz=3800.0,
        seed_offset=71,
    ),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--sample-rate", type=int, default=44100)
    parser.add_argument("--seed", type=int, default=424242)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--quality", type=float, default=3.0, help="Vorbis -q:a value passed to ffmpeg")
    parser.add_argument("--only", default="", help="Comma-separated profile names: breeze,strong,leaf,grass,ground,gust")
    parser.add_argument(
        "--set",
        action="append",
        default=[],
        metavar="PROFILE.FIELD=VALUE",
        help="Override a profile field, e.g. --set strong.peak=0.38",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print resolved parameters without writing files")
    parser.add_argument("--keep-wav", action="store_true", help="Write intermediate WAV files beside the OGG files")
    args = parser.parse_args()

    profiles = apply_overrides(DEFAULT_PROFILES, args.set)
    selected = select_profiles(profiles, args.only)

    if args.dry_run:
        print(json.dumps({name: asdict(profile) for name, profile in selected.items()}, indent=2, sort_keys=True))
        return 0

    if shutil.which(args.ffmpeg) is None:
        raise SystemExit(f"ffmpeg not found: {args.ffmpeg}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="a4mc-wind-sounds-") as temp_dir_name:
        temp_dir = Path(temp_dir_name)
        for index, profile in enumerate(selected.values()):
            rng = random.Random(args.seed + profile.seed_offset + index * 9973)
            samples = generate_profile(profile, args.sample_rate, rng)
            wav_path = temp_dir / f"{profile.name}.wav"
            write_wav(wav_path, samples, args.sample_rate)

            output_path = args.output_dir / profile.file_name
            encode_ogg(args.ffmpeg, wav_path, output_path, args.quality)
            if args.keep_wav:
                keep_path = args.output_dir / f"{Path(profile.file_name).stem}.wav"
                keep_path.write_bytes(wav_path.read_bytes())
            print(f"generated {output_path} ({profile.kind}, {profile.duration:.2f}s)")

    return 0


def apply_overrides(profiles: dict[str, SoundProfile], overrides: Iterable[str]) -> dict[str, SoundProfile]:
    resolved = dict(profiles)
    for override in overrides:
        if "=" not in override or "." not in override.split("=", 1)[0]:
            raise SystemExit(f"invalid override {override!r}; expected PROFILE.FIELD=VALUE")
        key, value_text = override.split("=", 1)
        profile_name, field_name = key.split(".", 1)
        if profile_name not in resolved:
            raise SystemExit(f"unknown profile {profile_name!r}; expected one of {', '.join(resolved)}")
        profile = resolved[profile_name]
        data = asdict(profile)
        if field_name not in data:
            raise SystemExit(f"unknown field {profile_name}.{field_name}")
        if field_name in {"name", "file_name", "kind"}:
            raise SystemExit(f"{profile_name}.{field_name} is not overrideable")
        current = data[field_name]
        if isinstance(current, int) and not isinstance(current, bool):
            value = int(value_text)
        elif isinstance(current, float):
            value = float(value_text)
        else:
            raise SystemExit(f"{profile_name}.{field_name} cannot be overridden")
        resolved[profile_name] = replace(profile, **{field_name: value})
    return resolved


def select_profiles(profiles: dict[str, SoundProfile], only: str) -> dict[str, SoundProfile]:
    if not only.strip():
        return profiles
    selected: dict[str, SoundProfile] = {}
    for name in [item.strip() for item in only.split(",") if item.strip()]:
        if name not in profiles:
            raise SystemExit(f"unknown profile {name!r}; expected one of {', '.join(profiles)}")
        selected[name] = profiles[name]
    return selected


def generate_profile(profile: SoundProfile, sample_rate: int, rng: random.Random) -> list[float]:
    if profile.kind == "loop":
        return generate_loop(profile, sample_rate, rng)
    if profile.kind == "gust":
        return generate_gust(profile, sample_rate, rng)
    raise SystemExit(f"unknown profile kind {profile.kind!r}")


def generate_loop(profile: SoundProfile, sample_rate: int, rng: random.Random) -> list[float]:
    output_count = max(1, int(profile.duration * sample_rate))
    fade_count = max(1, min(output_count // 3, int(profile.loop_crossfade_s * sample_rate)))
    total_count = output_count + fade_count

    samples = make_wind_bed(profile, total_count, sample_rate, rng)
    apply_modulation(samples, profile, sample_rate, rng)
    add_bursts(samples, profile, sample_rate, rng)
    samples = soft_clip(samples, drive=1.35)
    samples = seamless_loop(samples, output_count, fade_count)
    remove_dc(samples)
    normalize_peak(samples, profile.peak)
    return samples


def generate_gust(profile: SoundProfile, sample_rate: int, rng: random.Random) -> list[float]:
    count = max(1, int(profile.duration * sample_rate))
    raw = [rng.uniform(-1.0, 1.0) for _ in range(count)]
    swept = dynamic_lowpass(raw, sample_rate, profile.sweep_start_hz, profile.sweep_end_hz)
    swept = biquad_filter(swept, sample_rate, "highpass", profile.highpass_hz, q=0.70)
    swept = biquad_filter(swept, sample_rate, "lowpass", profile.lowpass_hz, q=0.72)

    samples = swept
    if profile.body_mix > 0.0:
        body = biquad_filter(raw[:], sample_rate, "lowpass", profile.body_lowpass_hz, q=0.60)
        mix_in(samples, body, profile.body_mix)
    if profile.hiss_mix > 0.0:
        hiss = biquad_filter(raw[:], sample_rate, "highpass", profile.hiss_highpass_hz, q=0.75)
        hiss = biquad_filter(hiss, sample_rate, "lowpass", profile.lowpass_hz, q=0.70)
        mix_in(samples, hiss, profile.hiss_mix)

    apply_modulation(samples, profile, sample_rate, rng)
    add_bursts(samples, profile, sample_rate, rng)
    for i in range(count):
        t = i / sample_rate
        progress = i / max(1, count - 1)
        attack = smoothstep(0.0, profile.attack_s, t)
        tail_progress = max(0.0, (t - profile.attack_s) / max(0.001, profile.duration - profile.attack_s))
        release = max(0.0, 1.0 - tail_progress) ** profile.release_power
        samples[i] *= attack * release

    samples = soft_clip(samples, drive=1.55)
    remove_dc(samples)
    normalize_peak(samples, profile.peak)
    return samples


def make_wind_bed(profile: SoundProfile, count: int, sample_rate: int, rng: random.Random) -> list[float]:
    raw = [rng.uniform(-1.0, 1.0) for _ in range(count)]
    base = biquad_filter(raw[:], sample_rate, "highpass", profile.highpass_hz, q=0.72)
    base = biquad_filter(base, sample_rate, "lowpass", profile.lowpass_hz, q=0.72)

    if profile.body_mix > 0.0:
        body = biquad_filter(raw[:], sample_rate, "lowpass", profile.body_lowpass_hz, q=0.58)
        mix_in(base, body, profile.body_mix)
    if profile.hiss_mix > 0.0:
        hiss_raw = [rng.uniform(-1.0, 1.0) for _ in range(count)]
        hiss = biquad_filter(hiss_raw, sample_rate, "highpass", profile.hiss_highpass_hz, q=0.72)
        hiss = biquad_filter(hiss, sample_rate, "lowpass", profile.lowpass_hz, q=0.70)
        mix_in(base, hiss, profile.hiss_mix)
    return base


def apply_modulation(samples: list[float], profile: SoundProfile, sample_rate: int, rng: random.Random) -> None:
    count = len(samples)
    phase1 = rng.uniform(0.0, math.tau)
    phase2 = rng.uniform(0.0, math.tau)
    texture = make_texture(count, sample_rate, profile.texture_rate_hz, profile.texture_depth, rng)
    for i in range(count):
        t = i / sample_rate
        gain = 1.0
        if profile.lfo_depth > 0.0 and profile.lfo_rate_hz > 0.0:
            gain *= 1.0 + math.sin(math.tau * profile.lfo_rate_hz * t + phase1) * profile.lfo_depth
        if profile.lfo2_depth > 0.0 and profile.lfo2_rate_hz > 0.0:
            gain *= 1.0 + math.sin(math.tau * profile.lfo2_rate_hz * t + phase2) * profile.lfo2_depth
        samples[i] *= max(0.0, gain * texture[i])


def make_texture(count: int, sample_rate: int, rate_hz: float, depth: float, rng: random.Random) -> list[float]:
    if rate_hz <= 0.0 or depth <= 0.0:
        return [1.0] * count
    step = max(1, int(sample_rate / rate_hz))
    values = [1.0] * count
    previous = 1.0 + rng.uniform(-depth, depth)
    index = 0
    while index < count:
        target = 1.0 + rng.uniform(-depth, depth)
        end = min(count, index + step)
        span = max(1, end - index)
        for i in range(index, end):
            t = (i - index) / span
            values[i] = previous + (target - previous) * smoothstep(0.0, 1.0, t)
        previous = target
        index = end
    return values


def add_bursts(samples: list[float], profile: SoundProfile, sample_rate: int, rng: random.Random) -> None:
    if profile.burst_rate_hz <= 0.0 or profile.burst_gain <= 0.0:
        return

    count = len(samples)
    duration = count / sample_rate
    burst_track = [0.0] * count
    t = 0.0
    while t < duration:
        t += rng.expovariate(profile.burst_rate_hz)
        start = int(t * sample_rate)
        if start >= count:
            break
        burst_count = min(count - start, max(1, int(profile.burst_decay_s * 6.0 * sample_rate)))
        for i in range(burst_count):
            local_t = i / sample_rate
            attack = smoothstep(0.0, profile.burst_attack_s, local_t)
            decay = math.exp(-local_t / max(0.001, profile.burst_decay_s))
            burst_track[start + i] += rng.uniform(-1.0, 1.0) * attack * decay

    burst_track = biquad_filter(burst_track, sample_rate, "bandpass", profile.burst_bandpass_hz, q=1.25)
    for i in range(count):
        samples[i] += burst_track[i] * profile.burst_gain


def seamless_loop(samples: list[float], output_count: int, fade_count: int) -> list[float]:
    loop = samples[:output_count]
    for i in range(fade_count):
        t = i / max(1, fade_count - 1)
        loop[i] = samples[output_count + i] * (1.0 - t) + loop[i] * t
    return loop


def dynamic_lowpass(samples: list[float], sample_rate: int, start_hz: float, end_hz: float) -> list[float]:
    output: list[float] = []
    y = 0.0
    ratio = max(0.001, end_hz) / max(0.001, start_hz)
    for i, sample in enumerate(samples):
        progress = i / max(1, len(samples) - 1)
        eased = smoothstep(0.0, 1.0, progress)
        cutoff = start_hz * (ratio ** eased)
        alpha = 1.0 - math.exp(-math.tau * cutoff / sample_rate)
        y += alpha * (sample - y)
        output.append(y)
    return output


def biquad_filter(samples: list[float], sample_rate: int, kind: str, cutoff_hz: float, q: float) -> list[float]:
    cutoff = max(20.0, min(cutoff_hz, sample_rate * 0.45))
    omega = math.tau * cutoff / sample_rate
    sin_omega = math.sin(omega)
    cos_omega = math.cos(omega)
    alpha = sin_omega / (2.0 * max(0.05, q))

    if kind == "lowpass":
        b0 = (1.0 - cos_omega) * 0.5
        b1 = 1.0 - cos_omega
        b2 = (1.0 - cos_omega) * 0.5
    elif kind == "highpass":
        b0 = (1.0 + cos_omega) * 0.5
        b1 = -(1.0 + cos_omega)
        b2 = (1.0 + cos_omega) * 0.5
    elif kind == "bandpass":
        b0 = sin_omega * 0.5
        b1 = 0.0
        b2 = -sin_omega * 0.5
    else:
        raise ValueError(f"unsupported filter kind: {kind}")

    a0 = 1.0 + alpha
    a1 = -2.0 * cos_omega
    a2 = 1.0 - alpha
    b0 /= a0
    b1 /= a0
    b2 /= a0
    a1 /= a0
    a2 /= a0

    x1 = x2 = y1 = y2 = 0.0
    output: list[float] = []
    for x0 in samples:
        y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        output.append(y0)
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0
    return output


def mix_in(target: list[float], source: list[float], amount: float) -> None:
    dry = max(0.0, 1.0 - amount)
    wet = max(0.0, amount)
    for i in range(len(target)):
        target[i] = target[i] * dry + source[i] * wet


def soft_clip(samples: list[float], drive: float) -> list[float]:
    normalizer = math.tanh(drive)
    return [math.tanh(sample * drive) / normalizer for sample in samples]


def remove_dc(samples: list[float]) -> None:
    if not samples:
        return
    mean = sum(samples) / len(samples)
    for i in range(len(samples)):
        samples[i] -= mean


def normalize_peak(samples: list[float], peak: float) -> None:
    maximum = max((abs(sample) for sample in samples), default=0.0)
    if maximum <= 0.000001:
        return
    gain = peak / maximum
    for i in range(len(samples)):
        samples[i] *= gain


def write_wav(path: Path, samples: list[float], sample_rate: int) -> None:
    pcm = array("h")
    for sample in samples:
        clamped = max(-1.0, min(1.0, sample))
        pcm.append(int(round(clamped * 32767.0)))
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(pcm.tobytes())


def encode_ogg(ffmpeg: str, wav_path: Path, output_path: Path, quality: float) -> None:
    command = [
        ffmpeg,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(wav_path),
        "-ac",
        "1",
        "-codec:a",
        "libvorbis",
        "-q:a",
        str(quality),
        str(output_path),
    ]
    subprocess.run(command, check=True)


def smoothstep(edge0: float, edge1: float, value: float) -> float:
    if edge1 <= edge0:
        return 1.0 if value >= edge1 else 0.0
    t = max(0.0, min(1.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


if __name__ == "__main__":
    raise SystemExit(main())
