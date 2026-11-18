package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_constructor_checks.rs}: constructors and setters refuse
 * arguments C refuses, with C's status.
 *
 * <p>The other tests need classes not yet ported.
 */
class ConstructorChecksTest {

    /** A Mapping with an inverse but no forward transformation: an inverted RateMap. */
    private static Mapping inverseOnly() {
        RateMap rate = new RateMap(new ZoomMap(1, 2.0), 1, 1);
        rate.invert();
        assertTrue(!rate.getTranForward() && rate.getTranInverse());
        return rate;
    }

    /** A Mapping with a forward but no inverse transformation: a RateMap. */
    private static Mapping forwardOnly() {
        RateMap rate = new RateMap(new ZoomMap(1, 2.0), 1, 1);
        assertTrue(rate.getTranForward() && !rate.getTranInverse());
        return rate;
    }

    /**
     * tranmap.c astInitTranMap: the first Mapping supplies the forward transformation and the
     * second the inverse.
     */
    @Test
    void aTranmapNeedsItsTwoDirections() {
        AstException e = assertThrows(AstException.class, () -> new TranMap(inverseOnly(), new UnitMap(1)));
        assertEquals(AstException.AST__INTRD, e.getStatus());
        e = assertThrows(AstException.class, () -> new TranMap(new UnitMap(1), forwardOnly()));
        assertEquals(AstException.AST__INTRD, e.getStatus());
        new TranMap(forwardOnly(), inverseOnly());
    }

    /**
     * switchmap.c astInitSwitchMap: the forward selector needs a forward transformation and the
     * inverse selector an inverse one.
     */
    @Test
    void aSwitchmapSelectorNeedsItsDirection() {
        AstException e = assertThrows(
                AstException.class, () -> new SwitchMap(inverseOnly(), null, new Mapping[] {new ZoomMap(1, 2.0)}));
        assertEquals(AstException.AST__INTRD, e.getStatus());
        e = assertThrows(
                AstException.class, () -> new SwitchMap(null, forwardOnly(), new Mapping[] {new ZoomMap(1, 2.0)}));
        assertEquals(AstException.AST__INTRD, e.getStatus());
    }

    /** wcsmap.c SetPV: an axis out of range, or one that is not celestial, is AST__AXIIN. */
    @Test
    void aWcsmapPvNeedsACelestialAxis() {
        WcsMap wcs = new WcsMap(3, WcsMap.AST__TAN, 1, 2);
        for (int axis : new int[] {0, 3, 4}) {
            AstException e = assertThrows(AstException.class, () -> wcs.setPV(axis - 1, 1, 0.0));
            assertEquals(AstException.AST__AXIIN, e.getStatus(), "axis " + axis);
        }
    }

    /** mathmap.c astInitMathMap: too few forward or inverse functions is AST__INNTF. */
    @Test
    void aMathmapNeedsAFunctionPerOutput() {
        AstException e =
                assertThrows(AstException.class, () -> new MathMap(1, 2, new String[] {"y=x"}, new String[] {"x=y"}));
        assertEquals(AstException.AST__INNTF, e.getStatus());
        e = assertThrows(AstException.class, () -> new MathMap(2, 1, new String[] {"y=x1"}, new String[] {"x1=y"}));
        assertEquals(AstException.AST__INNTF, e.getStatus());
    }

    /** polymap.c StoreArrays: a term for an output that does not exist is AST__BADCI. */
    @Test
    void aPolymapTermNeedsARealOutput() {
        for (int outAxis : new int[] {0, 2}) {
            double[] bad = {1.0, outAxis, 1.0};
            double[] good = {1.0, 1.0, 1.0};
            AstException e = assertThrows(AstException.class, () -> new PolyMap(1, 1, 1, bad, 0, null));
            assertEquals(AstException.AST__BADCI, e.getStatus(), "forward, output " + outAxis);
            e = assertThrows(AstException.class, () -> new PolyMap(1, 1, 1, good, 1, bad));
            assertEquals(AstException.AST__BADCI, e.getStatus(), "inverse, output " + outAxis);
        }
    }

    /**
     * slamap.c AddSlaCvt, specmap.c AddSpecCvt and timemap.c AddTimeCvt: each conversion takes
     * exactly its number of arguments, or it is AST__TIMIN.
     */
    @Test
    void aConversionTakesExactlyItsArguments() {
        SlaMap sla = new SlaMap();
        AstException e = assertThrows(AstException.class, () -> sla.add("PREC", new double[] {1950.0}));
        assertEquals(AstException.AST__TIMIN, e.getStatus());
        e = assertThrows(AstException.class, () -> sla.add("PREC", new double[] {1950.0, 2000.0, 1.0}));
        assertEquals(AstException.AST__TIMIN, e.getStatus());
        sla.add("PREC", new double[] {1950.0, 2000.0});

        SpecMap spec = new SpecMap(1, 0);
        e = assertThrows(AstException.class, () -> spec.specAdd("FRTOVL", new double[0]));
        assertEquals(AstException.AST__TIMIN, e.getStatus());
        spec.specAdd("FRTOVL", new double[] {1.0e11});

        TimeMap time = new TimeMap();
        e = assertThrows(AstException.class, () -> time.timeAdd("MJDTOMJD", new double[] {0.0}));
        assertEquals(AstException.AST__TIMIN, e.getStatus());
        time.timeAdd("MJDTOMJD", new double[] {0.0, 1.0});
    }
}
