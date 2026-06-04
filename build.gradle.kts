import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.spotbugs) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.pitest) apply false
}

val javaVersion = libs.versions.java.get().toInt()
val springBomCoordinate = libs.spring.boot.bom.get().toString()
val checkstyleToolVersion = libs.versions.checkstyle.get()
val tomcatVersion = libs.versions.tomcat.get()
val pitestToolVersion = libs.versions.pitest.tool.get()
val pitestJunit5Version = libs.versions.pitest.junit5.get()

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

    // Override the Tomcat version managed by the Spring Boot BOM. 3.5.14 ships
    // tomcat 10.1.54; 10.1.55 is required to clear the critical Improper
    // Authentication CVE (SNYK-JAVA-ORGAPACHETOMCATEMBED-16691231). The Spring
    // dependency-management plugin resolves the BOM's ${tomcat.version} property
    // against this project extra property.
    extra["tomcat.version"] = tomcatVersion

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
        apply(plugin = "info.solidsoft.pitest")

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

        // Mutation testing. Runs out-of-band via the `mutation.yml`
        // workflow (scheduled + manual), never on the per-PR critical path.
        // Advisory by design: no mutationThreshold is set, so a low score never
        // fails the build — the HTML/XML reports are the deliverable.
        extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
            pitestVersion.set(pitestToolVersion)
            junit5PluginVersion.set(pitestJunit5Version)
            targetClasses.set(listOf("com.mariaalpha.*"))
            // Mutate against the fast unit tests only. PIT passes excludedGroups
            // to the JUnit 5 companion plugin as platform tags, so the
            // `integration` (Testcontainers) and `e2e` (full-stack
            // ComposeContainer) suites — which need Docker per mutant and are
            // already excluded from the default `test` task — are skipped here too.
            excludedGroups.set(listOf("integration", "e2e"))
            jvmArgs.set(listOf("-Xmx1g"))
            threads.set(Runtime.getRuntime().availableProcessors())
            outputFormats.set(listOf("HTML", "XML"))
            timestampedReports.set(false)
            failWhenNoMutations.set(false)
        }
    }
}
