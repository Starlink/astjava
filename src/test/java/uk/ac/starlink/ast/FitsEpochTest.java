package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/epoch.rs}: the date of observation a
 * foreign read gives its Frames, C's {@code ChooseEpoch}, {@code TimeSysToAst} and {@code
 * TDBConv} (fitschan.c:6294, 35046, 34422). Further tests cover {@code ChooseEpoch}.
 */
class FitsEpochTest {

    @Test
    void tdbConvRoundTrips() {
        for (int scale : new int[] {TimeFrame.AST__UTC, TimeFrame.AST__TAI, TimeFrame.AST__TT, TimeFrame.AST__TCB}) {
            double tdb = FitsEpoch.tdbConv(51544.0, scale, false);
            assertNotEquals(51544.0, tdb);
            double back = FitsEpoch.tdbConv(tdb, scale, true);
            assertEquals(51544.0, back, 1e-11, "scale " + scale);
        }
        assertEquals(51544.0, FitsEpoch.tdbConv(51544.0, TimeFrame.AST__TDB, false));
    }

    @Test
    void timeSysToAstWarnsAsCDoes() {
        FitsWarnings w = new FitsWarnings();
        assertEquals(TimeFrame.AST__UTC, FitsEpoch.timeSysToAst(null, w));
        assertEquals(TimeFrame.AST__TAI, FitsEpoch.timeSysToAst("IAT", w));
        assertEquals(TimeFrame.AST__TT, FitsEpoch.timeSysToAst("TDT", w));
        assertEquals(0, w.size());
        assertEquals(TimeFrame.AST__UTC, FitsEpoch.timeSysToAst("UT", w));
        assertEquals(TimeFrame.AST__TT, FitsEpoch.timeSysToAst("ET", w));
        assertEquals(TimeFrame.AST__UTC, FitsEpoch.timeSysToAst("utc", w));
        assertEquals(3, w.size());
        for (FitsWarnings.Warning x : w.list()) {
            assertEquals("badval", x.condition);
        }
        assertEquals(
                "The original FITS header contained a value of utc for keyword TIMESYS. AST does not support"
                        + " this timescale so UTC will be used instead.",
                w.list().get(2).text);
    }

    /** With Strict in force a TIMESYS warning is an error, as C's {@code Warn} makes it. */
    @Test
    void timeSysToAstWarningIsAnErrorWithStrict() {
        FitsWarnings w = FitsWarnings.strict("BadVal", "astRead");
        AstException e = assertThrows(AstException.class, () -> FitsEpoch.timeSysToAst("ET", w));
        assertEquals(AstException.AST__BADIN, e.getStatus());
    }

    /**
     * ChooseEpoch converts the date from the TIMESYS timescale to TDB (fitschan.c:6371-6374), and
     * raises the TIMESYS warning whether or not there is a date.
     */
    @Test
    void chooseEpochConvertsToTdb() {
        FitsStore store = new FitsStore(1);
        store.mjdobs = 51544.0;
        store.timesys = "TDB";
        FitsWarnings w = new FitsWarnings();
        assertEquals(51544.0, FitsEpoch.chooseEpoch(store, w));
        store.timesys = "TAI";
        assertEquals(FitsEpoch.tdbConv(51544.0, TimeFrame.AST__TAI, false), FitsEpoch.chooseEpoch(store, w));
        store.timesys = null;
        assertEquals(FitsEpoch.tdbConv(51544.0, TimeFrame.AST__UTC, false), FitsEpoch.chooseEpoch(store, w));
        assertEquals(0, w.size());
        store.mjdobs = null;
        store.timesys = "ET";
        assertNull(FitsEpoch.chooseEpoch(store, w));
        assertEquals(1, w.size());
        assertTrue(w.list().get(0).text.contains("ET"));
    }
}
