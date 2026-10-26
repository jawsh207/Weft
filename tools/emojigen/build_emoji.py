#!/usr/bin/env python3
"""
Build Weft's emoji list from Unicode data.

    python3 tools/emojigen/build_emoji.py          # download everything (CI)
    python3 tools/emojigen/build_emoji.py --input emoji-test.txt \\
        --annotations en.xml --annotations en-derived.xml --no-license

Writes app/src/main/assets/emoji/emoji.txt and, next to it, the license text
of every source (LICENSE-*.txt), which their licenses require to ship with it.

Sources:
  - Unicode emoji-test.txt: every emoji, its group, name and skin-tone forms
    (Unicode License v3)
  - Unicode CLDR annotations (en.xml, annotationsDerived/en.xml): official
    keywords, available in ~100 languages (Unicode License v3)
  - emojilib (npm): community keywords in everyday English (MIT)
  - GitHub gemoji (db/emoji.json): shortcodes like "thumbsup", "joy" (MIT)
  - Weft's own extra_keywords.tsv (GPL-3.0): ranking for common searches

Only the list is bundled; the emoji are drawn with the phone's own font.

Output format (UTF-8, tab separated):

    # comment lines
    @Group name
    <emoji>\t<name>\t<skin variants, space separated>\t<keywords, " | " separated>\t<picks>

<picks> are Weft's hand-picked keywords and gemoji shortcodes, as
"keyword#rank" (" | " separated): the emoji is that keyword's rank-th best
match, and ranks above name and CLDR matches.

Rules:
- Only fully-qualified emoji are kept (the forms keyboards should insert).
- The "Component" group (bare skin-tone swatches, hair parts) is skipped.
- Skin-tone forms are folded into their base emoji as variants. Only
  variants that use one tone throughout are kept (5 per emoji).
- Flags also get their two-letter region code as a keyword ("us" → 🇺🇸).
"""

from __future__ import annotations

import argparse
import io
import json
import re
import tarfile
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
EMOJILIB_REGISTRY = "https://registry.npmjs.org/emojilib/latest"
GEMOJI = "https://raw.githubusercontent.com/github/gemoji/master"
GEMOJI_URL = f"{GEMOJI}/db/emoji.json"
GEMOJI_LICENSE_URL = f"{GEMOJI}/LICENSE"

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
        entry = {"emoji": emoji, "name": m.group(4).strip(), "variants": [], "keywords": [], "picks": []}
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


def fetch_emojilib() -> tuple[str, str, str]:
    """Download the latest emojilib npm package: (keywords json, license, version)."""
    meta = json.loads(fetch(EMOJILIB_REGISTRY))
    with urllib.request.urlopen(meta["dist"]["tarball"], timeout=120) as r:
        data = r.read()
    keywords_json = license_text = None
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        for m in tar.getmembers():
            name = m.name.lower()
            if name.endswith("emoji-en-us.json") and keywords_json is None:
                keywords_json = tar.extractfile(m).read().decode("utf-8")
            elif name.rsplit("/", 1)[-1] in ("license", "license.md", "license.txt"):
                license_text = tar.extractfile(m).read().decode("utf-8")
    if keywords_json is None:
        sys.exit("emojilib package has no emoji-en-US.json; its layout may have changed")
    return keywords_json, license_text or "MIT License (see https://github.com/muan/emojilib)", meta.get("version", "")


def parse_emojilib(text: str) -> dict[tuple[int, ...], list[str]]:
    """emojilib: {"😀": ["grinning_face", "face", "smile", ...]}; underscores become spaces."""
    out: dict[tuple[int, ...], list[str]] = {}
    for emoji, words in json.loads(text).items():
        out.setdefault(key_of(emoji), []).extend(w.replace("_", " ") for w in words if isinstance(w, str))
    return out


def parse_gemoji(text: str):
    """gemoji db/emoji.json: [{"emoji": "👍", "aliases": ["+1", "thumbsup"], "tags": [...]}].

    Returns (tags as keywords, aliases as picks). A shortcode names exactly one
    emoji, so it ranks like a hand-picked keyword.
    """
    tags: dict[tuple[int, ...], list[str]] = {}
    shortcodes: dict[tuple[int, ...], list[str]] = {}
    for entry in json.loads(text):
        emoji = entry.get("emoji")
        if not emoji:
            continue
        k = key_of(emoji)
        tags.setdefault(k, []).extend(t.replace("_", " ") for t in entry.get("tags", []))
        shortcodes.setdefault(k, []).extend(f"{a.lower()}#0" for a in entry.get("aliases", []))
    return tags, shortcodes


