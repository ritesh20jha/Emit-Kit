// Aggregate root. Shared configuration lives in the build-logic convention plugins.

plugins {
    alias(libs.plugins.owasp.dependency.check)
}

// Run with ./gradlew dependencyCheckAggregate (CI: .github/workflows/security.yml).
dependencyCheck {
    // Fail the build on CVSS 7.0 and above (high and critical).
    failBuildOnCVSS.set(7.0f)
    nvd {
        // NVD API key, read from the environment so it is never committed. Optional, but without it
        // the NVD feed download is rate-limited and slow.
        apiKey.set(providers.environmentVariable("NVD_API_KEY"))
    }
    data {
        // Under build/ so the CI cache step can restore it between runs.
        directory.set(layout.buildDirectory.dir("dependency-check-data").map { it.asFile.path })
    }
}
