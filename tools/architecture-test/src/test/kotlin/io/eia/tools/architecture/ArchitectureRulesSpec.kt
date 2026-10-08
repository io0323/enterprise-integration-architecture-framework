package io.eia.tools.architecture

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/** ルール自体の検証: 違反サンプルで失敗し、準拠サンプルで成功すること。 */
class ArchitectureRulesSpec :
    FunSpec({
        val fixtures = File(CodeBase.fromSystemProperty().root, "tools/architecture-test/src/test/resources/fixtures")
        val violations = CodeBase(fixtures.resolve("violations"))
        val compliant = CodeBase(fixtures.resolve("compliant"))

        fun List<Violation>.fileNames(): List<String> = map { it.path.substringAfterLast('/') }

        fun List<Violation>.rulesOf(fileName: String): Set<String> = filter { it.path.endsWith("/$fileName") }.map { it.rule }.toSet()

        context("違反サンプルを検出する") {
            test("サービスをディレクトリから列挙する") {
                violations.services shouldBe listOf("other", "sample")
            }

            test("依存方向: domain → application / adapters、adapters → app") {
                val found = ArchitectureRules.layerDependencies(violations, "sample")
                found.fileNames() shouldContainExactlyInAnyOrder
                    listOf("DomainDependsOnAdapter.kt", "DomainDependsOnAdapter.kt", "AdapterDependsOnApp.kt")
                shouldThrow<AssertionError> { found.assertNone() }.message shouldContain "依存方向"
            }

            test("サービス間依存と、application から platform への依存") {
                ArchitectureRules.serviceIsolation(violations, "other").fileNames() shouldBe listOf("CrossServiceDependency.kt")
                ArchitectureRules.serviceIsolation(violations, "sample").fileNames() shouldBe listOf("UsesFrameworks.kt")
            }

            test("配置と一致しないパッケージ") {
                ArchitectureRules.packageMatchesLocation(violations, "sample").fileNames() shouldBe listOf("MisplacedPackage.kt")
            }

            test("platform から services への依存") {
                ArchitectureRules.platformIndependentOfServices(violations).fileNames() shouldBe
                    listOf("PlatformDependsOnService.kt")
            }

            test("許可していない platform 間の依存(import・完全修飾名・build.gradle.kts。テストの依存は除く)") {
                val found = PlatformDependencyRules.unexpectedDependencies(violations)
                found.map { it.path } shouldContainExactlyInAnyOrder
                    listOf(
                        "platform/observability/src/main/kotlin/io/eia/platform/observability/DependsOnSecurity.kt",
                        "platform/reliability/src/main/kotlin/io/eia/platform/reliability/QualifiedAudit.kt",
                        "platform/security/build.gradle.kts",
                    )
                found.map { it.detail.substringBefore('(') } shouldContainExactlyInAnyOrder
                    listOf("observability → security", "reliability → audit", "security → observability")
            }

            test("platform 間の循環") {
                PlatformDependencyRules.cycles(violations).map { it.detail } shouldBe
                    listOf("observability → security → observability")
            }

            test("循環を 1 回ずつ、名前が最小のモジュールから列挙する") {
                val graph = mapOf("c" to setOf("a"), "a" to setOf("b"), "b" to setOf("c", "d"), "d" to setOf("d"))
                PlatformDependencyRules.findCycles(graph) shouldContainExactlyInAnyOrder listOf(listOf("a", "b", "c"), listOf("d"))
            }

            test("commonMain の禁止 import(integration-sdk の io.ktor.client は許可)") {
                val found = ArchitectureRules.commonMainPurity(violations)
                found.map { it.detail.substringAfter("import ").substringBefore('(') } shouldContainExactlyInAnyOrder
                    listOf(
                        "java.time.Instant",
                        "kotlin.jvm.Synchronized",
                        "io.ktor.server.application.Application",
                        "org.apache.kafka.clients.producer.KafkaProducer",
                        "org.jetbrains.exposed.sql.Table",
                        "org.koin.core.module.Module",
                        "io.ktor.server.engine.EmbeddedServer",
                    )
            }

            test("kotlin.Result の import・暗黙の解決・完全修飾名と runCatching") {
                val found = ArchitectureRules.noKotlinResult(violations)
                found.rulesOf("UsesKotlinResult.kt") shouldBe setOf("kotlin.Result 禁止", "runCatching 禁止")
                found.rulesOf("ImplicitKotlinResult.kt") shouldBe setOf("kotlin.Result 禁止")
                found.filter { it.path.endsWith("/ImplicitKotlinResult.kt") }.size shouldBe 2
                shouldThrow<AssertionError> { found.assertNone() }
            }

            test("shared/kernel・canonical-model・resilience の許可リスト外の import") {
                ArchitectureRules.sharedImportAllowList(violations).map {
                    it.detail.substringAfter("import ").substringBefore('(')
                } shouldContainExactlyInAnyOrder
                    listOf(
                        "io.eia.platform.observability.Tracer",
                        "kotlinx.datetime.LocalDate",
                        "kotlinx.coroutines.delay",
                        "kotlinx.serialization.Serializable",
                    )
            }

            test("テスト以外のソースセットから platform/test-support を参照する") {
                ArchitectureRules.testSupportOnlyFromTests(violations).fileNames() shouldContainExactlyInAnyOrder
                    listOf("UsesTestSupportInMain.kt", "UsesTestSupportQualified.kt")
            }

            test("OTel SDK を platform/observability と services/*/app 以外の本番コードで使う") {
                ArchitectureRules.otelSdkOnlyInAllowedModules(violations).fileNames() shouldContainExactlyInAnyOrder
                    listOf("UsesOtelSdk.kt", "UsesOtelSdkQualified.kt")
            }

            test("Nimbus JOSE+JWT を platform/security 以外の本番コードで使う") {
                ArchitectureRules.nimbusOnlyInSecurity(violations).fileNames() shouldContainExactlyInAnyOrder
                    listOf("UsesNimbus.kt", "UsesNimbusQualified.kt")
            }

            test("Kafka・Avro を許可したモジュールの外の本番コードで使う・Apicurio の公式のライブラリを本番コードで使う") {
                ArchitectureRules.messagingLibrariesOnlyInAllowedModules(violations).fileNames() shouldContainExactlyInAnyOrder
                    // UsesFrameworks.kt は application(commonMain)で Kafka を import する既存の違反の例(commonMainPurity でも検出する)
                    listOf("UsesKafka.kt", "UsesAvro4kQualified.kt", "UsesAvro.kt", "UsesApicurio.kt", "UsesFrameworks.kt")
            }

            test("services の本番コードで Resilience(...) を直接作る(完全修飾名を含む)") {
                ArchitectureRules.resilienceOnlyThroughMetrics(violations).fileNames() shouldContainExactlyInAnyOrder
                    listOf("CreatesResilience.kt", "CreatesResilienceQualified.kt")
            }

            test("services の domain と application(テストを含む)で Canonical Model を import・完全修飾名で参照する") {
                val found = ArchitectureRules.canonicalModelOutsideDomainAndApplication(violations)
                found.fileNames() shouldContainExactlyInAnyOrder
                    listOf("UsesCanonicalModel.kt", "UsesCanonicalModel.kt", "CanonicalInTest.kt")
            }

            test("Retryable と NonRetryable の両方を直接・間接に実装する型") {
                ArchitectureRules
                    .domainErrorKindIsExclusive(
                        violations,
                    ).map { it.detail.substringBefore(' ') } shouldContainExactlyInAnyOrder
                    listOf("BothKinds", "IndirectBoth")
            }
        }

        context("準拠サンプルでは違反がない") {
            test("サービスごとのルール") {
                compliant.services shouldBe listOf("good", "two-words")
                compliant.services.forEach { service ->
                    ArchitectureRules.layerDependencies(compliant, service).shouldBeEmpty()
                    ArchitectureRules.serviceIsolation(compliant, service).shouldBeEmpty()
                    ArchitectureRules.packageMatchesLocation(compliant, service).shouldBeEmpty()
                }
            }

            test("サービスのパッケージは、ディレクトリ名から - を除いた io.eia.<service>(例 legacy-sim → io.eia.legacysim)") {
                ArchitectureRules.servicePackage("order") shouldBe "io.eia.order"
                ArchitectureRules.servicePackage("legacy-order-acl") shouldBe "io.eia.legacyorderacl"
            }

            test("全体のルール(kernel の Result と @JvmInline は許可)") {
                ArchitectureRules.platformIndependentOfServices(compliant).shouldBeEmpty()
                ArchitectureRules.commonMainPurity(compliant).shouldBeEmpty()
                ArchitectureRules.noKotlinResult(compliant).shouldBeEmpty()
            }

            test("shared の許可リスト・DomainError 分類の排他・test-support の参照元・OTel SDK と Nimbus の配置") {
                ArchitectureRules.sharedImportAllowList(compliant).shouldBeEmpty()
                ArchitectureRules.testSupportOnlyFromTests(compliant).shouldBeEmpty()
                ArchitectureRules.domainErrorKindIsExclusive(compliant).shouldBeEmpty()
                ArchitectureRules.otelSdkOnlyInAllowedModules(compliant).shouldBeEmpty()
                ArchitectureRules.nimbusOnlyInSecurity(compliant).shouldBeEmpty()
                ArchitectureRules.canonicalModelOutsideDomainAndApplication(compliant).shouldBeEmpty()
                ArchitectureRules.resilienceOnlyThroughMetrics(compliant).shouldBeEmpty()
                ArchitectureRules.messagingLibrariesOnlyInAllowedModules(compliant).shouldBeEmpty()
            }

            test("platform 間の依存は許可した一覧だけで、循環がない(テストの依存とコメントは数えない)") {
                PlatformDependencyRules.unexpectedDependencies(compliant).shouldBeEmpty()
                PlatformDependencyRules.cycles(compliant).shouldBeEmpty()
                PlatformDependencyRules.dependencies(compliant).map { it.from to it.to }.toSet() shouldBe
                    setOf("security" to "reliability")
            }

            test("許可した一覧そのものに循環がない") {
                PlatformDependencyRules.findCycles(PlatformDependencyRules.ALLOWED).shouldBeEmpty()
            }
        }

        context("KotlinSourceText") {
            test("コメントと文字列リテラルを除去する") {
                val code =
                    KotlinSourceText.stripCommentsAndStrings(
                        "val a = \"runCatching {\" // runCatching {\n/* kotlin.Result */ val b = '\"'\nval c = \"\"\"Result<\"\"\"",
                    )
                code shouldContain "val a ="
                code shouldContain "val c ="
                listOf("runCatching", "kotlin.Result", "Result<").forEach { (it in code) shouldBe false }
            }
        }
    })
