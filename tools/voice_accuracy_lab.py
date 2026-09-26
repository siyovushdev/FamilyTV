#!/usr/bin/env python3
"""Local dataset transfer and on-device benchmark orchestration. No cloud services."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys

REMOTE = "/sdcard/Android/data/ru.familytv/files/voice-benchmark"
RUNNER = "ru.familytv.test/androidx.test.runner.AndroidJUnitRunner"
TEST = "ru.familytv.accuracy.AccuracyLabInstrumentedTest#benchmarkDataset"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb serial; omit when exactly one device is attached")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("record", help="Open the developer-only recorder")
    pull = sub.add_parser("pull", help="Copy dataset plus reports from device; destination must be new")
    pull.add_argument("destination", type=Path)
    push = sub.add_parser("push", help="Upload a frozen corpus to a separate device dataset directory")
    push.add_argument("source", type=Path)
    push.add_argument("--name", required=True, help="Unique remote dataset name, e.g. frozen-h340-v1")
    run = sub.add_parser("run", help="Run six ASR configurations and retrieve reports")
    run.add_argument("--dataset", default=REMOTE)
    run.add_argument("--corpus-kind", default="human", choices=["human", "public", "synthetic"])
    run.add_argument("--qwen", action="store_true", help="Also test guarded and domain-prefix Qwen normalization")
    run.add_argument("--output", type=Path, required=True, help="New local result directory")
    args = parser.parse_args()
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])

    def call(*parts, capture=False):
        return subprocess.run(adb + list(parts), check=True, text=True, capture_output=capture)

    if args.action == "record":
        call("shell", "am", "start", "-n", "ru.familytv/.accuracy.AccuracyLabActivity")
    elif args.action == "pull":
        if args.destination.exists():
            raise ValueError("Choose a new destination to avoid merging different corpora")
        args.destination.parent.mkdir(parents=True, exist_ok=True)
        call("pull", REMOTE, str(args.destination))
    elif args.action == "push":
        import re
        if not re.fullmatch(r"[A-Za-z0-9_-]+", args.name):
            raise ValueError("Use a simple unique dataset name")
        root = args.source.resolve()
        manifest = root / "manifest.jsonl"
        rows = [json.loads(line) for line in manifest.read_text().splitlines() if line.strip()]
        target = "/sdcard/Android/data/ru.familytv/files/" + args.name
        # Never merge a new corpus into an existing directory.
        call("shell", "mkdir " + shlex.quote(target))
        for relative in dict.fromkeys(row["file"] for row in rows):
            source = (root / relative).resolve()
            if not source.is_relative_to(root) or not source.is_file():
                raise ValueError("Invalid audio path: " + relative)
            remote_file = target + "/" + source.relative_to(root).as_posix()
            call("shell", "mkdir -p " + shlex.quote(str(Path(remote_file).parent)))
            call("push", str(source), remote_file)
        call("push", str(manifest), target + "/manifest.jsonl")
        print("Dataset ready:", target)
    else:
        if args.output.exists():
            raise ValueError("Result directory already exists; choose a new one")
        args.output.mkdir(parents=True)
        import datetime
        run_name = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        remote_output = REMOTE + "/runs/" + run_name
        # Android shell quoting is explicit; dataset paths are not shell code.
        command = ["am", "instrument", "-w", "-e", "class", TEST,
                   "-e", "accuracyDataset", args.dataset, "-e", "accuracyOutput", remote_output,
                   "-e", "accuracyQwen", str(args.qwen).lower(),
                   "-e", "accuracyCorpusKind", args.corpus_kind, RUNNER]
        result = call("shell", shlex.join(command), capture=True)
        (args.output / "instrumentation.txt").write_text(result.stdout + result.stderr)
        print(result.stdout)
        if "OK (1 test)" not in result.stdout or "FAILURES" in result.stdout:
            raise RuntimeError("Instrumented run failed; inspect instrumentation.txt (partial reports may exist on device)")
        for filename in ["results.json", "results.csv", "summary.json", "summary.md"]:
            call("pull", remote_output + "/" + filename, str(args.output / filename))
        receipts = {file.name: hashlib.sha256(file.read_bytes()).hexdigest()
                    for file in args.output.iterdir() if file.is_file()}
        (args.output / "report-sha256.json").write_text(json.dumps(receipts, indent=2) + "\n")
        print("Report:", args.output / "summary.md")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print("Error:", error, file=sys.stderr)
        sys.exit(1)
