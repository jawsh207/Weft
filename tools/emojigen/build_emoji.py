#!/usr/bin/env python3
"""
Build Weft's emoji list from Unicode's emoji-test.txt.

    python3 tools/emojigen/build_emoji.py                 # download "latest"
    python3 tools/emojigen/build_emoji.py --input FILE    # use a local copy

Writes app/src/main/assets/emoji/emoji.txt. Only the list (order, groups,
names, skin-tone variants) comes from Unicode; the emoji are drawn with the
phone's own emoji font. Unicode data is under the Unicode License v3.

Output format (UTF-8, tab separated):

    # comment lines
    @Group name
    <emoji>\t<name>\t<space-separated single-tone skin variants, may be empty>

Rules:
- Only fully-qualified emoji are kept (the forms keyboards should insert).
- The "Component" group (bare skin-tone swatches, hair parts) is skipped.
- Skin-tone forms are folded into their base emoji as variants. Only
  variants that use one tone throughout are kept (5 per emoji); mixed-tone
  pairs would make popups enormous.
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT = REPO / "app" / "src" / "main" / "assets" / "emoji" / "emoji.txt"
URL = "https://unicode.org/Public/emoji/latest/emoji-test.txt"

SKIN_TONES = set(range(0x1F3FB, 0x1F400))
VS16 = 0xFE0F
SKIP_GROUPS = {"Component"}

LINE = re.compile(r"^([0-9A-F ]+?)\s*;\s*([a-z-]+)\s*#\s*(\S+)\s+E[0-9.]+\s+(.+)$")


def key_of(cps: list[int]) -> tuple[int, ...]:
    """Identity of an emoji ignoring skin tone and the emoji-style selector."""
    return tuple(c for c in cps if c not in SKIN_TONES and c != VS16)


def parse(text: str):
    version = ""
    groups: list[tuple[str, list[dict]]] = []
    by_key: dict[tuple[int, ...], dict] = {}
    group = None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("# Version:"):
            version = line.split(":", 1)[1].strip()
        if line.startswith("# group:"):
            name = line.split(":", 1)[1].strip()
            group = None if name in SKIP_GROUPS else (name, [])
            if group:
                groups.append(group)
            continue
        if not line or line.startswith("#") or group is None:
            continue
        m = LINE.match(line)
        if not m or m.group(2) != "fully-qualified":
            continue
        cps = [int(c, 16) for c in m.group(1).split()]
        emoji = "".join(chr(c) for c in cps)
        name = m.group(4).strip()
        tones = {c for c in cps if c in SKIN_TONES}
        if tones:
            base = by_key.get(key_of(cps))
            if base is not None and len(tones) == 1:
                base["variants"].append(emoji)
            continue
        entry = {"emoji": emoji, "name": name, "variants": []}
        by_key.setdefault(key_of(cps), entry)
        group[1].append(entry)
    return version, groups


def render(version: str, groups) -> str:
    out = [
        f"# Weft emoji list, generated from Unicode emoji-test.txt (version {version or 'unknown'}).",
        "# Source: https://unicode.org/Public/emoji/ - Unicode License v3 (Unicode-3.0).",
        "# Regenerate with tools/emojigen/build_emoji.py; do not edit by hand.",
    ]
    for name, entries in groups:
        out.append(f"@{name}")
        for e in entries:
            out.append(f"{e['emoji']}\t{e['name']}\t{' '.join(e['variants'])}")
    return "\n".join(out) + "\n"


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", help="Local emoji-test.txt (default: download latest)")
    p.add_argument("--out", default=str(OUT))
    args = p.parse_args()

    if args.input:
        text = Path(args.input).read_text(encoding="utf-8")
    else:
        print(f"Downloading {URL}", file=sys.stderr)
        req = urllib.request.Request(URL, headers={"User-Agent": "weft-emojigen"})
        with urllib.request.urlopen(req, timeout=120) as r:
            text = r.read().decode("utf-8")

    version, groups = parse(text)
    if not groups:
        sys.exit("No emoji found; is this an emoji-test.txt file?")
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render(version, groups), encoding="utf-8")
    total = sum(len(e) for _, e in groups)
    variants = sum(len(x["variants"]) for _, e in groups for x in e)
    print(f"Wrote {out}: Unicode {version}, {len(groups)} groups, {total:,} emoji, "
          f"{variants:,} skin-tone variants", file=sys.stderr)


if __name__ == "__main__":
    main()
