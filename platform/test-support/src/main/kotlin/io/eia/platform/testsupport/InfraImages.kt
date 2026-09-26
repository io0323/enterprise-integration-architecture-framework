package io.eia.platform.testsupport

import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path

/**
 * `infra/local/images.env` のイメージを Testcontainers で使う(ADR-0016 §5)。compose と統合テストの版を揃えるため、
 * イメージ名をテストに直接書かず、必ずここから取得する。
 *
 * ファイルのパスは build-logic(`eia.jvm-library`)がシステムプロパティ [FILE_PROPERTY] で渡す。
 */
public object InfraImages {
    public const val FILE_PROPERTY: String = "eia.images.file"

    private val entries: Map<String, String> by lazy {
        val path =
            System.getProperty(FILE_PROPERTY)
                ?: error("システムプロパティ $FILE_PROPERTY がありません。Gradle の Test タスクから実行してください")
        parse(Files.readAllLines(Path.of(path)))
    }

    /** [key](例 `POSTGRES_IMAGE`)のイメージ。キーがなければ例外にする(黙って `latest` にしない)。 */
    public fun get(key: String): DockerImageName = get(entries, key)

    internal fun get(
        entries: Map<String, String>,
        key: String,
    ): DockerImageName {
        val reference = entries[key] ?: error("infra/local/images.env に $key がありません")
        return toDockerImageName(reference)
    }

    /** `KEY=VALUE` の行を読む。空行と `#` で始まる行は無視する。 */
    internal fun parse(lines: List<String>): Map<String, String> =
        lines
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "images.env の行の形式が不正です: $line" }
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }

    /**
     * `<repository>:<tag>@sha256:<digest>` を Testcontainers のイメージ名にする。
     * Testcontainers はタグとダイジェストの併記を解釈できないため、固定の実体であるダイジェストだけを残す。
     */
    internal fun toDockerImageName(reference: String): DockerImageName {
        val digestAt = reference.indexOf("@sha256:")
        require(digestAt > 0) { "images.env のイメージはダイジェストで固定してください: $reference" }
        val nameWithTag = reference.substring(0, digestAt)
        val lastSlash = nameWithTag.lastIndexOf('/')
        val tagColon = nameWithTag.lastIndexOf(':')
        val repository = if (tagColon > lastSlash) nameWithTag.substring(0, tagColon) else nameWithTag
        return DockerImageName.parse(repository + reference.substring(digestAt))
    }
}
