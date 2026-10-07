# ADR-0025: Kafka の Producer・Avro の Serde・Schema Registry の使い方
- Status: Accepted
- Date: 2026-10-02
- Framework 参照章: 6.1, 6.3, 6.4, 8.3, 13.2, 14.1, 15.3

## Context
P06 では、注文のイベントを Outbox + Debezium で発行する(ADR-0007)。ADR-0007 は「アプリが Apicurio の Avro シリアライザでペイロードをバイト列にして保存し、スキーマ ID はペイロードの先頭に埋め込む」と決めたが、次の点は決めていなかった。
- 埋め込む形式の具体(ID の大きさ・どの ID か)と、それを作る実装(公式の Serde か、自前か)。
- スキーマをいつ・誰がレジストリに登録し、サービスがどうやって ID を知るか。
- 書き込みのたびにレジストリへ問い合わせると、注文 API の可用性がレジストリに引きずられる。
- CDC の Anti-Corruption 変換(P06 ⑤)など、DB の更新を伴わずに Kafka へ直接送る場面の Producer の作法(ヘッダ・トレース・配信保証)。
- Kafka と Avro のライブラリを、どのモジュールで使ってよいか。

## Decision
### 1. wire format と Serde
- 形式は Apicurio Registry 3 の公式の Serde の既定と同じにする: `[0x00][contentId: 4 バイト・ビッグエンディアン][Avro のバイナリ]`(`Default4ByteIdHandler`・`apicurio.registry.use-id=contentId`・`apicurio.registry.headers.enabled=false`)。Confluent の形式とも同じ。
  - ID は globalId ではなく **contentId** にする。同じ内容のスキーマは、アーティファクトや版が違っても同じ ID になる。
  - ID を Kafka のヘッダに入れる形式は使わない。Outbox 経由では、シリアライザが付けたヘッダが Kafka に届かないため(ADR-0007 §1)。
- Serde は公式の実装を使わず、`platform/messaging-kafka` に自前で持つ(`ApicurioWireFormat`・`AvroEventSerializer`・`AvroEventDeserializer`)。エンコードとデコードは avro4k(kotlinx.serialization)で行う。
  - 公式の Serde は Apicurio の SDK(kiota・Vert.x)を引き込み、ID の解決をリクエストの処理中に行う(§3 と両立しない)。
  - 相互運用は統合テスト(`ApicurioInteropIT`)で **双方向に** 確かめる。自前の serializer で書いたものを公式の `AvroKafkaDeserializer` で読めること、公式の `AvroKafkaSerializer` で書いたものを自前の deserializer で読めること。公式の Serde はこのテストでだけ使う(§5)。
- 書き手のスキーマは契約のスキーマ(`contracts/avro`)にし、Kotlin のクラスから生成したスキーマは使わない。avro4k は型とスキーマを **record のフルネーム**(または alias)で対応させるため、イベントの型の `@SerialName` は契約の record のフルネーム(例 `io.eia.events.sales.OrderCreated`)にする。入れ子の record も同じ。
  - Canonical Model の型(`io.eia.shared.canonical.*`)はシリアル名が契約の record 名と違うため、そのままでは書けない。サービスの adapters に、契約の名前を付けたイベントの型を置き、Canonical Model から写す(ADR-0010 Decision 7 の「変換は adapters」と同じ)。
- 受信側は contentId から書き手のスキーマを取り(`WriterSchemas`。内容は不変なので期限なしでキャッシュする)、そのスキーマで読む。FULL 互換(ADR-0014)の範囲なら、読み手の型と版が違っても読める。

### 2. スキーマの登録(Contract First)
- アーティファクトは `{groupId}/{topic}-value`(`groupId` は `default`)。公式の Serde の既定(`TopicIdStrategy`)と同じなので、公式の Serde を使う相手とも、同じアーティファクトで相互運用できる。
- 登録は `tools/schema-publish`(`make schemas`)だけが行う。AsyncAPI(`contracts/asyncapi`)のチャネルの `address` をトピック名、メッセージの `payload.schema.$ref` の Avro ファイルをスキーマとして、`POST /groups/default/artifacts?ifExists=FIND_OR_CREATE_VERSION&canonical=true` で登録する。同じ内容なら既存の版を返し、異なれば新しい版を作る。互換性の違反はレジストリ(FULL_TRANSITIVE。ADR-0014・ADR-0016 §7)が拒否する(終了コード 1)。
- **サービスは自動登録しない**(公式の Serde の `auto-register` に当たる動作を持たない)。契約にないスキーマがレジストリに入り込むのを防ぐため。
- 1 つのチャネルには 1 つのスキーマだけを許す(トピックごとのアーティファクトは 1 つのため)。
- 公式の Serde で書く相手は、`apicurio.registry.canonicalize=true` を設定する。契約は整形した JSON のまま登録するため、正規化しないと内容の検索が一致しない。

### 3. スキーマ ID の事前の解決(可用性)
- サービスが書き込みに使うスキーマの contentId は、起動時に `SchemaIdBook` ですべて解決し、メモリに持つ。**リクエストの処理中はレジストリに問い合わせない**(`SchemaIdBook.contentIdOf` はメモリだけを見る)。解決した ID は内容に対して不変なので、期限を設けない。
- すべての ID が解決するまで、サービスの `/health/ready` は失敗にする(トラフィックを受けない)。レジストリが止まっていても、サービスは起動して解決を繰り返す(`resolveUntilReady`。既定 5 秒ごと)。未登録(`make schemas` の実行漏れ)も繰り返し、後から登録すれば回復する。
- 解決した後は、レジストリを止めてもイベントの書き込み(Outbox への保存・Kafka への送信)は続けられる。プラットフォームの部品では `ApicurioRegistryClientIT` で確かめる。注文の作成で確かめるのは P06 ③(統合テストか e2e)。
- 契約のスキーマを変えて新しい版を登録した場合、稼働中のサービスは古い ID を使い続ける。新しい版で書くには、サービスの再起動(デプロイ)が必要になる。デプロイの手順で `make schemas` → サービスの更新の順にする。

