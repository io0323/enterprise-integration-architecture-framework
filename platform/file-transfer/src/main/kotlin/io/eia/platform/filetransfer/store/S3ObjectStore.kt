package io.eia.platform.filetransfer.store

import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.StoreRejected
import io.eia.platform.filetransfer.StoreUnavailable
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
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * S3 互換ストレージ(SeaweedFS。ADR-0015)の置き場(ADR-0032 §1)。ファイルの到着の監視(platform/batch。ADR-0030 §4)と、
 * ファイルの書き出し・検査・移動に使う。
 *
 * 資格情報は呼び出しのたびに [SecretProvider] から取る。用途ごとに権限を絞った identity の名前を渡す(監視は読み取りだけ、など)。
 */
@Suppress("TooManyFunctions") // 置き場の操作(ObjectStore・LockedObjectWriter・VersionedObjectReader)ごとに 1 つの関数にする
public class S3ObjectStore(
    private val config: S3StoreConfig,
    secrets: SecretProvider,
) : ObjectStore,
    LockedObjectWriter,
    VersionedObjectReader,
    AutoCloseable {
    private val client: S3Client =
        S3Client
            .builder()
            .endpointOverride(config.endpoint)
            .region(Region.of(config.region))
            .forcePathStyle(config.pathStyle)
            .credentialsProvider(SecretCredentials(secrets, config.accessKeyName, config.secretKeyName))
            .httpClient(
                UrlConnectionHttpClient
                    .builder()
                    .connectionTimeout(config.connectTimeout)
                    .socketTimeout(config.apiCallTimeout)
                    .build(),
            ).requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .overrideConfiguration { it.apiCallTimeout(config.apiCallTimeout) }
            .build()

    override fun list(prefix: String): Result<List<StoredObject>, FileTransferError> =
        s3Catching {
            val objects = mutableListOf<StoredObject>()
            var token: String? = null
            do {
                val page =
                    client.listObjectsV2(
                        ListObjectsV2Request
                            .builder()
                            .bucket(config.bucket)
                            .prefix(prefix)
                            .continuationToken(token)
                            .build(),
                    )
                page.contents().forEach { objects += StoredObject(it.key(), it.size()) }
                token = page.nextContinuationToken()
                // 続きがあると言いながらトークンを返さない互換ストレージで、同じページを取り続けないようにする
                val hasNext = page.isTruncated == true && token != null
            } while (hasNext)
            ok(objects.sortedBy { it.key })
        }

    override fun read(
        key: String,
        maxBytes: Int,
    ): Result<ByteArray?, FileTransferError> =
        s3Catching {
            try {
                client.getObject(get(key)).use { ok(it.readNBytes(maxBytes + 1)) }
            } catch (_: NoSuchKeyException) {
                ok(null)
            }
        }

    override fun readVersion(
        key: String,
        versionId: String,
        maxBytes: Int,
    ): Result<ByteArray?, FileTransferError> =
        s3Catching {
            val request =
                GetObjectRequest
                    .builder()
                    .bucket(config.bucket)
                    .key(key)
                    .versionId(versionId)
                    .build()
            try {
                client.getObject(request).use { ok(it.readNBytes(maxBytes + 1)) }
            } catch (_: NoSuchKeyException) {
                ok(null)
            }
        }

    override fun head(key: String): Result<StoredObject?, FileTransferError> =
        s3Catching {
            try {
                val head =
                    client.headObject(
                        HeadObjectRequest
                            .builder()
                            .bucket(config.bucket)
                            .key(key)
                            .build(),
                    )
                ok(StoredObject(key, head.contentLength()))
            } catch (_: NoSuchKeyException) {
                ok(null)
            } catch (e: S3Exception) {
                // HEAD の 404 は本文がないので、互換ストレージによっては NoSuchKeyException にならない
                if (e.statusCode() == NOT_FOUND) ok(null) else throw e
            }
        }

    override fun download(
        key: String,
        target: Path,
    ): Result<Boolean, FileTransferError> =
        s3Catching {
            try {
                client.getObject(get(key)).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                ok(true)
            } catch (_: NoSuchKeyException) {
                ok(false)
            }
        }

    override fun upload(
        key: String,
        source: Path,
    ): Result<Unit, FileTransferError> =
        s3Catching {
            client.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(config.bucket)
                    .key(key)
                    .build(),
                RequestBody.fromFile(source),
            )
            ok(Unit)
        }

    override fun put(
        key: String,
        bytes: ByteArray,
    ): Result<Unit, FileTransferError> =
        s3Catching {
            client.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(config.bucket)
                    .key(key)
                    .build(),
                RequestBody.fromBytes(bytes),
            )
            ok(Unit)
        }

    /**
     * Object Lock(COMPLIANCE)で置く(ADR-0040 §5)。保持のモードと期限は put のたびに明示し、バケットの既定の保持設定には頼らない(ADR-0017 §5 と同じ)。
     * SDK の既定のチェックサムの計算は使わないので、Object Lock つきの put に要るチェックサム(Content-MD5 と SHA-256)を自分で付ける。
     */
    override fun putLocked(
        key: String,
        bytes: ByteArray,
        retainUntil: Instant,
    ): Result<String, FileTransferError> =
        s3Catching {
            val request =
                PutObjectRequest
                    .builder()
                    .bucket(config.bucket)
                    .key(key)
                    .contentMD5(base64Digest("MD5", bytes))
                    .checksumSHA256(base64Digest("SHA-256", bytes))
                    .objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(retainUntil)
                    .build()
            val versionId = client.putObject(request, RequestBody.fromBytes(bytes)).versionId()
            if (versionId.isNullOrEmpty()) err(StoreRejected("NoVersionId")) else ok(versionId)
        }

    override fun copy(
        sourceKey: String,
        targetKey: String,
    ): Result<Boolean, FileTransferError> =
        s3Catching {
            try {
                client.copyObject(
                    CopyObjectRequest
                        .builder()
                        .sourceBucket(config.bucket)
                        .sourceKey(sourceKey)
                        .destinationBucket(config.bucket)
                        .destinationKey(targetKey)
                        .build(),
                )
                ok(true)
            } catch (_: NoSuchKeyException) {
                ok(false)
            }
        }

    override fun delete(key: String): Result<Unit, FileTransferError> =
        s3Catching {
            client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(config.bucket)
                    .key(key)
                    .build(),
            )
            ok(Unit)
        }

    override fun close() {
        client.close()
    }

    private fun get(key: String): GetObjectRequest =
        GetObjectRequest
            .builder()
            .bucket(config.bucket)
            .key(key)
            .build()

    private companion object {
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR = 500

        /** 例外を [FileTransferError] に変換する。理由にはエラーコードだけを入れる(資格情報や応答の本文を入れない)。 */
        inline fun <T> s3Catching(block: () -> Result<T, FileTransferError>): Result<T, FileTransferError> =
            try {
                block()
            } catch (e: S3Exception) {
                val status = e.statusCode()
                val code = e.awsErrorDetails()?.errorCode() ?: "HTTP $status"
                when {
                    status >= SERVER_ERROR || status == TOO_MANY_REQUESTS -> err(StoreUnavailable(code))
                    status == FORBIDDEN -> err(StoreRejected("AccessDenied"))
                    else -> err(StoreRejected(code))
                }
            } catch (e: SdkClientException) {
                err(StoreUnavailable(e::class.simpleName ?: "SdkClientException"))
            } catch (e: SdkException) {
                err(StoreUnavailable(e::class.simpleName ?: "SdkException"))
            } catch (e: IOException) {
                err(StoreUnavailable(e::class.simpleName ?: "IOException"))
            }
    }
}

/**
 * @property endpoint S3 のエンドポイント(ローカルは `http://localhost:19333`)
 * @property pathStyle true なら `{endpoint}/{bucket}/{key}`(ADR-0015 §2)
 * @property accessKeyName / secretKeyName [SecretProvider] で取得する名前。用途ごとに権限を絞った identity の名前を渡す
 */
public data class S3StoreConfig(
    val endpoint: URI,
    val bucket: String,
    val accessKeyName: SecretName,
    val secretKeyName: SecretName,
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
private class SecretCredentials(
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

/** Object Lock つきの put に付けるチェックサム(Content-MD5・x-amz-checksum-sha256。base64)。 */
private fun base64Digest(
    algorithm: String,
    bytes: ByteArray,
): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(bytes))
