package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditStorageRejected
import io.eia.platform.audit.AuditStorageUnavailable
import io.eia.platform.security.secret.SecretName
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRetentionRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * S3 互換ストレージのアンカーの保存先(ADR-0015・ADR-0017)。
 *
 * - addressing style は設定で切り替える。ローカル(SeaweedFS)は path-style(ADR-0015 §2)。
 * - SDK の既定のチェックサム(CRC 系を常に付ける)は使わず、必要なときだけにする(WHEN_REQUIRED)。
 *   Object Lock つきの put には、Content-MD5 と SHA-256 のチェックサムを自分で計算して明示的に付ける(S3 の Object Lock は put にチェックサムを求める)。
 * - 資格情報は呼び出しのたびに [SecretProvider] から取る(ローテーションに追従する)。
 */
public class S3AnchorStore internal constructor(
    private val config: S3AnchorStoreConfig,
    private val client: S3Client,
) : AnchorStore,
    AutoCloseable {
    public constructor(config: S3AnchorStoreConfig, secrets: SecretProvider) : this(config, buildClient(config, secrets, emptyList()))

    override fun put(
        key: String,
        body: ByteArray,
        retainUntil: Instant,
    ): Result<String, AuditError> =
        s3Catching {
            val request =
                PutObjectRequest
                    .builder()
                    .bucket(config.bucket)
                    .key(key)
                    .contentType("application/json")
                    .contentMD5(base64Digest("MD5", body))
                    .checksumSHA256(base64Digest("SHA-256", body))
                    .objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(retainUntil)
                    .build()
            val versionId = client.putObject(request, RequestBody.fromBytes(body)).versionId()
            if (versionId.isNullOrEmpty()) {
                err(AuditStorageRejected(STORAGE, "版の ID が返りません(バケットのバージョニングと Object Lock を確かめてください)"))
            } else {
                ok(versionId)
            }
        }

    override fun listVersions(prefix: String): Result<List<AnchorVersion>, AuditError> =
        s3Catching {
            val versions = mutableListOf<AnchorVersion>()
            var keyMarker: String? = null
            var versionIdMarker: String? = null
            do {
                val request =
                    ListObjectVersionsRequest
                        .builder()
                        .bucket(config.bucket)
                        .prefix(prefix)
                        .keyMarker(keyMarker)
                        .versionIdMarker(versionIdMarker)
                        .build()
                val page = client.listObjectVersions(request)
                page.deleteMarkers().forEach { marker ->
                    versions += AnchorVersion(marker.key(), marker.versionId(), marker.lastModified(), true, null, null, null)
                }
                page.versions().forEach { version -> versions += readVersion(version.key(), version.versionId(), version.lastModified()) }
                keyMarker = page.nextKeyMarker()
                versionIdMarker = page.nextVersionIdMarker()
                // 続きがあると言いながらマーカーを返さない互換ストレージで、同じページを取り続けないようにする
                val hasNext = page.isTruncated == true && keyMarker != null
            } while (hasNext)
            ok(versions.sortedWith(compareBy(AnchorVersion::key, AnchorVersion::lastModified, AnchorVersion::versionId)))
        }

    @Suppress("ReturnCount") // 本文を取得できない・読めないときは理由つきの版を返す
    private fun readVersion(
        key: String,
        versionId: String,
        lastModified: Instant,
    ): AnchorVersion {
        val body =
            try {
                client
                    .getObject(
                        GetObjectRequest
                            .builder()
                            .bucket(config.bucket)
                            .key(key)
                            .versionId(versionId)
                            .build(),
                    ).use { stream ->
                        stream.readNBytes(MAX_ANCHOR_BYTES + 1)
                    }
            } catch (e: S3Exception) {
                return AnchorVersion(key, versionId, lastModified, false, null, null, null, "取得できません(${errorCode(e)})")
            } catch (e: IOException) {
                return AnchorVersion(key, versionId, lastModified, false, null, null, null, "読み取れません(${e::class.simpleName})")
            }
        val retention =
            try {
                client
                    .getObjectRetention(
                        GetObjectRetentionRequest
                            .builder()
                            .bucket(config.bucket)
                            .key(key)
                            .versionId(versionId)
                            .build(),
                    ).retention()
            } catch (e: S3Exception) {
                if (e.statusCode() == NOT_FOUND || errorCode(e) == NO_LOCK_CONFIGURATION) null else throw e
            }
        return AnchorVersion(
            key = key,
            versionId = versionId,
            lastModified = lastModified,
            isDeleteMarker = false,
            body = body,
            retentionMode = retention?.modeAsString(),
            retainUntil = retention?.retainUntilDate(),
        )
    }

    override fun close() {
        client.close()
    }

    internal companion object {
        private const val STORAGE = "S3"
        private const val NOT_FOUND = 404
        private const val FORBIDDEN = 403
        private const val SERVER_ERROR = 500
        private const val TOO_MANY_REQUESTS = 429
        private const val NO_LOCK_CONFIGURATION = "NoSuchObjectLockConfiguration"
        const val MAX_ANCHOR_BYTES = 4 * 1024

        fun buildClient(
            config: S3AnchorStoreConfig,
            secrets: SecretProvider,
            interceptors: List<ExecutionInterceptor>,
        ): S3Client =
            S3Client
                .builder()
                .endpointOverride(config.endpoint)
                .region(Region.of(config.region))
                .forcePathStyle(config.pathStyle)
                .credentialsProvider(SecretCredentialsProvider(secrets, config.accessKeyName, config.secretKeyName))
                .httpClient(
                    UrlConnectionHttpClient
                        .builder()
                        .connectionTimeout(config.connectTimeout)
                        .socketTimeout(config.apiCallTimeout)
                        .build(),
                ).requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration { builder ->
                    builder.apiCallTimeout(config.apiCallTimeout)
                    interceptors.forEach(builder::addExecutionInterceptor)
                }.build()

        private fun base64Digest(
            algorithm: String,
            body: ByteArray,
        ): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(body))

        private fun errorCode(e: S3Exception): String = e.awsErrorDetails()?.errorCode() ?: "HTTP ${e.statusCode()}"

        /** 例外を [AuditError] に変換する。理由にはエラーコードだけを入れる(資格情報や応答の本文を入れない)。 */
        private inline fun <T> s3Catching(block: () -> Result<T, AuditError>): Result<T, AuditError> =
            try {
                block()
            } catch (e: S3Exception) {
                val status = e.statusCode()
                if (status >= SERVER_ERROR || status == TOO_MANY_REQUESTS) {
                    err(AuditStorageUnavailable(STORAGE, errorCode(e)))
                } else {
                    err(AuditStorageRejected(STORAGE, if (status == FORBIDDEN) "AccessDenied" else errorCode(e)))
                }
            } catch (e: SdkClientException) {
                err(AuditStorageUnavailable(STORAGE, e::class.simpleName ?: "SdkClientException"))
            } catch (e: SdkException) {
                err(AuditStorageUnavailable(STORAGE, e::class.simpleName ?: "SdkException"))
            }
    }
}

