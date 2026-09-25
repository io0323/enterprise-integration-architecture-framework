package io.eia.shared.kernel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** P00: kotest が jvm / js / linuxX64(/ macosArm64)の各ターゲットで実行できることの確認。P01 で実テストに置き換える。 */
class KernelSmokeSpec :
    FunSpec({
        test("kotest が全ターゲットで実行できる") {
            listOf(1, 2, 3).sum() shouldBe 6
        }
    })
