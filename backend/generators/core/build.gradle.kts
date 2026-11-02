plugins {
    id("emitkit.java-conventions")
}

dependencies {
    // api: the SPI signatures expose ApiModel, Tag and Operation.
    api(project(":spec-engine"))
    implementation(project(":common"))
    implementation(libs.jmustache)
}
