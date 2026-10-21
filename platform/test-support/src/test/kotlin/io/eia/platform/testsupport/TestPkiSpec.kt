package io.eia.platform.testsupport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class TestPkiSpec :
    FunSpec({
        test("CA が署名した証明書(SAN の DNS 名・有効期間)と、PEM の証明書と PKCS#8 の鍵のファイル") {
            TestPki().use { pki ->
                val from = Clock.System.now() - 2.days
                val issued = pki.issue("service", listOf("service", "localhost"), validFrom = from, validity = 10.days)
                issued.cert.verify(pki.ca.publicKey)
                issued.cert.subjectAlternativeNames.map { it[1] } shouldContainExactly listOf("service", "localhost")
                issued.cert.notBefore.time shouldBe from.toEpochMilliseconds() / 1000 * 1000
                pki.ca.basicConstraints shouldBe Int.MAX_VALUE
                Files.readString(pki.caFile) shouldContain "BEGIN CERTIFICATE"
                Files.readString(issued.certFile) shouldContain "BEGIN CERTIFICATE"
                Files.readString(issued.keyFile) shouldContain "BEGIN PRIVATE KEY"
            }
        }

        test("クライアント証明書を出す SSLContext と、出さない SSLContext を作れる。閉じるとファイルを消す") {
            val pki = TestPki("Spec CA")
            val issued = pki.issue("apisix", listOf("apisix"))
            issued.clientContext(pki).protocol shouldBe "TLS"
            pki.anonymousClientContext().protocol shouldBe "TLS"
            pki.ca.subjectX500Principal.name shouldBe "CN=Spec CA"
            pki.close()
            Files.exists(pki.caFile) shouldBe false
            Files.exists(issued.keyFile) shouldBe false
        }
    })
