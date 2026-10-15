# 計測用のイメージ。services/*/app/Dockerfile と同じ作り(ベースは images.env の JAVA_RUNTIME_IMAGE。distroless の Java 21、nonroot)。
# 起動の引数は `<メインクラス> <出力のディレクトリ> <件数>`(run.sh が渡す)。
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
ARG CANDIDATE
WORKDIR /app
COPY ${CANDIDATE}/build/install/bench/lib/ /app/lib/
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*"]
