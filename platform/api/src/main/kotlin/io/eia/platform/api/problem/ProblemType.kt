package io.eia.platform.api.problem

/**
 * Problem Details の `type`(RFC 9457 §3.1.1)と、その種類の `title`・`status`・`detail`(ADR-0022 §2)。
 *
 * - `type` はクライアントがエラーの種類を判別する識別子。環境ごとに変える設定値にしない。変えるとクライアントにとって
 *   破壊的変更になる。この Framework を組織で採用するときは、最初に基底 URI([BASE_URI])を 1 回だけ決める。
 * - `title` と `detail` は種類ごとに決めた固定の文にする。例外のメッセージや、受け取った値は入れない(内部の情報を出さない)。
 * - 一覧は INTEGRATION_STANDARDS §6 に載せる。種類を追加するときは、一覧と契約(OpenAPI)の説明も更新する。
 *
 * [ABOUT_BLANK] は、状態コード以上の意味を持たない応答(405・413・415 など)に使う(RFC 9457 §4.2.1)。
 */
public class ProblemType private constructor(
    /** `type` の URI。 */
    public val uri: String,
    public val status: Int,
    public val title: String,
    public val detail: String?,
) {
    override fun toString(): String = uri

    @Suppress("MagicNumber") // HTTP の状態コード
    public companion object {
        /** `type` の基底 URI。RFC 2606 の予約ドメインで、実在のサイトと衝突しない。 */
        public const val BASE_URI: String = "https://eiaf.example/problems/"

        public val VALIDATION_FAILED: ProblemType =
            of("validation-failed", 422, "Validation failed", "The request contains invalid values. See errors.")
        public val BAD_REQUEST: ProblemType = of("bad-request", 400, "Bad request", "The request could not be read.")
        public val IDEMPOTENCY_KEY_MISSING: ProblemType =
            of("idempotency-key-missing", 400, "Idempotency-Key required", "This request requires a valid Idempotency-Key header.")
        public val IDEMPOTENCY_KEY_REUSED: ProblemType =
            of(
                "idempotency-key-reused",
                422,
                "Idempotency-Key reused",
                "The Idempotency-Key was already used for a different request.",
            )
        public val IDEMPOTENCY_REQUEST_IN_PROGRESS: ProblemType =
            of(
                "idempotency-request-in-progress",
                409,
                "Request in progress",
                "A request with the same Idempotency-Key is still being processed.",
            )
        public val NOT_FOUND: ProblemType = of("not-found", 404, "Not found", "The requested resource was not found.")
        public val CONFLICT: ProblemType = of("conflict", 409, "Conflict", "The request conflicts with the current state of the resource.")
        public val RATE_LIMITED: ProblemType = of("rate-limited", 429, "Too many requests", "The rate limit was exceeded.")
        public val SERVICE_UNAVAILABLE: ProblemType =
            of("service-unavailable", 503, "Service unavailable", "The service is temporarily unavailable.")
        public val INTERNAL_ERROR: ProblemType = of("internal-error", 500, "Internal error", "An unexpected error occurred.")
        public val UNAUTHORIZED: ProblemType = of("unauthorized", 401, "Unauthorized", null)
        public val FORBIDDEN: ProblemType = of("forbidden", 403, "Forbidden", null)

        /** 登録済みの種類(INTEGRATION_STANDARDS §6 の一覧と一致させる)。 */
        public val ALL: List<ProblemType> =
            listOf(
                VALIDATION_FAILED,
                BAD_REQUEST,
                IDEMPOTENCY_KEY_MISSING,
                IDEMPOTENCY_KEY_REUSED,
                IDEMPOTENCY_REQUEST_IN_PROGRESS,
                NOT_FOUND,
                CONFLICT,
                RATE_LIMITED,
                SERVICE_UNAVAILABLE,
                INTERNAL_ERROR,
                UNAUTHORIZED,
                FORBIDDEN,
            )

        /** `about:blank`。`title` は状態コードの理由句([title])にする(RFC 9457 §4.2.1)。 */
        public fun aboutBlank(
            status: Int,
            title: String,
        ): ProblemType = ProblemType("about:blank", status, title, null)

        private fun of(
            slug: String,
            status: Int,
            title: String,
            detail: String?,
        ): ProblemType = ProblemType(BASE_URI + slug, status, title, detail)
    }
}
