package io.eia.platform.security.jwt

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 起動の直後は遅い(タイムアウトする)IdP。[slow] を false にすると JWKS を返す。 */
private class SlowStartingIdp(
    private val jwks: JWKSet,
) : ResourceRetriever {
    val fetches = AtomicInteger()

    @Volatile var slow = true

    override fun retrieveResource(url: URL): Resource {
        fetches.incrementAndGet()
        if (slow) throw SocketTimeoutException("Read timed out")
        return Resource(jwks.toString(true), "application/json")
    }
}

private fun verifier(idp: SlowStartingIdp): JwtVerifier =
    JwtVerifier(
        JwtVerifierConfig(ISSUER, AUDIENCE, JWKS_URI, jwks = JwksConfig(rateLimitMinInterval = 50.milliseconds)),
        idp,
        Clock.System,
        null,
    )

private fun token(): String = signRsa(validClaims(now = Clock.System.now()).build(), key = TestKeys.rsa)

/** #74: 起動時の JWKS の先読みと、取得できるまで ready にしないこと(ADR-0019 改訂履歴)。 */
class JwksPrefetchSpec :
    FunSpec({
        test("IdP がタイムアウトする間は先読みに失敗して ready にならず、応答するようになれば取得して ready になる") {
            val idp = SlowStartingIdp(TestKeys.jwks(TestKeys.rsa))
            verifier(idp).use { verifier ->
                verifier.isReady shouldBe false
                verifier.prefetch() shouldBe false
                verifier.isReady shouldBe false

                idp.slow = false
                delay(100.milliseconds)
                verifier.prefetch() shouldBe true
                verifier.isReady shouldBe true

                // 先読みした JWKS で検証する(リクエストの中では取得しない)
                val fetched = idp.fetches.get()
                verifier.verifyBlocking(token()).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
                idp.fetches.get() shouldBe fetched
            }
        }

        test("prefetchUntilLoaded は取得できるまで繰り返す。取り直しの頻度の制限の中では IdP に問い合わせない") {
            val idp = SlowStartingIdp(TestKeys.jwks(TestKeys.rsa))
            verifier(idp).use { verifier ->
                coroutineScope {
                    val loading = async { verifier.prefetchUntilLoaded(interval = 10.milliseconds) }
                    delay(300.milliseconds)
                    verifier.isReady shouldBe false
                    // 10ms ごとに呼んでも(約 30 回)、問い合わせは制限の間隔(50ms)ごとに 2 回まで(Nimbus の RateLimitedJWKSetSource)
                    idp.fetches.get() shouldBeLessThanOrEqual 2 * (300 / 50 + 1)
                    idp.slow = false
                    withTimeout(5.seconds) { loading.await() }
                }
                verifier.isReady shouldBe true
            }
        }

        test("一度取得した後は、IdP が止まっても ready のまま(キャッシュで検証する)。先読みなしでも、検証で取得できれば ready") {
            val idp = SlowStartingIdp(TestKeys.jwks(TestKeys.rsa))
            idp.slow = false
            verifier(idp).use { verifier ->
                verifier.verifyBlocking(token()).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
                verifier.isReady shouldBe true
                idp.slow = true
                verifier.verifyBlocking(token()).shouldBeInstanceOf<Result.Ok<VerifiedToken>>()
                verifier.isReady shouldBe true
            }
        }
    })
