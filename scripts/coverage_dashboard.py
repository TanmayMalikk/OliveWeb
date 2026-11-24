#!/usr/bin/env python3
"""Build a browsable project coverage and test report from local test artifacts."""
import argparse
import json
import os
import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass
from html import escape
from pathlib import Path
from urllib.parse import quote

from check_coverage import jacoco_line_coverage, lcov_line_coverage

ROOT = Path(__file__).resolve().parent.parent
REPORTS = ROOT / 'build/reports'
OUTPUT = REPORTS / 'coverage/index.html'
WATCH_CLASS = 'com.olive.app.wear.WatchFitnessAccessibilityTest'


@dataclass
class TestResult:
    suite: str
    name: str
    status: str
    time: str
    report: Path

    @property
    def key(self):
        return self.suite, self.name


def junit_reports(paths):
    results = {}
    for path in sorted(paths):
        if not path.is_file():
            continue
        for case in ET.parse(path).iter('testcase'):
            status = next((label for tag, label in [('failure', 'Failed'), ('error', 'Error'),
                           ('skipped', 'Skipped')] if case.find(tag) is not None), 'Passed')
            result = TestResult(case.get('classname', ''), case.get('name', ''),
                                status, case.get('time', ''), path)
            results[result.key] = result
    return results


def watch_results(path):
    if not path.is_file():
        return {}
    results = {}
    fields = {}
    statuses = {'0': 'Passed', '-1': 'Error', '-2': 'Failed', '-3': 'Skipped', '-4': 'Skipped'}
    for line in path.read_text(encoding='utf-8-sig', errors='replace').splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line.removeprefix('INSTRUMENTATION_STATUS: ').partition('=')
            fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            status = statuses.get(line.split()[-1])
            if status and 'class' in fields and 'test' in fields:
                result = TestResult(fields['class'], fields['test'], status, '', path)
                results[result.key] = result
            fields.clear()
    return results


def device_lines(tests):
    aliases = {}
    for test in tests:
        canonical = device_test_key(test)
        aliases[canonical] = canonical
        aliases[f'{test.suite}#{test.name}'] = canonical
        aliases[f'{test.suite.rsplit(".", 1)[-1]}_{test.name}'] = canonical
    rows = defaultdict(dict)
    path = ROOT / 'app/build/reports/coverage/device-test-lines.tsv'
    if path.is_file():
        for row in path.read_text(encoding='utf-8').splitlines():
            test, source, numbers = row.split('\t')
            canonical = aliases.get(test)
            if canonical is None:
                continue
            results = rows[canonical]
            if source:
                results.setdefault(source, set()).update(map(int, numbers.split(',')))
    return rows


def device_test_key(test):
    return re.sub(r'[^A-Za-z0-9_.-]', '_', f'{test.suite}#{test.name}')


def executable_web_lines():
    baseline = ROOT / 'web/coverage/lcov.info'
    lines = defaultdict(set)
    file = None
    if baseline.is_file():
        for entry in baseline.read_text(encoding='utf-8').splitlines():
            if entry.startswith('SF:'):
                file = (ROOT / 'web' / entry[3:]).resolve()
            elif file is not None and entry.startswith('DA:'):
                lines[file].add(int(entry[3:].split(',')[0]))
    return lines


def browser_lines():
    baseline = executable_web_lines()
    results = {}
    for path in REPORTS.glob('web-browser-coverage/*.meta'):
        metadata = json.loads(path.read_text(encoding='utf-8'))
        key = metadata['file'], metadata['title']
        if key in results and results[key][0] >= metadata['retry']:
            continue
        if metadata['status'] != 'passed':
            continue
        coverage = json.loads(path.with_suffix('.json').read_text(encoding='utf-8'))
        files = {}
        for filename, counters in coverage.items():
            source = Path(filename).resolve()
            hits = {counters['statementMap'][key]['start']['line']
                    for key, count in counters['s'].items() if count > 0}
            hits &= baseline.get(source, set())
            if hits:
                files[source.relative_to(ROOT / 'web').as_posix()] = hits
        results[key] = metadata['retry'], files
    return {key: files for key, (_, files) in results.items()}


