package uk.ac.starlink.ast.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class NativeClassesTest {

    private static final String DUMP = " Begin FrameSet \t# Set of inter-related coordinate systems\n"
            + " IsA Object\n"
            + "    Frm1 = \t# Frame number 1\n"
            + "       Begin FluxFrame \t# Description of flux values\n"
            + "          Ax1 = \n"
            + "             Begin LutMap\n"
            + "             End LutMap\n"
            + "       End FluxFrame\n"
            + "    Map2 = \n"
            + "       Begin CmpMap\n"
            + "          MapA = \n"
            + "             Begin ZoomMap\n"
            + "             End ZoomMap\n"
            + "          MapB = \n"
            + "             Begin FluxFrame\n"
            + "             End FluxFrame\n"
            + "       End CmpMap\n"
            + " End FrameSet\n";

    @Test
    void listsTheBegunClassesInOrder() {
        assertEquals(
                Arrays.asList("FrameSet", "FluxFrame", "LutMap", "CmpMap", "ZoomMap", "FluxFrame"),
                NativeClasses.begun(DUMP));
    }

    @Test
    void missingClassesAreSortedWithoutRepeats() {
        assertEquals(Arrays.asList("FluxFrame", "LutMap"), NativeClasses.missing(DUMP));
        assertEquals("needs FluxFrame, LutMap", NativeClasses.needs(DUMP));
    }

    @Test
    void aDumpOfLoadableClassesNeedsNothing() {
        String dump = " Begin CmpMap\n  MapA =\n   Begin UnitMap\n   End UnitMap\n End CmpMap\n";
        assertEquals(Collections.emptyList(), NativeClasses.missing(dump));
        assertNull(NativeClasses.needs(dump));
    }

    @Test
    void aFitsHeaderNeedsTheClassesOfItsBegastCards() {
        String header = "BEGAST_A= 'FrameSet'           / Set of inter-related coordinate systems\n"
                + "BEGAST_B= 'Frame   '           / Coordinate system description\n"
                + "BEGAST_C= 'MathMap '           / Transformation using mathematical functions\n"
                + "BEGAST_D= 'LutMap  '\n"
                + "BEGAST_E= 'MathMap '\n"
                + "CTYPE1  = 'RA---TAN'\n";
        assertEquals("needs LutMap, MathMap", NativeClasses.needsInFitsHeader(header));
        assertNull(NativeClasses.needsInFitsHeader("BEGAST_A= 'ZoomMap '\nZOOM_A  =                  2.0\n"));
    }
}
