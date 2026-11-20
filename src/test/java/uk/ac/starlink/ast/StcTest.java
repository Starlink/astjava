package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Translation of jniast's {@code StcTest}: {@code testConstructors}, each of the four Stc classes
 * made from a Box and two AstroCoords elements. jniast's {@code testReads} and {@code testChandra}
 * read STC-X documents through an XmlChan, which this library does not have, and are not
 * translated.
 */
class StcTest {

    @Test
    void constructors() {
        Region region = box(-100, -100, 100, 100);
        AstroCoords ac1 = new AstroCoords(new String[] {"X", "Y"});
        ac1.setValue(box(1, 1, 5, 10));
        AstroCoords ac2 = new AstroCoords(new String[] {"RA", "DEC"});
        ac2.setError(box(-1, -1, 1, 1));
        AstroCoords[] acs = {ac1, ac2};

        checkStc(new StcCatalogEntryLocation(region, acs), region, acs);
        checkStc(new StcObsDataLocation(region, acs), region, acs);
        checkStc(new StcResourceProfile(region, acs), region, acs);
        checkStc(new StcSearchLocation(region, acs), region, acs);
    }

    private static void checkStc(Stc stc, Region region, AstroCoords[] acs) {
        assertSameShape(region, stc.getStcRegion());
        assertEquals(acs.length, stc.getStcNCoord());
        for (int i = 0; i < acs.length; i++) {
            AstroCoords ac = stc.getStcCoord(i + 1);
            if (acs[i].getName() == null) {
                assertNull(ac.getName());
            } else {
                assertArrayEquals(acs[i].getName(), ac.getName());
            }
        }
    }

    private static Box box(double x1, double y1, double x2, double y2) {
        return new Box(new Frame(2), 1, new double[] {x1, y1}, new double[] {x2, y2}, null);
    }

    private static void assertSameShape(Region region1, Region region2) {
        int ov = region1.overlap(region2);
        assertEquals(Region.OVERLAP_SAME, ov, "Regions don't match (" + ov + ")");
    }
}