def read_extra_keywords(path: Path) -> dict[tuple[int, ...], list[str]]:
    """Weft's hand-picked keywords, as "keyword#rank" (rank = position in the line)."""
    out: dict[tuple[int, ...], list[str]] = {}
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        keyword, _, emoji = line.partition("\t")
        for rank, e in enumerate(emoji.split()):
            out.setdefault(key_of(e), []).append(f"{keyword.strip().lower()}#{rank}")
    return out


def region_code(emoji: str) -> str | None:
    cps = [ord(c) for c in emoji]
    if len(cps) == 2 and all(REGIONAL_A <= c <= REGIONAL_A + 25 for c in cps):
        return "".join(chr(ord("a") + c - REGIONAL_A) for c in cps)
    return None


def add_picks(by_key, picks) -> int:
    added = 0
    for key, words in picks.items():
        entry = by_key.get(key)
        if entry is not None:
            entry["picks"].extend(w for w in words if w not in entry["picks"])
            added += len(words)
    return added


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
        "# Licenses: Unicode data under Unicode-3.0, emojilib and gemoji under MIT; see LICENSE-*.txt.",
        "# Regenerate with tools/emojigen/build_emoji.py; do not edit by hand.",
    ]
    for name, entries in groups:
        out.append(f"@{name}")
        for e in entries:
            out.append("\t".join([
                e["emoji"], e["name"], " ".join(e["variants"]),
                " | ".join(e["keywords"]), " | ".join(e["picks"]),
            ]))
    return "\n".join(out) + "\n"


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", help="Local emoji-test.txt (default: download latest)")
    p.add_argument("--annotations", action="append",
                   help="Local CLDR annotations XML (repeatable; default: download en + derived)")
    p.add_argument("--no-annotations", action="store_true", help="Skip CLDR keywords")
    p.add_argument("--emojilib", help="Local emojilib emoji-en-US.json (default: download)")
    p.add_argument("--gemoji", help="Local gemoji db/emoji.json (default: download)")
    p.add_argument("--no-community", action="store_true", help="Skip emojilib and gemoji")
    p.add_argument("--extra-keywords", default=str(EXTRA_KEYWORDS))
    p.add_argument("--out", default=str(OUT_DIR / "emoji.txt"))
    p.add_argument("--no-license", action="store_true", help="Don't download license texts")
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

    licenses = {}
    if not args.no_community:
        if args.emojilib:
            emojilib_json = Path(args.emojilib).read_text(encoding="utf-8")
            sources.append("emojilib")
        else:
            emojilib_json, licenses["emojilib"], version_lib = fetch_emojilib()
            sources.append(f"emojilib {version_lib}".strip())
        keyword_maps.append(parse_emojilib(emojilib_json))
        if args.gemoji:
            gemoji_json = Path(args.gemoji).read_text(encoding="utf-8")
        else:
            gemoji_json = fetch(GEMOJI_URL)
            licenses["gemoji"] = fetch(GEMOJI_LICENSE_URL)
        gemoji_tags, gemoji_shortcodes = parse_gemoji(gemoji_json)
        keyword_maps.append(gemoji_tags)
        sources.append("GitHub gemoji")
    keywords = add_keywords(by_key, keyword_maps)
    keywords += add_picks(by_key, read_extra_keywords(Path(args.extra_keywords)))
    if not args.no_community:
        keywords += add_picks(by_key, gemoji_shortcodes)
    sources.append("Weft extra_keywords.tsv")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render(version, groups, sources), encoding="utf-8")
    if not args.no_license:
        licenses["Unicode"] = fetch(LICENSE_URL)
        for name, text in licenses.items():
            (out.parent / f"LICENSE-{name}.txt").write_text(text, encoding="utf-8")

    total = sum(len(e) for _, e in groups)
    variants = sum(len(x["variants"]) for _, e in groups for x in e)
    print(f"Wrote {out}: Unicode {version}, {len(groups)} groups, {total:,} emoji, "
          f"{variants:,} skin-tone variants, {keywords:,} keywords", file=sys.stderr)


if __name__ == "__main__":
    main()
