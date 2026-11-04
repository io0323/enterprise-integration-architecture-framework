@file:Suppress("MagicNumber", "TooManyFunctions") // 件数・待ち時間。取引先の側の操作と観察の小さな関数を、このシナリオのファイルにまとめる

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.PROMETHEUS
import io.eia.tests.e2e.E2eEnvironment.encode
import io.eia.tests.e2e.E2eEnvironment.get
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private val infra: Path = Path.of(System.getProperty("eiaf.repo.root", ".")).resolve("infra/local")
private val secrets: Path = infra.resolve("secrets")
private const val PARTNER = "partner01"
private const val GNUPG_IMAGE = "eiaf-gnupg:local"
private val UTC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
private val UNB_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd:HHmm").withZone(ZoneOffset.UTC)
private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneOffset.UTC)

// E2E だけが対応表に入れる買い手と商品(GS1 のチェックデジット付き)。make verify PROFILE=b2b は対応表にない買い手(4912345000019)と
// 商品(4901234567894)で業務の拒否を確かめるので、それとは別の値にする(E2E の後に verify を動かしても結果が変わらない)
private const val KNOWN_BUYER = "4912345000026"
private const val KNOWN_ITEM = "4901234567917"
private const val UNKNOWN_BUYER = "4912345000019"
private const val UNKNOWN_ITEM = "4901234567894"
private const val CUSTOMER = "cust-e2e-b2b"

/** コマンドを実行し、終了コードと出力を返す。[input] は標準入力、[environment] は子のプロセスに渡す環境変数。 */
private fun run(
    command: List<String>,
    input: ByteArray? = null,
    timeout: Duration = Duration.ofMinutes(5),
): Pair<Int, String> {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    process.outputStream.use { stream -> input?.let(stream::write) }
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) { "${command.take(4)} が終わりません" }
    return process.exitValue() to output
}

/** compose のコマンド(core・order・b2b の profile)。 */
private fun compose(vararg args: String): Pair<Int, String> =
    run(
        listOf("docker", "compose", "-f", infra.resolve("docker-compose.yml").toString()) +
            listOf("--env-file", infra.resolve("images.env").toString(), "--env-file", infra.resolve(".env").toString()) +
            listOf("--profile", "core", "--profile", "order", "--profile", "b2b") + args,
    )

/** b2b-gateway の DB を管理者で読む(結果の観察だけ。サービスはブラックボックスのまま)。 */
private fun b2b(query: String): String =
    compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "b2b_gateway", "-tAc", query).second.trim()

private fun imageVariable(name: String): String =
    Files.readAllLines(infra.resolve("images.env")).first { it.startsWith("$name=") }.substringAfter('=')

private fun id(flag: String): String = run(listOf("id", flag)).second.trim()

/**
 * 取引先 partner01 の側の GnuPG(make verify PROFILE=b2b と同じイメージと鍵)。取引先の秘密鍵と EIAF の公開鍵を読み込んでから [script] を動かす。
 * [script] の中では `$eiaf`(EIAF の鍵のフィンガープリント)と `$passphrase`(取引先の鍵のパスフレーズ。標準入力から読む)を使える。
 */
private fun partnerGpg(
    work: Path,
    script: String,
): Pair<Int, String> {
    val preamble =
        """
        set -euo pipefail
        mkdir -m 700 -p "${'$'}GNUPGHOME"
        read -r passphrase || true
        gpg --batch --quiet --import /keys/eiaf.pub.asc 2>/dev/null
        eiaf="${'$'}(gpg --batch --with-colons --list-keys | awk -F: '/^fpr:/ { print ${'$'}10; exit }')"
        gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 --import /keys/partner.sec.asc 3<<<"${'$'}passphrase" 2>/dev/null
        """.trimIndent()
    val command =
        listOf("docker", "run", "--rm", "-i", "--user", "${id("-u")}:${id("-g")}") +
            listOf("-v", "${secrets.resolve("pgp-partner01.sec.asc")}:/keys/partner.sec.asc:ro") +
            listOf("-v", "${secrets.resolve("pgp-eiaf.pub.asc")}:/keys/eiaf.pub.asc:ro") +
            listOf("-v", "$work:/work", "--entrypoint", "bash", GNUPG_IMAGE, "-c", preamble + "\n" + script)
    return run(command, input = Files.readAllBytes(secrets.resolve("pgp-partner01.passphrase")))
}

