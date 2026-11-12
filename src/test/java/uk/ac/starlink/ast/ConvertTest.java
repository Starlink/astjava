package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_convert.rs}, from C's {@code testconvert.c}: {@code
 * convert} between Frames.
 *
 * <p>{@code convert_constant_numerator_units}, {@code convert_domain_gate_blocks_mismatch}, {@code
 * find_frame_domain_mismatch_returns_none} and {@code convert_basic_sky_unitmap} run here; the
 * last leaves out its {@code astEqual} checks that the base and current Frames of each result are
 * the Frames converted, which need {@code astEqual}, outside the first phase. The tests whose
 * Frames include a CmpFrame ({@code convert_basic_cmp_permmap}, {@code convert_sky_cmp_permmap},
 * {@code find_frame_cmp_needs_widened_axes}) are translated in Task 20; those whose Frames include
 * a SpecFrame or a Circle are deferred.
 *
 * <p>{@code aFramesetTriesItsBaseFrameFirst} has no Rust counterpart: it checks the order in which
 * frameset.c {@code ConvertX} tries a FrameSet's Frames.
 */
class ConvertTest {

    private static Frame activeFrame(String unit) {
        Frame f = new Frame(1);
        f.setUnit(1, unit);
        f.setActiveUnit(true);
        return f;
    }

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    /**
     * Converting a plain Frame whose Domain is SKY to a SkyFrame, and the reverse, both give a
     * UnitMap (testconvert.c Errors 1-8).
     */
    @Test
    void convertBasicSkyUnitmap() {
        SkyFrame sf = sky("FK5");
        Frame bf = new Frame(2);
        bf.setDomain("SKY");

        FrameSet fs = bf.convert(sf, "");
        assertNotNull(fs, "bf->sf converts");
        assertTrue(MappingTestData.isUnitChain(fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)));

        fs = sf.convert(bf, "");
        assertNotNull(fs, "sf->bf converts");
        assertTrue(MappingTestData.isUnitChain(fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)));
    }

    /**
     * A plain Frame whose Domain is set to something other than SKY does not convert to a SkyFrame
     * in either direction (testconvert.c Errors 9-10).
     */
    @Test
    void convertDomainGateBlocksMismatch() {
        SkyFrame sf = sky("FK5");
        Frame bf = new Frame(2);
        bf.setDomain("NOTSKY");
        assertNull(bf.convert(sf, ""));
        assertNull(sf.convert(bf, ""));
    }

    /**
     * Finding a GAPPT SkyFrame template in a plain Frame whose Domain is ARDAPP finds nothing: the
     * Domain does not match, and the cast-retry does not produce a match (testconvert.c Error 51).
     */
    @Test
    void findFrameDomainMismatchReturnsNone() {
        Frame target = new Frame(2);
        target.setDomain("ARDAPP");
        assertNull(target.findFrame(sky("GAPPT"), " "));
    }

    @Test
    void convertConstantNumeratorUnits() {
        String[][] cases = {{"1/m", "2/m"}, {"1/m", "2*m**-1"}, {"1/m", "0.5/m"}, {"Hz", "2/s"}};
        double[] expect = {0.5, 0.5, 2.0, 0.5};
        for (int i = 0; i < cases.length; i++) {
            String from = cases[i][0];
            String to = cases[i][1];
            FrameSet fs = activeFrame(from).convert(activeFrame(to), "");
            assertNotNull(fs, "no conversion from " + from + " to " + to);
            Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            double out = map.tran1(1, new double[] {1.0}, true)[0];
            assertTrue(
                    Math.abs(out - expect[i]) <= 1.0e-12,
                    "1 " + from + " is " + out + " " + to + ", expected " + expect[i]);
        }
    }

    // frameset.c ConvertX tries a FrameSet's base Frame first and its current Frame last, so when
    // both could be used the base Frame is, and becomes the Base; the Mapping runs from the
    // current Frame through it.
    @Test
    void aFramesetTriesItsBaseFrameFirst() {
        Frame a = new Frame(1);
        a.setDomain("Y");
        Frame b = new Frame(1);
        b.setDomain("Y");
        FrameSet fs = new FrameSet(a);
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(1, 2.0), b);
        Frame y = new Frame(1);
        y.setDomain("Y");
        FrameSet cvt = fs.convert(y, "");
        assertNotNull(cvt);
        assertEquals(0.5, cvt.tran1(1, new double[] {1.0}, true)[0]);
        assertEquals(1, fs.getBase());
        assertEquals(2, fs.getCurrent());
    }
}
