#!/usr/bin/env python3
"""Run issue #19's grid concurrency contracts on an isolated NeoForge 1.21.1 server."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--skip-build", action="store_true", help="Use an already built jar and server launch arguments")
args = parser.parse_args()
variant = root / "versions/1.21.1-neoforge"
if not args.skip_build:
    subprocess.run([str(root / "gradlew"), ":1.21.1-neoforge:jar",
                    ":1.21.1-neoforge:createServerLaunchScript"], cwd=root, check=True)
properties = (root / "stonecutter.properties.toml").read_text()
version = re.search(r'^mod.version\s*=\s*"([^"]+)"', properties, re.M)[1]
channel = re.search(r'^mod.channel_tag\s*=\s*"([^"]*)"', properties, re.M)[1]
artifacts = list((variant / "build/libs").glob(f"aerodynamics4mc-{version}{channel}-neoforge+1.21.1*.jar"))
artifacts = [p for p in artifacts if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
if len(artifacts) != 1:
    raise SystemExit(f"Expected one core jar, found {artifacts}")
dev = variant / "build/moddev"
classpath = (dev / "serverRunClasspath.txt").read_text().splitlines()[1]
merged = next((dev / "artifacts").glob("neoforge-*-merged.jar"))
report_root = root / "build/reports/coarse-grid-runtime"
report_root.mkdir(parents=True, exist_ok=True)
# Retain logs and source injection separately from all release artifacts and player saves.
work = Path(tempfile.mkdtemp(prefix="run-", dir=report_root))
classes, addon, server = work / "classes", work / "addon", work / "server"
classes.mkdir()
(addon / "META-INF").mkdir(parents=True)
(server / "mods").mkdir(parents=True)
compile_classpath = classpath + ":" + str(merged)
subprocess.run(["javac", "-proc:none", "-cp", compile_classpath, "-d", str(classes),
                str(root / "tools/tests/CoarseGridConcurrencyTest.java")], check=True)
wrapper = work / "IssueTests.java"
wrapper.write_text('''package a4mc.issuetests;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import com.aerodynamics4mc.runtime.CoarseGridConcurrencyTest;
@Mod("a4mc_issue_tests")
public class IssueTests {
 public IssueTests(){NeoForge.EVENT_BUS.addListener(this::started);}
 void started(ServerStartedEvent event){
  try {CoarseGridConcurrencyTest.run(event.getServer().overworld());java.nio.file.Files.writeString(java.nio.file.Path.of("result.txt"),"PASS\\n");}
  catch(Throwable e){e.printStackTrace();try{java.nio.file.Files.writeString(java.nio.file.Path.of("result.txt"),"FAIL: "+e);}catch(Exception ignored){}}
  event.getServer().halt(false);
 }
}
''')
subprocess.run(["javac", "-proc:none", "-cp", compile_classpath + ":" + str(classes),
                "-d", str(addon), str(wrapper)], check=True)
(addon / "META-INF/neoforge.mods.toml").write_text('''modLoader="javafml"
loaderVersion="[4,)"
license="MIT"
[[mods]]
modId="a4mc_issue_tests"
version="1.0"
displayName="Coarse grid concurrency contracts"
''')
# The contract class uses package access; put it into the temporary core copy to avoid module split packages.
with zipfile.ZipFile(artifacts[0]) as source, zipfile.ZipFile(server / "mods/core.jar", "w", zipfile.ZIP_DEFLATED) as output:
    seen = set()
    for item in source.infolist():
        if item.filename not in seen:
            output.writestr(item, source.read(item))
            seen.add(item.filename)
    for item in classes.rglob("*.class"):
        output.write(item, item.relative_to(classes).as_posix())
subprocess.run(["jar", "cf", str(server / "mods/contracts.jar"), "-C", str(addon), "."], check=True)
# Load production jars, without leaking development classes or API dependencies into the mod loader.
clean = ":".join(p for p in classpath.split(":") if not any(x in p for x in
                 ("/build/classes/", "/build/resources/", "/build/generated/", "/api/build/libs/", "PacketHandler-")))
cp_file = work / "classpath.txt"
cp_file.write_text("-classpath\n" + clean)
(server / "eula.txt").write_text("eula=true\n")
(server / "server.properties").write_text("online-mode=false\nserver-ip=127.0.0.1\nserver-port=25579\n"
                                         "view-distance=2\nsimulation-distance=2\ngenerate-structures=false\n")
log = work / "console.log"
print(f"Running isolated server; report: {work}", flush=True)
with log.open("w") as output:
    completed = subprocess.run(["java", "-Xmx2G", "@" + str(cp_file), "@" + str(dev / "serverRunVmArgs.txt"),
                               "net.neoforged.devlaunch.Main", "@" + str(dev / "serverRunProgramArgs.txt"), "nogui"],
                              cwd=server, stdout=output, stderr=subprocess.STDOUT, timeout=180)
result = server / "result.txt"
if completed.returncode or not result.exists() or result.read_text().strip() != "PASS":
    print("\n".join(log.read_text().splitlines()[-50:]))
    raise SystemExit(f"Grid concurrency contracts failed; see {log}")
for line in log.read_text().splitlines():
    if "COARSE_GRID_" in line:
        print(line)
print(f"PASS; report: {log}")
