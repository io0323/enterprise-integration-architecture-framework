package io.eia.order.domain

import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.money.MoneyError
import io.eia.shared.kernel.ok
import kotlin.time.Instant

/**
 * 注文(集約)。order の業務のルールを表す独自のモデルで、Canonical Model(`shared/canonical-model`)には依存しない。
 * Canonical Model や契約の DTO との変換は adapters で行う(ADR-0010 Decision 7)。
 *
 * - 作るのは [place](受け付け)と [restore](保存した値からの復元)だけ。
 * - 金額はすべて税抜。明細の金額は単価 × 数量、[totalAmount] は明細の金額の合計(ADR-0011)。税は計算しない(請求で計算する)。
 * - [version] は楽観的ロックのための版。domain は値を運ぶだけで、増やすのと衝突の検出は永続化(P05 ④a)が行う。
 * - [toString] は配送先を伏せる(個人情報)。
 */
@Suppress("LongParameterList") // 集約の全項目
public class Order private constructor(
    public val id: OrderId,
    public val customerId: CustomerId,
    public val status: OrderStatus,
    public val orderedAt: Instant,
    public val lines: List<OrderLine>,
    public val totalAmount: Money,
    public val shippingAddress: ShippingAddress,
    public val version: Long,
) {
    /**
     * [next] の状態に遷移する(遷移表は [OrderStatus.allowedTransitions] と docs/architecture/order-state-machine.md)。
     *
     * - 表にある遷移は [Transition.Applied](新しい状態の注文)。
     * - 同じ状態への遷移は [Transition.NoOp](何も変えない)。At-Least-Once でイベントが重複して届いても、状態変更のイベントを
     *   重ねて発行しないため(P06 の Outbox では NoOp のときに発行しない)。
     * - 表にない遷移は [ConflictError](API では 409 `conflict`)。
     */
    public fun transitionTo(next: OrderStatus): Result<Transition, ConflictError> =
        when {
            next == status -> ok(Transition.NoOp(this))
            next in status.next -> ok(Transition.Applied(copy(status = next), from = status))
            else -> err(ConflictError("注文の状態を $status から $next には変えられません"))
        }

    private fun copy(status: OrderStatus): Order = Order(id, customerId, status, orderedAt, lines, totalAmount, shippingAddress, version)

    override fun equals(other: Any?): Boolean =
        other is Order &&
            id == other.id &&
            customerId == other.customerId &&
            status == other.status &&
            orderedAt == other.orderedAt &&
            lines == other.lines &&
            totalAmount == other.totalAmount &&
            shippingAddress == other.shippingAddress &&
            version == other.version

    override fun hashCode(): Int = listOf(id, customerId, status, orderedAt, lines, totalAmount, shippingAddress, version).hashCode()

    override fun toString(): String =
        "Order(id=$id, status=$status, orderedAt=$orderedAt, lines=${lines.size}, totalAmount=$totalAmount, version=$version)"

    /** [transitionTo] の結果。 */
    public sealed interface Transition {
        /** 遷移後(または変わらなかった)の注文。 */
        public val order: Order

        /** 状態が [from] から `order.status` に変わった。状態変更のイベントを発行する。 */
        public data class Applied(
            override val order: Order,
            val from: OrderStatus,
        ) : Transition

        /** すでにその状態だった。何も変えず、イベントも発行しない。 */
        public data class NoOp(
            override val order: Order,
        ) : Transition
    }

    public companion object {
        /** 新しい注文の版。 */
        public const val INITIAL_VERSION: Long = 0

        /**
         * 注文を受け付ける。状態は [OrderStatus.PLACED]、版は [INITIAL_VERSION]。
         * 値域と業務整合の違反は、すべて集めて [ValidationError] で返す(項目のパスは契約と同じ。例 `lines[0].quantity`)。
         *
         * - 明細は 1 件以上。数量は 1 以上。単価は 0 以上。
         * - すべての明細の単価は、1 件目の明細と同じ通貨。
         * - 単価 × 数量と合計が、金額で表せる範囲を超えない(超えたら丸めずにエラー。ADR-0011 §1)。
         */
        public fun place(
            id: OrderId,
            draft: OrderDraft,
            orderedAt: Instant,
        ): Result<Order, ValidationError> {
            val violations = Violations()
            val customerId = violations.take(CustomerId.parse(draft.customerId))
            val address = violations.take(ShippingAddress.of(draft.shippingAddress))
            if (draft.lines.isEmpty()) violations.add("lines", "1 件以上です")
            val lines = draft.lines.mapIndexedNotNull { index, line -> line(violations, index, line, draft.lines.first().unitPrice) }
            // 合計は、違反がないときだけ計算する(合計があれば、ほかの項目にも違反はない)
            val total = if (violations.isEmpty) totalOf(violations, lines) else null
            return if (total != null && customerId != null && address != null) {
                ok(Order(id, customerId, OrderStatus.PLACED, orderedAt, lines, total, address, INITIAL_VERSION))
            } else {
                err(violations.toError())
            }
        }

        /** 保存した値から復元する(永続化の adapters が使う)。保存の前に検証済みの値なので、ここでは検証しない。 */
        @Suppress("LongParameterList") // 集約の全項目
        public fun restore(
            id: OrderId,
            customerId: CustomerId,
            status: OrderStatus,
            orderedAt: Instant,
            lines: List<RestoredLine>,
            totalAmount: Money,
            shippingAddress: ShippingAddress,
            version: Long,
        ): Order =
            Order(
                id,
                customerId,
                status,
                orderedAt,
                lines.map { OrderLine(it.lineNumber, it.productId, it.sku, it.quantity, it.unitPrice, it.lineAmount) },
                totalAmount,
                shippingAddress,
                version,
            )

        private fun line(
            violations: Violations,
            index: Int,
            draft: OrderLineDraft,
            first: Money,
        ): OrderLine? {
            val path = "lines[$index]"
            val productId = violations.take(ProductId.parse(draft.productId, "$path.productId"))
            val sku = violations.take(Sku.parse(draft.sku, "$path.sku"))
            val quantityValid = draft.quantity >= 1
            val priceValid = !draft.unitPrice.isNegative
            if (!quantityValid) violations.add("$path.quantity", "1 以上です")
            if (!priceValid) violations.add("$path.unitPrice.amount", "0 以上です")
            if (draft.unitPrice.currency != first.currency) violations.add("$path.unitPrice.currency", "1 件目の明細と同じ通貨です")
            val amount = if (quantityValid && priceValid) lineAmountOf(violations, path, draft) else null
            return if (productId != null && sku != null && amount != null) {
                OrderLine(index + 1, productId, sku, draft.quantity, draft.unitPrice, amount)
            } else {
                null
            }
        }

        /** 単価 × 数量。金額で表せる範囲を超えたら、丸めずに違反にする(ADR-0011 §1)。 */
        private fun lineAmountOf(
            violations: Violations,
            path: String,
            draft: OrderLineDraft,
        ): Money? =
            when (val product = draft.unitPrice * draft.quantity) {
                is Result.Ok -> {
                    product.value
                }

                is Result.Err -> {
                    violations.add("$path.quantity", "金額が表せる範囲を超えます")
                    null
                }
            }

        private fun totalOf(
            violations: Violations,
            lines: List<OrderLine>,
        ): Money? =
            when (val total = Money.sum(lines.first().unitPrice.currency, lines.map { it.lineAmount })) {
                is Result.Ok -> {
                    total.value
                }

                is Result.Err -> {
                    // 通貨の不一致は明細の検証で先に弾くので、ここに来るのは合計の桁あふれだけ
                    check(total.error is MoneyError.Overflow) { "想定外の金額のエラーです: ${total.error.code}" }
                    violations.add("lines", "合計の金額が表せる範囲を超えます")
                    null
                }
            }
    }
}

/** 受け付ける注文(検証前)。項目の名前は契約の `PlaceOrderRequest` と同じ。adapters が DTO から作る。 */
public data class OrderDraft(
    val customerId: String,
    val lines: List<OrderLineDraft>,
    val shippingAddress: AddressDraft,
)

/** 受け付ける配送先(検証前)。[toString] は国コード以外を伏せる(個人情報)。 */
public data class AddressDraft(
    val countryCode: String,
    val postalCode: String,
    val region: String?,
    val city: String,
    val line1: String,
    val line2: String?,
) {
    override fun toString(): String = "AddressDraft(countryCode=$countryCode, ***)"
}

/** 保存した明細の値([Order.restore] の入力)。 */
public data class RestoredLine(
    val lineNumber: Int,
    val productId: ProductId,
    val sku: Sku,
    val quantity: Long,
    val unitPrice: Money,
    val lineAmount: Money,
)
