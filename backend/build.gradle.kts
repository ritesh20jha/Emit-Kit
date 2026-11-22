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
        // the NVD feed download is rate-limited and slow. A blank value (CI sets an empty string when
        // a PR has no secrets) counts as no key.
        apiKey.set(providers.environmentVariable("NVD_API_KEY").filter { it.isNotBlank() })
    }
    data {
        // Under build/ so the CI cache step can restore it between runs. The data lives under
        // backend/build, so ./gradlew clean forces a cold download (30+ min keyless).
        directory.set(layout.buildDirectory.dir("dependency-check-data").map { it.asFile.path })
    }
}
