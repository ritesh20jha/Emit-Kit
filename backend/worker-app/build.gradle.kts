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
}
