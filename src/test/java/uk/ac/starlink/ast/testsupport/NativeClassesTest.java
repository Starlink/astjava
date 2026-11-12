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
            + "       Begin SpecFrame \t# Description of spectral coordinate system\n"
            + "          Ax1 = \n"
            + "             Begin LutMap\n"
            + "             End LutMap\n"
            + "       End SpecFrame\n"
            + "    Map2 = \n"
            + "       Begin CmpMap\n"
            + "          MapA = \n"
            + "             Begin ZoomMap\n"
            + "             End ZoomMap\n"
            + "          MapB = \n"
            + "             Begin SpecFrame\n"
            + "             End SpecFrame\n"
            + "       End CmpMap\n"
            + " End FrameSet\n";

    @Test
    void listsTheBegunClassesInOrder() {
        assertEquals(
                Arrays.asList("FrameSet", "SpecFrame", "LutMap", "CmpMap", "ZoomMap", "SpecFrame"),
                NativeClasses.begun(DUMP));
    }

    @Test
    void missingClassesAreSortedWithoutRepeats() {
        assertEquals(Arrays.asList("LutMap", "SpecFrame"), NativeClasses.missing(DUMP));
        assertEquals("needs LutMap, SpecFrame", NativeClasses.needs(DUMP));
    }

    @Test
    void aDumpOfLoadableClassesNeedsNothing() {
        String dump = " Begin CmpMap\n  MapA =\n   Begin UnitMap\n   End UnitMap\n End CmpMap\n";
        assertEquals(Collections.emptyList(), NativeClasses.missing(dump));
        assertNull(NativeClasses.needs(dump));
    }
}
