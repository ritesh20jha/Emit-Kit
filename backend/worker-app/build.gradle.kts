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
}

// The convention plugin disables bootJar until a main class exists; worker-app has one now.
tasks.named("bootJar") { enabled = true }

// Forward -Dsandbox.* from the Gradle invocation into every test JVM (read by DockerSandboxRunnerIT).
tasks.withType<Test>().configureEach {
    listOf("sandbox.runtime", "sandbox.docker-host").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
