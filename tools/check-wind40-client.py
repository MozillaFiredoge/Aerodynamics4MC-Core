#!/usr/bin/env python3
"""Run production client wind decoding contracts using a built NeoForge 1.21.1 development classpath."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
dev = root / "versions/1.21.1-neoforge/build/moddev"
classpath_file = dev / "clientRunClasspath.txt"
if not classpath_file.exists():
    raise SystemExit("Build :1.21.1-neoforge:classes and :1.21.1-neoforge:createClientLaunchScript first")
classpath = classpath_file.read_text().splitlines()[1]
classpath += ":" + str(next((dev / "artifacts").glob("neoforge-*-merged.jar")))
with tempfile.TemporaryDirectory(prefix="a4mc-wind-client-") as work:
    subprocess.run(["javac", "-proc:none", "-cp", classpath, "-d", work,
                    str(root / "tools/tests/Wind40ClientContractTest.java")], check=True)
    subprocess.run(["java", "-cp", work + ":" + classpath,
                    "com.aerodynamics4mc.client.Wind40ClientContractTest"], cwd=work, check=True)
