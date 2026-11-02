plugins {
    id("emitkit.java-conventions")
    `java-test-fixtures`
}

dependencies {
    // api: the SPI signatures expose ApiModel, Tag and Operation.
    api(project(":spec-engine"))
    implementation(project(":common"))
    implementation(libs.jmustache)
    // Test fixtures do not inherit the convention plugin's BOM; spec-engine's jspecify needs it.
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.java.diff.utils)
}

tasks.test {
    // Forward -Dgolden.update=true from the Gradle invocation into the test JVM.
    System.getProperty("golden.update")?.let { systemProperty("golden.update", it) }
}
