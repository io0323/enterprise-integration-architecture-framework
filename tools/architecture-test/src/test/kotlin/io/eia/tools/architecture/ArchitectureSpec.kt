package io.eia.tools.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * リポジトリ全体に対するアーキテクチャ規約の検査(CLAUDE.md §4、MODULE_DESIGN §2、ADR-0004)。
 * サービスごとの検査は services 配下のディレクトリを列挙して生成するため、サービス追加時にテストの修正は不要。
 */
class ArchitectureSpec :
    FunSpec({
        val codeBase = CodeBase.fromSystemProperty()

        test("services 配下のサービスを検出できる") {
            codeBase.services.shouldNotBeEmpty()
        }

        codeBase.services.forEach { service ->
            context("services/$service") {
                test("依存方向は domain ← application ← adapters ← app の向きのみ") {
                    ArchitectureRules.layerDependencies(codeBase, service).assertNone()
                }
                test("他サービスに依存せず、domain / application は platform に依存しない") {
                    ArchitectureRules.serviceIsolation(codeBase, service).assertNone()
                }
                test("パッケージが io.eia.$service.<layer> と配置に一致する") {
                    ArchitectureRules.packageMatchesLocation(codeBase, service).assertNone()
                }
            }
        }

        test("platform は services に依存しない") {
            ArchitectureRules.platformIndependentOfServices(codeBase).assertNone()
        }

        test("platform 間の本番の依存は許可した一覧だけ(MODULE_DESIGN §2)") {
            PlatformDependencyRules.unexpectedDependencies(codeBase).assertNone()
        }

        test("platform 間の依存に循環がない") {
            PlatformDependencyRules.cycles(codeBase).assertNone()
        }

        test("commonMain は ADR-0004 の禁止 import を含まない") {
            ArchitectureRules.commonMainPurity(codeBase).assertNone()
        }

        test("kotlin.Result と runCatching を使わない") {
            ArchitectureRules.noKotlinResult(codeBase).assertNone()
        }

        test("shared/kernel・canonical-model・resilience はフレームワークに依存しない(許可リスト)") {
            ArchitectureRules.sharedImportAllowList(codeBase).assertNone()
        }

        test("platform/test-support はテストのソースセットからだけ参照する") {
            ArchitectureRules.testSupportOnlyFromTests(codeBase).assertNone()
        }

        test("OTel SDK は platform/observability と services/*/app だけで使う(ADR-0004 §4)") {
            ArchitectureRules.otelSdkOnlyInAllowedModules(codeBase).assertNone()
        }

        test("Nimbus JOSE+JWT は platform/security だけで使う(ADR-0019 §1)") {
            ArchitectureRules.nimbusOnlyInSecurity(codeBase).assertNone()
        }

        test("services の本番コードは Resilience(...) を直接作らない(ResilienceMetrics 経由。ADR-0021 §7)") {
            ArchitectureRules.resilienceOnlyThroughMetrics(codeBase).assertNone()
        }

        test("services の domain と application は Canonical Model に依存しない(ADR-0010 Decision 7)") {
            ArchitectureRules.canonicalModelOutsideDomainAndApplication(codeBase).assertNone()
        }

        test("Retryable と NonRetryable の両方を実装する型がない") {
            ArchitectureRules.domainErrorKindIsExclusive(codeBase).assertNone()
        }
    })
