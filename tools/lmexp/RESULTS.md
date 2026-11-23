# Next-word models compared

Same benchmark positions for every model (held-out Tatoeba sentences, a third of the
benchmark, split into a tuning half and a test half; numbers are on the test half).

- Today's dictionary alone: **top-3 36.8%**, top-1 23.9%
- Ceiling for re-ranking: the right word is among the dictionary's 30 candidates 58.3% of the time
- Test positions: 1731

Re-rank = the model reorders the dictionary's candidates. Open = it may also add its
own guesses (dictionary words only). Speed is one CPU thread on the CI machine (x86),
context plus all candidates; a phone is likely 2–4× slower.

| Model | Params (M) | Size int8 (MB) | ms / prediction | Re-rank top-3 | Open top-3 | Open top-1 | License |
|---|---|---|---|---|---|---|---|
| Weft own S (word LSTM) | 2.0 | 2.2 | 0.7 | 41.5 | 42.1 | 27.0 | Apache-2.0 (trained here on Tatoeba, Common Voice, Leipzig: CC BY / CC0) |
| Weft own M (word LSTM) | 5.6 | 6.0 | 0.4 | 43.2 | 45.1 | 29.1 | Apache-2.0 (trained here on Tatoeba, Common Voice, Leipzig: CC BY / CC0) |
| Weft own L (word LSTM) | 16.4 | 16.9 | 0.9 | 44.8 | 46.3 | 30.2 | Apache-2.0 (trained here on Tatoeba, Common Voice, Leipzig: CC BY / CC0) |
| EleutherAI/pythia-14m | 14.1 | 18.0 | 18.9 | 38.0 | 38.1 | 25.0 | apache-2.0 |
| EleutherAI/pythia-70m | 70.4 | 74.5 | 78.2 | 40.2 | 41.0 | 25.9 | apache-2.0 |
| distilbert/distilgpt2 | 81.9 | 124.6 | 104.8 | 41.4 | 43.4 | 26.9 | apache-2.0 |
| EleutherAI/pythia-160m | 162.3 | 166.9 | 160.7 | 41.6 | 44.1 | 27.8 | apache-2.0 |
| HuggingFaceTB/SmolLM2-135M | 134.5 | 168.4 | 184.6 | 42.5 | 46.3 | 29.9 | apache-2.0 |
| HuggingFaceTB/SmolLM2-360M | 361.8 | 414.8 | 463.3 | 42.9 | 47.3 | 31.0 | apache-2.0 |

## Details

- **Weft own S (word LSTM)**: rerank {'top1': 27.0, 'top3': 41.5, 'weight': 1.5, 'offset': 0}, open {'top1': 27.0, 'top3': 42.1, 'weight': 1.5, 'offset': 1}, params_m 2.0, onnx_int8_mb 2.2, size_note ONNX int8 file, ms_per_prediction 0.7, license Apache-2.0 (trained here on Tatoeba, Common Voice, Leipzig: CC BY / CC0), train_tokens_m 73.0, train_minutes 46, vocab 20000
- **Weft own M (word LSTM)**: rerank {'top1': 28.5, 'top3': 43.2, 'weight': 5, 'offset': 0}, open {'top1': 29.1, 'top3': 45.1, 'weight': 5, 'offset': 0}, params_m 5.6, onnx_int8_mb 6.0, size_note ONNX int8 file, ms_per_prediction 0.4, license Apache-2.0 (trained here on Tatoeba, Common Voice, Leipzig: CC BY / CC0), train_tokens_m 73.0, train_minutes 72, vocab 30000
- **Weft own L (word LSTM)**: rerank {'top1': 29.5, 'top3': 44.8, 'weight': 2, 'offset': 0}, open {'top1': 30.2, 'top3': 46.3, 'weight': 3, 'offset': 3}, params_m 16.4, onnx_int8_mb 16.9, size_note ONNX int8 file, ms_per_prediction 0.9, license Apache-2.0 (trained here on Tatoeba, Common Voice, Leipzig: CC BY / CC0), train_tokens_m 54.9, train_minutes 120, vocab 40000
- **EleutherAI/pythia-14m**: rerank {'top1': 25.2, 'top3': 38.0, 'weight': 0.2, 'offset': 0}, open {'top1': 25.0, 'top3': 38.1, 'weight': 0.2, 'offset': 0}, params_m 14.1, onnx_int8_mb 18.0, size_note ONNX int8 file + tokenizer, ms_per_prediction 18.9, license apache-2.0
- **EleutherAI/pythia-70m**: rerank {'top1': 25.8, 'top3': 40.2, 'weight': 0.3, 'offset': 0}, open {'top1': 25.9, 'top3': 41.0, 'weight': 0.3, 'offset': 0}, params_m 70.4, onnx_int8_mb 74.5, size_note ONNX int8 file + tokenizer, ms_per_prediction 78.2, license apache-2.0
- **distilbert/distilgpt2**: rerank {'top1': 26.2, 'top3': 41.4, 'weight': 0.75, 'offset': 0}, open {'top1': 26.9, 'top3': 43.4, 'weight': 0.75, 'offset': 0}, params_m 81.9, onnx_int8_mb 124.6, size_note ONNX int8 file + tokenizer, ms_per_prediction 104.8, license apache-2.0
- **EleutherAI/pythia-160m**: rerank {'top1': 26.9, 'top3': 41.6, 'weight': 1, 'offset': 0}, open {'top1': 27.8, 'top3': 44.1, 'weight': 1, 'offset': 0}, params_m 162.3, onnx_int8_mb 166.9, size_note ONNX int8 file + tokenizer, ms_per_prediction 160.7, license apache-2.0
- **HuggingFaceTB/SmolLM2-135M**: rerank {'top1': 28.2, 'top3': 42.5, 'weight': 0.5, 'offset': 0}, open {'top1': 29.9, 'top3': 46.3, 'weight': 1, 'offset': 0}, params_m 134.5, onnx_int8_mb 168.4, size_note ONNX int8 file + tokenizer, ms_per_prediction 184.6, license apache-2.0
- **HuggingFaceTB/SmolLM2-360M**: rerank {'top1': 29.3, 'top3': 42.9, 'weight': 0.75, 'offset': 0}, open {'top1': 31.0, 'top3': 47.3, 'weight': 1, 'offset': 0}, params_m 361.8, onnx_int8_mb 414.8, size_note ONNX int8 file + tokenizer, ms_per_prediction 463.3, license apache-2.0
