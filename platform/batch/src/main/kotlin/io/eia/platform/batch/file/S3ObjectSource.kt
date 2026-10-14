package io.eia.platform.batch.file

import io.eia.platform.batch.BatchError
import io.eia.platform.batch.BatchSourceRejected
import io.eia.platform.batch.BatchSourceUnavailable
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
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.net.URI
import java.time.Duration

/**
 * S3 互換ストレージ(SeaweedFS。ADR-0015)の置き場。ファイルの到着の監視に使う(ADR-0030 §4)。
 *
 * 読むだけなので、読み取りの権限だけを持つ identity の資格情報を渡す。資格情報は呼び出しのたびに [SecretProvider] から取る。
 * P09 のファイル連携(`platform/file-transfer`)ができたら、S3 の接続はそちらに寄せる。
 */
public class S3ObjectSource(
    private val config: S3ObjectSourceConfig,
    secrets: SecretProvider,
) : ObjectSource,
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

    override fun list(prefix: String): Result<List<StoredObject>, BatchError> =
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
    ): Result<ByteArray?, BatchError> =
        s3Catching {
            try {
                client
                    .getObject(
                        GetObjectRequest
                            .builder()
                            .bucket(config.bucket)
                            .key(key)
                            .build(),
                    ).use { ok(it.readNBytes(maxBytes + 1)) }
            } catch (_: NoSuchKeyException) {
                ok(null)
            } catch (e: IOException) {
                err(BatchSourceUnavailable(e::class.simpleName ?: "IOException"))
            }
        }

    override fun close() {
        client.close()
    }

    private companion object {
        const val FORBIDDEN = 403
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR = 500

        /** 例外を [BatchError] に変換する。理由にはエラーコードだけを入れる(資格情報や応答の本文を入れない)。 */
        inline fun <T> s3Catching(block: () -> Result<T, BatchError>): Result<T, BatchError> =
            try {
                block()
            } catch (e: S3Exception) {
                val status = e.statusCode()
                val code = e.awsErrorDetails()?.errorCode() ?: "HTTP $status"
                when {
                    status >= SERVER_ERROR || status == TOO_MANY_REQUESTS -> err(BatchSourceUnavailable(code))
                    status == FORBIDDEN -> err(BatchSourceRejected("AccessDenied"))
                    else -> err(BatchSourceRejected(code))
                }
            } catch (e: SdkClientException) {
                err(BatchSourceUnavailable(e::class.simpleName ?: "SdkClientException"))
            } catch (e: SdkException) {
                err(BatchSourceUnavailable(e::class.simpleName ?: "SdkException"))
            }
    }
}

/**
 * @property endpoint S3 のエンドポイント(ローカルは `http://localhost:19333`)
 * @property pathStyle true なら `{endpoint}/{bucket}/{key}`(ADR-0015 §2)
 * @property accessKeyName / secretKeyName [SecretProvider] で取得する名前。読み取り専用の identity の名前を渡す
 */
public data class S3ObjectSourceConfig(
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
