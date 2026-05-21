package dev.jaeyoung.step

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class StepLitePublishingTest {
    @Test
    fun moduleIsReadyForDevJaeyoungMavenPublication() {
        val buildFile = File("build.gradle.kts").readText()

        assertTrue(buildFile.contains("`maven-publish`"))
        assertTrue(buildFile.contains("group = \"dev.jaeyoung\""))
        assertTrue(buildFile.contains("artifactId = \"fileloom-step-lite\""))
        assertTrue(buildFile.contains("withSourcesJar()"))
        assertTrue(buildFile.contains("from(components[\"java\"])"))
    }

    @Test
    fun publicationCarriesCentralPortalMetadataAndArtifacts() {
        val buildFile = File("build.gradle.kts").readText()

        assertTrue(buildFile.contains("signing"))
        assertTrue(buildFile.contains("useInMemoryPgpKeys"))
        assertTrue(buildFile.contains("MAVEN_CENTRAL_USERNAME"))
        assertTrue(buildFile.contains("MAVEN_CENTRAL_PASSWORD"))
        assertTrue(buildFile.contains("SIGNING_KEY"))
        assertTrue(buildFile.contains("SIGNING_PASSWORD"))
        assertTrue(buildFile.contains("releaseVersion"))
        assertTrue(buildFile.contains("withJavadocJar()"))
        assertTrue(buildFile.contains("scm {"))
        assertTrue(buildFile.contains("developerConnection.set(\"scm:git:https://github.com/beefiker/fileloom-step-lite.git\")"))
        assertTrue(buildFile.contains("developers {"))
        assertTrue(buildFile.contains("centralRelease"))
        assertTrue(buildFile.contains("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"))
        assertTrue(buildFile.contains("centralSnapshots"))
        assertTrue(buildFile.contains("https://central.sonatype.com/repository/maven-snapshots/"))
    }

    @Test
    fun readmeDocumentsMavenCentralCredentialsAndCommands() {
        val readme = File("README.md").readText()

        assertTrue(readme.contains("MAVEN_CENTRAL_USERNAME"))
        assertTrue(readme.contains("MAVEN_CENTRAL_PASSWORD"))
        assertTrue(readme.contains("SIGNING_KEY"))
        assertTrue(readme.contains("SIGNING_PASSWORD"))
        assertTrue(readme.contains("publishAllPublicationsToCentralSnapshotsRepository"))
        assertTrue(readme.contains("publishAllPublicationsToCentralReleaseRepository"))
    }
}
