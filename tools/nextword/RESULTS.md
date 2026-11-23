# Next-word model

Written by the Next-word model workflow (tools/nextword). Top-3 = how often the
next word is among the three predictions, on positions never used for training or
tuning. Sentences = the typing benchmark's held-out Tatoeba sentences; conversation =
Taskmaster-2's sports and music dialogs, which no variant trains on.

Dictionary alone: sentences top-3 36.8% (top-1 23.9%), conversation top-3 28.3% (top-1 16.1%).

| Variant | Sentences top-3 | Sentences top-1 | Conversation top-3 | Conversation top-1 | Perplexity | Weight / offset | File MB | Trained |
|---|---|---|---|---|---|---|---|---|
| already in the app | 43.7 | 28.5 | 37.9 | 24.8 | – | 1.5 / 0.5 | – | earlier |
| chat-long-x2 | 43.8 | 28.0 | 39.4 | 25.6 | 60.9 | 1 / 0 | 6.31 | 300 min, 260.1M words seen of 39.0M |
| chat-long | 44.7 | 27.6 | 39.3 | 25.2 | 60.3 | 1.5 / 1 | 6.31 | 300 min, 246.2M words seen of 31.6M |
| long (shipped) | 44.9 | 29.2 | 39.3 | 24.9 | 59.0 | 1.5 / 0.5 | 6.31 | 300 min, 237.5M words seen of 30.6M |

Shipped file: `app/src/main/assets/models/next_en.wlmz` (5.7 MB gzipped).

## Details

- **current**: `{"variant": "current", "weight": 1.5, "offset": 0.5, "sentences": {"top1": 28.5, "top3": 43.7, "dictionary_top1": 23.9, "dictionary_top3": 36.8, "positions": 1731}, "conversation": {"top1": 24.8, "top3": 37.9, "dictionary_top1": 16.1, "dictionary_top3": 28.3, "positions": 4161}}`
- **chat-long-x2**: `{"variant": "chat-long-x2", "weight": 1, "offset": 0, "sentences": {"top1": 28.0, "top3": 43.8, "dictionary_top1": 23.9, "dictionary_top3": 36.8, "positions": 1731}, "conversation": {"top1": 25.6, "top3": 39.4, "dictionary_top1": 16.1, "dictionary_top3": 28.3, "positions": 4161}, "params_m": 5.6, "file_mb": 6.31, "validation_perplexity": 60.9, "validation_history": [4.3244, 4.2324, 4.1795, 4.1485, 4.1279, 4.1171, 4.1099], "train_tokens_m": 39.0, "tokens_seen_m": 260.1, "train_minutes": 300, "int8_max_diff": 0.1361, "sources": "Tatoeba (CC BY 2.0 FR), Common Voice (CC0), Leipzig Corpora Collection news (CC BY 4.0), Google Taskmaster dialogs (CC BY 4.0), OpenAssistant OASST2 messages (Apache-2.0)"}`
- **chat-long**: `{"variant": "chat-long", "weight": 1.5, "offset": 1, "sentences": {"top1": 27.6, "top3": 44.7, "dictionary_top1": 23.9, "dictionary_top3": 36.8, "positions": 1731}, "conversation": {"top1": 25.2, "top3": 39.3, "dictionary_top1": 16.1, "dictionary_top3": 28.3, "positions": 4161}, "params_m": 5.6, "file_mb": 6.31, "validation_perplexity": 60.3, "validation_history": [4.334, 4.2376, 4.1808, 4.1473, 4.1272, 4.1143, 4.1043, 4.0993], "train_tokens_m": 31.6, "tokens_seen_m": 246.2, "train_minutes": 300, "int8_max_diff": 0.1309, "sources": "Tatoeba (CC BY 2.0 FR), Common Voice (CC0), Leipzig Corpora Collection news (CC BY 4.0), Google Taskmaster dialogs (CC BY 4.0), OpenAssistant OASST2 messages (Apache-2.0)"}`
- **long**: `{"variant": "long", "weight": 1.5, "offset": 0.5, "sentences": {"top1": 29.2, "top3": 44.9, "dictionary_top1": 23.9, "dictionary_top3": 36.8, "positions": 1731}, "conversation": {"top1": 24.9, "top3": 39.3, "dictionary_top1": 16.1, "dictionary_top3": 28.3, "positions": 4161}, "params_m": 5.6, "file_mb": 6.31, "validation_perplexity": 59.0, "validation_history": [4.3154, 4.2177, 4.1598, 4.1257, 4.1047, 4.0907, 4.0813, 4.0767], "train_tokens_m": 30.6, "tokens_seen_m": 237.5, "train_minutes": 300, "int8_max_diff": 0.1438, "sources": "Tatoeba (CC BY 2.0 FR), Common Voice (CC0), Leipzig Corpora Collection news (CC BY 4.0), Google Taskmaster dialogs (CC BY 4.0)"}`
