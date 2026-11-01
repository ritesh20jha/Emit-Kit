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
    implementation(libs.spring.boot.starter.security)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.security.test)
}

// The convention plugin disables bootJar until a main class exists; api-app has one now.
tasks.named("bootJar") { enabled = true }
