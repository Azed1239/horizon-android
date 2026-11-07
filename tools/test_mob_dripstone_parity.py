#!/usr/bin/env python3
"""Compare the live mob landing adapter with registered vanilla dripstone landings."""
import json
import os
from pathlib import Path
import subprocess

try:
    from . import lab, test_rust_parity as base
except ImportError:
    import lab
    import test_rust_parity as base


def main():
    lock, jars, libraries = base.verify_artifacts()
    build = lab.LOCAL / "rust-mob-dripstone-parity"
    build.mkdir(exist_ok=True)
    report_path = build / "report.json"
    report_path.unlink(missing_ok=True)
    oracle = lab.ROOT / "mods/horizon/src/test/java/dev/horizonlab/parity/VanillaMobDripstoneOracle.java"
    sources = [Path(__file__), oracle, lab.ROOT / "Cargo.lock"]
    for crate in ["horizon-client", "horizon-world", "horizon-core"]:
        sources.extend(sorted((lab.ROOT / "crates" / crate / "src").rglob("*.rs")))
    tested = base.source_hashes(sources)
    if not (lab.RESOURCES / "entity-types.json").is_file():
        raise RuntimeError("Prepare the pinned game resources before running this harness")
    classes = build / "classes"
    classes.mkdir(exist_ok=True)
    dependencies = os.pathsep.join(map(str, libraries))

    def run(command, name, env=None):
        with (build / (name + ".log")).open("w", encoding="utf-8") as log:
            subprocess.run(list(map(str, command)), cwd=lab.ROOT, env=env, check=True,
                           timeout=1800, stdout=log, stderr=subprocess.STDOUT)

    run(["javac", "--release", lock["minecraft"]["java_major"], "-proc:none", "-cp",
         str(jars["server"]) + os.pathsep + dependencies, "-d", classes, oracle], "compile-java")
    references = []
    expected = None
    for side, jar in jars.items():
        output = build / (side + ".json")
        run(["java", "-cp", os.pathsep.join([str(classes), str(jar), dependencies]),
             "dev.horizonlab.parity.VanillaMobDripstoneOracle", output], side)
        rows = json.loads(output.read_text(encoding="utf-8"))
        if expected is not None and rows != expected:
            raise AssertionError("Client and server dripstone oracle results differ")
        expected = rows
        references.append(dict(side=side, jar_sha256=lab.digest(jar, "sha256"),
                               output_sha256=lab.digest(output, "sha256")))
    env = dict(os.environ, HORIZON_MOB_DRIPSTONE_ORACLE=str(build / "server.json"))
    run(["cargo", "test", "--locked", "-p", "horizon-client", "--no-default-features", "--lib",
         "mobs::living::tests::dripstone_landings_match_vanilla", "--", "--exact", "--ignored", "--nocapture"], "rust", env)
    if "test result: ok. 1 passed;" not in (build / "rust.log").read_text(encoding="utf-8"):
        raise AssertionError("The mob parity test did not execute")
    base.ensure_sources_unchanged(sources, tested)
    lab.write_json(report_path, dict(schema=1, minecraft=lock["minecraft"]["version"],
                   fixtures=len(expected), sources_sha256=tested, references=references,
                   scope="Actual registered PointedDripstoneBlock.fallOn into Pig/LivingEntity fall damage, compared with Mob's landing adapter. All dripstone states, short/long falls, fall immunity, damage type, and passenger event arguments. World/network/audio integration remains separate."))
    print(f"{len(expected)} mob landing cases match both pinned vanilla jars: {report_path}")


if __name__ == "__main__":
    main()
