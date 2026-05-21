package dev.jaeyoung.step

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

enum class StepLiteUnit {
    MILLIMETER,
    CENTIMETER,
    METER,
    INCH,
    FOOT,
    UNKNOWN
}

data class StepLitePoint(
    val x: Double,
    val y: Double,
    val z: Double = 0.0
)

data class StepLiteBounds(
    val min: StepLitePoint,
    val max: StepLitePoint
) {
    fun include(point: StepLitePoint): StepLiteBounds {
        return StepLiteBounds(
            min = StepLitePoint(
                x = min(min.x, point.x),
                y = min(min.y, point.y),
                z = min(min.z, point.z)
            ),
            max = StepLitePoint(
                x = max(max.x, point.x),
                y = max(max.y, point.y),
                z = max(max.z, point.z)
            )
        )
    }

    companion object {
        fun fromPoint(point: StepLitePoint): StepLiteBounds = StepLiteBounds(point, point)
    }
}

sealed interface StepLiteEntity {
    val sourceId: Int?

    data class Line(
        val start: StepLitePoint,
        val end: StepLitePoint,
        override val sourceId: Int? = null
    ) : StepLiteEntity

    data class Circle(
        val center: StepLitePoint,
        val radius: Double,
        override val sourceId: Int? = null
    ) : StepLiteEntity

    data class Arc(
        val center: StepLitePoint,
        val radius: Double,
        val startAngleRadians: Double,
        val endAngleRadians: Double,
        override val sourceId: Int? = null
    ) : StepLiteEntity
}

data class StepLiteDocument(
    val name: String,
    val unit: StepLiteUnit,
    val entities: List<StepLiteEntity>,
    val unsupportedEntityCount: Int,
    val bounds: StepLiteBounds
)

enum class StepLiteUnsupportedReason {
    NOT_STEP,
    EMPTY_OR_UNSUPPORTED,
    TOO_LARGE
}

sealed interface StepLiteParseResult {
    data class Success(val document: StepLiteDocument) : StepLiteParseResult
    data class Unsupported(val reason: StepLiteUnsupportedReason) : StepLiteParseResult
    data class Failure(val message: String) : StepLiteParseResult
}

