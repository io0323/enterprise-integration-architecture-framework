// 違反: security → observability は本番の依存として許可していない(observability → security の import と合わせて循環になる)
dependencies {
    implementation(project(":platform:observability"))
    // テストの依存は対象外
    testImplementation(project(":platform:audit"))
}
