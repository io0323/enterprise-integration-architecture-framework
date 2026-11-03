package io.eia.bffgraphql.adapters.graphql

import graphql.ErrorClassification
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** `errors[].extensions.code`。画面はこの値で扱いを決める。 */
internal object ErrorCodes {
    const val RATE_LIMITED = "RATE_LIMITED"
    const val QUERY_LIMIT_EXCEEDED = "QUERY_LIMIT_EXCEEDED"
    const val BAD_REQUEST = "BAD_REQUEST"
    const val FORBIDDEN = "FORBIDDEN"
    const val UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE"
    const val UPSTREAM_ERROR = "UPSTREAM_ERROR"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

/** errors の種類(仕様の `classification`。応答には `extensions.code` を返す)。 */
internal object StorefrontError : ErrorClassification

/**
 * データの取得の例外を errors の 1 件にする(ADR-0043 §6)。文は決まった文だけにする(下流の情報と例外の文を応答に出さない)。
 * 例外の連鎖は `UpstreamFailure` まで辿る(DataLoader の失敗は `CompletionException` に包まれる)。
 */
internal object DataFetcherErrors : DataFetcherExceptionHandler {
    private val logger = LoggerFactory.getLogger(DataFetcherErrors::class.java)

    override fun handleException(parameters: DataFetcherExceptionHandlerParameters): CompletableFuture<DataFetcherExceptionHandlerResult> {
        val location = parameters.sourceLocation
        val path = parameters.path
        val error =
            when (val cause = unwrap(parameters.exception)) {
                is UpstreamFailure -> {
                    upstream(cause, location, path)
                }

                else -> {
                    logger.error("GraphQL の項目の取得で想定外の例外が発生しました({})", path, cause)
                    build("Internal error", location, path, mapOf("code" to ErrorCodes.INTERNAL_ERROR))
                }
            }
        return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult(error).build())
    }

    private fun upstream(
        failure: UpstreamFailure,
        location: SourceLocation?,
        path: ResultPath,
    ): GraphQLError {
        val code = if (failure.retryable) ErrorCodes.UPSTREAM_UNAVAILABLE else ErrorCodes.UPSTREAM_ERROR
        return build(
            "The field could not be read",
            location,
            path,
            mapOf("code" to code, "service" to failure.service, "retryable" to failure.retryable),
        )
    }

    private fun build(
        message: String,
        location: SourceLocation?,
        path: ResultPath,
        extensions: Map<String, Any>,
    ): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .message(message)
            .apply { location?.let(::location) }
            .path(path)
            .errorType(StorefrontError)
            .extensions(extensions)
            .build()

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (current is CompletionException && current.cause != null) {
            current = checkNotNull(current.cause)
        }
        return current
    }
}