/**
 * @property endpoint S3 のエンドポイント(ローカルは `http://localhost:19333`)
 * @property pathStyle true なら `{endpoint}/{bucket}/{key}`(ADR-0015 §2)
 * @property accessKeyName / secretKeyName [SecretProvider] で取得する名前(ローカルは `AUDIT_S3_ACCESS_KEY` / `AUDIT_S3_SECRET_KEY`)
 */
public data class S3AnchorStoreConfig(
    val endpoint: URI,
    val bucket: String,
    val accessKeyName: SecretName = SecretName("AUDIT_S3_ACCESS_KEY"),
    val secretKeyName: SecretName = SecretName("AUDIT_S3_SECRET_KEY"),
    val region: String = "us-east-1",
    val pathStyle: Boolean = true,
    val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    val apiCallTimeout: Duration = DEFAULT_API_CALL_TIMEOUT,
) {
    public companion object {
        public val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        public val DEFAULT_API_CALL_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

/** 呼び出しのたびに [SecretProvider] から資格情報を取る。取れなければ SDK の例外にする(値は例外に入れない)。 */
internal class SecretCredentialsProvider(
    private val secrets: SecretProvider,
    private val accessKeyName: SecretName,
    private val secretKeyName: SecretName,
) : AwsCredentialsProvider {
    override fun resolveCredentials(): AwsBasicCredentials {
        val access = secrets.get(accessKeyName)
        val secret = secrets.get(secretKeyName)
        if (access !is Result.Ok) throw SdkClientException.create("S3 の資格情報を取得できません: ${(access as Result.Err).error.message}")
        if (secret !is Result.Ok) throw SdkClientException.create("S3 の資格情報を取得できません: ${(secret as Result.Err).error.message}")
        return AwsBasicCredentials.create(access.value.reveal(), secret.value.reveal())
    }
}
