#!/usr/bin/env python3
"""
Experiment (see README.md): gathers every model's RESULT.json into one table,
tools/lmexp/RESULTS.md, sorted by size.

    python3 tools/lmexp/report.py results/*.json
"""
import json
import sys


def main():
    results = [json.load(open(p)) for p in sys.argv[1:]]
    results.sort(key=lambda r: (r.get("onnx_int8_mb") or r.get("params_m") or 0))
    base = results[0]["baseline"] if results else {}
    lines = [
        "# Next-word models compared",
        "",
        "Same benchmark positions for every model (held-out Tatoeba sentences, a third of the",
        "benchmark, split into a tuning half and a test half; numbers are on the test half).",
        "",
        f"- Today's dictionary alone: **top-3 {base.get('top3')}%**, top-1 {base.get('top1')}%",
        f"- Ceiling for re-ranking: the right word is among the dictionary's 30 candidates "
        f"{results[0].get('ceiling_top30') if results else '?'}% of the time",
        f"- Test positions: {results[0].get('test_positions') if results else '?'}",
        "",
        "Re-rank = the model reorders the dictionary's candidates. Open = it may also add its",
        "own guesses (dictionary words only). Speed is one CPU thread on the CI machine (x86),",
        "context plus all candidates; a phone is likely 2–4× slower.",
        "",
        "| Model | Params (M) | Size int8 (MB) | ms / prediction | Re-rank top-3 | Open top-3 | Open top-1 | License |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        size = r.get("onnx_int8_mb")
        size = f"{size}" if size is not None else "—"
        if "estimate" in r.get("size_note", ""):
            size += " (est.)"
        lines.append(
            f"| {r['model']} | {r.get('params_m')} | {size} | {r.get('ms_per_prediction')} | "
            f"{r['rerank']['top3']} | {r.get('open', {}).get('top3', '—')} | {r.get('open', {}).get('top1', '—')} | "
            f"{r.get('license')} |")
    lines += ["", "## Details", ""]
    for r in results:
        lines.append(f"- **{r['model']}**: " + ", ".join(
            f"{k} {v}" for k, v in r.items() if k not in ("model", "baseline", "ceiling_top30", "test_positions")))
    open("tools/lmexp/RESULTS.md", "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
