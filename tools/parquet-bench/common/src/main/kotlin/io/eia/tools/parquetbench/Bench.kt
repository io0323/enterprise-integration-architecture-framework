package io.eia.tools.parquetbench

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.system.exitProcess

/** 1 つの候補の書き出し。[rows] を [target] に Parquet(zstd)で書く。 */
fun interface ParquetWriterCandidate {
    fun write(
        rows: Sequence<SalesRow>,
        target: Path,
    )
}

/**
 * 計測の本体。引数は `<出力のディレクトリ> <件数>`。
 * 同じ JVM で 2 回書き(1 回目はクラスの読み込みとネイティブの展開を含む。2 回目は常駐のサービスで毎日書くときに近い)、
 * 結果を 1 行の JSON で標準出力に出す。
 */
object Bench {
    fun run(
        candidate: String,
        args: Array<String>,
        footerReaders: Map<String, (Path) -> Long> = emptyMap(),
        writer: ParquetWriterCandidate,
    ) {
        if (args.size != 2) {
            System.err.println("使い方: bench <出力のディレクトリ> <件数>")
            exitProcess(2)
        }
        val outDir = Path(args[0])
        val rows = args[1].toInt()
        Files.createDirectories(outDir)
        val arch = System.getProperty("os.arch")
        val cold = outDir.resolve("$candidate-$arch-$rows-cold.parquet")
        val warm = outDir.resolve("$candidate-$arch-$rows.parquet")
        val result =
            try {
                val coldMs = timed { writer.write(SalesRows.generate(rows), cold.also { it.deleteIfExists() }) }
                val warmMs = timed { writer.write(SalesRows.generate(rows), warm.also { it.deleteIfExists() }) }
                cold.deleteIfExists()
                mapOf(
                    "ok" to true,
                    "coldMs" to coldMs,
                    "warmMs" to warmMs,
                    "fileBytes" to warm.fileSize(),
                    "file" to warm.fileName.toString(),
                    // 受け側の検査(件数)と同じく、フッタだけを読む。同じクラスパスで読めるかを確かめる
                    "footerRows" to
                        footerReaders.mapValues { (_, read) ->
                            when (val read = attempt { read(warm) }) {
                                is Attempt.Ok -> read.value
                                is Attempt.Failed -> read.error.take(MAX_ERROR)
                            }
                        },
                )
            } catch (expected: Throwable) {
                // 失敗も結果として残す(例: ネイティブが読み込めない、Hadoop のクラスがない)
                expected.printStackTrace()
                mapOf("ok" to false, "error" to "${expected::class.java.name}: ${expected.message}".take(MAX_ERROR))
            }
        val memory =
            mapOf(
                "heapPeakBytes" to peak(MemoryType.HEAP),
                "nonHeapPeakBytes" to peak(MemoryType.NON_HEAP),
                "processPeakRssBytes" to processPeakRss(),
                "cgroupPeakBytes" to cgroupPeak(),
                "cgroupMaxBytes" to cgroupMax(),
            )
        val base =
            mapOf(
                "candidate" to candidate,
                "arch" to arch,
                "rows" to rows,
                "java" to System.getProperty("java.runtime.version"),
                "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            )
        println(json(base + result + memory))
    }

    private const val MAX_ERROR = 500

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun peak(type: MemoryType): Long =
        ManagementFactory
            .getMemoryPoolMXBeans()
            .filter { it.type == type }
            .sumOf { it.peakUsage?.used ?: 0L }

    /** プロセスの常駐メモリの最大(Linux の VmHWM)。ヒープの外(ネイティブ・直接バッファ)を含む。 */
    private fun processPeakRss(): Long? {
        val status = Path("/proc/self/status")
        if (!status.exists()) return null
        return status
            .readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.removePrefix("VmHWM:")
            ?.trim()
            ?.removeSuffix("kB")
            ?.trim()
            ?.toLong()
            ?.times(1024)
    }

    /** コンテナ(cgroup v2)のメモリの上限(docker run --memory)。 */
    private fun cgroupMax(): Long? {
        val max = Path("/sys/fs/cgroup/memory.max")
        return if (max.exists()) max.readText().trim().toLongOrNull() else null
    }

    /** コンテナ(cgroup v2)のメモリの最大。ページキャッシュを含む。 */
    private fun cgroupPeak(): Long? {
        val peak = Path("/sys/fs/cgroup/memory.peak")
        return if (peak.exists()) peak.readText().trim().toLongOrNull() else null
    }

    fun json(values: Map<String, Any?>): String =
        values.entries.joinToString(",", "{", "}") { (key, value) ->
            val rendered =
                when (value) {
                    null -> "null"
                    is Number, is Boolean -> value.toString()
                    is Map<*, *> -> json(value.entries.associate { it.key.toString() to it.value })
                    is List<*> -> value.joinToString(",", "[", "]") { item -> if (item is Number) "$item" else quote("$item") }
                    else -> quote(value.toString())
                }
            "${quote(key)}:$rendered"
        }

    private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
