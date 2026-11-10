#!/usr/bin/env python3
"""Check line coverage reports against CI thresholds and summarize the result."""
import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def jacoco_line_coverage(report_path: Path) -> tuple[int, int]:
    root = ET.parse(report_path).getroot()
    counter = next((item for item in root.findall("counter") if item.get("type") == "LINE"), None)
    if counter is None:
        raise ValueError(f"No LINE counter found in JaCoCo report: {report_path}")
    missed = int(counter.attrib["missed"])
    covered = int(counter.attrib["covered"])
    return covered, covered + missed


def lcov_line_coverage(report_path: Path) -> tuple[int, int]:
    covered = 0
    total = 0
    with report_path.open(encoding="utf-8") as report:
        for line in report:
            if line.startswith("DA:"):
                fields = line[3:].strip().split(",")
                if len(fields) < 2:
                    raise ValueError(f"Malformed LCOV line record in {report_path}: {line.strip()}")
                hits = int(fields[1])
                total += 1
                covered += hits > 0
    if total == 0:
        raise ValueError(f"No line records found in LCOV report: {report_path}")
    return covered, total


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("format", choices=("jacoco", "lcov"))
    parser.add_argument("report", type=Path)
    parser.add_argument("minimum", type=float)
    parser.add_argument("--name", required=True, help="Suite name shown in CI output")
    args = parser.parse_args()

    if not 0 <= args.minimum <= 100:
        parser.error("minimum must be between 0 and 100")

    try:
        if not args.report.is_file():
            raise FileNotFoundError(f"Coverage report not found: {args.report}")
        covered, total = (
            jacoco_line_coverage(args.report)
            if args.format == "jacoco"
            else lcov_line_coverage(args.report)
        )
        percent = covered * 100 / total
        passed = percent >= args.minimum
        result = "PASS" if passed else "FAIL"
        message = (
            f"{result}: {args.name} line coverage is {percent:.2f}% "
            f"({covered}/{total}); required minimum is {args.minimum:.2f}%."
        )
    except (ET.ParseError, OSError, ValueError, KeyError) as error:
        message = f"FAIL: Could not verify {args.name} coverage: {error}"
        print(message, file=sys.stderr)
        summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary_path:
            with open(summary_path, "a", encoding="utf-8") as summary:
                summary.write(f"- {message}\n")
        return 1

    print(message)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(f"- {message}\n")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
