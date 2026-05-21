package dev.jaeyoung.step

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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

    data class Polyline(
        val points: List<StepLitePoint>,
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

private data class DirectionRecord(
    val x: Double,
    val y: Double,
    val z: Double
)

private data class PlacementBasis(
    val xAxis: DirectionRecord,
    val yAxis: DirectionRecord
)

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
        val directions = linkedMapOf<Int, DirectionRecord>()
        val placements = linkedMapOf<Int, AxisPlacementRecord>()
        val circles = linkedMapOf<Int, CircleRecord>()
        val ellipses = linkedMapOf<Int, EllipseRecord>()
        val splines = linkedMapOf<Int, BSplineRecord>()
        val curveWrappers = linkedMapOf<Int, CurveWrapperRecord>()
        val lineCurves = linkedSetOf<Int>()
        val polylineCurves = linkedMapOf<Int, List<Int>>()
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
                "DIRECTION" -> {
                    val direction = record.args.firstNumberTuple(minSize = 2)?.toDirection()
                    if (direction != null) directions[record.id] = direction
                }
                "AXIS2_PLACEMENT_3D" -> {
                    val refs = record.args.refs()
                    if (refs.isNotEmpty()) {
                        placements[record.id] = AxisPlacementRecord(
                            locationPointId = refs[0],
                            axisDirectionId = refs.getOrNull(1),
                            refDirectionId = refs.getOrNull(2)
                        )
                    }
                }
                "CIRCLE" -> {
                    val circle = record.args.toCircleRecord()
                    if (circle != null) circles[record.id] = circle
                }
                "ELLIPSE" -> {
                    val ellipse = record.args.toEllipseRecord()
                    if (ellipse != null) ellipses[record.id] = ellipse
                }
                "LINE" -> {
                    lineCurves += record.id
                }
                "POLYLINE" -> {
                    val pointRefs = record.args.refs()
                    if (pointRefs.size >= 2) {
                        polylineCurves[record.id] = pointRefs
                    }
                }
                "B_SPLINE_CURVE_WITH_KNOTS" -> {
                    val spline = record.args.toBSplineRecord()
                    if (spline != null) splines[record.id] = spline
                }
                "TRIMMED_CURVE" -> {
                    val trimmedCurve = record.args.toTrimmedCurveRecord()
                    if (trimmedCurve != null) curveWrappers[record.id] = trimmedCurve
                }
                "SURFACE_CURVE", "SEAM_CURVE" -> {
                    val wrappedCurve = record.args.toBasisCurveWrapperRecord()
                    if (wrappedCurve != null) curveWrappers[record.id] = wrappedCurve
                }
                "COMPLEX" -> {
                    unit = maxOf(unit, record.args.resolveUnit())
                    val spline = record.args.toComplexBSplineRecord()
                    if (spline != null) splines[record.id] = spline
                    val circle = record.args.entityArgs("CIRCLE")?.toCircleRecord()
                    if (circle != null) circles[record.id] = circle
                    val ellipse = record.args.entityArgs("ELLIPSE")?.toEllipseRecord()
                    if (ellipse != null) ellipses[record.id] = ellipse
                }
                "EDGE_CURVE" -> {
                    val refs = record.args.refs()
                    if (refs.size >= 3) {
                        edges += EdgeCurveRecord(
                            sourceId = record.id,
                            startVertexId = refs[0],
                            endVertexId = refs[1],
                            curveId = refs[2],
                            sameSense = record.args.lastTopLevelLogical() ?: true
                        )
                    } else {
                        unsupported += 1
                    }
                }
                "SI_UNIT", "CONVERSION_BASED_UNIT" -> {
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
                val entity = edge.toEntity(
                    start = start,
                    end = end,
                    points = points,
                    directions = directions,
                    placements = placements,
                    circles = circles,
                    ellipses = ellipses,
                    splines = splines,
                    curveWrappers = curveWrappers,
                    lineCurves = lineCurves,
                    polylineCurves = polylineCurves
                )
                if (entity != null) {
                    entities += entity
                } else {
                    unsupported += 1
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
        val curveId: Int,
        val sameSense: Boolean
    )

    private data class AxisPlacementRecord(
        val locationPointId: Int,
        val axisDirectionId: Int?,
        val refDirectionId: Int?
    )

    private data class CircleRecord(
        val placementId: Int,
        val radius: Double
    )

    private data class EllipseRecord(
        val placementId: Int,
        val majorRadius: Double,
        val minorRadius: Double
    )

    private data class BSplineRecord(
        val degree: Int,
        val controlPointIds: List<Int>,
        val knots: List<Double>,
        val weights: List<Double>? = null
    )

    private data class WeightedPoint(
        val x: Double,
        val y: Double,
        val z: Double,
        val weight: Double
    )

    private data class CurveWrapperRecord(
        val basisCurveId: Int,
        val sameSense: Boolean
    )

    private data class ResolvedCurveRecord(
        val curveId: Int,
        val sameSense: Boolean
    )

    private fun EdgeCurveRecord.toEntity(
        start: StepLitePoint,
        end: StepLitePoint,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        placements: Map<Int, AxisPlacementRecord>,
        circles: Map<Int, CircleRecord>,
        ellipses: Map<Int, EllipseRecord>,
        splines: Map<Int, BSplineRecord>,
        curveWrappers: Map<Int, CurveWrapperRecord>,
        lineCurves: Set<Int>,
        polylineCurves: Map<Int, List<Int>>
    ): StepLiteEntity? {
        val resolvedCurve = resolveCurve(curveWrappers)
        val resolvedCurveId = resolvedCurve.curveId
        val resolvedSameSense = resolvedCurve.sameSense

        val circle = circles[resolvedCurveId]
        val circlePlacement = circle?.let { placements[it.placementId] }
        val center = circlePlacement?.let { points[it.locationPointId] }
        if (circle != null && circlePlacement != null && center != null) {
            val circleBasis = circlePlacement.toBasis(directions)
            val arcStart = if (resolvedSameSense) start else end
            val arcEnd = if (resolvedSameSense) end else start
            if (!circleBasis.isFlatInPreviewPlane()) {
                return StepLiteEntity.Polyline(
                    points = circle.toPolylinePoints(
                        center = center,
                        basis = circleBasis,
                        start = arcStart,
                        end = arcEnd,
                        closed = start.samePositionAs(end)
                    ),
                    sourceId = sourceId
                )
            }
            return if (start.samePositionAs(end)) {
                StepLiteEntity.Circle(
                    center = center,
                    radius = circle.radius,
                    sourceId = sourceId
                )
            } else {
                StepLiteEntity.Arc(
                    center = center,
                    radius = circle.radius,
                    startAngleRadians = arcStart.angleFrom(center),
                    endAngleRadians = arcEnd.angleFrom(center),
                    sourceId = sourceId
                )
            }
        }

        val ellipse = ellipses[resolvedCurveId]
        val ellipsePlacement = ellipse?.let { placements[it.placementId] }
        val ellipseCenter = ellipsePlacement?.let { points[it.locationPointId] }
        if (ellipse != null && ellipsePlacement != null && ellipseCenter != null) {
            val ellipseStart = if (resolvedSameSense) start else end
            val ellipseEnd = if (resolvedSameSense) end else start
            return StepLiteEntity.Polyline(
                points = ellipse.toPolylinePoints(
                    center = ellipseCenter,
                    basis = ellipsePlacement.toBasis(directions),
                    start = ellipseStart,
                    end = ellipseEnd,
                    closed = start.samePositionAs(end)
                ),
                sourceId = sourceId
            )
        }

        val spline = splines[resolvedCurveId]
        if (spline != null) {
            val splinePoints = spline.toPolylinePoints(points)
                ?.orientedBetween(
                    start = if (resolvedSameSense) start else end,
                    end = if (resolvedSameSense) end else start
                )
            return splinePoints?.let {
                StepLiteEntity.Polyline(points = it, sourceId = sourceId)
            }
        }

        val polylinePointIds = polylineCurves[resolvedCurveId]
        if (polylinePointIds != null) {
            val polylinePoints = polylinePointIds.mapNotNull(points::get)
                .takeIf { it.size >= 2 }
                ?.orientedBetween(
                    start = if (resolvedSameSense) start else end,
                    end = if (resolvedSameSense) end else start
                )
            return polylinePoints?.let {
                StepLiteEntity.Polyline(points = it, sourceId = sourceId)
            }
        }

        return if (resolvedCurveId in lineCurves) {
            StepLiteEntity.Line(
                start = start,
                end = end,
                sourceId = sourceId
            )
        } else {
            null
        }
    }

    private fun EdgeCurveRecord.resolveCurve(curveWrappers: Map<Int, CurveWrapperRecord>): ResolvedCurveRecord {
        var id = curveId
        var sense = sameSense
        repeat(MaxCurveWrapperDepth) {
            val wrapper = curveWrappers[id] ?: return ResolvedCurveRecord(id, sense)
            id = wrapper.basisCurveId
            sense = sense == wrapper.sameSense
        }
        return ResolvedCurveRecord(id, sense)
    }

    private fun CircleRecord.toPolylinePoints(
        center: StepLitePoint,
        basis: PlacementBasis,
        start: StepLitePoint,
        end: StepLitePoint,
        closed: Boolean
    ): List<StepLitePoint> {
        return samplePlacedConic(
            center = center,
            basis = basis,
            majorRadius = radius,
            minorRadius = radius,
            start = start,
            end = end,
            closed = closed,
            segmentCount = CircleSegments
        )
    }

    private fun EllipseRecord.toPolylinePoints(
        center: StepLitePoint,
        basis: PlacementBasis,
        start: StepLitePoint,
        end: StepLitePoint,
        closed: Boolean
    ): List<StepLitePoint> {
        return samplePlacedConic(
            center = center,
            basis = basis,
            majorRadius = majorRadius,
            minorRadius = minorRadius,
            start = start,
            end = end,
            closed = closed,
            segmentCount = EllipseSegments
        )
    }

    private fun samplePlacedConic(
        center: StepLitePoint,
        basis: PlacementBasis,
        majorRadius: Double,
        minorRadius: Double,
        start: StepLitePoint,
        end: StepLitePoint,
        closed: Boolean,
        segmentCount: Int
    ): List<StepLitePoint> {
        val startAngle = start.ellipseAngleFrom(center, basis, majorRadius, minorRadius)
        val sweep = if (closed) {
            2.0 * PI
        } else {
            positiveSweep(
                from = startAngle,
                to = end.ellipseAngleFrom(center, basis, majorRadius, minorRadius)
            )
        }
        val segments = if (closed) {
            segmentCount
        } else {
            max(2, ceil(sweep / (2.0 * PI) * segmentCount).toInt())
        }
        return List(segments + 1) { index ->
            val angle = startAngle + sweep * index / segments
            val majorOffset = majorRadius * cos(angle)
            val minorOffset = minorRadius * sin(angle)
            StepLitePoint(
                x = center.x + basis.xAxis.x * majorOffset + basis.yAxis.x * minorOffset,
                y = center.y + basis.xAxis.y * majorOffset + basis.yAxis.y * minorOffset,
                z = center.z + basis.xAxis.z * majorOffset + basis.yAxis.z * minorOffset
            )
        }
    }

    private fun AxisPlacementRecord.toBasis(directions: Map<Int, DirectionRecord>): PlacementBasis {
        val zAxis = axisDirectionId?.let(directions::get)?.normalizedOrNull() ?: DefaultZAxis
        val rawX = refDirectionId?.let(directions::get)?.normalizedOrNull() ?: DefaultXAxis
        val projectedX = rawX.minus(zAxis.scale(rawX.dot(zAxis))).normalizedOrNull()
            ?: fallbackXAxisFor(zAxis)
        return PlacementBasis(
            xAxis = projectedX,
            yAxis = zAxis.cross(projectedX).normalizedOrNull() ?: DefaultYAxis
        )
    }

    private fun fallbackXAxisFor(zAxis: DirectionRecord): DirectionRecord {
        return listOf(DefaultXAxis, DefaultYAxis)
            .firstNotNullOfOrNull { candidate ->
                candidate.minus(zAxis.scale(candidate.dot(zAxis))).normalizedOrNull()
            }
            ?: DefaultXAxis
    }

    private fun BSplineRecord.toPolylinePoints(points: Map<Int, StepLitePoint>): List<StepLitePoint>? {
        val controlPoints = controlPointIds.mapNotNull(points::get)
        if (controlPoints.size != controlPointIds.size || controlPoints.size <= degree || knots.size != controlPoints.size + degree + 1) {
            return null
        }
        if (weights != null && weights.size != controlPoints.size) return null

        val start = knots[degree]
        val end = knots[knots.size - degree - 1]
        if (end <= start) return null

        return List(SplineSegments + 1) { index ->
            val t = if (index == SplineSegments) end else start + (end - start) * index / SplineSegments
            if (weights == null) {
                evaluateBSpline(t, controlPoints)
            } else {
                evaluateRationalBSpline(t, controlPoints, weights)
            }
        }.dedupeConsecutivePoints()
            .takeIf { it.size >= 2 }
    }

    private fun BSplineRecord.evaluateBSpline(t: Double, controlPoints: List<StepLitePoint>): StepLitePoint {
        val span = findSplineSpan(t, controlPoints.size)
        val work = ArrayList<StepLitePoint>(degree + 1)
        for (offset in 0..degree) {
            work += controlPoints[span - degree + offset]
        }
        for (level in 1..degree) {
            for (offset in degree downTo level) {
                val knotIndex = span - degree + offset
                val denominator = knots[knotIndex + degree - level + 1] - knots[knotIndex]
                val alpha = if (denominator == 0.0) 0.0 else (t - knots[knotIndex]) / denominator
                work[offset] = work[offset - 1].lerp(work[offset], alpha)
            }
        }
        return work[degree]
    }

    private fun BSplineRecord.evaluateRationalBSpline(
        t: Double,
        controlPoints: List<StepLitePoint>,
        weights: List<Double>
    ): StepLitePoint {
        val span = findSplineSpan(t, controlPoints.size)
        val work = ArrayList<WeightedPoint>(degree + 1)
        for (offset in 0..degree) {
            val index = span - degree + offset
            val point = controlPoints[index]
            val weight = weights[index]
            work += WeightedPoint(
                x = point.x * weight,
                y = point.y * weight,
                z = point.z * weight,
                weight = weight
            )
        }
        for (level in 1..degree) {
            for (offset in degree downTo level) {
                val knotIndex = span - degree + offset
                val denominator = knots[knotIndex + degree - level + 1] - knots[knotIndex]
                val alpha = if (denominator == 0.0) 0.0 else (t - knots[knotIndex]) / denominator
                work[offset] = work[offset - 1].lerp(work[offset], alpha)
            }
        }
        return work[degree].toPointOrNull() ?: controlPoints[span]
    }

    private fun WeightedPoint.lerp(other: WeightedPoint, alpha: Double): WeightedPoint {
        return WeightedPoint(
            x = x + (other.x - x) * alpha,
            y = y + (other.y - y) * alpha,
            z = z + (other.z - z) * alpha,
            weight = weight + (other.weight - weight) * alpha
        )
    }

    private fun WeightedPoint.toPointOrNull(): StepLitePoint? {
        if (kotlin.math.abs(weight) <= CoordinateTolerance) return null
        return StepLitePoint(
            x = x / weight,
            y = y / weight,
            z = z / weight
        )
    }

    private fun BSplineRecord.findSplineSpan(t: Double, controlPointCount: Int): Int {
        val lastSpan = controlPointCount - 1
        if (t >= knots[lastSpan + 1]) return lastSpan
        var low = degree
        var high = controlPointCount
        var middle = (low + high) / 2
        while (t < knots[middle] || t >= knots[middle + 1]) {
            if (t < knots[middle]) {
                high = middle
            } else {
                low = middle
            }
            middle = (low + high) / 2
        }
        return middle
    }

    private fun String.toBSplineRecord(): BSplineRecord? {
        val degree = topLevelNumbers().firstOrNull()?.toInt() ?: return null
        if (degree < 1) return null
        val controlPointIds = refs().takeIf { it.size > degree } ?: return null
        val numberTuples = topLevelNumberTuples()
        return toBSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            numberTuples = numberTuples,
            weights = null
        )
    }

    private fun String.toComplexBSplineRecord(): BSplineRecord? {
        val curveArgs = entityArgs("B_SPLINE_CURVE") ?: return null
        val knotArgs = entityArgs("B_SPLINE_CURVE_WITH_KNOTS") ?: return null
        val degree = curveArgs.topLevelNumbers().firstOrNull()?.toInt() ?: return null
        if (degree < 1) return null
        val controlPointIds = curveArgs.refs().takeIf { it.size > degree } ?: return null
        val weights = entityArgs("RATIONAL_B_SPLINE_CURVE")
            ?.deepNumberTuples()
            ?.lastOrNull()
        return knotArgs.toBSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            numberTuples = knotArgs.topLevelNumberTuples(),
            weights = weights
        )
    }

    private fun String.toCircleRecord(): CircleRecord? {
        val placementId = refs().firstOrNull() ?: return null
        val radius = topLevelNumbers().lastOrNull() ?: return null
        if (radius <= 0.0) return null
        return CircleRecord(
            placementId = placementId,
            radius = radius
        )
    }

    private fun String.toEllipseRecord(): EllipseRecord? {
        val placementId = refs().firstOrNull() ?: return null
        val radii = topLevelNumbers().takeLast(2)
        if (radii.size != 2 || radii[0] <= 0.0 || radii[1] <= 0.0) return null
        return EllipseRecord(
            placementId = placementId,
            majorRadius = radii[0],
            minorRadius = radii[1]
        )
    }

    private fun String.toBSplineRecord(
        degree: Int,
        controlPointIds: List<Int>,
        numberTuples: List<List<Double>>,
        weights: List<Double>?
    ): BSplineRecord? {
        val multiplicities = numberTuples.getOrNull(numberTuples.size - 2)
            ?.map { it.toInt() }
            ?: return null
        val knotValues = numberTuples.lastOrNull() ?: return null
        if (multiplicities.size != knotValues.size || multiplicities.any { it <= 0 }) return null
        if (weights != null && (weights.size != controlPointIds.size || weights.any { it <= 0.0 })) return null
        val expandedKnots = ArrayList<Double>()
        knotValues.forEachIndexed { index, knot ->
            repeat(multiplicities[index]) {
                expandedKnots += knot
            }
        }
        if (expandedKnots.size != controlPointIds.size + degree + 1) return null
        return BSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            knots = expandedKnots,
            weights = weights
        )
    }

    private fun String.toTrimmedCurveRecord(): CurveWrapperRecord? {
        val basisCurveId = refs().firstOrNull() ?: return null
        return CurveWrapperRecord(
            basisCurveId = basisCurveId,
            sameSense = lastTopLevelLogical() ?: true
        )
    }

    private fun String.toBasisCurveWrapperRecord(): CurveWrapperRecord? {
        val basisCurveId = refs().firstOrNull() ?: return null
        return CurveWrapperRecord(
            basisCurveId = basisCurveId,
            sameSense = true
        )
    }

    private companion object {
        private const val StepHeader = "ISO-10303-21;"
        private const val DefaultMaxBytes = 16 * 1024 * 1024
        private const val DefaultMaxRecords = 250_000
        private const val DefaultMaxEntities = 100_000
        private const val CircleSegments = 32
        private const val EllipseSegments = 32
        private const val SplineSegments = 32
        private const val MaxCurveWrapperDepth = 8
        private val DefaultXAxis = DirectionRecord(1.0, 0.0, 0.0)
        private val DefaultYAxis = DirectionRecord(0.0, 1.0, 0.0)
        private val DefaultZAxis = DirectionRecord(0.0, 0.0, 1.0)
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
        if (this[index] == '\'') {
            index = skipStepString(index) + 1
        } else if (this[index] == '#') {
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
    var index = 0
    while (index < length) {
        when (this[index]) {
            '\'' -> index = skipStepString(index)
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
        index += 1
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

    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char == '\'' -> {
                flush(index)
                index = skipStepString(index)
            }
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
        index += 1
    }
    flush(length)
    return values
}

private fun String.topLevelNumberTuples(): List<List<Double>> {
    val tuples = ArrayList<List<Double>>()
    var depth = 0
    var tupleStart = -1
    var index = 0
    while (index < length) {
        when (this[index]) {
            '\'' -> index = skipStepString(index)
            '(' -> {
                depth += 1
                if (depth == 1) tupleStart = index + 1
            }
            ')' -> {
                if (depth == 1 && tupleStart >= 0) {
                    val values = substring(tupleStart, index)
                        .split(',')
                        .map { it.trim() }
                        .takeIf { tokens -> tokens.all { token -> token.toStepDoubleOrNull() != null } }
                        ?.mapNotNull { it.toStepDoubleOrNull() }
                    if (!values.isNullOrEmpty()) tuples += values
                }
                depth -= 1
            }
        }
        index += 1
    }
    return tuples
}

private fun String.deepNumberTuples(): List<List<Double>> {
    val tuples = ArrayList<List<Double>>()
    val tupleStarts = ArrayList<Int>()
    var index = 0
    while (index < length) {
        when (this[index]) {
            '\'' -> index = skipStepString(index)
            '(' -> tupleStarts += index + 1
            ')' -> {
                val tupleStart = tupleStarts.removeLastOrNull()
                if (tupleStart != null) {
                    val values = substring(tupleStart, index)
                        .split(',')
                        .map { it.trim() }
                        .takeIf { tokens -> tokens.all { token -> token.toStepDoubleOrNull() != null } }
                        ?.mapNotNull { it.toStepDoubleOrNull() }
                    if (!values.isNullOrEmpty()) tuples += values
                }
            }
        }
        index += 1
    }
    return tuples
}

private fun String.entityArgs(entityName: String): String? {
    var index = 0
    while (index < length) {
        if (this[index] == '\'') {
            index = skipStepString(index) + 1
            continue
        }
        if (matchesEntityNameAt(index, entityName)) {
            var open = index + entityName.length
            while (open < length && this[open].isWhitespace()) open += 1
            val close = matchingParenEnd(open) ?: return null
            return substring(open + 1, close)
        }
        index += 1
    }
    return null
}

private fun String.matchesEntityNameAt(index: Int, entityName: String): Boolean {
    if (!regionMatches(index, entityName, 0, entityName.length, ignoreCase = true)) return false
    val previous = getOrNull(index - 1)
    if (previous != null && (previous.isLetterOrDigit() || previous == '_')) return false
    var cursor = index + entityName.length
    while (cursor < length && this[cursor].isWhitespace()) cursor += 1
    return getOrNull(cursor) == '('
}

private fun String.matchingParenEnd(open: Int): Int? {
    if (getOrNull(open) != '(') return null
    var depth = 0
    var index = open
    while (index < length) {
        when (this[index]) {
            '\'' -> index = skipStepString(index)
            '(' -> depth += 1
            ')' -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
        index += 1
    }
    return null
}

private fun String.lastTopLevelLogical(): Boolean? {
    var depth = 0
    var logical: Boolean? = null
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char == '\'' -> index = skipStepString(index)
            char == '(' -> depth += 1
            char == ')' -> depth -= 1
            depth == 0 && regionMatches(index, ".T.", 0, 3, ignoreCase = true) -> {
                logical = true
                index += 2
            }
            depth == 0 && regionMatches(index, ".F.", 0, 3, ignoreCase = true) -> {
                logical = false
                index += 2
            }
        }
        index += 1
    }
    return logical
}

private fun String.skipStepString(start: Int): Int {
    var index = start + 1
    while (index < length) {
        if (this[index] == '\'') {
            if (getOrNull(index + 1) == '\'') {
                index += 2
                continue
            }
            return index
        }
        index += 1
    }
    return lastIndex
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

private fun List<Double>.toDirection(): DirectionRecord {
    return DirectionRecord(
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

private fun StepLitePoint.lerp(other: StepLitePoint, alpha: Double): StepLitePoint {
    return StepLitePoint(
        x = x + (other.x - x) * alpha,
        y = y + (other.y - y) * alpha,
        z = z + (other.z - z) * alpha
    )
}

private fun StepLitePoint.angleFrom(center: StepLitePoint): Double {
    return atan2(y - center.y, x - center.x)
}

private fun DirectionRecord.dot(other: DirectionRecord): Double {
    return x * other.x + y * other.y + z * other.z
}

private fun DirectionRecord.cross(other: DirectionRecord): DirectionRecord {
    return DirectionRecord(
        x = y * other.z - z * other.y,
        y = z * other.x - x * other.z,
        z = x * other.y - y * other.x
    )
}

private fun DirectionRecord.minus(other: DirectionRecord): DirectionRecord {
    return DirectionRecord(
        x = x - other.x,
        y = y - other.y,
        z = z - other.z
    )
}

private fun DirectionRecord.scale(value: Double): DirectionRecord {
    return DirectionRecord(
        x = x * value,
        y = y * value,
        z = z * value
    )
}

private fun DirectionRecord.normalizedOrNull(): DirectionRecord? {
    val length = sqrt(x * x + y * y + z * z)
    if (length <= CoordinateTolerance) return null
    return scale(1.0 / length)
}

private fun PlacementBasis.isFlatInPreviewPlane(): Boolean {
    return kotlin.math.abs(xAxis.z) <= CoordinateTolerance &&
        kotlin.math.abs(yAxis.z) <= CoordinateTolerance
}

private fun StepLitePoint.ellipseAngleFrom(
    center: StepLitePoint,
    basis: PlacementBasis,
    majorRadius: Double,
    minorRadius: Double
): Double {
    val relative = DirectionRecord(
        x = x - center.x,
        y = y - center.y,
        z = z - center.z
    )
    return atan2(relative.dot(basis.yAxis) / minorRadius, relative.dot(basis.xAxis) / majorRadius)
}

private fun positiveSweep(from: Double, to: Double): Double {
    var sweep = to - from
    while (sweep <= 0.0) sweep += 2.0 * PI
    return sweep
}

private fun List<StepLitePoint>.orientedBetween(start: StepLitePoint, end: StepLitePoint): List<StepLitePoint> {
    return if (
        firstOrNull()?.samePositionAs(end) == true &&
        lastOrNull()?.samePositionAs(start) == true
    ) {
        asReversed()
    } else {
        this
    }
}

private fun List<StepLitePoint>.dedupeConsecutivePoints(): List<StepLitePoint> {
    val points = ArrayList<StepLitePoint>(size)
    forEach { point ->
        if (points.lastOrNull()?.samePositionAs(point) != true) {
            points += point
        }
    }
    return points
}

private fun StepLiteEntity.bounds(): StepLiteBounds {
    return when (this) {
        is StepLiteEntity.Line -> StepLiteBounds.fromPoint(start).include(end)
        is StepLiteEntity.Polyline -> points.drop(1)
            .fold(StepLiteBounds.fromPoint(points.first())) { bounds, point -> bounds.include(point) }
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
