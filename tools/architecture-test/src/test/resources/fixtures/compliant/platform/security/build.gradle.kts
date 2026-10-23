dependencies {
    api(project(":platform:api"))
    // implementation(project(":platform:audit")) コメントは依存に数えない
    testImplementation(project(":platform:observability"))
    integrationTestImplementation(project(":platform:test-support"))
}
