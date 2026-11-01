plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(project(":common"))
    // S3Client is in ArtifactStorage's public API, so consumers need it at compile time.
    api(platform(libs.aws.sdk.bom))
    api(libs.aws.s3)
    implementation(libs.spring.boot.core)
    // Repository interfaces and entity annotations are public API, so consumers need JPA at compile time.
    api(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
}
