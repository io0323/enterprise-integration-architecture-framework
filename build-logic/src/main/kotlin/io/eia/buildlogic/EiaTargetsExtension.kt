package io.eia.buildlogic

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import javax.inject.Inject

/**
 * KMP ターゲット集合を宣言する DSL(ADR-0004)。
 *
 * ```
 * eiaTargets {
 *     js()      // 1 行で追加できる
 *     native()  // linuxX64 + macosArm64
 * }
 * ```
 * 既定のターゲットは convention plugin 側で宣言する(`eia.kmp-library` は 4 ターゲット、`eia.kmp-domain` は jvm のみ)。
 */
open class EiaTargetsExtension
    @Inject
    constructor(
        private val kotlin: KotlinMultiplatformExtension,
    ) {
        fun jvm() {
            kotlin.jvm()
        }

        /** Kotlin/JS(IR)。テストは Node.js で実行する(ブラウザ不要)。 */
        fun js() {
            kotlin.js {
                nodejs()
            }
        }

        fun linuxX64() {
            kotlin.linuxX64()
        }

        fun macosArm64() {
            kotlin.macosArm64()
        }

        /** ADR-0004 の native ターゲット(linuxX64, macosArm64)。 */
        fun native() {
            linuxX64()
            macosArm64()
        }

        /** shared 配下の標準構成: jvm, js(IR), linuxX64, macosArm64。 */
        fun all() {
            jvm()
            js()
            native()
        }
    }
