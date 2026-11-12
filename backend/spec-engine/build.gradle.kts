plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(project(":common"))
    api(libs.jspecify)
    // Kept private: swagger/Jackson-2 types must not leak into the public API.
    implementation(libs.swagger.parser)
    implementation(libs.jackson.databind)
    // Source line numbers for lint issues; version managed by the Spring Boot BOM.
    implementation(libs.snakeyaml)
    // SemVer parsing and comparison for the version calculator; kept private (String in the API).
    implementation(libs.java.semver)
}
