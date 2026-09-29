package io.eia.platform.audit.canonical

import io.eia.platform.audit.AuditRecord
import io.eia.platform.audit.ChainHash

/**
 * 記録を、ハッシュを計算するためのバイト列に直す方法。形式は `canonical_version` ごとに固定し、変えるときは新しい版を足す
 * (保存済みの記録は、記録したときの版で検証し直す)。仕様は ADR-0017 の表。
 */
public interface CanonicalForm {
    public val version: Int

    public fun encode(record: AuditRecord): ByteArray

    /** `hash` 列の値。[encode] の結果の SHA-256。 */
    public fun hash(record: AuditRecord): ChainHash = ChainHash.sha256(encode(record))
}

/** 版から直列化の方法を選ぶ。検証はこれを通し、記録ごとの `canonical_version` で切り替える。 */
public object CanonicalForms {
    /** 新しく記録するときに使う版。 */
    public val CURRENT: CanonicalForm = CanonicalFormV1

    private val byVersion: Map<Int, CanonicalForm> = listOf<CanonicalForm>(CanonicalFormV1).associateBy { it.version }

    public fun forVersion(version: Int): CanonicalForm? = byVersion[version]
}
