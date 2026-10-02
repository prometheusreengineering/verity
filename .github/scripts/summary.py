#!/usr/bin/env python3
"""Writes a Markdown summary of a Verity report.json to the GitHub job summary and step outputs."""
import json
import os
import sys

ICONS = {"PASSED": "✅", "PREPARED": "✅", "FAILED": "❌", "CRASHED": "💥", "TIMED_OUT": "⏱️", "ERROR": "⚠️", "CANCELLED": "⏹️"}


def main(path):
    out = os.environ.get("GITHUB_OUTPUT")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if not os.path.exists(path):
        text = "### Verity\n\nNo report was produced (configuration error or crash before the run started).\n"
        if summary:
            with open(summary, "a", encoding="utf-8") as f:
                f.write(text)
        print(text)
        return
    with open(path, encoding="utf-8") as f:
        report = json.load(f)
    results = report.get("results", [])
    passed = sum(1 for r in results if r.get("status") in ("PASSED", "PREPARED"))
    lines = [f"### Verity · {report.get('profile')}: {passed}/{len(results)} passed", "",
             "| | Target | Loader version | Time | Result |", "|---|---|---|---|---|"]
    for r in results:
        status = r.get("status", "?")
        msg = (r.get("message") or "").replace("|", "\\|").replace("\n", " ")
        if len(msg) > 160:
            msg = msg[:157] + "..."
        lines.append(f"| {ICONS.get(status, '')} | `{r.get('id')}` | {r.get('loaderVersion') or ''} | "
                     f"{r.get('durationMs', 0) / 1000:.0f}s | {status.lower()}: {msg} |")
        for c in r.get("checks", []):
            if not c.get("passed"):
                cmsg = (c.get("message") or "").replace("|", "\\|")
                lines.append(f"| | ↳ `{c.get('name')}` | | | ❌ {cmsg} |")
    text = "\n".join(lines) + "\n"
    print(text)
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text)
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"passed={passed}\ntotal={len(results)}\n")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "verity-report/report.json")