/** 取引先 partner01 として sftp-b2b に接続し、[batch] の SFTP のコマンドを実行する(ホスト鍵は make env の known_hosts に固定)。 */
private fun partnerSftp(batch: String): Pair<Int, String> =
    run(
        listOf("sftp", "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=${secrets.resolve("sftp-b2b.known_hosts")}") +
            listOf("-o", "LogLevel=ERROR", "-o", "BatchMode=yes", "-i", secrets.resolve("sftp-b2b").toString()) +
            listOf("-P", "19223", "-b", "-", "$PARTNER@127.0.0.1"),
        input = batch.toByteArray(),
    )

/** 取引先のコードの対応表を CSV で丸ごと入れ替える(`make b2b-codes` と同じ運用者のコマンド)。 */
private fun importCodes(
    kind: String,
    csv: String,
): Pair<Int, String> {
    val file = Files.createTempFile("e2e-b2b-$kind", ".csv")
    Files.writeString(file, csv)
    // コンテナはアプリの利用者で動くので、読めるようにする
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
    return compose(
        "run",
        "--rm",
        "--no-deps",
        "-v",
        "$file:/import/codes.csv:ro",
        "b2b-gateway",
        "codes",
        "import",
        PARTNER,
        kind,
        "/import/codes.csv",
        "--operator",
        "e2e",
    )
}

/** 受注の交換(ORDERS D.96A のサブセット。契約 contracts/files/edi/edifact-orders.v1.yaml の形。明細 1 つ)。[unt] で UNT の件数を変えられる。 */
private fun orders(
    reference: String,
    buyer: String,
    item: String,
    bgm: String = "BGM+220+PO-$reference+9",
    unt: Int? = null,
): String {
    val now = Instant.now()
    val body =
        listOf(
            bgm,
            "DTM+137:${UTC_DATE.format(now)}:102",
            "NAD+BY+$buyer::9",
            "NAD+SU+4900000000001::9",
            "CUX+2:JPY:9",
            "LIN+1++$item:EN",
            "QTY+21:1",
            "PRI+AAA:100",
            "UNS+S",
            "CNT+2:1",
        )
    val segments =
        listOf("UNB+UNOA:3+4912345000019:14+4900000000001:14+${UNB_TIME.format(now)}+$reference", "UNH+1+ORDERS:D:96A:UN") + body +
            listOf("UNT+${unt ?: (body.size + 2)}+1", "UNZ+1+$reference")
    return "UNA:+.? '" + segments.joinToString("'", postfix = "'")
}

/** 取引先が置く文書。[plain] が null なら PGP でない壊れたファイル(復号の段階の拒否)。 */
private data class Document(
    val label: String,
    val reference: String,
    val plain: String?,
) {
    val file: String = "orders-$reference.edi.pgp"
}

private fun receivedRow(file: String): String = b2b("SELECT outcome || ' ' || stage FROM b2b.received_document WHERE file_name = '$file'")

private fun documentId(file: String): String = b2b("SELECT id FROM b2b.received_document WHERE file_name = '$file'")

/** 応答の記録(種類:状態。種類の順)。応答がなければ空。 */
private fun acknowledgements(documentId: String): String =
    b2b(
        "SELECT coalesce(string_agg(kind || ':' || state, ' ' ORDER BY kind), '') " +
            "FROM b2b.acknowledgement WHERE document_id = '$documentId'",
    )

private fun firingOrPending(): List<Pair<String, String>> =
    json(get("$PROMETHEUS/api/v1/alerts"))
        .jsonObject
        .getValue("data")
        .jsonObject
        .getValue("alerts")
        .jsonArray
        .map { it.jsonObject.getValue("labels").jsonObject }
        .filter {
            it["alertname"]?.jsonPrimitive?.content == "B2bDocumentRejected" &&
                it["trading_partner_id"]?.jsonPrimitive?.content == PARTNER
        }.map { "B2bDocumentRejected" to (it["b2b_stage"]?.jsonPrimitive?.content ?: "") }

private fun prometheusValue(expr: String): Double? =
    json(get("$PROMETHEUS/api/v1/query?query=${encode(expr)}"))
        .jsonObject
        .getValue("data")
        .jsonObject
        .getValue("result")
        .jsonArray
        .firstOrNull()
        ?.jsonObject
        ?.getValue("value")
        ?.jsonArray
        ?.get(1)
        ?.jsonPrimitive
        ?.content
        ?.toDouble()

