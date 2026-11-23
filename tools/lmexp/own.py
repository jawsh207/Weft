#!/usr/bin/env python3
"""
Experiment (see README.md): trains one of Weft's own small next-word models on
train.txt, then measures it on the benchmark's next-word positions the same
way as the pretrained models: re-ranking the dictionary's candidates, adding
its own guesses ("open"), its size as an int8 ONNX file, and its speed.

The model is a word-level LSTM: it reads the sentence so far one word at a
time and gives a probability to every word it knows. An "adaptive softmax"
keeps the output layer small (common words get full detail, rare ones less).

    python3 tools/lmexp/own.py SIZE train.txt dump.jsonl RESULT.json [--minutes 100]

SIZE is S, M or L (see SIZES).
"""
import argparse
import collections
import json
import os
import random
import sys
import time
from pathlib import Path

import torch
import torch.nn as nn

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

# name: (vocabulary, embedding width, LSTM width)
SIZES = {"S": (20_000, 64, 128), "M": (30_000, 128, 256), "L": (40_000, 256, 640)}
SEQ = 32
BATCH = 64


class WordLSTM(nn.Module):
    """Embedding → one LSTM layer → adaptive softmax over the vocabulary."""

    def __init__(self, vocab, emb, hid):
        super().__init__()
        self.emb = nn.Embedding(vocab, emb)
        self.lstm = nn.LSTM(emb, hid, batch_first=True)
        cut = [c for c in (2000, 10000) if c < vocab - 1]
        self.out = nn.AdaptiveLogSoftmaxWithLoss(hid, vocab, cutoffs=cut, div_value=4.0)

    def hidden(self, ids):
        h, _ = self.lstm(self.emb(ids))
        return h

    def forward(self, ids):
        """Log-probabilities of every word after the last position of [ids] (1 × T)."""
        return self.out.log_prob(self.hidden(ids)[:, -1])


def build_vocab(path, size):
    counts = collections.Counter()
    for line in open(path, encoding="utf-8"):
        counts.update(common.words(line))
    itos = ["<s>", "<unk>"] + [w for w, _ in counts.most_common(size - 2)]
    return itos, {w: i for i, w in enumerate(itos)}


def encode(stoi, text):
    unk = stoi["<unk>"]
    return [stoi.get(w, unk) for w in common.words(text)]


def train(model, path, stoi, minutes, log):
    ids = []
    for line in open(path, encoding="utf-8"):
        ids.append(0)
        ids.extend(encode(stoi, line))
    data = torch.tensor(ids)
    n = (len(data) - 1) // SEQ
    starts = list(range(n))
    opt = torch.optim.Adam(model.parameters(), lr=2e-3)
    deadline = time.time() + minutes * 60
    seen = 0
    epoch = 0
    log(f"training tokens: {len(data):,}")
    while time.time() < deadline and epoch < 3:
        random.shuffle(starts)
        for b in range(0, len(starts) - BATCH, BATCH):
            idx = torch.tensor(starts[b:b + BATCH]) * SEQ
            x = torch.stack([data[i:i + SEQ] for i in idx.tolist()])
            y = torch.stack([data[i + 1:i + SEQ + 1] for i in idx.tolist()])
            h = model.hidden(x)
            loss = model.out(h.reshape(-1, h.size(-1)), y.reshape(-1)).loss
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 0.5)
            opt.step()
            seen += BATCH * SEQ
            if (b // BATCH) % 500 == 0:
                log(f"epoch {epoch} step {b // BATCH} loss {loss.item():.3f} tokens {seen:,}")
            if time.time() > deadline:
                break
        epoch += 1
    return seen


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("size", choices=SIZES)
    p.add_argument("train")
    p.add_argument("dump")
    p.add_argument("result")
    p.add_argument("--minutes", type=float, default=100)
    a = p.parse_args()
    torch.manual_seed(1)
    random.seed(1)
    torch.set_num_threads(os.cpu_count() or 4)
    log = lambda m: print(f"[own-{a.size}] {m}", flush=True)  # noqa: E731

    vocab, emb, hid = SIZES[a.size]
    itos, stoi = build_vocab(a.train, vocab)
    model = WordLSTM(len(itos), emb, hid)
    params = sum(p.numel() for p in model.parameters())
    log(f"parameters: {params:,}")
    t0 = time.time()
    seen = train(model, a.train, stoi, a.minutes, log)
    train_min = (time.time() - t0) / 60
    model.eval()

    rows = common.load_dump(a.dump)
    dict_words = common.load_words(a.dump + ".words.tsv")
    unk = stoi["<unk>"]

    @torch.no_grad()
    def logprobs(prefix):
        return model(torch.tensor([[0] + encode(stoi, prefix)]))[0]

    scores, extra = [], []
    with torch.no_grad():
        for r in rows:
            lp = logprobs(r["prefix"])
            scores.append([float(lp[stoi[c[0].lower()]]) if c[0].lower() in stoi else float(lp[unk]) - 3
                           for c in r["cands"]])
            top = torch.topk(lp, 40)
            own = []
            for v, i in zip(top.values.tolist(), top.indices.tolist()):
                w = itos[i]
                if w in dict_words:
                    own.append((dict_words[w], v))
                if len(own) >= 20:
                    break
            extra.append(own)
    result = common.evaluate(rows, scores, extra)

    # Speed and size as it would ship: int8 weights, one thread.
    torch.set_num_threads(1)
    q = torch.ao.quantization.quantize_dynamic(model, {nn.LSTM, nn.Linear}, dtype=torch.qint8)
    with torch.no_grad():
        ms = common.timed(lambda r: q(torch.tensor([[0] + encode(stoi, r["prefix"])])), rows)
    size_mb, size_note = onnx_size(model, a.size)
    result.update({
        "model": f"Weft own {a.size} (word LSTM)",
        "params_m": round(params / 1e6, 1),
        "onnx_int8_mb": size_mb,
        "size_note": size_note,
        "ms_per_prediction": ms,
        "license": "Apache-2.0 (trained here on Tatoeba, Common Voice, Leipzig: CC BY / CC0)",
        "train_tokens_m": round(seen / 1e6, 1),
        "train_minutes": round(train_min),
        "vocab": len(itos),
    })
    json.dump(result, open(a.result, "w"), indent=1)
    log(json.dumps(result))


def onnx_size(model, tag):
    """Exports the model to ONNX, quantizes it to int8, and returns its size in MB."""
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        src, dst = f"own_{tag}.onnx", f"own_{tag}.int8.onnx"
        torch.onnx.export(model, (torch.tensor([[0, 5, 6]]),), src, input_names=["ids"],
                          output_names=["logprobs"], dynamic_axes={"ids": {1: "t"}}, opset_version=17)
        quantize_dynamic(src, dst, weight_type=QuantType.QInt8)
        return round(os.path.getsize(dst) / 1e6, 1), "ONNX int8 file"
    except Exception as e:  # noqa: BLE001 - fall back to an estimate, and say so
        print(f"ONNX export failed ({e}); estimating from parameter count", flush=True)
        return round(sum(p.numel() for p in model.parameters()) / 1e6, 1), "estimate: 1 byte per weight"


if __name__ == "__main__":
    main()
