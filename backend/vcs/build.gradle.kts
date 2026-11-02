plugins {
    id("emitkit.java-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":common"))

    testFixturesImplementation(project(":common"))
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj)

    testImplementation(testFixtures(project(":vcs")))
}
