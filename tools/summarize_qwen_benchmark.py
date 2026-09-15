#!/usr/bin/env python3
"""Summarize the last QwenLatencyInstrumentedTest run in a saved logcat file.

Usage: python3 tools/summarize_qwen_benchmark.py path/to/run-log.txt
Cold = first request after engine/prefix initialization; warm excludes that request.
p95 uses the nearest-rank definition (ceil(0.95 * N)). Times are milliseconds.
"""
import json
import math
import re
import statistics
import sys
from pathlib import Path


def summarize(path):
    text = Path(path).read_text()
    start = text.rfind("qwenNativeReady")
    if start < 0:
        raise ValueError(f"No Qwen run in {path}")
    text = text[start:]
    profiles = [dict((k, float(v)) for k, v in re.findall(r"(\w+)=([\d.]+)", line))
                for line in text.splitlines() if "QWEN_PROFILE" in line]
    answers = re.findall(r'BENCH result.*input="([^"]*)" output="([^"]*)" expected="([^"]*)" wallMs=([\d.]+)', text)
    if not profiles or not answers:
        raise ValueError("Incomplete run: inspect the raw log")
    by_request = {int(p["request"]): p for p in profiles}
    for index, (_, actual, _, _) in enumerate(answers, 1):
        if index not in by_request and not actual.startswith("ERROR:"):
            raise ValueError("Missing profile for a successful request")

    def distribution(values):
        warm = values[1:]
        return {"cold": values[0], "warm_n": len(warm), "warm_median": statistics.median(warm),
                "warm_p95": sorted(warm)[math.ceil(.95 * len(warm)) - 1], "warm_max": max(warm)}

    result = {"file": str(path), "requests": len(answers),
              "native_profiles": len(profiles),
              "correct": sum(actual == expected for _, actual, expected, _ in answers),
              "wall_ms": distribution([float(a[3]) for a in answers]),
              "native_ms": {k: distribution([p[k] for p in profiles]) for k in
                            ("tokenizationMs", "prefixPromptEvalMs", "userPromptEvalMs", "generationMs", "totalNativeMs")},
              "tokens": {k: sorted({int(p[k]) for p in profiles}) for k in
                         ("totalPromptTokens", "constantPrefixTokens", "prefixEvaluatedTokens", "newUserTokens", "generatedTokens")},
              "failures": [{"input": i, "actual": a, "expected": e} for i, a, e, _ in answers if a != e],
              "requests_detail": [{"input": i, "output": a, "expected": e, "wallMs": float(w), **p}
                                  for index, (i, a, e, w) in enumerate(answers, 1)
                                  for p in [by_request.get(index, {"request": index, "nativeProfileUnavailable": True})]]}
    sensors = {}
    for key in ("freq0", "freq4", "temp0", "temp1"):
        values = [int(v) for v in re.findall(rf"\b{key}=(\d+)", text)]
        sensors[key] = {"min": min(values), "max": max(values)} if values else "unavailable"
    result["sensors_kHz_milliC"] = sensors
    return result


if __name__ == "__main__":
    print(json.dumps([summarize(path) for path in sys.argv[1:]], ensure_ascii=False, indent=2))
