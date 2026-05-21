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
}
