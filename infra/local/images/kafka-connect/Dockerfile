# Kafka Connect(Debezium の Postgres コネクタ + Apicurio の Converter)のイメージ(ADR-0016 §9)。
#
# Debezium の公式イメージ(quay.io/debezium/connect)は使わない。最新のパッチ版のタグが毎日付け直され、
# 古いダイジェストが取得できなくなるため、ダイジェストで固定できない(ADR-0016 §9)。
# 代わりに、images.env で固定した apache/kafka に、Maven Central の成果物をバージョンと SHA-256 で固定して載せる
# (Maven Central は公開後に成果物を差し替えられない)。
#
# ベースイメージは images.env の KAFKA_IMAGE を build 引数で受け取る(docker compose / Testcontainers の両方から渡す)。
# 既定値は置かない(渡し忘れたらビルドを失敗させ、images.env と版がずれないようにする)。
ARG KAFKA_IMAGE

# ------------------------------------------------------------------ 成果物の取得と展開
FROM ${KAFKA_IMAGE} AS plugins

# 版を上げるときは、バージョンと SHA-256 を両方更新する(SHA-256 は Maven Central の .sha256 と、取得したファイルの値の両方で確認する)
ARG DEBEZIUM_VERSION=3.6.3.Final
ARG DEBEZIUM_POSTGRES_SHA256=68dfbd3aa0e22cbc164017311449f69dfe6b96b70e72c72c7a3a24ef5c7810e0
# Apicurio Registry(サーバ。images.env の APICURIO_REGISTRY_IMAGE)と同じ版にする
ARG APICURIO_VERSION=3.3.3
ARG APICURIO_CONVERTER_SHA256=1c28bb0fc4e737f56bba389a936535565607d89502458d5f28bbe4c02ac4bb3a

USER root
# ADD --checksum は取得したファイルの SHA-256 が一致しなければビルドを失敗させる
ADD --checksum=sha256:${DEBEZIUM_POSTGRES_SHA256} \
    https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz \
    /tmp/debezium-connector-postgres.tar.gz
ADD --checksum=sha256:${APICURIO_CONVERTER_SHA256} \
    https://repo1.maven.org/maven2/io/apicurio/apicurio-registry-distro-connect-converter/${APICURIO_VERSION}/apicurio-registry-distro-connect-converter-${APICURIO_VERSION}.tar.gz \
    /tmp/apicurio-converter.tar.gz
# plugin.path の下にプラグインごとのディレクトリを置く。
# Apicurio の配布物に含まれる kafka-clients は、Connect の本体(ベースイメージ)の版を使うため取り除く
# (org.apache.kafka のクラスは本体から読まれるので使われないが、別の版の jar を並べておかない)
RUN mkdir -p /plugins/apicurio-converter \
    && tar -xzf /tmp/debezium-connector-postgres.tar.gz -C /plugins \
    && tar -xzf /tmp/apicurio-converter.tar.gz -C /plugins/apicurio-converter \
    && rm -f /plugins/apicurio-converter/kafka-clients-*.jar \
    && test -f /plugins/debezium-connector-postgres/debezium-connector-postgres-${DEBEZIUM_VERSION}.jar \
    && test -f /plugins/apicurio-converter/apicurio-registry-utils-converter-${APICURIO_VERSION}.jar

# ------------------------------------------------------------------ 実行用のイメージ
FROM ${KAFKA_IMAGE}

USER root
COPY --from=plugins /plugins /opt/kafka/plugins
COPY --chmod=0755 connect-entrypoint.sh /opt/kafka/connect-entrypoint.sh
# 起動時に設定ファイルを書き出す場所(ベースイメージの利用者 appuser が書ける場所)
RUN mkdir -p /opt/kafka/connect-config && chown appuser:appuser /opt/kafka/connect-config
USER appuser

EXPOSE 8083
# ENTRYPOINT はベースイメージのもの(/__cacert_entrypoint.sh)をそのまま使う
CMD ["/opt/kafka/connect-entrypoint.sh"]
