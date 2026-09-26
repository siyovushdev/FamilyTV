#!/usr/bin/env python3
"""Extract a frozen, pre-inference sample from a downloaded Golos test Parquet mirror.
Requires optional pyarrow. Does not download audio or change the source archive.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random


def main():
    import pyarrow.parquet as pq
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('parquet', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--count', type=int, default=20)
    p.add_argument('--seed', type=int, default=3201)
    args = p.parse_args()
    if args.count < 1:
        p.error('count must be positive')
    data = pq.read_table(args.parquet).to_pylist()
    data.sort(key=lambda r: r['id'])
    random.Random(args.seed).shuffle(data)
    selected = data[:args.count]
    args.output.mkdir(parents=True, exist_ok=False)
    rows = []
    for row in selected:
        # Never use an archive-provided path as an output filesystem path.
        name = hashlib.sha256(row['id'].encode()).hexdigest()[:24] + '.wav'
        (args.output / name).write_bytes(row['audio']['bytes'])
        rows.append(dict(id=row['id'], audio_filepath=name, text=row['text']))
    (args.output / 'manifest.jsonl').write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows))
    with args.parquet.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    (args.output / 'selection.json').write_text(json.dumps(dict(seed=args.seed, ids=[r['id'] for r in selected],
        eligible=len(data), sourceArchiveSha256=digest), indent=2) + '\n')
    print(f'Selected {len(selected)} of {len(data)} before ASR')


if __name__ == '__main__':
    main()
