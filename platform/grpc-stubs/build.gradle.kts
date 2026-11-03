import com.google.protobuf.gradle.id

plugins {
    id("eia.jvm-library")
    id("com.google.protobuf")
}

// contracts/proto の契約から生成したスタブ(ADR-0042 §2)。手で書いたコードは置かない。
dependencies {
    api(libs.protobuf.java)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.kotlinx.coroutines.core)
}

// 生成コードだけのモジュールなので、カバレッジの閾値を当てない(振る舞いは platform/grpc のテストで確かめる)
tasks.matching { it.name == "koverVerify" }.configureEach { enabled = false }

sourceSets {
    main {
        proto {
            srcDir(rootProject.layout.projectDirectory.dir("contracts/proto"))
        }
    }
}

val protocArtifact =
    libs.protobuf.protoc
        .get()
        .toString()
val grpcJavaPlugin =
    libs.grpc.protoc.java
        .get()
        .toString()
val grpcKotlinPlugin = "${libs.grpc.protoc.kotlin.get()}:jdk8@jar"

protobuf {
    protoc { artifact = protocArtifact }
    plugins {
        id("grpc") { artifact = grpcJavaPlugin }
        id("grpckt") { artifact = grpcKotlinPlugin }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                id("grpc")
                id("grpckt")
            }
        }
    }
}
