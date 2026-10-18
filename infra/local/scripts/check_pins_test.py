"""check-pins.py の単体テスト(ネットワークと gh を使わない)。`python3 -I infra/local/scripts/check_pins_test.py`。"""
import contextlib
import importlib.util
import io
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

    def test_dockerfile_fetch_verified(self):
        text = (
            "ARG V=1.0\nARG X_SHA256=" + "b" * 64 + "\n"
            "RUN fetch-verified.sh https://example.test/x/${V}/x-${V}.tar.gz ${X_SHA256} /tmp/x.tar.gz\n"
        )
        self.assertEqual(pins.parse_artifacts(text), [("X_SHA256", "https://example.test/x/1.0/x-1.0.tar.gz", "b" * 64)])

    def test_repository_files_are_parsed(self):
        # images.env と Kafka Connect の Dockerfile の全部の固定を読める(形が崩れたら CI で分かる)
        self.assertGreaterEqual(len(pins.parse_images(pins.IMAGES_ENV.read_text())), 19)
        names = [a[0] for path in pins.DOCKERFILES for a in pins.parse_artifacts(path.read_text())]
        self.assertEqual(sorted(names), ["APICURIO_CONVERTER_SHA256", "DEBEZIUM_POSTGRES_SHA256", "JMX_EXPORTER_SHA256"])
        packages = [p for path in pins.DEB_DOCKERFILES for p in pins.parse_packages(path.read_text())]
        self.assertEqual([p[1] for p in packages], ["gpg", "gpg-agent", "gpgconf"])
        self.assertEqual({p[3] for p in packages}, {"trixie"})

    def test_dockerfile_debian_packages(self):
        text = (
            "ARG DEBIAN_SUITE=trixie\nARG GPG_DEB_VERSION=2.4.7-21+deb13u1+b5\nARG CURL_DEB_VERSION=8.14.1-2\n"
            "RUN apt-get update \\\n    && apt-get install -y gpg=${GPG_DEB_VERSION} \\\n      gpg-agent=${GPG_DEB_VERSION} curl=${CURL_DEB_VERSION}\n"
        )
        self.assertEqual(
            pins.parse_packages(text),
            [
                ("GPG_DEB_VERSION", "gpg", "2.4.7-21+deb13u1+b5", "trixie"),
                ("GPG_DEB_VERSION", "gpg-agent", "2.4.7-21+deb13u1+b5", "trixie"),
                ("CURL_DEB_VERSION", "curl", "8.14.1-2", "trixie"),
            ],
        )

    def test_dockerfile_debian_packages_need_the_suite(self):
        with self.assertRaises(ValueError):
            pins.parse_packages("ARG GPG_DEB_VERSION=1\nRUN apt-get install gpg=${GPG_DEB_VERSION}\n")

    def test_debian_index(self):
        text = "Package: gpg\nVersion: 2.4.7-21+deb13u1+b5\nArchitecture: arm64\n\nPackage: gpg\nVersion: 2.4.7-21+deb13u2\n\nPackage: curl\nVersion: 8\n"
        self.assertEqual(pins.parse_debian_index(text), {"gpg": {"2.4.7-21+deb13u1+b5", "2.4.7-21+deb13u2"}, "curl": {"8"}})


class PackageTest(unittest.TestCase):
    def setUp(self):
        self.original = (pins.debian_versions,)

    def tearDown(self):
        (pins.debian_versions,) = self.original

    def check(self, by_arch, version="2.4.7-21+deb13u1+b5"):
        pins.debian_versions = lambda suite, arch: by_arch[arch]
        return pins.check_package("GPG_DEB_VERSION", "gpg", version, "trixie")

    def test_available_on_both_architectures(self):
        finding = self.check({"amd64": {"gpg": {"2.4.7-21+deb13u1+b5"}}, "arm64": {"gpg": {"2.4.7-21+deb13u1+b5"}}})
        self.assertTrue(finding.ok)
        self.assertEqual(finding.name, "GPG_DEB_VERSION:gpg")

    def test_removed_version_is_broken_with_candidates(self):
        finding = self.check({"amd64": {"gpg": {"2.4.7-21+deb13u2"}}, "arm64": {"gpg": {"2.4.7-21+deb13u2"}}})
        self.assertTrue(finding.broken)
        self.assertEqual(len(finding.problems), 2)
        self.assertIn("linux/amd64 のリポジトリに gpg=2.4.7-21+deb13u1+b5 がない", finding.problems[0])
        self.assertIn("2.4.7-21+deb13u2", finding.candidate)

    def test_missing_on_one_architecture_is_broken(self):
        finding = self.check({"amd64": {"gpg": {"2.4.7-21+deb13u1+b5"}}, "arm64": {"gpg": {"2.4.7-21+deb13u1"}}})
        self.assertTrue(finding.broken)
        self.assertEqual([p.split(" ")[0] for p in finding.problems], ["linux/arm64"])

    def test_unreachable_index_is_reported_but_not_broken(self):
        finding = self.check({"amd64": None, "arm64": None})
        self.assertFalse(finding.broken)
        self.assertEqual(finding.problems, ["linux/amd64 のパッケージの一覧を取得できない", "linux/arm64 のパッケージの一覧を取得できない"])


