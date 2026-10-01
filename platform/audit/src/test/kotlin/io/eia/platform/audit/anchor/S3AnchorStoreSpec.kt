package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditStorageRejected
import io.eia.platform.audit.AuditStorageUnavailable
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRetentionRequest
import software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
import software.amazon.awssdk.services.s3.model.ObjectVersion
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

private const val BUCKET = "eiaf-audit"
private val CONFIG =
    S3AnchorStoreConfig(
        endpoint = URI("http://localhost:19333"),
        bucket = BUCKET,
        accessKeyName = SecretName("ORDER_AUDIT_S3_ACCESS_KEY"),
        secretKeyName = SecretName("ORDER_AUDIT_S3_SECRET_KEY"),
    )
private val MODIFIED: Instant = Instant.parse("2026-09-28T00:00:00Z")
private val UNTIL: Instant = Instant.parse("2026-09-29T00:00:00Z")

private fun s3Error(
    status: Int,
    code: String,
): S3Exception =
    S3Exception
        .builder()
        .statusCode(status)
        .awsErrorDetails(
            AwsErrorDetails
                .builder()
                .errorCode(code)
                .errorMessage("secret-detail")
                .build(),
        ).build() as S3Exception

private fun body(bytes: ByteArray): ResponseInputStream<GetObjectResponse> =
    ResponseInputStream(GetObjectResponse.builder().build(), AbortableInputStream.create(ByteArrayInputStream(bytes)))

