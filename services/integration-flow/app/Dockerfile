# integration-flow(iPaaS 風の Flow の実行。ADR-0036)のコンテナイメージ。order-service と同じ作り(ADR-0024 §7)。
# - ベースは images.env の JAVA_RUNTIME_IMAGE(distroless の Java 21、nonroot)。シェルがないので java を直接起動する。
# - 中身は `./gradlew :services:integration-flow:app:installDist` の出力。Flow の定義(contracts/flows)はクラスパスに含まれる。
# - コマンドは migrate(所有者の資格情報)・serve(アプリのロールの資格情報)・validate。compose が引数で渡す(P10 ⑥)。
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY build/install/app/lib/ /app/lib/
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "io.eia.integrationflow.app.MainKt"]
