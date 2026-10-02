package io.eia.tools.schemapublish

import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File

/** `make schemas` と同じ実行([run])を、実際の Apicurio Registry 3(FULL_TRANSITIVE)に対して行う。 */
class SchemaPublishIT :
    FunSpec({
        val registry = ApicurioRegistryContainer().also { it.start() }
        afterSpec { registry.stop() }

        val repository = File(checkNotNull(System.getProperty("eia.contractsDir"))).parentFile

        test("リポジトリの契約を登録できる。2 回目も成功する(同じ内容なら新しい版を作らない)") {
            run(listOf("--registry", registry.baseUrl, "--root", repository.path)) shouldBe ExitCode.OK
            run(listOf("--registry", registry.baseUrl, "--root", repository.path)) shouldBe ExitCode.OK
        }

        test("互換性のない変更(default のない項目の追加)はレジストリが拒否し、終了コード 1") {
            val copy = tempdir()
            File(repository, "contracts").copyRecursively(File(copy, "contracts"))
            run(listOf("--registry", registry.baseUrl, "--root", copy.path)) shouldBe ExitCode.OK

            val schema = File(copy, "contracts/avro/sales/OrderCancelled.avsc")
            schema.writeText(
                schema.readText().replaceFirst("\"fields\": [", "\"fields\": [{\"name\": \"breaking\", \"type\": \"string\"},"),
            )
            run(listOf("--registry", registry.baseUrl, "--root", copy.path)) shouldBe ExitCode.REJECTED
        }

        test("レジストリに接続できなければ終了コード 2") {
            run(listOf("--registry", "http://127.0.0.1:9/apis/registry/v3", "--root", repository.path)) shouldBe ExitCode.CANNOT_RUN
        }
    })
