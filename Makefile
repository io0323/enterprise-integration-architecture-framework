# EIAF ローカル開発用コマンド(CLAUDE.md §7)
GRADLE := ./gradlew

.PHONY: help setup build check arch-test contract-check schemas integration-test format env not-root certs order-dist up down logs ps verify stats clean e2e audit-verify

# ローカル基盤(infra/local。ADR-0016)
# PROFILE: core / cdc / iot / file / b2b / chaos / order。core は常に含まれる(積み上げ方式)。空白区切りで複数指定できる。
PROFILE ?= core
INFRA := infra/local
COMPOSE := docker compose -f $(INFRA)/docker-compose.yml --env-file $(INFRA)/images.env --env-file $(INFRA)/.env
COMPOSE_PROFILES_ARGS := --profile core $(foreach p,$(filter-out core,$(PROFILE)),--profile $(p))
SERVICE ?=
# order-service のコンテナは、開発用の鍵(infra/local/certs/*.key。0600)を読むため、ホストの利用者の uid で動かす。
# root(uid 0)では make up と make certs を止める(コンテナが root で動き、distroless の nonroot の意味がなくなるため。ADR-0024 §7)
EIAF_UID := $(shell id -u)
EIAF_GID := $(shell id -g)
export EIAF_UID EIAF_GID
# 開発用の証明書(infra/local/certs)を読むコンテナ。証明書を作り直したら(certs/.renewed)、make up で作り直す
CERT_CONSUMERS := order-service apisix

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

order-dist: ## order-service と schema-publish(契約のスキーマの登録)のイメージの中身(installDist)を作る
	$(GRADLE) :services:order:app:installDist :tools:schema-publish:installDist

# --build: 自前で組み立てるイメージ(kafka-connect・order-service・schema-publish。ADR-0016 §9)の変更を反映する(変更がなければキャッシュを使う)
# 証明書の有効期限は毎回確かめる(期限切れの証明書で起動に失敗しないように)
up: not-root env certs $(if $(filter order,$(PROFILE)),order-dist) ## ローカル基盤を起動し、全コンテナが healthy になるまで待つ(例: make up PROFILE=cdc)
	@if [ -f $(INFRA)/certs/.renewed ]; then \
		$(COMPOSE) --profile '*' rm --stop --force $(CERT_CONSUMERS) >/dev/null 2>&1 || true; \
		rm -f $(INFRA)/certs/.renewed; \
	fi
	$(COMPOSE) $(COMPOSE_PROFILES_ARGS) up -d --build --wait --wait-timeout 420

down: env ## ローカル基盤を停止する(全 profile。データは残す)
	$(COMPOSE) --profile '*' down --remove-orphans

clean: env ## ローカル基盤を停止し、ボリュームも削除する
	$(COMPOSE) --profile '*' down --remove-orphans --volumes

logs: env ## ログを表示する(例: make logs SERVICE=kafka)
	$(COMPOSE) $(COMPOSE_PROFILES_ARGS) logs -f --tail=200 $(SERVICE)

ps: env ## コンテナの状態を表示する
	$(COMPOSE) --profile '*' ps

verify: ## profile ごとの検証(healthy・機能の疎通。例: make verify PROFILE=file)
	@$(INFRA)/scripts/verify.sh $(PROFILE)

audit-verify: ## 監査記録の改竄の検査(例: make audit-verify SERVICE=order。終了コード 0=正常 / 1=改竄の疑い / 2=実行できない。ADR-0017)
	@$(INFRA)/scripts/audit-verify.sh $(SERVICE)

stats: ## 起動中コンテナのメモリ使用量(docker stats)
	@$(INFRA)/scripts/stats.sh

# 秘密情報は infra/local/.env から環境変数で渡す(verify.sh と同じ)。基盤は make up PROFILE=order で起動しておく
# シナリオの後に、監査記録の全体の検査が OK で、アンカーがあることを確かめる(AuditAnchorE2E がアンカーの保存を待つ。ADR-0017)
e2e: env ## E2E シナリオ(tests/e2e。make up PROFILE=order で起動した基盤に、公開されたエンドポイントだけで接続する)
	@set -a && . $(INFRA)/.env && set +a && $(GRADLE) :tests:e2e:e2eTest
	@$(INFRA)/scripts/audit-anchored.sh order
