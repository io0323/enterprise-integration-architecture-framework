package io.eia.platform.testsupport

import org.testcontainers.kafka.KafkaContainer

/**
 * Kafka のテスト用コンテナ。イメージは images.env の `KAFKA_IMAGE`。統合テストの Kafka は、すべてこれで立てる(#170)。
 *
 * Testcontainers の [KafkaContainer] は、コンテナを起動してから起動のスクリプトをコピーし、コンテナはファイルが現れたらすぐに実行する。
 * コピーが書き込みのためにファイルを開いている間に実行すると、`Text file busy`(ETXTBSY。sh の終了コード 126)で落ちる。
 * そこで起動のコマンドを差し替え、実行が 126 で終わったときだけ、少し待って実行し直す(回数に上限を付ける)。
 */
public class KafkaBrokerContainer : KafkaContainer(InfraImages.get("KAFKA_IMAGE")) {
    init {
        withCommand("sh", "-c", COMMAND)
    }

    private companion object {
        const val STARTER_SCRIPT = "/tmp/testcontainers_start.sh"

        // 126 での実行し直しは 0.1 秒おきに 50 回(5 秒)まで。それ以外の終了コードは、そのまま返す
        const val COMMAND =
            "while [ ! -f $STARTER_SCRIPT ]; do sleep 0.1; done; " +
                "i=0; while :; do $STARTER_SCRIPT; rc=\$?; " +
                "if [ \$rc -ne 126 ] || [ \$i -ge 50 ]; then exit \$rc; fi; i=\$((i + 1)); sleep 0.1; done"
    }
}
