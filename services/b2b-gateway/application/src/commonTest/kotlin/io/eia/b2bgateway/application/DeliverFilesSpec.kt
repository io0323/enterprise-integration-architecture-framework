package io.eia.b2bgateway.application

import io.eia.b2bgateway.application.port.inbound.FileDeliverySummary
import io.eia.b2bgateway.application.port.inbound.HandoffMismatch
import io.eia.b2bgateway.application.port.outbound.Delivery
import io.eia.b2bgateway.application.port.outbound.FileDeliveries
import io.eia.b2bgateway.application.port.outbound.FileDelivery
import io.eia.b2bgateway.application.port.outbound.HandoffFile
import io.eia.b2bgateway.application.port.outbound.HandoffFiles
import io.eia.b2bgateway.application.port.outbound.HandoffListing
import io.eia.b2bgateway.application.port.outbound.OutboundDocument
import io.eia.b2bgateway.application.port.outbound.PartnerOutbox
import io.eia.b2bgateway.application.usecase.DeliverFilesService
import io.eia.b2bgateway.application.usecase.FileDeliveryPorts
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private val NOW = Instant.parse("2026-11-02T00:00:00Z")

private data object SftpDown : DomainError.Retryable {
    override val code: String = "sftp_down"
    override val message: String = "取引先の SFTP に接続できません"
}

private fun file(
    name: String,
    content: String = name,
    createdAt: Instant = NOW,
) = HandoffFile(name, "$name.csv.gz", "sha:$content", createdAt)

/** 受け渡しの場所・送信の記録・取引先の送信の仕組みの代わり。送った文書・記録・消したファイルを残す。 */
private class FileFakes(
    val files: List<HandoffFile>,
    val contents: Map<String, String> = files.associate { it.name to it.name },
    val recorded: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    val present: Set<String> = emptySet(),
    val failing: Set<String> = emptySet(),
) : HandoffFiles,
    FileDeliveries,
    PartnerOutbox {
    val sent = mutableListOf<String>()
    val records = mutableListOf<FileDelivery>()
    val deleted = mutableListOf<String>()

    override suspend fun list(): Result<HandoffListing, DomainError> = ok(HandoffListing(files, listOf("broken")))

    override suspend fun read(file: HandoffFile): Result<ByteArray, DomainError> = ok(contents.getValue(file.name).encodeToByteArray())

    override suspend fun delete(file: HandoffFile): Result<Unit, DomainError> {
        deleted += file.name
        return ok(Unit)
    }

    override suspend fun delivered(name: String): Result<Set<String>, DomainError> = ok(recorded[name].orEmpty())

    override suspend fun record(delivery: FileDelivery): Result<Unit, DomainError> {
        records += delivery
        recorded.getOrPut(delivery.name) { mutableSetOf() } += delivery.partnerId
        return ok(Unit)
    }

    override suspend fun deliver(
        partnerId: String,
        document: OutboundDocument,
    ): Result<Delivery, DomainError> {
        if (partnerId in failing) return err(SftpDown)
        sent += "$partnerId:${document.destination}:${document.remoteName}:${document.sealed}:${document.content.decodeToString()}"
        return ok(if (partnerId in present) Delivery.ALREADY_PRESENT else Delivery.SENT)
    }

    fun service() =
        DeliverFilesService(
            FileDeliveryPorts(this, { dataset -> if (dataset == "sales_daily") listOf("p1", "p2") else emptyList() }, this, this),
            { "sha:${it.decodeToString()}" },
            FixedClock(NOW),
            retention = 14.days,
        )
}

private suspend fun FileFakes.run(): FileDeliverySummary = service()().shouldBeInstanceOf<Result.Ok<FileDeliverySummary>>().value

