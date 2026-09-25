#!/usr/bin/env python3
"""Gradle のテスト結果(JUnit XML)をモジュール × テストタスク別に集計し、Markdown の表を出力する。

使い方: python3 scripts/ci/test-summary.py [ルート] >> "$GITHUB_STEP_SUMMARY"
対象は <module>/build/test-results/<task>/*.xml(jvmTest / jsNodeTest / linuxX64Test / macosArm64Test / test など)。
"""

import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
totals = defaultdict(lambda: {"tests": 0, "failures": 0, "skipped": 0})

for xml_file in sorted(root.glob("**/build/test-results/*/*.xml")):
    if "node_modules" in xml_file.parts:
        continue
    results_dir = xml_file.parent.parent  # <module>/build/test-results
    module = results_dir.parent.parent.relative_to(root).as_posix()
    task = xml_file.parent.name
    try:
        suites = ET.parse(xml_file).getroot()
    except ET.ParseError:
        continue
    for suite in suites.iter("testsuite"):
        entry = totals[(module, task)]
        entry["tests"] += int(suite.get("tests", 0))
        entry["failures"] += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        entry["skipped"] += int(suite.get("skipped", 0))

print("## テスト結果(モジュール × ターゲット)\n")
if not totals:
    print("テスト結果の XML が見つかりませんでした。")
    sys.exit(0)
print("| モジュール | タスク | 件数 | 失敗 | スキップ |")
print("|---|---|---:|---:|---:|")
for (module, task), entry in sorted(totals.items()):
    print(f"| {module} | {task} | {entry['tests']} | {entry['failures']} | {entry['skipped']} |")
print(
    "\n注: jsNodeTest の件数は context(コンテナ)と Kotest Executor も 1 件として数えるため、"
    "他のターゲットより多くなる。jvmTest には JVM 専用のテストが含まれうる。"
)
