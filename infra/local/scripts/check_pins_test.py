"""check-pins.py の単体テスト(ネットワークと gh を使わない)。`python3 -I infra/local/scripts/check_pins_test.py`。"""
import importlib.util
import json
import sys
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("check_pins", Path(__file__).with_name("check-pins.py"))
pins = importlib.util.module_from_spec(SPEC)
sys.modules["check_pins"] = pins  # dataclass がモジュールを引けるように
SPEC.loader.exec_module(pins)

DIGEST = "sha256:" + "a" * 64


class ParseTest(unittest.TestCase):
    def test_images_env(self):
        text = f"# comment\n\nKAFKA_IMAGE=apache/kafka:4.3.1@{DIGEST}\nJAVA=gcr.io/distroless/java21-debian12:nonroot@{DIGEST}\n"
        self.assertEqual(
            pins.parse_images(text),
            [("KAFKA_IMAGE", "apache/kafka", "4.3.1", DIGEST), ("JAVA", "gcr.io/distroless/java21-debian12", "nonroot", DIGEST)],
        )

    def test_images_env_without_digest_is_rejected(self):
        with self.assertRaises(ValueError):
            pins.parse_images("KAFKA_IMAGE=apache/kafka:4.3.1\n")

    def test_dockerfile_add_checksum(self):
        text = (
            "ARG V=1.0\nARG X_SHA256=" + "b" * 64 + "\n"
            "ADD --checksum=sha256:${X_SHA256} \\\n    https://example.test/x/${V}/x-${V}.tar.gz \\\n    /tmp/x.tar.gz\n"
        )
        self.assertEqual(pins.parse_artifacts(text), [("X_SHA256", "https://example.test/x/1.0/x-1.0.tar.gz", "b" * 64)])

    def test_repository_files_are_parsed(self):
        # images.env と Kafka Connect の Dockerfile の全部の固定を読める(形が崩れたら CI で分かる)
        self.assertGreaterEqual(len(pins.parse_images(pins.IMAGES_ENV.read_text())), 19)
        names = [a[0] for path in pins.DOCKERFILES for a in pins.parse_artifacts(path.read_text())]
        self.assertEqual(sorted(names), ["APICURIO_CONVERTER_SHA256", "DEBEZIUM_POSTGRES_SHA256", "JMX_EXPORTER_SHA256"])


class FakeGh:
    def __init__(self, issues):
        self.issues = issues
        self.calls = []

    def __call__(self, *args):
        self.calls.append(args)
        if args[:2] == ("issue", "list"):
            return json.dumps(self.issues)
        if args[:2] == ("issue", "create"):
            return "https://github.com/o/r/issues/123\n"
        return ""


class ReportIssueTest(unittest.TestCase):
    def finding(self, current="sha256:" + "c" * 64):
        f = pins.Finding("KAFKA_IMAGE", "image", f"apache/kafka:4.3.1@{DIGEST}", ["タグ 4.3.1 が別のダイジェストに付け直された"], current)
        f.candidate = f"KAFKA_IMAGE=apache/kafka:4.3.1@{current}"
        return f

    def run_with(self, issues, finding, simulate=False):
        fake = FakeGh(issues)
        original = pins.gh
        pins.gh = fake
        try:
            return pins.report_issue(finding, simulate), fake.calls
        finally:
            pins.gh = original

    def test_creates_issue_when_none_is_open(self):
        result, calls = self.run_with([], self.finding())
        self.assertEqual(result, "#123(作成)")
        create = [c for c in calls if c[:2] == ("issue", "create")][0]
        self.assertIn("[pin-check] KAFKA_IMAGE: 固定したイメージを確認する", create)
        self.assertIn("type:chore,area:infra", create)

    def test_comments_only_when_the_value_is_new(self):
        open_issue = {"number": 7, "title": "[pin-check] KAFKA_IMAGE: 固定したイメージを確認する", "body": "old", "comments": []}
        result, calls = self.run_with([open_issue], self.finding())
        self.assertEqual(result, "#7(コメントを追加)")
        self.assertFalse(any(c[:2] == ("issue", "create") for c in calls))

        reported = dict(open_issue, comments=[{"body": "sha256:" + "c" * 64}])
        result, calls = self.run_with([reported], self.finding())
        self.assertEqual(result, "#7(報告済み)")
        self.assertFalse(any(c[:2] in (("issue", "create"), ("issue", "comment")) for c in calls))

    def test_simulated_issues_do_not_mix_with_real_ones(self):
        real = {"number": 7, "title": "[pin-check] KAFKA_IMAGE: 固定したイメージを確認する", "body": "", "comments": []}
        result, calls = self.run_with([real], self.finding(), simulate=True)
        self.assertEqual(result, "#123(作成)")
        self.assertTrue(any("[pin-check][simulated] KAFKA_IMAGE: 固定したイメージを確認する" in c for c in calls))


if __name__ == "__main__":
    unittest.main()
