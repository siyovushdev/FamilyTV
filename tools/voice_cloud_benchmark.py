#!/usr/bin/env python3
"""Public corpus preparation and optional, explicitly confirmed cloud ASR references."""
import argparse
import json
from pathlib import Path
import sys
from voicebench.corpus import import_corpus, subset, load, classify, atomic
from voicebench.cloud import run_cloud
from voicebench.providers import PROVIDERS
from voicebench.report import compare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name, kind in [('import-golos', 'golos'), ('import-commonvoice', 'commonvoice'), ('import-generic', 'generic')]:
        p = commands.add_parser(name)
        p.set_defaults(kind=kind)
        p.add_argument('--manifest', type=Path, required=True)
        p.add_argument('--audio-root', type=Path, required=True, help='Common Voice: downloaded clips/ directory')
        p.add_argument('--output', type=Path, required=True)
        p.add_argument('--dataset', default=kind)
        p.add_argument('--audio-column', default='file')
        p.add_argument('--truth-column', default='truth')
        p.add_argument('--speaker-id-column', help='Optional pseudonymous ID column; values are hashed')
        p.add_argument('--license', required=True, help='License identifier or exact license document URL')
        p.add_argument('--source-reference', required=True)
        p.add_argument('--privacy', choices=['public', 'private'], default='private')
        p.add_argument('--population', choices=['public-general', 'adult', 'child', 'unknown'], default='unknown')
        p.add_argument('--origin', choices=['natural', 'synthetic', 'extracted', 'test'], default='natural')
        p.add_argument('--cloud-safe', action='store_true', help='Explicit consent for synthetic/test fixtures only')
        p.add_argument('--far-field', action='store_true', default=None)
        p.add_argument('--seed', type=int, default=3201)
        p.add_argument('--limit', type=int, default=0, help='0 imports all; selection happens before model inference')
    p = commands.add_parser('subset')
    p.add_argument('--dataset', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--group', choices=['general', 'short', 'farfield', 'noisy'], default='general')
    p.add_argument('--count', type=int, default=200)
    p.add_argument('--seed', type=int, default=3201)
    p.add_argument('--ids', type=Path, help='Replay an existing selection.json')
    p = commands.add_parser('index')
    p.add_argument('--dataset', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p = commands.add_parser('run-cloud')
    p.add_argument('--dataset', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--cache', type=Path, default=Path('voice-benchmark/cloud-cache'))
    p.add_argument('--providers', default='yandex,azure,google')
    p.add_argument('--sample-limit', type=int, default=20)
    p.add_argument('--dry-run', action='store_true')
    p.add_argument('--confirm-cloud-cost', action='store_true')
    p.add_argument('--allow-private-cloud-upload', action='store_true')
    p.add_argument('--retry-errors', action='store_true', help='Retry explicit HTTP errors, never uncertain requests')
    p = commands.add_parser('compare')
    p.add_argument('--dataset', type=Path, required=True)
    p.add_argument('--local', type=Path)
    p.add_argument('--cloud', type=Path)
    p.add_argument('--context-config', default='C_hotwords_1.0')
    p.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command.startswith('import-'):
        if args.limit < 0:
            parser.error('--limit must be nonnegative')
        import_corpus(args)
    elif args.command == 'subset':
        if args.count < 1:
            parser.error('--count must be positive')
        subset(args)
    elif args.command == 'index':
        atomic(args.output, [dict(id=r['id'], truth=r['truth'], classification=classify(r['truth'], r.get('origin', 'natural')))
                             for r in load(args.dataset)])
    elif args.command == 'run-cloud':
        names = list(dict.fromkeys(args.providers.split(',')))
        if any(name not in PROVIDERS for name in names):
            parser.error('Providers: ' + ','.join(PROVIDERS))
        run_cloud(args.dataset, args.output, args.cache, [PROVIDERS[n]() for n in names], args.sample_limit,
                  args.confirm_cloud_cost, args.dry_run, args.allow_private_cloud_upload, args.retry_errors)
    else:
        compare(args)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as error:
        print('Error:', error, file=sys.stderr)
        sys.exit(1)
