"""
The next-word model file (.wlm) that the keyboard ships: writing it, and a
NumPy reader that runs it exactly the way the app's NextWordModel.kt does, so
the numbers measured here are the numbers the keyboard gets.

Needs only NumPy (no PyTorch). train.py writes the file; make_fixture.py
writes a tiny one for the app's unit tests.

The model reads the sentence so far one word at a time (an LSTM) and gives
every word it knows a log-probability of coming next. The output layer is an
"adaptive softmax": the most common words (the head) get full detail, rarer
ones sit in smaller clusters, which keeps the file and the work per word small.

File layout, little-endian, every section padded to 4 bytes:

    "WLM1", int32 version (1)
    int32 vocab, emb, hidden, clusters, then one int32 cutoff per cluster
    float32 weight, offset, unk_penalty   (how Suggester mixes scores, tuned here)
    string sources                        (int32 byte length + UTF-8, shown in Settings › About)
    words: int32 offsets[vocab + 1], int32 sorted[vocab] (ids by UTF-8 bytes), bytes
    matrices, each: int32 rows, int32 cols, float32 scale[rows], int8 data[rows × cols]
        embedding (vocab × emb), input weights (4·hidden × emb), recurrent weights
        (4·hidden × hidden), head (cutoff0 + clusters × hidden), and per cluster
        a projection (p × hidden) and output (size × p)
    vector: int32 n, float32 bias[n]  (4·hidden, gate order input, forget, cell, output)

Word 0 is "<s>" (start of sentence), word 1 is "<unk>" (any other word).
"""
import struct

import numpy as np

MAGIC = b"WLM1"
VERSION = 1


def quantize(m):
    """Symmetric int8 per row: data × scale ≈ m."""
    m = np.asarray(m, dtype=np.float32)
    scale = np.abs(m).max(axis=1) / 127.0
    scale[scale == 0] = 1.0
    q = np.clip(np.round(m / scale[:, None]), -127, 127).astype(np.int8)
    return q, scale.astype(np.float32)


def _pad(b):
    return b + b"\0" * (-len(b) % 4)


def write(path, words, emb, w_ih, w_hh, bias, head, tails, cutoffs,
          weight=1.0, offset=0.0, unk_penalty=3.0, sources=""):
    """Writes a model. [tails] is a list of (projection, output) matrices, one per cluster."""
    hid = w_hh.shape[1]
    assert len(words) == emb.shape[0] and len(tails) == len(cutoffs)
    out = [MAGIC, struct.pack("<i", VERSION),
           struct.pack("<4i", len(words), emb.shape[1], hid, len(cutoffs)),
           struct.pack(f"<{len(cutoffs)}i", *cutoffs),
           struct.pack("<3f", weight, offset, unk_penalty)]
    src = sources.encode("utf-8")
    out.append(_pad(struct.pack("<i", len(src)) + src))
    enc = [w.encode("utf-8") for w in words]
    offsets = np.zeros(len(enc) + 1, dtype="<i4")
    offsets[1:] = np.cumsum([len(e) for e in enc])
    order = sorted(range(len(enc)), key=lambda i: enc[i])
    out.append(offsets.tobytes())
    out.append(np.asarray(order, dtype="<i4").tobytes())
    out.append(_pad(b"".join(enc)))
    for m in [emb, w_ih, w_hh, head] + [x for pair in tails for x in pair]:
        q, s = quantize(m)
        out.append(struct.pack("<2i", *q.shape) + s.astype("<f4").tobytes() + _pad(q.tobytes()))
    out.append(struct.pack("<i", len(bias)) + np.asarray(bias, dtype="<f4").tobytes())
    with open(path, "wb") as f:
        f.write(b"".join(out))


def set_mixing(path, weight, offset):
    """Rewrites the tuned mixing weight and offset in an existing file."""
    with open(path, "r+b") as f:
        head = f.read(24)
        clusters = struct.unpack_from("<i", head, 20)[0]
        f.seek(24 + 4 * clusters)
        f.write(struct.pack("<2f", weight, offset))


class Model:
    """Reads a .wlm file and runs it with the same int8 weights and arithmetic as the app."""

    def __init__(self, path):
        b = open(path, "rb").read()
        assert b[:4] == MAGIC, "not a Weft next-word model"
        p = 8
        self.vocab, self.emb_dim, self.hid, n = struct.unpack_from("<4i", b, p); p += 16
        self.cutoffs = list(struct.unpack_from(f"<{n}i", b, p)); p += 4 * n
        self.weight, self.offset, self.unk_penalty = struct.unpack_from("<3f", b, p); p += 12
        slen = struct.unpack_from("<i", b, p)[0]; p += 4
        self.sources = b[p:p + slen].decode("utf-8"); p += slen + (-slen % 4)
        offs = np.frombuffer(b, dtype="<i4", count=self.vocab + 1, offset=p); p += 4 * (self.vocab + 1)
        p += 4 * self.vocab  # sorted ids: only the app needs them
        blob = b[p:p + offs[-1]]; p += int(offs[-1]) + (-int(offs[-1]) % 4)
        self.words = [blob[offs[i]:offs[i + 1]].decode("utf-8") for i in range(self.vocab)]
        self.ids = {w: i for i, w in enumerate(self.words)}

        def matrix():
            nonlocal p
            r, c = struct.unpack_from("<2i", b, p); p += 8
            s = np.frombuffer(b, dtype="<f4", count=r, offset=p); p += 4 * r
            q = np.frombuffer(b, dtype=np.int8, count=r * c, offset=p).reshape(r, c); p += r * c + (-(r * c) % 4)
            return q.astype(np.float32) * s[:, None]
        self.emb = matrix()
        self.w_ih = matrix()
        self.w_hh = matrix()
        self.head = matrix()
        self.tails = [(matrix(), matrix()) for _ in self.cutoffs]
        k = struct.unpack_from("<i", b, p)[0]; p += 4
        self.bias = np.frombuffer(b, dtype="<f4", count=k, offset=p).copy()

    def id(self, word):
        return self.ids.get(word, 1)

    def start(self):
        """The state before any word: the LSTM has read "<s>"."""
        return self.step((np.zeros(self.hid, np.float32), np.zeros(self.hid, np.float32)), 0)

    def step(self, state, word_id):
        h, c = state
        g = self.w_ih @ self.emb[word_id] + self.w_hh @ h + self.bias
        H = self.hid
        i, f, z, o = (1 / (1 + np.exp(-g[:H])), 1 / (1 + np.exp(-g[H:2 * H])),
                      np.tanh(g[2 * H:3 * H]), 1 / (1 + np.exp(-g[3 * H:])))
        c = f * c + i * z
        return o * np.tanh(c), c

    def state_for(self, words):
        s = self.start()
        for w in words:
            s = self.step(s, self.id(w))
        return s

    def log_probs(self, state):
        """Log-probability of every word in the vocabulary coming next."""
        h = state[0]
        out = np.empty(self.vocab, np.float32)
        head = _log_softmax(self.head @ h)
        short = self.cutoffs[0]
        out[:short] = head[:short]
        bounds = self.cutoffs + [self.vocab]
        for k, (proj, outw) in enumerate(self.tails):
            out[bounds[k]:bounds[k + 1]] = _log_softmax(outw @ (proj @ h)) + head[short + k]
        return out


def _log_softmax(x):
    m = x.max()
    return x - m - np.log(np.exp(x - m).sum())
