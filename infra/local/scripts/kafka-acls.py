"""カタログ(contracts/catalog/*.yaml)から、Kafka のユーザーと ACL を作る(secure profile。ADR-0047)。

- `kafka-acls.py [カタログのディレクトリ]`: 1 行に 1 件、`<ユーザー> <操作> <topic|group> <名前>` を出す。
- `kafka-acls.py --plan [カタログのディレクトリ] < kafka-acls.sh --list の出力`: 今の ACL との差を
  `add <ユーザー> <操作> <topic|group> <名前>` / `remove ...` で出す(kafka-security.sh がブローカーをカタログにそろえる)。

規則(Framework 12.3。トピック単位の Produce / Consume と、Consumer Group 単位):
- style が event / cdc の連携だけを扱う(mqtt・rest などは Kafka を使わない)。
- 書く側: pattern が queue(コマンド。ADR-0006)なら senders、それ以外は provider。チャネルに Write。
- 読む側: consumers。チャネルに Read、group に Read、DLQ(`{チャネル}.dlq`)に Write。

カタログは contract-check(catalog.schema.json)で検証済みの前提で、決まった書き方(フローの対応・1 行 1 件)だけを読む。
知らない書き方があれば、黙って飛ばさずに止める(ACL が足りないまま動くのを防ぐ)。
"""
import re
import sys
from pathlib import Path

TOP = re.compile(r"^([A-Za-z]+):\s*(.*)$")
ITEM = re.compile(r"^  - (.+)$")
FLOW = re.compile(r"^\{(.*)\}$")


class CatalogError(ValueError):
    pass


def flow_map(text: str, where: str) -> dict:
    match = FLOW.match(text.strip())
    if not match:
        raise CatalogError(f"{where}: {{ key: value, ... }} の形ではありません: {text}")
    pairs = {}
    for part in match.group(1).split(","):
        key, sep, value = part.partition(":")
        if not sep:
            raise CatalogError(f"{where}: key: value の形ではありません: {part}")
        pairs[key.strip()] = value.strip()
    return pairs


def parse(text: str, where: str) -> dict:
    """必要な項目(style・pattern・provider・senders・consumers・channels)だけを読む。"""
    entry = {"senders": [], "consumers": [], "channels": []}
    section = None
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        top = TOP.match(line)
        if top:
            key, value = top.groups()
            section = key if key in ("senders", "consumers", "channels") and value == "" else None
            if key in ("style", "pattern"):
                entry[key] = value.strip()
            elif key == "provider":
                entry["provider"] = flow_map(value, f"{where}:{number}")
            elif key in ("senders", "consumers", "channels") and value not in ("", "[]"):
                raise CatalogError(f"{where}:{number}: {key} は 1 行 1 件で書いてください")
            continue
        item = ITEM.match(line)
        if item and section:
            value = item.group(1).strip()
            entry[section].append(value if section == "channels" else flow_map(value, f"{where}:{number}"))
    return entry


def bindings(entry: dict, where: str) -> set:
    if entry.get("style") not in ("event", "cdc"):
        return set()
    writers = entry["senders"] if entry.get("pattern") == "queue" else [entry.get("provider") or {}]
    result = set()
    for writer in writers:
        if not writer.get("system"):
            raise CatalogError(f"{where}: 書く側の system がありません")
        result |= {(writer["system"], "Write", "topic", channel) for channel in entry["channels"]}
    for consumer in entry["consumers"]:
        system, group = consumer.get("system"), consumer.get("group")
        if not system or not group:
            raise CatalogError(f"{where}: event / cdc の consumers には system と group が要ります: {consumer}")
        result.add((system, "Read", "group", group))
        for channel in entry["channels"]:
            result |= {(system, "Read", "topic", channel), (system, "Write", "topic", f"{channel}.dlq")}
    return result


def catalog_bindings(directory: Path) -> list:
    result = set()
    for path in sorted(directory.glob("INT-*.yaml")):
        result |= bindings(parse(path.read_text(encoding="utf-8"), path.name), path.name)
    return sorted(result)


RESOURCE = re.compile(r"ResourcePattern\(resourceType=(TOPIC|GROUP), name=([^,]+), patternType=LITERAL\)")
OTHER_RESOURCE = re.compile(r"ResourcePattern\(")
ENTRY = re.compile(r"\(principal=User:([^,]+), host=\*, operation=([A-Z_]+), permissionType=ALLOW\)")


def parse_listing(text: str) -> set:
    """`kafka-acls.sh --list` の出力。カタログから作る形(リテラルのトピック・グループ、全ホストの ALLOW)だけを読み、ほかの形があれば止める。"""
    result = set()
    resource = None
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        found = RESOURCE.search(line)
        if found:
            resource = (found.group(1).lower(), found.group(2))
            continue
        if OTHER_RESOURCE.search(line):
            raise CatalogError(f"カタログから作らない形の ACL があります: {line}")
        entry = ENTRY.fullmatch(line)
        if entry and resource:
            result.add((entry.group(1), entry.group(2).capitalize(), resource[0], resource[1]))
        elif line.startswith("("):
            raise CatalogError(f"カタログから作らない形の ACL があります: {line}")
    return result


def plan(desired: list, current: set) -> list:
    wanted = set(desired)
    return [("add", *b) for b in sorted(wanted - current)] + [("remove", *b) for b in sorted(current - wanted)]


def main(argv: list) -> int:
    args = argv[1:]
    planning = bool(args) and args[0] == "--plan"
    if planning:
        args = args[1:]
    directory = Path(args[0]) if args else Path(__file__).resolve().parents[3] / "contracts" / "catalog"
    try:
        lines = catalog_bindings(directory)
        if not lines:
            raise CatalogError(f"ACL が 0 件です({directory})")
        output = plan(lines, parse_listing(sys.stdin.read())) if planning else lines
    except CatalogError as error:
        print(f"カタログと ACL をそろえられません: {error}", file=sys.stderr)
        return 2
    for line in output:
        print(" ".join(line))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
