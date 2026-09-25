# EIAF ローカル開発用コマンド(CLAUDE.md §7)
GRADLE := ./gradlew

.PHONY: help setup build check arch-test integration-test format up down e2e

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

integration-test: ## Testcontainers 統合テスト
	$(GRADLE) integrationTest

format: ## ktlint で自動整形
	$(GRADLE) ktlintFormat

up: ## ローカルミドルウェア起動(P03 で infra/local を構築)
	@if [ -f infra/local/docker-compose.yml ]; then docker compose -f infra/local/docker-compose.yml up -d; else echo "infra/local は未構築です(P03)"; exit 1; fi

down: ## ローカルミドルウェア停止
	@if [ -f infra/local/docker-compose.yml ]; then docker compose -f infra/local/docker-compose.yml down; else echo "infra/local は未構築です(P03)"; exit 1; fi

e2e: ## E2E シナリオ(P05 で tests/e2e を構築)
	@if [ -d tests/e2e ]; then $(GRADLE) :tests:e2e:e2eTest; else echo "tests/e2e は未構築です(P05)"; exit 1; fi
