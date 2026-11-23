#!/usr/bin/env python3
"""
Writes the training text for Weft's next-word model (see README.md).

    python3 tools/nextword/prepare.py OUT_DIR [--news 300000]

Makes, in OUT_DIR:
  general.txt       Tatoeba (minus the benchmark's held-out sentences), Common
                    Voice and Leipzig news: the sentences the dictionary is built from.
  conversation.txt  Google's Taskmaster dialogs (CC BY 4.0): people ordering,
                    booking and chatting with an assistant, typed or spoken.
                    TM-3 (movie tickets) is cut to a quarter so tickets don't take over.
  chat.txt          OpenAssistant OASST2 (Apache-2.0): the English messages
                    people typed to the chatbot (not its replies, which
                    volunteers wrote as polished answers), leaving out
                    deleted, rejected and generated ones, code and links.

One sentence per line. Downloads are kept in tools/dictgen/cache like the
dictionary build's.

    python3 tools/nextword/prepare.py --conversation-test FILE

writes the held-out conversation sentences (Taskmaster-2's sports and music
dialogs, never trained on) for the benchmark: tools/bench/data/conversation_en.txt.
"""
import argparse
import gzip
import json
import re
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "dictgen"))
import build_dict as bd  # noqa: E402
import make_dict as md  # noqa: E402

TASKMASTER = "https://raw.githubusercontent.com/google-research-datasets/Taskmaster/master/"
TRAIN_FILES = (
    ["TM-1-2019/self-dialogs.json", "TM-1-2019/woz-dialogs.json"]
    + [f"TM-2-2020/data/{d}.json" for d in ("flights", "food-ordering", "hotels", "movies", "restaurant-search")]
    + [f"TM-3-2020/data/data_{i:02d}.json" for i in range(0, 20, 4)]  # a quarter of the movie-ticket dialogs
    + [f"TM-4-2024/data/data_{i:02d}.json" for i in range(8)]
)
TEST_FILES = ["TM-2-2020/data/sports.json", "TM-2-2020/data/music.json"]
SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
OASST = "https://huggingface.co/datasets/OpenAssistant/oasst2/resolve/main/2023-11-05_oasst2_all.messages.jsonl.gz"


def fetch_taskmaster(name, cache):
    """One Taskmaster file, downloaded once into [cache]."""
    path = cache / "taskmaster" / name.replace("/", "_")
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        print("downloading", name, flush=True)
        tmp = path.with_suffix(".part")
        urllib.request.urlretrieve(TASKMASTER + name, tmp)
        tmp.rename(path)
    return path


def dialog_sentences(path):
    """Every utterance in a Taskmaster file, split into sentences of at least two words."""
    for dialog in json.load(open(path, encoding="utf-8")):
        for u in dialog.get("utterances", []):
            for s in SENTENCE_END.split(u.get("text", "").replace("\n", " ").strip()):
                if len(s.split()) >= 2:
                    yield s


def chat_sentences(cache):
    """Sentences from the English OpenAssistant messages people typed (see the docstring)."""
    path = cache / "oasst2_all.messages.jsonl.gz"
    if not path.exists():
        print("downloading OASST2 messages", flush=True)
        tmp = path.with_suffix(".part")
        urllib.request.urlretrieve(OASST, tmp)
        tmp.rename(path)
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            m = json.loads(line)
            if (m.get("lang") != "en" or m.get("role") != "prompter" or m.get("deleted")
                    or m.get("review_result") is False or m.get("synthetic")):
                continue
            text = m.get("text", "")
            if "```" in text or "http" in text or "    " in text:
                continue
            for para in text.splitlines():
                for s in SENTENCE_END.split(para.strip()):
                    if 2 <= len(s.split()) <= 40:
                        yield s


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("out", nargs="?")
    p.add_argument("--news", type=int, default=300_000, help="Leipzig news sentences to include")
    p.add_argument("--conversation-test", metavar="FILE", help="write the held-out conversation sentences")
    p.add_argument("--test-sentences", type=int, default=3000)
    a = p.parse_args()
    cache = HERE.parent / "dictgen" / "cache" / "en_US"
    cache.mkdir(parents=True, exist_ok=True)

    if a.conversation_test:
        every = []
        for name in TEST_FILES:
            every.extend(dialog_sentences(fetch_taskmaster(name, cache)))
        step = max(1, len(every) // a.test_sentences)
        picked = every[::step][:a.test_sentences]
        Path(a.conversation_test).write_text("\n".join(picked) + "\n", encoding="utf-8")
        print(f"{len(picked)} held-out conversation sentences written to {a.conversation_test}")
        return

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    config = json.loads((HERE.parent / "dictgen" / "sources" / "en_US.json").read_text())
    paths = {k: md.fetch(k, config["sources"][k], cache) for k in ("tatoeba", "commonvoice", "leipzig")}
    held_out = bd.tatoeba_held_out(paths["tatoeba"]) if paths["tatoeba"] else set()
    counts = {}

    def put(f, name, sentences, limit=None):
        n = 0
        for s in sentences:
            s = s.strip().replace("\n", " ")
            if s:
                f.write(s + "\n")
                n += 1
                if limit and n >= limit:
                    break
        counts[name] = n

    with open(out / "general.txt", "w", encoding="utf-8") as f:
        if paths["tatoeba"]:
            put(f, "tatoeba", bd.tatoeba_sentences(paths["tatoeba"]))
        if paths["commonvoice"]:
            put(f, "commonvoice", bd.plain_sentences(paths["commonvoice"], held_out))
        if paths["leipzig"]:
            put(f, "leipzig", bd.leipzig_sentences(paths["leipzig"]), a.news)
    with open(out / "conversation.txt", "w", encoding="utf-8") as f:
        put(f, "taskmaster", (s for name in TRAIN_FILES for s in dialog_sentences(fetch_taskmaster(name, cache))))
    with open(out / "chat.txt", "w", encoding="utf-8") as f:
        put(f, "oasst2", chat_sentences(cache))
    print("Training sentences:", counts)


if __name__ == "__main__":
    main()
