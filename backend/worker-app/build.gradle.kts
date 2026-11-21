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
    // PR bodies and breaking-change comments are markdown, rendered from templates without escaping.
    implementation(libs.jmustache)
    implementation(libs.commons.compress)
    // Live run logs: lines are published to Redis and buffered to Postgres. Boot's module supplies
    // the auto-configured StringRedisTemplate; lettuce is its optional client.
    implementation(libs.spring.boot.data.redis)
    implementation(libs.spring.data.redis)
    implementation(libs.lettuce.core)
    // StringRedisTemplate for the VCS quota store. Boot autoconfigures it from this starter.
    implementation(libs.spring.boot.starter.data.redis)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility)
    // InMemoryVcsClient, the VCS fake the step tests run against.
    testImplementation(testFixtures(project(":vcs")))
    // GitHubMock: the GitHub REST endpoints the pipeline calls, served in-process.
    testImplementation(libs.wiremock.standalone)
    // The petstore spec and other shared OpenAPI fixtures for the end-to-end test.
    testImplementation(testFixtures(project(":generators:core")))
}

// The convention plugin disables bootJar until a main class exists; worker-app has one now.
tasks.named("bootJar") { enabled = true }

tasks.withType<Test>().configureEach {
    providers.systemProperty("sandbox.runtime").orNull?.let { systemProperty("sandbox.runtime", it) }
    providers.systemProperty("sandbox.docker-host").orNull?.let { systemProperty("sandbox.docker-host", it) }
    providers.systemProperty("e2e.required").orNull?.let { systemProperty("e2e.required", it) }
    providers.systemProperty("emitkit.toolchain.image").orNull?.let { systemProperty("emitkit.toolchain.image", it) }
}
