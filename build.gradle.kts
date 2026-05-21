import java.util.zip.ZipFile
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    `java-library`
    `maven-publish`
    signing
}

group = "dev.jaeyoung"
version = providers.gradleProperty("releaseVersion")
    .orElse("0.1.0-SNAPSHOT")
    .get()

base {
    archivesName.set("fileloom-step-lite")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.named<Jar>("javadocJar") {
    from("README.md")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

val maxPublishedJarBytes = 128 * 1024
val forbiddenArtifactSuffixes = listOf(".wasm", ".so", ".dylib", ".dll", ".a", ".o")

val checkPublishedArtifactFootprint by tasks.registering {
    group = "verification"
    description = "Checks that the published parser jar stays small and free of native or Wasm payloads."

    val jarTask = tasks.named<Jar>("jar")
    dependsOn(jarTask)
    val jarFile = jarTask.flatMap { it.archiveFile }
    inputs.file(jarFile)

    doLast {
        val artifact = jarFile.get().asFile
        check(artifact.length() <= maxPublishedJarBytes) {
            "fileloom-step-lite jar is ${artifact.length()} bytes, above the $maxPublishedJarBytes byte budget"
        }
        ZipFile(artifact).use { zip ->
            val forbiddenEntries = zip.entries().asSequence()
                .map { it.name }
                .filter { entryName ->
                    forbiddenArtifactSuffixes.any { suffix ->
                        entryName.endsWith(suffix, ignoreCase = true)
                    }
                }
                .toList()
            check(forbiddenEntries.isEmpty()) {
                "fileloom-step-lite jar contains forbidden native/Wasm payloads: $forbiddenEntries"
            }
        }
    }
}

val checkMavenCentralPublishEnv by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks Maven Central publish preflight validation stays wired and dependency-free."
    commandLine("bash", "scripts/test-maven-central-env.sh")
}

tasks.named("check") {
    dependsOn(checkPublishedArtifactFootprint)
    dependsOn(checkMavenCentralPublishEnv)
}

val mavenCentralUsername = providers.gradleProperty("mavenCentralUsername")
    .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
val mavenCentralPassword = providers.gradleProperty("mavenCentralPassword")
    .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
val signingKey = providers.gradleProperty("signingInMemoryKey")
    .orElse(providers.environmentVariable("SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword")
    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "fileloom-step-lite"
            pom {
                name.set("Fileloom STEP Lite")
                description.set("Lightweight dependency-free STEP/STP preview parser for Fileloom CAD wireframes.")
                url.set("https://github.com/beefiker/fileloom-step-lite")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/beefiker/fileloom-step-lite.git")
                    developerConnection.set("scm:git:https://github.com/beefiker/fileloom-step-lite.git")
                    url.set("https://github.com/beefiker/fileloom-step-lite")
                }
                developers {
                    developer {
                        id.set("jaeyoung")
                        name.set("Jaeyoung")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "centralRelease"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = mavenCentralUsername.orNull
                password = mavenCentralPassword.orNull
            }
        }
        maven {
            name = "centralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            credentials {
                username = mavenCentralUsername.orNull
                password = mavenCentralPassword.orNull
            }
        }
    }
}

val checkMavenCentralArtifacts by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks Maven Central publication artifacts include required jars and metadata."
    dependsOn(
        tasks.named("jar"),
        tasks.named("sourcesJar"),
        tasks.named("javadocJar"),
        tasks.named("generatePomFileForMavenJavaPublication")
    )
    commandLine("bash", "scripts/check-maven-central-artifacts.sh", version.toString())
}

tasks.named("check") {
    dependsOn(checkMavenCentralArtifacts)
}

val isReleaseVersion = !version.toString().endsWith("SNAPSHOT")
val isRemoteCentralPublish = gradle.startParameter.taskNames.any { taskName ->
    taskName == "publish" || taskName.contains("Central", ignoreCase = true)
}

signing {
    isRequired = isReleaseVersion && isRemoteCentralPublish
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
    }
    sign(publishing.publications["mavenJava"])
}
