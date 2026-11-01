plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(project(":common"))
    api(libs.jspecify)
}
