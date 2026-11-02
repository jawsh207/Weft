#!/usr/bin/env python3
"""
Fetch names and places into sources/en.names.tsv, which build_dict.py adds to
the English dictionaries with their capitals: people's names, countries,
demonyms, regions and brands from Wikidata (CC0), and cities from GeoNames
(CC BY 4.0).

    python3 tools/dictgen/fetch_names.py

Each kind is ranked by notability (number of Wikipedia sitelinks). The output
is committed so dictionary builds are reproducible; rerun to refresh it.
Columns: label, kind, rank (1 = most notable within its kind).
"""

from __future__ import annotations

import io
import json
import sys
import time
import zipfile
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

ENDPOINT = "https://query.wikidata.org/sparql"
AGENT = "weft-dictgen/1.0 (https://github.com/jawsh207/Weft; keyboard dictionary builder)"
OUT = Path(__file__).resolve().parent / "sources" / "en.names.tsv"


def ranked(types: str, limit: int, extra: str = "", min_links: int = 3) -> str:
    """
    Items of any of [types], most sitelinks first, with their English labels.
    The inner query picks the items without touching labels (name items have
    hundreds, one per script), and the label service then looks up only the
    English label of those few thousand, which keeps the public endpoint well
    inside its 60-second limit.
    """
    return f"""
SELECT ?label ?n WHERE {{
  {{
    SELECT ?item (MAX(?links) AS ?n) WHERE {{
      VALUES ?type {{ {types} }}
      ?item wdt:P31 ?type ; wikibase:sitelinks ?links .
      FILTER(?links >= {min_links})
      {extra}
    }} GROUP BY ?item ORDER BY DESC(?n) LIMIT {limit}
  }}
  SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en" . ?item rdfs:label ?label . }}
}} ORDER BY DESC(?n)"""


QUERIES = {
    # Given names (male, female, unisex) and family names.
    # One query per kind of given name keeps each well inside the time limit.
    "given": [
        ranked("wd:Q12308941", 4000, min_links=3),   # male given names
        ranked("wd:Q11879590", 4000, min_links=3),   # female given names
        ranked("wd:Q3409032 wd:Q202444", 2000, min_links=3),  # unisex, plain "given name"
    ],
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
    # Companies, websites, apps and brands.
    "org": ranked(
        "wd:Q4830453 wd:Q891723 wd:Q6881511 wd:Q35127 wd:Q620615 wd:Q167270 wd:Q431289",
        3000,
        min_links=25,
    ),
}


BUDGET_SECONDS = 20 * 60  # stop starting new queries after this


GEONAMES = "https://download.geonames.org/export/dump/cities15000.zip"
MIN_POPULATION = 50_000


def geonames_cities() -> list[tuple[str, int]] | None:
    """Cities over MIN_POPULATION from GeoNames, by population (CC BY 4.0)."""
    req = urllib.request.Request(GEONAMES, headers={"User-Agent": AGENT})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
    except Exception as e:  # noqa: BLE001
        warn(f"GeoNames download failed: {e}")
        return None
    out = []
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        with z.open("cities15000.txt") as f:
            for line in io.TextIOWrapper(f, encoding="utf-8"):
                cols = line.rstrip("\n").split("\t")
                if len(cols) > 14 and cols[14].isdigit() and int(cols[14]) >= MIN_POPULATION:
                    out.append((cols[1], int(cols[14])))
    out.sort(key=lambda x: -x[1])
    return out


def warn(message: str) -> None:
    """Shows on the GitHub Actions run page (raw logs aren't always at hand)."""
    print(f"::warning title=fetch_names::{message}")
    print(message, file=sys.stderr)


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
            warn(f"attempt {attempt + 1} failed: {e}")
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
    kinds = dict(QUERIES, place="geonames")
    for kind, sparql in kinds.items():
        results = None
        if time.monotonic() - start < BUDGET_SECONDS:
            t = time.monotonic()
            print(f"[{kind}] querying…", file=sys.stderr)
            if sparql == "geonames":
                results = geonames_cities()
            elif isinstance(sparql, list):
                parts = [query(q) for q in sparql]
                ok = [p for p in parts if p is not None]
                results = sorted((x for p in ok for x in p), key=lambda x: -x[1]) if ok else None
            else:
                results = query(sparql)
            print(f"::notice title=fetch_names::{kind}: {'failed' if results is None else len(results)} "
                  f"in {time.monotonic() - t:.0f}s")
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
        f.write("# label\tkind\trank  (generated by fetch_names.py)\n")
        f.write("# source\tWikidata (names, countries, regions, brands)\tCC0-1.0\thttps://www.wikidata.org\n")
        f.write("# source\tGeoNames (cities)\tCC-BY-4.0\thttps://www.geonames.org\n")
        for label, kind, rank in rows:
            f.write(f"{label}\t{kind}\t{rank}\n")
    print(f"Wrote {OUT}: {len(rows):,} names", file=sys.stderr)


if __name__ == "__main__":
    main()
