"""NFR の計測の結果(tests/e2e/build/nfr/*.json)を、GitHub Actions のジョブの概要に出す Markdown にする(P14 ⑩。ADR-0048)。

    python3 scripts/reports/nfr-summary.py [結果のディレクトリ]

回ごとに、遅延(p50 / p95 / p99 / 最大)の表と、そのほかの値(件数・割合・結果の内訳)を出す。
`pipelineCheck.usable` が false の回(計測の間に Kafka が切り離された・コネクタが止まった。nfr-postcheck.sh)は、使わない回として示す。
数値の合否は判定しない(レポートで判断する)。
"""
import json
import sys
from pathlib import Path

DIRECTORY = Path(sys.argv[1] if len(sys.argv) > 1 else "tests/e2e/build/nfr")


def cell(value: object) -> str:
    text = json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else str(value)
    return text.replace("|", "\\|").replace("\n", " ")


def summarize(path: Path) -> list[str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    check = data.get("pipelineCheck")
    if check is None:
        usable = "確認なし"
    elif check["usable"]:
        usable = "使う"
    else:
        usable = f"**使わない**(Kafka の切り離し {check['kafkaDetached']} 回 / コネクタ {check['connectors']})"
    lines = [f"### {data['scenario']}({data['startedAt']})", "", f"この回: {usable}", ""]
    latencies = {k: v for k, v in data["results"].items() if isinstance(v, dict) and "p99Ms" in v}
    others = {k: v for k, v in data["results"].items() if k not in latencies}
    if latencies:
        lines += ["| 区間 | 件数 | p50 | p95 | p99 | 最大 |", "|---|---|---|---|---|---|"]
        for name, s in latencies.items():
            lines.append(f"| {cell(name)} | {s['count']} | {s['p50Ms']:.0f} | {s['p95Ms']:.0f} | {s['p99Ms']:.0f} | {s['maxMs']:.0f} |")
        lines.append("")
    if others:
        lines += ["| 項目 | 値 |", "|---|---|"]
        lines += [f"| {cell(name)} | {cell(value)} |" for name, value in others.items()]
        lines.append("")
    return lines


def main() -> None:
    files = sorted(DIRECTORY.glob("*.json"))
    print("## NFR の計測の結果(ミリ秒)")
    print()
    if not files:
        print("結果がありません。")
        return
    for path in files:
        print("\n".join(summarize(path)))


if __name__ == "__main__":
    main()