class DeliverFilesSpec :
    FunSpec({
        test("受け取る取引先ごとに、暗号化する前のデータを送信の仕組みに渡し(正式名は {データファイル}.pgp)、送った記録を残す") {
            val fakes = FileFakes(listOf(file("sales_daily_20261101150000_001")), present = setOf("p2"))

            val summary = fakes.run()

            summary.sent shouldContainExactly listOf("sales_daily_20261101150000_001" to "p1", "sales_daily_20261101150000_001" to "p2")
            summary.invalid shouldContainExactly listOf("broken")
            fakes.sent shouldContainExactly
                listOf(
                    "p1:OUTBOUND:sales_daily_20261101150000_001.csv.gz.pgp:false:sales_daily_20261101150000_001",
                    "p2:OUTBOUND:sales_daily_20261101150000_001.csv.gz.pgp:false:sales_daily_20261101150000_001",
                )
            fakes.records.map { "${it.partnerId}:${it.remoteName}:${it.sha256}:${it.alreadyPresent}" } shouldContainExactly
                listOf(
                    "p1:sales_daily_20261101150000_001.csv.gz.pgp:sha:sales_daily_20261101150000_001:false",
                    "p2:sales_daily_20261101150000_001.csv.gz.pgp:sha:sales_daily_20261101150000_001:true",
                )
        }

        test("毎回すべて一覧し、記録のない取引先だけに送る。過去の日付のファイルが後から置かれても送る(名前の順の位置では見ない)") {
            val recorded =
                mutableMapOf(
                    "sales_daily_20261101150000_001" to mutableSetOf("p1", "p2"),
                    "sales_daily_20261031150000_001" to mutableSetOf("p1"),
                )
            val fakes =
                FileFakes(
                    listOf(
                        file("sales_daily_20261020150000_001"),
                        file("sales_daily_20261031150000_001"),
                        file("sales_daily_20261101150000_001"),
                    ),
                    recorded = recorded,
                )

            fakes.run().sent shouldContainExactly
                listOf(
                    "sales_daily_20261020150000_001" to "p1",
                    "sales_daily_20261020150000_001" to "p2",
                    "sales_daily_20261031150000_001" to "p2",
                )
        }

        test("SHA-256 がマニフェストと違うファイルは、どの取引先にも送らない。1 つの取引先の失敗は、ほかの取引先とファイルを止めない") {
            val fakes =
                FileFakes(
                    listOf(file("sales_daily_20261101150000_001"), file("sales_daily_20261102150000_001")),
                    contents =
                        mapOf(
                            "sales_daily_20261101150000_001" to "tampered",
                            "sales_daily_20261102150000_001" to "sales_daily_20261102150000_001",
                        ),
                    failing = setOf("p1"),
                )

            val summary = fakes.run()

            summary.failed.map { Triple(it.name, it.partnerId, it.error) } shouldContainExactly
                listOf(
                    Triple("sales_daily_20261101150000_001", null, HandoffMismatch("sales_daily_20261101150000_001")),
                    Triple("sales_daily_20261102150000_001", "p1", SftpDown),
                )
            summary.sent shouldContainExactly listOf("sales_daily_20261102150000_001" to "p2")
        }

        test("送り終えた・受け取る取引先のないファイルは、保存期間を過ぎたら消す。送れていないファイルと、期間の内のファイルは消さない") {
            val old = NOW - 15.days
            val recorded =
                mutableMapOf(
                    "sales_daily_20261001150000_001" to mutableSetOf("p1", "p2"),
                    "sales_daily_20261101150000_001" to mutableSetOf("p1", "p2"),
                )
            val fakes =
                FileFakes(
                    listOf(
                        file("sales_daily_20261001150000_001", createdAt = old),
                        file("sales_weekly_20261001150000_001", createdAt = old),
                        file("sales_daily_20261002150000_001", createdAt = old),
                        file("sales_daily_20261101150000_001"),
                    ),
                    recorded = recorded,
                    failing = setOf("p1", "p2"),
                )

            val summary = fakes.run()

            summary.deleted shouldContainExactly listOf("sales_daily_20261001150000_001", "sales_weekly_20261001150000_001")
            fakes.deleted shouldContainExactly summary.deleted
        }

        test("一覧を読めなければ、そのエラー") {
            val fakes =
                object : HandoffFiles by FileFakes(emptyList()) {
                    override suspend fun list(): Result<HandoffListing, DomainError> = err(SftpDown)
                }
            val ports = FileFakes(emptyList())
            DeliverFilesService(FileDeliveryPorts(fakes, { emptyList() }, ports, ports), { "" }, FixedClock(NOW), 14.days)() shouldBe
                err(SftpDown)
            ports.sent.shouldBeEmpty()
        }
    })
