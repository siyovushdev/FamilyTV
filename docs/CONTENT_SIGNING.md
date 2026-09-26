# Content Signing

FamilyTV uses **Ed25519** asymmetric signatures so devices can trust a publisher,
not only file integrity (SHA-256).

## Roles

| Artifact | Proves |
|----------|--------|
| SHA-256 of ZIP | Bytes were not corrupted in transit |
| Ed25519 signature of index payload | Index (and declared package hashes) came from a trusted publisher |

SHA-256 alone is **not** authenticity.

## Keys

- **Private key**: host-only (`.secrets/content-signing/`). Never in APK, never in git.
- **Public key**: embedded as `app/src/main/assets/content/trust/dev-publisher.pub.b64`
  with `keyId=familytv-dev-1`.

Generate:

```bash
python3 tools/signing/generate_keys.py --out .secrets/content-signing --install-public
```

Sign / verify:

```bash
python3 tools/signing/sign.py sign payload.json --out index.json
python3 tools/signing/sign.py verify index.json --pk .secrets/content-signing/dev-publisher.ed25519.pk
```

## Canonical payload

Signature covers UTF-8 JSON with:

- sorted object keys
- compact separators `,` `:`
- `ensure_ascii=False` (Unicode preserved)

Implemented in `tools/signing/canonical.py` and `CanonicalJson` (Kotlin). Must stay aligned.

## Envelope schema (`schemaVersion=1`)

```json
{
  "schemaVersion": 1,
  "keyId": "familytv-dev-1",
  "signature": "<base64 Ed25519>",
  "payload": {
    "schemaVersion": 1,
    "indexVersion": 12,
    "generatedAt": "2026-09-19T12:00:00Z",
    "publisherId": "familytv-dev",
    "packages": [ /* RemotePackageRecord */ ]
  }
}
```

## Key rotation (future)

1. Add new public key under a new `keyId` to the trust store (keep old during transition).
2. Publish indexes signed with the new key.
3. After devices update, remove the old key in a later app release.
4. Never reuse keyIds for different key material.

Do not build full PKI yet — `ContentTrustStore` is intentionally small.
