"""Provider-specific short-audio adapters. No reference labels enter this module."""
import base64
import json
import os
import re
import time
import urllib.parse
import urllib.request
import wave
from dataclasses import dataclass


@dataclass
class CloudAsrResult:
    text: str
    latencyMs: float
    confidence: object
    alternatives: list
    providerMetadata: dict
    raw: dict


class CloudAsrProvider:
    name = ''
    context_supported = False

    def __init__(self, env=None, transport=None):
        self.env = dict(os.environ if env is None else env)
        self.transport = transport or self.post

    @staticmethod
    def post(url, headers, body):
        # Refuse redirects, including redirects that might forward authorization.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        request = urllib.request.Request(url, data=body, headers=headers, method='POST')
        with urllib.request.build_opener(NoRedirect).open(request, timeout=90) as response:
            return json.load(response)

    def config(self):
        return {'adapterVersion': 1, 'api': self.name, 'language': 'ru-RU'}

    def recognize(self, wav_file, language='ru-RU', context=None):
        if language != 'ru-RU':
            raise ValueError('This benchmark is Russian-only')
        if context and not self.context_supported:
            raise ValueError('Context unsupported by selected API')
        started = time.monotonic()
        raw = self.request(wav_file, language, context)
        text, confidence, alternatives = self.normalize(raw)
        return CloudAsrResult(text, (time.monotonic() - started) * 1000, confidence,
                              alternatives, self.config(), raw)


class Yandex(CloudAsrProvider):
    name = 'yandex'

    def available(self):
        return bool(self.env.get('YANDEX_API_KEY') or (self.env.get('YANDEX_IAM_TOKEN') and self.env.get('YANDEX_FOLDER_ID')))

    def request(self, wav, language, context):
        query = dict(lang=language, format='lpcm', sampleRateHertz=16000)
        key = self.env.get('YANDEX_API_KEY')
        auth = 'Api-Key ' + key if key else 'Bearer ' + self.env['YANDEX_IAM_TOKEN']
        if not key:
            query['folderId'] = self.env['YANDEX_FOLDER_ID']
        with wave.open(str(wav), 'rb') as audio:
            pcm = audio.readframes(audio.getnframes())
        return self.transport('https://stt.api.cloud.yandex.net/speech/v1/stt:recognize?' + urllib.parse.urlencode(query),
                              {'Authorization': auth, 'Content-Type': 'application/octet-stream'}, pcm)

    def normalize(self, raw):
        if 'error_code' in raw or 'error' in raw:
            raise ValueError('Yandex returned an error response')
        if not isinstance(raw.get('result'), str):
            raise ValueError('Malformed Yandex result')
        return raw['result'], None, []


class Azure(CloudAsrProvider):
    name = 'azure'

    def available(self):
        return bool(self.env.get('AZURE_SPEECH_KEY') and self.env.get('AZURE_SPEECH_REGION'))

    def config(self):
        return {**super().config(), 'region': self.env.get('AZURE_SPEECH_REGION'), 'format': 'detailed', 'api': 'short-audio-v1'}

    def request(self, wav, language, context):
        region = self.env['AZURE_SPEECH_REGION']
        if not re.fullmatch('[a-z0-9-]+', region):
            raise ValueError('Invalid Azure region')
        url = f'https://{region}.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language={language}&format=detailed'
        return self.transport(url, {'Ocp-Apim-Subscription-Key': self.env['AZURE_SPEECH_KEY'],
                                   'Content-Type': 'audio/wav; codecs=audio/pcm; samplerate=16000'}, wav.read_bytes())

    def normalize(self, raw):
        status = raw.get('RecognitionStatus')
        if status in ('NoMatch', 'InitialSilenceTimeout', 'BabbleTimeout'):
            return '', None, []
        if status != 'Success' or not raw.get('NBest'):
            raise ValueError('Azure recognition failed or malformed')
        alternatives = [{'text': a.get('Display', a.get('Lexical', '')), 'confidence': a.get('Confidence')}
                        for a in raw['NBest']]
        return alternatives[0]['text'], alternatives[0]['confidence'], alternatives


class Google(CloudAsrProvider):
    name = 'google'
    context_supported = True

    def available(self):
        if self.env.get('GOOGLE_ACCESS_TOKEN'):
            return True
        # Do not probe metadata servers during credential-free dry runs.
        return bool(self.env.get('GOOGLE_APPLICATION_CREDENTIALS') or self.env.get('GOOGLE_USE_ADC') == '1')

    def config(self):
        return {**super().config(), 'api': 'v1-recognize', 'encoding': 'LINEAR16', 'sampleRateHertz': 16000, 'maxAlternatives': 3}

    def request(self, wav, language, context):
        token = self.env.get('GOOGLE_ACCESS_TOKEN')
        headers = {'Content-Type': 'application/json'}
        if not token:
            try:
                import google.auth
                from google.auth.transport.requests import Request
            except ImportError as error:
                raise ValueError('Install google-auth and requests for ADC') from error
            credentials, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/cloud-platform'])
            credentials.refresh(Request())
            token = credentials.token
            if credentials.quota_project_id:
                headers['x-goog-user-project'] = credentials.quota_project_id
        headers['Authorization'] = 'Bearer ' + token
        config = dict(encoding='LINEAR16', sampleRateHertz=16000, languageCode=language, maxAlternatives=3)
        if context:
            config['speechContexts'] = [{'phrases': list(context)}]
        body = dict(config=config, audio={'content': base64.b64encode(wav.read_bytes()).decode()})
        return self.transport('https://speech.googleapis.com/v1/speech:recognize', headers, json.dumps(body).encode())

    def normalize(self, raw):
        if 'error' in raw:
            raise ValueError('Google returned an error response')
        segments = raw.get('results', [])
        if not isinstance(segments, list):
            raise ValueError('Malformed Google result')
        alternatives = [segment.get('alternatives', []) for segment in segments]
        if any(not segment or 'transcript' not in segment[0] for segment in alternatives):
            raise ValueError('Malformed Google alternatives')
        text = ' '.join(segment[0]['transcript'] for segment in alternatives)
        # Segment confidences remain segment-level; never invent a combined score/N-best.
        confidence = alternatives[0][0].get('confidence') if len(alternatives) == 1 else None
        return text, confidence, alternatives


PROVIDERS = {p.name: p for p in (Yandex, Azure, Google)}
