# Fileloom STEP Lite

Lightweight Kotlin/JVM STEP/STP preview parser for Fileloom CAD wireframes.

This parser is intentionally not an OpenCascade binding and does not bundle WASM, native code, generated meshes, or heavyweight dependencies. It references the `@tx-code/occt-js` / `occt-import-js` style of returning a normalized import result, but narrows the implementation to fast text parsing and preview geometry that is cheap enough for Fileloom's Android app bundle.

## Current Scope

- Reads ISO-10303-21 STEP/STP text files from an `InputStream`
- Detects bounded input size before building in-memory model structures
- Extracts `PRODUCT` name, unit hints, `CARTESIAN_POINT`, `VERTEX_POINT`, complex point/vertex records, `DIRECTION`, `AXIS2_PLACEMENT_3D`, complex direction/placement records, `LINE`, `POLYLINE`, complex line/polyline records, `CIRCLE`, `ELLIPSE`, `PARABOLA`, `HYPERBOLA`, complex conic records, `B_SPLINE_CURVE_WITH_KNOTS`, `BEZIER_CURVE`, `QUASI_UNIFORM_CURVE`, complex rational B-spline records, `TRIMMED_CURVE`, `SURFACE_CURVE`, `SEAM_CURVE`, complex curve wrapper records, `EDGE_CURVE`, and complex edge records
- Emits lightweight line, polyline, circle, arc, sampled ellipse, sampled parabola, sampled hyperbola, sampled B-spline, sampled Bezier, and sampled rational B-spline wireframes with 3D bounds for preview adapters
- Counts unsupported curve records instead of flattening them into misleading straight lines
- Honors `EDGE_CURVE` same-sense direction for circular arcs, polylines, sampled ellipses, and sampled B-splines
- Resolves `TRIMMED_CURVE`, `SURFACE_CURVE`, and `SEAM_CURVE` wrappers to their lightweight basis curves before preview conversion
- Applies `AXIS2_PLACEMENT_3D` direction/ref-direction axes when sampling ellipses and non-XY circles
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

Create a Central Portal user token and set its username/password as Gradle properties or environment variables:

```bash
export MAVEN_CENTRAL_USERNAME="..."
export MAVEN_CENTRAL_PASSWORD="..."
export SIGNING_KEY="$(cat private-key.asc)"
export SIGNING_PASSWORD="..."
```

Check publish inputs locally without uploading:

```bash
./scripts/require-maven-central-env.sh snapshot
./scripts/require-maven-central-env.sh release
```

Publish a snapshot:

```bash
./gradlew publishAllPublicationsToCentralSnapshotsRepository
```

Or run the `Publish Maven` GitHub Actions workflow with target `snapshot`.

Publish a release candidate:

```bash
./gradlew -PreleaseVersion=0.1.0 publishAllPublicationsToCentralReleaseRepository
```

For GitHub Actions, set repository secrets named `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, and `SIGNING_PASSWORD`. Then either run the `Publish Maven` workflow with target `release` and `release_version`, or push a tag such as `v0.1.0`.

Release artifacts published through the Central Portal OSSRH Staging API still need to be uploaded from the staging compatibility service into Central Portal. The GitHub workflow runs this step automatically after a release upload:

```bash
curl --fail-with-body \
  --request POST \
  --header "Authorization: Bearer $(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')" \
  "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/dev.jaeyoung"
```

Publishing references:

- Central Portal Gradle guidance: https://central.sonatype.org/publish/publish-portal-gradle/
- Central Portal OSSRH Staging API: https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/
- Central Portal snapshots: https://central.sonatype.org/publish/publish-portal-snapshots/

## License

Apache-2.0. See `LICENSE`.
