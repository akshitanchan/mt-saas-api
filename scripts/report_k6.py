#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

EXTRA_P95 = [
    "p95_auth_request_link",
    "p95_tasks_create",
    "p95_tasks_list",
    "p95_webhook_stripe",
]

def _get(d: dict[str, Any], path: list[str], default=None):
    cur: Any = d
    for p in path:
        if not isinstance(cur, dict) or p not in cur:
            return default
        cur = cur[p]
    return cur

def extract_row(p: Path) -> dict[str, Any] | None:
    try:
        data = json.loads(p.read_text())
    except Exception:
        return None

    meta = data.get("meta", {})
    k6 = data.get("k6", {})

    p95 = _get(k6, ["metrics", "http_req_duration", "values", "p(95)"])
    rps = _get(k6, ["metrics", "http_reqs", "values", "rate"])
    fail_rate = _get(k6, ["metrics", "http_req_failed", "values", "rate"])
    webhook_success = _get(k6, ["metrics", "webhook_success_rate", "values", "rate"])

    if p95 is None or rps is None:
        return None

    extras: dict[str, Any] = {}
    for k in EXTRA_P95:
        v = _get(k6, ["metrics", k, "values", "p(95)"])
        if v is not None:
            extras[k] = float(v)

    return {
        "file": p.name,
        "run_id": meta.get("run_id", "unknown"),
        "git_sha": meta.get("git_sha", "unknown"),
        "created_at": meta.get("created_at", ""),
        "vus": meta.get("vus", ""),
        "duration": meta.get("duration", ""),
        "p95_ms": float(p95),
        "rps": float(rps),
        "fail_rate": float(fail_rate) if fail_rate is not None else None,
        "webhook_success_rate": float(webhook_success) if webhook_success is not None else None,
        **extras,
    }

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="k6-results")
    ap.add_argument("--latest", action="store_true", help="only show latest run_id")
    args = ap.parse_args()

    root = Path(args.dir)
    rows = []
    for p in sorted(root.glob("*.json")):
        row = extract_row(p)
        if row:
            rows.append(row)

    if not rows:
        print("no k6 summaries found")
        return 1

    # pick latest run_id by created_at
    rows.sort(key=lambda r: (r["created_at"], r["file"]))
    if args.latest:
        latest_run = rows[-1]["run_id"]
        rows = [r for r in rows if r["run_id"] == latest_run]
        rows.sort(key=lambda r: int(r["vus"]) if str(r["vus"]).isdigit() else 0)

    # print markdown table
    print("| vus | duration | p95 (ms) | req/s | fail rate | webhook success | git | run_id | file |")
    print("|---:|:---:|---:|---:|---:|---:|:---:|:---:|:---|")
    for r in rows:
        fr = "" if r["fail_rate"] is None else f'{r["fail_rate"]:.4f}'
        ws = "" if r["webhook_success_rate"] is None else f'{r["webhook_success_rate"]:.4f}'
        print(
            f'| {r["vus"]} | {r["duration"]} | {r["p95_ms"]:.2f} | {r["rps"]:.2f} | {fr} | {ws} | {r["git_sha"]} | {r["run_id"]} | {r["file"]} |'
        )

    return 0

if __name__ == "__main__":
    raise SystemExit(main())
