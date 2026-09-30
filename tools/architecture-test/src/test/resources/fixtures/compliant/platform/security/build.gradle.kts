dependencies {
    api(project(":platform:reliability"))
    // implementation(project(":platform:audit")) コメントは依存に数えない
    testImplementation(project(":platform:observability"))
    integrationTestImplementation(project(":platform:test-support"))
}
