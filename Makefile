# EIAF ローカル開発用コマンド(CLAUDE.md §7)
GRADLE := ./gradlew

.PHONY: help setup build check arch-test contract-check schemas alerts-test integration-test format env not-root certs pgp-keys order-dist legacy-dist saga-dist batch-dist file-dist saas-dist iot-dist up down logs ps verify stats clean e2e audit-verify legacy-simulate dlq-replay batch-etl file-exchange iot-reprocess-dlq

# ローカル基盤(infra/local。ADR-0016)
# PROFILE: core / cdc / iot / file / b2b / chaos / order / saga / batch / saas。core は常に含まれる(積み上げ方式)。空白区切りで複数指定できる。
PROFILE ?= core
INFRA := infra/local
# E2E=1: E2E 用の設定(tests/e2e/e2e.env。待ち時間を短くする)を足して起動する(例: make up PROFILE="order cdc saga batch file saas iot" E2E=1)
E2E ?=
COMPOSE := docker compose -f $(INFRA)/docker-compose.yml --env-file $(INFRA)/images.env --env-file $(INFRA)/.env $(if $(E2E),--env-file tests/e2e/e2e.env)
COMPOSE_PROFILES_ARGS := --profile core $(foreach p,$(filter-out core,$(PROFILE)),--profile $(p))
SERVICE ?=
# order-service のコンテナは、開発用の鍵(infra/local/certs/*.key。0600)を読むため、ホストの利用者の uid で動かす。
# root(uid 0)では make up と make certs を止める(コンテナが root で動き、distroless の nonroot の意味がなくなるため。ADR-0024 §7)
EIAF_UID := $(shell id -u)
EIAF_GID := $(shell id -g)
export EIAF_UID EIAF_GID
# 開発用の証明書(infra/local/certs)を読むコンテナ。証明書を作り直したら(certs/.renewed)、make up で作り直す
CERT_CONSUMERS := order-service apisix webhook-receiver

help: ## コマンド一覧
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-18s %s\n", $$1, $$2}'

setup: ## clone 後に最初に 1 回実行する(Git フックの登録と push 設定。リポジトリ単位)
	git config core.hooksPath scripts/git-hooks
	git config push.default simple
	@echo "Git フック(scripts/git-hooks)を登録し、push.default=simple を設定しました"

build: ## 全ビルド + 品質チェック + 単体テスト + アーキテクチャテスト
	$(GRADLE) build

check: ## ビルド成果物を作らずに検査のみ実行
	$(GRADLE) check

arch-test: ## Konsist アーキテクチャテストのみ実行
	$(GRADLE) :tools:architecture-test:test

contract-check: ## 契約の検査(命名・カタログ・構造・main との互換性。ADR-0013)
	git fetch --quiet origin main
	$(GRADLE) :tools:contract-check:run

schemas: ## 契約の Avro スキーマを Schema Registry(Apicurio)に登録する。make up の後に実行する(ADR-0025 §2)
	$(GRADLE) -q :tools:schema-publish:run --args="--registry http://localhost:19081/apis/registry/v3 --root $(CURDIR)"

alerts-test: ## Prometheus のアラートのルールの検査と単体テスト(infra/local/prometheus/rules。promtool。P06 ④)
	@$(INFRA)/scripts/alerts-test.sh

integration-test: ## Testcontainers 統合テスト
	$(GRADLE) integrationTest

format: ## ktlint で自動整形
	$(GRADLE) ktlintFormat

env: ## infra/local/.env(秘密情報)をランダム生成する。既にあれば不足分だけ追記
	@$(INFRA)/scripts/init-env.sh

not-root:
	@if [ "$(EIAF_UID)" = 0 ]; then \
		echo "make up / make certs を root(uid 0)で実行しないでください。" >&2; \
		echo "ローカルのコンテナはホストの利用者の uid で動き、0600 の開発用の鍵を読みます。root で実行すると" >&2; \
		echo "コンテナが root で動き、distroless の nonroot の意味がなくなります。root 以外の利用者で実行してください(ADR-0024 §7)。" >&2; \
		exit 1; \
	fi

certs: not-root ## 開発用の CA と mTLS の証明書を作る。残りが 7 日を切っていれば作り直す(docs/runbooks/dev-certificates.md)
	@$(INFRA)/scripts/gen-dev-certs.sh

