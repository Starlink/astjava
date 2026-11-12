package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static uk.ac.starlink.ast.MappingTestData.fromRows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Translation of Rust {@code tests/test_transform_validation.rs}: every Mapping's transformation
 * makes the checks C's base-class {@code Transform} (mapping.c) makes before any class transforms
 * a point.
 *
 * <p>{@code an_undefined_direction_is_trnnd} uses a RateMap, which is outside the first phase; a
 * Mapping whose inverse is undefined, as a RateMap's is, stands in for it. The Region part of
 * {@code a_region_and_a_frameset_check_too} needs a class outside the first phase. In {@code rate_checks_its_axes}, the same stand-in, inverted, has
 * no forward transformation for the numerical rate to use, as the RateMap does.
 */
class TransformValidationTest {

    private static int code(Executable e) {
        return assertThrows(AstException.class, e).getStatus();
    }

    private static List<Mapping> mappings() {
        return Arrays.<Mapping>asList(
                new UnitMap(2),
                new ZoomMap(2, 2.0),
                new WinMap(new double[] {1.0, -2.0}, new double[] {2.0, 3.0}),
                new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null),
                new CmpMap(new ZoomMap(2, 2.0), new UnitMap(2), true));
    }

    @Test
    void aWrongNumberOfInputCoordinatesIsNcpin() {
        for (Mapping map : mappings()) {
            String name = map.astClass();
            assertEquals(
                    AstException.AST__NCPIN, code(() -> map.transform(fromRows(new double[] {1.0}), 1, true)), name);
            assertEquals(
                    AstException.AST__NCPIN,
                    code(() -> map.transform(fromRows(new double[] {1.0, 2.0, 3.0}), 1, false)),
                    name);
        }
    }

    @Test
    void aRegionAndAFramesetCheckToo() {
        FrameSet frameset = new FrameSet(new Frame(2));
        assertEquals(AstException.AST__NCPIN, code(() -> frameset.transform(fromRows(new double[] {1.0}), 1, true)));
    }

    @Test
    void tooSmallAnOutputIsNoptsOrNocts() {
        for (Mapping map : mappings()) {
            String name = map.astClass();
            double[][] pts = fromRows(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
            assertEquals(AstException.AST__NOPTS, code(() -> map.transformInto(pts, 2, true, new double[2][1])), name);
            assertEquals(AstException.AST__NOCTS, code(() -> map.transformInto(pts, 2, true, new double[1][2])), name);
        }
    }

    @Test
    void anUndefinedDirectionIsTrnnd() {
        Mapping rate = new NoInverseMap();
        double[][] pts = fromRows(new double[] {1.0});
        assertEquals(AstException.AST__TRNND, code(() -> rate.transform(pts, 1, false)));
        assertEquals(AstException.AST__TRNND, code(() -> rate.transformNanProtocol(pts, 1, false)));
    }

    /**
     * {@code astRate} checks both axis indices before any class finds a rate (mapping.c {@code
     * astRate_}), and the base class's numerical rate needs the forward transformation (mapping.c
     * {@code Rate}).
     */
    @Test
    void rateChecksItsAxes() {
        for (Mapping map : mappings()) {
            double[] at = {1.0, 2.0};
            int[][] bad = {{0, 1}, {3, 1}, {1, 0}, {1, 3}};
            for (int[] axes : bad) {
                assertEquals(
                        AstException.AST__AXIIN,
                        code(() -> map.rate(at, axes[0], axes[1])),
                        map.astClass() + " " + axes[0] + " " + axes[1]);
            }
            map.rate(at, 1, 1);
            assertEquals(AstException.AST__NCPIN, code(() -> map.rate(new double[] {1.0}, 1, 1)), map.astClass());
        }

        NoInverseMap rate = new NoInverseMap();
        rate.invert();
        assertEquals(AstException.AST__NODEF, code(() -> rate.rate(new double[] {1.0}, 1, 1)));
    }

    /** {@code astMapBox} takes a 1-based output index and refuses one outside the outputs (AST__BADCI). */
    @Test
    void mapBoxTakesAOneBasedOutput() {
        WinMap map = new WinMap(new double[] {1.0, -2.0}, new double[] {2.0, 3.0});
        double[] lbnd = {0.0, 0.0};
        double[] ubnd = {1.0, 1.0};
        double[] first = map.mapBox(lbnd, ubnd, true, 1, null, null);
        assertEquals(1.0, first[0], 1e-9);
        assertEquals(3.0, first[1], 1e-9);
        double[] second = map.mapBox(lbnd, ubnd, true, 2, null, null);
        assertEquals(-2.0, second[0], 1e-9);
        assertEquals(1.0, second[1], 1e-9);
        for (int bad : new int[] {0, 3}) {
            assertEquals(AstException.AST__BADCI, code(() -> map.mapBox(lbnd, ubnd, true, bad, null, null)));
        }
    }
}
