#!/usr/bin/env python3
"""Extract a residual replay fixture from compat exec-worker ndjson (TC artifacts).

Input: one or more exec-worker-*.ndjson files (ExecutionLogger output, one JSON
object per line) — a parallel run produces one file per worker JVM; pass them
all (the shell glob expands to multiple arguments).
Output (LAST argument): trace-compatible file ``count<TAB>METHOD<TAB>path``
(count=1 for each tuple).

Only rows with diffCount > 0 are emitted. Dedupes by (METHOD, path) across all inputs.

Usage:
  python3 scripts/local-stands/extract-residual-fixture.py \\
    /path/to/exec-worker-*.ndjson \\
    /tmp/residual-fixture.txt
"""
from __future__ import annotations

import json
import sys
from typing import Any
from urllib.parse import urlencode


def expand_path(endpoint: str, path_params: dict[str, Any], query_params: dict[str, Any]) -> tuple[str, str]:
    method, path_template = endpoint.split(" ", 1)
    path = path_template
    for key, value in (path_params or {}).items():
        path = path.replace("{" + key + "}", str(value))
    q = {k: v for k, v in (query_params or {}).items() if k != "_weight" and v is not None}
    if q:
        path = path + "?" + urlencode(q)
    return method.upper(), path


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: extract-residual-fixture.py <exec-worker.ndjson>... <out.txt>", file=sys.stderr)
        return 2
    sources, dst = sys.argv[1:-1], sys.argv[-1]
    seen: set[tuple[str, str]] = set()
    lines: list[str] = []
    total = 0
    for src in sources:
        with open(src, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                total += 1
                rec = json.loads(line)
                if (rec.get("diffCount") or 0) <= 0:
                    continue
                method, path = expand_path(
                    rec["endpoint"],
                    rec.get("pathParams") or {},
                    rec.get("queryParams") or {},
                )
                key = (method, path)
                if key in seen:
                    continue
                seen.add(key)
                lines.append(f"1\t{method}\t{path}\n")
    with open(dst, "w", encoding="utf-8") as out:
        out.writelines(lines)
    print(f"read {total} exec rows from {len(sources)} file(s), wrote {len(lines)} unique failing tuples -> {dst}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
