"""NFR のレポート(docs/reports/p14-nfr.md)の「連携ごとの結果」の表を、カタログと計測の結果から作る(P14 ⑩。ADR-0048)。

- `nfr-table.py`: 表を標準出力に出す。
- `nfr-table.py --write`: レポートの `<!-- nfr-table:begin -->` と `<!-- nfr-table:end -->` の間を入れ替える。

入力:
- カタログ(contracts/catalog/INT-*.yaml): ID・名前・方式・Tier・SLO。contract-check で検証済みの前提で、決まった書き方(1 行 1 項目)だけを読む。
- 計測の結果(docs/reports/p14-nfr-integrations.json): 連携ごとの `measured`(yes / partial / no)・`result`・`verdict`・`reason`。
  数値は `make nfr-measure` の出力(tests/e2e/build/nfr/*.json)から書き写す。

カタログとの一覧の一致は `./gradlew build`(tools/architecture-test の NfrReportSpec)が確かめる。数値は検査しない。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "contracts/catalog"
RESULTS = ROOT / "docs/reports/p14-nfr-integrations.json"
REPORT = ROOT / "docs/reports/p14-nfr.md"
BEGIN = "<!-- nfr-table:begin -->"
END = "<!-- nfr-table:end -->"

FIELD = re.compile(r"^(id|name|style|tier):\s*(.+?)\s*$", re.MULTILINE)
SLO = re.compile(r'^slo:\s*\{\s*availability:\s*"([^"]+)",\s*latencyP99:\s*"([^"]+)"\s*\}', re.MULTILINE)

# Framework 18 章の Tier の目標。同期(API)と非同期(Event)で遅延の目標が違う
SYNC = {"rest", "grpc", "graphql", "webhook"}
EVENT = {"event", "cdc", "mqtt"}
TIER = {
    "1": ("99.99%", "API p99 < 300ms", "Event E2E < 5s"),
    "2": ("99.9%", "API p99 < 1s", "Event < 30s"),
    "3": ("99.5%", "バッチの SLA", "バッチの SLA"),
}
MEASURED = {"yes": "計測した", "partial": "一部", "no": "計測しない"}


def catalog() -> list[dict]:
    entries = []
    for path in sorted(CATALOG.glob("INT-*.yaml")):
        text = path.read_text(encoding="utf-8")
        fields = dict(FIELD.findall(text))
        slo = SLO.search(text)
        if not slo or set(fields) != {"id", "name", "style", "tier"}:
            raise SystemExit(f"{path.name}: id・name・style・tier・slo を読めません")
        fields["availability"], fields["latency"] = slo.groups()
        entries.append(fields)
    return entries


def framework_target(tier: str, style: str) -> str:
    availability, api, event = TIER[tier]
    if style in SYNC:
        return f"{availability} / {api}"
    if style in EVENT:
        return f"{availability} / {event}"
    return f"{availability} / 時間枠(7・9 章)"


def cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def table() -> str:
    results = json.loads(RESULTS.read_text(encoding="utf-8"))
    entries = catalog()
    missing = [e["id"] for e in entries if e["id"] not in results]
    extra = sorted(set(results) - {e["id"] for e in entries})
    if missing or extra:
        raise SystemExit(f"計測の結果とカタログの一覧が違います: 結果にない {missing} / カタログにない {extra}")
    lines = [
        "| ID | 名前 | 方式 | Tier | カタログの SLO(可用性 / p99) | Framework の Tier の目標 | 計測 | 結果 | 判定 | 計測しない理由・備考 |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for e in entries:
        r = results[e["id"]]
        lines.append(
            "| "
            + " | ".join(
                cell(v)
                for v in (
                    e["id"],
                    e["name"],
                    e["style"],
                    e["tier"],
                    f'{e["availability"]}% / {e["latency"]}',
                    framework_target(e["tier"], e["style"]),
                    MEASURED[r["measured"]],
                    r.get("result", "—"),
                    r.get("verdict", "—"),
                    r.get("reason", ""),
                )
            )
            + " |"
        )
    return "\n".join(lines)


def main() -> None:
    generated = table()
    if sys.argv[1:] == ["--write"]:
        text = REPORT.read_text(encoding="utf-8")
        start, end = text.index(BEGIN) + len(BEGIN), text.index(END)
        REPORT.write_text(text[:start] + "\n" + generated + "\n" + text[end:], encoding="utf-8")
    elif sys.argv[1:]:
        raise SystemExit("使い方: nfr-table.py [--write]")
    else:
        print(generated)


if __name__ == "__main__":
    main()
