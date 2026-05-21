import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    `java-library`
    `maven-publish`
}

group = "dev.jaeyoung"
version = "0.1.0-SNAPSHOT"

base {
    archivesName.set("fileloom-step-lite")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "fileloom-step-lite"
            pom {
                name.set("Fileloom STEP Lite")
                description.set("Lightweight dependency-free STEP/STP preview parser for Fileloom CAD wireframes.")
                url.set("https://github.com/jaeyoung-dev/Fileloom")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
