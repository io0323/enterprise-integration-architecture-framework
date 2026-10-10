#!/usr/bin/env python3
"""固定したイメージのダイジェストと成果物の SHA-256 の定期確認(#35。ADR-0016 §2・§9)。.github/workflows/pin-check.yml が使う。

確かめること:
- infra/local/images.env の各イメージ(`<repository>:<tag>@sha256:<digest>`)
  1. 固定したダイジェストを今も取得できるか(上流での削除の検知)。
  2. タグが固定したものと別のダイジェストを指していないか(上流での付け直しの検知)。付け直されていれば、
     新しいダイジェストが linux/amd64 と linux/arm64 を含むか(ADR-0016 §2)も確かめ、images.env の行の候補を出す。
- infra/local/images/kafka-connect/Dockerfile の `ADD --checksum=sha256:${…}` の成果物(Maven Central・GitHub のリリース)
  3. 固定した URL から今も取得できるか(削除の検知)。
  4. 取得したファイルの SHA-256 が固定した値と一致するか(差し替えの検知)。

使い方:
  check-pins.py [--summary FILE] [--issues] [--simulate] [--fail-on-broken]
    --summary FILE    結果の表(Markdown)を FILE に追記する(GitHub の Step Summary)
    --issues          異常ごとに Issue を作る(同じ固定の Issue が開いていれば、新しい値のときだけコメントを足す)。gh を使う
    --simulate        固定した値を、取得できない値に書き換えて確かめる(Issue の作成の確認用。タイトルに [simulated] を付ける)
    --fail-on-broken  取得できない・SHA-256 が合わないものがあれば終了コード 1(PR の確認用。付け直しだけなら 0)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
IMAGES_ENV = ROOT / "infra/local/images.env"
DOCKERFILES = [ROOT / "infra/local/images/kafka-connect/Dockerfile"]
REQUIRED_PLATFORMS = {"linux/amd64", "linux/arm64"}
ZERO_SHA256 = "0" * 64
TITLE_PREFIX = "[pin-check]"
LABELS = "type:chore,area:infra"
README = "infra/local/README.md の「イメージの更新」"


@dataclass
class Finding:
    """1 つの固定の確認の結果。[problems] が空なら正常。"""

    name: str
    kind: str  # image / artifact
    pinned: str
    problems: list[str] = field(default_factory=list)
    current: str | None = None  # タグの今のダイジェスト・取得したファイルの SHA-256
    candidate: str | None = None  # images.env・Dockerfile の行の候補
    broken: bool = False  # 取得できない・SHA-256 が合わない(付け直しだけなら False)

    @property
    def ok(self) -> bool:
        return not self.problems


# ------------------------------------------------------------------ 読み取り


def parse_images(text: str) -> list[tuple[str, str, str, str]]:
    """images.env の行から (変数名, リポジトリ, タグ, ダイジェスト) を返す。"""
    pins = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        match = re.fullmatch(r"(?P<repo>[^@\s]+):(?P<tag>[^:@\s]+)@(?P<digest>sha256:[0-9a-f]{64})", value)
        if not match:
            raise ValueError(f"{name}: <repository>:<tag>@sha256:<digest> の形ではありません")
        pins.append((name, match["repo"], match["tag"], match["digest"]))
    return pins


def parse_artifacts(text: str) -> list[tuple[str, str, str]]:
    """Dockerfile の `ADD --checksum=sha256:${VAR} <url>` から (変数名, URL, SHA-256) を返す。ARG の既定値で展開する。"""
    args = dict(re.findall(r"^ARG\s+([A-Z0-9_]+)=(\S+)", text, re.MULTILINE))
    joined = re.sub(r"\\\n\s*", " ", text)
    artifacts = []
    for var, url in re.findall(r"^ADD\s+--checksum=sha256:\$\{([A-Z0-9_]+)\}\s+(\S+)", joined, re.MULTILINE):
        expanded = re.sub(r"\$\{([A-Z0-9_]+)\}", lambda m: args[m[1]], url)
        artifacts.append((var, expanded, args[var]))
    return artifacts


# ------------------------------------------------------------------ 確かめる


def imagetools(ref: str) -> dict | None:
    """`docker buildx imagetools inspect` の Manifest(取得できなければ None)。"""
    result = subprocess.run(
        ["docker", "buildx", "imagetools", "inspect", ref, "--format", "{{json .Manifest}}"],
        capture_output=True,
        text=True,
        timeout=120,
    )
    return json.loads(result.stdout) if result.returncode == 0 else None


def platforms(manifest: dict) -> set[str]:
    return {
        f"{m['platform']['os']}/{m['platform']['architecture']}"
        for m in manifest.get("manifests", [])
        if m.get("platform", {}).get("os") not in (None, "unknown")
    }


def check_image(name: str, repo: str, tag: str, digest: str) -> Finding:
    finding = Finding(name, "image", f"{repo}:{tag}@{digest}")
    if imagetools(f"{repo}@{digest}") is None:
        finding.problems.append("固定したダイジェストを取得できない(上流で削除された)")
        finding.broken = True
    current = imagetools(f"{repo}:{tag}")
    if current is None:
        finding.problems.append(f"タグ {tag} を取得できない")
        finding.broken = True
        return finding
    finding.current = current["digest"]
    if current["digest"] != digest:
        missing = REQUIRED_PLATFORMS - platforms(current)
        note = f"(新しいダイジェストに {', '.join(sorted(missing))} がない)" if missing else ""
        finding.problems.append(f"タグ {tag} が別のダイジェストに付け直された{note}")
        if not missing:
            finding.candidate = f"{name}={repo}:{tag}@{current['digest']}"
    return finding


def check_artifact(name: str, url: str, sha256: str) -> Finding:
    finding = Finding(name, "artifact", f"{url} (sha256:{sha256})")
    digest = hashlib.sha256()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "eiaf-pin-check"}), timeout=300) as response:
            for chunk in iter(lambda: response.read(1 << 20), b""):
                digest.update(chunk)
    except (urllib.error.URLError, TimeoutError) as e:
        finding.problems.append(f"取得できない({type(e).__name__})")
        finding.broken = True
        return finding
    finding.current = digest.hexdigest()
    if finding.current != sha256:
        finding.problems.append("取得したファイルの SHA-256 が固定した値と合わない(差し替え、または取得の誤り)")
        finding.broken = True
        finding.candidate = f"ARG {name}={finding.current}(差し替えの理由を上流で確かめてから使う)"
    return finding


# ------------------------------------------------------------------ 報告


def table(findings: list[Finding], simulate: bool) -> str:
    rows = [
        "## 固定したイメージと成果物の確認" + ("(simulate)" if simulate else ""),
        "",
        "| 固定 | 種類 | 結果 | 今の値 |",
        "|---|---|---|---|",
    ]
    for f in findings:
        result = "OK" if f.ok else "**" + " / ".join(f.problems) + "**"
        current = f"`{f.current[:19]}…`" if f.current else "-"
        rows.append(f"| `{f.name}` | {f.kind} | {result} | {current} |")
    return "\n".join(rows) + "\n"


def issue_body(f: Finding) -> str:
    lines = [
        f"定期確認(`.github/workflows/pin-check.yml`)で、固定した値の異常を検知しました。",
        "",
        f"- 固定: `{f.pinned}`",
        *[f"- {p}" for p in f.problems],
    ]
    if f.current:
        lines.append(f"- 今の値: `{f.current}`")
    if f.candidate:
        lines += ["", "更新の候補(対応するアーキテクチャを確かめた値):", "", "```", f.candidate, "```"]
    lines += ["", f"対応の手順は {README}(ADR-0016 §2・§9)。", "", "🤖 Generated by pin-check"]
    return "\n".join(lines)


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def report_issue(f: Finding, simulate: bool) -> str:
    """同じ固定の Issue が開いていれば、新しい値のときだけコメントを足す。なければ作る。"""
    prefix = f"{TITLE_PREFIX}{'[simulated]' if simulate else ''} {f.name}:"
    found = json.loads(gh("issue", "list", "--state", "open", "--label", "area:infra", "--search", f'"{prefix}" in:title',
                          "--json", "number,title,body,comments", "--limit", "20"))
    existing = [i for i in found if i["title"].startswith(prefix)]
    body = issue_body(f)
    if existing:
        issue = existing[0]
        seen = issue["body"] + "".join(c["body"] for c in issue["comments"])
        marker = f.current or " / ".join(f.problems)
        if marker in seen:
            return f"#{issue['number']}(報告済み)"
        gh("issue", "comment", str(issue["number"]), "--body", body)
        return f"#{issue['number']}(コメントを追加)"
    title = f"{prefix} 固定した{'イメージ' if f.kind == 'image' else '成果物'}を確認する"
    url = gh("issue", "create", "--title", title, "--label", LABELS, "--body", body).strip()
    return f"#{url.rsplit('/', 1)[-1]}(作成)"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--summary")
    parser.add_argument("--issues", action="store_true")
    parser.add_argument("--simulate", action="store_true")
    parser.add_argument("--fail-on-broken", action="store_true")
    args = parser.parse_args(argv)

    images = parse_images(IMAGES_ENV.read_text())
    artifacts = [a for path in DOCKERFILES for a in parse_artifacts(path.read_text())]
    if args.simulate:
        # 1 つ目のイメージは取得できないダイジェスト、1 つ目の成果物は合わない SHA-256 にする
        name, repo, tag, _ = images[0]
        images[0] = (name, repo, tag, "sha256:" + ZERO_SHA256)
        var, url, _ = artifacts[0]
        artifacts[0] = (var, url, ZERO_SHA256)

    findings = [check_image(*i) for i in images] + [check_artifact(*a) for a in artifacts]
    report = table(findings, args.simulate)
    print(report)
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as out:
            out.write(report)

    problems = [f for f in findings if not f.ok]
    for f in problems:
        print(f"{f.name}: {' / '.join(f.problems)}", file=sys.stderr)
        if args.issues:
            print(f"  Issue: {report_issue(f, args.simulate)}", file=sys.stderr)
    print(f"確認 {len(findings)} 件、異常 {len(problems)} 件", file=sys.stderr)
    return 1 if args.fail_on_broken and any(f.broken for f in problems) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
