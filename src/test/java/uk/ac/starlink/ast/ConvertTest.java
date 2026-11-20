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
 * find_frame_domain_mismatch_returns_none}, {@code convert_basic_sky_unitmap}, and the CmpFrame
 * tests ({@code convert_basic_cmp_permmap}, {@code convert_sky_cmp_permmap} and {@code
 * find_frame_cmp_needs_widened_axes}), whose CmpFrames hold a SpecFrame, run here; they leave out
 * their {@code astEqual} checks that the base and current Frames of each result are the Frames
 * converted, as this library has no {@code astEqual}. {@code convert_between_regions_does_not_clip}
 * converts between two Circles.
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

    /** The CmpFrame of a frequency SpecFrame and an FK5 SkyFrame (testconvert.c {@code cf}). */
    private static CmpFrame specSky() {
        SpecFrame df = new SpecFrame();
        df.setSystem("FREQ");
        return new CmpFrame(df, sky("FK5"));
    }

    private static boolean isPerm(FrameSet fs) {
        return fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT) instanceof PermMap;
    }

    /**
     * A plain two-axis Frame converts to and from a CmpFrame of a SpecFrame and a SkyFrame only if
     * its Domain is SKY, with a PermMap, as the CmpFrame holds the SkyFrame on axes 2 and 3
     * (testconvert.c Errors 11-26).
     */
    @Test
    void convertBasicCmpPermmap() {
        CmpFrame cf = specSky();
        Frame notSky = new Frame(2);
        notSky.setDomain("NOTSKY");
        assertNull(notSky.convert(cf, ""));
        assertNull(cf.convert(notSky, ""));

        Frame bf = new Frame(2);
        bf.setDomain("SKY");
        FrameSet fs = bf.convert(cf, "");
        assertNotNull(fs, "bf->cf converts");
        assertTrue(isPerm(fs));
        fs = cf.convert(bf, "");
        assertNotNull(fs, "cf->bf converts");
        assertTrue(isPerm(fs));
    }

    /** A SkyFrame converts to and from a CmpFrame holding it with a PermMap. */
    @Test
    void convertSkyCmpPermmap() {
        SkyFrame sf = sky("FK5");
        CmpFrame cf = specSky();
        FrameSet fs = sf.convert(cf, "");
        assertNotNull(fs, "sf->cf converts");
        assertTrue(isPerm(fs));
        fs = cf.convert(sf, "");
        assertNotNull(fs, "cf->sf converts");
        assertTrue(isPerm(fs));
    }

    /**
     * A CmpFrame template is found in a SkyFrame target; a SkyFrame template, whose MinAxes and
     * MaxAxes are 2, is not found in the three-axis CmpFrame until they are widened, and then with
     * a PermMap.
     */
    @Test
    void findFrameCmpNeedsWidenedAxes() {
        SkyFrame sf = sky("FK5");
        CmpFrame cf = specSky();
        assertNotNull(sf.findFrame(cf, " "));
        assertNull(cf.findFrame(sf, " "));
        SkyFrame sf2 = sky("FK5");
        sf2.setMaxAxes(3);
        sf2.setMinAxes(1);
        FrameSet fs = cf.findFrame(sf2, " ");
        assertNotNull(fs, "widened template matches");
        assertTrue(isPerm(fs));
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
        assertTrue(MappingTestData.isUnitMap(fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)));

        fs = sf.convert(bf, "");
        assertNotNull(fs, "sf->bf converts");
        assertTrue(MappingTestData.isUnitMap(fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)));
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

    /**
     * Converting between two Regions gives a Mapping between their Frames that does not clip, as
     * region.c's {@code Convert} and {@code ConvertX} replace each Region by its Frame: the centre
     * of a Galactic Circle well outside an ICRS Circle still converts, to C's value (to the six
     * figures the Rust test records).
     */
    @Test
    void convertBetweenRegionsDoesNotClip() {
        SkyFrame icrs = new SkyFrame();
        icrs.setSystem("ICRS");
        SkyFrame gal = new SkyFrame();
        gal.setSystem("Galactic");
        Circle par = new Circle(icrs, new double[] {1.0, 0.5}, 0.01, null);
        Circle err = new Circle(gal, new double[] {2.0, -0.3}, 0.0001, null);
        FrameSet fs = err.convert(par, "");
        assertNotNull(fs, "the Circles convert");
        double[][] out = fs.tran2(1, new double[] {2.0}, new double[] {-0.3}, true);
        assertEquals(0.0273624, out[0][0], 1e-6);
        assertEquals(0.784531, out[1][0], 1e-6);
    }
}
