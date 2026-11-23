#!/usr/bin/env python3
"""
Trains Weft's next-word model and measures it as the keyboard will run it
(see README.md).

    python3 tools/nextword/train.py VARIANT DATA_DIR OUT.wlm RESULT.json \\
        --dump bench.jsonl --conversation-dump conversation.jsonl [--minutes 240]

VARIANT (see VARIANTS below) picks the training text from prepare.py's
DATA_DIR, how many passes to make and how fast the learning rate falls:
  general        general.txt only, 5 passes
  conversation   + conversation.txt (Taskmaster dialogs), 5 passes (v1.3.0)
  long           the same text, 9 passes, gentler learning-rate fall
  conv2, conv3   the same, with the conversation text counted twice or three times

The model: 30,000-word vocabulary, 128-wide word vectors, one 256-wide LSTM
layer and an adaptive softmax (about 5.6 million weights, 6 MB as int8). It is
trained for up to the variant's passes with dropout and a falling learning rate,
keeping whichever pass did best on sentences set aside from training.

Afterwards it is written as a .wlm file (int8, see wlm.py), read back with the
NumPy reader that matches the app, and scored on the dumps NextWordDumpTest
writes: the dictionary's own candidates for every benchmark position, re-ranked
with the model and joined by the model's own guesses. The mixing weight and
offset are tuned on half the sentences of both dumps and stored in the file;
the other half is reported.

Needs PyTorch (CPU) and NumPy.
"""
import argparse
import collections
import json
import os
import random
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import evaluate  # noqa: E402
import wlm  # noqa: E402

VOCAB, EMB, HID = 30_000, 128, 256
CUTOFFS = [2000, 10000]
SEQ, BATCH = 32, 64
DROPOUT = 0.1
GENERAL = "Tatoeba (CC BY 2.0 FR), Common Voice (CC0), Leipzig Corpora Collection news (CC BY 4.0)"
TASKMASTER = ", Google Taskmaster dialogs (CC BY 4.0)"


class Variant:
    """
    One way of training: which of prepare.py's files to read and how many times each
    counts ([files], name → repeats), how many passes, and how fast the learning rate
    falls after the first pass (multiplied by [decay] each pass).
    """

    def __init__(self, files, epochs, decay, sources):
        self.files, self.epochs, self.decay, self.sources = files, epochs, decay, sources


VARIANTS = {
    "general": Variant({"general.txt": 1}, 5, 0.5, GENERAL),
    # Shipped in v1.3.0.
    "conversation": Variant({"general.txt": 1, "conversation.txt": 1}, 5, 0.5, GENERAL + TASKMASTER),
    # The same text, longer and with a gentler learning-rate fall: shipped after v1.3.0.
    # (Adding OpenAssistant's messages, or counting the conversation text twice, did no better.)
    "long": Variant({"general.txt": 1, "conversation.txt": 1}, 9, 0.7, GENERAL + TASKMASTER),
    # The conversation text counted two or three times, for sentence starts in conversation.
    "conv2": Variant({"general.txt": 1, "conversation.txt": 2}, 9, 0.7, GENERAL + TASKMASTER),
    "conv3": Variant({"general.txt": 1, "conversation.txt": 3}, 8, 0.7, GENERAL + TASKMASTER),
}


class WordLSTM(nn.Module):
    """Embedding → dropout → one LSTM layer → dropout → adaptive softmax over the vocabulary."""

    def __init__(self, vocab):
        super().__init__()
        self.emb = nn.Embedding(vocab, EMB)
        self.lstm = nn.LSTM(EMB, HID, batch_first=True)
        self.drop = nn.Dropout(DROPOUT)
        self.out = nn.AdaptiveLogSoftmaxWithLoss(HID, vocab, cutoffs=CUTOFFS, div_value=4.0)

    def hidden(self, ids):
        h, _ = self.lstm(self.drop(self.emb(ids)))
        return self.drop(h)

    def loss(self, x, y):
        h = self.hidden(x)
        return self.out(h.reshape(-1, HID), y.reshape(-1)).loss


