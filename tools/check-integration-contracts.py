#!/usr/bin/env python3
"""Run Minecraft-free terrain API and particle selection regression checks using JDK 17+."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
sources = list((root / "api/src/main/java").rglob("*.java"))
sources += [root / "tools/tests/AeroTerrainApiContractTest.java"]
sources += [root / "src/client/java/com/aerodynamics4mc/client/ParticleWindRules.java",
            root / "tools/tests/ParticleWindRulesContractTest.java"]
with tempfile.TemporaryDirectory(prefix="a4mc-contracts-") as output:
    subprocess.run(["javac", "--release", "17", "-d", output, *map(str, sources)], check=True)
    for test in ["com.aerodynamics4mc.api.AeroTerrainApiContractTest",
                 "com.aerodynamics4mc.client.ParticleWindRulesContractTest"]:
        subprocess.run(["java", "-cp", output, test], check=True)
