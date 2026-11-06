plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(platform(libs.spring.ai.bom))
    implementation(project(":common"))
    implementation(project(":generators:core"))
    implementation(libs.spring.ai.client.chat)
    implementation(libs.spring.ai.google.genai)
    implementation(libs.jspecify)
    // Boot is provided by the application; these annotations only bind AiProperties.
    compileOnly(libs.spring.boot.core)
    testImplementation(libs.spring.boot.core)

    // Test-only SLF4J binding. Avoids the NOP-logger warning.
    testRuntimeOnly(libs.logback.classic)
}