def read_lines(data_dir, variant, rng):
    """
    (training lines, validation lines). Validation takes 0.5% of each file's distinct lines
    before any repeats, so repeated lines never count as unseen.
    """
    train_lines, val_lines = [], []
    for name, repeats in VARIANTS[variant].files.items():
        lines = [l for l in open(Path(data_dir) / name, encoding="utf-8") if l.strip()]
        rng.shuffle(lines)
        n_val = max(200, len(lines) // 200)
        val_lines += lines[:n_val]
        train_lines += lines[n_val:] * repeats
    rng.shuffle(train_lines)
    return train_lines, val_lines


def to_ids(lines, stoi):
    unk = stoi["<unk>"]
    ids = []
    for line in lines:
        ids.append(0)
        ids.extend(stoi.get(w, unk) for w in common.words(line))
    return torch.tensor(ids)


def batches(data, shuffle):
    n = (len(data) - 1) // SEQ
    starts = list(range(n))
    if shuffle:
        random.shuffle(starts)
    for b in range(0, n - BATCH + 1, BATCH):
        idx = [s * SEQ for s in starts[b:b + BATCH]]
        yield (torch.stack([data[i:i + SEQ] for i in idx]), torch.stack([data[i + 1:i + SEQ + 1] for i in idx]))


@torch.no_grad()
def val_loss(model, data):
    model.eval()
    total, n = 0.0, 0
    for x, y in batches(data, False):
        total += model.loss(x, y).item()
        n += 1
    model.train()
    return total / max(n, 1)


def train(model, data, val, minutes, variant, log):
    opt = torch.optim.Adam(model.parameters(), lr=2e-3)
    deadline = time.time() + minutes * 60
    best, best_state, seen, history = float("inf"), None, 0, []
    for epoch in range(variant.epochs):
        lr = 2e-3 * (variant.decay ** max(0, epoch - 1))
        for g in opt.param_groups:
            g["lr"] = lr
        for step, (x, y) in enumerate(batches(data, True)):
            loss = model.loss(x, y)
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 0.5)
            opt.step()
            seen += x.numel()
            if step % 1000 == 0:
                log(f"epoch {epoch} step {step} loss {loss.item():.3f} tokens {seen:,}")
            if time.time() > deadline:
                break
        v = val_loss(model, val)
        history.append(round(v, 4))
        log(f"epoch {epoch} done: validation loss {v:.4f} (perplexity {np.exp(v):.1f}), lr {lr}")
        if v < best:
            best, best_state = v, {k: t.clone() for k, t in model.state_dict().items()}
        if time.time() > deadline:
            log("time budget used up")
            break
    model.load_state_dict(best_state)
    return seen, best, history


def export(model, itos, path, sources):
    """Writes the trained weights as a .wlm file (mixing weight and offset filled in later)."""
    sd = {k: v.detach().numpy() for k, v in model.state_dict().items()}
    tails = [(sd[f"out.tail.{i}.0.weight"], sd[f"out.tail.{i}.1.weight"]) for i in range(len(CUTOFFS))]
    wlm.write(path, itos, sd["emb.weight"], sd["lstm.weight_ih_l0"], sd["lstm.weight_hh_l0"],
              sd["lstm.bias_ih_l0"] + sd["lstm.bias_hh_l0"], sd["out.head.weight"], tails, CUTOFFS,
              sources=sources)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("variant", choices=VARIANTS)
    p.add_argument("data")
    p.add_argument("out")
    p.add_argument("result")
    p.add_argument("--dump", required=True)
    p.add_argument("--conversation-dump", required=True)
    p.add_argument("--minutes", type=float, default=240)
    a = p.parse_args()
    torch.manual_seed(1)
    random.seed(1)
    torch.set_num_threads(os.cpu_count() or 4)
    log = lambda msg: print(f"[{a.variant}] {msg}", flush=True)  # noqa: E731

    train_lines, val_lines = read_lines(a.data, a.variant, random.Random(1))
    counts = collections.Counter(w for l in train_lines for w in common.words(l))
    itos = ["<s>", "<unk>"] + [w for w, _ in counts.most_common(VOCAB - 2)]
    stoi = {w: i for i, w in enumerate(itos)}
    data, val = to_ids(train_lines, stoi), to_ids(val_lines, stoi)
    log(f"{len(train_lines):,} sentences, {len(data):,} tokens; vocabulary {len(itos):,}")

    model = WordLSTM(len(itos))
    params = sum(t.numel() for t in model.parameters())
    t0 = time.time()
    seen, best, history = train(model, data, val, a.minutes, VARIANTS[a.variant], log)
    minutes = (time.time() - t0) / 60
    model.eval()
    export(model, itos, a.out, VARIANTS[a.variant].sources)

    # Score exactly what ships: the int8 file through the NumPy twin of NextWordModel.kt.
    m = wlm.Model(a.out)
    with torch.no_grad():
        ref = model.out.log_prob(model.hidden(torch.tensor([[0] + [stoi.get(w, 1) for w in "i would like".split()]]))[:, -1])[0].numpy()
    quant_diff = float(np.abs(m.log_probs(m.state_for(["i", "would", "like"])) - ref)[:5000].max())
    log(f"int8 vs float log-probabilities, largest difference among common words: {quant_diff:.3f}")

    weight, offset, sets = evaluate.measure(a.out, a.dump, a.conversation_dump)
    wlm.set_mixing(a.out, weight, offset)
    result = {"variant": a.variant, "weight": weight, "offset": offset, **sets}
    result.update({
        "params_m": round(params / 1e6, 2),
        "file_mb": round(os.path.getsize(a.out) / 1e6, 2),
        "validation_perplexity": round(float(np.exp(best)), 1),
        "validation_history": history,
        "train_tokens_m": round(len(data) / 1e6, 1),
        "tokens_seen_m": round(seen / 1e6, 1),
        "train_minutes": round(minutes),
        "int8_max_diff": round(quant_diff, 4),
        "sources": VARIANTS[a.variant].sources,
    })
    json.dump(result, open(a.result, "w"), indent=1)
    log(json.dumps(result))


if __name__ == "__main__":
    main()