class StepLiteParser(
    private val maxBytes: Int = DefaultMaxBytes,
    private val maxRecords: Int = DefaultMaxRecords,
    private val maxEntities: Int = DefaultMaxEntities
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxRecords > 0) { "maxRecords must be positive" }
        require(maxEntities > 0) { "maxEntities must be positive" }
    }

    fun parse(input: InputStream): StepLiteParseResult {
        val bytes = input.readCapped(maxBytes)
            ?: return StepLiteParseResult.Unsupported(StepLiteUnsupportedReason.TOO_LARGE)
        val text = bytes.toString(StandardCharsets.UTF_8)
        if (!text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith(StepHeader, ignoreCase = true)) {
            return StepLiteParseResult.Unsupported(StepLiteUnsupportedReason.NOT_STEP)
        }

        return try {
            parseText(text)
        } catch (_: StepLiteTooLargeException) {
            StepLiteParseResult.Unsupported(StepLiteUnsupportedReason.TOO_LARGE)
        } catch (error: Exception) {
            StepLiteParseResult.Failure(error.message ?: "Unable to parse STEP")
        }
    }

    private fun parseText(text: String): StepLiteParseResult {
        val points = linkedMapOf<Int, StepLitePoint>()
        val vertexPoints = linkedMapOf<Int, Int>()
        val placements = linkedMapOf<Int, AxisPlacementRecord>()
        val circles = linkedMapOf<Int, CircleRecord>()
        val edges = ArrayList<EdgeCurveRecord>()
        var productName = ""
        var unit = StepLiteUnit.UNKNOWN
        var recordCount = 0
        var unsupported = 0

        StepRecordSequence(text).forEach { raw ->
            recordCount += 1
            if (recordCount > maxRecords) throw StepLiteTooLargeException()
            if (raw.contains("SI_UNIT", ignoreCase = true) || raw.contains("CONVERSION_BASED_UNIT", ignoreCase = true)) {
                unit = maxOf(unit, raw.resolveUnit())
            }
            val record = StepRecord.parse(raw) ?: return@forEach
            when (record.type) {
                "PRODUCT" -> if (productName.isBlank()) {
                    productName = record.args.firstString().orEmpty()
                }
                "CARTESIAN_POINT" -> {
                    val point = record.args.firstNumberTuple(minSize = 2)?.toPoint()
                    if (point != null) points[record.id] = point
                }
                "VERTEX_POINT" -> {
                    val pointRef = record.args.refs().firstOrNull()
                    if (pointRef != null) vertexPoints[record.id] = pointRef
                }
                "AXIS2_PLACEMENT_3D" -> {
                    val refs = record.args.refs()
                    if (refs.isNotEmpty()) {
                        placements[record.id] = AxisPlacementRecord(locationPointId = refs[0])
                    }
                }
                "CIRCLE" -> {
                    val placementId = record.args.refs().firstOrNull()
                    val radius = record.args.topLevelNumbers().lastOrNull()
                    if (placementId != null && radius != null && radius > 0.0) {
                        circles[record.id] = CircleRecord(
                            placementId = placementId,
                            radius = radius
                        )
                    }
                }
                "EDGE_CURVE" -> {
                    val refs = record.args.refs()
                    if (refs.size >= 3) {
                        edges += EdgeCurveRecord(record.id, refs[0], refs[1], refs[2])
                    } else {
                        unsupported += 1
                    }
                }
                "SI_UNIT", "CONVERSION_BASED_UNIT", "COMPLEX" -> {
                    unit = maxOf(unit, record.args.resolveUnit())
                }
                else -> Unit
            }
        }

        val entities = ArrayList<StepLiteEntity>(min(edges.size, maxEntities))
        for (edge in edges) {
            if (entities.size >= maxEntities) break
            val start = vertexPoints[edge.startVertexId]?.let(points::get)
            val end = vertexPoints[edge.endVertexId]?.let(points::get)
            if (start != null && end != null) {
                val circle = circles[edge.curveId]
                val center = circle
                    ?.let { placements[it.placementId] }
                    ?.let { points[it.locationPointId] }
                entities += if (circle != null && center != null) {
                    if (start.samePositionAs(end)) {
                        StepLiteEntity.Circle(
                            center = center,
                            radius = circle.radius,
                            sourceId = edge.sourceId
                        )
                    } else {
                        StepLiteEntity.Arc(
                            center = center,
                            radius = circle.radius,
                            startAngleRadians = start.angleFrom(center),
                            endAngleRadians = end.angleFrom(center),
                            sourceId = edge.sourceId
                        )
                    }
                } else {
                    StepLiteEntity.Line(
                        start = start,
                        end = end,
                        sourceId = edge.sourceId
                    )
                }
            } else {
                unsupported += 1
            }
        }

        val bounds = entities.asSequence()
            .map { it.bounds() }
            .fold(null as StepLiteBounds?) { current, bounds ->
                current?.include(bounds.min)?.include(bounds.max) ?: bounds
            }
            ?: return StepLiteParseResult.Unsupported(StepLiteUnsupportedReason.EMPTY_OR_UNSUPPORTED)

        return StepLiteParseResult.Success(
            StepLiteDocument(
                name = productName.ifBlank { "STEP model" },
                unit = unit,
                entities = entities,
                unsupportedEntityCount = unsupported,
                bounds = bounds
            )
        )
    }

    private data class EdgeCurveRecord(
        val sourceId: Int,
        val startVertexId: Int,
        val endVertexId: Int,
        val curveId: Int
    )

    private data class AxisPlacementRecord(
        val locationPointId: Int
    )

    private data class CircleRecord(
        val placementId: Int,
        val radius: Double
    )

    private companion object {
        private const val StepHeader = "ISO-10303-21;"
        private const val DefaultMaxBytes = 16 * 1024 * 1024
        private const val DefaultMaxRecords = 250_000
        private const val DefaultMaxEntities = 100_000
    }
}

