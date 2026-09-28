# Runbook: Kotlin/JS のテスト用の npm 依存の脆弱性(Dependabot)

## 対象
Dependabot alerts が `kotlin-js-store/yarn.lock` の npm パッケージを指したとき、または Dependabot が `yarn.lock` を書き換えた PR を作ったとき。

## 前提
- `kotlin-js-store/yarn.lock` は、Kotlin の Gradle プラグインが生成する。Kotlin/JS のテスト(`jsNodeTest`)を動かす mocha とその依存が入る。成果物には入らない。
- この lock は Gradle の設定から作り直すもので、手で直すものではない。`yarn.lock` だけを書き換えると、`kotlinStoreYarnLock` が「Lock file was changed. Run the `kotlinUpgradeYarnLock` task」で失敗し、CI の build が落ちる(2026-09-28 に確認)。

## 手順
1. **Dependabot が `yarn.lock` だけを書き換えた PR は、マージせずに閉じる。** コメントに、このランブックで直すことを書く。
2. `fix/...` のブランチで、`gradle/libs.versions.toml` の `npm-*` の版を、アドバイザリの修正版以上に上げる。まだないパッケージなら、`npm-<name>` を足し、`build-logic/src/main/kotlin/eia.root.gradle.kts` の `resolution(...)` に加える。
   - 版は npm の registry で確かめる。mocha は CommonJS の `require` で読み込むため、CommonJS の入口がない版(ESM だけの版)は選ばない。
3. `./gradlew kotlinUpgradeYarnLock` で lock を作り直す。`git diff kotlin-js-store/yarn.lock` で、対象のパッケージの版だけが変わったことを確かめる。
4. `./gradlew jsNodeTest --rerun-tasks` で、全モジュールの JS のテストが通ることを確かめる。
5. テストが通るだけでは、失敗したときにしか使われない処理(差分の表示など)は動かない。上げたパッケージを mocha が使う処理を、`build/js/node_modules` の実物で直接呼んで確かめる(例: `diff` なら `mocha/lib/reporters/base` の `generateDiff`)。
6. PR をマージした後、Dependabot alerts が自動で閉じたことを確かめる。
7. 新しい版で mocha が動かない場合は、上げるのをやめる。「開発用の依存で、成果物に含まれない」ことを理由にアラートを dismiss する案を、根拠を添えて判断を仰ぐ。

## 履歴
- 2026-09-28: serialize-javascript 6.0.2 → 7.1.2(GHSA-5c6j-r48x-rmvq・GHSA-qj8w-gfj5-8c6v)、diff 7.0.0 → 8.0.4(GHSA-73rr-hh4g-fpgx)。mocha 11.7.6 の `generateDiff` と `BufferedWorkerPool.serializeOptions` が動くことを確かめた。
