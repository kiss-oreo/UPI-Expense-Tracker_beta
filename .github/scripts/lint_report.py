#!/usr/bin/env python3
"""Make a lint failure readable without downloading the HTML report.

Reads AGP's XML lint reports and prints every Error/Fatal issue with its file:line, plus a count per
severity. GitHub only tells you the *first* lint error, so this exists to show all of them in the job
log and on the run page. Prints to stdout and (when running in Actions) to the step summary.
"""

from __future__ import annotations

import glob
import os
import xml.etree.ElementTree as ET

REPORTS = "app/build/reports"
lines: list[str] = []


def emit(text: str = "") -> None:
    lines.append(text)
    print(text, flush=True)


paths = sorted(glob.glob(f"{REPORTS}/lint-results-*.xml"))
if not paths:
    emit("No lint XML report was produced (the lint task did not get as far as writing one).")
elif not any("debug" in os.path.basename(p) for p in paths):
    emit(f"Only these lint reports exist: {', '.join(os.path.basename(p) for p in paths)}")

for path in paths:
    root = ET.parse(path).getroot()
    issues = root.findall("issue")
    errors = [i for i in issues if i.get("severity") in ("Error", "Fatal")]
    warnings = [i for i in issues if i.get("severity") == "Warning"]
    emit(f"### Lint — {os.path.basename(path)}: {len(errors)} errors, {len(warnings)} warnings, {len(issues)} issues")
    if errors:
        emit("")
        emit("Errors (each one fails the build):")
        for issue in errors:
            location = issue.find("location")
            where = f"{location.get('file')}:{location.get('line')}" if location is not None else "?"
            emit(f"- **{issue.get('id')}** {where} — {issue.get('message')}")
    emit("")

summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
