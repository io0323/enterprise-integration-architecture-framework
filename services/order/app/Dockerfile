# order-service のコンテナイメージ(ADR-0024 §7)。
# - ベースは images.env の JAVA_RUNTIME_IMAGE(distroless の Java 21、nonroot。タグ + ダイジェストで固定)。シェルがないので、
#   起動スクリプトは使わず java を直接起動する。
# - 中身は `./gradlew :services:order:app:installDist` の出力(build/install/app/lib)。make up PROFILE=order が先に作る。
# - サブコマンドは migrate(所有者の資格情報でマイグレーション)/ serve(リクエストの処理)。ADR-0024 §2
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY build/install/app/lib/ /app/lib/
# distroless の nonroot(65532)。compose はローカルの鍵を読むために、ホストの利用者の uid で上書きする(root にはしない)
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "io.eia.order.app.MainKt"]
CMD ["serve"]
