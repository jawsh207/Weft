#!/usr/bin/env python3
"""
One-step dictionary build: download the sources listed in sources/<locale>.json
into cache/<locale>/, then run build_dict.py and write the result into the app's
assets.

    python3 tools/dictgen/make_dict.py en_US

Downloads are cached; delete cache/<locale>/ to refetch. Sources marked
"optional" are skipped with a warning if they can't be fetched.
"""

from __future__ import annotations

import base64
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ASSETS = REPO / "app" / "src" / "main" / "assets" / "dictionaries"


def fetch(name: str, spec: dict, cache: Path) -> Path | None:
    dest = cache / spec["file"]
    if dest.exists() and dest.stat().st_size > 0:
        print(f"[{name}] cached: {dest.name}")
        return dest
    for p in spec.get("fallbackPaths", []):
        if spec.get("url") is None and Path(p).exists():
            print(f"[{name}] using {p}")
            return Path(p)
    if spec.get("urls"):
        # Several files concatenated into one (e.g. Common Voice's sentence lists).
        parts = []
        for u in spec["urls"]:
            print(f"[{name}] downloading {u}")
            try:
                req = urllib.request.Request(u, headers={"User-Agent": "weft-dictgen"})
                with urllib.request.urlopen(req, timeout=120) as r:
                    parts.append(r.read().decode("utf-8", "replace").rstrip("\n") + "\n")
            except Exception as e:  # noqa: BLE001 - one missing file shouldn't sink the source
                print(f"[{name}] FAILED: {e}")
        if not parts:
            return None
        dest.write_text("".join(parts), encoding="utf-8")
        print(f"[{name}] saved {dest.stat().st_size / 1e6:.1f} MB from {len(parts)} files")
        return dest
    url = spec.get("url")
    if not url:
        print(f"[{name}] not downloadable: {spec.get('manual', 'add it manually')}")
        return None
    print(f"[{name}] downloading {url}")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "weft-dictgen"})
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
        if spec.get("decode") == "base64":
            data = base64.b64decode(data)
        dest.write_bytes(data)
        print(f"[{name}] saved {len(data) / 1e6:.1f} MB")
        return dest
    except Exception as e:  # noqa: BLE001 - report and continue
        print(f"[{name}] FAILED: {e}")
        if spec.get("fallback"):
            print(f"[{name}] try: {spec['fallback']}")
        for p in spec.get("fallbackPaths", []):
            if Path(p).exists():
                print(f"[{name}] using {p}")
                return Path(p)
        return None


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    locale = sys.argv[1]
    config = json.loads((HERE / "sources" / f"{locale}.json").read_text())
    cache = HERE / "cache" / locale
    cache.mkdir(parents=True, exist_ok=True)

    paths = {}
    for name, spec in config["sources"].items():
        path = fetch(name, spec, cache)
        if path is None and not spec.get("optional"):
            sys.exit(f"Required source '{name}' is missing; see message above.")
        paths[name] = path

    cmd = [sys.executable, str(HERE / "build_dict.py"),
           "--locale", locale,
           "--description", config.get("description", locale),
           "--out", str(ASSETS / f"main_{locale}.ekdz")]
    if paths.get("aosp"):
        cmd += ["--aosp", str(paths["aosp"])]
    if paths.get("subtitles"):
        cmd += ["--subtitles", str(paths["subtitles"])]
    elif config.get("casualFromSentences"):
        cmd += ["--casual-from-sentences"]
        spoken = HERE / "sources" / f"{locale}.spoken.txt"
        if spoken.exists():
            cmd += ["--spoken", str(spoken)]
    if paths.get("leipzig"):
        cmd += ["--leipzig", str(paths["leipzig"])]
    if paths.get("allow"):
        cmd += ["--allow", str(paths["allow"])]
    if paths.get("tatoeba"):
        cmd += ["--tatoeba", str(paths["tatoeba"])]
    if paths.get("commonvoice"):
        cmd += ["--sentences", f"Common Voice sentences|CC0-1.0|https://commonvoice.mozilla.org|{paths['commonvoice']}"]
    names = HERE / "sources" / config.get("names", f"{locale}.names.tsv")
    if names.exists():
        cmd += ["--names", str(names)]
    variants = config.get("variants")
    if variants:
        cmd += ["--variants", str(HERE / "sources" / variants["file"])]
        for region, out in variants["outputs"].items():
            cmd += ["--variant-out", f"{region}={ASSETS / out}"]
    casual = HERE / "sources" / f"{locale}.casual.txt"
    if casual.exists():
        cmd += ["--casual", str(casual)]
    block = HERE / "sources" / f"{locale}.block.txt"
    if block.exists():
        cmd += ["--block", str(block)]
    cmd += sys.argv[2:]  # pass-through extra options, e.g. --max-words 120000

    print("Running:", " ".join(cmd))
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()
