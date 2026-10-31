plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(project(":common"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.s3)
    // S3 SDK needs an HTTP client at runtime; url-connection-client is the lightest one.
    implementation(libs.aws.url.connection.client)
    implementation(libs.spring.boot.core)
    testImplementation(libs.testcontainers.core)
}