def link(path, title):
    href = quote(Path(os.path.relpath(path, OUTPUT.parent)).as_posix())
    return f'<a href="{href}">{escape(title)}</a>' if path.is_file() else escape(title)


def line_ranges(numbers):
    ranges = []
    for number in sorted(numbers):
        if ranges and ranges[-1][1] + 1 == number:
            ranges[-1][1] = number
        else:
            ranges.append([number, number])
    return ', '.join(str(start) if start == end else f'{start}–{end}' for start, end in ranges)


def render_hits(hits):
    if hits is None:
        return 'Suite only'
    count = sum(map(len, hits.values()))
    if not count:
        return '0 lines recorded'
    files = ''.join(f'<li><code>{escape(file)}</code><br>{line_ranges(lines)}</li>'
                    for file, lines in sorted(hits.items()))
    return f'<details><summary>{count:,} lines in {len(hits):,} files</summary><ul>{files}</ul></details>'


def render_tests(label, results, get_lines):
    if not results:
        return f'<section><h2>{label}</h2><p>No results available.</p></section>'
    counts = {status: sum(result.status == status for result in results.values())
              for status in ('Passed', 'Failed', 'Error', 'Skipped')}
    summary = ' · '.join(f'{count} {status.lower()}' for status, count in counts.items() if count)
    rows = []
    for result in sorted(results.values(), key=lambda row: row.key):
        hits = render_hits(get_lines(result)) if result.status == 'Passed' else '—'
        status = link(result.report, result.status) if result.status != 'Passed' else result.status
        rows.append(f'<tr data-test><td>{escape(result.name)}<small>{escape(result.suite)}</small></td>'
                    f'<td>{status}</td><td>{escape(result.time or "—")}</td><td>{hits}</td></tr>')
    return (f'<section><h2>{label}</h2><p>{len(results)} tests · {summary}</p><table><thead><tr>'
            '<th>Test</th><th>Result</th><th>Seconds</th><th>Source lines hit</th></tr></thead>'
            f'<tbody>{"".join(rows)}</tbody></table></section>')


def coverage_card(label, raw, html, reader, scope):
    if not raw.is_file():
        return f'<section class="card"><h2>{label}</h2><p>No report available.</p></section>'
    hit, total = reader(raw)
    percent = f'{100 * hit / total:.2f}%' if total else '—'
    return (f'<section class="card"><h2>{label}</h2><strong>{percent}</strong>'
            f'<p>{hit:,} / {total:,} lines · {scope}</p>{link(html, "Detailed coverage")}</section>')


