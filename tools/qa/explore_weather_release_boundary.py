#!/usr/bin/env python3
"""Checks release outputs after :app:compileReleaseKotlin :app:mergeReleaseAssets :app:processReleaseMainManifest.

Production Explore must ship its own Activity and assets, with no debug preview entry, debug-only
assets or model references.
"""
import glob
import hashlib
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'app/build/intermediates'
OUT = ROOT / 'docs/v1/explore-weather-production/logs/release-boundary.json'
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()

manifest = (BUILD / 'merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml').read_text()
explore_entry = re.search(r'<activity[^>]*ru\.familytv\.explore\.ExploreActivity"[^>]*>', manifest, re.S)
assets = BUILD / 'assets/release/mergeReleaseAssets'
provenance = json.loads((ROOT / 'app/src/main/assets/explore_weather/provenance.json').read_text())
shipped = {f: sha(assets / 'explore_weather' / f) for f in sorted(os.listdir(assets / 'explore_weather'))}
expected = {os.path.basename(a['asset']): a['sha256'] for a in provenance['assets']}
classes_dir = BUILD / 'built_in_kotlinc/release/compileReleaseKotlin/classes'
classes = glob.glob(str(classes_dir / '**/*.class'), recursive=True)
explore_bytes = b''.join(Path(c).read_bytes() for c in classes if '/ru/familytv/explore/' in c)
forbidden = ['StitchExploreWeatherPreviewActivity', 'VoiceRuntime.get', 'AudioRecord', 'newPipeline', 'GroundedQwenMatcher', '.gguf', '.onnx']
result = {
    'tasks': ':app:compileReleaseKotlin :app:mergeReleaseAssets :app:processReleaseMainManifest',
    'manifest': {
        'exploreActivity': explore_entry is not None,
        'exploreExportedFalse': bool(explore_entry and 'android:exported="false"' in explore_entry[0]),
        'stitchEntries': re.findall(r'[\w.]*Stitch\w*', manifest),
    },
    'assets': {
        'explore_weather': shipped,
        'matchesProvenance': all(shipped.get(k) == v for k, v in expected.items()),
        'debugOnlyDirsPresent': [d for d in ('stitch_games', 'stitch_watch', 'accuracy') if (assets / d).exists()],
    },
    'classes': {
        'count': len(classes),
        'explore': sorted(os.path.relpath(c, classes_dir) for c in classes if '/ru/familytv/explore/' in c and '$' not in c),
        'stitch': [os.path.relpath(c, classes_dir) for c in classes if '/stitch/' in c],
        'exploreBytecodeMentions': {k: k.encode() in explore_bytes for k in forbidden},
    },
}
result['pass'] = (result['manifest']['exploreActivity'] and result['manifest']['exploreExportedFalse']
                  and not result['manifest']['stitchEntries'] and result['assets']['matchesProvenance']
                  and not result['assets']['debugOnlyDirsPresent'] and bool(result['classes']['explore'])
                  and not result['classes']['stitch'] and not any(result['classes']['exploreBytecodeMentions'].values()))
OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print('release boundary', 'PASS' if result['pass'] else 'FAIL', len(result['classes']['explore']), 'explore classes')
assert result['pass'], result
