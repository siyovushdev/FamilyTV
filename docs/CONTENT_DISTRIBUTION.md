# Content Distribution

Offline-first remote delivery protocol for FamilyTV packages.

```
Authoring (lessons/)
  → tools/content/build.py
  → dist/*.zip
  → tools/publisher/publish.py  (validate + sign index)
  → tools/publisher/server.py   (LAN)  OR  future CMS/CDN
  → device ContentSyncEngine
       fetch signed index → verify Ed25519
       → download ZIP to staging
       → verify size + SHA-256
       → ContentPackageStore.importZip / activate
  → LocalContentRepository.reload → Home
```

## Device components

| Class | Role |
|-------|------|
| `ContentTrustStore` | Trusted publisher public keys |
| `ContentIndexParser` / `ContentIndexVerifier` | Parse + signature + indexVersion guard |
| `ContentSyncEngine` | Sync / update listing / download+install |
| `ContentSyncPrefs` | Source URL, highest accepted indexVersion, cache |
| `ContentPackageStore` | **Only** install path (unchanged) |

## Update policy

- `MANUAL` (default): admin chooses Download/Install/Activate
- `AUTO_SAFE`: architecturally supported; does not silently replace active content during testing

## Offline-first

- Home cold launch **never** waits on network
- Uses last-known-good local catalog / installed packages
- Sync is admin-initiated (or future background job), independent of Home

## Index rollback protection

Device persists `highestAcceptedIndexVersion`. A lower signed `indexVersion` is rejected
unless admin explicitly resets the guard.

Package rollback remains a separate local `ContentPackageStore.rollback` action.

## Threat model

| Threat | Mitigation |
|--------|------------|
| MITM modifies index | Ed25519 signature fail |
| MITM modifies ZIP | SHA-256 in signed index fail |
| Unknown signer | Trust store keyId miss |
| Old index replay | highestAcceptedIndexVersion |
| Path traversal / zip-slip | Existing package validation |
| Partial/corrupt download | Staging + size/hash checks; keep previous active |
| Cleartext MITM on LAN | Debug-only + explicit flag; release requires HTTPS |
| Code execution from content | Packages are data only (no native/code) |

## Future remote CMS

Same signed protocol:

```
Local Publisher → same index/ZIP protocol → Remote CMS/CDN → device
```

When remote: HTTPS mandatory; signing keys rotate via trust store updates in the app.
