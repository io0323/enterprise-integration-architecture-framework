package io.eia.tools.devicesimulator

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Instant

private val CONTRACTS: Path = Path.of(checkNotNull(System.getProperty("eia.contractsDir")) { "Gradle の test タスクから実行してください" })
private val mapper = JsonMapper.builder().build()
private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

private fun violations(
    version: Int,
    payload: String,
): List<String> {
    val schema = registry.getSchema(mapper.readTree(CONTRACTS.resolve("devices/telemetry.v$version.schema.json").readText()))
    return schema.validate(mapper.readTree(payload)).map { it.toString() }
}

/** シミュレータが作るペイロードが、契約(contracts/devices の JSON Schema)に適合する(Contract First)。 */
class PayloadContractSpec :
    FunSpec({
        val session = BootSession()
        val at = Instant.parse("2026-10-25T01:02:03.456789Z")

        test("形式の版 1・2 のどちらも、時刻ありとなしで契約に適合する") {
            for (version in SimulatorOptions.PAYLOAD_VERSIONS) {
                for (deviceTime in listOf(at, null)) {
                    violations(version, Telemetry.payload(version, session, seq = 7, deviceTime = deviceTime)).shouldBeEmpty()
                }
            }
        }

        test("契約の検査が効いていること(boot のないペイロードは違反)") {
            violations(2, """{"v":2,"seq":1,"readings":[{"name":"temperature","value":1.0}]}""").shouldNotBeEmpty()
        }
    })
