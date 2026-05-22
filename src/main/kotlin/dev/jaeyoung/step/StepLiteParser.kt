package dev.jaeyoung.step

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
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
        val parabolas = linkedMapOf<Int, ParabolaRecord>()
        val hyperbolas = linkedMapOf<Int, HyperbolaRecord>()
        val vectors = linkedMapOf<Int, VectorRecord>()
        val lineRecords = linkedMapOf<Int, LineRecord>()
        val splines = linkedMapOf<Int, BSplineRecord>()
        val curveWrappers = linkedMapOf<Int, CurveWrapperRecord>()
        val compositeSegments = linkedMapOf<Int, CompositeCurveSegmentRecord>()
        val compositeCurves = linkedMapOf<Int, List<Int>>()
        val lineCurves = linkedSetOf<Int>()
        val polylineCurves = linkedMapOf<Int, List<Int>>()
        val polyLoops = linkedMapOf<Int, List<Int>>()
        val edges = linkedMapOf<Int, EdgeCurveRecord>()
        val orientedEdges = linkedMapOf<Int, OrientedEdgeRecord>()
        val edgeLoops = linkedMapOf<Int, List<Int>>()
        val invalidWrapperBasisCurveIds = linkedSetOf<Int>()
        val invalidEdgeLoopMemberIds = linkedSetOf<Int>()
        val invalidEdgeCurveGeometryIds = linkedSetOf<Int>()
        var productName = ""
        var fileName = ""
        var unit = StepLiteUnit.UNKNOWN
        var recordCount = 0
        var unsupported = 0

        StepRecordSequence(text).forEach { raw ->
            recordCount += 1
            if (recordCount > maxRecords) throw StepLiteTooLargeException()
            if (fileName.isBlank()) {
                fileName = raw.headerFileName().orEmpty()
            }
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
                "AXIS2_PLACEMENT_2D" -> {
                    val refs = record.args.refs()
                    if (refs.isNotEmpty()) {
                        placements[record.id] = AxisPlacementRecord(
                            locationPointId = refs[0],
                            axisDirectionId = null,
                            refDirectionId = refs.getOrNull(1)
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
                "PARABOLA" -> {
                    val parabola = record.args.toParabolaRecord()
                    if (parabola != null) parabolas[record.id] = parabola
                }
                "HYPERBOLA" -> {
                    val hyperbola = record.args.toHyperbolaRecord()
                    if (hyperbola != null) hyperbolas[record.id] = hyperbola
                }
                "VECTOR" -> {
                    val vector = record.args.toVectorRecord()
                    if (vector != null) vectors[record.id] = vector
                }
                "LINE" -> {
                    lineCurves += record.id
                    val line = record.args.toLineRecord()
                    if (line != null) lineRecords[record.id] = line
                }
                "POLYLINE" -> {
                    val polyline = record.args.toPolylineRecord()
                    if (polyline != null) polylineCurves[record.id] = polyline
                }
                "POLY_LOOP" -> {
                    val loop = record.args.toPolyLoopRecord()
                    if (loop != null) polyLoops[record.id] = loop
                }
                "B_SPLINE_CURVE_WITH_KNOTS" -> {
                    val spline = record.args.toBSplineRecord()
                    if (spline != null) splines[record.id] = spline
                }
                "B_SPLINE_CURVE" -> {
                    val spline = record.args.toSimpleBSplineRecord()
                    if (spline != null) splines[record.id] = spline
                }
                "BEZIER_CURVE" -> {
                    val spline = record.args.toSimpleBezierRecord()
                    if (spline != null) splines[record.id] = spline
                }
                "QUASI_UNIFORM_CURVE" -> {
                    val spline = record.args.toSimpleQuasiUniformRecord()
                    if (spline != null) splines[record.id] = spline
                }
                "UNIFORM_CURVE" -> {
                    val spline = record.args.toSimpleUniformRecord()
                    if (spline != null) splines[record.id] = spline
                }
                "TRIMMED_CURVE" -> {
                    val trimmedCurve = record.args.toTrimmedCurveRecord()
                    if (trimmedCurve != null) {
                        curveWrappers[record.id] = trimmedCurve
                    } else {
                        record.args.refs().firstOrNull()?.let(invalidWrapperBasisCurveIds::add)
                    }
                }
                "SURFACE_CURVE", "SEAM_CURVE" -> {
                    val wrappedCurve = record.args.toBasisCurveWrapperRecord()
                    if (wrappedCurve != null) curveWrappers[record.id] = wrappedCurve
                }
                "OFFSET_CURVE_3D" -> {
                    val offsetCurve = record.args.toOffsetCurveRecord()
                    if (offsetCurve != null) {
                        curveWrappers[record.id] = offsetCurve
                    } else {
                        record.args.refs().firstOrNull()?.let(invalidWrapperBasisCurveIds::add)
                    }
                }
                "COMPOSITE_CURVE_SEGMENT" -> {
                    val segment = record.args.toCompositeCurveSegmentRecord()
                    if (segment != null) compositeSegments[record.id] = segment
                }
                "COMPOSITE_CURVE" -> {
                    val segmentIds = record.args.toCompositeCurveRecord()
                    if (segmentIds != null) compositeCurves[record.id] = segmentIds
                }
                "ORIENTED_EDGE" -> {
                    val orientedEdge = record.args.toOrientedEdgeRecord()
                    if (orientedEdge != null) orientedEdges[record.id] = orientedEdge
                }
                "EDGE_LOOP" -> {
                    val loop = record.args.toEdgeLoopRecord()
                    if (loop != null) {
                        edgeLoops[record.id] = loop
                    } else {
                        record.args.referenceAggregateFieldRefs(fieldIndex = 1)?.let(invalidEdgeLoopMemberIds::addAll)
                    }
                }
                "COMPLEX" -> {
                    unit = maxOf(unit, record.args.resolveUnit())
                    val direction = record.args.entityArgs("DIRECTION")
                        ?.firstNumberTuple(minSize = 2)
                        ?.toDirection()
                    if (direction != null) directions[record.id] = direction
                    val axisPlacementRefs = record.args.entityArgs("AXIS2_PLACEMENT_3D")?.refs()
                    if (!axisPlacementRefs.isNullOrEmpty()) {
                        placements[record.id] = AxisPlacementRecord(
                            locationPointId = axisPlacementRefs[0],
                            axisDirectionId = axisPlacementRefs.getOrNull(1),
                            refDirectionId = axisPlacementRefs.getOrNull(2)
                        )
                    }
                    val axis2dPlacementRefs = record.args.entityArgs("AXIS2_PLACEMENT_2D")?.refs()
                    if (!axis2dPlacementRefs.isNullOrEmpty()) {
                        placements[record.id] = AxisPlacementRecord(
                            locationPointId = axis2dPlacementRefs[0],
                            axisDirectionId = null,
                            refDirectionId = axis2dPlacementRefs.getOrNull(1)
                        )
                    }
                    val point = record.args.entityArgs("CARTESIAN_POINT")
                        ?.firstNumberTuple(minSize = 2)
                        ?.toPoint()
                    if (point != null) points[record.id] = point
                    val vertexPoint = record.args.entityArgs("VERTEX_POINT")?.refs()?.firstOrNull()
                    if (vertexPoint != null) vertexPoints[record.id] = vertexPoint
                    val spline = record.args.toComplexBSplineRecord()
                    if (spline != null) splines[record.id] = spline
                    val polyline = record.args.entityArgs("POLYLINE")?.toPolylineRecord()
                    if (polyline != null) polylineCurves[record.id] = polyline
                    val polyLoop = record.args.entityArgs("POLY_LOOP")?.toPolyLoopRecord()
                    if (polyLoop != null) polyLoops[record.id] = polyLoop
                    val circle = record.args.entityArgs("CIRCLE")?.toCircleRecord()
                    if (circle != null) circles[record.id] = circle
                    val ellipse = record.args.entityArgs("ELLIPSE")?.toEllipseRecord()
                    if (ellipse != null) ellipses[record.id] = ellipse
                    val parabola = record.args.entityArgs("PARABOLA")?.toParabolaRecord()
                    if (parabola != null) parabolas[record.id] = parabola
                    val hyperbola = record.args.entityArgs("HYPERBOLA")?.toHyperbolaRecord()
                    if (hyperbola != null) hyperbolas[record.id] = hyperbola
                    val vector = record.args.entityArgs("VECTOR")?.toVectorRecord()
                    if (vector != null) vectors[record.id] = vector
                    val trimmedCurveArgs = record.args.entityArgs("TRIMMED_CURVE")
                    val trimmedCurve = trimmedCurveArgs?.toTrimmedCurveRecord()
                    if (trimmedCurve != null) {
                        curveWrappers[record.id] = trimmedCurve
                    } else {
                        trimmedCurveArgs?.refs()?.firstOrNull()?.let(invalidWrapperBasisCurveIds::add)
                    }
                    val surfaceCurve = record.args.entityArgs("SURFACE_CURVE")?.toBasisCurveWrapperRecord()
                    if (surfaceCurve != null) curveWrappers[record.id] = surfaceCurve
                    val seamCurve = record.args.entityArgs("SEAM_CURVE")?.toBasisCurveWrapperRecord()
                    if (seamCurve != null) curveWrappers[record.id] = seamCurve
                    val offsetCurveArgs = record.args.entityArgs("OFFSET_CURVE_3D")
                    val offsetCurve = offsetCurveArgs?.toOffsetCurveRecord()
                    if (offsetCurve != null) {
                        curveWrappers[record.id] = offsetCurve
                    } else {
                        offsetCurveArgs?.refs()?.firstOrNull()?.let(invalidWrapperBasisCurveIds::add)
                    }
                    val compositeSegment = record.args.entityArgs("COMPOSITE_CURVE_SEGMENT")
                        ?.toCompositeCurveSegmentRecord()
                    if (compositeSegment != null) compositeSegments[record.id] = compositeSegment
                    val compositeCurve = record.args.entityArgs("COMPOSITE_CURVE")?.toCompositeCurveRecord()
                    if (compositeCurve != null) compositeCurves[record.id] = compositeCurve
                    val orientedEdge = record.args.entityArgs("ORIENTED_EDGE")?.toOrientedEdgeRecord()
                    if (orientedEdge != null) orientedEdges[record.id] = orientedEdge
                    val edgeLoop = record.args.entityArgs("EDGE_LOOP")?.toEdgeLoopRecord()
                    if (edgeLoop != null) {
                        edgeLoops[record.id] = edgeLoop
                    } else {
                        record.args.entityArgs("EDGE_LOOP")
                            ?.referenceAggregateFieldRefs(fieldIndex = 1)
                            ?.let(invalidEdgeLoopMemberIds::addAll)
                    }
                    val lineArgs = record.args.entityArgs("LINE")
                    if (lineArgs != null) {
                        lineCurves += record.id
                        val line = lineArgs.toLineRecord()
                        if (line != null) lineRecords[record.id] = line
                    }
                    val edgeCurveArgs = record.args.entityArgs("EDGE_CURVE")
                    if (edgeCurveArgs != null) {
                        val edge = edgeCurveArgs.toEdgeCurveRecord(sourceId = record.id)
                        if (edge != null) {
                            edges[record.id] = edge
                        } else {
                            edgeCurveArgs.referenceFieldRef(fieldIndex = 3)?.let(invalidEdgeCurveGeometryIds::add)
                            unsupported += 1
                        }
                    }
                }
                "EDGE_CURVE" -> {
                    val edge = record.args.toEdgeCurveRecord(sourceId = record.id)
                    if (edge != null) {
                        edges[record.id] = edge
                    } else {
                        record.args.referenceFieldRef(fieldIndex = 3)?.let(invalidEdgeCurveGeometryIds::add)
                        unsupported += 1
                    }
                }
                "SI_UNIT", "CONVERSION_BASED_UNIT" -> {
                    unit = maxOf(unit, record.args.resolveUnit())
                }
                else -> Unit
            }
        }

        val entities = ArrayList<StepLiteEntity>(
            min(
                edgeLoops.size + edges.size + circles.size + ellipses.size + polyLoops.size +
                    lineRecords.size + polylineCurves.size + splines.size + curveWrappers.size + compositeCurves.size,
                maxEntities
            )
        )

        fun addEntity(entity: StepLiteEntity) {
            if (!entity.hasFiniteGeometry()) {
                unsupported += 1
                return
            }
            if (entities.size >= maxEntities) throw StepLiteTooLargeException()
            entities += entity
        }

        val referencedCurveIds = edges.values.asSequence()
            .map { it.curveId }
            .plus(curveWrappers.values.asSequence().map { it.basisCurveId })
            .plus(compositeSegments.values.asSequence().map { it.parentCurveId })
            .plus(invalidWrapperBasisCurveIds.asSequence())
            .plus(invalidEdgeCurveGeometryIds.asSequence())
            .toSet()
        val loopEdgeIds = linkedSetOf<Int>()
        for ((sourceId, orientedEdgeIds) in edgeLoops) {
            orientedEdgeIds.toEdgeLoopPolyline(
                sourceId = sourceId,
                points = points,
                vertexPoints = vertexPoints,
                directions = directions,
                placements = placements,
                circles = circles,
                ellipses = ellipses,
                parabolas = parabolas,
                hyperbolas = hyperbolas,
                splines = splines,
                vectors = vectors,
                curveWrappers = curveWrappers,
                compositeSegments = compositeSegments,
                compositeCurves = compositeCurves,
                lineCurves = lineCurves,
                lineRecords = lineRecords,
                polylineCurves = polylineCurves,
                edges = edges,
                orientedEdges = orientedEdges
            )?.let { loop ->
                addEntity(loop)
                loopEdgeIds += orientedEdgeIds.mapNotNull { edgeId ->
                    orientedEdges[edgeId]?.edgeId ?: edgeId.takeIf(edges::containsKey)
                }
            }
        }
        val invalidLoopEdgeIds = invalidEdgeLoopMemberIds.mapNotNullTo(linkedSetOf()) { loopMemberId ->
            orientedEdges[loopMemberId]?.edgeId ?: loopMemberId.takeIf(edges::containsKey)
        }
        for (edge in edges.values) {
            if (edge.sourceId in loopEdgeIds) continue
            if (edge.sourceId in invalidLoopEdgeIds) continue
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
                    parabolas = parabolas,
                    hyperbolas = hyperbolas,
                    splines = splines,
                    vectors = vectors,
                    curveWrappers = curveWrappers,
                    compositeSegments = compositeSegments,
                    compositeCurves = compositeCurves,
                    lineCurves = lineCurves,
                    lineRecords = lineRecords,
                    polylineCurves = polylineCurves
                )
                if (entity != null) {
                    addEntity(entity)
                } else {
                    unsupported += 1
                }
            } else {
                unsupported += 1
            }
        }
        for ((sourceId, line) in lineRecords) {
            if (sourceId in referencedCurveIds) continue
            line.toStandaloneEntity(
                sourceId = sourceId,
                points = points,
                directions = directions,
                vectors = vectors
            )?.let { addEntity(it) }
        }
        for ((sourceId, circle) in circles) {
            if (sourceId in referencedCurveIds) continue
            circle.toStandaloneEntity(
                sourceId = sourceId,
                points = points,
                directions = directions,
                placements = placements
            )?.let { addEntity(it) }
        }
        for ((sourceId, ellipse) in ellipses) {
            if (sourceId in referencedCurveIds) continue
            ellipse.toStandalonePolyline(
                sourceId = sourceId,
                points = points,
                directions = directions,
                placements = placements
            )?.let { addEntity(it) }
        }
        for ((sourceId, wrapper) in curveWrappers) {
            if (sourceId in referencedCurveIds) continue
            wrapper.toStandaloneEntity(
                sourceId = sourceId,
                points = points,
                directions = directions,
                placements = placements,
                circles = circles,
                ellipses = ellipses,
                parabolas = parabolas,
                hyperbolas = hyperbolas,
                splines = splines,
                vectors = vectors,
                curveWrappers = curveWrappers,
                compositeSegments = compositeSegments,
                compositeCurves = compositeCurves,
                lineCurves = lineCurves,
                lineRecords = lineRecords,
                polylineCurves = polylineCurves
            )?.let { addEntity(it) }
        }
        for ((sourceId, pointIds) in polylineCurves) {
            if (sourceId in referencedCurveIds) continue
            pointIds.toPolylinePoints(points)?.let { polylinePoints ->
                addEntity(
                    StepLiteEntity.Polyline(
                        points = polylinePoints,
                        sourceId = sourceId
                    )
                )
            }
        }
        for ((sourceId, spline) in splines) {
            if (sourceId in referencedCurveIds) continue
            spline.toPolylinePoints(points)?.let { splinePoints ->
                addEntity(
                    StepLiteEntity.Polyline(
                        points = splinePoints,
                        sourceId = sourceId
                    )
                )
            }
        }
        for ((sourceId, segmentIds) in compositeCurves) {
            if (sourceId in referencedCurveIds) continue
            segmentIds.toCompositeCurvePoints(
                points = points,
                splines = splines,
                directions = directions,
                vectors = vectors,
                placements = placements,
                circles = circles,
                ellipses = ellipses,
                parabolas = parabolas,
                hyperbolas = hyperbolas,
                curveWrappers = curveWrappers,
                compositeSegments = compositeSegments,
                compositeCurves = compositeCurves,
                lineRecords = lineRecords,
                polylineCurves = polylineCurves
            )?.let { compositePoints ->
                addEntity(
                    StepLiteEntity.Polyline(
                        points = compositePoints,
                        sourceId = sourceId
                    )
                )
            }
        }
        for ((sourceId, pointIds) in polyLoops) {
            pointIds.toClosedPolylinePoints(points)?.let { loopPoints ->
                addEntity(
                    StepLiteEntity.Polyline(
                        points = loopPoints,
                        sourceId = sourceId
                    )
                )
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
                name = productName.ifBlank { fileName }.ifBlank { "STEP model" },
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

    private data class OrientedEdgeRecord(
        val edgeId: Int,
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

    private data class ParabolaRecord(
        val placementId: Int,
        val focalDistance: Double
    )

    private data class HyperbolaRecord(
        val placementId: Int,
        val semiAxis: Double,
        val semiImagAxis: Double
    )

    private data class VectorRecord(
        val directionId: Int,
        val magnitude: Double
    )

    private data class LineRecord(
        val pointId: Int,
        val vectorId: Int
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
        val sameSense: Boolean,
        val trimStartPointId: Int? = null,
        val trimEndPointId: Int? = null,
        val trimStartParameter: Double? = null,
        val trimEndParameter: Double? = null,
        val offsetDistance: Double? = null,
        val offsetDirectionId: Int? = null
    )

    private data class CompositeCurveSegmentRecord(
        val parentCurveId: Int,
        val sameSense: Boolean
    )

    private data class ResolvedCurveRecord(
        val curveId: Int,
        val sameSense: Boolean,
        val offset: DirectionRecord? = null,
        val trimStartPoint: StepLitePoint? = null,
        val trimEndPoint: StepLitePoint? = null,
        val trimStartParameter: Double? = null,
        val trimEndParameter: Double? = null
    )

    private fun LineRecord.toStandaloneEntity(
        sourceId: Int,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        vectors: Map<Int, VectorRecord>
    ): StepLiteEntity.Line? {
        val linePoints = toPoints(
            points = points,
            directions = directions,
            vectors = vectors
        ) ?: return null
        return StepLiteEntity.Line(
            start = linePoints[0],
            end = linePoints[1],
            sourceId = sourceId
        )
    }

    private fun LineRecord.toPoints(
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        vectors: Map<Int, VectorRecord>
    ): List<StepLitePoint>? {
        val start = pointAtParameter(0.0, points, directions, vectors) ?: return null
        val end = pointAtParameter(1.0, points, directions, vectors) ?: return null
        return listOf(start, end)
    }

    private fun LineRecord.toParameterTrimmedEntity(
        sourceId: Int,
        sameSense: Boolean,
        startParameter: Double,
        endParameter: Double,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        vectors: Map<Int, VectorRecord>
    ): StepLiteEntity.Line? {
        val startValue = if (sameSense) startParameter else endParameter
        val endValue = if (sameSense) endParameter else startParameter
        val start = pointAtParameter(startValue, points, directions, vectors) ?: return null
        val end = pointAtParameter(endValue, points, directions, vectors) ?: return null
        if (start.samePositionAs(end)) return null
        return StepLiteEntity.Line(start = start, end = end, sourceId = sourceId)
    }

    private fun LineRecord.pointAtParameter(
        parameter: Double,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        vectors: Map<Int, VectorRecord>
    ): StepLitePoint? {
        val start = points[pointId] ?: return null
        val vector = vectors[vectorId] ?: return null
        val direction = directions[vector.directionId]?.normalizedOrNull() ?: return null
        return start.offsetBy(direction, vector.magnitude * parameter)
    }

    private fun List<Int>.toEdgeLoopPolyline(
        sourceId: Int,
        points: Map<Int, StepLitePoint>,
        vertexPoints: Map<Int, Int>,
        directions: Map<Int, DirectionRecord>,
        placements: Map<Int, AxisPlacementRecord>,
        circles: Map<Int, CircleRecord>,
        ellipses: Map<Int, EllipseRecord>,
        parabolas: Map<Int, ParabolaRecord>,
        hyperbolas: Map<Int, HyperbolaRecord>,
        splines: Map<Int, BSplineRecord>,
        vectors: Map<Int, VectorRecord>,
        curveWrappers: Map<Int, CurveWrapperRecord>,
        compositeSegments: Map<Int, CompositeCurveSegmentRecord>,
        compositeCurves: Map<Int, List<Int>>,
        lineCurves: Set<Int>,
        lineRecords: Map<Int, LineRecord>,
        polylineCurves: Map<Int, List<Int>>,
        edges: Map<Int, EdgeCurveRecord>,
        orientedEdges: Map<Int, OrientedEdgeRecord>
    ): StepLiteEntity.Polyline? {
        val merged = ArrayList<StepLitePoint>()
        for (loopMemberId in this) {
            val orientedEdge = orientedEdges[loopMemberId]
            val memberEdgeId = orientedEdge?.edgeId ?: loopMemberId
            val memberSameSense = orientedEdge?.sameSense ?: true
            val edge = edges[memberEdgeId] ?: return null
            val startVertexId = if (memberSameSense) edge.startVertexId else edge.endVertexId
            val endVertexId = if (memberSameSense) edge.endVertexId else edge.startVertexId
            val start = vertexPoints[startVertexId]?.let(points::get) ?: return null
            val end = vertexPoints[endVertexId]?.let(points::get) ?: return null
            val segment = edge.copy(sameSense = edge.sameSense == memberSameSense)
                .toEntity(
                    start = start,
                    end = end,
                    points = points,
                    directions = directions,
                    placements = placements,
                    circles = circles,
                    ellipses = ellipses,
                    parabolas = parabolas,
                    hyperbolas = hyperbolas,
                    splines = splines,
                    vectors = vectors,
                    curveWrappers = curveWrappers,
                    compositeSegments = compositeSegments,
                    compositeCurves = compositeCurves,
                    lineCurves = lineCurves,
                    lineRecords = lineRecords,
                    polylineCurves = polylineCurves
                )
                ?.toPathPoints(topologyStart = start, topologyEnd = end)
                ?.orientedBetween(start = start, end = end)
                ?: return null
            if (
                segment.firstOrNull()?.samePositionAs(start) != true ||
                segment.lastOrNull()?.samePositionAs(end) != true
            ) {
                return null
            }
            if (merged.isEmpty()) {
                merged += segment
            } else if (merged.last().samePositionAs(segment.first())) {
                merged += segment.drop(1)
            } else {
                return null
            }
        }
        val loopPoints = merged.dedupeConsecutivePoints().takeIf { it.size >= 3 } ?: return null
        if (!loopPoints.first().samePositionAs(loopPoints.last())) return null
        return StepLiteEntity.Polyline(
            points = loopPoints,
            sourceId = sourceId
        )
    }

    private fun StepLiteEntity.toPathPoints(
        topologyStart: StepLitePoint? = null,
        topologyEnd: StepLitePoint? = null
    ): List<StepLitePoint>? {
        return when (this) {
            is StepLiteEntity.Line -> listOf(start, end)
            is StepLiteEntity.Polyline -> points
            is StepLiteEntity.Circle -> {
                val startAngle = if (
                    topologyStart != null &&
                    topologyEnd != null &&
                    topologyStart.samePositionAs(topologyEnd)
                ) {
                    topologyStart.angleFrom(center)
                } else {
                    0.0
                }
                sampleCircularPath(center, radius, startAngle, startAngle + 2.0 * PI)
            }
            is StepLiteEntity.Arc -> sampleCircularPath(center, radius, startAngleRadians, endAngleRadians)
        }.takeIf { it.size >= 2 }
    }

    private fun sampleCircularPath(
        center: StepLitePoint,
        radius: Double,
        startAngleRadians: Double,
        endAngleRadians: Double
    ): List<StepLitePoint> {
        val sweep = positiveSweep(startAngleRadians, endAngleRadians)
        val segments = max(2, ceil(sweep / (2.0 * PI) * CircleSegments).toInt())
        return List(segments + 1) { index ->
            val angle = startAngleRadians + sweep * index / segments
            StepLitePoint(
                x = center.x + radius * cos(angle),
                y = center.y + radius * sin(angle),
                z = center.z
            )
        }
    }

    private fun EdgeCurveRecord.toEntity(
        start: StepLitePoint,
        end: StepLitePoint,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        placements: Map<Int, AxisPlacementRecord>,
        circles: Map<Int, CircleRecord>,
        ellipses: Map<Int, EllipseRecord>,
        parabolas: Map<Int, ParabolaRecord>,
        hyperbolas: Map<Int, HyperbolaRecord>,
        splines: Map<Int, BSplineRecord>,
        vectors: Map<Int, VectorRecord>,
        curveWrappers: Map<Int, CurveWrapperRecord>,
        compositeSegments: Map<Int, CompositeCurveSegmentRecord>,
        compositeCurves: Map<Int, List<Int>>,
        lineCurves: Set<Int>,
        lineRecords: Map<Int, LineRecord>,
        polylineCurves: Map<Int, List<Int>>
    ): StepLiteEntity? {
        val resolvedCurve = resolveCurve(curveWrappers, directions, points) ?: return null
        val resolvedCurveId = resolvedCurve.curveId
        val resolvedSameSense = resolvedCurve.sameSense
        val resolvedOffset = resolvedCurve.offset
        val basisStart = resolvedOffset?.let { start.offsetBy(it, -1.0) } ?: start
        val basisEnd = resolvedOffset?.let { end.offsetBy(it, -1.0) } ?: end

        fun StepLiteEntity.withResolvedOffset(): StepLiteEntity {
            return resolvedOffset?.let { offsetBy(it, 1.0) } ?: this
        }

        val circle = circles[resolvedCurveId]
        val circlePlacement = circle?.let { placements[it.placementId] }
        val center = circlePlacement?.let { points[it.locationPointId] }
        if (circle != null && circlePlacement != null && center != null) {
            val circleBasis = circlePlacement.toBasis(directions)
            val arcStart = if (resolvedSameSense) basisStart else basisEnd
            val arcEnd = if (resolvedSameSense) basisEnd else basisStart
            if (!circleBasis.isFlatInPreviewPlane()) {
                return StepLiteEntity.Polyline(
                    points = circle.toPolylinePoints(
                        center = center,
                        basis = circleBasis,
                        start = arcStart,
                        end = arcEnd,
                        closed = basisStart.samePositionAs(basisEnd)
                    ),
                    sourceId = sourceId
                ).withResolvedOffset()
            }
            return if (basisStart.samePositionAs(basisEnd)) {
                StepLiteEntity.Circle(
                    center = center,
                    radius = circle.radius,
                    sourceId = sourceId
                ).withResolvedOffset()
            } else {
                StepLiteEntity.Arc(
                    center = center,
                    radius = circle.radius,
                    startAngleRadians = arcStart.angleFrom(center),
                    endAngleRadians = arcEnd.angleFrom(center),
                    sourceId = sourceId
                ).withResolvedOffset()
            }
        }

        val ellipse = ellipses[resolvedCurveId]
        val ellipsePlacement = ellipse?.let { placements[it.placementId] }
        val ellipseCenter = ellipsePlacement?.let { points[it.locationPointId] }
        if (ellipse != null && ellipsePlacement != null && ellipseCenter != null) {
            val ellipseStart = if (resolvedSameSense) basisStart else basisEnd
            val ellipseEnd = if (resolvedSameSense) basisEnd else basisStart
            return StepLiteEntity.Polyline(
                points = ellipse.toPolylinePoints(
                    center = ellipseCenter,
                    basis = ellipsePlacement.toBasis(directions),
                    start = ellipseStart,
                    end = ellipseEnd,
                    closed = basisStart.samePositionAs(basisEnd)
                ),
                sourceId = sourceId
            ).withResolvedOffset()
        }

        val parabola = parabolas[resolvedCurveId]
        val parabolaPlacement = parabola?.let { placements[it.placementId] }
        val parabolaCenter = parabolaPlacement?.let { points[it.locationPointId] }
        if (parabola != null && parabolaPlacement != null && parabolaCenter != null) {
            return StepLiteEntity.Polyline(
                points = parabola.toPolylinePoints(
                    center = parabolaCenter,
                    basis = parabolaPlacement.toBasis(directions),
                    start = if (resolvedSameSense) basisStart else basisEnd,
                    end = if (resolvedSameSense) basisEnd else basisStart
                ),
                sourceId = sourceId
            ).withResolvedOffset()
        }

        val hyperbola = hyperbolas[resolvedCurveId]
        val hyperbolaPlacement = hyperbola?.let { placements[it.placementId] }
        val hyperbolaCenter = hyperbolaPlacement?.let { points[it.locationPointId] }
        if (hyperbola != null && hyperbolaPlacement != null && hyperbolaCenter != null) {
            return StepLiteEntity.Polyline(
                points = hyperbola.toPolylinePoints(
                    center = hyperbolaCenter,
                    basis = hyperbolaPlacement.toBasis(directions),
                    start = if (resolvedSameSense) basisStart else basisEnd,
                    end = if (resolvedSameSense) basisEnd else basisStart
                ),
                sourceId = sourceId
            ).withResolvedOffset()
        }

        val spline = splines[resolvedCurveId]
        if (spline != null) {
            val trimStartParameter = resolvedCurve.trimStartParameter
            val trimEndParameter = resolvedCurve.trimEndParameter
            val splinePoints = if (trimStartParameter != null && trimEndParameter != null) {
                spline.toPolylinePoints(
                    points = points,
                    startParameter = trimStartParameter,
                    endParameter = trimEndParameter
                )
            } else if (resolvedCurve.trimStartPoint != null && resolvedCurve.trimEndPoint != null) {
                spline.toPolylinePoints(points)
                    ?.trimmedBetween(
                        start = resolvedCurve.trimStartPoint,
                        end = resolvedCurve.trimEndPoint
                    )
            } else {
                spline.toPolylinePoints(points)
            }
                ?.orientedBetween(
                    start = if (resolvedSameSense) basisStart else basisEnd,
                    end = if (resolvedSameSense) basisEnd else basisStart
                )
            return splinePoints?.let {
                StepLiteEntity.Polyline(points = it, sourceId = sourceId).withResolvedOffset()
            }
        }

        val compositeSegmentIds = compositeCurves[resolvedCurveId]
        if (compositeSegmentIds != null) {
            val compositePoints = compositeSegmentIds.toCompositeCurvePoints(
                points = points,
                splines = splines,
                directions = directions,
                vectors = vectors,
                placements = placements,
                circles = circles,
                ellipses = ellipses,
                parabolas = parabolas,
                hyperbolas = hyperbolas,
                curveWrappers = curveWrappers,
                compositeSegments = compositeSegments,
                compositeCurves = compositeCurves,
                lineRecords = lineRecords,
                polylineCurves = polylineCurves
            )
                ?.applyPointTrim(resolvedCurve)
                ?.orientedBetween(
                    start = if (resolvedSameSense) basisStart else basisEnd,
                    end = if (resolvedSameSense) basisEnd else basisStart
                )
            return compositePoints?.let {
                StepLiteEntity.Polyline(points = it, sourceId = sourceId).withResolvedOffset()
            }
        }

        val polylinePointIds = polylineCurves[resolvedCurveId]
        if (polylinePointIds != null) {
            val polylinePoints = polylinePointIds.toPolylinePoints(points)
                ?.applyPointTrim(resolvedCurve)
                ?.orientedBetween(
                    start = if (resolvedSameSense) basisStart else basisEnd,
                    end = if (resolvedSameSense) basisEnd else basisStart
                )
            return polylinePoints?.let {
                StepLiteEntity.Polyline(points = it, sourceId = sourceId).withResolvedOffset()
            }
        }

        return if (resolvedCurveId in lineCurves) {
            StepLiteEntity.Line(
                start = if (resolvedSameSense) basisStart else basisEnd,
                end = if (resolvedSameSense) basisEnd else basisStart,
                sourceId = sourceId
            ).withResolvedOffset()
        } else {
            null
        }
    }

    private fun EdgeCurveRecord.resolveCurve(
        curveWrappers: Map<Int, CurveWrapperRecord>,
        directions: Map<Int, DirectionRecord>,
        points: Map<Int, StepLitePoint>
    ): ResolvedCurveRecord? {
        var id = curveId
        var sense = sameSense
        var offset: DirectionRecord? = null
        var trimStartPoint: StepLitePoint? = null
        var trimEndPoint: StepLitePoint? = null
        var trimStartParameter: Double? = null
        var trimEndParameter: Double? = null
        repeat(MaxCurveWrapperDepth) {
            val wrapper = curveWrappers[id] ?: return ResolvedCurveRecord(
                curveId = id,
                sameSense = sense,
                offset = offset,
                trimStartPoint = trimStartPoint,
                trimEndPoint = trimEndPoint,
                trimStartParameter = trimStartParameter,
                trimEndParameter = trimEndParameter
            )
            val offsetDistance = wrapper.offsetDistance
            val offsetDirectionId = wrapper.offsetDirectionId
            if (offsetDistance != null || offsetDirectionId != null) {
                val directionId = offsetDirectionId ?: return null
                val direction = directions[directionId]?.normalizedOrNull() ?: return null
                val delta = direction.scale(offsetDistance ?: return null)
                offset = offset?.plus(delta) ?: delta
            }
            val wrapperTrimStartPoint = wrapper.trimStartPointId?.let(points::get)
            val wrapperTrimEndPoint = wrapper.trimEndPointId?.let(points::get)
            if (wrapperTrimStartPoint != null && wrapperTrimEndPoint != null) {
                trimStartPoint = if (wrapper.sameSense) wrapperTrimStartPoint else wrapperTrimEndPoint
                trimEndPoint = if (wrapper.sameSense) wrapperTrimEndPoint else wrapperTrimStartPoint
            }
            val wrapperTrimStartParameter = wrapper.trimStartParameter
            val wrapperTrimEndParameter = wrapper.trimEndParameter
            if (wrapperTrimStartParameter != null && wrapperTrimEndParameter != null) {
                trimStartParameter = if (wrapper.sameSense) wrapperTrimStartParameter else wrapperTrimEndParameter
                trimEndParameter = if (wrapper.sameSense) wrapperTrimEndParameter else wrapperTrimStartParameter
            }
            id = wrapper.basisCurveId
            sense = sense == wrapper.sameSense
        }
        return ResolvedCurveRecord(
            curveId = id,
            sameSense = sense,
            offset = offset,
            trimStartPoint = trimStartPoint,
            trimEndPoint = trimEndPoint,
            trimStartParameter = trimStartParameter,
            trimEndParameter = trimEndParameter
        )
    }

    private fun List<StepLitePoint>.applyPointTrim(resolvedCurve: ResolvedCurveRecord): List<StepLitePoint>? {
        val trimStart = resolvedCurve.trimStartPoint
        val trimEnd = resolvedCurve.trimEndPoint
        return if (trimStart != null && trimEnd != null) {
            trimmedBetween(start = trimStart, end = trimEnd)
        } else {
            this
        }
    }

    private fun CurveWrapperRecord.toStandaloneEntity(
        sourceId: Int,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        placements: Map<Int, AxisPlacementRecord>,
        circles: Map<Int, CircleRecord>,
        ellipses: Map<Int, EllipseRecord>,
        parabolas: Map<Int, ParabolaRecord>,
        hyperbolas: Map<Int, HyperbolaRecord>,
        splines: Map<Int, BSplineRecord>,
        vectors: Map<Int, VectorRecord>,
        curveWrappers: Map<Int, CurveWrapperRecord>,
        compositeSegments: Map<Int, CompositeCurveSegmentRecord>,
        compositeCurves: Map<Int, List<Int>>,
        lineCurves: Set<Int>,
        lineRecords: Map<Int, LineRecord>,
        polylineCurves: Map<Int, List<Int>>
    ): StepLiteEntity? {
        val start = trimStartPointId?.let(points::get)
        val end = trimEndPointId?.let(points::get)
        if (start != null && end != null) {
            return EdgeCurveRecord(
                sourceId = sourceId,
                startVertexId = 0,
                endVertexId = 0,
                curveId = sourceId,
                sameSense = true
            ).toEntity(
                start = start,
                end = end,
                points = points,
                directions = directions,
                placements = placements,
                circles = circles,
                ellipses = ellipses,
                parabolas = parabolas,
                hyperbolas = hyperbolas,
                splines = splines,
                vectors = vectors,
                curveWrappers = curveWrappers,
                compositeSegments = compositeSegments,
                compositeCurves = compositeCurves,
                lineCurves = lineCurves,
                lineRecords = lineRecords,
                polylineCurves = polylineCurves
            )
        }

        val startParameter = trimStartParameter
        val endParameter = trimEndParameter
        if (startParameter != null && endParameter != null) {
            return basisCurveId.toParameterTrimmedEntity(
                sourceId = sourceId,
                sameSense = sameSense,
                startParameter = startParameter,
                endParameter = endParameter,
                points = points,
                directions = directions,
                vectors = vectors,
                placements = placements,
                circles = circles,
                ellipses = ellipses,
                parabolas = parabolas,
                hyperbolas = hyperbolas,
                splines = splines,
                lineRecords = lineRecords
            )?.let { applyOffset(it, directions) }
        }

        circles[basisCurveId]?.toStandaloneEntity(
            sourceId = sourceId,
            points = points,
            directions = directions,
            placements = placements
        )?.let { return applyOffset(it, directions) }

        ellipses[basisCurveId]?.toStandalonePolyline(
            sourceId = sourceId,
            points = points,
            directions = directions,
            placements = placements
        )?.let { return applyOffset(it, directions) }

        return basisCurveId.toBoundedCurvePoints(
            sameSense = sameSense,
            points = points,
            splines = splines,
            directions = directions,
            vectors = vectors,
            placements = placements,
            circles = circles,
            ellipses = ellipses,
            parabolas = parabolas,
            hyperbolas = hyperbolas,
            curveWrappers = curveWrappers,
            compositeSegments = compositeSegments,
            compositeCurves = compositeCurves,
            lineRecords = lineRecords,
            polylineCurves = polylineCurves,
            depth = 0
        )?.let { basisPoints ->
            applyOffset(
                StepLiteEntity.Polyline(points = basisPoints, sourceId = sourceId),
                directions
            )
        }
    }

    private fun List<Int>.toCompositeCurvePoints(
        points: Map<Int, StepLitePoint>,
        splines: Map<Int, BSplineRecord>,
        directions: Map<Int, DirectionRecord>,
        vectors: Map<Int, VectorRecord>,
        placements: Map<Int, AxisPlacementRecord>,
        circles: Map<Int, CircleRecord>,
        ellipses: Map<Int, EllipseRecord>,
        parabolas: Map<Int, ParabolaRecord>,
        hyperbolas: Map<Int, HyperbolaRecord>,
        curveWrappers: Map<Int, CurveWrapperRecord>,
        compositeSegments: Map<Int, CompositeCurveSegmentRecord>,
        compositeCurves: Map<Int, List<Int>>,
        lineRecords: Map<Int, LineRecord>,
        polylineCurves: Map<Int, List<Int>>,
        depth: Int = 0
    ): List<StepLitePoint>? {
        if (depth > MaxCurveWrapperDepth) return null
        val merged = ArrayList<StepLitePoint>()
        for (segmentId in this) {
            val segment = compositeSegments[segmentId] ?: return null
            val segmentPoints = segment.parentCurveId.toBoundedCurvePoints(
                sameSense = segment.sameSense,
                points = points,
                splines = splines,
                directions = directions,
                vectors = vectors,
                placements = placements,
                circles = circles,
                ellipses = ellipses,
                parabolas = parabolas,
                hyperbolas = hyperbolas,
                curveWrappers = curveWrappers,
                compositeSegments = compositeSegments,
                compositeCurves = compositeCurves,
                lineRecords = lineRecords,
                polylineCurves = polylineCurves,
                depth = depth + 1
            ) ?: return null
            if (merged.isNotEmpty() && merged.last().samePositionAs(segmentPoints.first())) {
                merged += segmentPoints.drop(1)
            } else if (merged.isEmpty()) {
                merged += segmentPoints
            } else {
                return null
            }
        }
        return merged.dedupeConsecutivePoints().takeIf { it.size >= 2 }
    }

    private fun Int.toBoundedCurvePoints(
        sameSense: Boolean,
        points: Map<Int, StepLitePoint>,
        splines: Map<Int, BSplineRecord>,
        directions: Map<Int, DirectionRecord>,
        vectors: Map<Int, VectorRecord>,
        placements: Map<Int, AxisPlacementRecord>,
        circles: Map<Int, CircleRecord>,
        ellipses: Map<Int, EllipseRecord>,
        parabolas: Map<Int, ParabolaRecord>,
        hyperbolas: Map<Int, HyperbolaRecord>,
        curveWrappers: Map<Int, CurveWrapperRecord>,
        compositeSegments: Map<Int, CompositeCurveSegmentRecord>,
        compositeCurves: Map<Int, List<Int>>,
        lineRecords: Map<Int, LineRecord>,
        polylineCurves: Map<Int, List<Int>>,
        depth: Int
    ): List<StepLitePoint>? {
        if (depth > MaxCurveWrapperDepth) return null

        val wrapper = curveWrappers[this]
        if (wrapper != null) {
            val wrapperSameSense = sameSense == wrapper.sameSense
            val trimStart = wrapper.trimStartPointId?.let(points::get)
            val trimEnd = wrapper.trimEndPointId?.let(points::get)
            if (trimStart != null && trimEnd != null) {
                return wrapper.applyOffset(
                    wrapper.basisCurveId.toTrimmedBoundedCurvePoints(
                        sameSense = wrapperSameSense,
                        trimStart = trimStart,
                        trimEnd = trimEnd,
                        points = points,
                        splines = splines,
                        directions = directions,
                        vectors = vectors,
                        placements = placements,
                        circles = circles,
                        ellipses = ellipses,
                        parabolas = parabolas,
                        hyperbolas = hyperbolas,
                        curveWrappers = curveWrappers,
                        compositeSegments = compositeSegments,
                        compositeCurves = compositeCurves,
                        lineRecords = lineRecords,
                        polylineCurves = polylineCurves,
                        depth = depth + 1
                    ),
                    directions = directions
                )
            }
            val trimStartParameter = wrapper.trimStartParameter
            val trimEndParameter = wrapper.trimEndParameter
            if (trimStartParameter != null && trimEndParameter != null) {
                return wrapper.applyOffset(
                    wrapper.basisCurveId.toParameterTrimmedBoundedCurvePoints(
                        sameSense = wrapperSameSense,
                        startParameter = trimStartParameter,
                        endParameter = trimEndParameter,
                        points = points,
                        directions = directions,
                        vectors = vectors,
                        placements = placements,
                        circles = circles,
                        ellipses = ellipses,
                        parabolas = parabolas,
                        hyperbolas = hyperbolas,
                        splines = splines,
                        lineRecords = lineRecords
                    ),
                    directions = directions
                )
            }
            return wrapper.applyOffset(
                wrapper.basisCurveId.toBoundedCurvePoints(
                    sameSense = wrapperSameSense,
                    points = points,
                    splines = splines,
                    directions = directions,
                    vectors = vectors,
                    placements = placements,
                    circles = circles,
                    ellipses = ellipses,
                    parabolas = parabolas,
                    hyperbolas = hyperbolas,
                    curveWrappers = curveWrappers,
                    compositeSegments = compositeSegments,
                    compositeCurves = compositeCurves,
                    lineRecords = lineRecords,
                    polylineCurves = polylineCurves,
                    depth = depth + 1
                ),
                directions = directions
            )
        }

        val compositeSegmentIds = compositeCurves[this]
        if (compositeSegmentIds != null) {
            return compositeSegmentIds.toCompositeCurvePoints(
                points = points,
                splines = splines,
                directions = directions,
                vectors = vectors,
                placements = placements,
                circles = circles,
                ellipses = ellipses,
                parabolas = parabolas,
                hyperbolas = hyperbolas,
                curveWrappers = curveWrappers,
                compositeSegments = compositeSegments,
                compositeCurves = compositeCurves,
                lineRecords = lineRecords,
                polylineCurves = polylineCurves,
                depth = depth + 1
            )?.let { if (sameSense) it else it.asReversed() }
        }

        val line = lineRecords[this]
        if (line != null) {
            return line.toPoints(
                points = points,
                directions = directions,
                vectors = vectors
            )?.let { if (sameSense) it else it.asReversed() }
        }

        val polylinePointIds = polylineCurves[this]
        if (polylinePointIds != null) {
            return polylinePointIds.toPolylinePoints(points)
                ?.let { if (sameSense) it else it.asReversed() }
        }

        val spline = splines[this]
        if (spline != null) {
            return spline.toPolylinePoints(points)
                ?.let { if (sameSense) it else it.asReversed() }
        }

        return null
    }

    private fun CurveWrapperRecord.applyOffset(
        points: List<StepLitePoint>?,
        directions: Map<Int, DirectionRecord>
    ): List<StepLitePoint>? {
        val directionId = offsetDirectionId ?: return points
        val distance = offsetDistance ?: return points
        val direction = directions[directionId]?.normalizedOrNull() ?: return null
        return points?.map { it.offsetBy(direction, distance) }
    }

    private fun CurveWrapperRecord.applyOffset(
        entity: StepLiteEntity,
        directions: Map<Int, DirectionRecord>
    ): StepLiteEntity? {
        val directionId = offsetDirectionId ?: return entity
        val distance = offsetDistance ?: return entity
        val direction = directions[directionId]?.normalizedOrNull() ?: return null
        return entity.offsetBy(direction, distance)
    }

    private fun Int.toTrimmedBoundedCurvePoints(
        sameSense: Boolean,
        trimStart: StepLitePoint,
        trimEnd: StepLitePoint,
        points: Map<Int, StepLitePoint>,
        splines: Map<Int, BSplineRecord>,
        directions: Map<Int, DirectionRecord>,
        vectors: Map<Int, VectorRecord>,
        placements: Map<Int, AxisPlacementRecord>,
        circles: Map<Int, CircleRecord>,
        ellipses: Map<Int, EllipseRecord>,
        parabolas: Map<Int, ParabolaRecord>,
        hyperbolas: Map<Int, HyperbolaRecord>,
        curveWrappers: Map<Int, CurveWrapperRecord>,
        compositeSegments: Map<Int, CompositeCurveSegmentRecord>,
        compositeCurves: Map<Int, List<Int>>,
        lineRecords: Map<Int, LineRecord>,
        polylineCurves: Map<Int, List<Int>>,
        depth: Int
    ): List<StepLitePoint>? {
        if (depth > MaxCurveWrapperDepth) return null
        val start = if (sameSense) trimStart else trimEnd
        val end = if (sameSense) trimEnd else trimStart

        val circle = circles[this]
        val circlePlacement = circle?.let { placements[it.placementId] }
        val circleCenter = circlePlacement?.let { points[it.locationPointId] }
        if (circle != null && circlePlacement != null && circleCenter != null) {
            return circle.toPolylinePoints(
                center = circleCenter,
                basis = circlePlacement.toBasis(directions),
                start = start,
                end = end,
                closed = start.samePositionAs(end)
            )
        }

        val ellipse = ellipses[this]
        val ellipsePlacement = ellipse?.let { placements[it.placementId] }
        val ellipseCenter = ellipsePlacement?.let { points[it.locationPointId] }
        if (ellipse != null && ellipsePlacement != null && ellipseCenter != null) {
            return ellipse.toPolylinePoints(
                center = ellipseCenter,
                basis = ellipsePlacement.toBasis(directions),
                start = start,
                end = end,
                closed = start.samePositionAs(end)
            )
        }

        val parabola = parabolas[this]
        val parabolaPlacement = parabola?.let { placements[it.placementId] }
        val parabolaCenter = parabolaPlacement?.let { points[it.locationPointId] }
        if (parabola != null && parabolaPlacement != null && parabolaCenter != null) {
            return parabola.toPolylinePoints(
                center = parabolaCenter,
                basis = parabolaPlacement.toBasis(directions),
                start = start,
                end = end
            )
        }

        val hyperbola = hyperbolas[this]
        val hyperbolaPlacement = hyperbola?.let { placements[it.placementId] }
        val hyperbolaCenter = hyperbolaPlacement?.let { points[it.locationPointId] }
        if (hyperbola != null && hyperbolaPlacement != null && hyperbolaCenter != null) {
            return hyperbola.toPolylinePoints(
                center = hyperbolaCenter,
                basis = hyperbolaPlacement.toBasis(directions),
                start = start,
                end = end
            )
        }

        val spline = splines[this]
        if (spline != null) {
            return spline.toPolylinePoints(points)
                ?.trimmedBetween(start = start, end = end)
        }

        return toBoundedCurvePoints(
            sameSense = true,
            points = points,
            splines = splines,
            directions = directions,
            vectors = vectors,
            placements = placements,
            circles = circles,
            ellipses = ellipses,
            parabolas = parabolas,
            hyperbolas = hyperbolas,
            curveWrappers = curveWrappers,
            compositeSegments = compositeSegments,
            compositeCurves = compositeCurves,
            lineRecords = lineRecords,
            polylineCurves = polylineCurves,
            depth = depth + 1
        )?.trimmedBetween(start = start, end = end)
    }

    private fun Int.toParameterTrimmedBoundedCurvePoints(
        sameSense: Boolean,
        startParameter: Double,
        endParameter: Double,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        vectors: Map<Int, VectorRecord>,
        placements: Map<Int, AxisPlacementRecord>,
        circles: Map<Int, CircleRecord>,
        ellipses: Map<Int, EllipseRecord>,
        parabolas: Map<Int, ParabolaRecord>,
        hyperbolas: Map<Int, HyperbolaRecord>,
        splines: Map<Int, BSplineRecord>,
        lineRecords: Map<Int, LineRecord>
    ): List<StepLitePoint>? {
        return toParameterTrimmedEntity(
            sourceId = 0,
            sameSense = sameSense,
            startParameter = startParameter,
            endParameter = endParameter,
            points = points,
            directions = directions,
            vectors = vectors,
            placements = placements,
            circles = circles,
            ellipses = ellipses,
            parabolas = parabolas,
            hyperbolas = hyperbolas,
            splines = splines,
            lineRecords = lineRecords
        )?.toPathPoints()
    }

    private fun Int.toParameterTrimmedEntity(
        sourceId: Int,
        sameSense: Boolean,
        startParameter: Double,
        endParameter: Double,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        vectors: Map<Int, VectorRecord>,
        placements: Map<Int, AxisPlacementRecord>,
        circles: Map<Int, CircleRecord>,
        ellipses: Map<Int, EllipseRecord>,
        parabolas: Map<Int, ParabolaRecord>,
        hyperbolas: Map<Int, HyperbolaRecord>,
        splines: Map<Int, BSplineRecord>,
        lineRecords: Map<Int, LineRecord>
    ): StepLiteEntity? {
        val startValue = if (sameSense) startParameter else endParameter
        val endValue = if (sameSense) endParameter else startParameter

        val line = lineRecords[this]
        if (line != null) {
            return line.toParameterTrimmedEntity(
                sourceId = sourceId,
                sameSense = sameSense,
                startParameter = startParameter,
                endParameter = endParameter,
                points = points,
                directions = directions,
                vectors = vectors
            )
        }

        val circle = circles[this]
        val circlePlacement = circle?.let { placements[it.placementId] }
        val circleCenter = circlePlacement?.let { points[it.locationPointId] }
        if (circle != null && circlePlacement != null && circleCenter != null) {
            val basis = circlePlacement.toBasis(directions)
            val forwardStart = circleCenter.pointOnPlacedConic(basis, circle.radius, circle.radius, startParameter)
            val forwardEnd = circleCenter.pointOnPlacedConic(basis, circle.radius, circle.radius, endParameter)
            val start = if (sameSense) forwardStart else forwardEnd
            val end = if (sameSense) forwardEnd else forwardStart
            val closed = start.samePositionAs(end) && abs(endValue - startValue) > CoordinateTolerance
            if (basis.isFlatInPreviewPlane() && sameSense) {
                return if (closed) {
                    StepLiteEntity.Circle(
                        center = circleCenter,
                        radius = circle.radius,
                        sourceId = sourceId
                    )
                } else {
                    StepLiteEntity.Arc(
                        center = circleCenter,
                        radius = circle.radius,
                        startAngleRadians = start.angleFrom(circleCenter),
                        endAngleRadians = end.angleFrom(circleCenter),
                        sourceId = sourceId
                    )
                }
            }
            val sampled = circle.toPolylinePoints(
                center = circleCenter,
                basis = basis,
                start = forwardStart,
                end = forwardEnd,
                closed = closed
            ).let { if (sameSense) it else it.asReversed() }
            return StepLiteEntity.Polyline(
                points = sampled,
                sourceId = sourceId
            )
        }

        val ellipse = ellipses[this]
        val ellipsePlacement = ellipse?.let { placements[it.placementId] }
        val ellipseCenter = ellipsePlacement?.let { points[it.locationPointId] }
        if (ellipse != null && ellipsePlacement != null && ellipseCenter != null) {
            val basis = ellipsePlacement.toBasis(directions)
            val forwardStart = ellipseCenter.pointOnPlacedConic(
                basis,
                ellipse.majorRadius,
                ellipse.minorRadius,
                startParameter
            )
            val forwardEnd = ellipseCenter.pointOnPlacedConic(
                basis,
                ellipse.majorRadius,
                ellipse.minorRadius,
                endParameter
            )
            val start = if (sameSense) forwardStart else forwardEnd
            val end = if (sameSense) forwardEnd else forwardStart
            val sampled = ellipse.toPolylinePoints(
                center = ellipseCenter,
                basis = basis,
                start = forwardStart,
                end = forwardEnd,
                closed = start.samePositionAs(end) && abs(endValue - startValue) > CoordinateTolerance
            ).let { if (sameSense) it else it.asReversed() }
            return StepLiteEntity.Polyline(
                points = sampled,
                sourceId = sourceId
            )
        }

        val parabola = parabolas[this]
        val parabolaPlacement = parabola?.let { placements[it.placementId] }
        val parabolaCenter = parabolaPlacement?.let { points[it.locationPointId] }
        if (parabola != null && parabolaPlacement != null && parabolaCenter != null) {
            val basis = parabolaPlacement.toBasis(directions)
            return StepLiteEntity.Polyline(
                points = parabola.toPolylinePoints(
                    center = parabolaCenter,
                    basis = basis,
                    start = parabolaCenter.pointOnParabola(basis, parabola.focalDistance, startValue),
                    end = parabolaCenter.pointOnParabola(basis, parabola.focalDistance, endValue)
                ),
                sourceId = sourceId
            )
        }

        val hyperbola = hyperbolas[this]
        val hyperbolaPlacement = hyperbola?.let { placements[it.placementId] }
        val hyperbolaCenter = hyperbolaPlacement?.let { points[it.locationPointId] }
        if (hyperbola != null && hyperbolaPlacement != null && hyperbolaCenter != null) {
            val basis = hyperbolaPlacement.toBasis(directions)
            return StepLiteEntity.Polyline(
                points = hyperbola.toPolylinePoints(
                    center = hyperbolaCenter,
                    basis = basis,
                    start = hyperbolaCenter.pointOnHyperbola(
                        basis,
                        hyperbola.semiAxis,
                        hyperbola.semiImagAxis,
                        startValue
                    ),
                    end = hyperbolaCenter.pointOnHyperbola(
                        basis,
                        hyperbola.semiAxis,
                        hyperbola.semiImagAxis,
                        endValue
                    )
                ),
                sourceId = sourceId
            )
        }

        val spline = splines[this]
        if (spline != null) {
            return spline.toPolylinePoints(
                points = points,
                startParameter = startValue,
                endParameter = endValue
            )?.let { splinePoints ->
                StepLiteEntity.Polyline(points = splinePoints, sourceId = sourceId)
            }
        }

        return null
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

    private fun CircleRecord.toStandaloneEntity(
        sourceId: Int,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        placements: Map<Int, AxisPlacementRecord>
    ): StepLiteEntity? {
        val placement = placements[placementId] ?: return null
        val center = points[placement.locationPointId] ?: return null
        val basis = placement.toBasis(directions)
        if (basis.isFlatInPreviewPlane()) {
            return StepLiteEntity.Circle(
                center = center,
                radius = radius,
                sourceId = sourceId
            )
        }
        return StepLiteEntity.Polyline(
            points = toPolylinePoints(
                center = center,
                basis = basis,
                start = center.offsetBy(basis.xAxis, radius),
                end = center.offsetBy(basis.xAxis, radius),
                closed = true
            ),
            sourceId = sourceId
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

    private fun EllipseRecord.toStandalonePolyline(
        sourceId: Int,
        points: Map<Int, StepLitePoint>,
        directions: Map<Int, DirectionRecord>,
        placements: Map<Int, AxisPlacementRecord>
    ): StepLiteEntity.Polyline? {
        val placement = placements[placementId] ?: return null
        val center = points[placement.locationPointId] ?: return null
        val basis = placement.toBasis(directions)
        val start = center.offsetBy(basis.xAxis, majorRadius)
        return StepLiteEntity.Polyline(
            points = toPolylinePoints(
                center = center,
                basis = basis,
                start = start,
                end = start,
                closed = true
            ),
            sourceId = sourceId
        )
    }

    private fun ParabolaRecord.toPolylinePoints(
        center: StepLitePoint,
        basis: PlacementBasis,
        start: StepLitePoint,
        end: StepLitePoint
    ): List<StepLitePoint> {
        val startParameter = start.parabolaParameterFrom(center, basis, focalDistance)
        val endParameter = end.parabolaParameterFrom(center, basis, focalDistance)
        return List(ParabolaSegments + 1) { index ->
            val parameter = startParameter + (endParameter - startParameter) * index / ParabolaSegments
            val majorOffset = focalDistance * parameter * parameter
            val minorOffset = 2.0 * focalDistance * parameter
            StepLitePoint(
                x = center.x + basis.xAxis.x * majorOffset + basis.yAxis.x * minorOffset,
                y = center.y + basis.xAxis.y * majorOffset + basis.yAxis.y * minorOffset,
                z = center.z + basis.xAxis.z * majorOffset + basis.yAxis.z * minorOffset
            )
        }
    }

    private fun HyperbolaRecord.toPolylinePoints(
        center: StepLitePoint,
        basis: PlacementBasis,
        start: StepLitePoint,
        end: StepLitePoint
    ): List<StepLitePoint> {
        val startParameter = start.hyperbolaParameterFrom(center, basis, semiImagAxis)
        val endParameter = end.hyperbolaParameterFrom(center, basis, semiImagAxis)
        val branchSign = start.hyperbolaBranchSignFrom(center, basis)
        return List(HyperbolaSegments + 1) { index ->
            val parameter = startParameter + (endParameter - startParameter) * index / HyperbolaSegments
            val majorOffset = branchSign * semiAxis * hyperbolicCosine(parameter)
            val minorOffset = semiImagAxis * hyperbolicSine(parameter)
            StepLitePoint(
                x = center.x + basis.xAxis.x * majorOffset + basis.yAxis.x * minorOffset,
                y = center.y + basis.xAxis.y * majorOffset + basis.yAxis.y * minorOffset,
                z = center.z + basis.xAxis.z * majorOffset + basis.yAxis.z * minorOffset
            )
        }
    }

    private fun StepLitePoint.pointOnParabola(
        basis: PlacementBasis,
        focalDistance: Double,
        parameter: Double
    ): StepLitePoint {
        val majorOffset = focalDistance * parameter * parameter
        val minorOffset = 2.0 * focalDistance * parameter
        return offsetInBasis(basis, majorOffset, minorOffset)
    }

    private fun StepLitePoint.pointOnHyperbola(
        basis: PlacementBasis,
        semiAxis: Double,
        semiImagAxis: Double,
        parameter: Double
    ): StepLitePoint {
        val majorOffset = semiAxis * hyperbolicCosine(parameter)
        val minorOffset = semiImagAxis * hyperbolicSine(parameter)
        return offsetInBasis(basis, majorOffset, minorOffset)
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

    private fun StepLitePoint.pointOnPlacedConic(
        basis: PlacementBasis,
        majorRadius: Double,
        minorRadius: Double,
        angle: Double
    ): StepLitePoint {
        val majorOffset = majorRadius * cos(angle)
        val minorOffset = minorRadius * sin(angle)
        return offsetInBasis(basis, majorOffset, minorOffset)
    }

    private fun StepLitePoint.offsetInBasis(
        basis: PlacementBasis,
        majorOffset: Double,
        minorOffset: Double
    ): StepLitePoint {
        return StepLitePoint(
            x = x + basis.xAxis.x * majorOffset + basis.yAxis.x * minorOffset,
            y = y + basis.xAxis.y * majorOffset + basis.yAxis.y * minorOffset,
            z = z + basis.xAxis.z * majorOffset + basis.yAxis.z * minorOffset
        )
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

    private fun BSplineRecord.toPolylinePoints(
        points: Map<Int, StepLitePoint>,
        startParameter: Double? = null,
        endParameter: Double? = null
    ): List<StepLitePoint>? {
        val controlPoints = controlPointIds.mapNotNull(points::get)
        if (controlPoints.size != controlPointIds.size || controlPoints.size <= degree || knots.size != controlPoints.size + degree + 1) {
            return null
        }
        if (weights != null && weights.size != controlPoints.size) return null

        val domainStart = knots[degree]
        val domainEnd = knots[knots.size - degree - 1]
        if (domainEnd <= domainStart) return null
        val start = startParameter ?: domainStart
        val end = endParameter ?: domainEnd
        if (start < domainStart || start > domainEnd || end < domainStart || end > domainEnd) return null
        if (abs(end - start) <= CoordinateTolerance) return null

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
        val degree = topLevelNumbers().firstOrNull()?.toExactIntOrNull() ?: return null
        if (degree < 1) return null
        val controlPointIds = splineControlPointIds()?.takeIf { it.size > degree } ?: return null
        val numberTuples = topLevelNumberTuples()
        return toBSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            numberTuples = numberTuples,
            weights = null
        )
    }

    private fun String.toSimpleBSplineRecord(): BSplineRecord? {
        val degree = topLevelNumbers().firstOrNull()?.toExactIntOrNull() ?: return null
        if (degree < 1) return null
        val controlPointIds = splineControlPointIds()?.takeIf { it.size > degree } ?: return null
        return toQuasiUniformBSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            weights = null
        )
    }

    private fun String.toSimpleBezierRecord(): BSplineRecord? {
        val degree = topLevelNumbers().firstOrNull()?.toExactIntOrNull() ?: return null
        if (degree < 1) return null
        val controlPointIds = splineControlPointIds()?.takeIf { it.size > degree } ?: return null
        return toBezierBSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            weights = null
        )
    }

    private fun String.toSimpleQuasiUniformRecord(): BSplineRecord? {
        val degree = topLevelNumbers().firstOrNull()?.toExactIntOrNull() ?: return null
        if (degree < 1) return null
        val controlPointIds = splineControlPointIds()?.takeIf { it.size > degree } ?: return null
        return toQuasiUniformBSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            weights = null
        )
    }

    private fun String.toSimpleUniformRecord(): BSplineRecord? {
        val degree = topLevelNumbers().firstOrNull()?.toExactIntOrNull() ?: return null
        if (degree < 1) return null
        val controlPointIds = splineControlPointIds()?.takeIf { it.size > degree } ?: return null
        return toUniformBSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            weights = null
        )
    }

    private fun String.toComplexBSplineRecord(): BSplineRecord? {
        val curveArgs = entityArgs("B_SPLINE_CURVE") ?: return null
        val degree = curveArgs.topLevelNumbers().firstOrNull()?.toExactIntOrNull() ?: return null
        if (degree < 1) return null
        val controlPointIds = curveArgs.splineControlPointIds()?.takeIf { it.size > degree } ?: return null
        val weights = entityArgs("RATIONAL_B_SPLINE_CURVE")
            ?.deepNumberTuples()
            ?.lastOrNull()
        val knotArgs = entityArgs("B_SPLINE_CURVE_WITH_KNOTS")
        if (knotArgs == null && entityArgs("BEZIER_CURVE") != null) {
            return toBezierBSplineRecord(
                degree = degree,
                controlPointIds = controlPointIds,
                weights = weights
            )
        }
        if (knotArgs == null && entityArgs("QUASI_UNIFORM_CURVE") != null) {
            return toQuasiUniformBSplineRecord(
                degree = degree,
                controlPointIds = controlPointIds,
                weights = weights
            )
        }
        if (knotArgs == null && entityArgs("UNIFORM_CURVE") != null) {
            return toUniformBSplineRecord(
                degree = degree,
                controlPointIds = controlPointIds,
                weights = weights
            )
        }
        if (knotArgs == null) {
            return toQuasiUniformBSplineRecord(
                degree = degree,
                controlPointIds = controlPointIds,
                weights = weights
            )
        }
        return knotArgs.toBSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            numberTuples = knotArgs.topLevelNumberTuples(),
            weights = weights
        )
    }

    private fun toBezierBSplineRecord(
        degree: Int,
        controlPointIds: List<Int>,
        weights: List<Double>?
    ): BSplineRecord? {
        if (controlPointIds.size != degree + 1) return null
        if (weights != null && (weights.size != controlPointIds.size || weights.any { it <= 0.0 })) return null
        return BSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            knots = List(degree + 1) { 0.0 } + List(degree + 1) { 1.0 },
            weights = weights
        )
    }

    private fun toQuasiUniformBSplineRecord(
        degree: Int,
        controlPointIds: List<Int>,
        weights: List<Double>?
    ): BSplineRecord? {
        if (controlPointIds.size <= degree) return null
        if (weights != null && (weights.size != controlPointIds.size || weights.any { it <= 0.0 })) return null
        val interiorCount = controlPointIds.size - degree - 1
        val endKnot = (interiorCount + 1).toDouble()
        return BSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            knots = List(degree + 1) { 0.0 } +
                (1..interiorCount).map { it.toDouble() } +
                List(degree + 1) { endKnot },
            weights = weights
        )
    }

    private fun toUniformBSplineRecord(
        degree: Int,
        controlPointIds: List<Int>,
        weights: List<Double>?
    ): BSplineRecord? {
        if (controlPointIds.size <= degree) return null
        if (weights != null && (weights.size != controlPointIds.size || weights.any { it <= 0.0 })) return null
        return BSplineRecord(
            degree = degree,
            controlPointIds = controlPointIds,
            knots = List(controlPointIds.size + degree + 1) { it.toDouble() },
            weights = weights
        )
    }

    private fun String.splineControlPointIds(): List<Int>? {
        val fields = topLevelFields()
        val degreeIndex = fields.indexOfFirst { field ->
            field.topLevelNumbers().firstOrNull()?.toExactIntOrNull() != null
        }
        if (degreeIndex < 0) return null
        val controlPointField = fields.getOrNull(degreeIndex + 1) ?: return null
        if (controlPointField.hasUnsetStepValueOutsideString()) return null
        return controlPointField.refs().takeIf { it.isNotEmpty() }
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

    private fun String.toParabolaRecord(): ParabolaRecord? {
        val placementId = refs().firstOrNull() ?: return null
        val focalDistance = topLevelNumbers().lastOrNull() ?: return null
        if (focalDistance <= 0.0) return null
        return ParabolaRecord(
            placementId = placementId,
            focalDistance = focalDistance
        )
    }

    private fun String.toHyperbolaRecord(): HyperbolaRecord? {
        val placementId = refs().firstOrNull() ?: return null
        val radii = topLevelNumbers().takeLast(2)
        if (radii.size != 2 || radii[0] <= 0.0 || radii[1] <= 0.0) return null
        return HyperbolaRecord(
            placementId = placementId,
            semiAxis = radii[0],
            semiImagAxis = radii[1]
        )
    }

    private fun String.toPolylineRecord(): List<Int>? {
        if (hasUnsetStepValueOutsideString()) return null
        return refs().takeIf { it.size >= 2 }
    }

    private fun String.toPolyLoopRecord(): List<Int>? {
        if (hasUnsetStepValueOutsideString()) return null
        return refs().takeIf { it.size >= 3 }
    }

    private fun String.toVectorRecord(): VectorRecord? {
        val directionId = refs().firstOrNull() ?: return null
        val magnitude = topLevelNumbers().lastOrNull() ?: return null
        if (magnitude <= 0.0) return null
        return VectorRecord(
            directionId = directionId,
            magnitude = magnitude
        )
    }

    private fun String.toLineRecord(): LineRecord? {
        val refs = refs()
        if (refs.size < 2) return null
        return LineRecord(
            pointId = refs[0],
            vectorId = refs[1]
        )
    }

    private fun String.toBSplineRecord(
        degree: Int,
        controlPointIds: List<Int>,
        numberTuples: List<List<Double>>,
        weights: List<Double>?
    ): BSplineRecord? {
        val multiplicities = numberTuples.getOrNull(numberTuples.size - 2)
            ?.map { value ->
                value.toExactIntOrNull()?.takeIf { it > 0 } ?: return null
            }
            ?: return null
        val knotValues = numberTuples.lastOrNull() ?: return null
        if (multiplicities.size != knotValues.size) return null
        if (knotValues.zipWithNext().any { (current, next) -> next <= current }) return null
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
        val fields = topLevelFields()
        val trimStartField = fields.getOrNull(2)
        val trimEndField = fields.getOrNull(3)
        val basisCurveId = fields.getOrNull(1)?.refs()?.firstOrNull()
            ?: refs().firstOrNull()
            ?: return null
        val trimStartPointId = trimStartField?.refs()?.firstOrNull()
        val trimEndPointId = trimEndField?.refs()?.firstOrNull()
        val trimStartParameter = trimStartField?.parameterTrimValueOrNull()
        val trimEndParameter = trimEndField?.parameterTrimValueOrNull()
        if (
            (trimStartPointId == null || trimEndPointId == null) &&
            (trimStartParameter == null || trimEndParameter == null)
        ) {
            return null
        }
        return CurveWrapperRecord(
            basisCurveId = basisCurveId,
            sameSense = lastTopLevelLogical() ?: true,
            trimStartPointId = trimStartPointId,
            trimEndPointId = trimEndPointId,
            trimStartParameter = trimStartParameter,
            trimEndParameter = trimEndParameter
        )
    }

    private fun String.toBasisCurveWrapperRecord(): CurveWrapperRecord? {
        val basisCurveId = refs().firstOrNull() ?: return null
        return CurveWrapperRecord(
            basisCurveId = basisCurveId,
            sameSense = true
        )
    }

    private fun String.toOffsetCurveRecord(): CurveWrapperRecord? {
        val fields = topLevelFields()
        val basisCurveId = fields.getOrNull(1)?.refs()?.firstOrNull() ?: return null
        val distance = fields.getOrNull(2)?.toStepDoubleOrNull() ?: return null
        val directionId = fields.getOrNull(3)?.refs()?.firstOrNull() ?: return null
        return CurveWrapperRecord(
            basisCurveId = basisCurveId,
            sameSense = true,
            offsetDistance = distance,
            offsetDirectionId = directionId
        )
    }

    private fun String.toCompositeCurveSegmentRecord(): CompositeCurveSegmentRecord? {
        val parentCurveId = refs().lastOrNull() ?: return null
        return CompositeCurveSegmentRecord(
            parentCurveId = parentCurveId,
            sameSense = lastTopLevelLogical() ?: true
        )
    }

    private fun String.toCompositeCurveRecord(): List<Int>? {
        return requiredReferenceAggregateField(fieldIndex = 1)
    }

    private fun String.toOrientedEdgeRecord(): OrientedEdgeRecord? {
        val edgeId = refs().lastOrNull() ?: return null
        return OrientedEdgeRecord(
            edgeId = edgeId,
            sameSense = lastTopLevelLogical() ?: true
        )
    }

    private fun String.toEdgeCurveRecord(sourceId: Int): EdgeCurveRecord? {
        val fields = topLevelFields()
        val startVertexId = fields.requiredFieldRef(fieldIndex = 1) ?: return null
        val endVertexId = fields.requiredFieldRef(fieldIndex = 2) ?: return null
        val curveId = fields.requiredFieldRef(fieldIndex = 3) ?: return null
        return EdgeCurveRecord(
            sourceId = sourceId,
            startVertexId = startVertexId,
            endVertexId = endVertexId,
            curveId = curveId,
            sameSense = lastTopLevelLogical() ?: true
        )
    }

    private fun String.toEdgeLoopRecord(): List<Int>? {
        return requiredReferenceAggregateField(fieldIndex = 1)
    }

    private fun String.requiredReferenceAggregateField(fieldIndex: Int): List<Int>? {
        val field = topLevelFields().getOrNull(fieldIndex) ?: return null
        if (field.hasUnsetStepValueOutsideString()) return null
        return field.refs().takeIf { it.isNotEmpty() }
    }

    private fun String.referenceAggregateFieldRefs(fieldIndex: Int): List<Int>? {
        return topLevelFields()
            .getOrNull(fieldIndex)
            ?.refs()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun String.referenceFieldRef(fieldIndex: Int): Int? {
        return topLevelFields().requiredFieldRef(fieldIndex)
    }

    private fun List<String>.requiredFieldRef(fieldIndex: Int): Int? {
        val field = getOrNull(fieldIndex) ?: return null
        if (field.hasUnsetStepValueOutsideString()) return null
        return field.refs().firstOrNull()
    }

    private companion object {
        private const val StepHeader = "ISO-10303-21;"
        private const val DefaultMaxBytes = 16 * 1024 * 1024
        private const val DefaultMaxRecords = 250_000
        private const val DefaultMaxEntities = 100_000
        private const val CircleSegments = 32
        private const val EllipseSegments = 32
        private const val ParabolaSegments = 32
        private const val HyperbolaSegments = 32
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

private fun String.headerFileName(): String? {
    val trimmed = trim()
    if (!trimmed.startsWith("FILE_NAME", ignoreCase = true)) return null
    val open = trimmed.indexOf('(').takeIf { it >= 0 } ?: return null
    val close = trimmed.lastIndexOf(')').takeIf { it > open } ?: return null
    return trimmed.substring(open + 1, close).firstString()
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

private fun String.hasUnsetStepValueOutsideString(): Boolean {
    var index = 0
    while (index < length) {
        when (this[index]) {
            '\'' -> index = skipStepString(index)
            '$', '*' -> return true
        }
        index += 1
    }
    return false
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
                    val values = substring(tupleStart, index).toNumberTupleOrNull()
                    if (values != null && values.size >= minSize) return values
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
            char == '#' -> {
                flush(index)
                var cursor = index + 1
                while (cursor < length && this[cursor].isDigit()) cursor += 1
                index = cursor - 1
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
                    substring(tupleStart, index).toNumberTupleOrNull()?.let(tuples::add)
                }
                depth -= 1
            }
        }
        index += 1
    }
    return tuples
}

private fun String.parameterTrimValueOrNull(): Double? {
    if (refs().isNotEmpty()) return null
    val values = deepNumberTuples().flatten()
    if (values.isEmpty()) return null
    return if (contains("PARAMETER_VALUE", ignoreCase = true) || values.size == 1) values.first() else null
}

private fun String.topLevelFields(): List<String> {
    val fields = ArrayList<String>()
    var depth = 0
    var fieldStart = 0
    var index = 0
    while (index < length) {
        when (this[index]) {
            '\'' -> index = skipStepString(index)
            '(' -> depth += 1
            ')' -> depth -= 1
            ',' -> if (depth == 0) {
                fields += substring(fieldStart, index).trim()
                fieldStart = index + 1
            }
        }
        index += 1
    }
    fields += substring(fieldStart).trim()
    return fields
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
                    substring(tupleStart, index).toNumberTupleOrNull()?.let(tuples::add)
                }
            }
        }
        index += 1
    }
    return tuples
}

