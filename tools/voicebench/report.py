"""Reference-preserving cross-system reports; agreement is never ground truth."""
from collections import Counter, defaultdict
import csv
import json
import statistics
import math
from .corpus import atomic, clean, load, sha, inside


def edits(reference, hypothesis):
    a, b = clean(reference).split(), clean(hypothesis).split()
    table = [[(0, 0, j) for j in range(len(b)+1)]]
    for i, word in enumerate(a, 1):
        current = [(0, i, 0)]
        for j, other in enumerate(b, 1):
            if word == other:
                current.append(table[i-1][j-1])
            else:
                s, d, ins = table[i-1][j-1]
                candidates = [(s+1, d, ins)]
                s, d, ins = table[i-1][j]
                candidates.append((s, d+1, ins))
                s, d, ins = current[j-1]
                candidates.append((s, d, ins+1))
                current.append(min(candidates, key=sum))
        table.append(current)
    return table[-1][-1]


def aggregate(rows):
    successful = [r for r in rows if r['ok']]
    s = d = i = words = exact = 0
    for row in successful:
        e = edits(row['truth'], row['text'])
        s += e[0]; d += e[1]; i += e[2]
        words += len(clean(row['truth']).split())
        exact += clean(row['truth']) == clean(row['text'])
    latency = [r['latencyMs'] for r in successful if r.get('latencyMs') is not None]
    rtf = [r['rtf'] for r in successful if r.get('rtf') is not None]
    return dict(n=len(rows), successful=len(successful), failed=len(rows)-len(successful),
                exact=exact, exactAccuracy=exact/len(rows) if successful else None,
                wer=(s+d+i)/words if words else None, substitutions=s, deletions=d, insertions=i,
                werCoverage='successful results only; inspect failure count',
                latencyMedianMs=statistics.median(latency) if latency else None,
                latencyP95Ms=sorted(latency)[min(len(latency)-1, math.ceil(len(latency)*.95)-1)] if latency else None,
                rtfMedian=statistics.median(rtf) if rtf else None)


def consensus(texts):
    # Caller supplies one result per independent engine family, not decoder variants.
    counts = Counter(clean(text) for text in texts.values() if clean(text))
    return next((text for text, n in counts.items() if n >= 3), None)


