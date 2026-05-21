package dev.jaeyoung.step

import java.io.ByteArrayInputStream
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
    }
}
