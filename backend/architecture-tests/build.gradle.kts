plugins {
    id("emitkit.java-conventions")
}

dependencies {
    testImplementation(project(":common"))
    testImplementation(project(":domain"))
    testImplementation(project(":vcs"))
    testImplementation(project(":spec-engine"))
    testImplementation(project(":generators:core"))
    testImplementation(project(":generators:workflows"))
    testImplementation(project(":generators:typescript-sdk"))
    testImplementation(project(":generators:python-sdk"))
    testImplementation(project(":generators:mcp"))
    testImplementation(project(":generators:cli"))
    testImplementation(project(":ai"))
    testImplementation(project(":api-app"))
    testImplementation(project(":worker-app"))
    testImplementation(libs.archunit)
}
