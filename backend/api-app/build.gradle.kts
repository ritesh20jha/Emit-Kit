plugins {
    id("emitkit.spring-app")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":vcs"))
    implementation(project(":spec-engine"))
    implementation(project(":common"))
    implementation(project(":ai"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.session.data.redis)
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.springdoc.openapi.webmvc)
    implementation(libs.spring.boot.starter.amqp)
    // spring-amqp marks Jackson optional, so the JSON converter needs it at runtime; its constructor
    // overloads reference JsonMapper, so it is also needed at compile time.
    implementation(libs.jackson.databind)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.vault)
    testImplementation(libs.spring.vault.core)
    testImplementation(libs.wiremock.standalone)
    testImplementation(testFixtures(project(":vcs")))
}

// -Dopenapi.update=true (scripts/export-openapi.sh) makes OpenApiSnapshotTest rewrite the committed spec.
tasks.withType<Test>().configureEach {
    providers.systemProperty("openapi.update").orNull?.let { systemProperty("openapi.update", it) }
}

// The convention plugin disables bootJar until a main class exists; api-app has one now.
tasks.named("bootJar") { enabled = true }
