# 追加の Flow の定義(既定は空)

integration-flow が、イメージに含めた contracts/flows に足して読むディレクトリ(`INTEGRATION_FLOW_EXTRA_FLOWS_DIR=/flows/extra`)の既定のマウント元。
ここには Flow を置かない(本番の Flow は contracts/flows に置き、契約と同じくレビューする。ADR-0036 §1)。
E2E(`make up ... E2E=1`)では、tests/e2e/e2e.env の `INTEGRATION_FLOW_EXTRA_FLOWS` で tests/e2e/flows をマウントする。
