import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.spotbugs) apply false
    alias(libs.plugins.spotless) apply false
}

val javaVersion = libs.versions.java.get().toInt()
val springBomCoordinate = libs.spring.boot.bom.get().toString()
val checkstyleToolVersion = libs.versions.checkstyle.get()

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(javaVersion)
        }
    }

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom(springBomCoordinate)
        }
    }

    if (name != "proto" && name != "e2e-tests") {
        apply(plugin = "checkstyle")
        apply(plugin = "com.github.spotbugs")
        apply(plugin = "jacoco")
        apply(plugin = "com.diffplug.spotless")

        extensions.configure<CheckstyleExtension> {
            toolVersion = checkstyleToolVersion
            configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        }

        extensions.configure<SpotBugsExtension> {
            effort = Effort.MAX
            reportLevel = Confidence.MEDIUM
            excludeFilter = rootProject.file("config/spotbugs/exclude-filter.xml")
        }

        tasks.withType<Test> {
            useJUnitPlatform {
                if (project.hasProperty("includeTags")) {
                    includeTags(project.property("includeTags") as String)
                } else {
                    // Both 'integration' (per-service Testcontainers) and 'e2e' (full-stack
                    // ComposeContainer) are excluded from the default test task. They run via:
                    //   ./gradlew test -PincludeTags=integration   (per-service)
                    //   ./gradlew :e2e-tests:test -PincludeTags=e2e (full-stack)
                    excludeTags("integration", "e2e")
                }
            }
            finalizedBy(tasks.named("jacocoTestReport"))
        }

        tasks.named<JacocoReport>("jacocoTestReport") {
            reports {
                xml.required = true
                html.required = true
            }
        }

        extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            java {
                googleJavaFormat()
                removeUnusedImports()
            }
        }
    }
}
