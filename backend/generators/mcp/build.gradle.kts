plugins {
    id("emitkit.java-conventions")
}

dependencies {
    api(project(":generators:core"))
    testImplementation(testFixtures(project(":generators:core")))
    testImplementation(libs.json.schema.validator)
}
