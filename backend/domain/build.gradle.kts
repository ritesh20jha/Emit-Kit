plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(project(":common"))
    // S3Client is in ArtifactStorage's public API, so consumers need it at compile time.
    api(platform(libs.aws.sdk.bom))
    api(libs.aws.s3)
    implementation(libs.spring.boot.core)
    testImplementation(libs.testcontainers.core)
}