private data class StepRecord(
    val id: Int,
    val type: String,
    val args: String
) {
    companion object {
        fun parse(raw: String): StepRecord? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("#")) return null
            val equals = trimmed.indexOf('=')
            if (equals <= 1) return null
            val id = trimmed.substring(1, equals).trim().toIntOrNull() ?: return null
            var cursor = equals + 1
            while (cursor < trimmed.length && trimmed[cursor].isWhitespace()) cursor += 1
            if (trimmed.getOrNull(cursor) == '(') {
                return StepRecord(
                    id = id,
                    type = "COMPLEX",
                    args = trimmed.substring(cursor)
                )
            }
            val typeStart = cursor
            while (cursor < trimmed.length && (trimmed[cursor].isLetterOrDigit() || trimmed[cursor] == '_')) {
                cursor += 1
            }
            if (cursor == typeStart) return null
            val type = trimmed.substring(typeStart, cursor).uppercase()
            val argsStart = trimmed.indexOf('(', startIndex = cursor).takeIf { it >= 0 } ?: return null
            val argsEnd = trimmed.lastIndexOf(')').takeIf { it > argsStart } ?: return null
            return StepRecord(
                id = id,
                type = type,
                args = trimmed.substring(argsStart + 1, argsEnd)
            )
        }
    }
}

private class StepRecordSequence(
    private val text: String
) {
    inline fun forEach(block: (String) -> Unit) {
        val builder = StringBuilder()
        var inString = false
        var inLineComment = false
        var inBlockComment = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val next = text.getOrNull(index + 1)

            if (inLineComment) {
                if (char == '\n' || char == '\r') inLineComment = false
                index += 1
                continue
            }
            if (inBlockComment) {
                if (char == '*' && next == '/') {
                    inBlockComment = false
                    index += 2
                } else {
                    index += 1
                }
                continue
            }
            if (!inString && char == '/' && next == '/') {
                inLineComment = true
                index += 2
                continue
            }
            if (!inString && char == '/' && next == '*') {
                inBlockComment = true
                index += 2
                continue
            }

            builder.append(char)
            if (char == '\'') {
                if (inString && next == '\'') {
                    builder.append(next)
                    index += 2
                    continue
                }
                inString = !inString
            } else if (!inString && char == ';') {
                block(builder.substring(0, builder.length - 1))
                builder.clear()
            }
            index += 1
        }
    }
}

private fun String.refs(): List<Int> {
    val refs = ArrayList<Int>()
    var index = 0
    while (index < length) {
        if (this[index] == '#') {
            var cursor = index + 1
            while (cursor < length && this[cursor].isDigit()) cursor += 1
            substring(index + 1, cursor).toIntOrNull()?.let(refs::add)
            index = cursor
        } else {
            index += 1
        }
    }
    return refs
}

private fun String.firstString(): String? {
    val start = indexOf('\'')
    if (start < 0) return null
    val builder = StringBuilder()
    var index = start + 1
    while (index < length) {
        val char = this[index]
        if (char == '\'') {
            if (getOrNull(index + 1) == '\'') {
                builder.append('\'')
                index += 2
                continue
            }
            return builder.toString()
        }
        builder.append(char)
        index += 1
    }
    return null
}

private fun String.firstNumberTuple(minSize: Int): List<Double>? {
    var depth = 0
    var tupleStart = -1
    for (index in indices) {
        when (this[index]) {
            '(' -> {
                depth += 1
                if (depth == 1) tupleStart = index + 1
            }
            ')' -> {
                if (depth == 1 && tupleStart >= 0) {
                    val values = substring(tupleStart, index)
                        .split(',')
                        .mapNotNull { it.trim().toStepDoubleOrNull() }
                    if (values.size >= minSize) return values
                }
                depth -= 1
            }
        }
    }
    return null
}

