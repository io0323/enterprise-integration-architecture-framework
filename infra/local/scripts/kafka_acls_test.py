"""kafka-acls.py の単体テスト(ブローカーを使わない)。`python3 -I infra/local/scripts/kafka_acls_test.py`。"""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("kafka_acls", Path(__file__).with_name("kafka-acls.py"))
acls = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(acls)

EVENT = """id: INT-X-001
style: event
pattern: pub-sub
provider: { owner: team-a, system: svc-a }
consumers:
  - { owner: team-b, system: svc-b, group: svc-b.ingest }
channels:
  - a.thing.happened.v1
tier: 1
"""

COMMAND = """id: INT-X-002
style: event
pattern: queue
provider: { owner: team-b, system: svc-b }
consumers:
  - { owner: team-b, system: svc-b, group: b.command }
senders:
  - { owner: team-a, system: svc-a }
channels:
  - b.thing.cmd-do.v1
"""


class BindingsTest(unittest.TestCase):
    def test_event_provider_writes_and_consumer_reads_with_group_and_dlq(self):
        self.assertEqual(
            acls.bindings(acls.parse(EVENT, "x"), "x"),
            {
                ("svc-a", "Write", "topic", "a.thing.happened.v1"),
                ("svc-b", "Read", "topic", "a.thing.happened.v1"),
                ("svc-b", "Read", "group", "svc-b.ingest"),
                ("svc-b", "Write", "topic", "a.thing.happened.v1.dlq"),
            },
        )

    def test_command_senders_write_and_the_provider_does_not(self):
        result = acls.bindings(acls.parse(COMMAND, "x"), "x")
        self.assertIn(("svc-a", "Write", "topic", "b.thing.cmd-do.v1"), result)
        self.assertNotIn(("svc-b", "Write", "topic", "b.thing.cmd-do.v1"), result)
        self.assertIn(("svc-b", "Read", "group", "b.command"), result)

    def test_non_kafka_styles_have_no_acl(self):
        self.assertEqual(acls.bindings(acls.parse(EVENT.replace("style: event", "style: mqtt"), "x"), "x"), set())

    def test_consumer_without_group_is_rejected(self):
        with self.assertRaises(acls.CatalogError):
            acls.bindings(acls.parse(EVENT.replace(", group: svc-b.ingest", ""), "x"), "x")

    def test_unknown_layout_is_rejected(self):
        with self.assertRaises(acls.CatalogError):
            acls.parse(EVENT.replace("consumers:\n  - {", "consumers: [{").replace("ingest }", "ingest }]"), "x")

    def test_real_catalog(self):
        result = acls.catalog_bindings(Path(__file__).resolve().parents[3] / "contracts" / "catalog")
        self.assertIn(("order-service", "Write", "topic", "sales.order.created.v1"), result)
        self.assertIn(("batch-etl", "Read", "group", "batch-etl.order-ingest"), result)
        self.assertNotIn(("order-service", "Read", "topic", "sales.order.created.v1"), result)

    def test_empty_catalog_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(acls.catalog_bindings(Path(directory)), [])


LISTING = """Current ACLs for resource `ResourcePattern(resourceType=TOPIC, name=a.thing.happened.v1, patternType=LITERAL)`:
\t(principal=User:svc-a, host=*, operation=WRITE, permissionType=ALLOW)
\t(principal=User:old, host=*, operation=READ, permissionType=ALLOW)

Current ACLs for resource `ResourcePattern(resourceType=GROUP, name=svc-b.ingest, patternType=LITERAL)`:
\t(principal=User:svc-b, host=*, operation=READ, permissionType=ALLOW)
"""


class PlanTest(unittest.TestCase):
    def test_listing(self):
        self.assertEqual(
            acls.parse_listing(LISTING),
            {
                ("svc-a", "Write", "topic", "a.thing.happened.v1"),
                ("old", "Read", "topic", "a.thing.happened.v1"),
                ("svc-b", "Read", "group", "svc-b.ingest"),
            },
        )

    def test_plan_adds_missing_and_removes_extra(self):
        desired = sorted(acls.bindings(acls.parse(EVENT, "x"), "x"))
        self.assertEqual(
            acls.plan(desired, acls.parse_listing(LISTING)),
            [
                ("add", "svc-b", "Read", "topic", "a.thing.happened.v1"),
                ("add", "svc-b", "Write", "topic", "a.thing.happened.v1.dlq"),
                ("remove", "old", "Read", "topic", "a.thing.happened.v1"),
            ],
        )

    def test_unexpected_acl_forms_stop(self):
        for text in (
            LISTING.replace("LITERAL", "PREFIXED"),
            LISTING.replace("permissionType=ALLOW", "permissionType=DENY", 1),
            LISTING.replace("resourceType=GROUP", "resourceType=CLUSTER"),
        ):
            with self.assertRaises(acls.CatalogError):
                acls.parse_listing(text)


if __name__ == "__main__":
    unittest.main(argv=sys.argv[:1], verbosity=1)
