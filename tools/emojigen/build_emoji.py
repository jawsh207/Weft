#!/usr/bin/env python3
"""
Build Weft's emoji list from Unicode data.

    python3 tools/emojigen/build_emoji.py          # download everything (CI)
    python3 tools/emojigen/build_emoji.py --input emoji-test.txt \\
        --annotations en.xml --annotations en-derived.xml --no-license

Writes app/src/main/assets/emoji/emoji.txt and, next to it, Unicode's license
text (LICENSE-Unicode.txt), which must ship with the data.

Sources, all under the Unicode License v3 (Unicode-3.0, OSI approved):
  - emoji-test.txt: every emoji, its group, name and skin-tone forms
  - CLDR annotations (en.xml, annotationsDerived/en.xml): search keywords
Plus Weft's own extra_keywords.tsv (GPL-3.0) for texting slang like "lol".

Only the list is bundled; the emoji are drawn with the phone's own font.

Output format (UTF-8, tab separated):

    # comment lines
    @Group name
    <emoji>\t<name>\t<skin variants, space separated>\t<keywords, " | " separated>

Rules:
- Only fully-qualified emoji are kept (the forms keyboards should insert).
- The "Component" group (bare skin-tone swatches, hair parts) is skipped.
- Skin-tone forms are folded into their base emoji as variants. Only
  variants that use one tone throughout are kept (5 per emoji).
- Flags also get their two-letter region code as a keyword ("us" → 🇺🇸).
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT_DIR = REPO / "app" / "src" / "main" / "assets" / "emoji"
EXTRA_KEYWORDS = HERE / "extra_keywords.tsv"

EMOJI_TEST_URL = "https://unicode.org/Public/emoji/latest/emoji-test.txt"
CLDR = "https://raw.githubusercontent.com/unicode-org/cldr/main/common"
ANNOTATION_URLS = [f"{CLDR}/annotations/en.xml", f"{CLDR}/annotationsDerived/en.xml"]
LICENSE_URL = "https://www.unicode.org/license.txt"

SKIN_TONES = set(range(0x1F3FB, 0x1F400))
VS16 = 0xFE0F
REGIONAL_A = 0x1F1E6
SKIP_GROUPS = {"Component"}

LINE = re.compile(r"^([0-9A-F ]+?)\s*;\s*([a-z-]+)\s*#\s*(\S+)\s+E[0-9.]+\s+(.+)$")


def fetch(url: str) -> str:
    print(f"Downloading {url}", file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "weft-emojigen"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read().decode("utf-8")


def key_of(text_or_cps) -> tuple[int, ...]:
    """Identity of an emoji ignoring skin tone and the emoji-style selector."""
    cps = [ord(c) for c in text_or_cps] if isinstance(text_or_cps, str) else text_or_cps
    return tuple(c for c in cps if c not in SKIN_TONES and c != VS16)


def parse_emoji_test(text: str):
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
        tones = {c for c in cps if c in SKIN_TONES}
        if tones:
            base = by_key.get(key_of(cps))
            if base is not None and len(tones) == 1:
                base["variants"].append(emoji)
            continue
        entry = {"emoji": emoji, "name": m.group(4).strip(), "variants": [], "keywords": []}
        by_key.setdefault(key_of(cps), entry)
        group[1].append(entry)
    return version, groups, by_key


def parse_annotations(xml_text: str) -> dict[tuple[int, ...], list[str]]:
    """CLDR keywords per emoji (the "tts" entries are names, which we already have)."""
    out: dict[tuple[int, ...], list[str]] = {}
    root = ET.fromstring(xml_text)
    for a in root.iter("annotation"):
        if a.get("type") == "tts" or not a.text:
            continue
        words = [w.strip() for w in a.text.split("|") if w.strip()]
        out.setdefault(key_of(a.get("cp", "")), []).extend(words)
    return out


def read_extra_keywords(path: Path) -> dict[tuple[int, ...], list[str]]:
    out: dict[tuple[int, ...], list[str]] = {}
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        keyword, _, emoji = line.partition("\t")
        for e in emoji.split():
            out.setdefault(key_of(e), []).append(keyword.strip())
    return out


def region_code(emoji: str) -> str | None:
    cps = [ord(c) for c in emoji]
    if len(cps) == 2 and all(REGIONAL_A <= c <= REGIONAL_A + 25 for c in cps):
        return "".join(chr(ord("a") + c - REGIONAL_A) for c in cps)
    return None


def add_keywords(by_key, keyword_maps) -> int:
    added = 0
    for kmap in keyword_maps:
        for key, words in kmap.items():
            entry = by_key.get(key)
            if entry is None:
                continue
            for w in words:
                w = w.replace("\t", " ").strip()
                if w and w.lower() != entry["name"].lower() and w not in entry["keywords"]:
                    entry["keywords"].append(w)
                    added += 1
    for entry in by_key.values():
        code = region_code(entry["emoji"])
        if code and code not in entry["keywords"]:
            entry["keywords"].append(code)
            added += 1
    return added


def render(version: str, groups, sources: list[str]) -> str:
    out = [
        f"# Weft emoji list. Unicode emoji {version or 'unknown'}.",
        "# Data: " + "; ".join(sources),
        "# Unicode data is under the Unicode License v3 (Unicode-3.0); see LICENSE-Unicode.txt.",
        "# Regenerate with tools/emojigen/build_emoji.py; do not edit by hand.",
    ]
    for name, entries in groups:
        out.append(f"@{name}")
        for e in entries:
            out.append("\t".join([e["emoji"], e["name"], " ".join(e["variants"]), " | ".join(e["keywords"])]))
    return "\n".join(out) + "\n"


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", help="Local emoji-test.txt (default: download latest)")
    p.add_argument("--annotations", action="append",
                   help="Local CLDR annotations XML (repeatable; default: download en + derived)")
    p.add_argument("--no-annotations", action="store_true", help="Skip CLDR keywords")
    p.add_argument("--extra-keywords", default=str(EXTRA_KEYWORDS))
    p.add_argument("--out", default=str(OUT_DIR / "emoji.txt"))
    p.add_argument("--no-license", action="store_true", help="Don't download Unicode's license text")
    args = p.parse_args()

    text = Path(args.input).read_text(encoding="utf-8") if args.input else fetch(EMOJI_TEST_URL)
    version, groups, by_key = parse_emoji_test(text)
    if not groups:
        sys.exit("No emoji found; is this an emoji-test.txt file?")
    sources = ["Unicode emoji-test.txt"]

    keyword_maps = []
    if not args.no_annotations:
        xmls = [Path(a).read_text(encoding="utf-8") for a in args.annotations] if args.annotations \
            else [fetch(u) for u in ANNOTATION_URLS]
        keyword_maps += [parse_annotations(x) for x in xmls]
        sources.append("Unicode CLDR annotations (en)")
    keyword_maps.append(read_extra_keywords(Path(args.extra_keywords)))
    sources.append("Weft extra_keywords.tsv")
    keywords = add_keywords(by_key, keyword_maps)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render(version, groups, sources), encoding="utf-8")
    if not args.no_license:
        (out.parent / "LICENSE-Unicode.txt").write_text(fetch(LICENSE_URL), encoding="utf-8")

    total = sum(len(e) for _, e in groups)
    variants = sum(len(x["variants"]) for _, e in groups for x in e)
    print(f"Wrote {out}: Unicode {version}, {len(groups)} groups, {total:,} emoji, "
          f"{variants:,} skin-tone variants, {keywords:,} keywords", file=sys.stderr)


if __name__ == "__main__":
    main()
