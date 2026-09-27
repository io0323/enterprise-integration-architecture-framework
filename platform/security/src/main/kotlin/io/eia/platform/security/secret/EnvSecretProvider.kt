package io.eia.platform.security.secret

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * 環境変数と Docker secrets から秘密情報を読む [SecretProvider](ADR-0008・ADR-0019 §6)。
 *
 * 名前 `NAME` に対して、次のどちらか一方を設定する。
 * - `NAME`: 値そのもの
 * - `NAME_FILE`: 値を書いたファイルのパス(Docker secrets は `/run/secrets/<name>` にマウントされる)
 *
 * 両方あるとき・どちらもないとき・値が空のときはエラーにする(どちらが使われるかを曖昧にしないため)。
 * ファイルは呼ばれるたびに読み直す(ファイルの差し替えによるローテーションに追従するため)。
 * ファイルの末尾の改行は 1 つ(`\r\n` か `\n`)だけ取り除く(`echo` で書いたファイルを想定。値の中の改行と、末尾の `\r` だけは残す)。
 *
 * @param environment 環境変数(テストでは Map を渡す)
 */
public class EnvSecretProvider(
    private val environment: Map<String, String> = System.getenv(),
) : SecretProvider {
    override fun get(name: SecretName): Result<Secret, SecretError> {
        val direct = environment[name.value]
        val filePath = environment[name.value + SecretName.FILE_SUFFIX]
        return when {
            direct != null && filePath != null -> {
                err(SecretMisconfigured(name, "$name と ${name}${SecretName.FILE_SUFFIX} の両方が設定されています"))
            }

            direct != null -> {
                if (direct.isEmpty()) err(SecretMisconfigured(name, "値が空です")) else ok(Secret(direct))
            }

            filePath != null -> {
                readFile(name, filePath)
            }

            else -> {
                err(SecretNotFound(name))
            }
        }
    }

    @Suppress("ReturnCount") // 失敗の種類(パス・大きさ・I/O・文字コード・空)ごとに返す
    private fun readFile(
        name: SecretName,
        filePath: String,
    ): Result<Secret, SecretError> {
        // エラーのメッセージにはパスを入れない(パスは秘密ではないが、設定の値をそのまま出す習慣を作らない)。例外のメッセージも入れない
        val path =
            try {
                Path.of(filePath)
            } catch (_: InvalidPathException) {
                return err(SecretMisconfigured(name, "${SecretName.FILE_SUFFIX} のパスが不正です"))
            }
        val bytes =
            try {
                if (Files.size(path) > MAX_FILE_BYTES) {
                    return err(SecretMisconfigured(name, "ファイルが大きすぎます(上限 $MAX_FILE_BYTES バイト)"))
                }
                Files.readAllBytes(path)
            } catch (_: NoSuchFileException) {
                return err(SecretUnreadable(name, "ファイルがありません"))
            } catch (e: IOException) {
                return err(SecretUnreadable(name, e::class.simpleName ?: "IOException"))
            } catch (_: SecurityException) {
                return err(SecretUnreadable(name, "ファイルを読む権限がありません"))
            }
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                return err(SecretMisconfigured(name, "ファイルが UTF-8 ではありません"))
            }
        val value = if (text.endsWith("\r\n")) text.dropLast(2) else text.removeSuffix("\n")
        return if (value.isEmpty()) err(SecretMisconfigured(name, "ファイルが空です")) else ok(Secret(value))
    }

    public companion object {
        /** ファイルの大きさの上限。鍵や証明書の束を想定した値で、誤って大きなファイルを指定したときにメモリに読み込まない。 */
        public const val MAX_FILE_BYTES: Long = 64L * 1024
    }
}
