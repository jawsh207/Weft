# Next-word model

Written by the Next-word model workflow (tools/nextword). Top-3 = how often the
next word is among the three predictions, on positions never used for training or
tuning. Sentences = the typing benchmark's held-out Tatoeba sentences; conversation =
Taskmaster-2's sports and music dialogs, which no variant trains on.

Dictionary alone: sentences top-3 36.8% (top-1 23.9%), conversation top-3 28.3% (top-1 16.1%).

| Variant | Sentences top-3 | Sentences top-1 | Conversation top-3 | Conversation top-1 | Perplexity | Weight / offset | File MB | Trained |
|---|---|---|---|---|---|---|---|---|
| conversation (shipped) | 43.7 | 28.5 | 37.9 | 24.8 | 61.4 | 1.5 / 0.5 | 6.31 | 103 min, 153.1M words seen of 30.6M |
| general | 45.3 | 29.2 | 32.0 | 21.0 | 71.9 | 2 / 0.5 | 6.31 | 145 min, 121.0M words seen of 24.2M |

Shipped file: `app/src/main/assets/models/next_en.wlmz` (5.7 MB gzipped).

## Details

- **conversation**: `{"variant": "conversation", "weight": 1.5, "offset": 0.5, "sentences": {"top1": 28.5, "top3": 43.7, "dictionary_top1": 23.9, "dictionary_top3": 36.8, "positions": 1731}, "conversation": {"top1": 24.8, "top3": 37.9, "dictionary_top1": 16.1, "dictionary_top3": 28.3, "positions": 4161}, "params_m": 5.6, "file_mb": 6.31, "validation_perplexity": 61.4, "validation_history": [4.3231, 4.2218, 4.1596, 4.1305, 4.1173], "train_tokens_m": 30.6, "tokens_seen_m": 153.1, "train_minutes": 103, "int8_max_diff": 0.1597, "sources": "Tatoeba (CC BY 2.0 FR), Common Voice (CC0), Leipzig Corpora Collection news (CC BY 4.0), Google Taskmaster dialogs (CC BY 4.0)"}`
- **general**: `{"variant": "general", "weight": 2, "offset": 0.5, "sentences": {"top1": 29.2, "top3": 45.3, "dictionary_top1": 23.9, "dictionary_top3": 36.8, "positions": 1731}, "conversation": {"top1": 21.0, "top3": 32.0, "dictionary_top1": 16.1, "dictionary_top3": 28.3, "positions": 4161}, "params_m": 5.6, "file_mb": 6.31, "validation_perplexity": 71.9, "validation_history": [4.4876, 4.3827, 4.3163, 4.2889, 4.275], "train_tokens_m": 24.2, "tokens_seen_m": 121.0, "train_minutes": 145, "int8_max_diff": 0.1421, "sources": "Tatoeba (CC BY 2.0 FR), Common Voice (CC0), Leipzig Corpora Collection news (CC BY 4.0)"}`
