package io.eia.platform.filetransfer

import io.eia.platform.filetransfer.store.ObjectStore
import io.eia.platform.filetransfer.store.StoredObject
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/**
 * テスト用の置き場。[crashAt] 回目の書き込みの操作(put・upload・copy・delete)の直前で失敗させ、
 * プロセスが落ちたのと同じく、その後の操作をしない状態を作れる。
 */
@Suppress("ReturnCount") // テスト用の置き場(失敗・ない・ある、で返す)
internal class InMemoryObjectStore : ObjectStore {
    val objects = TreeMap<String, ByteArray>()
    var mutations = 0
        private set
    var crashAt: Int? = null
    var failing: FileTransferError? = null

    fun keys(): List<String> = objects.keys.toList()

    override fun list(prefix: String): Result<List<StoredObject>, FileTransferError> =
        failing?.let { err(it) }
            ?: ok(objects.filterKeys { it.startsWith(prefix) }.map { (key, body) -> StoredObject(key, body.size.toLong()) })

    override fun read(
        key: String,
        maxBytes: Int,
    ): Result<ByteArray?, FileTransferError> = failing?.let { err(it) } ?: ok(objects[key]?.let { it.copyOf(minOf(it.size, maxBytes + 1)) })

    override fun head(key: String): Result<StoredObject?, FileTransferError> =
        failing?.let { err(it) } ?: ok(objects[key]?.let { StoredObject(key, it.size.toLong()) })

    override fun download(
        key: String,
        target: Path,
    ): Result<Boolean, FileTransferError> {
        failing?.let { return err(it) }
        val body = objects[key] ?: return ok(false)
        Files.write(target, body)
        return ok(true)
    }

    override fun upload(
        key: String,
        source: Path,
    ): Result<Unit, FileTransferError> = mutate { objects[key] = Files.readAllBytes(source) }

    override fun put(
        key: String,
        bytes: ByteArray,
    ): Result<Unit, FileTransferError> = mutate { objects[key] = bytes.copyOf() }

    override fun copy(
        sourceKey: String,
        targetKey: String,
    ): Result<Boolean, FileTransferError> {
        val body = objects[sourceKey] ?: return failing?.let { err(it) } ?: ok(false)
        return when (val done = mutate { objects[targetKey] = body.copyOf() }) {
            is Result.Err -> done
            is Result.Ok -> ok(true)
        }
    }

    override fun delete(key: String): Result<Unit, FileTransferError> = mutate { objects.remove(key) }

    private fun mutate(change: () -> Unit): Result<Unit, FileTransferError> {
        failing?.let { return err(it) }
        mutations++
        if (mutations == crashAt) return err(StoreUnavailable("crash"))
        change()
        return ok(Unit)
    }
}
