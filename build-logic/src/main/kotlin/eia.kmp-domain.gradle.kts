import io.eia.buildlogic.configureKmp

// services/*/{domain,application} 用。ソースは commonMain、既定ターゲットは jvm のみ(ADR-0004)。
// js / native を追加するときは、モジュールの build.gradle.kts に `eiaTargets { js() }` を 1 行書く。
configureKmp { jvm() }