class RetryTest(unittest.TestCase):
    def setUp(self):
        self.original = (pins.RETRY_DELAYS, pins.fetch_sha256)
        pins.RETRY_DELAYS = [0, 0]

    def tearDown(self):
        pins.RETRY_DELAYS, pins.fetch_sha256 = self.original

    def fetches(self, *results):
        calls = iter(results)
        pins.fetch_sha256 = lambda url: next(calls)

    def test_transient_failures_are_retried(self):
        # 1 回目は HTTP 503、2 回目は誤った応答、3 回目で正しいファイル → 異常にしない
        self.fetches((None, "HTTP 503"), ("d" * 64, None), ("e" * 64, None))
        finding = pins.check_artifact("X_SHA256", "https://example.test/x", "e" * 64)
        self.assertTrue(finding.ok)

    def test_persistent_failure_is_reported_with_the_status(self):
        self.fetches((None, "HTTP 429"), (None, "HTTP 429"), (None, "HTTP 429"))
        finding = pins.check_artifact("X_SHA256", "https://example.test/x", "e" * 64)
        self.assertEqual(finding.problems, ["取得できない(HTTP 429。3 回試した)"])
        self.assertTrue(finding.broken)

    def test_persistent_mismatch_is_reported_with_a_candidate(self):
        self.fetches(("d" * 64, None), ("d" * 64, None), ("d" * 64, None))
        finding = pins.check_artifact("X_SHA256", "https://example.test/x", "e" * 64)
        self.assertIn("SHA-256 が固定した値と合わない", finding.problems[0])
        self.assertEqual(finding.current, "d" * 64)
        self.assertTrue(finding.candidate.startswith("ARG X_SHA256=" + "d" * 64))


class EnvironmentTest(unittest.TestCase):
    def image(self, current):
        return pins.Finding("I", "image", "r:t@" + DIGEST, [] if current else ["タグ t を取得できない"], current)

    def test_most_images_unreachable_is_an_environment_problem(self):
        self.assertTrue(pins.environment_broken([self.image(None), self.image(None), self.image(DIGEST)]))
        self.assertTrue(pins.environment_broken([self.image(None), self.image(DIGEST)]))

    def test_a_few_unreachable_images_are_reported(self):
        self.assertFalse(pins.environment_broken([self.image(None), self.image(DIGEST), self.image(DIGEST)]))


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

    def run_with(self, issues, finding, simulate=False, create=True):
        fake = FakeGh(issues)
        original = pins.gh
        pins.gh = fake
        try:
            return pins.report_issue(finding, simulate, create), fake.calls
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

    def test_without_create_nothing_is_created_or_commented(self):
        # 表示だけ: 開いている Issue を読むだけで、作らず・コメントしない
        result, calls = self.run_with([], self.finding(), create=False)
        self.assertEqual(result, "作る(表示だけ): [pin-check] KAFKA_IMAGE: 固定したイメージを確認する")
        self.assertEqual([c[:2] for c in calls], [("issue", "list")])

        open_issue = {"number": 7, "title": "[pin-check] KAFKA_IMAGE: 固定したイメージを確認する", "body": "old", "comments": []}
        result, calls = self.run_with([open_issue], self.finding(), create=False)
        self.assertEqual(result, "#7 にコメントする(表示だけ)")
        self.assertEqual([c[:2] for c in calls], [("issue", "list")])

    def test_plan_without_gh_does_not_fail(self):
        def missing(*args):
            raise FileNotFoundError("gh")

        original = pins.gh
        pins.gh = missing
        try:
            self.assertIn("表示だけ", pins.planned_issue(self.finding(), True, False))
            with self.assertRaises(FileNotFoundError):
                pins.planned_issue(self.finding(), True, True)
        finally:
            pins.gh = original

    def test_simulated_issues_do_not_mix_with_real_ones(self):
        real = {"number": 7, "title": "[pin-check] KAFKA_IMAGE: 固定したイメージを確認する", "body": "", "comments": []}
        result, calls = self.run_with([real], self.finding(), simulate=True)
        self.assertEqual(result, "#123(作成)")
        self.assertTrue(any("[pin-check][simulated] KAFKA_IMAGE: 固定したイメージを確認する" in c for c in calls))