/**
 * B2B / EDI Gateway(P12。ROADMAP の DoD: 構文・スキーマ・業務のそれぞれのエラーで、正しいエラー応答と運用通知が出る。受信した全ドキュメントが
 * Audit に記録され、ハッシュチェーンの検証が通る)。取引先 partner01 の側として、GnuPG で署名・暗号化した受注の文書を sftp-b2b に置き、
 * 届いた応答(CONTRL・APERAK)を取り出して GnuPG で復号・署名の検証をする。b2b-gateway の DB は結果の観察だけに使う。
 *
 * - 受け付け: 対応表にある買い手・商品の注文を、order の API(Gateway 経由)に取り込み、肯定の APERAK(AP)を返す(承認の条件 1。台帳の
 *   `positiveAperak: true`)
 * - 業務の拒否: 対応表にない買い手・商品 → APERAK(RE。ORD001・ORD004)
 * - スキーマの拒否: BGM の文書の種類のコードが仕様にない → CONTRL の UCM = 4 と誤りの位置(UCS・UCD)。APERAK は返さない
 * - 構文の拒否: UNT の件数が合わない → CONTRL の UCM = 4 と UNT の誤り。APERAK は返さない
 * - 復号の拒否: PGP でないファイル → 応答は返せない(運用に知らせ、取引先には台帳の連絡先から連絡する)。原本と監査は残す
 */
