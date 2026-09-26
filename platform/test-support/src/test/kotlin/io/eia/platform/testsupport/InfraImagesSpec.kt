package io.eia.platform.testsupport

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.testcontainers.utility.DockerImageName

private const val DIGEST = "sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837"

class InfraImagesSpec :
    FunSpec({
        test("コメントと空行を無視して KEY=VALUE を読む") {
            val entries =
                InfraImages.parse(
                    listOf(
                        "# コメント",
                        "",
                        "KAFKA_IMAGE=apache/kafka:4.3.1@$DIGEST",
                        "  PROBE_IMAGE = busybox:1.38.0-musl@$DIGEST  ",
                    ),
                )

            entries shouldBe
                mapOf(
                    "KAFKA_IMAGE" to "apache/kafka:4.3.1@$DIGEST",
                    "PROBE_IMAGE" to "busybox:1.38.0-musl@$DIGEST",
                )
        }

        test("タグを落としてダイジェストで固定したイメージ名にする") {
            val image = InfraImages.toDockerImageName("apache/kafka:4.3.1@$DIGEST")

            image.repository shouldBe "apache/kafka"
            image.versionPart shouldBe DIGEST
            image.asCanonicalNameString() shouldBe "apache/kafka@$DIGEST"
        }

        test("レジストリにポートがあってもタグだけを落とす") {
            InfraImages.toDockerImageName("localhost:5000/team/app:1.0@$DIGEST").asCanonicalNameString() shouldBe
                "localhost:5000/team/app@$DIGEST"
            InfraImages.toDockerImageName("quay.io/keycloak/keycloak@$DIGEST").asCanonicalNameString() shouldBe
                "quay.io/keycloak/keycloak@$DIGEST"
        }

        test("重複したキーと、compose と解釈がずれる値を拒否する") {
            shouldThrow<IllegalArgumentException> {
                InfraImages.parse(listOf("KAFKA_IMAGE=a@$DIGEST", "KAFKA_IMAGE=b@$DIGEST"))
            }.message shouldContain "重複"
            listOf(
                "KAFKA_IMAGE=apache/kafka:4.3.1@$DIGEST # 後置コメント",
                "KAFKA_IMAGE=\"apache/kafka:4.3.1@$DIGEST\"",
                "KAFKA_IMAGE='apache/kafka:4.3.1@$DIGEST'",
                "KAFKA_IMAGE=",
            ).forEach { line ->
                shouldThrow<IllegalArgumentException> { InfraImages.parse(listOf(line)) }.message shouldContain "KAFKA_IMAGE"
            }
        }

        test("Testcontainers のモジュールが要求するイメージ名との互換を保つ") {
            InfraImages
                .toDockerImageName("postgres:18.6-alpine@$DIGEST")
                .isCompatibleWith(DockerImageName.parse("postgres")) shouldBe true
        }

        test("ダイジェストのない参照・存在しないキー・不正な行は例外にする") {
            shouldThrow<IllegalArgumentException> { InfraImages.toDockerImageName("postgres:latest") }
            shouldThrow<IllegalStateException> { InfraImages.get(emptyMap(), "NOPE_IMAGE") }.message shouldContain "NOPE_IMAGE"
            shouldThrow<IllegalArgumentException> { InfraImages.parse(listOf("NO_SEPARATOR")) }
        }

        test("Gradle から渡された images.env を読み、ローカル基盤の主なイメージがある") {
            listOf("POSTGRES_IMAGE", "KAFKA_IMAGE", "KEYCLOAK_IMAGE", "SEAWEEDFS_IMAGE", "OTEL_COLLECTOR_IMAGE", "PROBE_IMAGE")
                .forEach { InfraImages.get(it).versionPart shouldStartWith "sha256:" }
        }
    })
