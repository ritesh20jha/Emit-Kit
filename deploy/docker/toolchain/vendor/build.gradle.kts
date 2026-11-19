// Build-time only: fetches the Jackson 2 jars that generated Java SDKs import (javac -cp /opt/vendor/*)
// and the google-java-format all-deps jar. Runs in the Dockerfile vendor stage; the image has no network.
val jacksonVersion = "2.17.2" // same jackson-databind pin as backend/generators/java-sdk build.gradle.kts.mustache
val gjfVersion = "2.0.0"

plugins {
    `java-library` // supplies the consumer attributes Gradle needs to resolve transitive jars
}

val vendor by configurations.creating
val tools by configurations.creating {
    isTransitive = false // the all-deps jar already bundles its dependencies
}

repositories {
    mavenCentral()
}

dependencies {
    vendor("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    tools("com.google.googlejavaformat:google-java-format:$gjfVersion:all-deps")
}

tasks.register<Copy>("vendorJars") {
    from(vendor)
    into(layout.buildDirectory.dir("vendor"))
}

tasks.register<Copy>("toolJars") {
    from(tools) {
        rename { "google-java-format-all-deps.jar" }
    }
    into(layout.buildDirectory.dir("tools"))
}
