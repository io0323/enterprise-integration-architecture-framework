kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:integration-sdk:mqtt"))
            // 違反: HTTP の部分(libcurl などを巻き込む)
            implementation(project(":shared:integration-sdk:http"))
        }
    }
}
