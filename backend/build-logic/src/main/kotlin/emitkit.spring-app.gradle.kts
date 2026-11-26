plugins {
    id("emitkit.java-conventions")
    id("org.springframework.boot")
}

// No main class exists yet, so bootJar would fail `build`: use the plain jar for now.
// API-00 / WRK-00 flip this once a main class exists (enable bootJar, drop the plain-jar override).
tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = true }
