dependencies {
    api(project(":shared:resilience"))
    // implementation(libs.aws.sdk.s3) コメントは宣言に数えない
    integrationTestImplementation(libs.aws.sdk.s3)
}