private fun String.topLevelNumbers(): List<Double> {
    val values = ArrayList<Double>()
    var depth = 0
    var tokenStart = -1

    fun flush(end: Int) {
        if (tokenStart >= 0) {
            substring(tokenStart, end).toStepDoubleOrNull()?.let(values::add)
            tokenStart = -1
        }
    }

    for (index in indices) {
        val char = this[index]
        when {
            char == '(' -> {
                flush(index)
                depth += 1
            }
            char == ')' -> {
                flush(index)
                depth -= 1
            }
            depth == 0 && char.isNumberTokenChar() -> {
                if (tokenStart < 0) tokenStart = index
            }
            depth == 0 -> flush(index)
        }
    }
    flush(length)
    return values
}

private fun Char.isNumberTokenChar(): Boolean {
    return isDigit() || this == '+' || this == '-' || this == '.' || this == 'E' || this == 'e' || this == 'D' || this == 'd'
}

private fun List<Double>.toPoint(): StepLitePoint {
    return StepLitePoint(
        x = this[0],
        y = this[1],
        z = getOrElse(2) { 0.0 }
    )
}

private fun StepLitePoint.samePositionAs(other: StepLitePoint): Boolean {
    return kotlin.math.abs(x - other.x) <= CoordinateTolerance &&
        kotlin.math.abs(y - other.y) <= CoordinateTolerance &&
        kotlin.math.abs(z - other.z) <= CoordinateTolerance
}

private fun StepLitePoint.angleFrom(center: StepLitePoint): Double {
    return atan2(y - center.y, x - center.x)
}

private fun StepLiteEntity.bounds(): StepLiteBounds {
    return when (this) {
        is StepLiteEntity.Line -> StepLiteBounds.fromPoint(start).include(end)
        is StepLiteEntity.Circle -> StepLiteBounds(
            min = StepLitePoint(center.x - radius, center.y - radius, center.z),
            max = StepLitePoint(center.x + radius, center.y + radius, center.z)
        )
        is StepLiteEntity.Arc -> StepLiteBounds(
            min = StepLitePoint(center.x - radius, center.y - radius, center.z),
            max = StepLitePoint(center.x + radius, center.y + radius, center.z)
        )
    }
}

private fun String.toStepDoubleOrNull(): Double? {
    val normalized = trim()
        .removePrefix("+")
        .replace('D', 'E')
        .replace('d', 'E')
    return normalized.toDoubleOrNull()
}

private fun String.resolveUnit(): StepLiteUnit {
    val normalized = uppercase()
    return when {
        ".MILLI." in normalized && ".METRE." in normalized -> StepLiteUnit.MILLIMETER
        ".CENTI." in normalized && ".METRE." in normalized -> StepLiteUnit.CENTIMETER
        ".METRE." in normalized -> StepLiteUnit.METER
        "'MILLIMETRE'" in normalized || "'MILLIMETER'" in normalized -> StepLiteUnit.MILLIMETER
        "'CENTIMETRE'" in normalized || "'CENTIMETER'" in normalized -> StepLiteUnit.CENTIMETER
        "'INCH'" in normalized -> StepLiteUnit.INCH
        "'FOOT'" in normalized || "'FEET'" in normalized -> StepLiteUnit.FOOT
        else -> StepLiteUnit.UNKNOWN
    }
}

private fun maxOf(current: StepLiteUnit, candidate: StepLiteUnit): StepLiteUnit {
    return if (current == StepLiteUnit.UNKNOWN) candidate else current
}

private const val CoordinateTolerance = 1.0e-9

private class StepLiteTooLargeException : RuntimeException()

private fun InputStream.readCapped(maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