class SimulateTest(unittest.TestCase):
    """main の simulate: 書き換えた 3 つ(1 つ目のイメージ・成果物・パッケージ)だけを報告し、既定では Issue を作らない(ネットワークは使わない)。"""

    def setUp(self):
        images = pins.parse_images(pins.IMAGES_ENV.read_text())
        artifacts = [a for path in pins.DOCKERFILES for a in pins.parse_artifacts(path.read_text())]
        digests = {f"{repo}:{tag}": digest for _, repo, tag, digest in images}
        pinned = {digest for *_, digest in images}
        checksums = {url: sha for _, url, sha in artifacts}
        packages = [p for path in pins.DEB_DOCKERFILES for p in pins.parse_packages(path.read_text())]
        debian = {}
        for _, package, version, _ in packages:
            debian.setdefault(package, set()).add(version)
        manifest = lambda digest: {  # noqa: E731
            "digest": digest,
            "manifests": [{"platform": {"os": "linux", "architecture": a}} for a in ("amd64", "arm64")],
        }

        def imagetools(ref):
            if "@" in ref:
                return manifest(ref.split("@")[1]) if ref.split("@")[1] in pinned else None
            return manifest(digests[ref])

        self.original = (pins.imagetools, pins.fetch_sha256, pins.gh, pins.RETRY_DELAYS, pins.debian_versions)
        pins.imagetools = imagetools
        pins.debian_versions = lambda suite, arch: debian
        pins.fetch_sha256 = lambda url: (checksums[url], None)
        self.gh = FakeGh([])
        pins.gh = self.gh
        pins.RETRY_DELAYS = []
        self.first_image = images[0][0]
        self.first_artifact = artifacts[0][0]
        self.first_package = packages[0][0]
        self.checked = len(images) + len(artifacts) + len(packages)

    def tearDown(self):
        pins.imagetools, pins.fetch_sha256, pins.gh, pins.RETRY_DELAYS, pins.debian_versions = self.original

    def run_main(self, *argv):
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = pins.main(list(argv))
        return code, err.getvalue()

    def test_simulate_reports_only_the_rewritten_pins_without_creating_issues(self):
        code, err = self.run_main("--simulate")
        self.assertEqual(code, 0)
        reported = [line.split(":")[0] for line in err.splitlines() if line and not line.startswith(" ") and ":" in line]
        self.assertEqual(reported, [self.first_image, self.first_artifact, self.first_package])
        self.assertIn(f"確認 {self.checked} 件、異常 3 件", err)
        self.assertEqual(err.count("作る(表示だけ): [pin-check][simulated]"), 3)
        self.assertEqual({c[:2] for c in self.gh.calls}, {("issue", "list")})

    def test_simulate_creates_issues_only_when_asked(self):
        code, err = self.run_main("--simulate", "--create-issues")
        self.assertEqual(code, 0)
        self.assertEqual(sum(1 for c in self.gh.calls if c[:2] == ("issue", "create")), 3)

    def test_without_simulate_everything_is_ok(self):
        code, err = self.run_main("--create-issues")
        self.assertEqual(code, 0)
        self.assertIn(f"確認 {self.checked} 件、異常 0 件", err)
        self.assertEqual(self.gh.calls, [])


if __name__ == "__main__":
    unittest.main()
