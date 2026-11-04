"""Unix test runner: discover Android methods and write device/JUnit summaries."""

import re
import sys
from datetime import datetime
from pathlib import Path
import xml.etree.ElementTree as ET


def discover(root):
    cases = []
    for path in (root / "app/src/androidTest/java").rglob("*Test.kt"):
        source = path.read_text(encoding="utf-8-sig")
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        test_class = re.search(r"^class\s+(\w+Test)\b", source, re.M)
        if package and test_class:
            for method in re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source):
                cases.append(f"{package[1]}.{test_class[1]}#{method}")
    print("\n".join(sorted(cases)), end="\n" if cases else "")


def device_result(report_dir, test_case, status, duration, output_path):
    class_name, _, name = test_case.rpartition("#")
    suite = ET.Element("testsuite", name="Android device UI", tests="1",
                       failures=str(int(status == "FAILED")),
                       skipped=str(int(status == "SKIPPED")))
    case = ET.SubElement(suite, "testcase", classname=class_name, name=name, time=duration)
    output = output_path.read_text(encoding="utf-8", errors="replace")
    # Instrumentation can emit terminal control characters, which XML 1.0 rejects.
    output = re.sub(r"[^\x09\x0a\x0d\x20-\ud7ff\ue000-\ufffd\U00010000-\U0010ffff]", "", output)
    if status == "FAILED":
        ET.SubElement(case, "failure", message="Android device UI test failed or its process crashed.").text = output
    elif status == "SKIPPED":
        ET.SubElement(case, "skipped", message=output.strip())
    filename = re.sub(r"[^A-Za-z0-9_.-]", "_", test_case) + ".xml"
    ET.ElementTree(suite).write(report_dir / "device-ui-tests" / filename, encoding="utf-8", xml_declaration=True)


def summary(root, exit_code):
    reports = root / "build/reports"
    groups = {
        "Android JVM": sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")),
        "Web unit": [reports / "web-unit-tests.xml"],
        "Web browser/accessibility": [reports / "web-browser-tests.xml"],
        "Cloud Functions": [reports / "functions-tests.xml"],
        "Firebase rules": [reports / "firebase-rules-tests.xml"],
        "Android device UI": sorted((reports / "device-ui-tests").glob("*.xml")),
    }
    results = []
    for area, paths in groups.items():
        for path in paths:
            if not path.exists():
                continue
            try:
                for case in ET.parse(path).iter("testcase"):
                    failure = case.find("failure")
                    if failure is None:
                        failure = case.find("error")
                    skipped = case.find("skipped")
                    status = "FAILED" if failure is not None else "SKIPPED" if skipped is not None else "PASSED"
                    details = ""
                    if failure is not None:
                        details = "\n".join(filter(None, [failure.get("message"), failure.text]))
                    elif skipped is not None:
                        details = skipped.get("message", "")
                    results.append((area, case.get("classname", ""), case.get("name", ""), status, details))
            except (ET.ParseError, OSError) as error:
                results.append((area, "reporting", f"Could not parse {path}", "FAILED", str(error)))

    def read_report(name):
        path = reports / name
        return path.read_text(encoding="utf-8-sig", errors="replace").strip() if path.exists() else ""

    failed_check = read_report("failed-test-check.txt")
    if not failed_check and exit_code:
        failed_check = read_report("current-test-check.txt")
    failed = [row for row in results if row[3] == "FAILED"]
    passed = [row for row in results if row[3] == "PASSED"]
    skipped = [row for row in results if row[3] == "SKIPPED"]
    recorded_ui_failure = failed_check.startswith("Android device UI test ") and any(row[0] == "Android device UI" for row in failed)
    failed_setup = bool(exit_code and failed_check and not recorded_ui_failure)
    lines = ["OLIVE BRANCH TEST REPORT", f"Generated: {datetime.now().astimezone().isoformat(timespec='seconds')}",
             f"Command exit code: {exit_code}", f"Result: {'PASSED' if not exit_code and not failed else 'FAILED'}"]
    if failed_setup:
        lines.append(f"Failed check: {failed_check}")
    lines += ["", "SUMMARY", f"Passed tests: {len(passed)}", f"Failed tests: {len(failed)}",
              f"Failed setup/build checks: {int(failed_setup)}", f"Skipped tests: {len(skipped)}", ""]
    for area in groups:
        rows = [row for row in results if row[0] == area]
        if rows:
            counts = [sum(row[3] == status for row in rows) for status in ("PASSED", "FAILED", "SKIPPED")]
            lines.append(f"{area}: {counts[0]} passed, {counts[1]} failed, {counts[2]} skipped")
    lines += ["", "FAILED TEST DETAILS"]
    if failed_setup:
        lines += [f"FAIL [Test setup/build] {failed_check}", read_report("failed-test-output.txt"),
                  "This check stopped the run before later tests could execute.", ""]
    if not failed and not failed_setup:
        lines.append("None.")
    for area, class_name, name, _, details in failed:
        lines += [f"FAIL [{area}] {class_name} :: {name}", details, ""]
    for title, prefix, rows in (("PASSED TESTS", "PASS", passed), ("SKIPPED TESTS", "SKIP", skipped)):
        lines += ["", title]
        lines += [f"{prefix} [{area}] {class_name} :: {name}" for area, class_name, name, _, _ in rows]
    destination = root / "test-reports/olive-test-summary.txt"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nTest report: {destination}")


if __name__ == "__main__":
    action, *args = sys.argv[1:]
    if action == "discover":
        discover(Path(args[0]))
    elif action == "device-result":
        device_result(Path(args[0]), args[1], args[2], args[3], Path(args[4]))
    elif action == "summary":
        summary(Path(args[0]), int(args[1]))
    else:
        raise SystemExit(f"Unknown action: {action}")
