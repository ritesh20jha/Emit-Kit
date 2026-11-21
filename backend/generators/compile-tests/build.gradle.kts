plugins {
    id("emitkit.java-conventions")
}

dependencies {
    testImplementation(project(":generators:core"))
    testImplementation(testFixtures(project(":generators:core")))
    testImplementation(project(":generators:typescript-sdk"))
    testImplementation(project(":generators:python-sdk"))
    testImplementation(project(":generators:java-sdk"))
    testImplementation(project(":generators:cli"))
    testImplementation(project(":generators:mcp"))
    testImplementation(libs.commons.compress)
    testImplementation(libs.jackson.databind)
}

// The test JVM does not inherit Gradle's system properties, so forward the toolchain settings explicitly.
tasks.withType<Test>().configureEach {
    for (name in listOf("emitkit.toolchain.image", "emitkit.sandbox.runtime", "compile-tests.required")) {
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
}
