package io.eia.tools.contract

/**
 * contract-check の検査ルール。ID は `CC-{区分}-{連番}`。一覧は tools/contract-check/README.md にも載せる
 * (README と一致することはテストで検査する)。
 *
 * [chapter] は根拠(Framework の章・ADR)、[fix] は違反したときの修正方法。
 */
enum class Rule(
    val id: String,
    val title: String,
    val chapter: String,
    val fix: String,
) {
    // --- 構造 ---
    STRUCT_PARSE(
        "CC-STRUCT-001",
        "YAML / JSON として読めること",
        "15.2",
        "構文エラーの位置を直す",
    ),
    STRUCT_OPENAPI(
        "CC-STRUCT-002",
        "OpenAPI として正しいこと(swagger-parser の検証)",
        "5, 15.2",
        "OpenAPI 3.1 の仕様に合わせて記述を直す",
    ),
    STRUCT_ASYNCAPI(
        "CC-STRUCT-003",
        "AsyncAPI 3.0 の公式 JSON Schema に適合すること",
        "6.3, 15.2",
        "AsyncAPI 3.0 の仕様に合わせて記述を直す",
    ),
    STRUCT_AVRO(
        "CC-STRUCT-004",
        "Avro スキーマとして読めること",
        "6.3",
        "Avro 1.12 の仕様に合わせて直す(default の型、名前の参照順など)",
    ),
    STRUCT_JSON_SCHEMA(
        "CC-STRUCT-005",
        "JSON Schema(manifest・カタログ・ファイルの仕様)がメタスキーマに適合すること",
        "9, 16.1",
        "JSON Schema 2020-12 の仕様に合わせて直す",
    ),

    // --- 命名 ---
    NAMING_TOPIC(
        "CC-NAMING-001",
        "Topic は {domain}.{entity}.{event}.v{n}",
        "6.2, 16.1",
        "小文字・kebab-case の 4 セグメントに直す(例 sales.order.created.v1)。DLQ は {topic}.dlq",
    ),
    NAMING_COMMAND_TOPIC(
        "CC-NAMING-002",
        "Command Topic の event セグメントは cmd-{command}",
        "6.2, ADR-0006",
        "event セグメントを cmd-{command}(kebab-case)にする(例 inventory.stock.cmd-reserve.v1)",
    ),
    NAMING_API_SERVER(
        "CC-NAMING-003",
        "OpenAPI の servers[*].url は /{domain} で終わる",
        "5.2, ADR-0005",
        "servers には Gateway 公開 URL を /{domain} まで書く(例 http://localhost:19080/sales)",
    ),
    NAMING_API_PATH(
        "CC-NAMING-004",
        "OpenAPI の paths は /v{n}/{resource}(kebab-case)",
        "5.3, ADR-0005",
        "paths を /v{n}/ で始め、リソース名を小文字の kebab-case にする(例 /v1/orders/{orderId})",
    ),
    NAMING_FILE(
        "CC-NAMING-005",
        "contracts/files のファイル名",
        "9, 16.1",
        "{system}_{dataset}.v{n}.yaml、manifest.v{n}.schema.json、file-spec.v{n}.schema.json、edi/{name}.v{n}.yaml のいずれかにする",
    ),
    NAMING_INTEGRATION_ID(
        "CC-NAMING-006",
        "連携 ID は INT-{DOMAIN}-{NNN} で、カタログのファイル名は {id}.yaml",
        "16.1",
        "id を INT-{DOMAIN}-{NNN} にし、ファイル名を id と一致させる",
    ),
    NAMING_CONSUMER_GROUP(
        "CC-NAMING-007",
        "Consumer Group は {service}.{purpose}",
        "6.4",
        "小文字・kebab-case の 2 セグメントにする(例 inventory.reservation)",
    ),
    NAMING_AVRO(
        "CC-NAMING-008",
        "Avro の namespace は io.eia.events.{domain}、record は PascalCase、ファイル名は {Record}.avsc",
        "6.3, INTEGRATION_STANDARDS §1",
        "contracts/avro/{domain}/{Record}.avsc に置き、namespace と名前を合わせる(共通型は io.eia.events.common)",
    ),
    NAMING_CONTRACT_FILE(
        "CC-NAMING-009",
        "OpenAPI / AsyncAPI のファイル名は {name}.v{n}.yaml で、info.version のメジャーと n が一致する",
        "5.3, 16.1",
        "ファイル名を {name}.v{n}.yaml にし、破壊的変更は新しいファイル(v{n+1})で行う",
    ),
    NAMING_DOMAIN(
        "CC-NAMING-010",
        "Topic・API の domain が連携 ID の domain と一致する",
        "16.1, ADR-0005",
        "連携 ID の {DOMAIN} を小文字にした値を Topic の先頭セグメント・servers の末尾に使う",
    ),

    // --- API ---
    API_IDEMPOTENCY_KEY(
        "CC-API-001",
        "POST は Idempotency-Key ヘッダが必須",
        "5.4",
        "POST の parameters に Idempotency-Key(in: header、required: true)を追加する",
    ),
    API_RATE_LIMIT(
        "CC-API-002",
        "全 operation に 429 + Retry-After の応答がある",
        "5.6",
        "responses に 429 を追加し、headers に Retry-After を定義する",
    ),
    API_SECURITY(
        "CC-API-003",
        "全 operation に security が定義されている(匿名アクセスを許さない)",
        "12",
        "operation かトップレベルに security(OAuth2 のスコープ)を定義する。空の要件 {} は使わない",
    ),

    // --- Event ---
    EVENT_HEADERS(
        "CC-EVENT-001",
        "メッセージのヘッダに CloudEvents(ce_*)・traceparent・correlationid が必須で定義されている",
        "6.3, 14",
        "headers に ce_id, ce_source, ce_type, ce_time, ce_specversion, traceparent, correlationid を required で定義する",
    ),
    EVENT_AVRO_PAYLOAD(
        "CC-EVENT-002",
        "メッセージの payload は contracts/avro の Avro スキーマを参照する",
        "6.3",
        "payload を schemaFormat: application/vnd.apache.avro+json と、存在する .avsc への \$ref にする",
    ),

    // --- Catalog ---
    CATALOG_SCHEMA(
        "CC-CATALOG-001",
        "カタログがカタログスキーマに適合する(Owner・Tier・SLO・機密区分など)",
        "16.1",
        "contracts/catalog/catalog.schema.json の必須項目と値を満たす",
    ),
    CATALOG_CONTRACT_EXISTS(
        "CC-CATALOG-002",
        "カタログの contract が存在する",
        "16.1",
        "contract にリポジトリルートからの正しいパスを書く",
    ),
    CATALOG_DUPLICATE_ID(
        "CC-CATALOG-003",
        "連携 ID が重複しない",
        "16.1",
        "未使用の連番を割り当てる",
    ),
    CATALOG_CHANNELS(
        "CC-CATALOG-004",
        "カタログの channels と AsyncAPI の channel が一致する",
        "16.1",
        "AsyncAPI の channels[*].address をすべて catalog の channels に列挙する(過不足なし)",
    ),
    CATALOG_CONSUMER_GROUP(
        "CC-CATALOG-005",
        "event / cdc の consumer は group(Consumer Group)を持つ",
        "6.4",
        "consumers[*].group に {service}.{purpose} を書く",
    ),
    CATALOG_UNREGISTERED(
        "CC-CATALOG-006",
        "OpenAPI / AsyncAPI の契約はカタログに登録されている",
        "16.1",
        "contracts/catalog/{id}.yaml を追加し、contract にこのファイルを指定する",
    ),

    // --- Command ---
    COMMAND_SINGLE_CONSUMER(
        "CC-COMMAND-001",
        "Command Topic の購読者は受信サービスの 1 Consumer Group({service}.command)だけ",
        "6.2, ADR-0006",
        "購読を 1 つにする。複数の系が知るべき事実なら Domain Event として別トピックで発行する",
    ),
    COMMAND_PATTERN(
        "CC-COMMAND-002",
        "Command Topic は pattern: queue、pattern: queue は Command Topic のみ",
        "6.7, ADR-0006",
        "cmd- トピックを含む連携は pattern: queue にし、イベントトピックとは別の連携として登録する",
    ),

    // --- File ---
    FILE_MANIFEST(
        "CC-FILE-001",
        "manifest スキーマが標準の必須項目を required に含む",
        "9, INTEGRATION_STANDARDS §2",
        "required に file, recordCount, sha256, schemaVersion, createdAt, traceparent, correlationId を含める",
    ),
    FILE_SPEC(
        "CC-FILE-002",
        "ファイルの仕様がファイルの仕様のスキーマに適合する(形式・列・機密区分・個人情報の有無など)",
        "9, 12.2",
        "contracts/files/file-spec.v{n}.schema.json の必須項目と値を満たす",
    ),
    FILE_SPEC_NAME(
        "CC-FILE-003",
        "ファイルの仕様のファイル名と、中の system・dataset・version が一致する",
        "9, INTEGRATION_STANDARDS §1",
        "ファイル名を {system}_{dataset}.v{version}.yaml にする(manifest の schemaVersion は {system}_{dataset}.v{version})",
    ),
    FILE_SPEC_UNREGISTERED(
        "CC-FILE-004",
        "ファイルの仕様はカタログに登録されている",
        "16.1",
        "contracts/catalog/{id}.yaml(style: file)を追加し、contract にこの仕様を指定する",
    ),

    // --- 互換性(main との差分) ---
    COMPAT_AVRO(
        "CC-COMPAT-001",
        "Avro スキーマが main の版に対して FULL 互換(BACKWARD かつ FORWARD)",
        "6.3, 13, 15.3, ADR-0014",
        "追加・削除できるのは default 付きの項目だけ。必須項目の追加・削除、型変更、リネーム、enum 値の増減は新しいバージョンのトピック(.v{n+1})で行う",
    ),
    COMPAT_OPENAPI(
        "CC-COMPAT-002",
        "OpenAPI に main の版からの破壊的変更がない(oasdiff)",
        "5.3",
        "互換な変更(任意項目の追加など)にするか、/v{n+1} を新設して並行提供する",
    ),
    COMPAT_FILE(
        "CC-COMPAT-003",
        "ファイルの仕様が main の版に対して互換(列の削除・型や必須性の変更・並べ替え・enum の変更・形式の変更がない)",
        "9, 15.3",
        "足してよいのは、末尾の任意(nullable: true)の列と、新しい形式だけ。ほかの変更は新しい版のファイル({system}_{dataset}.v{n+1}.yaml)で行う",
    ),

    // --- 互換性検査の例外(contracts/compat-waivers.yaml) ---
    WAIVER_INVALID(
        "CC-WAIVER-001",
        "互換性検査の例外リストの形式が正しい",
        "16.1, ADR-0013",
        "各例外に rule(CC-COMPAT-*)・file・contains・reason・issue・expires(YYYY-MM-DD)を書く",
    ),
    WAIVER_EXPIRED(
        "CC-WAIVER-002",
        "期限切れの互換性検査の例外が残っていない",
        "16.1, ADR-0013",
        "例外が不要になっていれば削除する。まだ必要なら理由を Issue に残して expires を延ばす",
    ),
    WAIVER_ACTIVE(
        "CC-WAIVER-003",
        "active・deprecated の連携の契約への例外は、承認者と作成日を書き、期限は作成日から 14 日以内",
        "16.1, ADR-0013",
        "approver(@name か team-name)と created(YYYY-MM-DD)を書き、expires を created から 14 日以内にする。" +
            "長く要るなら、例外ではなく新しい版(v{n+1})で並行提供する",
    ),

    // --- ライフサイクル(比較元に対する検査。ADR-0034) ---
    LIFECYCLE_TRANSITION(
        "CC-LIFECYCLE-001",
        "連携の状態は 1 つの PR で 1 段階だけ前に進める(proposed → design → active → deprecated → retired)",
        "16.1, ADR-0034",
        "状態を 1 段階ずつ進める。新しい連携は proposed か design で登録する(既存の連携の後からの登録は onboarding)。" +
            "deprecated から active に戻すときは reactivation に reason と approver を書く",
    ),
    LIFECYCLE_ENTRY_REMOVED(
        "CC-LIFECYCLE-002",
        "カタログのエントリを消せるのは、比較元で retired のものだけ",
        "16.1, ADR-0034",
        "先に deprecated → retired と進め、retired がマージされた後の PR で消す",
    ),
    LIFECYCLE_CONTRACT_REMOVED(
        "CC-LIFECYCLE-003",
        "retired 以外の連携が使う契約のファイル(OpenAPI・AsyncAPI・ファイルの仕様と、AsyncAPI が参照する Avro)を消さない",
        "16.1, ADR-0034",
        "その契約を使う連携を retired にしてから消す。新しい版は別のファイル(v{n+1})で足し、古い版の連携を廃止の手順に乗せる",
    ),
    LIFECYCLE_RETIRED_BEFORE_SUNSET(
        "CC-LIFECYCLE-004",
        "retired に進めるのは、deprecated の移行の期限(deprecation.sunset)の後だけ",
        "16.1, ADR-0034",
        "移行の期限を待つ。消費者の移行が終わって早めるときは、通知(deprecation.notice)で合意してから sunset を前に直す",
    ),
    LIFECYCLE_PAST_SUNSET(
        "CC-LIFECYCLE-005",
        "移行の期限を過ぎた deprecated の連携(警告。ジョブは失敗させない)",
        "16, ADR-0034",
        "消費者の移行を確かめて retired に進める。移行が終わらないなら、理由を Issue に残して sunset を延ばす",
    ),

    // --- Canonical ---
    CANONICAL_AVRO(
        "CC-CANON-001",
        "Canonical Model(Kotlin)と Avro スキーマの項目が一致する",
        "15.1, ADR-0012",
        "ADR-0012 の変換規則に従って、項目名・必須性・型を Canonical Model と揃える",
    ),
    CANONICAL_AVRO_MISSING(
        "CC-CANON-002",
        "Canonical Model に対応づけた Avro の型が存在する",
        "15.1, ADR-0012",
        "CanonicalBindings の対応表か、contracts/avro の型名を直す",
    ),

    // --- ツール ---
    TOOL_FAILURE(
        "CC-TOOL-001",
        "検査ツール(oasdiff)を実行できる",
        "19",
        "--oasdiff に oasdiff の実行ファイルを指定する(./gradlew :tools:contract-check:run は自動で取得する)",
    ),
}

/** 違反 1 件。[file] はリポジトリルートからの相対パス。 */
data class Violation(
    val file: String,
    val rule: Rule,
    val message: String,
) {
    override fun toString(): String = "[${rule.id}] $file: $message"
}
