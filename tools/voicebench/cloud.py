"""Explicit cost/privacy gates and durable, conservative request journal."""
from collections import Counter
from dataclasses import asdict
import fcntl
import hashlib
import json
from pathlib import Path
import sys
import urllib.error

from .corpus import VOCAB, atomic, inside, load, sha, wave_info


def public_safe(row):
    private_signal = (row.get('population') == 'child' or row.get('speaker') == 'child' or
                      str(row.get('microphone') or '').lower() in ('h340', 'xvf3800'))
    return not private_signal and (row.get('privacy') == 'public' or
           (row.get('origin') in ('synthetic', 'test') and row.get('cloudSafe') is True))


def key_for(provider, audio_hash, context):
    return hashlib.sha256(json.dumps(dict(provider=provider.name, config=provider.config(),
        audioSha256=audio_hash, context=context), sort_keys=True).encode()).hexdigest()


def run_cloud(dataset, output, cache, providers, sample_limit=20, confirm=False,
              dry_run=False, allow_private=False, retry_errors=False):
    if sample_limit <= 0:
        raise ValueError('sample-limit must be positive')
    rows = load(dataset)[:sample_limit]
    cache, output = Path(cache), Path(output)
    cache.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    # Cross-process lock prevents duplicate requests and partially overwritten reports.
    with (cache / '.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        plan, jobs = [], []
        for row in rows:
            path = inside(dataset, row['file'])
            digest, duration = sha(path), wave_info(path)
            for provider in providers:
                contexts = [None]
                if provider.context_supported and row.get('domain', 'OPEN') != 'OPEN':
                    contexts.append(VOCAB[row['domain']])
                for context in contexts:
                    key = key_for(provider, digest, context)
                    cached_path = cache / (key + '.json')
                    cached = json.loads(cached_path.read_text()) if cached_path.exists() else None
                    state = ('CACHED' if cached and cached['status'] == 'OK' else
                             'UNCERTAIN' if cached and cached['status'] in ('IN_FLIGHT', 'UNCERTAIN') else
                             'ERROR_CACHED' if cached and not retry_errors else 'PENDING')
                    if not public_safe(row) and not allow_private:
                        state = 'DENIED_PRIVATE'
                    elif state == 'PENDING' and not provider.available():
                        state = 'SKIPPED_CREDENTIALS'
                    item = dict(id=row['id'], provider=provider.name, variant='context' if context else 'plain',
                                audioSha256=digest, context=context,
                                contextSupported=provider.context_supported,
                                contextStatus='enabled' if context else 'disabled' if provider.context_supported else 'unsupported',
                                status=state, durationSeconds=duration, cacheKey=key)
                    plan.append(item)
                    jobs.append((item, row, path, provider, cached_path, cached))
        pending = [r for r in plan if r['status'] == 'PENDING']
        # Identical audio/config requests collapse to one cache entry even within a run.
        unique = {r['cacheKey']: r for r in pending}
        summary = dict(samples=len(rows), totalAudioSeconds=sum(wave_info(inside(dataset, r['file'])) for r in rows),
                       matrixRequests=len(plan), pendingRequests=len(unique), estimatedUploadAudioSeconds=sum(r['durationSeconds'] for r in unique.values()),
                       states=dict(Counter(r['status'] for r in plan)),
                       monetaryEstimate='Not estimated: provider billing rounding and tariffs differ',
                       confirmed=confirm, dryRun=dry_run)
        print(json.dumps(summary, indent=2))
        atomic(output / 'plan.json', dict(summary=summary, requests=plan))
        if allow_private and any(not public_safe(row) for _, row, *_ in jobs):
            print('WARNING: private/local/child audio upload explicitly enabled. Confirmed requests will send audio outside this machine.', file=sys.stderr)
        results = []
        paused = set()
        for item, row, path, provider, cached_path, cached in jobs:
            result = dict(item)
            if result['status'] == 'CACHED':
                result.update({k: v for k, v in cached['result'].items() if k not in ('id', 'durationSeconds', 'cacheKey')})
                result['cacheHit'] = True
            elif result['status'] == 'PENDING':
                # Recheck after earlier duplicate audio in this run.
                if cached_path.exists():
                    cached = json.loads(cached_path.read_text())
                    if cached['status'] == 'OK':
                        result.update({k: v for k, v in cached['result'].items() if k not in ('id', 'durationSeconds', 'cacheKey')})
                        result['cacheHit'] = True
                    elif cached['status'] in ('IN_FLIGHT', 'UNCERTAIN'):
                        result['status'] = 'UNCERTAIN'
                if result['status'] == 'PENDING':
                    if provider.name in paused:
                        result['status'] = 'PAUSED_PROVIDER_ERROR'
                    elif dry_run or not confirm:
                        result['status'] = 'DRY_RUN' if dry_run else 'NEEDS_COST_CONFIRMATION'
                    else:
                        atomic(cached_path, dict(status='IN_FLIGHT', request=item))
                        try:
                            response = provider.recognize(path, context=item['context'])
                            data = asdict(response)
                            raw = data.pop('raw')
                            atomic(cache / (item['cacheKey'] + '.raw.json'), raw)
                            result.update(data, status='OK', cacheHit=False)
                            atomic(cached_path, dict(status='OK', result=result))
                        except urllib.error.HTTPError as error:
                            # An explicit HTTP rejection can be retried on a later opt-in run.
                            status = 'UNCERTAIN' if error.code >= 500 else 'ERROR'
                            result.update(status=status, error=f'HTTP {error.code}')
                            atomic(cached_path, dict(status=status, result=result))
                            paused.add(provider.name)
                        except Exception as error:
                            # Timeouts, parsing failures, and process death may follow billing.
                            # Never automatically retry these; retain the tombstone for manual review.
                            result.update(status='UNCERTAIN', error=type(error).__name__)
                            atomic(cached_path, dict(status='UNCERTAIN', result=result))
                            paused.add(provider.name)
            results.append(result)
            atomic(output / 'cloud-results.json', dict(schemaVersion=1, manifestSha256=sha(Path(dataset) / 'manifest.jsonl'),
                                                      plan=summary, samples=results))
        if not results:
            atomic(output / 'cloud-results.json', dict(schemaVersion=1, plan=summary, samples=[]))
        return results
