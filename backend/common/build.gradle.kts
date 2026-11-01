plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(libs.ulid.creator)
    implementation(libs.spring.vault.core)
    implementation(libs.jackson.databind)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.vault)
}
