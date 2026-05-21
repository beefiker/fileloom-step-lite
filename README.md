# Fileloom STEP Lite

Lightweight Kotlin/JVM STEP/STP preview parser for Fileloom CAD wireframes.

This parser is intentionally not an OpenCascade binding and does not bundle WASM, native code, generated meshes, or heavyweight dependencies. It references the `@tx-code/occt-js` / `occt-import-js` style of returning a normalized import result, but narrows the implementation to fast text parsing and preview geometry that is cheap enough for Fileloom's Android app bundle.

## Current Scope

- Reads ISO-10303-21 STEP/STP text files from an `InputStream`
- Detects bounded input size before building in-memory model structures
- Extracts `PRODUCT` name, unit hints, `CARTESIAN_POINT`, `VERTEX_POINT`, `AXIS2_PLACEMENT_3D`, `CIRCLE`, and `EDGE_CURVE`
- Emits lightweight line, circle, and arc entities with 3D bounds for preview adapters
- Has no runtime dependencies beyond the Kotlin/JDK standard libraries
- Publishes as `dev.jaeyoung:fileloom-step-lite`

This is not a full B-Rep kernel. Faces, triangulation, colors, assemblies, and exact topology are future additive work.

## Usage

```kotlin
val result = StepLiteParser().parse(inputStream)
when (result) {
    is StepLiteParseResult.Success -> {
        val document = result.document
        println(document.name)
        println(document.entities.size)
    }
    is StepLiteParseResult.Unsupported -> {
        println(result.reason)
    }
    is StepLiteParseResult.Failure -> {
        println(result.message)
    }
}
```

## Build

```bash
./gradlew test
./gradlew check
./gradlew publishToMavenLocal
```

The module is configured with `maven-publish`, `signing`, `group = "dev.jaeyoung"`, and artifact ID `fileloom-step-lite`.
The `check` lifecycle runs `checkPublishedArtifactFootprint`, which keeps the parser jar under its size budget and rejects native or WASM payloads.

## Maven Central

Create and verify the `dev.jaeyoung` namespace in Central Portal before running a remote publish. The build uses only Gradle's built-in publishing/signing plugins.

Set credentials with Gradle properties or environment variables:

```bash
export MAVEN_CENTRAL_USERNAME="..."
export MAVEN_CENTRAL_PASSWORD="..."
export SIGNING_KEY="$(cat private-key.asc)"
export SIGNING_PASSWORD="..."
```

Publish a snapshot:

```bash
./gradlew publishAllPublicationsToCentralSnapshotsRepository
```

Publish a release candidate:

```bash
./gradlew -PreleaseVersion=0.1.0 publishAllPublicationsToCentralReleaseRepository
```

Release artifacts published through the Central Portal OSSRH Staging API still need the Central Portal deployment flow after upload.

Publishing references:

- Central Portal Gradle guidance: https://central.sonatype.org/publish/publish-portal-gradle/
- Central Portal OSSRH Staging API: https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/
- Central Portal snapshots: https://central.sonatype.org/publish/publish-portal-snapshots/
