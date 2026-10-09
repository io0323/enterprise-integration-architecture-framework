package io.eia.payment.domain

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** 識別子(Saga ID・注文 ID・顧客 ID・承認 ID)の長さの上限(DB の列と同じ)。 */
public const val MAX_IDENTIFIER_LENGTH: Int = 64

/**
 * 金額(最小通貨単位と ISO 4217 の通貨コード。ADR-0012)。
 */
public data class Amount(
    public val minorUnits: Long,
    public val currency: String,
) {
    public companion object {
        private val CURRENCY = Regex("^[A-Z]{3}$")

        public fun of(
            minorUnits: Long,
            currency: String,
        ): Result<Amount, ValidationError> {
            val violations =
                buildList {
                    if (minorUnits < 0) add(FieldViolation("amount.minorUnits", "0 以上にしてください"))
                    if (!CURRENCY.matches(currency)) add(FieldViolation("amount.currency", "ISO 4217 の 3 文字にしてください"))
                }
            return if (violations.isEmpty()) ok(Amount(minorUnits, currency)) else err(ValidationError(violations))
        }
    }
}

/** 承認の記録の状態(Saga ID ごとに 1 行。ADR-0029 §5)。 */
public enum class AuthorizationStatus {
    /** 承認した(与信を確保している)。 */
    AUTHORIZED,

    /** 承認しなかった(何も確保していない)。 */
    DECLINED,

    /** 承認した後に取り消した(補償)。 */
    VOIDED,

    /** 「取消済み」の印: 承認より先に取消の指示が届いた。後から届いた承認の指示を拒否する(ADR-0029 §5)。 */
    VOIDED_BEFORE_AUTHORIZATION,
    ;

    /** 終わった状態(保持期間を過ぎたら消してよい)。有効な承認([AUTHORIZED])は業務の記録として残す。 */
    public val isSettled: Boolean get() = this != AUTHORIZED
}

/** 承認しなかった理由(契約の `PaymentDeclineReason`)。 */
public enum class DeclineReason {
    LIMIT_EXCEEDED,
    ALREADY_VOIDED,
}

/** 取消の結果(契約の `PaymentVoidOutcome`)。 */
public enum class VoidOutcome {
    VOIDED,
    NOT_AUTHORIZED,
}

/**
 * 承認の記録(Saga ID が業務キー)。
 *
 * @property authorizationId [AuthorizationStatus.AUTHORIZED] と [AuthorizationStatus.VOIDED] のときの承認 ID
 * @property decline [AuthorizationStatus.DECLINED] のときの理由
 */
public data class Authorization(
    public val sagaId: String,
    public val orderId: String,
    public val status: AuthorizationStatus,
    public val authorizationId: String? = null,
    public val decline: DeclineReason? = null,
    public val amount: Amount? = null,
)

/** 承認の指示への返事(契約の `payment.payment.authorized.v1` / `declined.v1`)。 */
public sealed interface AuthorizeReply {
    public data class Authorized(
        public val authorizationId: String,
    ) : AuthorizeReply

    public data class Declined(
        public val reason: DeclineReason,
    ) : AuthorizeReply
}

/** [PaymentRules.authorize] の判定。 */
public sealed interface AuthorizeDecision {
    public val reply: AuthorizeReply

    /** 新しい記録 [authorization] を作る(承認、または拒否)。 */
    public data class Record(
        public val authorization: Authorization,
        override val reply: AuthorizeReply,
    ) : AuthorizeDecision

    /** 同じ Saga の記録がある: 何も変えず、記録から決まる返事を返し直す。 */
    public data class Replay(
        override val reply: AuthorizeReply,
    ) : AuthorizeDecision
}

/** [PaymentRules.void] の判定。 */
public sealed interface VoidDecision {
    public val outcome: VoidOutcome

    /** 承認を取り消す。 */
    public data object Void : VoidDecision {
        override val outcome: VoidOutcome get() = VoidOutcome.VOIDED
    }

    /** 記録がない: 「取消済み」の印 [marker] を作る(後から届いた承認の指示を拒否するため。ADR-0029 §5)。 */
    public data class MarkVoidedBeforeAuthorization(
        public val marker: Authorization,
    ) : VoidDecision {
        override val outcome: VoidOutcome get() = VoidOutcome.NOT_AUTHORIZED
    }

    /** 何も変えず、記録から決まる結果を返し直す。 */
    public data class Replay(
        override val outcome: VoidOutcome,
    ) : VoidDecision
}

/**
 * 決済の承認の規則(模擬。ADR-0029 §7)。外部の決済の代行は呼ばない。純粋な関数で、永続化は application と adapters が行う。
 *
 * @property limit 承認できる金額の上限。通貨が違う金額は承認しない(模擬は 1 つの通貨だけを扱う)
 */