pgp-keys: not-root ## 開発用の PGP の鍵(自社 eiaf と取引先 partner01)を GnuPG で作る。既にあれば何もしない(docs/runbooks/file-exchange.md)
	@$(INFRA)/scripts/gen-dev-pgp-keys.sh

order-dist: ## order-service と schema-publish(契約のスキーマの登録)のイメージの中身(installDist)を作る
	$(GRADLE) :services:order:app:installDist :tools:schema-publish:installDist

legacy-dist: ## legacy-sim(レガシー基幹の模擬)・legacy-order-acl・schema-publish のイメージの中身(installDist)を作る
	$(GRADLE) :services:legacy-sim:app:installDist :services:legacy-order-acl:app:installDist :tools:schema-publish:installDist

# --build: 自前で組み立てるイメージ(kafka-connect・order-service・schema-publish。ADR-0016 §9)の変更を反映する(変更がなければキャッシュを使う)
# 証明書の有効期限は毎回確かめる(期限切れの証明書で起動に失敗しないように)
saga-dist: ## 注文 Saga の参加者(inventory・payment・shipping)と schema-publish のイメージの中身(installDist)を作る(ADR-0029)
	$(GRADLE) :services:inventory:app:installDist :services:payment:app:installDist :services:shipping:app:installDist :tools:schema-publish:installDist

batch-dist: ## 分析のバッチ(batch-etl)と schema-publish のイメージの中身(installDist)を作る(ADR-0031)
	$(GRADLE) :services:batch-etl:app:installDist :tools:schema-publish:installDist

file-dist: ## ファイル授受(file-exchange)のイメージの中身(installDist)を作る(ADR-0032)
	$(GRADLE) :services:file-exchange:app:installDist

saas-dist: ## SaaS 連携(saas-mock・webhook-receiver・integration-flow)と schema-publish のイメージの中身(installDist)を作る(ADR-0035・ADR-0036)
	$(GRADLE) :services:saas-mock:app:installDist :services:webhook-receiver:app:installDist :services:integration-flow:app:installDist :tools:schema-publish:installDist

iot-dist: ## IoT の橋渡し(iot-bridge)と schema-publish のイメージの中身(installDist)を作る(ADR-0039)
	$(GRADLE) :services:iot-bridge:app:installDist :tools:schema-publish:installDist

up: not-root env certs $(if $(filter order,$(PROFILE)),order-dist) $(if $(filter cdc,$(PROFILE)),legacy-dist) $(if $(filter saga,$(PROFILE)),saga-dist) $(if $(filter batch,$(PROFILE)),batch-dist) $(if $(filter file,$(PROFILE)),file-dist pgp-keys) $(if $(filter saas,$(PROFILE)),saas-dist) $(if $(filter iot,$(PROFILE)),iot-dist) ## ローカル基盤を起動し、全コンテナが healthy になるまで待つ(例: make up PROFILE=cdc)
	@if [ -f $(INFRA)/certs/.renewed ]; then \
		$(COMPOSE) --profile '*' rm --stop --force $(CERT_CONSUMERS) >/dev/null 2>&1 || true; \
		rm -f $(INFRA)/certs/.renewed; \
	fi
	$(COMPOSE) $(COMPOSE_PROFILES_ARGS) up -d --build --wait --wait-timeout 420
	@# 注文のイベントの発行(Outbox → Debezium。ADR-0007)。order-migrate が Outbox の表と publication を作った後に登録する
	@if [ -n "$(filter order,$(PROFILE))" ]; then $(INFRA)/scripts/connectors.sh order-outbox; fi
	@# レガシーの受注表の CDC(ADR-0026)。kafka-connect は legacy-migrate(publication・権限・signal 表)の成功を待って起動している
	@if [ -n "$(filter cdc,$(PROFILE))" ]; then $(INFRA)/scripts/connectors.sh legacy-juchu; fi
	@# 注文 Saga の参加者の返事の発行(Outbox → Debezium。ADR-0029)。各参加者の migrate が Outbox の表・publication・CONNECT を作った後に登録する
	@if [ -n "$(filter saga,$(PROFILE))" ]; then $(INFRA)/scripts/connectors.sh inventory-outbox payment-outbox shipping-outbox; fi
	@# SaaS 連携の取り込みのトピックと Canonical の顧客のイベントの発行(Outbox → Debezium。ADR-0035・ADR-0036)
	@if [ -n "$(filter saas,$(PROFILE))" ]; then $(INFRA)/scripts/connectors.sh webhook-receiver-outbox integration-flow-outbox; fi

