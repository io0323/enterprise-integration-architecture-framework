package io.eia.platform.testsupport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain

class SeaweedFsContainerSpec :
    FunSpec({
        test("identity ごとの資格情報と操作を、compose の s3.json と同じ形の JSON にする") {
            val json =
                SeaweedFsContainer.s3Config(
                    linkedMapOf("eiaf" to listOf("Admin", "Read"), "eiaf-audit-order" to listOf("Write:eiaf-audit/anchors/order/*")),
                    mapOf(
                        "eiaf" to SeaweedFsContainer.S3Credentials("ak1", "sk1"),
                        "eiaf-audit-order" to SeaweedFsContainer.S3Credentials("ak2", "sk2"),
                    ),
                )
            json shouldBe
                """{"identities":[""" +
                """{"name":"eiaf","credentials":[{"accessKey":"ak1","secretKey":"sk1"}],"actions":["Admin","Read"]},""" +
                """{"name":"eiaf-audit-order","credentials":[{"accessKey":"ak2","secretKey":"sk2"}],""" +
                """"actions":["Write:eiaf-audit/anchors/order/*"]}""" +
                "]}"
        }

        test("コンテナを組み立てると、identity ごとに乱数の資格情報を作る(起動はしない)") {
            val container = SeaweedFsContainer(mapOf("a" to listOf("Read"), "b" to listOf("Write")))
            container.credentials.keys shouldBe setOf("a", "b")
            container.credentials.getValue("a").secretKey shouldMatch Regex("^[0-9a-f]{32}$")
            container.credentials.getValue("a").toString() shouldNotContain container.credentials.getValue("a").secretKey
        }
    })
