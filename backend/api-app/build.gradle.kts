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
    implementation(libs.springdoc.openapi.webmvc)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
}

// The convention plugin disables bootJar until a main class exists; api-app has one now.
tasks.named("bootJar") { enabled = true }