class S3AnchorStoreSpec :
    FunSpec({
        test("COMPLIANCE・保持期限・Content-MD5・SHA-256 のチェックサムを付けて put する") {
            val client = mockk<S3Client>()
            val request = slot<PutObjectRequest>()
            every { client.putObject(capture(request), any<RequestBody>()) } returns PutObjectResponse.builder().versionId("v1").build()
            val payload = """{"seq":1}""".toByteArray()

            S3AnchorStore(CONFIG, client).put("anchors/order/2026-09-28.json", payload, UNTIL).getOrNull() shouldBe "v1"

            with(request.captured) {
                bucket() shouldBe BUCKET
                key() shouldBe "anchors/order/2026-09-28.json"
                objectLockMode() shouldBe ObjectLockMode.COMPLIANCE
                objectLockRetainUntilDate() shouldBe UNTIL
                contentMD5() shouldBe Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(payload))
                checksumSHA256() shouldBe Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload))
            }
        }

        test("版の ID が返らなければ(バージョニングが無効)拒否として扱う") {
            val client = mockk<S3Client>()
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()
            S3AnchorStore(CONFIG, client).put("k", byteArrayOf(1), UNTIL).shouldBeInstanceOf<Result.Err<AuditStorageRejected>>()
        }

        test("S3 の失敗を分類し、理由にはエラーコードだけを入れる") {
            val client = mockk<S3Client>()
            val store = S3AnchorStore(CONFIG, client)
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws s3Error(403, "AccessDenied")
            val denied = (store.put("k", byteArrayOf(1), UNTIL) as Result.Err).error.shouldBeInstanceOf<AuditStorageRejected>()
            denied.reason shouldBe "AccessDenied"
            denied.message shouldNotContain "secret-detail"
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws s3Error(503, "SlowDown")
            store.put("k", byteArrayOf(1), UNTIL).shouldBeInstanceOf<Result.Err<AuditStorageUnavailable>>()
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws s3Error(400, "InvalidRequest")
            (store.put("k", byteArrayOf(1), UNTIL) as Result.Err).error.shouldBeInstanceOf<AuditStorageRejected>().reason shouldBe
                "InvalidRequest"
            every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws SdkClientException.create("connect")
            store.put("k", byteArrayOf(1), UNTIL).shouldBeInstanceOf<Result.Err<AuditStorageUnavailable>>()
        }

        test("全版(削除マーカーを含む)を、ページをまたいで、内容と保持の設定とともに返す") {
            val client = mockk<S3Client>()
            val requests = mutableListOf<ListObjectVersionsRequest>()
            every { client.listObjectVersions(capture(requests)) } answers {
                if (requests.last().keyMarker() == null) {
                    ListObjectVersionsResponse
                        .builder()
                        .isTruncated(true)
                        .nextKeyMarker("anchors/order/a.json")
                        .nextVersionIdMarker("v1")
                        .versions(
                            ObjectVersion
                                .builder()
                                .key("anchors/order/a.json")
                                .versionId("v1")
                                .lastModified(MODIFIED)
                                .build(),
                        ).build()
                } else {
                    ListObjectVersionsResponse
                        .builder()
                        .isTruncated(false)
                        .versions(
                            ObjectVersion
                                .builder()
                                .key("anchors/order/b.json")
                                .versionId("v2")
                                .lastModified(MODIFIED)
                                .build(),
                        ).deleteMarkers(
                            DeleteMarkerEntry
                                .builder()
                                .key("anchors/order/a.json")
                                .versionId("m1")
                                .lastModified(MODIFIED)
                                .build(),
                        ).build()
                }
            }
            every { client.getObject(any<GetObjectRequest>()) } answers { body("x".toByteArray()) }
            every { client.getObjectRetention(match<GetObjectRetentionRequest> { it.versionId() == "v1" }) } returns
                GetObjectRetentionResponse
                    .builder()
                    .retention {
                        it
                            .mode(
                                ObjectLockRetentionMode.COMPLIANCE,
                            ).retainUntilDate(UNTIL)
                    }.build()
            every { client.getObjectRetention(match<GetObjectRetentionRequest> { it.versionId() == "v2" }) } throws
                s3Error(404, "NoSuchObjectLockConfiguration")

            val versions = S3AnchorStore(CONFIG, client).listVersions("anchors/order/").getOrNull()!!

            versions shouldHaveSize 3
            requests[1].keyMarker() shouldBe "anchors/order/a.json"
            requests[1].versionIdMarker() shouldBe "v1"
            val v1 = versions.single { it.versionId == "v1" }
            v1.retentionMode shouldBe "COMPLIANCE"
            v1.retainUntil shouldBe UNTIL
            v1.body!!.decodeToString() shouldBe "x"
            versions.single { it.versionId == "v2" }.retentionMode shouldBe null
            versions.single { it.versionId == "m1" }.isDeleteMarker shouldBe true
        }

        test("latest は、削除マーカーを除いて最も新しい版(最大のキーの、最も新しい保存)の内容だけを読む") {
            val client = mockk<S3Client>()

            fun version(
                key: String,
                id: String,
                modified: Instant,
            ) = ObjectVersion
                .builder()
                .key(key)
                .versionId(id)
                .lastModified(modified)
                .build()
            every { client.listObjectVersions(any<ListObjectVersionsRequest>()) } returns
                ListObjectVersionsResponse
                    .builder()
                    .isTruncated(false)
                    .versions(
                        version("anchors/order/2026-10-01.json", "old", MODIFIED.plusSeconds(600)),
                        version("anchors/order/2026-10-02.json", "v1", MODIFIED),
                        version("anchors/order/2026-10-02.json", "v2", MODIFIED.plusSeconds(60)),
                    ).deleteMarkers(
                        DeleteMarkerEntry
                            .builder()
                            .key("anchors/order/2026-10-02.json")
                            .versionId("m1")
                            .lastModified(MODIFIED.plusSeconds(120))
                            .build(),
                    ).build()
            val reads = mutableListOf<GetObjectRequest>()
            every { client.getObject(capture(reads)) } answers { body("x".toByteArray()) }
            every { client.getObjectRetention(any<GetObjectRetentionRequest>()) } returns GetObjectRetentionResponse.builder().build()

            val latest = S3AnchorStore(CONFIG, client).latest("anchors/order/").getOrNull().shouldNotBeNull()

            latest.versionId shouldBe "v2"
            reads.map { it.versionId() } shouldBe listOf("v2")
        }

        test("latest は、版がなければ null") {
            val client = mockk<S3Client>()
            every { client.listObjectVersions(any<ListObjectVersionsRequest>()) } returns
                ListObjectVersionsResponse.builder().isTruncated(false).build()
            S3AnchorStore(CONFIG, client).latest("anchors/order/").getOrNull() shouldBe null
        }

        test("読めない版は理由つきで返し、一覧の取得は続ける") {
            val client = mockk<S3Client>()
            every { client.listObjectVersions(any<ListObjectVersionsRequest>()) } returns
                ListObjectVersionsResponse
                    .builder()
                    .isTruncated(false)
                    .versions(
                        ObjectVersion
                            .builder()
                            .key("anchors/order/a.json")
                            .versionId("v1")
                            .lastModified(MODIFIED)
                            .build(),
                    ).build()
            every { client.getObject(any<GetObjectRequest>()) } throws s3Error(403, "AccessDenied")
            every { client.getObjectRetention(any<GetObjectRetentionRequest>()) } returns GetObjectRetentionResponse.builder().build()
            val version = S3AnchorStore(CONFIG, client).listVersions("anchors/order/").getOrNull()!!.single()
            version.body shouldBe null
            version.readError shouldBe "取得できません(AccessDenied)"
        }

        test("続きがあると言いながらマーカーを返さないストレージでも、同じページを取り続けない") {
            val client = mockk<S3Client>()
            every { client.listObjectVersions(any<ListObjectVersionsRequest>()) } returns
                ListObjectVersionsResponse.builder().isTruncated(true).build()
            S3AnchorStore(CONFIG, client).listVersions("anchors/order/").getOrNull() shouldBe emptyList()
            verify(exactly = 1) { client.listObjectVersions(any<ListObjectVersionsRequest>()) }
        }

        test("本文の読み取りの I/O エラーは、その版の理由にする") {
            val client = mockk<S3Client>()
            every { client.listObjectVersions(any<ListObjectVersionsRequest>()) } returns
                ListObjectVersionsResponse
                    .builder()
                    .isTruncated(false)
                    .versions(
                        ObjectVersion
                            .builder()
                            .key("anchors/order/a.json")
                            .versionId("v1")
                            .lastModified(MODIFIED)
                            .build(),
                    ).build()
            val broken =
                object : InputStream() {
                    override fun read(): Int = throw IOException("reset")
                }
            every { client.getObject(any<GetObjectRequest>()) } returns
                ResponseInputStream(GetObjectResponse.builder().build(), AbortableInputStream.create(broken))
            every { client.getObjectRetention(any<GetObjectRetentionRequest>()) } returns GetObjectRetentionResponse.builder().build()
            S3AnchorStore(CONFIG, client)
                .listVersions("anchors/order/")
                .getOrNull()!!
                .single()
                .readError shouldBe "読み取れません(IOException)"
        }

        test("保持の設定の取得が 404 以外で失敗したら、一覧の取得を失敗にする") {
            val client = mockk<S3Client>()
            every { client.listObjectVersions(any<ListObjectVersionsRequest>()) } returns
                ListObjectVersionsResponse
                    .builder()
                    .isTruncated(false)
                    .versions(
                        ObjectVersion
                            .builder()
                            .key("anchors/order/a.json")
                            .versionId("v1")
                            .lastModified(MODIFIED)
                            .build(),
                    ).build()
            every { client.getObject(any<GetObjectRequest>()) } answers { body("x".toByteArray()) }
            every { client.getObjectRetention(any<GetObjectRetentionRequest>()) } throws s3Error(403, "AccessDenied")
            S3AnchorStore(CONFIG, client).listVersions("anchors/order/").shouldBeInstanceOf<Result.Err<AuditStorageRejected>>()
        }

        test("資格情報は SecretProvider から取り、取れなければ値を含まない例外にする") {
            val provider =
                SecretCredentialsProvider(
                    EnvSecretProvider(mapOf("ORDER_AUDIT_S3_ACCESS_KEY" to "ak", "ORDER_AUDIT_S3_SECRET_KEY" to "sk")),
                    SecretName("ORDER_AUDIT_S3_ACCESS_KEY"),
                    SecretName("ORDER_AUDIT_S3_SECRET_KEY"),
                )
            provider.resolveCredentials().accessKeyId() shouldBe "ak"
            provider.resolveCredentials().secretAccessKey() shouldBe "sk"
            val missing =
                SecretCredentialsProvider(
                    EnvSecretProvider(mapOf("ORDER_AUDIT_S3_ACCESS_KEY" to "ak")),
                    SecretName("ORDER_AUDIT_S3_ACCESS_KEY"),
                    SecretName("ORDER_AUDIT_S3_SECRET_KEY"),
                )
            shouldThrow<SdkClientException> { missing.resolveCredentials() }.message shouldNotContain "ak"
        }

        test("クライアントを組み立てて閉じられる(path-style・WHEN_REQUIRED)") {
            val client = S3AnchorStore.buildClient(CONFIG, EnvSecretProvider(emptyMap()), emptyList())
            client.serviceClientConfiguration().endpointOverride().get() shouldBe URI("http://localhost:19333")
            S3AnchorStore(CONFIG, client).close()
            val closable = mockk<S3Client>(relaxed = true)
            S3AnchorStore(CONFIG, closable).close()
            verify { closable.close() }
        }
    })
