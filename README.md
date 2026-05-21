# Fileloom STEP Lite

Lightweight Kotlin/JVM STEP/STP preview parser for Fileloom CAD wireframes.

This parser is intentionally not an OpenCascade binding and does not bundle WASM, native code, generated meshes, or heavyweight dependencies. It references the `@tx-code/occt-js` / `occt-import-js` style of returning a normalized import result, but narrows the implementation to fast text parsing and preview geometry that is cheap enough for Fileloom's Android app bundle.

## Current Scope

- Reads ISO-10303-21 STEP/STP text files from an `InputStream`
- Detects bounded input size before building in-memory model structures
- Extracts `PRODUCT` name, unit hints, `CARTESIAN_POINT`, `VERTEX_POINT`, and `EDGE_CURVE`
- Emits lightweight line entities with 3D bounds for preview adapters
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
./gradlew publishToMavenLocal
```

The module is configured with `maven-publish`, `group = "dev.jaeyoung"`, and artifact ID `fileloom-step-lite`.
