package io.eia.platform.security.secret

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

private val NAME = SecretName("ORDER_DB_PASSWORD")
private const val VALUE = "pa55:w0rd/with+symbols"

private fun Result<Secret, SecretError>.value(): String = shouldBeInstanceOf<Result.Ok<Secret>>().value.reveal()

private fun Result<Secret, SecretError>.error(): SecretError = shouldBeInstanceOf<Result.Err<SecretError>>().error

class EnvSecretProviderSpec :
    FunSpec({
        val dir = tempdir()

        fun file(
            name: String,
            content: ByteArray,
        ): String = File(dir, name).apply { writeBytes(content) }.absolutePath

        test("環境変数の値を読む") {
            EnvSecretProvider(mapOf("ORDER_DB_PASSWORD" to VALUE)).get(NAME).value() shouldBe VALUE
        }

        test("_FILE のファイル(Docker secrets)を読み、末尾の改行を 1 つだけ取り除く") {
            listOf("\n", "\r\n", "").forEach { eol ->
                val path = file("secret-${eol.length}", (VALUE + eol).toByteArray())
                EnvSecretProvider(mapOf("ORDER_DB_PASSWORD_FILE" to path)).get(NAME).value() shouldBe VALUE
            }
            val multiline = file("multi", "line1\nline2\n\n".toByteArray())
            EnvSecretProvider(mapOf("ORDER_DB_PASSWORD_FILE" to multiline)).get(NAME).value() shouldBe "line1\nline2\n"
        }

        test("ファイルは呼ばれるたびに読み直す(ファイルの差し替えによるローテーション)") {
            val path = file("rotating", "old".toByteArray())
            val provider = EnvSecretProvider(mapOf("ORDER_DB_PASSWORD_FILE" to path))

            provider.get(NAME).value() shouldBe "old"
            File(path).writeText("new")
            provider.get(NAME).value() shouldBe "new"
        }

        test("どちらもなければ SecretNotFound(NonRetryable)") {
            val error = EnvSecretProvider(emptyMap()).get(NAME).error()
            error shouldBe SecretNotFound(NAME)
            error.shouldBeInstanceOf<DomainError.NonRetryable>()
        }

        test("値とファイルの両方があれば、どちらも使わずにエラーにする") {
            val path = file("both", "from-file".toByteArray())
            EnvSecretProvider(mapOf("ORDER_DB_PASSWORD" to VALUE, "ORDER_DB_PASSWORD_FILE" to path))
                .get(NAME)
                .error()
                .shouldBeInstanceOf<SecretMisconfigured>()
        }

        test("空の値・空のファイル・UTF-8 でないファイル・大きすぎるファイルは不正な設定") {
            EnvSecretProvider(mapOf("ORDER_DB_PASSWORD" to "")).get(NAME).error().shouldBeInstanceOf<SecretMisconfigured>()
            listOf(
                file("empty", ByteArray(0)),
                file("newline-only", "\n".toByteArray()),
                file("binary", byteArrayOf(0xC3.toByte(), 0x28)),
                file("huge", ByteArray((EnvSecretProvider.MAX_FILE_BYTES + 1).toInt()) { 'a'.code.toByte() }),
            ).forEach { path ->
                EnvSecretProvider(mapOf("ORDER_DB_PASSWORD_FILE" to path)).get(NAME).error().shouldBeInstanceOf<SecretMisconfigured>()
            }
        }

        test("ファイルがなければ SecretUnreadable(Retryable。差し替えの途中でありうる)。メッセージにパスを入れない") {
            val path = File(dir, "missing").absolutePath
            val error = EnvSecretProvider(mapOf("ORDER_DB_PASSWORD_FILE" to path)).get(NAME).error()

            error.shouldBeInstanceOf<SecretUnreadable>().shouldBeInstanceOf<DomainError.Retryable>()
            error.message shouldNotContain path
        }

        test("Secret の toString は値を伏せ、equals は値で比べる") {
            val secret = Secret(VALUE)

            secret.toString() shouldBe "Secret(***)"
            "$secret" shouldNotContain VALUE
            secret shouldBe Secret(VALUE)
            secret shouldNotBe Secret("other")
            shouldThrow<IllegalArgumentException> { Secret("") }
        }

        test("SecretName は環境変数の名前の規則だけを許し、_FILE で終わる名前は使えない") {
            listOf("order_db", "1ABC", "A-B", "", "ORDER_DB_PASSWORD_FILE").forEach { name ->
                shouldThrow<IllegalArgumentException> { SecretName(name) }
            }
        }
    })
