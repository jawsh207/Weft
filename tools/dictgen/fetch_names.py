#!/usr/bin/env python3
"""
Fetch names and places from Wikidata (CC0) into sources/en.names.tsv, which
build_dict.py adds to the English dictionaries with their capitals.

    python3 tools/dictgen/fetch_names.py

Each kind is ranked by notability (number of Wikipedia sitelinks). The output
is committed so dictionary builds are reproducible; rerun to refresh it.
Columns: label, kind, rank (1 = most notable within its kind).
"""

from __future__ import annotations

import json
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

ENDPOINT = "https://query.wikidata.org/sparql"
AGENT = "weft-dictgen/1.0 (https://github.com/jawsh207/Weft; keyboard dictionary builder)"
OUT = Path(__file__).resolve().parent / "sources" / "en.names.tsv"


def ranked(types: str, limit: int, extra: str = "", min_links: int = 3) -> str:
    """
    Items of any of [types] with an English label, most sitelinks first. The
    sitelinks filter comes before the label join so the public endpoint stays
    well inside its 60-second limit.
    """
    return f"""
SELECT DISTINCT ?label ?n WHERE {{
  VALUES ?type {{ {types} }}
  ?item wdt:P31 ?type ; wikibase:sitelinks ?n .
  FILTER(?n >= {min_links})
  {extra}
  ?item rdfs:label ?label .
  FILTER(LANG(?label) = "en")
}} ORDER BY DESC(?n) LIMIT {limit}"""


QUERIES = {
    # Given names (male, female, unisex) and family names.
    "given": ranked("wd:Q202444 wd:Q12308941 wd:Q11879590 wd:Q3409032", 8000, min_links=2),
    "family": ranked("wd:Q101352", 6000, min_links=8),
    # Countries and their demonyms ("France", "French").
    "country": ranked("wd:Q6256 wd:Q3624078", 400),
    "demonym": """
SELECT DISTINCT ?label ?n WHERE {
  VALUES ?type { wd:Q6256 wd:Q3624078 }
  ?item wdt:P31 ?type ; wikibase:sitelinks ?n ; wdt:P1549 ?label .
  FILTER(LANG(?label) = "en")
} ORDER BY DESC(?n) LIMIT 400""",
    # States, provinces and regions people type often; continents.
    "region": ranked(
        "wd:Q35657 wd:Q11828004 wd:Q9357527 wd:Q5852411 wd:Q3336843 "
        "wd:Q180673 wd:Q5107 wd:Q107390",
        800,
    ),
    # Cities and towns over 50,000 people.
    "place": ranked(
        "wd:Q515 wd:Q1549591 wd:Q1637706 wd:Q200250 wd:Q5119 wd:Q1093829 wd:Q3957 "
        "wd:Q7930989 wd:Q747074",
        6000,
        "?item wdt:P1082 ?pop . FILTER(?pop >= 50000)",
    ),
    # Companies, websites, apps and brands.
    "org": ranked(
        "wd:Q4830453 wd:Q891723 wd:Q6881511 wd:Q35127 wd:Q620615 wd:Q167270 wd:Q431289",
        3000,
        min_links=25,
    ),
}


BUDGET_SECONDS = 20 * 60  # stop starting new queries after this


def query(sparql: str) -> list[tuple[str, int]] | None:
    """Results, or None if the endpoint failed (the server gives up after 60 s)."""
    url = ENDPOINT + "?" + urllib.parse.urlencode({"query": sparql, "format": "json"})
    req = urllib.request.Request(url, headers={"User-Agent": AGENT, "Accept": "application/sparql-results+json"})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=75) as r:
                data = json.load(r)
            return [(b["label"]["value"], int(b["n"]["value"])) for b in data["results"]["bindings"]]
        except Exception as e:  # noqa: BLE001 - retry once, then report
            print(f"  attempt {attempt + 1} failed: {e}", file=sys.stderr)
            time.sleep(15)
    return None


def previous_rows() -> dict[str, list[tuple[str, str, int]]]:
    """The committed file's rows by kind, kept for any kind whose query fails."""
    out: dict[str, list[tuple[str, str, int]]] = {}
    if OUT.exists():
        for line in OUT.read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if line.startswith("#") or len(parts) != 3 or not parts[2].isdigit():
                continue
            out.setdefault(parts[1], []).append((parts[0], parts[1], int(parts[2])))
    return out


def usable(label: str) -> bool:
    label = unicodedata.normalize("NFC", label.strip())
    parts = label.split(" ")
    if not 1 <= len(parts) <= 2:
        return False
    for p in parts:
        if not p or not p[0].isupper() or len(p) > 32:
            return False
        if not all(ch.isalpha() or ch in "'-" or unicodedata.category(ch)[0] == "M" for ch in p):
            return False
    return True


def main() -> None:
    old = previous_rows()
    rows = []
    start = time.monotonic()
    for kind, sparql in QUERIES.items():
        results = None
        if time.monotonic() - start < BUDGET_SECONDS:
            print(f"[{kind}] querying…", file=sys.stderr)
            results = query(sparql)
        if results is None:
            rows += old.get(kind, [])
            print(f"[{kind}] failed or out of time; kept {len(old.get(kind, [])):,} from before", file=sys.stderr)
            continue
        seen = set()
        rank = 0
        for label, _ in results:
            label = unicodedata.normalize("NFC", label.strip())
            if label in seen or not usable(label):
                continue
            seen.add(label)
            rank += 1
            rows.append((label, kind, rank))
        print(f"[{kind}] {rank:,} names", file=sys.stderr)
        time.sleep(2)  # be gentle with the public endpoint
    if not rows:
        sys.exit("No names fetched; nothing written.")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# label\tkind\trank  (from Wikidata, CC0; generated by fetch_names.py)\n")
        for label, kind, rank in rows:
            f.write(f"{label}\t{kind}\t{rank}\n")
    print(f"Wrote {OUT}: {len(rows):,} names", file=sys.stderr)


if __name__ == "__main__":
    main()
