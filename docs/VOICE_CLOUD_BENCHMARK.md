# Public corpus and cloud Voice Accuracy Benchmark

Milestone 3.2 adds host-side tools under `tools/voicebench/`. Android games still use the existing local runtime and settings. `tools/voice_accuracy_lab.py` transfers datasets and runs the six native configurations; `tools/voice_cloud_benchmark.py` imports corpora, freezes subsets, indexes transcripts, calls optional cloud references, and compares results.

## Dependencies and provider setup

Python 3.11+, `ffmpeg`, and `ffprobe` are required for import. Core tooling and mock tests use the standard library. Optional Parquet extraction needs `pyarrow`; Google ADC needs `google-auth` and `requests`. Install optional packages in a virtual environment, not the Android build.

Set credentials in your shell or provider-standard credential store. Never put values in commands committed to the repository. `voice-benchmark/secrets/` and `.env*` are ignored; the CLI does not automatically execute/load dotenv files.

| Provider | Credentials | Selected API / context |
|---|---|---|
| Yandex | `YANDEX_API_KEY`, or `YANDEX_IAM_TOKEN` + `YANDEX_FOLDER_ID` | SpeechKit synchronous v1, raw PCM; context unsupported by this adapter/API |
| Azure | `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION` | Short-audio REST v1, detailed output; context unsupported by this adapter/API |
| Google | `GOOGLE_APPLICATION_CREDENTIALS`, or `GOOGLE_USE_ADC=1` for configured ADC; optional temporary `GOOGLE_ACCESS_TOKEN` | STT v1 recognize; full-domain `speechContexts` supported |

Missing credentials produce `SKIPPED_CREDENTIALS`. Google requires the relevant Speech API enabled and ADC permissions/billing configured in your own project. No billing identifiers are stored in reports. Provider credentials are used only at request time, never in cache keys or raw result files.

