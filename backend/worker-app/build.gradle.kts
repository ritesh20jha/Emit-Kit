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
    // The JSON converter is configured with a Jackson 3 ObjectMapper type, so it must compile here.
    implementation(libs.jackson.databind)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.rabbitmq)
}

// The convention plugin disables bootJar until a main class exists; worker-app has one now.
tasks.named("bootJar") { enabled = true }
