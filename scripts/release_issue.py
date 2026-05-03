#!/usr/bin/env python3
"""Helpers for the release-from-issue automation.

Subcommands:

    discover                 Emit one FQN per line for each instrumented
                             test class under app/src/androidTest. Drives
                             the dynamic checklist in the release issue.

    parse-tag                Read an issue body on stdin, print the
                             `vX.Y.Z` tag the user typed in the form.

    hydrate    --tests F     Read issue body on stdin; replace the
                             UI_TESTS section with `- [ ] <FQN>` lines
                             from F (one FQN per line).

    tick       --results D   Read issue body on stdin; tick UI_TESTS
                             checkboxes for classes whose JUnit XML in D
                             has zero failures + zero errors.

    comment    --results D   Print a markdown failure summary suitable
                             for `gh issue comment`. Empty output (exit 0)
                             when nothing failed. `--run-url URL` injects
                             a back-link.

The MANUAL_QA section is opaque to every command — never read or written.

Marker discipline (issue body MUST contain these literally, one per line):

    <!-- UI_TESTS_START -->
    ...auto-managed content...
    <!-- UI_TESTS_END -->

Anything outside the markers is preserved byte-for-byte.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ET


REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
ANDROID_TEST_ROOT = REPO_ROOT / "app" / "src" / "androidTest"

UI_TESTS_START = "<!-- UI_TESTS_START -->"
UI_TESTS_END = "<!-- UI_TESTS_END -->"

# Matches the `@RunWith(AndroidJUnit4::class)` annotation. Class name is
# captured from the `class X` line that follows. Permits intermediate
# blank lines or other annotations between them.
CLASS_DECL = re.compile(r"^\s*(?:open\s+|abstract\s+|sealed\s+|data\s+)?class\s+(\w+)")


# ─── discover ─────────────────────────────────────────────────────────


def discover() -> list[str]:
    """Return FQNs of every instrumented test class.

    A "test class" here is any Kotlin file under app/src/androidTest that
    contains `@RunWith(AndroidJUnit4::class)` somewhere in the file. The
    package comes from the file's `package` declaration; the class name
    is the next `class X` token after the annotation.
    """
    out: list[str] = []
    for kt in sorted(ANDROID_TEST_ROOT.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        if "@RunWith(AndroidJUnit4::class)" not in text:
            continue
        pkg_match = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
        if not pkg_match:
            continue
        pkg = pkg_match.group(1)
        # Find the first class after the annotation. The annotation may
        # not be immediately on top of the class (e.g. a docstring lives
        # between), so scan forward from the annotation index.
        anchor = text.index("@RunWith(AndroidJUnit4::class)")
        for line in text[anchor:].splitlines():
            m = CLASS_DECL.match(line)
            if m:
                out.append(f"{pkg}.{m.group(1)}")
                break
    return out


# ─── parse-tag ────────────────────────────────────────────────────────


def parse_tag(body: str) -> str:
    """Extract the `vX.Y.Z` tag from the issue form's "Release tag" field.

    The issue form renders the tag as:

        ### Release tag

        v0.0.8

    We grab the first non-blank line after the `### Release tag` heading,
    strip whitespace, and validate the shape.
    """
    lines = body.splitlines()
    in_section = False
    for line in lines:
        stripped = line.strip()
        if not in_section:
            if stripped.lower() in ("### release tag", "### tag"):
                in_section = True
            continue
        if stripped.startswith("###"):
            break  # next section, no tag found
        if stripped:
            if not re.fullmatch(r"v\d+\.\d+\.\d+", stripped):
                raise SystemExit(
                    f"tag `{stripped}` does not match vX.Y.Z — fix the issue body and reopen"
                )
            return stripped
    raise SystemExit("no `Release tag` field found in issue body")


# ─── body editing ─────────────────────────────────────────────────────


def _split_body(body: str) -> tuple[str, str, str]:
    """Split body into (prefix, ui_tests_section, suffix).

    `ui_tests_section` is the text strictly between the START and END
    markers, EXCLUDING the marker lines themselves. The markers stay in
    the prefix and suffix so callers don't have to re-emit them.
    """
    if UI_TESTS_START not in body or UI_TESTS_END not in body:
        raise SystemExit(
            f"issue body missing required markers `{UI_TESTS_START}` / `{UI_TESTS_END}`"
        )
    start_idx = body.index(UI_TESTS_START) + len(UI_TESTS_START)
    end_idx = body.index(UI_TESTS_END)
    if end_idx <= start_idx:
        raise SystemExit("UI_TESTS_END appears before UI_TESTS_START")
    return body[:start_idx], body[start_idx:end_idx], body[end_idx:]


def hydrate(body: str, fqns: list[str]) -> str:
    """Replace the UI_TESTS section with a fresh `- [ ] FQN` checklist."""
    prefix, _, suffix = _split_body(body)
    lines = ["", *(f"- [ ] {fqn}" for fqn in fqns), ""]
    return prefix + "\n" + "\n".join(lines) + "\n" + suffix


def tick(body: str, results: dict[str, bool]) -> str:
    """Re-render the UI_TESTS checklist with `[x]` for passed classes.

    Lines that don't look like checklist entries (e.g. headings, blank
    lines, free-form notes someone wedged in there) are dropped — this
    section is workflow-owned and is fully regenerated on every run.

    A class is considered passed iff `results[fqn]` is True. Classes
    missing from `results` (e.g. a test class that wasn't run because
    the suite crashed early) stay unchecked.
    """
    prefix, section, suffix = _split_body(body)
    fqns = []
    for line in section.splitlines():
        m = re.match(r"\s*-\s*\[[ xX]\]\s+([\w.]+)", line)
        if m:
            fqns.append(m.group(1))
    rendered = ["", *(_render_line(fqn, results.get(fqn, False)) for fqn in fqns), ""]
    return prefix + "\n" + "\n".join(rendered) + "\n" + suffix


def _render_line(fqn: str, passed: bool) -> str:
    box = "[x]" if passed else "[ ]"
    return f"- {box} {fqn}"


# ─── JUnit XML parsing ────────────────────────────────────────────────


def _parse_results(results_dir: pathlib.Path) -> dict[str, dict]:
    """Walk the results dir, return {fqn: {passed, failures}}.

    AGP's `connectedDebugAndroidTest` writes ONE TEST-*.xml per device,
    not one per class. The `<testsuite name="...">` attribute is just the
    first class encountered — useless for per-class accounting. The real
    per-class info lives in each `<testcase classname="..." name="...">`.
    Group by `classname` to get the actual per-class verdict.

    `failures` is a list of `(method, message, trace_excerpt)` tuples.
    A class is "passed" iff it appeared in the results AND has zero
    failures and zero errors.
    """
    out: dict[str, dict] = {}
    for xml in sorted(results_dir.rglob("TEST-*.xml")):
        try:
            tree = ET.parse(xml)
        except ET.ParseError as e:
            print(f"warning: could not parse {xml}: {e}", file=sys.stderr)
            continue
        for case in tree.iter("testcase"):
            fqn = case.attrib.get("classname", "").strip()
            if not fqn:
                continue
            method = case.attrib.get("name", "?")
            entry = out.setdefault(fqn, {"passed": True, "failures": []})
            for problem in (*case.findall("failure"), *case.findall("error")):
                msg = problem.attrib.get("message", "").strip()
                trace = (problem.text or "").strip()
                excerpt = "\n".join(trace.splitlines()[:5])
                entry["failures"].append((method, msg, excerpt))
                entry["passed"] = False
    return out


# ─── failure comment ──────────────────────────────────────────────────


def comment(results_dir: pathlib.Path, run_url: str | None) -> str:
    """Build a markdown failure summary. Returns empty string if green."""
    parsed = _parse_results(results_dir)
    failed = {fqn: info for fqn, info in parsed.items() if not info["passed"]}
    if not failed:
        return ""
    lines: list[str] = []
    plural = "s" if len(failed) > 1 else ""
    lines.append(f"## ❌ {len(failed)} test class{plural} failed")
    lines.append("")
    if run_url:
        lines.append(f"Run: {run_url}")
        lines.append("")
    for fqn, info in failed.items():
        lines.append(f"### `{fqn}`")
        lines.append("")
        for method, msg, excerpt in info["failures"][:3]:
            lines.append(f"- **`{method}`** — {msg or '(no message)'}")
            if excerpt:
                lines.append("  ```")
                for trace_line in excerpt.splitlines():
                    lines.append(f"  {trace_line}")
                lines.append("  ```")
        if len(info["failures"]) > 3:
            lines.append(f"- _(and {len(info['failures']) - 3} more failed)_")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


# ─── CLI ──────────────────────────────────────────────────────────────


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("discover", help="emit instrumented-test FQNs to stdout")
    sub.add_parser("parse-tag", help="extract `vX.Y.Z` from issue body on stdin")

    p_hydrate = sub.add_parser("hydrate", help="seed UI_TESTS section with discovered FQNs")
    p_hydrate.add_argument("--tests", required=True, help="file with one FQN per line")

    p_tick = sub.add_parser("tick", help="mark UI_TESTS boxes from JUnit results")
    p_tick.add_argument("--results", required=True, help="dir with TEST-*.xml under it")

    p_comment = sub.add_parser("comment", help="render failure summary")
    p_comment.add_argument("--results", required=True, help="dir with TEST-*.xml under it")
    p_comment.add_argument("--run-url", default=None, help="link back to the workflow run")

    args = parser.parse_args(argv)

    if args.cmd == "discover":
        for fqn in discover():
            print(fqn)
        return 0
    if args.cmd == "parse-tag":
        print(parse_tag(sys.stdin.read()))
        return 0
    if args.cmd == "hydrate":
        body = sys.stdin.read()
        fqns = [
            line.strip()
            for line in pathlib.Path(args.tests).read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        sys.stdout.write(hydrate(body, fqns))
        return 0
    if args.cmd == "tick":
        body = sys.stdin.read()
        parsed = _parse_results(pathlib.Path(args.results))
        results = {fqn: info["passed"] for fqn, info in parsed.items()}
        sys.stdout.write(tick(body, results))
        return 0
    if args.cmd == "comment":
        sys.stdout.write(comment(pathlib.Path(args.results), args.run_url))
        return 0
    parser.error(f"unknown command: {args.cmd}")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