private fun String.toNumberTupleOrNull(): List<Double>? {
    val tokens = split(',')
    val values = ArrayList<Double>(tokens.size)
    for (token in tokens) {
        values += token.trim().toStepDoubleOrNull() ?: return null
    }
    return values.takeIf { it.isNotEmpty() }
}

private fun Double.toExactIntOrNull(): Int? {
    if (isNaN() || isInfinite()) return null
    if (this < Int.MIN_VALUE.toDouble() || this > Int.MAX_VALUE.toDouble()) return null
    val value = toInt()
    return value.takeIf { value.toDouble() == this }
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

private fun StepLitePoint.squaredDistanceTo(other: StepLitePoint): Double {
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return dx * dx + dy * dy + dz * dz
}

private fun StepLitePoint.lerp(other: StepLitePoint, alpha: Double): StepLitePoint {
    return StepLitePoint(
        x = x + (other.x - x) * alpha,
        y = y + (other.y - y) * alpha,
        z = z + (other.z - z) * alpha
    )
}

private fun StepLitePoint.offsetBy(direction: DirectionRecord, distance: Double): StepLitePoint {
    return StepLitePoint(
        x = x + direction.x * distance,
        y = y + direction.y * distance,
        z = z + direction.z * distance
    )
}

private fun StepLiteEntity.offsetBy(direction: DirectionRecord, distance: Double): StepLiteEntity {
    return when (this) {
        is StepLiteEntity.Line -> copy(
            start = start.offsetBy(direction, distance),
            end = end.offsetBy(direction, distance)
        )
        is StepLiteEntity.Circle -> copy(center = center.offsetBy(direction, distance))
        is StepLiteEntity.Arc -> copy(center = center.offsetBy(direction, distance))
        is StepLiteEntity.Polyline -> copy(points = points.map { it.offsetBy(direction, distance) })
    }
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

private fun DirectionRecord.plus(other: DirectionRecord): DirectionRecord {
    return DirectionRecord(
        x = x + other.x,
        y = y + other.y,
        z = z + other.z
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

private fun StepLitePoint.parabolaParameterFrom(
    center: StepLitePoint,
    basis: PlacementBasis,
    focalDistance: Double
): Double {
    val relative = DirectionRecord(
        x = x - center.x,
        y = y - center.y,
        z = z - center.z
    )
    return relative.dot(basis.yAxis) / (2.0 * focalDistance)
}

private fun StepLitePoint.hyperbolaParameterFrom(
    center: StepLitePoint,
    basis: PlacementBasis,
    semiImagAxis: Double
): Double {
    val relative = DirectionRecord(
        x = x - center.x,
        y = y - center.y,
        z = z - center.z
    )
    return asinh(relative.dot(basis.yAxis) / semiImagAxis)
}

private fun StepLitePoint.hyperbolaBranchSignFrom(center: StepLitePoint, basis: PlacementBasis): Double {
    val majorDistance = DirectionRecord(
        x = x - center.x,
        y = y - center.y,
        z = z - center.z
    ).dot(basis.xAxis)
    return if (majorDistance < 0.0) -1.0 else 1.0
}

private fun positiveSweep(from: Double, to: Double): Double {
    var sweep = to - from
    while (sweep <= 0.0) sweep += 2.0 * PI
    return sweep
}

private fun hyperbolicSine(value: Double): Double {
    val positive = exp(value)
    val negative = exp(-value)
    return (positive - negative) / 2.0
}

private fun hyperbolicCosine(value: Double): Double {
    val positive = exp(value)
    val negative = exp(-value)
    return (positive + negative) / 2.0
}

private fun asinh(value: Double): Double {
    return ln(value + sqrt(value * value + 1.0))
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

private fun List<StepLitePoint>.trimmedBetween(start: StepLitePoint, end: StepLitePoint): List<StepLitePoint>? {
    if (size < 2) return null
    val startIndex = indexOfClosestTo(start)
    val endIndex = indexOfClosestTo(end)
    val trimmed = ArrayList<StepLitePoint>(abs(endIndex - startIndex) + 2)
    trimmed += start
    if (startIndex <= endIndex) {
        for (index in (startIndex + 1) until endIndex) {
            trimmed += this[index]
        }
    } else {
        for (index in (startIndex - 1) downTo (endIndex + 1)) {
            trimmed += this[index]
        }
    }
    trimmed += end
    return trimmed.dedupeConsecutivePoints().takeIf { it.size >= 2 }
}

private fun List<StepLitePoint>.indexOfClosestTo(target: StepLitePoint): Int {
    var closestIndex = 0
    var closestDistance = this[0].squaredDistanceTo(target)
    for (index in 1 until size) {
        val distance = this[index].squaredDistanceTo(target)
        if (distance < closestDistance) {
            closestIndex = index
            closestDistance = distance
        }
    }
    return closestIndex
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

private fun List<Int>.toPolylinePoints(pointsById: Map<Int, StepLitePoint>): List<StepLitePoint>? {
    return mapNotNull(pointsById::get)
        .takeIf { it.size == size && it.size >= 2 }
        ?.dedupeConsecutivePoints()
        ?.takeIf { it.size >= 2 }
}

private fun List<Int>.toClosedPolylinePoints(pointsById: Map<Int, StepLitePoint>): List<StepLitePoint>? {
    val loopPoints = toPolylinePoints(pointsById) ?: return null
    if (loopPoints.size < 3) return null
    return if (loopPoints.first().samePositionAs(loopPoints.last())) {
        loopPoints
    } else {
        loopPoints + loopPoints.first()
    }
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

private fun StepLiteEntity.hasFiniteGeometry(): Boolean {
    val geometryIsFinite = when (this) {
        is StepLiteEntity.Line -> start.isFinite() && end.isFinite()
        is StepLiteEntity.Polyline -> points.all { it.isFinite() }
        is StepLiteEntity.Circle -> center.isFinite() && radius.isFinite()
        is StepLiteEntity.Arc -> center.isFinite() &&
            radius.isFinite() &&
            startAngleRadians.isFinite() &&
            endAngleRadians.isFinite()
    }
    return geometryIsFinite && bounds().isFinite()
}

private fun StepLiteBounds.isFinite(): Boolean {
    return min.isFinite() && max.isFinite()
}

private fun StepLitePoint.isFinite(): Boolean {
    return x.isFinite() && y.isFinite() && z.isFinite()
}

private fun String.toStepDoubleOrNull(): Double? {
    val normalized = trim()
        .removePrefix("+")
        .replace('D', 'E')
        .replace('d', 'E')
    val value = normalized.toDoubleOrNull() ?: return null
    return value.takeIf { it.isFinite() }
}

private fun String.resolveUnit(): StepLiteUnit {
    val normalized = uppercase()
    return when {
        "'INCH'" in normalized || "'INCHES'" in normalized -> StepLiteUnit.INCH
        "'FOOT'" in normalized || "'FEET'" in normalized -> StepLiteUnit.FOOT
        ".MILLI." in normalized && ".METRE." in normalized -> StepLiteUnit.MILLIMETER
        ".CENTI." in normalized && ".METRE." in normalized -> StepLiteUnit.CENTIMETER
        ".METRE." in normalized -> StepLiteUnit.METER
        "'MILLIMETRE'" in normalized || "'MILLIMETER'" in normalized -> StepLiteUnit.MILLIMETER
        "'CENTIMETRE'" in normalized || "'CENTIMETER'" in normalized -> StepLiteUnit.CENTIMETER
        else -> StepLiteUnit.UNKNOWN
    }
}

private fun maxOf(current: StepLiteUnit, candidate: StepLiteUnit): StepLiteUnit {
    return when {
        current == StepLiteUnit.UNKNOWN -> candidate
        candidate == StepLiteUnit.UNKNOWN -> current
        candidate.isNamedConversionUnit() && !current.isNamedConversionUnit() -> candidate
        else -> current
    }
}

private fun StepLiteUnit.isNamedConversionUnit(): Boolean {
    return this == StepLiteUnit.INCH || this == StepLiteUnit.FOOT
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
