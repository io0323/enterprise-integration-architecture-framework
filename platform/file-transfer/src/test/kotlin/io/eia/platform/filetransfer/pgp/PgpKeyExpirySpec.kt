package io.eia.platform.filetransfer.pgp

import io.eia.platform.security.secret.Secret
import io.eia.platform.testsupport.TestKeys
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.shouldBe
import java.time.Instant

private const val DAY = 86_400L

/** 取引先の公開鍵の中の有効期限(鍵の期限の監視に使う。ADR-0040 §3)。 */
class PgpKeyExpirySpec :
    FunSpec({
        fun expiry(key: TestKeys.Pgp): Instant? = PgpKeys.publicKeyExpiry(Secret(key.publicKey))

        fun daysFromNow(instant: Instant?): Long = (checkNotNull(instant).epochSecond - Instant.now().epochSecond) / DAY

        test("期限のない鍵は null") {
            expiry(TestKeys.pgp("Partner <partner@example.com>")) shouldBe null
        }

        test("主鍵と暗号化の副鍵のうち、早いほうの期限") {
            daysFromNow(expiry(TestKeys.pgp("P <p@example.com>", masterExpiry = 30 * DAY))).shouldBeBetween(29, 30)
            daysFromNow(expiry(TestKeys.pgp("P <p@example.com>", masterExpiry = 30 * DAY, subkeyExpiry = 10 * DAY))).shouldBeBetween(9, 10)
            daysFromNow(expiry(TestKeys.pgp("P <p@example.com>", subkeyExpiry = 5 * DAY))).shouldBeBetween(4, 5)
        }

        test("読めない鍵は null(送信のときに鍵の誤りになる)") {
            PgpKeys.publicKeyExpiry(Secret("not a key")) shouldBe null
        }
    })
