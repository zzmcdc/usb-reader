#!/usr/bin/env python3
"""Fetch pinned source; never reset or overwrite an existing checkout."""
import json
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
dependencies = json.loads((root / "third_party/dependencies.json").read_text())
for name, spec in dependencies.items():
    if spec.get("test_only") and "--tests" not in sys.argv:
        continue
    dest = root / "third_party" / name
    if dest.exists() and any(dest.iterdir()):
        if (dest / ".git").exists():
            actual = subprocess.check_output(["git", "-C", str(dest), "rev-parse", "HEAD"], text=True).strip()
            if actual != spec["revision"]:
                raise SystemExit(f"{name}: existing revision {actual} differs; checkout left untouched")
        continue  # A source distribution may intentionally omit .git.
    subprocess.run(["git", "init", str(dest)], check=True)
    subprocess.run(["git", "-C", str(dest), "remote", "add", "origin", spec["url"]], check=True)
    subprocess.run(["git", "-C", str(dest), "fetch", "--depth", "1", "origin", spec["revision"]], check=True)
    subprocess.run(["git", "-C", str(dest), "checkout", "--detach", "FETCH_HEAD"], check=True)
print("Pinned dependencies ready")