class B2bGatewayE2E :
    FunSpec({
        val work: Path = Files.createTempDirectory("e2e-b2b")
        val stamp = STAMP.format(Instant.now())

        // 交換の管理番号(UNB の 0020。an..14)。実行ごとに変える(同じ番号は送り直しとして扱われる)
        fun reference(letter: Char) = "E$stamp$letter"

        val accepted = Document("受け付け", reference('A'), orders(reference('A'), KNOWN_BUYER, KNOWN_ITEM))
        val business = Document("業務の拒否", reference('B'), orders(reference('B'), UNKNOWN_BUYER, UNKNOWN_ITEM))
        val schema =
            Document("スキーマの拒否", reference('S'), orders(reference('S'), KNOWN_BUYER, KNOWN_ITEM, bgm = "BGM+221+PO-${reference('S')}+9"))
        val syntax = Document("構文の拒否", reference('X'), orders(reference('X'), KNOWN_BUYER, KNOWN_ITEM, unt = 11))
        val unreadable = Document("復号の拒否", reference('D'), null)
        val documents = listOf(accepted, business, schema, syntax, unreadable)
        val ids = mutableMapOf<Document, String>()
        val startedAt = Instant.now().epochSecond.toDouble()

        beforeSpec {
            // 取引先の側の GnuPG のイメージ(make pgp-keys が作るものと同じ。キャッシュがあればすぐ終わる)
            val (built, buildOutput) =
                run(
                    listOf("docker", "build", "-q", "-t", GNUPG_IMAGE, "--build-arg", "DEBIAN_IMAGE=${imageVariable("DEBIAN_IMAGE")}") +
                        infra.resolve("images/gnupg").toString(),
                )
            withClue(buildOutput) { built shouldBe 0 }
            val buyers =
                importCodes(
                    "buyers",
                    "gln,customer_id,country_code,postal_code,region,city,line1,line2\n$KNOWN_BUYER,$CUSTOMER,JP,100-0001,,Chiyoda,1-1,\n",
                )
            withClue(buyers.second) { buyers.first shouldBe 0 }
            val items = importCodes("items", "code_type,code,product_id,sku\ngtin,$KNOWN_ITEM,prod-1,SKU-1\n")
            withClue(items.second) { items.first shouldBe 0 }
        }

        test("取引先として、署名・暗号化した受注の文書 4 つと、PGP でないファイル 1 つを受け取りのディレクトリに置ける(一時名 → 正式名)") {
            documents.forEach { document ->
                val content = document.plain?.toByteArray(Charsets.ISO_8859_1) ?: Random.nextBytes(512)
                Files.write(work.resolve(if (document.plain != null) "${document.reference}.edi" else document.file), content)
            }
            val sealed =
                documents.filter { it.plain != null }.joinToString("\n") {
                    """gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 --trust-model always --recipient "${'$'}eiaf" """ +
                        """--sign --encrypt --output /work/${it.file} /work/${it.reference}.edi 3<<<"${'$'}passphrase""""
                }
            val (gpgStatus, gpgOutput) = partnerGpg(work, sealed)
            withClue(gpgOutput) { gpgStatus shouldBe 0 }
            val batch =
                documents.joinToString(
                    "\n",
                ) { "put ${work.resolve(it.file)} to-eiaf/${it.file}.part\nrename to-eiaf/${it.file}.part to-eiaf/${it.file}" }
            val (sftpStatus, sftpOutput) = partnerSftp(batch)
            withClue(sftpOutput) { sftpStatus shouldBe 0 }

            documents.forEach { document ->
                withClue("${document.label}: b2b-gateway が受け付けて記録する") {
                    eventually(Duration.ofSeconds(120), Duration.ofSeconds(2)) { documentId(document.file).takeIf { it.isNotEmpty() } }
                        .shouldNotBeNull()
                        .also { ids[document] = it }
                }
            }
        }

        test("段階ごとに受け付けの結果を記録し、受け取った原本(暗号文)を保管した") {
            receivedRow(accepted.file) shouldBe "CHECKED none"
            receivedRow(business.file) shouldBe "CHECKED business"
            receivedRow(schema.file) shouldBe "CHECKED schema"
            receivedRow(syntax.file) shouldBe "CHECKED syntax"
            receivedRow(unreadable.file) shouldBe "UNREADABLE decryption"
            documents.forEach { document ->
                withClue(document.label) {
                    b2b("SELECT original_ref FROM b2b.received_document WHERE id = '${ids.getValue(document)}'").shouldNotBeEmpty()
                }
            }
        }

        test("受け付けた注文を order の API に取り込み、CONTRL と肯定の APERAK を送った") {
            val id = ids.getValue(accepted)
            withClue("CONTRL と APERAK(AP)を送る") {
                eventually(Duration.ofSeconds(120), Duration.ofSeconds(2)) {
                    acknowledgements(id).takeIf { it == "APERAK:SENT CONTRL:SENT" }
                } shouldBe "APERAK:SENT CONTRL:SENT"
            }
            val orderId = b2b("SELECT order_id FROM b2b.order_submission WHERE document_id = '$id' AND state = 'ACCEPTED'")
            orderId.shouldNotBeEmpty()
            val order = sendRespectingRateLimit(request("$ORDERS/$orderId").GET())
            order.statusCode() shouldBe 200
            order.body() shouldContain CUSTOMER
        }

        test("拒否した文書には段階に応じた応答を送った(業務: CONTRL と APERAK(RE)。スキーマ・構文: CONTRL。復号: なし)") {
            val expectations = listOf(business to "APERAK:SENT CONTRL:SENT", schema to "CONTRL:SENT", syntax to "CONTRL:SENT")
            expectations.forEach { (document, expected) ->
                withClue(document.label) {
                    eventually(Duration.ofSeconds(120), Duration.ofSeconds(2)) {
                        acknowledgements(ids.getValue(document)).takeIf { it == expected }
                    } shouldBe expected
                }
            }
            acknowledgements(ids.getValue(unreadable)) shouldBe ""
        }

        test("取引先として届いた応答を復号し、EIAF の署名を確かめ、中身が段階ごとに正しい") {
            val files =
                listOf(accepted, business, schema, syntax).flatMap { document ->
                    val id = ids.getValue(document)
                    b2b("SELECT kind || ' ' || remote_name FROM b2b.acknowledgement WHERE document_id = '$id' ORDER BY kind")
                        .lines()
                        .filter { it.isNotBlank() }
                        .map { line -> "${line.substringBefore(' ').lowercase()}-${document.reference}" to line.substringAfter(' ') }
                }
            val (sftpStatus, sftpOutput) =
                partnerSftp(
                    files.joinToString("\n") { (local, remote) -> "get from-eiaf/$remote ${work.resolve("$local.pgp")}" },
                )
            withClue(sftpOutput) { sftpStatus shouldBe 0 }
            val decrypt =
                files.joinToString("\n") { (local, _) ->
                    """gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 --status-fd 1 --output /work/$local.edi """ +
                        """--decrypt /work/$local.pgp 3<<<"${'$'}passphrase""""
                }
            val (gpgStatus, status) = partnerGpg(work, decrypt)
            withClue(status) {
                gpgStatus shouldBe 0
                // gpg の標準出力と標準エラーは 1 つにまとめて読むので、同じ行に混ざることがある。行頭ではなく出現の回数で数える
                status.windowed("[GNUPG:] VALIDSIG".length).count { it == "[GNUPG:] VALIDSIG" } shouldBe files.size
                status.windowed("[GNUPG:] GOODMDC".length).count { it == "[GNUPG:] GOODMDC" } shouldBe files.size
            }

            fun edi(local: String) = Files.readString(work.resolve("$local.edi"), Charsets.ISO_8859_1)

            val partnerIds = "4912345000019:14+4900000000001:14"
            withClue("受け付け") {
                edi("contrl-${accepted.reference}") shouldContain "UCI+${accepted.reference}+$partnerIds+7'UCM+1+ORDERS:D:96A:UN+7'"
                edi("aperak-${accepted.reference}") shouldContain "+9+AP'"
            }
            withClue("業務の拒否: 仕様の固定の文言だけで理由を返す") {
                edi("contrl-${business.reference}") shouldContain "UCI+${business.reference}+$partnerIds+7'UCM+1+ORDERS:D:96A:UN+7'"
                val aperak = edi("aperak-${business.reference}")
                aperak shouldContain "+9+RE'"
                aperak shouldContain "ERC+ORD001::ZZZ"
                aperak shouldContain "ERC+ORD004::ZZZ"
            }
            withClue("スキーマの拒否: 2 番目のセグメント(BGM)の 1 番目のデータ要素の値の誤り(UCD の 12)") {
                edi("contrl-${schema.reference}") shouldContain "UCM+1+ORDERS:D:96A:UN+4'UCS+2'UCD+12+1:1'"
            }
            withClue("構文の拒否: メッセージの枠(UNT)の誤り") {
                edi("contrl-${syntax.reference}") shouldContain "UCM+1+ORDERS:D:96A:UN+4+29+UNT"
            }
        }

        test("受け取った全部の文書・送った応答・社内への取り込みを監査に記録した(段階つき)") {
            documents.forEach { document ->
                val id = ids.getValue(document)
                withClue(document.label) {
                    b2b("SELECT count(*) FROM audit.audit_log WHERE action = 'b2b.document.receive' AND target_id = '$id'") shouldBe "1"
                }
            }
            b2b(
                "SELECT details->>'stage' FROM audit.audit_log WHERE action = 'b2b.document.receive' AND target_id = '${ids.getValue(
                    unreadable,
                )}'",
            ) shouldBe
                "decryption"
            val sentAcks =
                documents.sumOf { document ->
                    b2b("SELECT count(*) FROM b2b.acknowledgement WHERE document_id = '${ids.getValue(document)}'").toInt()
                }
            val auditedAcks =
                documents.sumOf { document ->
                    b2b(
                        "SELECT count(*) FROM audit.audit_log a JOIN b2b.acknowledgement k ON a.target_id = k.id::text " +
                            "WHERE a.action = 'b2b.ack.send' AND k.document_id = '${ids.getValue(document)}'",
                    ).toInt()
                }
            auditedAcks shouldBe sentAcks
            b2b(
                "SELECT count(*) FROM audit.audit_log a JOIN b2b.order_submission s ON a.target_id = s.id::text " +
                    "WHERE a.action = 'b2b.order.submit' AND a.outcome = 'success' AND s.document_id = '${ids.getValue(accepted)}'",
            ) shouldBe "1"
        }

        test("復号・構文・スキーマ・業務のそれぞれの拒否で、運用の通知(B2bDocumentRejected)が出る") {
            val expected = listOf("decryption", "syntax", "schema", "business").map { "B2bDocumentRejected" to it }
            // メトリクスの送信(10 秒)+ scrape(15 秒)+ ルールの評価(15 秒)
            withClue("Prometheus のアラート") {
                eventually(Duration.ofSeconds(180), Duration.ofSeconds(5)) {
                    firingOrPending().takeIf { it.containsAll(expected) }
                }.shouldNotBeNull() shouldContainAll expected
            }
        }

        test("監査のアンカーを保存し、ハッシュチェーンの検証(make audit-verify SERVICE=b2b-gateway)が通る") {
            // アンカーの保存の間隔(compose では 1 分)+ メトリクスの送信と scrape。応答の送信の監査より後に保存した版を待つ
            val sentAt = b2b("SELECT extract(epoch FROM max(recorded_at)) FROM audit.audit_log").toDouble()
            withClue("b2b-gateway が監査の記録の後にアンカーを保存する") {
                eventually(Duration.ofSeconds(180), Duration.ofSeconds(5)) {
                    prometheusValue("""max(eia_audit_anchor_last_published_seconds{job="b2b-gateway"})""")
                        ?.takeIf { it >= maxOf(startedAt, sentAt) }
                }.shouldNotBeNull()
            }
            val (status, output) = run(listOf("bash", infra.resolve("scripts/audit-anchored.sh").toString(), "b2b-gateway"))
            withClue(output) { status shouldBe 0 }
        }

        afterSpec { work.toFile().deleteRecursively() }
    })
