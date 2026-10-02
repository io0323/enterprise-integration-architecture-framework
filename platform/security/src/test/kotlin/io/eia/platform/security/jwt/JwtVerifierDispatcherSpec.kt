package io.eia.platform.security.jwt

import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import io.eia.shared.kernel.Result
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

/** 取得を [release] まで止められる IdP。 */
private class StallingIdp : ResourceRetriever {
    val fetches = AtomicInteger()
    val fetching = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun retrieveResource(url: URL): Resource {
        fetches.incrementAndGet()
        fetching.countDown()
        release.await()
        return Resource(TestKeys.jwks(TestKeys.rsa).toString(true), "application/json")
    }
}

/** ラッチを待つ上限(合否は時間で決めない。性質が崩れたときに、止まらずに失敗させるための上限)。 */
private const val WAIT_SECONDS = 20L

/** 共有の Dispatchers.IO の並列数の上限(kotlinx.coroutines の既定: 64 と CPU の数の大きい方)。 */
private val IO_PARALLELISM = maxOf(64, Runtime.getRuntime().availableProcessors())

/**
 * 検証を動かす dispatcher(ADR-0019 §3。計測は docs/reports/p05-jwks-dispatcher.md)。
 * 待ち時間の数値ではなく、時間に左右されない性質(スレッドを占有しないこと・取得の回数)で確かめる。
 */
class JwtVerifierDispatcherSpec :
    FunSpec({
        test("IdP の取得が止まっている間、多数の検証が待っていても、共有の Dispatchers.IO のスレッドは全部使える") {
            val idp = StallingIdp()
            JwtVerifier(JwtVerifierConfig(ISSUER, AUDIENCE, JWKS_URI), idp, Clock.System, null).use { verifier ->
                val token = signRsa(validClaims(now = Clock.System.now()).build(), key = TestKeys.rsa)
                coroutineScope {
                    // 共有の IO の上限より多くの検証を、取得が止まった状態で待たせる
                    val verifications = (1..IO_PARALLELISM * 2).map { async { verifier.verify(token) } }
                    val allRan =
                        try {
                            // テストのスレッドを塞がずに待つ(async の検証は同じスレッドで始まるため)
                            withContext(Dispatchers.Default) { idp.fetching.await(WAIT_SECONDS, TimeUnit.SECONDS) } shouldBe true
                            // 共有の Dispatchers.IO の上限の数だけ、ブロッキングの処理を同時に動かせるか(検証が IO のスレッドを占有していれば、
                            // 全部はそろわない)
                            val allRunning = CountDownLatch(IO_PARALLELISM)
                            repeat(IO_PARALLELISM) {
                                launch(Dispatchers.IO) {
                                    allRunning.countDown()
                                    allRunning.await(WAIT_SECONDS, TimeUnit.SECONDS)
                                }
                            }
                            withContext(Dispatchers.Default) { allRunning.await(WAIT_SECONDS, TimeUnit.SECONDS) }
                        } finally {
                            // 失敗したときも取得を止めたままにしない(検証のスレッドが戻らず、テストが終わらなくなるため)
                            idp.release.countDown()
                        }
                    verifications.awaitAll().forEach { it.shouldBeInstanceOf<Result.Ok<VerifiedToken>>() }
                    allRan shouldBe true
                }
                idp.fetches.get() shouldBe 1
            }
        }

        test("未知の kid のトークンを同時に大量に送っても、JWKS の取得は取り直しの頻度の制限を超えない(401 にする)") {
            val fetches = AtomicInteger()
            val idp =
                ResourceRetriever {
                    fetches.incrementAndGet()
                    Resource(TestKeys.jwks(TestKeys.rsa).toString(true), "application/json")
                }
            JwtVerifier(JwtVerifierConfig(ISSUER, AUDIENCE, JWKS_URI), idp, Clock.System, null).use { verifier ->
                val unknown = signRsa(validClaims(now = Clock.System.now()).build(), key = TestKeys.rsaUnknown)
                val results = coroutineScope { (1..200).map { async { verifier.verify(unknown) } }.awaitAll() }
                results.forEach {
                    it shouldBe Result.Err(JwtVerificationError.InvalidToken(JwtRejectionReason.UNKNOWN_KEY))
                }
                // 最初の取得と、未知の kid での取り直し 1 回(既定の最小の間隔 30 秒の中)
                fetches.get() shouldBeLessThanOrEqual 2
            }
        }

        test("verificationParallelism は正の値だけを受け付ける") {
            shouldThrow<IllegalArgumentException> { JwksConfig(verificationParallelism = 0) }
        }
    })
