#!/usr/bin/env python3
"""Make a lint failure readable without downloading the HTML report.

Reads AGP's XML lint reports and prints every Error/Fatal issue with its file:line, plus a count per
severity. GitHub only tells you the *first* lint error, so this exists to show all of them in the job
log and on the run page. Prints to stdout and (when running in Actions) to the step summary.
"""

from __future__ import annotations

import argparse
import glob
import os
import pathlib
import xml.etree.ElementTree as ET

REPORTS = "app/build/reports"
lines: list[str] = []


def emit(text: str = "") -> None:
    lines.append(text)
    print(text, flush=True)


def workflow_command_escape(text: str) -> str:
    """Escape a message for a GitHub workflow command (see 'Workflow commands for GitHub Actions')."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


parser = argparse.ArgumentParser()
parser.add_argument(
    "--annotate",
    action="store_true",
    help="also publish a single check-run annotation carrying every error, so they are readable "
         "without the job log (GitHub's own 'First failure' line shows only one)",
)
parser.add_argument("--out", type=pathlib.Path, help="write the same text to a file")
args = parser.parse_args()


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

if args.annotate:
    # One annotation with everything: GitHub's own lint summary names only the first error, and the job
    # log blob is not always reachable (it redirects to a storage host). Annotations are.
    details = [line for line in lines if line.startswith("- **")]
    if details:
        body = "\n".join(line.replace("- **", "").replace("** ", ": ").replace(" — ", " | ") for line in details)
        print(f"::error title=Lint found {len(details)} error(s)::{workflow_command_escape(body)}", flush=True)

if args.out:
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines) + "\n")

summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
