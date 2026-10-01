@file:Suppress("MagicNumber", "ForEachOnRange") // テストデータの件数・seq・範囲

package io.eia.platform.audit

import io.eia.platform.audit.anchor.AnchorCycle
import io.eia.platform.audit.anchor.AnchorKeys
import io.eia.platform.audit.anchor.AnchorOutcome
import io.eia.platform.audit.anchor.AnchorPublisher
import io.eia.platform.audit.anchor.PublishedAnchor
import io.eia.platform.audit.anchor.S3AnchorStore
import io.eia.platform.audit.anchor.ServiceName
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.audit.verify.AuditVerification
import io.eia.platform.audit.verify.Finding
import io.eia.platform.audit.verify.VerificationReport
import io.eia.shared.kernel.getOrNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val FORBIDDEN = 403

/** 統合テストの保持期限。テストの間は期限内で、長く残らない長さ。 */
private val RETENTION: Duration = Duration.ofMinutes(3)
private val MIN_RETENTION: Duration = Duration.ofMinutes(2)

/** PUT の要求のヘッダを記録する(Object Lock つきの put にチェックサムが付くことを確かめる)。 */
private class PutHeaderRecorder : ExecutionInterceptor {
    val puts = CopyOnWriteArrayList<Map<String, List<String>>>()

    override fun beforeTransmission(
        context: Context.BeforeTransmission,
        executionAttributes: ExecutionAttributes,
    ) {
        val request = context.httpRequest()
        if (request.method() == SdkHttpMethod.PUT) puts += request.headers().mapKeys { it.key.lowercase() }
    }
}

private fun forbidden(block: () -> Unit) {
    shouldThrow<S3Exception>(block).statusCode() shouldBe FORBIDDEN
}