Provider references: [Yandex synchronous recognition](https://aistudio.yandex.ru/en/docs/speechkit/stt/api/request-api), [Azure short-audio REST](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-speech-to-text-short), [Google RecognitionConfig](https://docs.cloud.google.com/speech-to-text/docs/reference/rest/v1/RecognitionConfig). Azure Speech SDK phrase lists and newer Yandex APIs are separate capabilities, not claimed by these REST adapters. Cloud requests have not been live-validated without credentials.

## Import and provenance

Download archives yourself from [Golos / OpenSLR 114](https://openslr.org/114/) or import an already downloaded Common Voice release. Do not bypass Common Voice authentication or license acceptance. Supply the license and source URL for the exact release. Golos uses its own [public attribution/share-alike license](https://github.com/sberdevices/golos/blob/master/license/en_us.pdf), explicitly **not** a Creative Commons license. Authors: Alexander Denisenko, Angelina Kovalenko, Fedor Minkin, Nikolay Karpov. Retain its attribution and conditions when sharing derivatives.

```bash
python3 tools/voice_cloud_benchmark.py import-golos \
  --manifest /data/golos/test/farfield/manifest.jsonl \
  --audio-root /data/golos --output voice-benchmark/public/golos \
  --license https://github.com/sberdevices/golos/blob/master/license/en_us.pdf \
  --source-reference https://openslr.org/114/ \
  --privacy public --population public-general --far-field --limit 200

python3 tools/voice_cloud_benchmark.py import-commonvoice \
  --manifest /data/cv-ru/validated.tsv --audio-root /data/cv-ru/clips \
  --output voice-benchmark/public/commonvoice --license RELEASE_LICENSE \
  --source-reference RELEASE_URL --privacy public --population public-general
```

`import-generic` accepts CSV, TSV or JSONL; defaults are `file`/`truth`. Use `--audio-column` and `--truth-column` for other names. Golos `audio_filepath`/`text` and Common Voice `path`/`sentence` are recognized. All referenced audio must be inside `--audio-root`; choose a new output directory. Imports never overwrite source audio. ffmpeg produces 16 kHz mono PCM16 WAV; empty, incomplete or over-30-second output is rejected, never silently clipped. A failed import may leave a partial directory: inspect/remove that generated directory or choose a new destination before retrying.

The manifest preserves dataset/source ID, transcript, relative source file, source/derived SHA-256, original/normalized rate, duration, license/source reference, population, origin, privacy and known far-field status. Unknown far-field is null. Only an explicitly requested `--speaker-id-column` is copied as a hash, never speaker names. Generic input defaults to private/unknown; public status is an explicit assertion by the importer. Preserve source archives and `selection.json` receipts.

## Frozen subsets and short-answer index

```bash
python3 tools/voice_cloud_benchmark.py subset --dataset voice-benchmark/public/golos \
  --output voice-benchmark/public/golos-frozen --group farfield --count 200 --seed 3201
python3 tools/voice_cloud_benchmark.py index --dataset voice-benchmark/public/golos-frozen \
  --output voice-benchmark/public/golos-frozen/index.json
```

Groups: `general`, `short` (≤3 seconds), `farfield` (explicit true), `noisy` (explicit noise metadata). Selection sorts IDs then shuffles with the seed, before any model output. `--ids previous/selection.json` replays exact IDs and rejects missing IDs. Fewer eligible samples remain visibly fewer; no padding. The index separates natural standalone words, sentences containing target words, and synthetic/extracted material. Sentences stay OPEN; merely containing “пять” does not enable number hotwords. No word clipping occurs. The full vocabulary contains eleven numbers, all four colors, or both yes/no words; expected answers never enter provider calls.

## Local and cloud runs

```bash
python3 tools/voice_accuracy_lab.py --serial DEVICE push voice-benchmark/public/golos-frozen --name golos-frozen-v1
python3 tools/voice_accuracy_lab.py --serial DEVICE run \
  --dataset /sdcard/Android/data/ru.familytv/files/golos-frozen-v1 \
  --corpus-kind public --qwen --output voice-benchmark/local-golos-v1
python3 tools/voice_cloud_benchmark.py run-cloud \
  --dataset voice-benchmark/public/golos-frozen \
  --output voice-benchmark/cloud-results/golos-v1 --sample-limit 20 --dry-run
# Review the printed sample/audio/request totals, then explicitly authorize cost:
python3 tools/voice_cloud_benchmark.py run-cloud \
  --dataset voice-benchmark/public/golos-frozen \
  --output voice-benchmark/cloud-results/golos-v1 --sample-limit 20 --confirm-cloud-cost
python3 tools/voice_cloud_benchmark.py compare \
  --dataset voice-benchmark/public/golos-frozen \
  --local voice-benchmark/local-golos-v1/results.json \
  --cloud voice-benchmark/cloud-results/golos-v1/cloud-results.json \
  --output voice-benchmark/local-golos-comparison
```

No cloud upload occurs without `--confirm-cloud-cost`; `--dry-run` overrides confirmation. The plan distinguishes matrix entries from pending unique requests, total dataset duration, and pending audio minutes across providers. It does not invent a currency estimate: billing rounding, quotas, tariffs and context semantics differ. Default sample limit is 20. OPEN samples run plain only. Google known-domain samples run plain and contextual; context status and support are explicit in every row.

## Privacy, cache and resume

By default only `privacy: public` audio or synthetic/test fixtures with `cloudSafe: true` may upload. Child-labelled and H340/XVF3800-labelled recordings are denied even when marked public. Personal recordings require the separate `--allow-private-cloud-upload` flag **and** cost confirmation; the tool prints a warning. Do not mislabel private data as public. Raw provider responses can contain transcripts: generated corpora, caches, reports and credentials are ignored by Git.

The cache key includes provider, adapter configuration/version, Russian language, audio SHA-256 and context. Responses and raw JSON are durable after each request. A cross-process cache lock prevents concurrent duplicate billing. An in-flight tombstone is written before network activity. Successful responses are reused; explicit HTTP errors require `--retry-errors`. Errors pause further requests to that provider during the run. Timeouts, 5xx responses, parse errors and interrupted in-flight requests remain `UNCERTAIN` and are **never automatically retried**, even with `--retry-errors`. Review provider logs/billing before manually removing a particular uncertain cache entry; doing so may cause a second charge. Keep the cache when resuming. There are no automatic retry loops.

## Metrics and interpretation

Local reports retain raw exact match, micro WER with S/D/I, latency/RTF and semantic accuracy/UNKNOWN/false accepts. Comparison reports independently summarize each engine/configuration. WER uses successful rows only and explicitly reports coverage/failures; unavailable providers have null accuracy. Normalization matches the lab: lowercase, selected punctuation removal and whitespace collapse, preserving ё/е. Confidence and N-best are kept only when returned; multi-segment Google alternatives remain segment-level and are not fabricated into sentence confidence. Confidence scales are not comparable across providers.

`comparison.json` and `errors.csv` preserve truth, greedy text, a **fixed** contextual configuration (`--context-config`, default 1.0), Qwen outputs and cloud texts. No per-sample best oracle is used. Flags identify observed failures, cloud agreement, context damage and wrong Qwen semantic outputs. A semantic correction is observable; whether it repaired language or acoustic errors is not established by transcripts alone. Listen to the source recording for causal investigation. Consensus requires three independent engine families: the six local decoders count once, and plain/context variants of a cloud provider count once. Consensus never changes reference labels or production behavior.

Public-general, adult, child and synthetic population/origin metadata remain separate from far-field recording conditions. Unknown age is not “adult.” This public subset does not validate child speech. No child corpus was imported; future consented recordings are a separate private evaluation.

## Tests and adding providers

```bash
python3 -m unittest discover -s tools/tests -v
```

Tests use mocked transports, no credentials or paid calls. Implement `CloudAsrProvider.available/config/request/normalize`, declare context support, and register it in `PROVIDERS`. Keep truth/expected answers out of its interface, use a fixed HTTPS endpoint, preserve genuine raw output, add response/error tests and document API-specific context behavior. Bump adapterVersion when request/normalization semantics change to invalidate old cache entries.

## Actual validation: 2026-09-15

Imported **20 / 1,916** Golos far-field test utterances, seed **3201**, duration **50.657125 s**, no rejected files. The original OpenSLR-linked short URL did not provide usable audio during this run, so the public [bonlime Golos test mirror](https://huggingface.co/datasets/bonlime/golos-test) was used. Downloaded Parquet SHA-256: `54fc11aba1118d0d39483d686928f85bcde1ee11db0664fd010b69670b94e9ec`. This matches its published LFS object hash; it is a third-party mirror, not an independently verified byte-for-byte original OpenSLR archive. Original Golos license/attribution remains recorded. Common Voice imported: **0**. Natural standalone domain answers: **0**. A frozen short subset contains **14** of these same recordings, not 14 additional samples. No noisy or child subset was fabricated.

Reproduce the source selection after obtaining the public Parquet file:

```bash
python3 tools/extract_golos_parquet.py /data/farfield.parquet \
  voice-benchmark/public-source/golos/seed3201 --count 20 --seed 3201
python3 tools/voice_cloud_benchmark.py import-golos \
  --manifest voice-benchmark/public-source/golos/seed3201/manifest.jsonl \
  --audio-root voice-benchmark/public-source/golos/seed3201 \
  --output voice-benchmark/public/golos-farfield-20 \
  --license https://github.com/sberdevices/golos/blob/master/license/en_us.pdf \
  --source-reference https://huggingface.co/datasets/bonlime/golos-test \
  --privacy public --population public-general --far-field
```

The extractor requires `pyarrow`, sorts source IDs, shuffles before inference, and records frozen IDs/archive hash. [Frozen source IDs](benchmarks/ms32-golos/source-selection.json), [device/model/audio receipts](benchmarks/ms32-golos/receipts.json), [full comparison](benchmarks/ms32-golos/comparison.json), and [per-sample error table](benchmarks/ms32-golos/errors.csv) are retained without audio or credentials. The receipt identifies the exact manifest and audio used in this run.

On ADT-3 / H728 (`sun55iw3p1`), Android 14, four CPU threads: **120/120** native configuration/sample evaluations completed, instrumented test passed in **150.096 s** including initialization/reporting. All six configurations produced identical transcripts: **8/20 exact (40%)**, **WER 37/82 = 45.12%**, **S/D/I = 5/32/0**.

| Configuration | STT median ms |
|---|---:|
| A greedy | 171.8 |
| B beam | 209.6 |
| C 0.5 | 205.3 |
| C 1.0 | 204.0 |
| C 1.5 | 222.1 |
| C 2.0 | 204.8 |

Greedy p95 **313.7 ms**, median RTF **0.0944**. These times exclude initialization/VAD and include zero-duration recognition work when VAD finds no speech, so they are **not** independent full-utterance acoustic-model throughput measurements. **7/20 recordings produced no VAD segments**. The measured accuracy belongs to the complete existing VAD→STT pipeline, not Zipformer in isolation; deletion errors warrant a separate VAD/segmentation investigation before blaming decoder search.

All samples were OPEN, so C hotwords were disabled; this run cannot estimate contextual bias gains/damage. Qwen mode was enabled but **0 inference calls** were eligible, so no semantic accuracy or Qwen improvement is claimed. All **60 cloud matrix entries were SKIPPED_CREDENTIALS**, zero uploads/cost; cloud adapter behavior is mock-tested, not live-certified. There is no cloud-vs-local accuracy conclusion.

Validation also passed the updated instrumented APK build and **16 distinct host unit tests** covering credentials, privacy/cost, complete context, cache/deduplication, in-flight resume, errors, provider response normalization, source import and frozen subset replay. Current evidence supports retaining production settings and investigating VAD misses; it does not support promoting beam/hotwords or estimating child accuracy from this tiny sample.

## When XVF3800 arrives

1. Connect it and verify the recorder input route/PCM levels; make a playback check before accepting any samples.
2. Record the same complete phrase list with H340 and XVF3800 where available, under matched distance/noise conditions; retain microphone, speaker ID and session metadata. Keep adult and consented child sessions separate and private.
3. Freeze IDs and run all six local configurations, then `run --qwen` on those same WAVs. Inspect VAD misses alongside raw and semantic errors; never use the expected answer as a hint.
4. Compare paired samples, UNKNOWN/false-accept rates and latency. If cloud reference is wanted, first review the dry-run and privacy decision, then explicitly provide both private-upload and cost flags. Keep a held-out session before considering any production change.
