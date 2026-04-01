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

    if (name != "proto") {
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
        }

        tasks.withType<Test> {
            useJUnitPlatform()
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
