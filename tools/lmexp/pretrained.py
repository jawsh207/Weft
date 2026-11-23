#!/usr/bin/env python3
"""
Experiment (see README.md): measures a small pretrained language model from
Hugging Face on the benchmark's next-word positions: re-ranking the
dictionary's candidates, adding its own one-piece word guesses ("open"), its
size as an int8 ONNX file (plus tokenizer), and its speed.

A candidate's score is the model's log-probability of the whole word (all its
word pieces) after the sentence so far. The sentence is read once and its
state reused for every candidate.

    python3 tools/lmexp/pretrained.py MODEL_ID dump.jsonl RESULT.json
"""
import argparse
import glob
import json
import os
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402


def repeat_cache(pkv, n):
    """The context's cached state, copied for [n] candidates."""
    if hasattr(pkv, "batch_repeat_interleave"):
        pkv.batch_repeat_interleave(n)
        return pkv
    return tuple(tuple(t.repeat_interleave(n, dim=0) for t in layer) for layer in pkv)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("model")
    p.add_argument("dump")
    p.add_argument("result")
    a = p.parse_args()
    from transformers import AutoModelForCausalLM, AutoTokenizer
    torch.set_num_threads(os.cpu_count() or 4)
    log = lambda m: print(f"[{a.model}] {m}", flush=True)  # noqa: E731

    tok = AutoTokenizer.from_pretrained(a.model)
    model = AutoModelForCausalLM.from_pretrained(a.model, torch_dtype=torch.float32).eval()
    params = sum(p.numel() for p in model.parameters())
    log(f"parameters: {params:,}")
    start = tok.bos_token_id if tok.bos_token_id is not None else tok.eos_token_id
    pad = tok.eos_token_id if tok.eos_token_id is not None else 0
    rows = common.load_dump(a.dump)
    dict_words = common.load_words(a.dump + ".words.tsv")

    def split(prefix):
        return (prefix[:-1], " ") if prefix.endswith(" ") else (prefix, "")

    @torch.no_grad()
    def score(r, words):
        """Log-probability of each word in [words] after r's prefix, and the model's last-step log-probs."""
        ctx_text, space = split(r["prefix"])
        ctx = [start] + tok(ctx_text, add_special_tokens=False)["input_ids"]
        out = model(torch.tensor([ctx]), use_cache=True)
        first = torch.log_softmax(out.logits[0, -1], -1)
        if not words:
            return [], first
        pieces = [tok(space + w, add_special_tokens=False)["input_ids"] or [pad] for w in words]
        width = max(len(x) for x in pieces)
        ids = torch.tensor([x + [pad] * (width - len(x)) for x in pieces])
        pkv = repeat_cache(out.past_key_values, len(words))
        mask = torch.ones(len(words), len(ctx) + width, dtype=torch.long)
        more = torch.log_softmax(model(ids, past_key_values=pkv, attention_mask=mask).logits, -1)
        result = []
        for k, x in enumerate(pieces):
            lp = float(first[x[0]])
            for j in range(1, len(x)):
                lp += float(more[k, j - 1, x[j]])
            result.append(lp)
        return result, first

    scores, extra = [], []
    for n, r in enumerate(rows):
        s, first = score(r, [c[0] for c in r["cands"]])
        scores.append(s)
        # Its own guesses: the likeliest next pieces that are whole dictionary words.
        own = []
        top = torch.topk(first, 200)
        for v, i in zip(top.values.tolist(), top.indices.tolist()):
            piece = tok.decode([i])
            w = piece.strip().lower()
            if piece.startswith(" ") == r["prefix"].endswith(" ") and w.isalpha() and w in dict_words:
                own.append((dict_words[w], v))
            if len(own) >= 20:
                break
        extra.append(own)
        if n % 500 == 0:
            log(f"{n}/{len(rows)} positions")
    result = common.evaluate(rows, scores, extra)

    torch.set_num_threads(1)
    ms = common.timed(lambda r: score(r, [c[0] for c in r["cands"]]), rows, limit=60)
    size_mb, note = onnx_size(a.model)
    license_ = "unknown"
    try:
        from huggingface_hub import model_info
        license_ = (model_info(a.model).card_data or {}).get("license", "unknown")
    except Exception as e:  # noqa: BLE001
        log(f"license lookup failed: {e}")
    result.update({
        "model": a.model,
        "params_m": round(params / 1e6, 1),
        "onnx_int8_mb": size_mb,
        "size_note": note,
        "ms_per_prediction": ms,
        "license": license_,
    })
    json.dump(result, open(a.result, "w"), indent=1)
    log(json.dumps(result))


def onnx_size(model_id):
    """Exports [model_id] to ONNX with Optimum, quantizes it to int8, returns MB (model + tokenizer)."""
    try:
        from optimum.exporters.onnx import main_export
        from onnxruntime.quantization import QuantType, quantize_dynamic
        out = Path("onnx_" + model_id.replace("/", "_"))
        main_export(model_id, output=out, task="text-generation-with-past")
        src = out / "model.onnx"
        if not src.exists():
            src = out / "decoder_model_merged.onnx"
        dst = out / "model.int8.onnx"
        quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8, use_external_data_format=False)
        size = os.path.getsize(dst) + sum(os.path.getsize(f) for f in glob.glob(str(out / "tokenizer.json")))
        return round(size / 1e6, 1), "ONNX int8 file + tokenizer"
    except Exception as e:  # noqa: BLE001
        print(f"ONNX export failed ({e}); estimating", flush=True)
        return None, f"export failed: {str(e)[:120]}"


if __name__ == "__main__":
    main()