public class PaymentRules(
    private val limit: Amount,
) {
    /**
     * 承認の指示の判定。同じ Saga ID の記録([existing])があれば、それで決まる返事を返し直す(取消済み・印なら `ALREADY_VOIDED`)。
     * 初めてなら、上限以下で同じ通貨なら承認する([authorizationId] を付ける)。
     */
    public fun authorize(
        existing: Authorization?,
        sagaId: String,
        orderId: String,
        amount: Amount,
        authorizationId: () -> String,
    ): AuthorizeDecision {
        if (existing != null) return AuthorizeDecision.Replay(replyOf(existing))
        return if (amount.currency == limit.currency && amount.minorUnits <= limit.minorUnits) {
            val id = authorizationId()
            AuthorizeDecision.Record(
                Authorization(sagaId, orderId, AuthorizationStatus.AUTHORIZED, authorizationId = id, amount = amount),
                AuthorizeReply.Authorized(id),
            )
        } else {
            AuthorizeDecision.Record(
                Authorization(sagaId, orderId, AuthorizationStatus.DECLINED, decline = DeclineReason.LIMIT_EXCEEDED, amount = amount),
                AuthorizeReply.Declined(DeclineReason.LIMIT_EXCEEDED),
            )
        }
    }

    /** 取消の指示の判定。記録がなければ印を作り、承認していれば取り消す。何度届いても 1 回だけ取り消す。 */
    public fun void(
        existing: Authorization?,
        sagaId: String,
        orderId: String,
    ): VoidDecision =
        when (existing?.status) {
            null -> {
                VoidDecision.MarkVoidedBeforeAuthorization(
                    Authorization(sagaId, orderId, AuthorizationStatus.VOIDED_BEFORE_AUTHORIZATION),
                )
            }

            AuthorizationStatus.AUTHORIZED -> {
                VoidDecision.Void
            }

            AuthorizationStatus.VOIDED -> {
                VoidDecision.Replay(VoidOutcome.VOIDED)
            }

            AuthorizationStatus.DECLINED, AuthorizationStatus.VOIDED_BEFORE_AUTHORIZATION -> {
                VoidDecision.Replay(VoidOutcome.NOT_AUTHORIZED)
            }
        }

    private fun replyOf(existing: Authorization): AuthorizeReply =
        when (existing.status) {
            AuthorizationStatus.AUTHORIZED -> {
                AuthorizeReply.Authorized(checkNotNull(existing.authorizationId))
            }

            AuthorizationStatus.DECLINED -> {
                AuthorizeReply.Declined(checkNotNull(existing.decline))
            }

            AuthorizationStatus.VOIDED, AuthorizationStatus.VOIDED_BEFORE_AUTHORIZATION -> {
                AuthorizeReply.Declined(
                    DeclineReason.ALREADY_VOIDED,
                )
            }
        }

    public companion object {
        /** 模擬の既定の上限(ADR-0029 §7)。 */
        public val DEFAULT_LIMIT: Amount = Amount(1_000_000, "JPY")
    }
}

/** 識別子を検査する(値はエラーに入れない)。 */
public fun validateIdentifiers(vararg fields: Pair<String, String>): Result<Unit, ValidationError> {
    val violations =
        fields.filter { (_, value) -> value.isBlank() || value.length > MAX_IDENTIFIER_LENGTH }.map { (name, _) ->
            FieldViolation(name, "1〜$MAX_IDENTIFIER_LENGTH 文字にしてください")
        }
    return if (violations.isEmpty()) ok(Unit) else err(ValidationError(violations))
}

/**
 * 終わった承認の記録(印を含む)の保持期間(ADR-0029 §5)。既定は 30 日で、[MINIMUM] より短くはできない。
 * 遅れて届いた承認の指示を拒否するため、印はコマンドのトピック(7 日)と DLQ(7 日)の保持期間の合計([MINIMUM])より長く残す。
 */
public data class SettledRetention private constructor(
    public val value: Duration,
) {
    public companion object {
        public val MINIMUM: Duration = 14.days
        public val DEFAULT: SettledRetention = SettledRetention(30.days)

        public fun of(
            value: Duration,
            field: String = "retention",
        ): Result<SettledRetention, ValidationError> =
            if (value >= MINIMUM) {
                ok(SettledRetention(value))
            } else {
                err(ValidationError(listOf(FieldViolation(field, "$MINIMUM 以上にしてください(コマンドのトピックと DLQ の保持期間の合計。ADR-0029 §5)"))))
            }
    }
}
