# shipping-service(注文 Saga の参加者。ADR-0029)のコンテナイメージ。order-service と同じ作り(ADR-0024 §7)。
# - ベースは images.env の JAVA_RUNTIME_IMAGE(distroless の Java 21、nonroot)。シェルがないので java を直接起動する。
# - 中身は `./gradlew :services:shipping:app:installDist` の出力。make up PROFILE=saga が先に作る。
# - コマンドは migrate(所有者の資格情報)か serve(アプリのロールの資格情報)。compose が引数で渡す。
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY build/install/app/lib/ /app/lib/
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "io.eia.shipping.app.MainKt"]
