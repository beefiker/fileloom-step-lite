package dev.jaeyoung.step

import java.io.File
import org.junit.Assert.assertFalse
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
        val artifactCheckScript = File("scripts/check-maven-central-artifacts.sh").readText()

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
        assertTrue(buildFile.contains("generateMetadataFileForMavenJavaPublication"))
        assertTrue(artifactCheckScript.contains("build/publications/mavenJava/module.json"))
        assertTrue(artifactCheckScript.contains("\\\"module\\\": \\\"fileloom-step-lite\\\""))
        assertTrue(artifactCheckScript.contains("\\\"org.gradle.jvm.version\\\": 17"))
        assertTrue(artifactCheckScript.contains("\\\"org.jetbrains.kotlin.platform.type\\\": \\\"jvm\\\""))
        assertTrue(artifactCheckScript.contains("\\\"module\\\": \\\"kotlin-stdlib\\\""))
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
        assertTrue(readme.contains("release_version` such as `0.1.0`"))
        assertTrue(readme.contains("push a tag such as `v0.1.0`"))
        assertTrue(readme.contains("checkPublishedArtifactFootprint"))
    }

    @Test
    fun parserBuildStaysLightweightAndFreeOfHeavyCadPayloads() {
        val buildFile = File("build.gradle.kts").readText()
        val catalog = File("gradle/libs.versions.toml").readText()
        val sourceText = File("src/main").walkTopDown()
            .filter { it.isFile }
            .joinToString(separator = "\n") { it.readText() }

        assertFalse(buildFile.contains("com.android.library"))
        assertFalse(buildFile.contains("implementation("))
        assertFalse(buildFile.contains("api("))
        assertFalse(catalog.contains("occt", ignoreCase = true))
        assertFalse(sourceText.contains("OpenCascade", ignoreCase = true))
        assertFalse(sourceText.contains("occt", ignoreCase = true))
        assertFalse(sourceText.contains("wasm", ignoreCase = true))
    }

    @Test
    fun buildLifecycleEnforcesPublishedArtifactFootprint() {
        val buildFile = File("build.gradle.kts").readText()

        assertTrue(buildFile.contains("checkPublishedArtifactFootprint"))
        assertTrue(buildFile.contains("maxPublishedJarBytes"))
        assertTrue(buildFile.contains("\".wasm\""))
        assertTrue(buildFile.contains("\".so\""))
        assertTrue(buildFile.contains("tasks.named(\"check\")"))
        assertTrue(buildFile.contains("dependsOn(checkPublishedArtifactFootprint)"))
    }

    @Test
    fun githubActionsWorkflowPublishesSnapshotsAndReleasesAfterChecks() {
        val workflow = File(".github/workflows/publish.yml")
        assertTrue("Expected Maven publish workflow at ${workflow.path}", workflow.isFile)

        val workflowText = workflow.readText()
        assertTrue(workflowText.contains("actions/checkout@v6.0.2"))
        assertTrue(workflowText.contains("actions/setup-java@v5.2.0"))
        assertTrue(workflowText.contains("gradle/actions/setup-gradle@v6.1.0"))
        assertTrue(workflowText.contains("./gradlew check"))
        assertTrue(workflowText.contains("publishAllPublicationsToCentralSnapshotsRepository"))
        assertTrue(workflowText.contains("publishAllPublicationsToCentralReleaseRepository"))
        assertTrue(workflowText.contains("MAVEN_CENTRAL_USERNAME"))
        assertTrue(workflowText.contains("MAVEN_CENTRAL_PASSWORD"))
        assertTrue(workflowText.contains("SIGNING_KEY"))
        assertTrue(workflowText.contains("SIGNING_PASSWORD"))
        assertTrue(workflowText.contains("release_version"))
    }

    @Test
    fun githubActionsWorkflowChecksPushesAndPullRequestsWithoutPublishing() {
        val workflow = File(".github/workflows/ci.yml")
        assertTrue("Expected CI workflow at ${workflow.path}", workflow.isFile)

        val workflowText = workflow.readText()
        assertTrue(workflowText.contains("push:"))
        assertTrue(workflowText.contains("branches: [main]"))
        assertTrue(workflowText.contains("pull_request:"))
        assertTrue(workflowText.contains("actions/checkout@v6.0.2"))
        assertTrue(workflowText.contains("actions/setup-java@v5.2.0"))
        assertTrue(workflowText.contains("gradle/actions/setup-gradle@v6.1.0"))
        assertTrue(workflowText.contains("./gradlew check"))
        assertFalse(workflowText.contains("publishAllPublications"))
        assertFalse(workflowText.contains("MAVEN_CENTRAL_PASSWORD"))
        assertFalse(workflowText.contains("SIGNING_KEY"))
    }

    @Test
    fun releaseWorkflowUploadsOssrhStagingRepositoryToCentralPortal() {
        val workflowText = File(".github/workflows/publish.yml").readText()
        val readme = File("README.md").readText()

        assertTrue(workflowText.contains("MAVEN_CENTRAL_NAMESPACE: dev.jaeyoung"))
        assertTrue(workflowText.contains("/manual/upload/defaultRepository/${'$'}{MAVEN_CENTRAL_NAMESPACE}"))
        assertTrue(workflowText.contains("Authorization: Bearer"))
        assertTrue(workflowText.contains("curl --fail-with-body"))
        assertTrue(readme.contains("MAVEN_CENTRAL_NAMESPACE=\"dev.jaeyoung\""))
        assertTrue(readme.contains("Manual release versions must be artifact versions such as `0.1.0`"))
        assertTrue(readme.contains("manual/upload/defaultRepository/${'$'}MAVEN_CENTRAL_NAMESPACE"))
    }
}