def compare(args):
    corpus = load(args.dataset)
    by_id = {r['id']: r for r in corpus}
    local = json.loads(args.local.read_text()) if args.local else {'samples': []}
    cloud = json.loads(args.cloud.read_text()) if args.cloud else {'samples': []}
    if cloud.get('manifestSha256') and cloud['manifestSha256'] != sha(args.dataset / 'manifest.jsonl'):
        raise ValueError('Cloud report belongs to a different manifest')
    if local.get('run', {}).get('datasetSha256') and local['run']['datasetSha256'] != sha(args.dataset / 'manifest.jsonl'):
        raise ValueError('Local report belongs to a different manifest')
    local_by, cloud_by, metrics = defaultdict(dict), defaultdict(dict), defaultdict(list)
    for r in local['samples']:
        if r['id'] not in by_id or r['truth'] != by_id[r['id']]['truth']:
            raise ValueError('Local sample/truth does not match corpus')
        # Verify actual audio receipt when available, including legacy runner metadata.
        expected = local.get('run', {}).get('audioSha256', {}).get(r['id'])
        if expected and expected != sha(inside(args.dataset, by_id[r['id']]['file'])):
            raise ValueError('Local audio hash mismatch')
        if r['config'] in local_by[r['id']]:
            raise ValueError('Duplicate local sample/configuration')
        local_by[r['id']][r['config']] = r
        metrics['local/' + r['config']].append(dict(truth=r['truth'], text=r['rawStt'], ok=not r.get('error'), latencyMs=r.get('sttMs'), rtf=r.get('rtf')))
    for r in cloud['samples']:
        if r['id'] not in by_id or r['audioSha256'] != sha(inside(args.dataset, by_id[r['id']]['file'])):
            raise ValueError('Cloud sample/audio does not match corpus')
        name = r['provider'] + '/' + r['variant']
        if name in cloud_by[r['id']]:
            raise ValueError('Duplicate cloud sample/provider variant')
        cloud_by[r['id']][name] = r
        metrics[name].append(dict(truth=by_id[r['id']]['truth'], text=r.get('text', ''), ok=r['status'] == 'OK', latencyMs=r.get('latencyMs')))
    if local['samples'] and args.context_config not in {r['config'] for r in local['samples']}:
        raise ValueError('Selected context configuration absent from local report')
    run = local.get('run', {})
    local_complete = bool(run.get('finishedAtEpochMs')) and not run.get('cancelled') and not run.get('runError') if local['samples'] else None
    grouped_metrics = {}
    # Never pool public-general, child or synthetic populations without retaining strata.
    for system, observations in metrics.items():
        source_rows = [r for r in local['samples'] if system == 'local/' + r['config']] if system.startswith('local/') else [r for r in cloud['samples'] if system == r['provider'] + '/' + r['variant']]
        groups = defaultdict(list)
        for source, observation in zip(source_rows, observations):
            metadata = by_id[source['id']]
            for key in ('population', 'origin', 'farField', 'category'):
                groups[key + ':' + str(metadata.get(key, 'unknown'))].append(observation)
        grouped_metrics[system] = {key: aggregate(values) for key, values in groups.items()}
    analyses = []
    semantics = defaultdict(list)
    for row in corpus:
        ident, truth = row['id'], row['truth']
        l, c = local_by[ident], cloud_by[ident]
        greedy, contextual = l.get('A_greedy'), l.get(args.context_config)
        independent = {}
        if greedy and not greedy.get('error'):
            independent['local'] = greedy['rawStt']
        for name, result in c.items():
            if name.endswith('/plain') and result['status'] == 'OK':
                independent[name.split('/')[0]] = result['text']
        flags = []
        correct = lambda text: clean(text) == clean(truth)
        if len(independent) >= 2 and all(not correct(t) for t in independent.values()):
            flags.append('all_observed_systems_fail')
        cloud_texts = {n: r['text'] for n, r in c.items() if r['status'] == 'OK'}
        if greedy and not greedy.get('error'):
            plain_clouds = [t for n, t in independent.items() if n != 'local']
            if not correct(greedy['rawStt']) and len(plain_clouds) >= 2 and len({clean(t) for t in plain_clouds}) == 1:
                flags.append('local_fails_clouds_agree')
            if correct(greedy['rawStt']) and any(not correct(t) for t in plain_clouds):
                flags.append('local_succeeds_cloud_fails')
            if contextual and not contextual.get('error') and correct(greedy['rawStt']) and not correct(contextual['rawStt']):
                flags.append('local_context_damage')
        for name, result in c.items():
            plain = c.get(name.replace('/context', '/plain'))
            if name.endswith('/context') and result['status'] == 'OK' and plain and plain['status'] == 'OK' and correct(plain['text']) and not correct(result['text']):
                flags.append(name + '_damage')
        qwen = {}
        for config, result in l.items():
            if 'normalized' not in result:
                continue
            answer = result.get('normalized') or 'UNKNOWN'
            target = row.get('answerTruth')
            label = 'not_semantically_scored'
            if target is not None:
                label = ('unknown' if answer == 'UNKNOWN' else 'correct_semantic_output' if str(answer) == str(target) else 'wrong_semantic_output_possible_hallucination')
                if str(answer) == str(target) and result.get('literalAnswer') != answer:
                    label = 'semantic_correction_observed_cause_unproven'
            qwen[config] = dict(normalized=answer, classification=label)
            if target is not None:
                semantics[config].append(dict(correct=not result.get('error') and str(answer) == str(target),
                    unknown=answer == 'UNKNOWN', falseAccept=answer != 'UNKNOWN' and str(answer) != str(target)))
        analyses.append(dict(id=ident, truth=truth, population=row.get('population', 'unknown'),
           farField=row.get('farField'), category=row['category'], localRawStt=greedy and greedy['rawStt'],
           localContextualStt=contextual and contextual['rawStt'], qwenNormalized=qwen,
           cloudTexts=cloud_texts, consensusCandidate=consensus(independent), flags=flags))
    args.output.mkdir(parents=True, exist_ok=True)
    summary = {name: aggregate(rows) for name, rows in metrics.items()}
    semantic_metrics = {config: dict(n=len(values), **{key + 'Rate': sum(v[key] for v in values)/len(values) for key in ('correct', 'unknown', 'falseAccept')}) for config, values in semantics.items()}
    atomic(args.output / 'comparison.json', dict(metrics=summary, groupedMetrics=grouped_metrics, semanticMetrics=semantic_metrics, samples=analyses,
           contextConfig=args.context_config, localRunComplete=local_complete, truthUnchanged=True,
           limitations=['Confidence is provider-specific', 'Transcript agreement cannot identify acoustic causality',
                        'Public general/far-field speech does not establish child-speech accuracy']))
    fields = ['id', 'truth', 'localRawStt', 'localContextualStt', 'cloudTexts', 'qwenNormalized', 'consensusCandidate', 'flags']
    with (args.output / 'errors.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in analyses:
            values = {k: json.dumps(row[k], ensure_ascii=False) if isinstance(row[k], (dict, list)) else row[k] for k in fields}
            writer.writerow({k: "'" + v if isinstance(v, str) and v.startswith(('=', '+', '-', '@')) else v for k, v in values.items()})
    lines = ['# Voice benchmark comparison', '', 'Context configuration: `' + args.context_config + '` (fixed, not per-sample oracle).', '',
             '| System | Successful / n | Exact | WER | Median ms |', '|---|---:|---:|---:|---:|']
    if local_complete is False:
        lines.insert(2, '**Local run incomplete or completion receipt unavailable: interpret coverage explicitly.**')
    for name, m in summary.items():
        lines.append(f'| {name} | {m["successful"]}/{m["n"]} | {m["exactAccuracy"]} | {m["wer"]} | {m["latencyMedianMs"]} |')
    lines += ['', 'WER covers successful results only. Missing credentials are not recognition errors; see success counts.',
              'Qwen classifications describe outputs, not proven acoustic rescue. Consensus requires three independent engine families; truth is unchanged.',
              'Public general and far-field speech do not validate child speech. Full error table: `errors.csv`.']
    (args.output / 'comparison.md').write_text('\n'.join(lines) + '\n')
