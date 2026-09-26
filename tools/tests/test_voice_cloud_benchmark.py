import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock
import urllib.error
import wave
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from voicebench.cloud import run_cloud, public_safe
from voicebench.corpus import VOCAB, classify, clean, write_manifest
from voicebench.providers import Yandex, Azure, Google
from voicebench.report import consensus, edits


class BenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dataset = self.root / 'dataset'
        self.dataset.mkdir()
        with wave.open(str(self.dataset / 'one.wav'), 'wb') as wav:
            wav.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
            wav.writeframes(b'\0\0' * 1600)
        self.row = dict(id='one', file='one.wav', truth='пять', category='number', domain='NUMBER_0_10',
                        answerTruth='5', expectedAnswer='TOP_SECRET_LABEL', privacy='public', population='public-general')
        write_manifest(self.dataset, [self.row])
        self.transport = Mock(return_value={'result': 'пять'})
        self.provider = Yandex({'YANDEX_API_KEY': 'test-key'}, self.transport)

    def run_benchmark(self, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return run_cloud(self.dataset, self.root/'output', self.root/'cache', [self.provider], **kwargs)

    def test_missing_credentials_skip(self):
        self.provider = Yandex({}, self.transport)
        self.assertEqual('SKIPPED_CREDENTIALS', self.run_benchmark(confirm=True)[0]['status'])
        self.transport.assert_not_called()

    def test_confirmation_and_dry_run(self):
        self.assertEqual('NEEDS_COST_CONFIRMATION', self.run_benchmark()[0]['status'])
        self.assertEqual('DRY_RUN', self.run_benchmark(confirm=True, dry_run=True)[0]['status'])
        self.transport.assert_not_called()

    def test_private_and_child_denied(self):
        for change in ({'privacy': 'private'}, {'population': 'child'}, {'microphone': 'xvf3800'}):
            write_manifest(self.dataset, [{**self.row, **change}])
            self.assertEqual('DENIED_PRIVATE', self.run_benchmark(confirm=True)[0]['status'])
        self.transport.assert_not_called()

    def test_private_opt_in_warning(self):
        write_manifest(self.dataset, [{**self.row, 'privacy': 'private'}])
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual('OK', self.run_benchmark(confirm=True, allow_private=True)[0]['status'])
        self.assertIn('WARNING', stderr.getvalue())

    def test_cache_resume_no_second_charge(self):
        first = self.run_benchmark(confirm=True)
        second = self.run_benchmark(confirm=True)
        self.assertEqual('пять', first[0]['text'])
        self.assertTrue(second[0]['cacheHit'])
        self.assertEqual(1, self.transport.call_count)
        self.assertTrue(list((self.root/'cache').glob('*.raw.json')))

    def test_uncertain_timeout_never_retried(self):
        self.transport.side_effect = TimeoutError()
        self.assertEqual('UNCERTAIN', self.run_benchmark(confirm=True)[0]['status'])
        self.run_benchmark(confirm=True, retry_errors=True)
        self.assertEqual(1, self.transport.call_count)

    def test_http_error_explicit_resume(self):
        self.transport.side_effect = urllib.error.HTTPError('url', 429, 'quota', {}, None)
        self.assertEqual('ERROR', self.run_benchmark(confirm=True)[0]['status'])
        self.run_benchmark(confirm=True)
        self.assertEqual(1, self.transport.call_count)
        self.transport.side_effect = None
        self.assertEqual('OK', self.run_benchmark(confirm=True, retry_errors=True)[0]['status'])

    def test_google_full_context_no_expected_answer(self):
        self.transport.return_value = {'results': [{'alternatives': [{'transcript': 'пять', 'confidence': .8}]}]}
        self.provider = Google({'GOOGLE_ACCESS_TOKEN': 'test'}, self.transport)
        results = self.run_benchmark(confirm=True)
        self.assertEqual(2, len(results))
        bodies = [json.loads(call.args[2]) for call in self.transport.call_args_list]
        self.assertNotIn('speechContexts', bodies[0]['config'])
        self.assertEqual(VOCAB['NUMBER_0_10'], bodies[1]['config']['speechContexts'][0]['phrases'])
        self.assertNotIn('TOP_SECRET_LABEL', str(bodies))
        self.assertNotIn('answerTruth', str(bodies))
        self.assertEqual(.8, results[0]['confidence'])

    def test_open_context_disabled(self):
        write_manifest(self.dataset, [{**self.row, 'domain': 'OPEN'}])
        self.provider = Google({'GOOGLE_ACCESS_TOKEN': 'test'}, Mock(return_value={}))
        rows = self.run_benchmark(confirm=True)
        self.assertEqual(1, len(rows))
        self.assertEqual('disabled', rows[0]['contextStatus'])

    def test_provider_normalization(self):
        azure = Azure({})
        self.assertEqual(('пять', .9, [{'text': 'пять', 'confidence': .9}]), azure.normalize({'RecognitionStatus': 'Success', 'NBest': [{'Display': 'пять', 'Confidence': .9}]}))
        self.assertEqual(('', None, []), azure.normalize({'RecognitionStatus': 'NoMatch'}))
        with self.assertRaises(ValueError):
            azure.normalize({'error': 'failed'})
        with self.assertRaises(ValueError):
            Yandex({}).normalize({'error_code': 'ERROR'})
        text, confidence, alternatives = Google({}).normalize({'results': [{'alternatives': [{'transcript': 'один', 'confidence': .8}]}, {'alternatives': [{'transcript': 'два', 'confidence': .9}]}]})
        self.assertEqual('один два', text)
        self.assertIsNone(confidence)
        self.assertEqual(2, len(alternatives))

    def test_classification_and_metrics(self):
        self.assertEqual('natural standalone', classify('Пять!')[1])
        self.assertEqual('OPEN', classify('мой ответ пять')[0])
        self.assertEqual('sentence-containing', classify('мой ответ пять')[1])
        self.assertEqual('synthetic/extracted', classify('пять', 'extracted')[1])
        self.assertNotEqual(clean('зелёный'), clean('зеленый'))
        self.assertEqual((1, 0, 0), edits('мой ответ пять', 'мой ответ шесть'))
        self.assertEqual((0, 1, 0), edits('один два', 'один'))
        self.assertIsNone(consensus({'local': 'пять', 'google': 'пять'}))
        self.assertEqual('пять', consensus({'local': 'пять', 'google': 'Пять!', 'azure': 'пять'}))

    def test_safe_synthetic_explicit_only(self):
        self.assertFalse(public_safe({'origin': 'synthetic'}))
        self.assertTrue(public_safe({'origin': 'synthetic', 'cloudSafe': True}))
        self.assertFalse(public_safe({'origin': 'synthetic', 'cloudSafe': True, 'population': 'child'}))



class ResumeAndImportTest(unittest.TestCase):
    setUp = BenchmarkTest.setUp
    run_benchmark = BenchmarkTest.run_benchmark

    def test_duplicate_audio_preserves_ids(self):
        write_manifest(self.dataset, [self.row, {**self.row, 'id': 'two'}])
        rows = self.run_benchmark(confirm=True)
        self.assertEqual(['one', 'two'], [r['id'] for r in rows])
        self.assertEqual(1, self.transport.call_count)

    def test_inflight_tombstone_not_retried(self):
        from voicebench.cloud import key_for
        from voicebench.corpus import sha, atomic
        key = key_for(self.provider, sha(self.dataset / 'one.wav'), None)
        atomic(self.root/'cache'/(key+'.json'), {'status': 'IN_FLIGHT'})
        self.assertEqual('UNCERTAIN', self.run_benchmark(confirm=True, retry_errors=True)[0]['status'])
        self.transport.assert_not_called()

    def test_hash_mismatch_blocks_upload(self):
        write_manifest(self.dataset, [{**self.row, 'audioSha256': 'wrong'}])
        with self.assertRaises(ValueError):
            self.run_benchmark(confirm=True)
        self.transport.assert_not_called()

    def test_import_and_frozen_subset(self):
        from types import SimpleNamespace
        from voicebench.corpus import import_corpus, subset, load
        source = self.root/'source.csv'
        source.write_text('file,truth\none.wav,пять\n')
        args = SimpleNamespace(output=self.root/'import', manifest=source, kind='generic', audio_root=self.dataset,
            audio_column='file', truth_column='truth', seed=1, limit=0, dataset='test', origin='natural',
            license='test-license', source_reference='https://example.org/test', far_field=None,
            population='unknown', privacy='private', cloud_safe=False, speaker_id_column=None)
        with contextlib.redirect_stdout(io.StringIO()):
            import_corpus(args)
            selected = SimpleNamespace(dataset=args.output, output=self.root/'subset', group='general', count=1, seed=1, ids=None)
            subset(selected)
            selected.ids = selected.output/'selection.json'
            selected.output = self.root/'replay'
            subset(selected)
        imported = load(args.output)[0]
        self.assertEqual('NUMBER_0_10', imported['domain'])
        self.assertEqual('5', imported['answerTruth'])
        self.assertEqual(load(self.root/'subset'), load(self.root/'replay'))
        self.assertEqual(16000, imported['originalSampleRate'])
        self.assertTrue((self.dataset/'one.wav').exists())

if __name__ == '__main__':
    unittest.main()
