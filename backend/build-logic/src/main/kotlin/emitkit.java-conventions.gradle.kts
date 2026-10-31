import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `java-library`
    id("com.diffplug.spotless")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform(catalog.findLibrary("spring-boot-bom").get()))

    testImplementation(platform(catalog.findLibrary("junit-bom").get()))
    testImplementation(platform(catalog.findLibrary("testcontainers-bom").get()))
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testImplementation(catalog.findLibrary("assertj").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    // Emit package-info.class even without annotations, so ArchUnit sees every module package.
    options.compilerArgs.add("-Xpkginfo:always")
}

spotless {
    java {
        googleJavaFormat(catalog.findVersion("google-java-format").get().requiredVersion)
    }
}
