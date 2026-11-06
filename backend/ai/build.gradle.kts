plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(platform(libs.spring.ai.bom))
    implementation(project(":common"))
    implementation(project(":generators:core"))
    implementation(libs.spring.ai.client.chat)
    implementation(libs.spring.ai.google.genai)

    // Test-only SLF4J binding; the BOM manages the version. Avoids the NOP-logger warning.
    testRuntimeOnly(platform(libs.spring.boot.bom))
    testRuntimeOnly(libs.logback.classic)
}
