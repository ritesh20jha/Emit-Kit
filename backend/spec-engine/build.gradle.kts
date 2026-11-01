plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(project(":common"))
    api("org.jspecify:jspecify")
}
