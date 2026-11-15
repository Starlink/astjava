package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.erfa.Dat;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The unit tests of Rust {@code src/mappings/timemap.rs}: {@link TimeMap#astDat}, C's {@code
 * astDat}, against ERFA's {@code eraDat} (through {@code palDat}), whose table of leap seconds it
 * reads.
 */
class TimeMapModuleTest {

    /** 1960-01-01, the first date of ERFA's table. */
    private static final double FIRST_TABLE_MJD = 36934.0;

    private static final double SPD = 86400.0;

    /** Dates over the table's range and around each of its instants. */
    private static List<Double> dates() {
        List<Double> dates = new ArrayList<>();
        for (int k = 0; ; k++) {
            double m = FIRST_TABLE_MJD + k * 0.25;
            if (m > 80000.0) {
                break;
            }
            dates.add(m);
        }
        for (double[] entry : Dat.LEAP_SECONDS) {
            double m = entry[0];
            for (double x : new double[] {m, m - 1.0, m + 1.0}) {
                dates.add(x);
                dates.add(Math.nextUp(x));
                dates.add(Math.nextDown(x));
                dates.add(Math.nextUp(Math.nextUp(x)));
                dates.add(Math.nextDown(Math.nextDown(x)));
            }
        }
        return dates;
    }

    /**
     * ERFA's {@code dat} has no value before 1960, where it gives 0.0 with a warning, while {@code
     * astDat} extends its first segment backwards; the two are compared from 1960-01-01.
     */
    @Test
    void astDatAndDatAgreeOnEveryDateBothAccept() {
        int compared = 0;
        for (double m : dates()) {
            if (m < FIRST_TABLE_MJD) {
                continue;
            }
            double a = TimeMap.astDat(m, true);
            double b = Pal.dat(m);
            assertEquals(Double.doubleToRawLongBits(b), Double.doubleToRawLongBits(a), m + ": " + a + " vs " + b);
            compared++;
        }
        assertTrue(compared > 170_000);
    }

    @Test
    void astDatAndDatDifferBeforeTheTableStarts() {
        double m = FIRST_TABLE_MJD - 1.0;
        assertEquals(0.0, Pal.dat(m));
        assertNotEquals(0.0, TimeMap.astDat(m, true));
    }

    /**
     * The inverse switches at each TAI instant, which is a table entry's UTC instant plus its
     * offset.
     */
    @Test
    void astDatInverseSwitchesAtTheTaiInstants() {
        for (int i = Dat.NERA1 + 1; i < Dat.LEAP_SECONDS.size(); i++) {
            double[] entry = Dat.LEAP_SECONDS.get(i);
            double tai = entry[0] + entry[1] / SPD;
            assertEquals(-entry[1], TimeMap.astDat(tai, false));
            assertEquals(-Dat.LEAP_SECONDS.get(i - 1)[1], TimeMap.astDat(Math.nextDown(tai), false));
        }
        // The forward and inverse functions are inverse across a 1972 jump.
        double utc = 57754.0 + 0.5;
        assertEquals(-37.0, TimeMap.astDat(utc + TimeMap.astDat(utc, true) / SPD, false));
    }
}
