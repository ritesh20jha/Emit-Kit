plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(project(":common"))
    api(libs.jspecify)
    // Kept private: swagger/Jackson-2 types must not leak into the public API.
    implementation(libs.swagger.parser)
}