def android_card(module, label, device):
    prefix = ROOT / module / 'build/reports/coverage'
    directory = prefix / ('combinedDebug' if device else 'test/debug')
    page = directory / ('html/index.html' if device else 'index.html')
    return coverage_card(label, directory / 'report.xml', page, jacoco_line_coverage,
                         'unit + device tests' if device else 'unit tests')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phone', action='store_true', help='Include phone device tests and combined coverage')
    parser.add_argument('--watch', action='store_true', help='Include Wear device tests and combined coverage')
    parser.add_argument('--skip-rules', action='store_true', help='The current run skipped Firebase Rules tests')
    parser.add_argument('--include-reruns', action='store_true', help='Use separately recorded local rerun results')
    args = parser.parse_args()
    cards = [android_card('app', 'Android phone', args.phone), android_card('wear', 'Wear OS', args.watch),
             coverage_card('Web', ROOT / 'web/coverage/combined/lcov.info', ROOT / 'web/coverage/combined/index.html',
                           lcov_line_coverage, 'unit + browser lines'),
             coverage_card('Cloud Functions', ROOT / 'functions/coverage/lcov.info', ROOT / 'functions/coverage/index.html',
                           lcov_line_coverage, 'unit tests')]
    sections = []
    for module, label in [('app', 'Android unit tests'), ('wear', 'Wear OS unit tests')]:
        results = junit_reports((ROOT / module / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
        sections.append(render_tests(label, results, lambda _: None))
    if args.phone:
        results = junit_reports((REPORTS / 'device-ui-tests').glob('*.xml'))
        results = {key: row for key, row in results.items() if row.suite != WATCH_CLASS}
        if args.include_reruns:
            reruns = junit_reports((REPORTS / 'device-regressions').glob('*.xml'))
            results.update({key: row for key, row in reruns.items() if key in results})
        lines = device_lines(results.values())
        sections.append(render_tests('Phone device tests', results, lambda row: lines.get(device_test_key(row))))
    if args.watch:
        log = REPORTS / ('watch-regression.log' if args.include_reruns else 'device-ui-tests/wear-instrumentation.log')
        results = watch_results(log) or junit_reports([REPORTS / 'device-ui-tests' / f'{WATCH_CLASS}.xml'])
        sections.append(render_tests('Wear OS device tests', results, lambda _: None))
    browser = browser_lines()
    suites = [('Web unit tests', 'web-unit'), ('Web browser tests', 'web-browser'),
              ('Cloud Functions tests', 'functions')]
    if not args.skip_rules:
        suites.append(('Firebase rules and callable integration tests', 'firebase-rules'))
    for label, filename in suites:
        get_lines = (lambda row: browser.get(row.key)) if filename == 'web-browser' else (lambda _: None)
        sections.append(render_tests(label, junit_reports([REPORTS / f'{filename}-tests.xml']), get_lines))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    summary = ('Includes recorded targeted reruns after the full device run.' if args.include_reruns else '')
    OUTPUT.write_text(f'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Olive · Project test coverage</title>
<style>
*{{box-sizing:border-box}}body{{background:#f6f8f2;color:#213321;font:16px system-ui;margin:0}}
main{{max-width:76rem;margin:auto;padding:2rem}}h1{{margin:0;font-size:2.4rem}}h2{{font-size:1.15rem}}
.cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(13rem,1fr));gap:1rem}}
.card{{background:#fff;border:1px solid #c6d2ba;border-radius:1rem;padding:1rem;margin-top:.8rem}}
.card strong{{font-size:1.7rem}}a{{color:#285322}}section{{margin-top:2rem}}
table{{width:100%;border-collapse:collapse;background:white}}th,td{{text-align:left;padding:.65rem;border-bottom:1px solid #dce5d6;vertical-align:top;overflow-wrap:anywhere}}th{{background:#e5eddc}}
small{{display:block;color:#475b44}}details ul{{padding-left:1.3rem}}details li{{margin-top:.5rem}}summary{{cursor:pointer}}
input{{font:inherit;padding:.6rem;width:min(100%,26rem);border:1px solid #52694c;border-radius:.4rem}}
</style>
<main><h1>Project test coverage</h1>{f'<p>{escape(summary)}</p>' if summary else ''}<div class="cards">{''.join(cards)}</div>
<p>Expand a test to see the source lines it hit when an individual coverage snapshot is available.
“Suite only” means coverage was collected for the suite, or individual line data is unavailable.
The Firebase emulator suite reports test results because its runner does not produce code line coverage.</p>
<p><label for="filter">Filter tests</label><br><input id="filter" type="search" placeholder="Test name, class, or result"></p>
{''.join(sections)}</main>
<script>
const filter = document.getElementById('filter');
filter.addEventListener('input', () => {{
  const query = filter.value.toLocaleLowerCase().trim();
  for (const row of document.querySelectorAll('[data-test]')) {{
    row.hidden = ![row.cells[0], row.cells[1]].some(cell => cell.textContent.toLocaleLowerCase().includes(query));
  }}
}});
</script></html>''', encoding='utf-8')
    print(f'Project coverage dashboard: {OUTPUT}')


if __name__ == '__main__':
    main()
