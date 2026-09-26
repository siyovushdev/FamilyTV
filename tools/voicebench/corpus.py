"""Immutable-source corpus conversion and deterministic selection."""
import csv
import hashlib
import json
from pathlib import Path
import random
import re
import shutil
import subprocess
import wave

VOCAB = {
    'NUMBER_0_10': 'ноль один два три четыре пять шесть семь восемь девять десять'.split(),
    'COLOR': 'красный синий зелёный жёлтый'.split(),
    'YES_NO': ['да', 'нет'], 'OPEN': [],
}


def clean(text):
    return re.sub(r'\s+', ' ', re.sub(r'[.,!?;:«»"“”]', ' ', text.lower())).strip()


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def atomic(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    with tmp.open('w') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
        stream.flush()
        import os
        os.fsync(stream.fileno())
    tmp.replace(path)


def inside(root, relative):
    root = Path(root).resolve()
    target = (root / relative).resolve()
    if not target.is_relative_to(root) or not target.is_file():
        raise ValueError(f'Audio must be an existing file inside corpus: {relative}')
    return target


def wave_info(path):
    with wave.open(str(path), 'rb') as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, 16000, 'NONE'):
            raise ValueError('Expected 16 kHz mono PCM16 WAV')
        frames = audio.getnframes()
        if not 0 < frames <= 480000 or len(audio.readframes(frames)) != frames * 2:
            raise ValueError('Audio must contain 0–30 seconds of complete PCM')
        return frames / 16000


def load(root):
    rows = [json.loads(line) for line in (Path(root) / 'manifest.jsonl').read_text().splitlines() if line.strip()]
    ids = set()
    for row in rows:
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', row['id']) or row['id'] in ids:
            raise ValueError('Invalid or duplicate sample ID')
        ids.add(row['id'])
        if not isinstance(row['truth'], str) or row.get('domain', 'OPEN') not in VOCAB:
            raise ValueError('Invalid truth/domain')
        path = inside(root, row['file'])
        wave_info(path)
        if row.get('audioSha256') and row['audioSha256'] != sha(path):
            raise ValueError('Audio hash mismatch: ' + row['id'])
    return rows


def classify(text, origin='natural'):
    normalized = clean(text)
    matches = {d: [word for word in words if word in normalized.split()] for d, words in VOCAB.items() if words}
    matches = {d: words for d, words in matches.items() if words}
    domain = next((d for d, words in VOCAB.items() if normalized in words), 'OPEN')
    kind = 'synthetic/extracted' if origin != 'natural' else ('natural standalone' if domain != 'OPEN' else 'sentence-containing' if matches else 'general')
    return domain, kind, matches


def input_rows(manifest, kind):
    if kind == 'commonvoice' or manifest.suffix.lower() in ('.csv', '.tsv'):
        with manifest.open(newline='') as stream:
            return list(csv.DictReader(stream, delimiter='\t' if kind == 'commonvoice' or manifest.suffix.lower() == '.tsv' else ','))
    return [json.loads(line) for line in manifest.read_text().splitlines() if line.strip()]


