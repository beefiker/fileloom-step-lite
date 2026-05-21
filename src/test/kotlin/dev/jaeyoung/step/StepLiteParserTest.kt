package dev.jaeyoung.step

import java.io.ByteArrayInputStream
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepLiteParserTest {
    @Test
    fun parsesStepBoxEdgesIntoLightweightWireframe() {
        val result = StepLiteParser().parse(BoxStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals("Fixture Box", document.name)
        assertEquals(StepLiteUnit.MILLIMETER, document.unit)
        assertEquals(12, document.entities.size)
        assertEquals(0, document.unsupportedEntityCount)
        assertClose(0.0, document.bounds.min.x)
        assertClose(0.0, document.bounds.min.y)
        assertClose(0.0, document.bounds.min.z)
        assertClose(10.0, document.bounds.max.x)
        assertClose(20.0, document.bounds.max.y)
        assertClose(30.0, document.bounds.max.z)

        val first = document.entities.first()
        assertTrue(first is StepLiteEntity.Line)
        first as StepLiteEntity.Line
        assertClose(0.0, first.start.x)
        assertClose(0.0, first.start.y)
        assertClose(0.0, first.start.z)
        assertClose(10.0, first.end.x)
        assertClose(0.0, first.end.y)
        assertClose(0.0, first.end.z)
    }

    @Test
    fun acceptsStpExtensionEquivalentContentWithLowercaseHeader() {
        val lowerCaseHeader = BoxStep.replace("ISO-10303-21;", "iso-10303-21;")

        val result = StepLiteParser().parse(lowerCaseHeader.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document
        assertEquals(12, document.entities.size)
    }

    @Test
    fun rejectsNonStepInput() {
        val result = StepLiteParser().parse("not a step file".byteInputStream())

        assertEquals(
            StepLiteParseResult.Unsupported(StepLiteUnsupportedReason.NOT_STEP),
            result
        )
    }

    @Test
    fun capsInputBeforeAllocatingLargeModels() {
        val result = StepLiteParser(maxBytes = 8).parse(ByteArrayInputStream(BoxStep.toByteArray()))

        assertEquals(
            StepLiteParseResult.Unsupported(StepLiteUnsupportedReason.TOO_LARGE),
            result
        )
    }

    @Test
    fun parsesCircularEdgeCurvesIntoArcsAndClosedCircles() {
        val result = StepLiteParser().parse(CircularStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(2, document.entities.size)
        val arc = document.entities[0]
        assertTrue(arc is StepLiteEntity.Arc)
        arc as StepLiteEntity.Arc
        assertClose(5.0, arc.center.x)
        assertClose(5.0, arc.center.y)
        assertClose(2.5, arc.radius)
        assertClose(0.0, arc.startAngleRadians)
        assertClose(PI / 2.0, arc.endAngleRadians)

        val circle = document.entities[1]
        assertTrue(circle is StepLiteEntity.Circle)
        circle as StepLiteEntity.Circle
        assertClose(20.0, circle.center.x)
        assertClose(20.0, circle.center.y)
        assertClose(4.0, circle.radius)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun ignoresReferencesAndTuplesInsideStepStrings() {
        val result = StepLiteParser().parse(StringHeavyStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val line = document.entities.single()
        assertTrue(line is StepLiteEntity.Line)
        line as StepLiteEntity.Line
        assertClose(0.0, line.start.x)
        assertClose(0.0, line.start.y)
        assertClose(10.0, line.end.x)
        assertClose(20.0, line.end.y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesComplexPointAndVertexRecordsForEdgeEndpoints() {
        val result = StepLiteParser().parse(ComplexPointVertexStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val line = document.entities.single()
        assertTrue(line is StepLiteEntity.Line)
        line as StepLiteEntity.Line
        assertClose(0.0, line.start.x)
        assertClose(0.0, line.start.y)
        assertClose(0.0, line.start.z)
        assertClose(10.0, line.end.x)
        assertClose(0.0, line.end.y)
        assertClose(5.0, line.end.z)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesPolylineEdgeCurvesWithoutFlatteningToSingleLine() {
        val result = StepLiteParser().parse(PolylineStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val polyline = document.entities.single()
        assertTrue(polyline is StepLiteEntity.Polyline)
        polyline as StepLiteEntity.Polyline
        assertEquals(3, polyline.points.size)
        assertClose(0.0, polyline.points[0].x)
        assertClose(0.0, polyline.points[0].y)
        assertClose(5.0, polyline.points[1].x)
        assertClose(8.0, polyline.points[1].y)
        assertClose(10.0, polyline.points[2].x)
        assertClose(0.0, polyline.points[2].y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesComplexLineAndPolylineRecordsAsLightweightCurves() {
        val result = StepLiteParser().parse(ComplexLinePolylineStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(2, document.entities.size)
        val line = document.entities[0]
        assertTrue(line is StepLiteEntity.Line)
        line as StepLiteEntity.Line
        assertClose(0.0, line.start.x)
        assertClose(0.0, line.start.y)
        assertClose(10.0, line.end.x)
        assertClose(0.0, line.end.y)

        val polyline = document.entities[1]
        assertTrue(polyline is StepLiteEntity.Polyline)
        polyline as StepLiteEntity.Polyline
        assertEquals(3, polyline.points.size)
        assertClose(10.0, polyline.points[0].x)
        assertClose(0.0, polyline.points[0].y)
        assertClose(15.0, polyline.points[1].x)
        assertClose(8.0, polyline.points[1].y)
        assertClose(20.0, polyline.points[2].x)
        assertClose(0.0, polyline.points[2].y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesComplexEdgeCurveRecordsAsLightweightWireframes() {
        val result = StepLiteParser().parse(ComplexEdgeCurveStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val line = document.entities.single()
        assertTrue(line is StepLiteEntity.Line)
        line as StepLiteEntity.Line
        assertClose(0.0, line.start.x)
        assertClose(0.0, line.start.y)
        assertClose(10.0, line.end.x)
        assertClose(0.0, line.end.y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun countsUnknownCurveEdgesInsteadOfFlatteningThem() {
        val result = StepLiteParser().parse(UnknownCurveStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        assertTrue(document.entities.single() is StepLiteEntity.Line)
        assertEquals(1, document.unsupportedEntityCount)
    }

    @Test
    fun honorsEdgeCurveSameSenseWhenParsingCircularArcs() {
        val result = StepLiteParser().parse(ReversedSameSenseArcStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val arc = document.entities.single()
        assertTrue(arc is StepLiteEntity.Arc)
        arc as StepLiteEntity.Arc
        assertClose(0.0, arc.startAngleRadians)
        assertClose(PI / 2.0, arc.endAngleRadians)
    }

    @Test
    fun honorsEdgeCurveSameSenseWhenParsingPolylineCurves() {
        val result = StepLiteParser().parse(ReversedSameSensePolylineStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val polyline = document.entities.single()
        assertTrue(polyline is StepLiteEntity.Polyline)
        polyline as StepLiteEntity.Polyline
        assertClose(0.0, polyline.points.first().x)
        assertClose(0.0, polyline.points.first().y)
        assertClose(10.0, polyline.points.last().x)
        assertClose(0.0, polyline.points.last().y)
    }

    @Test
    fun parsesEllipticalEdgeCurvesAsLightweightPolylines() {
        val result = StepLiteParser().parse(EllipseStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(2, document.entities.size)
        val closedEllipse = document.entities[0]
        assertTrue(closedEllipse is StepLiteEntity.Polyline)
        closedEllipse as StepLiteEntity.Polyline
        assertTrue(closedEllipse.points.size > 8)
        assertClose(16.0, closedEllipse.points.first().x)
        assertClose(10.0, closedEllipse.points.first().y)
        assertClose(closedEllipse.points.first().x, closedEllipse.points.last().x)
        assertClose(closedEllipse.points.first().y, closedEllipse.points.last().y)

        val ellipseArc = document.entities[1]
        assertTrue(ellipseArc is StepLiteEntity.Polyline)
        ellipseArc as StepLiteEntity.Polyline
        assertTrue(ellipseArc.points.size > 2)
        assertClose(16.0, ellipseArc.points.first().x)
        assertClose(10.0, ellipseArc.points.first().y)
        assertClose(10.0, ellipseArc.points.last().x)
        assertClose(13.0, ellipseArc.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesComplexConicRecordsAsLightweightCurves() {
        val result = StepLiteParser().parse(ComplexConicStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(2, document.entities.size)
        val arc = document.entities[0]
        assertTrue(arc is StepLiteEntity.Arc)
        arc as StepLiteEntity.Arc
        assertClose(5.0, arc.center.x)
        assertClose(5.0, arc.center.y)
        assertClose(2.5, arc.radius)
        assertClose(0.0, arc.startAngleRadians)
        assertClose(PI / 2.0, arc.endAngleRadians)

        val ellipse = document.entities[1]
        assertTrue(ellipse is StepLiteEntity.Polyline)
        ellipse as StepLiteEntity.Polyline
        assertTrue(ellipse.points.size > 2)
        assertClose(26.0, ellipse.points.first().x)
        assertClose(20.0, ellipse.points.first().y)
        assertClose(20.0, ellipse.points.last().x)
        assertClose(23.0, ellipse.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesParabolicEdgeCurvesAsLightweightPolylines() {
        val result = StepLiteParser().parse(ParabolaStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val parabola = document.entities.single()
        assertTrue(parabola is StepLiteEntity.Polyline)
        parabola as StepLiteEntity.Polyline
        assertTrue(parabola.points.size > 8)
        assertClose(0.0, parabola.points.first().x)
        assertClose(0.0, parabola.points.first().y)
        assertClose(2.0, parabola.points[parabola.points.lastIndex / 2].x)
        assertClose(4.0, parabola.points[parabola.points.lastIndex / 2].y)
        assertClose(8.0, parabola.points.last().x)
        assertClose(8.0, parabola.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesHyperbolicEdgeCurvesAsLightweightPolylines() {
        val result = StepLiteParser().parse(HyperbolaStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val hyperbola = document.entities.single()
        assertTrue(hyperbola is StepLiteEntity.Polyline)
        hyperbola as StepLiteEntity.Polyline
        assertTrue(hyperbola.points.size > 8)
        assertClose(2.0, hyperbola.points.first().x)
        assertClose(0.0, hyperbola.points.first().y)
        assertClose(2.2552519304127614, hyperbola.points[hyperbola.points.lastIndex / 2].x)
        assertClose(0.5210953054937474, hyperbola.points[hyperbola.points.lastIndex / 2].y)
        assertClose(3.0861612696304874, hyperbola.points.last().x)
        assertClose(1.1752011936438014, hyperbola.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesBSplineEdgeCurvesAsLightweightPolylines() {
        val result = StepLiteParser().parse(BSplineStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(2, document.entities.size)
        val spline = document.entities[0]
        assertTrue(spline is StepLiteEntity.Polyline)
        spline as StepLiteEntity.Polyline
        assertTrue(spline.points.size > 8)
        assertClose(0.0, spline.points.first().x)
        assertClose(0.0, spline.points.first().y)
        assertClose(10.0, spline.points.last().x)
        assertClose(0.0, spline.points.last().y)
        assertTrue(spline.points.maxOf { it.y } > 4.0)

        val reversedSpline = document.entities[1]
        assertTrue(reversedSpline is StepLiteEntity.Polyline)
        reversedSpline as StepLiteEntity.Polyline
        assertClose(0.0, reversedSpline.points.first().x)
        assertClose(0.0, reversedSpline.points.first().y)
        assertClose(10.0, reversedSpline.points.last().x)
        assertClose(0.0, reversedSpline.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesComplexBezierCurveRecordsAsLightweightPolylines() {
        val result = StepLiteParser().parse(ComplexBezierStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val bezier = document.entities.single()
        assertTrue(bezier is StepLiteEntity.Polyline)
        bezier as StepLiteEntity.Polyline
        assertTrue(bezier.points.size > 8)
        assertClose(0.0, bezier.points.first().x)
        assertClose(0.0, bezier.points.first().y)
        assertClose(5.0, bezier.points[bezier.points.lastIndex / 2].x)
        assertClose(5.0, bezier.points[bezier.points.lastIndex / 2].y)
        assertClose(10.0, bezier.points.last().x)
        assertClose(0.0, bezier.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun honorsAxisPlacementRefDirectionWhenSamplingEllipses() {
        val result = StepLiteParser().parse(RotatedEllipseStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val ellipse = document.entities.single()
        assertTrue(ellipse is StepLiteEntity.Polyline)
        ellipse as StepLiteEntity.Polyline
        assertClose(10.0, ellipse.points.first().x)
        assertClose(16.0, ellipse.points.first().y)
        assertClose(7.0, ellipse.points.last().x)
        assertClose(10.0, ellipse.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesComplexPlacementAndDirectionRecordsForConics() {
        val result = StepLiteParser().parse(ComplexPlacementDirectionStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val ellipse = document.entities.single()
        assertTrue(ellipse is StepLiteEntity.Polyline)
        ellipse as StepLiteEntity.Polyline
        assertClose(10.0, ellipse.points.first().x)
        assertClose(16.0, ellipse.points.first().y)
        assertClose(7.0, ellipse.points.last().x)
        assertClose(10.0, ellipse.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun samplesTiltedCirclePlacementsAsLightweightPolylines() {
        val result = StepLiteParser().parse(TiltedCircleStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val circle = document.entities.single()
        assertTrue(circle is StepLiteEntity.Polyline)
        circle as StepLiteEntity.Polyline
        assertTrue(circle.points.size > 8)
        assertClose(14.0, circle.points.first().x)
        assertClose(10.0, circle.points.first().y)
        assertClose(circle.points.first().x, circle.points.last().x)
        assertClose(circle.points.first().y, circle.points.last().y)
        assertClose(6.0, circle.points.minOf { it.x })
        assertClose(14.0, circle.points.maxOf { it.x })
        assertClose(10.0, circle.points.minOf { it.y })
        assertClose(10.0, circle.points.maxOf { it.y })
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun resolvesTrimmedCurveWrappersBeforeParsingEdgeCurves() {
        val result = StepLiteParser().parse(TrimmedCurveStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(2, document.entities.size)
        val arc = document.entities[0]
        assertTrue(arc is StepLiteEntity.Arc)
        arc as StepLiteEntity.Arc
        assertClose(0.0, arc.startAngleRadians)
        assertClose(PI / 2.0, arc.endAngleRadians)

        val reversedArc = document.entities[1]
        assertTrue(reversedArc is StepLiteEntity.Arc)
        reversedArc as StepLiteEntity.Arc
        assertClose(0.0, reversedArc.startAngleRadians)
        assertClose(PI / 2.0, reversedArc.endAngleRadians)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun resolvesSurfaceAndSeamCurveWrappersBeforeParsingEdgeCurves() {
        val result = StepLiteParser().parse(SurfaceCurveWrapperStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(2, document.entities.size)
        val surfaceCurve = document.entities[0]
        assertTrue(surfaceCurve is StepLiteEntity.Polyline)
        surfaceCurve as StepLiteEntity.Polyline
        assertClose(0.0, surfaceCurve.points.first().x)
        assertClose(0.0, surfaceCurve.points.first().y)
        assertClose(10.0, surfaceCurve.points.last().x)
        assertClose(0.0, surfaceCurve.points.last().y)
        assertTrue(surfaceCurve.points.maxOf { it.y } > 4.0)

        val seamCurve = document.entities[1]
        assertTrue(seamCurve is StepLiteEntity.Polyline)
        seamCurve as StepLiteEntity.Polyline
        assertClose(0.0, seamCurve.points.first().x)
        assertClose(0.0, seamCurve.points.first().y)
        assertClose(10.0, seamCurve.points.last().x)
        assertClose(0.0, seamCurve.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun resolvesComplexCurveWrappersBeforeParsingEdgeCurves() {
        val result = StepLiteParser().parse(ComplexCurveWrapperStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(3, document.entities.size)
        val trimmedArc = document.entities[0]
        assertTrue(trimmedArc is StepLiteEntity.Arc)
        trimmedArc as StepLiteEntity.Arc
        assertClose(0.0, trimmedArc.startAngleRadians)
        assertClose(PI / 2.0, trimmedArc.endAngleRadians)

        val surfaceCurve = document.entities[1]
        assertTrue(surfaceCurve is StepLiteEntity.Polyline)
        surfaceCurve as StepLiteEntity.Polyline
        assertClose(0.0, surfaceCurve.points.first().x)
        assertClose(0.0, surfaceCurve.points.first().y)
        assertClose(10.0, surfaceCurve.points.last().x)
        assertClose(0.0, surfaceCurve.points.last().y)

        val seamCurve = document.entities[2]
        assertTrue(seamCurve is StepLiteEntity.Polyline)
        seamCurve as StepLiteEntity.Polyline
        assertClose(0.0, seamCurve.points.first().x)
        assertClose(0.0, seamCurve.points.first().y)
        assertClose(10.0, seamCurve.points.last().x)
        assertClose(0.0, seamCurve.points.last().y)
        assertEquals(0, document.unsupportedEntityCount)
    }

    @Test
    fun parsesComplexRationalBSplineRecordsAsLightweightPolylines() {
        val result = StepLiteParser().parse(ComplexRationalBSplineStep.byteInputStream())

        assertTrue("Expected Success but was $result", result is StepLiteParseResult.Success)
        val document = (result as StepLiteParseResult.Success).document

        assertEquals(1, document.entities.size)
        val spline = document.entities.single()
        assertTrue(spline is StepLiteEntity.Polyline)
        spline as StepLiteEntity.Polyline
        assertTrue(spline.points.size > 8)
        assertClose(0.0, spline.points.first().x)
        assertClose(0.0, spline.points.first().y)
        assertClose(10.0, spline.points.last().x)
        assertClose(0.0, spline.points.last().y)
        assertTrue(spline.points.maxOf { it.y } > 7.0)
        assertEquals(0, document.unsupportedEntityCount)
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertEquals(expected, actual, 0.000001)
    }

    private companion object {
        private val BoxStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom lightweight STEP fixture'),'2;1');
            FILE_NAME('box.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Fixture Box','Fixture Box','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('p0',(0.,0.,0.));
            #11=CARTESIAN_POINT('p1',(10.,0.,0.));
            #12=CARTESIAN_POINT('p2',(10.,20.,0.));
            #13=CARTESIAN_POINT('p3',(0.,20.,0.));
            #14=CARTESIAN_POINT('p4',(0.,0.,30.));
            #15=CARTESIAN_POINT('p5',(10.,0.,30.));
            #16=CARTESIAN_POINT('p6',(10.,20.,30.));
            #17=CARTESIAN_POINT('p7',(0.,20.,30.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#11);
            #22=VERTEX_POINT('',#12);
            #23=VERTEX_POINT('',#13);
            #24=VERTEX_POINT('',#14);
            #25=VERTEX_POINT('',#15);
            #26=VERTEX_POINT('',#16);
            #27=VERTEX_POINT('',#17);
            #30=LINE('',#10,#90);
            #31=LINE('',#11,#91);
            #32=LINE('',#12,#92);
            #33=LINE('',#13,#93);
            #34=LINE('',#14,#94);
            #35=LINE('',#15,#95);
            #36=LINE('',#16,#96);
            #37=LINE('',#17,#97);
            #38=LINE('',#10,#98);
            #39=LINE('',#11,#99);
            #40=LINE('',#12,#100);
            #41=LINE('',#13,#101);
            #50=EDGE_CURVE('',#20,#21,#30,.T.);
            #51=EDGE_CURVE('',#21,#22,#31,.T.);
            #52=EDGE_CURVE('',#22,#23,#32,.T.);
            #53=EDGE_CURVE('',#23,#20,#33,.T.);
            #54=EDGE_CURVE('',#24,#25,#34,.T.);
            #55=EDGE_CURVE('',#25,#26,#35,.T.);
            #56=EDGE_CURVE('',#26,#27,#36,.T.);
            #57=EDGE_CURVE('',#27,#24,#37,.T.);
            #58=EDGE_CURVE('',#20,#24,#38,.T.);
            #59=EDGE_CURVE('',#21,#25,#39,.T.);
            #60=EDGE_CURVE('',#22,#26,#40,.T.);
            #61=EDGE_CURVE('',#23,#27,#41,.T.);
            #90=VECTOR('',#102,1.);
            #91=VECTOR('',#103,1.);
            #92=VECTOR('',#104,1.);
            #93=VECTOR('',#105,1.);
            #94=VECTOR('',#106,1.);
            #95=VECTOR('',#107,1.);
            #96=VECTOR('',#108,1.);
            #97=VECTOR('',#109,1.);
            #98=VECTOR('',#110,1.);
            #99=VECTOR('',#111,1.);
            #100=VECTOR('',#112,1.);
            #101=VECTOR('',#113,1.);
            #102=DIRECTION('',(1.,0.,0.));
            #103=DIRECTION('',(0.,1.,0.));
            #104=DIRECTION('',(-1.,0.,0.));
            #105=DIRECTION('',(0.,-1.,0.));
            #106=DIRECTION('',(1.,0.,0.));
            #107=DIRECTION('',(0.,1.,0.));
            #108=DIRECTION('',(-1.,0.,0.));
            #109=DIRECTION('',(0.,-1.,0.));
            #110=DIRECTION('',(0.,0.,1.));
            #111=DIRECTION('',(0.,0.,1.));
            #112=DIRECTION('',(0.,0.,1.));
            #113=DIRECTION('',(0.,0.,1.));
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val CircularStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom circular STEP fixture'),'2;1');
            FILE_NAME('circles.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Circular Fixture','Circular Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(5.,5.,0.));
            #11=DIRECTION('',(0.,0.,1.));
            #12=DIRECTION('',(1.,0.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=CIRCLE('',#13,2.5);
            #20=CARTESIAN_POINT('',(7.5,5.,0.));
            #21=CARTESIAN_POINT('',(5.,7.5,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #24=EDGE_CURVE('',#22,#23,#14,.T.);
            #30=CARTESIAN_POINT('',(20.,20.,0.));
            #31=AXIS2_PLACEMENT_3D('',#30,#11,#12);
            #32=CIRCLE('',#31,4.);
            #33=CARTESIAN_POINT('',(24.,20.,0.));
            #34=VERTEX_POINT('',#33);
            #35=EDGE_CURVE('',#34,#34,#32,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val StringHeavyStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom string-heavy STEP fixture'),'2;1');
            FILE_NAME('strings.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('String Fixture','String Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('start label (#404, #405)',(0.,0.,0.));
            #11=CARTESIAN_POINT('end label (9.,9.) #406',(10.,20.,0.));
            #20=VERTEX_POINT('start vertex #999',#10);
            #21=VERTEX_POINT('end vertex #998',#11);
            #30=LINE('line #997',#10,#90);
            #50=EDGE_CURVE('edge #996',#20,#21,#30,.T.);
            #90=VECTOR('',#91,1.);
            #91=DIRECTION('',(1.,0.,0.));
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ComplexPointVertexStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom complex point vertex STEP fixture'),'2;1');
            FILE_NAME('complex-point-vertex.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Complex Point Vertex Fixture','Complex Point Vertex Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=(
                CARTESIAN_POINT('',(0.,0.,0.))
                GEOMETRIC_REPRESENTATION_ITEM()
                POINT()
                REPRESENTATION_ITEM('')
            );
            #11=(
                CARTESIAN_POINT('',(10.,0.,5.))
                GEOMETRIC_REPRESENTATION_ITEM()
                POINT()
                REPRESENTATION_ITEM('')
            );
            #20=(
                REPRESENTATION_ITEM('')
                VERTEX()
                VERTEX_POINT('',#10)
            );
            #21=(
                REPRESENTATION_ITEM('')
                VERTEX()
                VERTEX_POINT('',#11)
            );
            #30=LINE('',#10,#90);
            #40=EDGE_CURVE('',#20,#21,#30,.T.);
            #90=VECTOR('',#91,1.);
            #91=DIRECTION('',(1.,0.,0.));
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val PolylineStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom polyline STEP fixture'),'2;1');
            FILE_NAME('polyline.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Polyline Fixture','Polyline Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=CARTESIAN_POINT('',(5.,8.,0.));
            #12=CARTESIAN_POINT('',(10.,0.,0.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#12);
            #30=POLYLINE('',(#10,#11,#12));
            #40=EDGE_CURVE('',#20,#21,#30,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ComplexLinePolylineStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom complex line polyline STEP fixture'),'2;1');
            FILE_NAME('complex-line-polyline.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Complex Line Polyline Fixture','Complex Line Polyline Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=CARTESIAN_POINT('',(10.,0.,0.));
            #12=CARTESIAN_POINT('',(15.,8.,0.));
            #13=CARTESIAN_POINT('',(20.,0.,0.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#11);
            #22=VERTEX_POINT('',#13);
            #30=(
                CURVE()
                GEOMETRIC_REPRESENTATION_ITEM()
                LINE('',#10,#90)
                REPRESENTATION_ITEM('')
            );
            #31=(
                BOUNDED_CURVE()
                CURVE()
                GEOMETRIC_REPRESENTATION_ITEM()
                POLYLINE('',(#11,#12,#13))
                REPRESENTATION_ITEM('')
            );
            #40=EDGE_CURVE('',#20,#21,#30,.T.);
            #41=EDGE_CURVE('',#21,#22,#31,.T.);
            #90=VECTOR('',#91,1.);
            #91=DIRECTION('',(1.,0.,0.));
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ComplexEdgeCurveStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom complex edge curve STEP fixture'),'2;1');
            FILE_NAME('complex-edge-curve.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Complex Edge Curve Fixture','Complex Edge Curve Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=CARTESIAN_POINT('',(10.,0.,0.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#11);
            #30=LINE('',#10,#90);
            #40=(
                EDGE()
                EDGE_CURVE('',#20,#21,#30,.T.)
                GEOMETRIC_REPRESENTATION_ITEM()
                REPRESENTATION_ITEM('')
            );
            #90=VECTOR('',#91,1.);
            #91=DIRECTION('',(1.,0.,0.));
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val UnknownCurveStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom unknown curve STEP fixture'),'2;1');
            FILE_NAME('unknown.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Unknown Curve Fixture','Unknown Curve Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=CARTESIAN_POINT('',(10.,0.,0.));
            #12=CARTESIAN_POINT('',(20.,0.,0.));
            #13=CARTESIAN_POINT('',(30.,0.,0.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#11);
            #22=VERTEX_POINT('',#12);
            #23=VERTEX_POINT('',#13);
            #30=LINE('',#10,#90);
            #31=B_SPLINE_CURVE_WITH_KNOTS('',3,(#11,#12,#13),.UNSPECIFIED.,.F.,.F.,(2,2),(0.,1.),.UNSPECIFIED.);
            #40=EDGE_CURVE('',#20,#21,#30,.T.);
            #41=EDGE_CURVE('',#21,#23,#31,.T.);
            #90=VECTOR('',#91,1.);
            #91=DIRECTION('',(1.,0.,0.));
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ReversedSameSenseArcStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom reversed arc STEP fixture'),'2;1');
            FILE_NAME('reverse-arc.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Reversed Arc Fixture','Reversed Arc Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(5.,5.,0.));
            #11=DIRECTION('',(0.,0.,1.));
            #12=DIRECTION('',(1.,0.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=CIRCLE('',#13,2.5);
            #20=CARTESIAN_POINT('',(7.5,5.,0.));
            #21=CARTESIAN_POINT('',(5.,7.5,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #24=EDGE_CURVE('',#23,#22,#14,.F.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ReversedSameSensePolylineStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom reversed polyline STEP fixture'),'2;1');
            FILE_NAME('reverse-polyline.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Reversed Polyline Fixture','Reversed Polyline Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=CARTESIAN_POINT('',(5.,8.,0.));
            #12=CARTESIAN_POINT('',(10.,0.,0.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#12);
            #30=POLYLINE('',(#10,#11,#12));
            #40=EDGE_CURVE('',#21,#20,#30,.F.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val EllipseStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom ellipse STEP fixture'),'2;1');
            FILE_NAME('ellipse.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Ellipse Fixture','Ellipse Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(10.,10.,0.));
            #11=DIRECTION('',(0.,0.,1.));
            #12=DIRECTION('',(1.,0.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=ELLIPSE('',#13,6.,3.);
            #20=CARTESIAN_POINT('',(16.,10.,0.));
            #21=CARTESIAN_POINT('',(10.,13.,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #24=EDGE_CURVE('',#22,#22,#14,.T.);
            #25=EDGE_CURVE('',#22,#23,#14,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ComplexConicStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom complex conic STEP fixture'),'2;1');
            FILE_NAME('complex-conic.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Complex Conic Fixture','Complex Conic Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(5.,5.,0.));
            #11=DIRECTION('',(0.,0.,1.));
            #12=DIRECTION('',(1.,0.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=(
                BOUNDED_CURVE()
                CIRCLE('',#13,2.5)
                CONIC()
                CURVE()
                GEOMETRIC_REPRESENTATION_ITEM()
                REPRESENTATION_ITEM('')
            );
            #20=CARTESIAN_POINT('',(7.5,5.,0.));
            #21=CARTESIAN_POINT('',(5.,7.5,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #24=EDGE_CURVE('',#22,#23,#14,.T.);
            #30=CARTESIAN_POINT('',(20.,20.,0.));
            #31=AXIS2_PLACEMENT_3D('',#30,#11,#12);
            #32=(
                BOUNDED_CURVE()
                ELLIPSE('',#31,6.,3.)
                CONIC()
                CURVE()
                GEOMETRIC_REPRESENTATION_ITEM()
                REPRESENTATION_ITEM('')
            );
            #40=CARTESIAN_POINT('',(26.,20.,0.));
            #41=CARTESIAN_POINT('',(20.,23.,0.));
            #42=VERTEX_POINT('',#40);
            #43=VERTEX_POINT('',#41);
            #44=EDGE_CURVE('',#42,#43,#32,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ParabolaStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom parabola STEP fixture'),'2;1');
            FILE_NAME('parabola.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Parabola Fixture','Parabola Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=DIRECTION('',(0.,0.,1.));
            #12=DIRECTION('',(1.,0.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=PARABOLA('',#13,2.);
            #20=CARTESIAN_POINT('',(0.,0.,0.));
            #21=CARTESIAN_POINT('',(8.,8.,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #24=EDGE_CURVE('',#22,#23,#14,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val HyperbolaStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom hyperbola STEP fixture'),'2;1');
            FILE_NAME('hyperbola.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Hyperbola Fixture','Hyperbola Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=DIRECTION('',(0.,0.,1.));
            #12=DIRECTION('',(1.,0.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=HYPERBOLA('',#13,2.,1.);
            #20=CARTESIAN_POINT('',(2.,0.,0.));
            #21=CARTESIAN_POINT('',(3.0861612696304874,1.1752011936438014,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #24=EDGE_CURVE('',#22,#23,#14,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val BSplineStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom B-spline STEP fixture'),'2;1');
            FILE_NAME('bspline.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('B-spline Fixture','B-spline Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=CARTESIAN_POINT('',(5.,10.,0.));
            #12=CARTESIAN_POINT('',(10.,0.,0.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#12);
            #30=B_SPLINE_CURVE_WITH_KNOTS('',2,(#10,#11,#12),.UNSPECIFIED.,.F.,.F.,(3,3),(0.,1.),.UNSPECIFIED.);
            #31=EDGE_CURVE('',#20,#21,#30,.T.);
            #32=EDGE_CURVE('',#21,#20,#30,.F.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ComplexBezierStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom complex Bezier STEP fixture'),'2;1');
            FILE_NAME('complex-bezier.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Complex Bezier Fixture','Complex Bezier Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=CARTESIAN_POINT('',(5.,10.,0.));
            #12=CARTESIAN_POINT('',(10.,0.,0.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#12);
            #30=(
                BEZIER_CURVE()
                BOUNDED_CURVE()
                B_SPLINE_CURVE(2,(#10,#11,#12),.UNSPECIFIED.,.F.,.F.)
                CURVE()
                GEOMETRIC_REPRESENTATION_ITEM()
                REPRESENTATION_ITEM('')
            );
            #31=EDGE_CURVE('',#20,#21,#30,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val RotatedEllipseStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom rotated ellipse STEP fixture'),'2;1');
            FILE_NAME('rotated-ellipse.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Rotated Ellipse Fixture','Rotated Ellipse Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(10.,10.,0.));
            #11=DIRECTION('',(0.,0.,1.));
            #12=DIRECTION('',(0.,1.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=ELLIPSE('',#13,6.,3.);
            #20=CARTESIAN_POINT('',(10.,16.,0.));
            #21=CARTESIAN_POINT('',(7.,10.,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #24=EDGE_CURVE('',#22,#23,#14,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ComplexPlacementDirectionStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom complex placement direction STEP fixture'),'2;1');
            FILE_NAME('complex-placement-direction.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Complex Placement Direction Fixture','Complex Placement Direction Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(10.,10.,0.));
            #11=(
                DIRECTION('',(0.,0.,1.))
                GEOMETRIC_REPRESENTATION_ITEM()
                REPRESENTATION_ITEM('')
            );
            #12=(
                DIRECTION('',(0.,1.,0.))
                GEOMETRIC_REPRESENTATION_ITEM()
                REPRESENTATION_ITEM('')
            );
            #13=(
                AXIS2_PLACEMENT_3D('',#10,#11,#12)
                GEOMETRIC_REPRESENTATION_ITEM()
                REPRESENTATION_ITEM('')
            );
            #14=ELLIPSE('',#13,6.,3.);
            #20=CARTESIAN_POINT('',(10.,16.,0.));
            #21=CARTESIAN_POINT('',(7.,10.,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #24=EDGE_CURVE('',#22,#23,#14,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val TiltedCircleStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom tilted circle STEP fixture'),'2;1');
            FILE_NAME('tilted-circle.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Tilted Circle Fixture','Tilted Circle Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(10.,10.,0.));
            #11=DIRECTION('',(0.,1.,0.));
            #12=DIRECTION('',(1.,0.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=CIRCLE('',#13,4.);
            #20=CARTESIAN_POINT('',(14.,10.,0.));
            #21=VERTEX_POINT('',#20);
            #22=EDGE_CURVE('',#21,#21,#14,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val TrimmedCurveStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom trimmed curve STEP fixture'),'2;1');
            FILE_NAME('trimmed-curve.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Trimmed Curve Fixture','Trimmed Curve Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(5.,5.,0.));
            #11=DIRECTION('',(0.,0.,1.));
            #12=DIRECTION('',(1.,0.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=CIRCLE('',#13,2.5);
            #20=CARTESIAN_POINT('',(7.5,5.,0.));
            #21=CARTESIAN_POINT('',(5.,7.5,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #30=TRIMMED_CURVE('',#14,(#20),(#21),.T.,.CARTESIAN.);
            #31=TRIMMED_CURVE('',#14,(#21),(#20),.F.,.CARTESIAN.);
            #40=EDGE_CURVE('',#22,#23,#30,.T.);
            #41=EDGE_CURVE('',#23,#22,#31,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val SurfaceCurveWrapperStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom surface curve wrapper STEP fixture'),'2;1');
            FILE_NAME('surface-curve.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Surface Curve Fixture','Surface Curve Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=CARTESIAN_POINT('',(5.,10.,0.));
            #12=CARTESIAN_POINT('',(10.,0.,0.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#12);
            #30=B_SPLINE_CURVE_WITH_KNOTS('',2,(#10,#11,#12),.UNSPECIFIED.,.F.,.F.,(3,3),(0.,1.),.UNSPECIFIED.);
            #31=SURFACE_CURVE('',#30,(#90),.CURVE_3D.);
            #32=SEAM_CURVE('',#30,(#91,#92),.PCURVE_S1.);
            #40=EDGE_CURVE('',#20,#21,#31,.T.);
            #41=EDGE_CURVE('',#20,#21,#32,.T.);
            #90=PCURVE('',#93,#94);
            #91=PCURVE('',#93,#94);
            #92=PCURVE('',#93,#94);
            #93=PLANE('',#95);
            #94=DEFINITIONAL_REPRESENTATION('',(),#96);
            #95=AXIS2_PLACEMENT_3D('',#10,#97,#98);
            #96=REPRESENTATION_CONTEXT('','');
            #97=DIRECTION('',(0.,0.,1.));
            #98=DIRECTION('',(1.,0.,0.));
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ComplexCurveWrapperStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom complex curve wrapper STEP fixture'),'2;1');
            FILE_NAME('complex-curve-wrapper.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Complex Curve Wrapper Fixture','Complex Curve Wrapper Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(5.,5.,0.));
            #11=DIRECTION('',(0.,0.,1.));
            #12=DIRECTION('',(1.,0.,0.));
            #13=AXIS2_PLACEMENT_3D('',#10,#11,#12);
            #14=CIRCLE('',#13,2.5);
            #20=CARTESIAN_POINT('',(7.5,5.,0.));
            #21=CARTESIAN_POINT('',(5.,7.5,0.));
            #22=VERTEX_POINT('',#20);
            #23=VERTEX_POINT('',#21);
            #30=(
                BOUNDED_CURVE()
                CURVE()
                GEOMETRIC_REPRESENTATION_ITEM()
                TRIMMED_CURVE('',#14,(#20),(#21),.T.,.CARTESIAN.)
                REPRESENTATION_ITEM('')
            );
            #40=EDGE_CURVE('',#22,#23,#30,.T.);
            #50=CARTESIAN_POINT('',(0.,0.,0.));
            #51=CARTESIAN_POINT('',(5.,10.,0.));
            #52=CARTESIAN_POINT('',(10.,0.,0.));
            #60=VERTEX_POINT('',#50);
            #61=VERTEX_POINT('',#52);
            #70=B_SPLINE_CURVE_WITH_KNOTS('',2,(#50,#51,#52),.UNSPECIFIED.,.F.,.F.,(3,3),(0.,1.),.UNSPECIFIED.);
            #71=(
                CURVE()
                GEOMETRIC_REPRESENTATION_ITEM()
                SURFACE_CURVE('',#70,(#90),.CURVE_3D.)
                REPRESENTATION_ITEM('')
            );
            #72=(
                CURVE()
                GEOMETRIC_REPRESENTATION_ITEM()
                SEAM_CURVE('',#70,(#91,#92),.PCURVE_S1.)
                REPRESENTATION_ITEM('')
            );
            #80=EDGE_CURVE('',#60,#61,#71,.T.);
            #81=EDGE_CURVE('',#60,#61,#72,.T.);
            #90=PCURVE('',#93,#94);
            #91=PCURVE('',#93,#94);
            #92=PCURVE('',#93,#94);
            #93=PLANE('',#95);
            #94=DEFINITIONAL_REPRESENTATION('',(),#96);
            #95=AXIS2_PLACEMENT_3D('',#50,#97,#98);
            #96=REPRESENTATION_CONTEXT('','');
            #97=DIRECTION('',(0.,0.,1.));
            #98=DIRECTION('',(1.,0.,0.));
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        private val ComplexRationalBSplineStep = """
            ISO-10303-21;
            HEADER;
            FILE_DESCRIPTION(('Fileloom complex rational B-spline STEP fixture'),'2;1');
            FILE_NAME('complex-rational-bspline.stp','2026-05-22',('Fileloom'),('Fileloom'),'','','');
            FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));
            ENDSEC;
            DATA;
            #1=PRODUCT('Complex Rational B-spline Fixture','Complex Rational B-spline Fixture','',(#2));
            #2=PRODUCT_CONTEXT('',#3,'mechanical');
            #3=APPLICATION_CONTEXT('fileloom step lite');
            #10=CARTESIAN_POINT('',(0.,0.,0.));
            #11=CARTESIAN_POINT('',(5.,10.,0.));
            #12=CARTESIAN_POINT('',(10.,0.,0.));
            #20=VERTEX_POINT('',#10);
            #21=VERTEX_POINT('',#12);
            #30=(
                BOUNDED_CURVE()
                B_SPLINE_CURVE(2,(#10,#11,#12),.UNSPECIFIED.,.F.,.F.)
                B_SPLINE_CURVE_WITH_KNOTS((3,3),(0.,1.),.UNSPECIFIED.)
                CURVE()
                GEOMETRIC_REPRESENTATION_ITEM()
                RATIONAL_B_SPLINE_CURVE((1.,4.,1.))
                REPRESENTATION_ITEM('')
            );
            #31=EDGE_CURVE('',#20,#21,#30,.T.);
            #200=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()
    }
}
