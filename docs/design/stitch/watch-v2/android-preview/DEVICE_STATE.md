# Device state — Watch preview

Acceptance device: **real ADT-3 / Allwinner h728**, Android 14, `sun55iw3p1`.
Display: physical **1920×1080**, density **320**. The native scene reports 1920×1080, scale 1.0; layout uses reference pixels rather than density-scaled dp.

Date: 2026-09-25 (Europe/Moscow). Accepted ADB serial: `192.168.68.105:5555`.
Raw properties: `logs/device.txt`.

The former address `192.168.68.100:5555` initially refused connections. A connected A527/527S was identified during discovery, but no acceptance media in this folder comes from that device. H728 subsequently appeared in `adb devices` at `.105`; its SoC was checked before installation and is asserted by the acceptance script.

The debug APK was installed with `adb install -r`; application data was preserved. Production launcher and player remain registered. Debug Watch fixtures use IDs beginning `stitch-watch-`; only those videos were played for this evidence. Their watch positions are real entries in the existing device-scoped WatchProgressStore.

Input: **ADB D-pad keyevents only**. No physical remote or child usability session was performed. The recording is a single native `screenrecord`, stopped with SIGINT; screenshots are raw `adb exec-out screencap -p` output. There are no composited UI elements in screenshots or edited cuts in the recording.

PSS readings are snapshots taken in the same navigation process, with earlier Activities retained in the back stack. Player PSS is measured with Media3 prepared and paused. Screenrecord was active during these measurements. They are not cold-isolated per-screen allocations, a peak-memory profile, or an FPS benchmark.

No application data was cleared, and no voice models were explicitly started. See `logs/acceptance_logcat.txt`, `logs/process_maps.txt` and `logs/measurements.json` for the final run.
