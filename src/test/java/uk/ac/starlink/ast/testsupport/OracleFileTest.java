package uk.ac.starlink.ast.testsupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OracleFileTest {

    private static List<OracleSection> load(String name) {
        return OracleFile.parse(Fixtures.root().resolve("oracle").resolve(name));
    }

    @Test
    void sectionCounts() {
        assertEquals(277, load("headers.oracle").size());
        assertEquals(353, load("framesets.oracle").size());
        assertEquals(1631, load("simplify_fixtures.oracle").size());
    }

    @Test
    void firstHeaderSection() {
        OracleSection s = load("headers.oracle").get(0);
        assertEquals("programs/joye_car_headers/CAR_model.head", s.path);
        assertEquals(2, s.nin);
        assertEquals(2, s.nout);
        assertTrue(s.forward);
        assertArrayEquals(new double[] {1, 1, 1.504617338324905, -1.0209198256704075}, s.rows[0], 0.0);
        assertEquals(1e-7, s.rtol, 0.0);
        assertEquals(1e-7, s.atol, 0.0);
        assertEquals(1e-9, s.equivRtol, 0.0);
        assertEquals(1e-9, s.equivAtol, 0.0);
        assertEquals(1e-6, s.rtripRtol, 0.0);
        assertEquals(0.05, s.rtripAtol, 0.0);
    }

    @Test
    void badTokenAndDirection() {
        List<OracleSection> secs = OracleFile.parseText(
                "# tol: rtol=1e-07 atol=1e-07  equiv_rtol=1e-09 equiv_atol=1e-09  rtrip_rtol=1e-06 rtrip_atol=0.05\n"
                        + "[a.map  nin=1 nout=2 dir=inverse]\n"
                        + " 4 5  BAD\n",
                "test");
        assertEquals(1, secs.size());
        assertFalse(secs.get(0).forward);
        assertEquals(CValues.BAD, secs.get(0).rows[0][2], 0.0);
    }

    @Test
    void malformedRowIsAnError() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OracleFile.parseText(
                        "# tol: rtol=1 atol=1 equiv_rtol=1 equiv_atol=1 rtrip_rtol=1 rtrip_atol=1\n"
                                + "[a.map  nin=1 nout=2 dir=forward]\n 4 5\n",
                        "test"));
    }

    @Test
    void fixturesReadReturnsText() {
        assertTrue(Fixtures.read("oracle/headers.oracle").startsWith("# transform oracle"));
    }
}
