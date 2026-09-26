package io.eia.tools.contract

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private const val USAGE =
    "usage: contract-check [--root <dir>] [--baseline <dir>] [--oasdiff <path>] [--markdown <file>]"

/** コマンドライン引数。 */
data class Options(
    val root: Path = Path("."),
    val baseline: Path? = null,
    val oasdiff: Path? = null,
    val markdown: Path? = null,
) {
    companion object {
        /** 解析できなければ null。 */
        fun parse(args: List<String>): Options? {
            if (args.size % 2 != 0) return null
            return args.chunked(2).fold(Options() as Options?) { options, (key, value) ->
                when (key) {
                    "--root" -> options?.copy(root = Path(value))
                    "--baseline" -> options?.copy(baseline = Path(value))
                    "--oasdiff" -> options?.copy(oasdiff = Path(value))
                    "--markdown" -> options?.copy(markdown = Path(value))
                    else -> null
                }
            }
        }
    }
}

/** 検査を実行し、違反があれば 1、なければ 0 を返す。 */
fun runCli(args: List<String>): Int {
    val options = Options.parse(args)
    if (options == null) {
        System.err.println(USAGE)
        return 2
    }
    val result = ContractCheck(options.root, options.baseline, options.oasdiff).run()
    print(Report.text(result))
    options.markdown?.let { file ->
        file.createParentDirectories()
        file.writeText(Report.markdown(result))
    }
    return if (result.violations.isEmpty()) 0 else 1
}

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList()))
}