class AuditAnchorIT :
    FunSpec({
        val env = AuditEnvironment()
        beforeSpec { env.start() }
        val log = AuditLog()
        val services = AtomicInteger()
        val recorder = PutHeaderRecorder()
        // アンカーは、サービスごとの書込み用の identity で書く(anchors/{service}/ の下だけ。Issue #43)
        val stores = mutableMapOf<ServiceName, S3AnchorStore>()

        fun storeFor(service: ServiceName): S3AnchorStore =
            stores.getOrPut(service) { env.anchorStore(AuditEnvironment.writer(service), listOf(recorder)) }

        afterSpec {
            stores.values.forEach { it.close() }
            env.close()
        }

        // 検査は、アンカーを書けない読み取り専用の資格情報で行う(make audit-verify と同じ)
        val verifyStore by lazy { env.anchorStore(AuditEnvironment.VERIFY) }

        fun newService(): ServiceName = ServiceName.parse("svc-${services.incrementAndGet()}").getOrNull()!!

        fun AuditDatabase.publish(service: ServiceName): PublishedAnchor =
            app.connection.use { AnchorPublisher(service, storeFor(service), RETENTION).publish(it).getOrNull().shouldNotBeNull() }

        fun AuditDatabase.verify(service: ServiceName): VerificationReport =
            app.connection.use { AuditVerification(service, verifyStore, MIN_RETENTION).run(it).getOrNull().shouldNotBeNull() }

        test("アンカーを COMPLIANCE・保持期限つきで anchors/{service}/{UTC の日付}.json に保存し、put にチェックサムが付く") {
            val db = env.newDatabase()
            val service = newService()
            (1..3).forEach { db.append(log, it) }
            recorder.puts.clear()
            val published = db.publish(service)
            published.key shouldBe "anchors/$service/${LocalDate.now(ZoneOffset.UTC)}.json"
            published.anchor.seq shouldBe 3
            val headers = recorder.puts.single()
            headers["content-md5"].shouldNotBeNull().single() shouldMatch Regex("^[A-Za-z0-9+/]{22}==$")
            headers["x-amz-checksum-sha256"].shouldNotBeNull().single() shouldMatch Regex("^[A-Za-z0-9+/]{43}=$")
            headers["x-amz-object-lock-mode"] shouldBe listOf("COMPLIANCE")
            val retention =
                env.admin
                    .getObjectRetention {
                        it.bucket(AuditEnvironment.BUCKET).key(published.key).versionId(published.versionId)
                    }.retention()
            retention.mode() shouldBe ObjectLockRetentionMode.COMPLIANCE
            // 保持期限は保存した時刻 + RETENTION(ストレージは秒の精度で保持する)
            Duration.between(published.retainUntil, retention.retainUntilDate()).abs() shouldBeLessThan Duration.ofSeconds(1)
            db.verify(service).findings.shouldBeEmpty()
        }

        test("同じ日に 2 回保存すると版として残り、全版を照合する。アンカーより後の記録を末尾から削除すると検出する") {
            val db = env.newDatabase()
            val service = newService()
            (1..3).forEach { db.append(log, it) }
            val first = db.publish(service)
            (4..5).forEach { db.append(log, it) }
            val second = db.publish(service)
            second.key shouldBe first.key
            val versions = env.admin.listObjectVersions { it.bucket(AuditEnvironment.BUCKET).prefix(AnchorKeys.prefix(service)) }.versions()
            versions shouldHaveSize 2
            db
                .verify(service)
                .also { it.anchorVersionCount shouldBe 2 }
                .findings
                .shouldBeEmpty()

            db.tamper("DELETE FROM audit.audit_log WHERE seq >= 4")
            db.verify(service).findings shouldContainExactly listOf(Finding.AnchorRecordMissing(second.key, second.versionId, 5, 3))
        }

        test("AnchorCycle: 差分を検証して保存し、増えなければ保存しない。再起動の後は S3 の最新の版を起点にし、差分の改竄は保存しない") {
            val db = env.newDatabase()
            val service = newService()

            fun cycle() = AnchorCycle(service, storeFor(service), AnchorPublisher(service, storeFor(service), RETENTION))

            fun AnchorCycle.check(): AnchorOutcome = db.app.connection.use { run(it).getOrNull().shouldNotBeNull() }
            val first = cycle()
            first.check() shouldBe AnchorOutcome.Empty
            (1..3).forEach { db.append(log, it) }
            first.check().shouldBeInstanceOf<AnchorOutcome.Published>().verifiedRecords shouldBe 3
            first.check() shouldBe AnchorOutcome.Unchanged(3)

            // 再起動したプロセス: S3 で最後に保存された版(seq=3)を起点にし、差分の 2 件だけを検証する
            val restarted = cycle()
            restarted.check() shouldBe AnchorOutcome.Unchanged(3)
            (4..5).forEach { db.append(log, it) }
            restarted.check().shouldBeInstanceOf<AnchorOutcome.Published>().verifiedRecords shouldBe 2
            db
                .verify(service)
                .also { it.anchorVersionCount shouldBe 2 }
                .findings
                .shouldBeEmpty()

            db.append(log, 6)
            db.tamper("UPDATE audit.audit_log SET actor_id = 'tampered' WHERE seq = 6")
            restarted.check() shouldBe AnchorOutcome.Rejected(listOf(Finding.HashMismatch(6)))
            env.admin.listObjectVersions { it.bucket(AuditEnvironment.BUCKET).prefix(AnchorKeys.prefix(service)) }.versions() shouldHaveSize
                2
        }

        test("AnchorCycle: サービスが追記を続けている間に繰り返しても、拒否にならない(1 つのスナップショットで読む)") {
            val db = env.newDatabase()
            val service = newService()
            val cycle = AnchorCycle(service, storeFor(service), AnchorPublisher(service, storeFor(service), RETENTION))
            (1..20).forEach { db.append(log, it) }
            val running = AtomicBoolean(true)
            val appended = AtomicInteger()
            val writer =
                Thread {
                    while (running.get()) db.append(log, 1_000 + appended.incrementAndGet())
                }.apply { start() }
            try {
                repeat(5) {
                    db.app.connection
                        .use { cycle.run(it) }
                        .getOrNull()
                        .shouldBeInstanceOf<AnchorOutcome.Published>()
                }
            } finally {
                running.set(false)
                writer.join()
            }
            db.verify(service).findings.shouldBeEmpty()
        }

        test("superuser がトリガーを外して末尾の記録の hash を書き換えると、チェーンとアンカーの照合で検出する") {
            val db = env.newDatabase()
            val service = newService()
            (1..4).forEach { db.append(log, it) }
            val published = db.publish(service)
            // 末尾の記録は後続の prev_hash で守られないため、アンカーとの照合で検出する
            db.tamper("UPDATE audit.audit_log SET hash = repeat('a', 64) WHERE seq = 4")
            db.verify(service).findings shouldContainExactly
                listOf(Finding.HashMismatch(4), Finding.AnchorHashMismatch(published.key, published.versionId, 4))
        }

        test("保持期限内のアンカーは管理者の資格情報でも削除できず、保持期限を短縮できない(書込み用の identity は下の権限のテスト)") {
            val db = env.newDatabase()
            val service = newService()
            db.append(log, 1)
            val published = db.publish(service)
            val shorter = Instant.now().plus(Duration.ofMinutes(1))
            forbidden {
                env.admin.deleteObject {
                    it
                        .bucket(AuditEnvironment.BUCKET)
                        .key(published.key)
                        .versionId(published.versionId)
                        .bypassGovernanceRetention(true)
                }
            }
            forbidden {
                env.admin.putObjectRetention {
                    it
                        .bucket(AuditEnvironment.BUCKET)
                        .key(published.key)
                        .versionId(published.versionId)
                        .bypassGovernanceRetention(true)
                        .retention { r -> r.mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(shorter) }
                }
            }
            forbidden {
                env.admin.putObjectRetention {
                    it
                        .bucket(AuditEnvironment.BUCKET)
                        .key(published.key)
                        .versionId(published.versionId)
                        .bypassGovernanceRetention(true)
                        .retention { r -> r.mode(ObjectLockRetentionMode.GOVERNANCE).retainUntilDate(published.retainUntil) }
                }
            }
            db.verify(service).findings.shouldBeEmpty()
        }

        test("order の書込み用の identity は、自分のプレフィックス(anchors/order/)にだけ書け、削除・Legal Hold・保持期限の短縮・管理操作ができない") {
            val bucket = AuditEnvironment.BUCKET
            val other = "eiaf-it-other"
            env.admin.createBucket { it.bucket(other) }
            env.admin.putObject({ it.bucket(other).key("k.txt") }, RequestBody.fromString("x"))
            val retainUntil = Instant.now().plus(Duration.ofMinutes(2))
            env.writerClient().use { order ->
                fun put(key: String) =
                    order.putObject(
                        {
                            it
                                .bucket(bucket)
                                .key(key)
                                .objectLockMode(ObjectLockMode.COMPLIANCE)
                                .objectLockRetainUntilDate(retainUntil)
                        },
                        RequestBody.fromString("{}"),
                    )
                // 自分のプレフィックスには COMPLIANCE の版を書ける
                val key = "anchors/order/it-probe-${System.nanoTime()}.json"
                val version = put(key).versionId()
                // ほかのサービスのプレフィックスと、anchors/ の外には書けない(ほかのサービスの検査を妨害できない)
                forbidden { put("anchors/svc-1/it-probe.json") }
                forbidden { put("it-probe.json") }
                // 自分のプレフィックスでも、削除・削除マーカー・Legal Hold・保持期限の短縮はできない(バケットポリシーの Deny)
                forbidden { order.deleteObject { it.bucket(bucket).key(key).versionId(version) } }
                forbidden { order.deleteObject { it.bucket(bucket).key(key) } }
                forbidden {
                    order.putObjectLegalHold {
                        it
                            .bucket(bucket)
                            .key(key)
                            .versionId(version)
                            .legalHold { h -> h.status("ON") }
                    }
                }
                forbidden {
                    order.putObjectRetention {
                        it
                            .bucket(bucket)
                            .key(key)
                            .versionId(version)
                            .retention { r -> r.mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(Instant.now().plusSeconds(30)) }
                    }
                }
                // バケットの管理操作とほかのバケットの操作はできない(プレフィックスに限った Write は管理操作を含まない)
                forbidden {
                    order.putObjectLockConfiguration {
                        it.bucket(bucket).objectLockConfiguration { c ->
                            c.objectLockEnabled(ObjectLockEnabled.ENABLED).rule { r ->
                                r.defaultRetention { d -> d.mode(ObjectLockRetentionMode.GOVERNANCE).days(1) }
                            }
                        }
                    }
                }
                forbidden { order.putBucketVersioning { it.bucket(bucket).versioningConfiguration { c -> c.status("Suspended") } } }
                forbidden { order.putBucketPolicy { it.bucket(bucket).policy("""{"Version":"2012-10-17","Statement":[]}""") } }
                forbidden { order.deleteBucketPolicy { it.bucket(bucket) } }
                forbidden { order.deleteBucket { it.bucket(bucket) } }
                forbidden { order.createBucket { it.bucket("eiaf-it-by-order") } }
                forbidden { order.listObjectsV2 { it.bucket(other) } }
                forbidden { order.putObject({ it.bucket(other).key("z.txt") }, RequestBody.fromString("x")) }
                forbidden { order.getObjectAsBytes { it.bucket(other).key("k.txt") } }
                forbidden { order.deleteObject { it.bucket(other).key("k.txt") } }
            }
        }

        test("サービスの書込み用の identity は、ほかのサービスのプレフィックスに書けない(svc-1 → anchors/order/・anchors/svc-2/)") {
            env.writerClient(AuditEnvironment.writer(ServiceName.parse("svc-1").getOrNull()!!)).use { svc1 ->
                listOf("anchors/order/it-probe.json", "anchors/svc-2/it-probe.json").forEach { key ->
                    forbidden { svc1.putObject({ it.bucket(AuditEnvironment.BUCKET).key(key) }, RequestBody.fromString("{}")) }
                }
            }
        }

        test("サービスが追記を続けている間に検査しても、誤って改竄の疑いにならない(1 つのスナップショットで読む)") {
            val db = env.newDatabase()
            val service = newService()
            (1..50).forEach { db.append(log, it) }
            db.publish(service)
            val running =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val appended =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val writer =
                Thread {
                    while (running.get()) db.append(log, 1_000 + appended.incrementAndGet())
                }.apply { start() }
            try {
                repeat(5) { db.verify(service).findings.shouldBeEmpty() }
            } finally {
                running.set(false)
                writer.join()
            }
            (appended.get() > 0) shouldBe true
            db.verify(service).findings.shouldBeEmpty()
        }

        test("検査専用の資格情報では、アンカーを読めるが、書けず、消せない") {
            val db = env.newDatabase()
            val service = newService()
            db.append(log, 1)
            val published = db.publish(service)
            env.verifyClient().use { verify ->
                verify.getObjectAsBytes { it.bucket(AuditEnvironment.BUCKET).key(published.key).versionId(published.versionId) }
                forbidden {
                    verify.putObject(
                        {
                            it
                                .bucket(AuditEnvironment.BUCKET)
                                .key(AnchorKeys.of(service, Instant.now()))
                                .objectLockMode(ObjectLockMode.COMPLIANCE)
                                .objectLockRetainUntilDate(Instant.now().plus(RETENTION))
                        },
                        RequestBody.fromBytes(published.anchor.toJson()),
                    )
                }
                forbidden { verify.deleteObject { it.bucket(AuditEnvironment.BUCKET).key(published.key) } }
                forbidden { verify.deleteObject { it.bucket(AuditEnvironment.BUCKET).key(published.key).versionId(published.versionId) } }
            }
            db.verify(service).findings.shouldBeEmpty()
        }

        test("バケットの既定の保持設定を GOVERNANCE に書き換えても、アプリが保存したアンカーは COMPLIANCE になる") {
            val db = env.newDatabase()
            val service = newService()
            db.append(log, 1)
            env.admin.putObjectLockConfiguration {
                it.bucket(AuditEnvironment.BUCKET).objectLockConfiguration { c ->
                    c.objectLockEnabled(ObjectLockEnabled.ENABLED).rule { r ->
                        r.defaultRetention { d -> d.mode(ObjectLockRetentionMode.GOVERNANCE).days(1) }
                    }
                }
            }
            try {
                val published = db.publish(service)
                val retention =
                    env.admin
                        .getObjectRetention {
                            it.bucket(AuditEnvironment.BUCKET).key(published.key).versionId(published.versionId)
                        }.retention()
                retention.mode() shouldBe ObjectLockRetentionMode.COMPLIANCE
                db.verify(service).findings.shouldBeEmpty()
            } finally {
                env.admin.putObjectLockConfiguration {
                    it.bucket(AuditEnvironment.BUCKET).objectLockConfiguration { c -> c.objectLockEnabled(ObjectLockEnabled.ENABLED) }
                }
            }
        }

        test("アンカーの削除マーカーを検出する(書込み用の identity では作れないため、管理者の資格情報で作る)") {
            val db = env.newDatabase()
            val service = newService()
            db.append(log, 1)
            val published = db.publish(service)
            val marker = env.admin.deleteObject { it.bucket(AuditEnvironment.BUCKET).key(published.key) }
            marker.deleteMarker() shouldBe true
            db.verify(service).findings shouldContainExactly listOf(Finding.AnchorDeleteMarker(published.key, marker.versionId()))
        }

        test("保持モードが COMPLIANCE でない版(GOVERNANCE)を検出する") {
            val db = env.newDatabase()
            val service = newService()
            db.append(log, 1)
            val published = db.publish(service)
            val governance = AnchorKeys.of(service, Instant.parse(published.anchor.createdAt))
            val version =
                env.admin
                    .putObject(
                        {
                            it
                                .bucket(AuditEnvironment.BUCKET)
                                .key(governance)
                                .objectLockMode(ObjectLockMode.GOVERNANCE)
                                .objectLockRetainUntilDate(Instant.now().plus(RETENTION))
                        },
                        RequestBody.fromBytes(published.anchor.toJson()),
                    ).versionId()
            db.verify(service).findings shouldContainExactly listOf(Finding.AnchorNotCompliance(governance, version, "GOVERNANCE"))
            // 後片付け: GOVERNANCE の版は管理者が bypass で消せる(COMPLIANCE との違い)
            env.admin.deleteObject {
                it
                    .bucket(AuditEnvironment.BUCKET)
                    .key(governance)
                    .versionId(version)
                    .bypassGovernanceRetention(true)
            }
        }
    })
