plugins {
    id("emitkit.java-conventions")
}

dependencies {
    implementation(libs.ulid.creator)
    implementation(libs.spring.vault.core)
    implementation(libs.jackson.databind)
    // Boot is provided by the application; these are needed only to compile the config and health beans.
    compileOnly(libs.spring.boot.core)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.health)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.vault)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.health)
}
