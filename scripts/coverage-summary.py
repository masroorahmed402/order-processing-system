#!/usr/bin/env python3
"""Prints line/branch coverage per package from the aggregated JaCoCo report (run after `mvn verify`)."""
import collections
import csv
import pathlib
import sys

report = pathlib.Path(__file__).resolve().parent.parent / "e2e-tests/target/site/jacoco-aggregate/jacoco.csv"
if not report.exists():
    sys.exit(f"{report} not found; run `mvn verify` first")

by_package = collections.defaultdict(lambda: [0, 0, 0, 0])
with report.open() as f:
    for row in csv.DictReader(f):
        values = [int(row[k]) for k in ("LINE_MISSED", "LINE_COVERED", "BRANCH_MISSED", "BRANCH_COVERED")]
        for i, v in enumerate(values):
            by_package[row["PACKAGE"]][i] += v


def pct(covered, missed):
    total = covered + missed
    return f"{100.0 * covered / total:.1f}%" if total else "n/a"


print("| Package | Line coverage | Branch coverage |")
print("|---|---:|---:|")
totals = [0, 0, 0, 0]
for package, (lm, lc, bm, bc) in sorted(by_package.items()):
    print(f"| `{package}` | {pct(lc, lm)} | {pct(bc, bm)} |")
    totals = [t + v for t, v in zip(totals, (lm, lc, bm, bc))]
lm, lc, bm, bc = totals
print(f"| **Total** | **{pct(lc, lm)}** ({lc}/{lc + lm} lines) | **{pct(bc, bm)}** ({bc}/{bc + bm} branches) |")
