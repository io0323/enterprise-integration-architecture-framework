# 契約のスキーマを Schema Registry に登録する 1 回だけのコンテナ(compose の schema-publish。ADR-0025 §2)。`make schemas` と同じ処理。
# - ベースは images.env の JAVA_RUNTIME_IMAGE(distroless の Java 21、nonroot)。order-service のイメージと同じ方式(ADR-0024 §7)。
# - 中身は `./gradlew :tools:schema-publish:installDist` の出力。make up PROFILE=order が先に作る(order-dist)。
# - 契約(contracts/)はイメージに入れず、compose が読み取り専用でマウントする(登録するのは、いま手元にある契約)。
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY build/install/schema-publish/lib/ /app/lib/
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-cp", "/app/lib/*", "io.eia.tools.schemapublish.MainKt"]
