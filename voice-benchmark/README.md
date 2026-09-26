# Local voice benchmark

`manifest.jsonl` is intentionally empty: no labeled human accuracy recordings
have been collected here. Never substitute generated speech for human accuracy.
The 26 recording prompts are defined in the debug app asset
`app/src/debug/assets/accuracy/phrases.json` (repository-relative).

The developer recorder writes into the device's app-specific external directory:
`/sdcard/Android/data/ru.familytv/files/voice-benchmark/`.
Use `tools/voice_accuracy_lab.py pull NEW_DIRECTORY` to preserve a local copy.
Keep personal audio/manifests in ignored `voice-benchmark/local-NAME/` directories.
The checked-in empty manifest is a template, not a place to commit personal data.

See [Voice Accuracy Lab](../docs/VOICE_ACCURACY_LAB.md) for the full schema,
experiment commands and H340/XVF3800 comparison procedure.
