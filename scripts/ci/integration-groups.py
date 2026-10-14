#!/usr/bin/env python3
"""統合テスト(Testcontainers)を CI の matrix のグループに分ける(#24。ADR-0020)。ci.yml の integration ジョブが使う。

使い方:
  integration-groups.py <group>   そのグループの Gradle のタスク(統合テストと Kover の閾値の検証)を空白区切りで出す
  integration-groups.py --check   統合テストを持つすべてのモジュールが、ちょうど 1 つのグループに入っていることを確かめる

グループ:
- sales: 全体のカバレッジの集約(ルートの build.gradle.kts の kover(...))の対象。全体の閾値(:koverVerify)は、集約の対象の
  統合テストを同じ Gradle の実行で測る必要があるので、集約の対象はすべてこのグループに入れる。
- platform: platform/*
- participants: 注文 Saga の参加者(inventory・payment・shipping)と tools/*

統合テストを持たないモジュールの閾値は、build ジョブ(./gradlew build)で検証する。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

GROUPS: dict[str, list[str]] = {
    "sales": ["services/order/", "services/legacy-sim/", "services/legacy-order-acl/"],
    "platform": ["platform/"],
    "participants": ["services/inventory/", "services/payment/", "services/shipping/", "tools/"],
}
# 全体の閾値(ルートの kover の集約)を検証するグループ
AGGREGATE_GROUP = "sales"


def modules_with_integration_tests() -> list[str]:
    """`src/integrationTest` を持つ Gradle のモジュール(リポジトリのルートからのパス)。テストの資材の中の同名のディレクトリは除く。"""
    settings = (ROOT / "settings.gradle.kts").read_text()
    included = {p.strip(":").replace(":", "/") for p in re.findall(r'"(:[^"]+)"', settings)}
    return sorted(m for m in included if (ROOT / m / "src/integrationTest").is_dir())


def aggregated_modules() -> list[str]:
    """ルートの build.gradle.kts の kover(project(...)) の対象。"""
    text = (ROOT / "build.gradle.kts").read_text()
    return sorted(p.strip(":").replace(":", "/") for p in re.findall(r'kover\(project\("(:[^"]+)"\)\)', text))


def group_of(module: str) -> list[str]:
    return [name for name, prefixes in GROUPS.items() if any(module.startswith(p) for p in prefixes)]


def tasks(group: str) -> list[str]:
    modules = [m for m in modules_with_integration_tests() if group in group_of(m)]
    result = [f":{m.replace('/', ':')}:{task}" for m in modules for task in ("integrationTest", "koverVerify")]
    if group == AGGREGATE_GROUP:
        result.append(":koverVerify")
    return result


def check() -> list[str]:
    errors = []
    for module in modules_with_integration_tests():
        groups = group_of(module)
        if len(groups) != 1:
            errors.append(f"{module}: グループが {len(groups)} 個({', '.join(groups) or 'なし'})。GROUPS に 1 つだけ入れてください")
    for module in aggregated_modules():
        if (ROOT / module / "src/integrationTest").is_dir() and group_of(module) != [AGGREGATE_GROUP]:
            errors.append(f"{module}: 全体のカバレッジの集約の対象なので、{AGGREGATE_GROUP} に入れてください")
    return errors


def main(argv: list[str]) -> int:
    if argv == ["--check"]:
        errors = check()
        for error in errors:
            print(error, file=sys.stderr)
        print(f"統合テストを持つモジュール {len(modules_with_integration_tests())} 個、違反 {len(errors)} 件", file=sys.stderr)
        return 1 if errors else 0
    if len(argv) == 1 and argv[0] in GROUPS:
        print(" ".join(tasks(argv[0])))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
