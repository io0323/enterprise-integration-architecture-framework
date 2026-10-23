package io.eia.tools.contract.rules

import io.eia.tools.contract.CatalogDocument
import io.eia.tools.contract.Contracts

/**
 * 契約のファイル(リポジトリルートからの相対パス)と、それを使う連携(カタログのエントリ)の対応(ADR-0034)。
 * 連携が使うのは、カタログの `contract`(OpenAPI・AsyncAPI・ファイルの仕様)と、AsyncAPI の payload が参照する Avro・デバイスの形式のスキーマ。
 * ライフサイクルの検査(契約のファイルの削除)・互換性の検査と状態の連動・互換性の検査の例外の条件で使う。
 */
object ContractUsage {
    fun of(contracts: Contracts): Map<String, List<CatalogDocument>> {
        val asyncApis = contracts.asyncApis.associateBy { it.path }
        return contracts.catalogs
            .flatMap { catalog ->
                val contract = catalog.entry.contract ?: return@flatMap emptyList()
                val payloads = asyncApis[contract]?.let { AsyncApiRules.payloadReferences(contracts, it) }.orEmpty()
                (listOf(contract) + payloads).map { it to catalog }
            }.groupBy({ it.first }, { it.second })
    }
}
