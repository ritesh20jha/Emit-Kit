plugins {
    id("emitkit.spring-app")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":domain"))
    implementation(project(":vcs"))
    implementation(project(":spec-engine"))
    implementation(project(":generators:core"))
    implementation(project(":ai"))
    // The generators run inside RunGenerators; GeneratorRegistry constructs each one.
    implementation(project(":generators:workflows"))
    implementation(project(":generators:typescript-sdk"))
    implementation(project(":generators:python-sdk"))
    implementation(project(":generators:java-sdk"))
    implementation(project(":generators:cli"))
    implementation(project(":generators:mcp"))
    implementation(project(":generators:docs"))

    implementation(libs.spring.boot.starter.amqp)
    // spring-amqp marks Jackson optional, so the JSON converter needs it at runtime; its constructor
    // overloads reference JsonMapper, so it is also needed at compile time.
    implementation(libs.jackson.databind)
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.httpclient5)
    implementation(libs.commons.compress)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.postgresql)
}

// The convention plugin disables bootJar until a main class exists; worker-app has one now.
tasks.named("bootJar") { enabled = true }

tasks.withType<Test>().configureEach {
    providers.systemProperty("sandbox.runtime").orNull?.let { systemProperty("sandbox.runtime", it) }
    providers.systemProperty("sandbox.docker-host").orNull?.let { systemProperty("sandbox.docker-host", it) }
}
