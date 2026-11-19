package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Array;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_mask_oracle.rs}: {@link Region#mask} against C's
 * {@code astMask<X>} (region.c {@code Mask<X>}, and pointlist.c {@code Mask<X>} for a PointList):
 * which pixels of a grid are set, and the count returned, masking inside and outside, with no
 * Mapping and through a Mapping from the Region's Frame to the grid, and the {@code AST__NGDIN} and
 * {@code AST__GBDIN} errors ({@code oracle/region_mask.json}, from {@code
 * tools/oracle_fixtures/region_mask_probe.c}). The "_prefilled" cases start from an array holding
 * 0, 1, 2 in turn rather than zeros, and the "_ub" cases (also prefilled) mask {@code byte} data,
 * where C masks {@code unsigned char}: the values are the same.
 *
 * <p>The cases of a Circle, a PointList and an Interval wait for those classes; the number of
 * cases run is asserted, so that the task that adds them raises it.
 */
class RegionMaskOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_mask");

    /** The number of cases whose classes are present. */
    private static final int CASES_RUN = 2;

    private static final int[] G6L = {1, 1};
    private static final int[] G6U = {6, 6};

    /** A case's Region, Mapping, whether the inside is masked, and grid. */
    private static final class Case {
        final Region region;
        final Mapping map;
        final boolean inside;
        final int[] lbnd;
        final int[] ubnd;

        Case(Region region, Mapping map, boolean inside, int[] lbnd, int[] ubnd) {
            this.region = region;
            this.map = map;
            this.inside = inside;
            this.lbnd = lbnd;
            this.ubnd = ubnd;
        }
    }

    private static Region negatedBox() {
        Region b = new Box(new Frame(2), 1, new double[] {2.0, 2.0}, new double[] {4.0, 4.0}, null);
        b.negate();
        return b;
    }

    /** The case of {@code name}, or null if its classes are not yet present. */
    private static Case build(String name) {
        String base = name;
        if (base.endsWith("_prefilled")) {
            base = base.substring(0, base.length() - "_prefilled".length());
        }
        if (base.endsWith("_ub")) {
            base = base.substring(0, base.length() - "_ub".length());
        }
        switch (base) {
            case "negated_box_inside":
                return new Case(negatedBox(), null, true, G6L, G6U);
            case "negated_box_outside":
                return new Case(negatedBox(), null, false, G6L, G6U);
            case "circle_inside":
            case "circle_outside":
            case "small_circle_inside":
            case "small_circle_outside":
            case "circle_through_zoommap":
            case "pointlist_inside":
            case "pointlist_outside":
            case "interval_inside":
            case "map_with_wrong_inputs":
            case "lower_bound_above_upper":
                return null;
            default:
                throw new AssertionError("no construction for case " + name);
        }
    }

    /** The values as digits in array order; a value that is not a digit shows as '?'. */
    private static String digits(double[] d) {
        StringBuilder sb = new StringBuilder();
        for (double v : d) {
            sb.append(v == Math.rint(v) && v >= 0.0 && v < 10.0 ? (char) ('0' + (int) v) : '?');
        }
        return sb.toString();
    }

    @Test
    void masksMatchTheCLibrary() {
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Case k = build(name);
            if (k == null) {
                continue;
            }
            run++;
            int npix = 1;
            for (int i = 0; i < k.lbnd.length; i++) {
                npix *= Math.max(k.ubnd[i] - k.lbnd[i] + 1, 1);
            }
            boolean prefilled = name.endsWith("_prefilled") || name.endsWith("_ub");
            JsonNode want = c.get(1);
            String variant = want.get("variant").textValue();
            int ndim = k.lbnd.length;
            if (name.endsWith("_ub")) {
                byte[] data = new byte[npix];
                for (int i = 0; i < npix; i++) {
                    data[i] = (byte) (prefilled ? i % 3 : 0);
                }
                int count = k.region.maskB(k.map, k.inside, ndim, k.lbnd, k.ubnd, data, (byte) 1);
                double[] d = new double[npix];
                for (int i = 0; i < npix; i++) {
                    d[i] = Array.getDouble(data, i);
                }
                check(name, want, count, d);
            } else if ("Ok".equals(variant)) {
                double[] d = new double[npix];
                for (int i = 0; i < npix; i++) {
                    d[i] = prefilled ? i % 3 : 0;
                }
                int count = k.region.maskD(k.map, k.inside, ndim, k.lbnd, k.ubnd, d, 1.0);
                check(name, want, count, d);
            } else {
                double[] d = new double[npix];
                AstException e = assertThrows(
                        AstException.class, () -> k.region.maskD(k.map, k.inside, ndim, k.lbnd, k.ubnd, d, 1.0), name);
                assertEquals("AST__" + want.get("value").textValue().toUpperCase(Locale.ROOT), e.getStatusName(), name);
            }
        }
        assertEquals(CASES_RUN, run);
    }

    private static void check(String name, JsonNode want, int count, double[] d) {
        assertEquals("Ok", want.get("variant").textValue(), name);
        assertEquals(want.get("value").get(0).intValue(), count, name + ": count");
        assertEquals(want.get("value").get(1).textValue(), digits(d), name + ": values");
    }
}
