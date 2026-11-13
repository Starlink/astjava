package uk.ac.starlink.ast.internal.wcslib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The tests of Rust {@code src/wcslib/mod.rs} ({@code mod tests}): the projection table's
 * parameter limits and the projection codes.
 */
class WcsProjectionTest {

    /**
     * The {@code mxpar} and {@code mxpar2} columns of C's {@code PrjInfo} table (wcsmap.c:684-716),
     * as {@code astGetPVMax} reports them. Golden values come from a probe against the reference
     * {@code libast}; {@code mxpar2} is 4 everywhere except {@code -TPN}.
     */
    @Test
    void pvLimitsMatchTheCProjectionTable() {
        Object[][] cases = {
            {WcsProjection.AZP, 2, 4},
            {WcsProjection.SZP, 3, 4},
            {WcsProjection.TAN, 0, 4},
            {WcsProjection.STG, 0, 4},
            {WcsProjection.SIN, 2, 4},
            {WcsProjection.ARC, 0, 4},
            {WcsProjection.ZPN, WcsProjection.WCSLIB_MXPAR, 4},
            {WcsProjection.ZEA, 0, 4},
            {WcsProjection.AIR, 1, 4},
            {WcsProjection.CYP, 2, 4},
            {WcsProjection.CEA, 1, 4},
            {WcsProjection.CAR, 0, 4},
            {WcsProjection.MER, 0, 4},
            {WcsProjection.SFL, 0, 4},
            {WcsProjection.PAR, 0, 4},
            {WcsProjection.MOL, 0, 4},
            {WcsProjection.AIT, 0, 4},
            {WcsProjection.COP, 2, 4},
            {WcsProjection.COE, 2, 4},
            {WcsProjection.COD, 2, 4},
            {WcsProjection.COO, 2, 4},
            {WcsProjection.BON, 1, 4},
            {WcsProjection.PCO, 0, 4},
            {WcsProjection.TSC, 0, 4},
            {WcsProjection.CSC, 0, 4},
            {WcsProjection.QSC, 0, 4},
            {WcsProjection.NCP, 2, 4},
            {WcsProjection.GLS, 0, 4},
            {WcsProjection.HPX, 2, 4},
            {WcsProjection.XPH, 0, 4},
            {WcsProjection.TPN, WcsProjection.WCSLIB_MXPAR, WcsProjection.WCSLIB_MXPAR},
        };
        for (Object[] c : cases) {
            WcsProjection proj = (WcsProjection) c[0];
            assertEquals((int) (Integer) c[1], proj.pvMaxLat(), "mxpar for " + proj.code());
            assertEquals((int) (Integer) c[2], proj.pvMaxLon(), "mxpar2 for " + proj.code());
        }
    }

    @Test
    void projectionCodeRoundTrip() {
        for (WcsProjection proj : new WcsProjection[] {
            WcsProjection.TAN,
            WcsProjection.SIN,
            WcsProjection.AIT,
            WcsProjection.CAR,
            WcsProjection.AZP,
            WcsProjection.HPX,
            WcsProjection.TPN,
            WcsProjection.NCP,
            WcsProjection.GLS
        }) {
            String code = proj.code();
            assertEquals(proj, WcsProjection.fromCode(code), "round-trip failed for " + code);
        }
    }

    @Test
    void projectionCodeCaseInsensitive() {
        assertEquals(WcsProjection.TAN, WcsProjection.fromCode("tan"));
        assertEquals(WcsProjection.AIT, WcsProjection.fromCode("Ait"));
        assertNull(WcsProjection.fromCode("XYZ"));
    }
}
