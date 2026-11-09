plugins {
    id("emitkit.java-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":common"))
    implementation(libs.kohsuke.github.api)
    implementation(libs.okhttp)
    // Boot is provided by the application; needed only to compile GitHubProperties.
    compileOnly(libs.spring.boot.core)

    testFixturesImplementation(project(":common"))
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj)

    testImplementation(testFixtures(project(":vcs")))
    testImplementation(libs.wiremock.standalone)
}
