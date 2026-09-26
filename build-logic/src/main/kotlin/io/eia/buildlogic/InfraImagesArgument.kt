package io.eia.buildlogic

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * `infra/local/images.env` のパスをテスト JVM のシステムプロパティ `eia.images.file` で渡す(ADR-0016 §5)。
 * ファイルは相対パスで入力に宣言するため、絶対パスがビルドキャッシュのキーに入らず、内容の変更時だけテストが再実行される。
 */
abstract class InfraImagesArgument : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val imagesFile: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf("-Deia.images.file=${imagesFile.get().asFile.absolutePath}")
}
