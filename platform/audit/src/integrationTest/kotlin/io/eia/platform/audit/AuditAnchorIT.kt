@file:Suppress("MagicNumber", "ForEachOnRange") // テストデータの件数・seq・範囲

package io.eia.platform.audit

import io.eia.platform.audit.anchor.AnchorKeys
import io.eia.platform.audit.anchor.AnchorPublisher
import io.eia.platform.audit.anchor.PublishedAnchor
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
        afterSpec { env.close() }
        val log = AuditLog()
        val services = AtomicInteger()
        val recorder = PutHeaderRecorder()
        val store by lazy { env.anchorStore(listOf(recorder)) }

        // 検査は、アンカーを書けない読み取り専用の資格情報で行う(make audit-verify と同じ)
        val verifyStore by lazy { env.anchorStore(identity = AuditEnvironment.VERIFY) }

        fun newService(): ServiceName = ServiceName.parse("svc-${services.incrementAndGet()}").getOrNull()!!

        fun AuditDatabase.publish(service: ServiceName): PublishedAnchor =
            app.connection.use { AnchorPublisher(service, store, RETENTION).publish(it).getOrNull().shouldNotBeNull() }

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

        test("保持期限内のアンカーは audit の資格情報でも管理者の資格情報でも削除できず、保持期限を短縮できない") {
            val db = env.newDatabase()
            val service = newService()
            db.append(log, 1)
            val published = db.publish(service)
            val shorter = Instant.now().plus(Duration.ofMinutes(1))
            env.auditClient().use { audit ->
                forbidden { audit.deleteObject { it.bucket(AuditEnvironment.BUCKET).key(published.key).versionId(published.versionId) } }
                forbidden {
                    audit.putObjectRetention {
                        it
                            .bucket(AuditEnvironment.BUCKET)
                            .key(published.key)
                            .versionId(published.versionId)
                            .retention { r -> r.mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(shorter) }
                    }
                }
            }
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

        test("audit の資格情報では、バケットの管理操作とほかのバケットの操作ができない") {
            val other = "eiaf-it-other"
            env.admin.createBucket { it.bucket(other) }
            env.admin.putObject({ it.bucket(other).key("k.txt") }, RequestBody.fromString("x"))
            env.auditClient().use { audit ->
                val bucket = AuditEnvironment.BUCKET
                forbidden {
                    audit.putObjectLockConfiguration {
                        it.bucket(bucket).objectLockConfiguration { c ->
                            c.objectLockEnabled(ObjectLockEnabled.ENABLED).rule { r ->
                                r.defaultRetention { d -> d.mode(ObjectLockRetentionMode.GOVERNANCE).days(1) }
                            }
                        }
                    }
                }
                forbidden { audit.putBucketVersioning { it.bucket(bucket).versioningConfiguration { c -> c.status("Suspended") } } }
                forbidden { audit.putBucketPolicy { it.bucket(bucket).policy("""{"Version":"2012-10-17","Statement":[]}""") } }
                forbidden { audit.deleteBucketPolicy { it.bucket(bucket) } }
                forbidden { audit.deleteBucket { it.bucket(bucket) } }
                forbidden { audit.createBucket { it.bucket("eiaf-it-by-audit") } }
                forbidden { audit.listObjectsV2 { it.bucket(other) } }
                forbidden { audit.putObject({ it.bucket(other).key("z.txt") }, RequestBody.fromString("x")) }
                forbidden { audit.getObjectAsBytes { it.bucket(other).key("k.txt") } }
                forbidden { audit.deleteObject { it.bucket(other).key("k.txt") } }
            }
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

        test("アンカーの削除マーカーを検出する(audit の資格情報では作れないため、管理者の資格情報で作る)") {
            val db = env.newDatabase()
            val service = newService()
            db.append(log, 1)
            val published = db.publish(service)
            env.auditClient().use { audit -> forbidden { audit.deleteObject { it.bucket(AuditEnvironment.BUCKET).key(published.key) } } }
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
