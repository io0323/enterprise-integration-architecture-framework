# EIAF ローカル開発用コマンド(CLAUDE.md §7)
GRADLE := ./gradlew

.PHONY: help setup build check arch-test contract-check integration-test format env up down logs ps verify stats clean e2e

# ローカル基盤(infra/local。ADR-0016)
# PROFILE: core / cdc / iot / file / b2b / chaos。core は常に含まれる(積み上げ方式)。空白区切りで複数指定できる。
PROFILE ?= core
INFRA := infra/local
COMPOSE := docker compose -f $(INFRA)/docker-compose.yml --env-file $(INFRA)/images.env --env-file $(INFRA)/.env
COMPOSE_PROFILES_ARGS := --profile core $(foreach p,$(filter-out core,$(PROFILE)),--profile $(p))
SERVICE ?=

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

integration-test: ## Testcontainers 統合テスト
	$(GRADLE) integrationTest

format: ## ktlint で自動整形
	$(GRADLE) ktlintFormat

env: ## infra/local/.env(秘密情報)をランダム生成する。既にあれば不足分だけ追記
	@$(INFRA)/scripts/init-env.sh

# --build: 自前で組み立てるイメージ(kafka-connect。ADR-0016 §9)の Dockerfile の変更を反映する(変更がなければキャッシュを使う)
up: env ## ローカル基盤を起動し、全コンテナが healthy になるまで待つ(例: make up PROFILE=cdc)
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

stats: ## 起動中コンテナのメモリ使用量(docker stats)
	@$(INFRA)/scripts/stats.sh

e2e: ## E2E シナリオ(P05 で tests/e2e を構築)
	@if [ -d tests/e2e ]; then $(GRADLE) :tests:e2e:e2eTest; else echo "tests/e2e は未構築です(P05)"; exit 1; fi
