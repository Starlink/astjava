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
 * Mapping whose inverse is undefined, as a RateMap's is, stands in for it. The FrameSet part of
 * {@code a_region_and_a_frameset_check_too} is translated in Task 16, and its Region part needs a
 * class outside the first phase; {@code rate_checks_its_axes} and {@code
 * map_box_takes_a_one_based_output} are translated in Task 8.
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
}