def import_corpus(args):
    if args.output.exists():
        raise ValueError('Choose a new output directory; originals are never overwritten')
    rows = input_rows(args.manifest, args.kind)
    rows.sort(key=lambda r: str(r.get(args.audio_column, r.get('audio_filepath', r.get('path', '')))))
    random.Random(args.seed).shuffle(rows)
    if args.limit:
        rows = rows[:args.limit]
    args.output.mkdir(parents=True)
    (args.output / 'audio').mkdir()
    results, rejected = [], []
    for row in rows:
        relative = row.get(args.audio_column) or row.get('audio_filepath') or row.get('path')
        truth = row.get(args.truth_column, row.get('text', row.get('sentence')))
        if not isinstance(truth, str) or not relative:
            raise ValueError('Missing audio or transcript column')
        source = inside(args.audio_root, relative)
        info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams', '-of', 'json', str(source)]))
        stream = next(s for s in info['streams'] if s['codec_type'] == 'audio')
        source_hash = sha(source)
        source_id = str(row.get('id', relative))
        ident = hashlib.sha256((args.dataset + '\0' + source_id).encode()).hexdigest()[:24]
        dest = args.output / 'audio' / (ident + '.wav')
        subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-i', str(source), '-map', '0:a:0', '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', '-n', str(dest)], check=True)
        try:
            duration = wave_info(dest)
        except ValueError as error:
            dest.unlink()
            rejected.append({'sourceId': source_id, 'reason': str(error)})
            continue
        domain, category, matches = classify(truth, args.origin)
        result = dict(id=ident, file='audio/' + dest.name, truth=truth, domain=domain,
                      category=category, sourceDataset=args.dataset, sourceId=source_id,
                      sourceFile=str(relative), sourceSha256=source_hash, audioSha256=sha(dest),
                      durationMs=duration * 1000, originalSampleRate=int(stream['sample_rate']),
                      sampleRate=16000, license=args.license, sourceReference=args.source_reference,
                      farField=args.far_field if args.far_field is not None else (row.get('farField') if isinstance(row.get('farField'), bool) else None),
                      population=args.population, origin=args.origin,
                      privacy=args.privacy, cloudSafe=args.cloud_safe, domainMatches=matches)
        if isinstance(row.get('noise'), str):
            result['noise'] = row['noise']
        # Only explicitly selected pseudonymous corpus IDs, never names/demographics copied wholesale.
        if args.speaker_id_column and row.get(args.speaker_id_column):
            result['speakerId'] = hashlib.sha256(str(row[args.speaker_id_column]).encode()).hexdigest()[:24]
        if domain != 'OPEN':
            result['answerTruth'] = str(VOCAB[domain].index(clean(truth))) if domain == 'NUMBER_0_10' else clean(truth)
        results.append(result)
    if len({r['id'] for r in results}) != len(results):
        raise ValueError('Duplicate source IDs')
    write_manifest(args.output, results)
    atomic(args.output / 'selection.json', dict(seed=args.seed, ids=[r['id'] for r in results],
           sourceManifestSha256=sha(args.manifest), rejected=rejected, selectedBeforeDecoding=True))
    print(f'Imported {len(results)} samples; rejected {len(rejected)}; {sum(r["durationMs"] for r in results)/1000:.2f}s')


def write_manifest(root, rows):
    (Path(root) / 'manifest.jsonl').write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows))


def subset(args):
    rows = load(args.dataset)
    eligible = [r for r in rows if args.group == 'general' or
                (args.group == 'short' and r.get('durationMs', 1e9) <= 3000) or
                (args.group == 'farfield' and r.get('farField') is True) or
                (args.group == 'noisy' and r.get('noise') not in (None, '', 'unknown', 'quiet', 'none'))]
    if args.ids:
        ids = json.loads(args.ids.read_text())['ids']
        by_id = {r['id']: r for r in eligible}
        if len(ids) != len(set(ids)) or any(i not in by_id for i in ids):
            raise ValueError('Frozen IDs missing, duplicated, or outside requested group')
        chosen = [by_id[i] for i in ids]
    else:
        eligible.sort(key=lambda r: r['id'])
        random.Random(args.seed).shuffle(eligible)
        chosen = eligible[:args.count]
    args.output.mkdir(parents=True, exist_ok=False)
    for row in chosen:
        target = args.output / row['file']
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(inside(args.dataset, row['file']), target)
    write_manifest(args.output, chosen)
    atomic(args.output / 'selection.json', dict(seed=args.seed, group=args.group, ids=[r['id'] for r in chosen], sourceManifestSha256=sha(args.dataset / 'manifest.jsonl')))
    print(f'Frozen {len(chosen)} / {len(eligible)} eligible samples')