### 4. Producer(DB の更新を伴わない送信)
- **DB の更新と組み合わせるイベントは Outbox(ADR-0007)で発行し、`EventProducer` は使わない**(二重書き込みの禁止)。`EventProducer` は、受け取ったイベントを整形して送り直すなど、DB の更新を伴わない送信だけに使う。
- 設定は `acks=all` と冪等な Producer(`enable.idempotence=true`)。キーと値はバイト列で、キーは集約の ID などのパーティションキー(UTF-8)。
- 送信ごとに PRODUCER の span(`{topic} publish`)を作り、その `traceparent` と呼び出し元の Correlation ID(なければ採番)をヘッダに入れる。ヘッダは CloudEvents の binary mode(`ce_id` / `ce_source` / `ce_type` / `ce_time` / `ce_specversion`)と `traceparent`・`correlationid` で、値はすべて UTF-8 の文字列(`EventMetadata`)。Outbox 経由の Debezium も同じ名前・同じ形式でヘッダを載せる(ADR-0007 §1)。`ce_type` はトピック名から `.v{n}` を除いたもの。`ce_id` は UUIDv7(`EventIds`)で採番する。Outbox でも同じ値を `id` 列に入れる(ADR-0007 の改訂履歴 2026-10-07)。
- 失敗は `PublishFailed` にし、Kafka の `RetriableException` なら Retryable、それ以外は NonRetryable にする。ログ・span・エラーにペイロードの値は入れない。

### 5. ライブラリの配置(Konsist の `messagingLibrariesOnlyInAllowedModules`)
- Kafka のクライアント(`org.apache.kafka`)と avro4k(`com.github.avrokotlin`)を本番コードで使ってよいのは、`platform/messaging-kafka` と `services/*/adapters`・`app` だけ。`org.apache.avro` は、これに加えて tools(contract-check の互換性の検査)でも使ってよい。
- Apicurio の公式のライブラリ(`io.apicurio`)は、テストのソースセットでだけ使う(§1 の相互運用の検査)。
- platform の依存に `messaging-kafka → schema-registry`(§3 の ID)と `messaging-kafka → observability`(§4 の span と Correlation ID)を加える(`PlatformDependencyRules`・MODULE_DESIGN §2)。

## Alternatives Considered
- **公式の Serde(`AvroKafkaSerializer`)をそのまま使う**: 自動登録なしでも、最初の書き込みのときにレジストリへ問い合わせて ID を解決する。キャッシュの期限が切れると問い合わせ直す。注文 API の可用性がレジストリに引きずられる(§3 の要件を満たせない)。Outbox への保存では Kafka の Producer を通さないため、Serde の Kafka 向けの機能(ヘッダなど)も使わない。SDK(kiota・Vert.x)の依存も大きい。不採用。相互運用だけはテストで担保する。
- **globalId を埋め込む / 8 バイトの ID にする**: globalId は版ごとに変わり、同じ内容でもアーティファクトごとに違う ID になる。8 バイトの形式(`Legacy8ByteIdHandler`)は Apicurio 2 の既定で、Confluent の形式と互換がない。不採用。
- **サービスが起動時に自動登録する**: 契約(contracts)を経ずにスキーマがレジストリに入る。レジストリの互換性ルールで止まるのが本番の起動時になり、CI の contract-check より遅い。不採用。
- **ID を解決できなくても起動を失敗させる(readiness ではなく liveness)**: レジストリの一時的な停止で、全サービスが再起動を繰り返す。起動して ready にならない方が、回復が速く、原因(レジストリ)もはっきりする。不採用。
- **avro4k ではなく Apache Avro の GenericRecord で組み立てる**: 項目名を文字列で書くため、型の検査が効かない。avro4k なら kotlinx.serialization の型から書けて、Canonical Model との変換も Kotlin の型で書ける。不採用。

## Consequences(トレードオフ)
- wire format を自前で持つため、Apicurio の既定が変わった場合は追従が必要になる。版を上げるときは `ApicurioInteropIT` で検知する(公式の Serde の版は images.env の Apicurio と揃える)。
- イベントの型の `@SerialName` を契約の record 名に揃える必要がある。ずれていればエンコードが失敗する(`EventEncodingFailed`)ので、最初のテストで気付ける。
- 新しいスキーマの版の反映には、`make schemas` とサービスの再起動の両方が必要になる。
- 契約を登録しないと、イベントを書くサービスは ready にならない。ローカル基盤では、compose の `schema-publish`(1 回だけ動くコンテナ。`make schemas` と同じ処理)が登録し、`order-service` はその完了を待って起動する(P06 ③a)。`make schemas` は、基盤を起動したまま契約を変えたときに使う。
- レジストリの REST はローカルでは未認証(ADR-0008 の「基盤の管理 API の認証」の縮退。対応は #29)。認証を加えるときは `ApicurioRegistryClient` に認証のヘッダ(OIDC の Client Credentials など)を加える。

## 改訂履歴
- 2026-10-07: P06 ② で、`ce_id` の採番を UUIDv4 から UUIDv7(`EventIds`)に改めた。Outbox の `id` 列と同じ値にするため(ADR-0007 の改訂履歴 2026-10-07)。
- 2026-10-07: P06 ③a で、`make up PROFILE=order` から契約を自動で登録するようにした(compose の `schema-publish`)。注文の作成で、解決の後に Apicurio を止めても注文を作れることを確かめた(`OrderEventOutboxIT`)。
