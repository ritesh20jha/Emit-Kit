plugins {
    id("emitkit.java-conventions")
}

dependencies {
    api(project(":generators:core"))
    implementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":generators:core")))
}
