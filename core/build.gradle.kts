plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}

// The parser spec in /parser-spec is the single source of truth shared with the Node harness.
val specDir = rootProject.layout.projectDirectory.dir("parser-spec")

tasks.processResources {
    from(specDir) {
        include("templates.json", "senders.json")
        into("parser-spec")
    }
}

tasks.processTestResources {
    from(specDir) {
        include("fixtures.json")
        into("parser-spec")
    }
}

tasks.test {
    useJUnitPlatform()
}