down: env ## ローカル基盤を停止する(全 profile。データは残す)
	$(COMPOSE) --profile '*' down --remove-orphans

clean: env ## ローカル基盤を停止し、ボリュームも削除する
	$(COMPOSE) --profile '*' down --remove-orphans --volumes

logs: env ## ログを表示する(例: make logs SERVICE=kafka)
	$(COMPOSE) $(COMPOSE_PROFILES_ARGS) logs -f --tail=200 $(SERVICE)

ps: env ## コンテナの状態を表示する
	$(COMPOSE) --profile '*' ps

# レガシーのアプリとして受注を書き換える(make up PROFILE=cdc の後。変えた受注番号を 1 行ずつ出す)。
# 例: make legacy-simulate ARGS="seed 10" / ARGS="advance 3" / ARGS="anomaly garbled-name"(種類は ARGS= で使い方を表示)
ARGS ?=
legacy-simulate: env legacy-dist ## レガシーの受注表を書き換える(例: make legacy-simulate ARGS="seed 10"。ADR-0026)
	@$(COMPOSE) --profile legacy-sim-cli run --rm --build --no-deps legacy-sim simulate $(ARGS)

batch-etl: env batch-dist ## 分析のバッチのコマンドを 1 回だけ実行する(例: make batch-etl ARGS="run 2026-10-13 --restart"。make up PROFILE="order batch" の後。docs/runbooks/batch-rerun.md)
	@$(COMPOSE) --profile batch run --rm --build --no-deps batch-etl $(ARGS)

file-exchange: env file-dist ## ファイル授受のコマンドを 1 回だけ実行する(例: make file-exchange ARGS="receive sales_daily_20261013150000_001"。make up PROFILE=file の後。docs/runbooks/file-exchange.md)
	@$(COMPOSE) --profile file run --rm --build --no-deps file-exchange $(ARGS)

iot-reprocess-dlq: env iot-dist ## IoT のテレメトリの DLQ をデコードからやり直す。既定は dry-run(例: make iot-reprocess-dlq ARGS="--limit 10 [--execute]"。make up PROFILE=iot の後。docs/runbooks/iot-bridge.md)
	@$(COMPOSE) --profile iot run --rm --build --no-deps iot-bridge reprocess-dlq $(ARGS)

verify: ## profile ごとの検証(healthy・機能の疎通。例: make verify PROFILE=file)
	@$(INFRA)/scripts/verify.sh $(PROFILE)

audit-verify: ## 監査記録の改竄の検査(例: make audit-verify SERVICE=order。終了コード 0=正常 / 1=改竄の疑い / 2=実行できない。ADR-0017)
	@$(INFRA)/scripts/audit-verify.sh $(SERVICE)

dlq-replay: ## DLQ のメッセージを元のトピックに戻す。既定は dry-run(例: make dlq-replay ARGS="--topic <topic>.dlq --limit 10 [--execute]"。docs/runbooks/event-dlq-replay.md)
	@$(INFRA)/scripts/dlq-replay.sh $(ARGS)

stats: ## 起動中コンテナのメモリ使用量(docker stats)
	@$(INFRA)/scripts/stats.sh

# 秘密情報は infra/local/.env から環境変数で渡す(verify.sh と同じ)。基盤は make up PROFILE="order cdc saga batch file saas iot" E2E=1 で起動しておく
# シナリオの後に、監査記録の全体の検査が OK で、アンカーがあることを確かめる(AuditAnchorE2E がアンカーの保存を待つ。ADR-0017)
e2e: env ## E2E シナリオ(tests/e2e。make up PROFILE="order cdc saga batch file saas iot" E2E=1 で起動した基盤に、公開されたエンドポイント・運用者のコマンド・コンテナの停止だけで接続する。tests/e2e/README.md)
	@set -a && . $(INFRA)/.env && set +a && $(GRADLE) :tests:e2e:e2eTest
	@$(INFRA)/scripts/audit-anchored.sh order
