#!/usr/bin/env python3
"""
Picks which next-word model ships (see README.md): the one whose next-word
top-3 is best on average over the benchmark sentences and the held-out
conversations. Gzips it into the app's assets and writes RESULTS.md.

    python3 tools/nextword/choose.py RESULTS_DIR

RESULTS_DIR holds <variant>.json and <variant>.wlm pairs from train.py, and
may hold current.json: evaluate.py's measurement of the model already in the
app. A new model replaces it only if it does better.
"""
import gzip
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSET = ROOT / "app/src/main/assets/models/next_en.wlmz"
REPORT = ROOT / "tools/nextword/RESULTS.md"


def main():
    d = Path(sys.argv[1])
    results = [json.load(open(f)) for f in sorted(d.glob("*.json"))]
    if not results:
        sys.exit("no results")

    def mean(r):
        return (r["sentences"]["top3"] + r["conversation"]["top3"]) / 2
    # The model already shipped comes first, so it wins ties.
    results.sort(key=lambda r: r["variant"] != "current")
    best = max(results, key=mean)
    if best["variant"] != "current":
        ASSET.parent.mkdir(parents=True, exist_ok=True)
        with open(d / f"{best['variant']}.wlm", "rb") as src, \
                gzip.GzipFile(ASSET, "wb", compresslevel=9, mtime=0) as dst:
            shutil.copyfileobj(src, dst)

    s0 = results[0]["sentences"]
    c0 = results[0]["conversation"]
    lines = [
        "# Next-word model",
        "",
        "Written by the Next-word model workflow (tools/nextword). Top-3 = how often the",
        "next word is among the three predictions, on positions never used for training or",
        "tuning. Sentences = the typing benchmark's held-out Tatoeba sentences; conversation =",
        "Taskmaster-2's sports and music dialogs, which no variant trains on.",
        "",
        f"Dictionary alone: sentences top-3 {s0['dictionary_top3']}% (top-1 {s0['dictionary_top1']}%), "
        f"conversation top-3 {c0['dictionary_top3']}% (top-1 {c0['dictionary_top1']}%).",
        "",
        "| Variant | Sentences top-3 | Sentences top-1 | Conversation top-3 | Conversation top-1 | Perplexity | Weight / offset | File MB | Trained |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        mark = (" (kept)" if r["variant"] == "current" else " (shipped)") if r is best else ""
        name = "already in the app" if r["variant"] == "current" else r["variant"]
        trained = (f"{r['train_minutes']} min, {r['tokens_seen_m']}M words seen of {r['train_tokens_m']}M"
                   if "train_minutes" in r else "earlier")
        lines.append(
            f"| {name}{mark} | {r['sentences']['top3']} | {r['sentences']['top1']} | "
            f"{r['conversation']['top3']} | {r['conversation']['top1']} | {r.get('validation_perplexity', '–')} | "
            f"{r['weight']} / {r['offset']} | {r.get('file_mb', '–')} | {trained} |")
    lines += ["", f"Shipped file: `app/src/main/assets/models/next_en.wlmz` "
              f"({ASSET.stat().st_size / 1e6:.1f} MB gzipped).", "", "## Details", ""]
    lines += [f"- **{r['variant']}**: `{json.dumps(r)}`" for r in results]
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(REPORT.read_text())


if __name__ == "__main__":
    main()
