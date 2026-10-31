plugins {
    id("emitkit.spring-app")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":vcs"))
    implementation(project(":spec-engine"))
    implementation(project(":common"))
    implementation(project(":ai"))
}
