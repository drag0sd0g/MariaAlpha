plugins {
    `java-library`
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
    testImplementation("org.awaitility:awaitility:4.2.2")
    testImplementation("ch.qos.logback:logback-classic:1.5.32")
    testImplementation("org.codehaus.janino:janino:3.1.12")
}


tasks.named<Test>("test") {
    useJUnitPlatform {
        // Default behaviour mirrors root build.gradle.kts: opt-in via -PincludeTags=e2e.
        if (project.hasProperty("includeTags")) {
            includeTags(project.property("includeTags") as String)
        } else {
            excludeTags("e2e")
        }
    }
    // The test boots ~10 containers; double the default JVM heap so logs and JSON deserialisation
    // don't OOM.
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}