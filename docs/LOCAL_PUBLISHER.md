# Local Publisher

Dev/LAN HTTP publisher for signed FamilyTV content. Not a production cloud.

## Publish

```bash
# Build packages first
python3 tools/content/build.py lessons/move-break

# Create signed index + copy ZIPs into publish/
python3 tools/publisher/publish.py --content dist --out publish
```

## Serve

```bash
python3 tools/publisher/server.py --content dist --host 0.0.0.0 --port 8080
```

Prints LAN URL, e.g. `http://192.168.x.x:8080/index.json`.

Re-run after content changes to bump `indexVersion` and re-sign.

## Device (debug)

Host firewall may block LAN port 8080. Prefer one of:

```bash
# A) adb reverse (reliable on H728 when firewall blocks LAN)
adb reverse tcp:8080 tcp:8080
# then use http://127.0.0.1:8080/index.json on device

# B) open firewall and use http://<pc-lan-ip>:8080/index.json
```

1. Long-press FamilyTV brand → Content Admin
2. Set source URL
3. Enable **Allow cleartext (debug LAN)** for HTTP
4. **Sync index** → **Install+Activate**

Cleartext HTTP is allowed **only** in debug builds with explicit admin flag.
Release builds require HTTPS.

## Layout

```
publish/
  index.json           # signed envelope
  index.payload.json   # unsigned debug copy
  index-version.txt
  packages/*.zip
```
