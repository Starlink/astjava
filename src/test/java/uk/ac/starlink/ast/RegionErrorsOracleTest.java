package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_errors_oracle.rs}: the errors C's Region
 * constructors and setters report, and the values its loader repairs ({@code
 * oracle/region_errors.json}, from {@code tools/oracle_fixtures/region_errors_probe.c}). Each
 * expected code is the status C is left holding.
 *
 * <p>The cases built with Boxes, Circles and Ellipses run: BAD coordinates given to {@code astBox}
 * (box.c {@code astInitBox}) and {@code astCircle} (circle.c {@code astInitCircle}), a Box dump
 * whose points have three values for its 2-D Frame (region.c {@code Load}), {@code astSetUnc}
 * given a Frame it cannot convert or an uncertainty whose current Frame is in another Domain,
 * FillFactor out of range, set and read, a dump with {@code MeshSz} below 5, and {@code
 * astEllipse} given a 3-D Frame, BAD values, a negative or zero axis, or points that do not
 * determine an ellipse; {@code astSetUnc} given a Polygon, which cannot be re-centred, and which
 * discards the uncertainty set before; and {@code astPolygon} (polygon.c {@code astInitPolygon})
 * given a 3-D Frame, no vertices, one or two, or a BAD vertex. The number of checks made by the
 * Box test is asserted.
 */
class RegionErrorsOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_errors");

    /** The number of C values checked. */
    private static final int CHECKS = 5;

    private int checks;

    private void assertStatus(String key, Runnable r) {
        AstException e = assertThrows(AstException.class, r::run, key);
        assertEquals(DATA.get(key).intValue(), e.getStatus(), key);
        checks++;
    }

    private static final double BAD = AstObject.AST__BAD;

    private static Box centred(double[] c, double[] p) {
        return new Box(new Frame(2), 0, c, p, null);
    }

    private static Box bounds(double[] l, double[] u) {
        return new Box(new Frame(2), 1, l, u, null);
    }

    @Test
    void boxRejectsBadCoordinatesAndTheLoaderAWrongLength() {
        assertStatus("BOX_CENTRE", () -> centred(new double[] {BAD, 0.0}, new double[] {1.0, 1.0}));
        assertStatus("BOX_CORNER", () -> centred(new double[] {0.0, 0.0}, new double[] {1.0, BAD}));
        assertStatus("BOX_LOWER", () -> bounds(new double[] {BAD, 0.0}, new double[] {1.0, 1.0}));
        assertStatus("BOX_UPPER", () -> bounds(new double[] {0.0, 0.0}, new double[] {1.0, BAD}));

        AstException e = assertThrows(
                AstException.class,
                () -> ChannelText.read(DATA.get("LOAD_NCOORD_DUMP").textValue()));
        assertEquals(DATA.get("LOAD_NCOORD").intValue(), e.getStatus());
        assertEquals(
                "astLoadRegion(Box): Corrupt Box contains  incorrect number of coordinate values per point (3).",
                e.getMsgText().split("\n", -1)[0]);
        checks++;

        assertEquals(CHECKS, checks);
    }

    private static Frame frame(int naxes, String domain) {
        Frame f = new Frame(naxes);
        if (domain != null) {
            f.setDomain(domain);
        }
        return f;
    }

    private static Circle circle() {
        return new Circle(frame(2, null), new double[] {0.0, 0.0}, 1.0, null);
    }

    private static Polygon triangle() {
        return new Polygon(frame(2, null), 3, new double[] {0.0, 1.0, 0.0}, new double[] {0.0, 0.0, 1.0}, null);
    }

    @Test
    void setUncertaintyRejectsAShapeThatCannotBeRecentred() {
        Polygon poly = triangle();
        assertStatus("SETUNC_SHAPE", () -> circle().setUnc(poly));
    }

    @Test
    void failedSetUncertaintyDropsTheOldOne() {
        Circle c = circle();
        c.setUnc(bounds(new double[] {0.0, 0.0}, new double[] {1.0, 1.0}));
        Polygon poly = triangle();
        assertThrows(AstException.class, () -> c.setUnc(poly));
        assertEquals(DATA.get("SETUNC_KEEPS_OLD").booleanValue(), c.testUnc());
    }

    private static Polygon polygon(int naxes, double[] x, double[] y) {
        return new Polygon(frame(naxes, null), x.length, x, y, null);
    }

    @Test
    void polygonConstructorErrorsMatchC() {
        double[] tx = {0.0, 1.0, 0.0};
        double[] ty = {0.0, 0.0, 1.0};
        assertStatus("POLY_NAXES", () -> polygon(3, tx, ty));
        assertStatus("POLY_EMPTY", () -> polygon(2, new double[0], new double[0]));
        AstException e = assertThrows(
                AstException.class, () -> polygon(2, new double[] {0.0, 1.0, BAD}, new double[] {0.0, BAD, 1.0}));
        assertEquals(DATA.get("POLY_BAD").intValue(), e.getStatus());
        assertEquals(
                "astInitPolygon(Polygon): One or more bad axis values supplied for the vertex number 3.",
                e.getMsgText());
    }

    @Test
    void polygonOfOneOrTwoVerticesIsAccepted() {
        assertEquals(0, DATA.get("POLY_FEW").intValue());
        Polygon one = polygon(2, new double[] {0.0}, new double[] {0.0});
        assertEquals(DATA.get("POLY_ONE_BOUNDED").booleanValue(), one.getBounded());
        Polygon two = polygon(2, new double[] {0.0, 1.0}, new double[] {0.0, 0.0});
        assertEquals(DATA.get("POLY_TWO_BOUNDED").booleanValue(), two.getBounded());
    }

    @Test
    void setUncertaintyRejectsAnUnconvertibleFrame() {
        Box box3 = new Box(frame(3, "OTHER"), 1, new double[3], new double[] {1.0, 1.0, 1.0}, null);
        assertStatus("SETUNC_FRAME", () -> circle().setUnc(box3));
    }

    @Test
    void fillFactorOutsideZeroToOneIsAnError() {
        Circle c = circle();
        c.setFillFactor(0.5);
        assertStatus("FILL_HIGH", () -> c.setFillFactor(1.5));
        assertEquals(DATA.get("FILL_AFTER").doubleValue(), c.getFillFactor());
        assertStatus("FILL_LOW", () -> c.setFillFactor(-0.1));
    }

    @Test
    void loaderRejectsAnOutOfRangeFillAndRaisesMeshSizeToFive() {
        assertStatus(
                "LOAD_FILL", () -> ChannelText.read(DATA.get("LOAD_FILL_DUMP").textValue()));
        Region r = (Region) ChannelText.read(DATA.get("LOAD_MESH_DUMP").textValue());
        assertEquals(DATA.get("LOAD_MESH_SIZE").intValue(), r.getMeshSize());
    }

    @Test
    void circleRejectsBadCoordinates() {
        assertStatus("CIRCLE_CENTRE", () -> new Circle(frame(2, null), new double[] {BAD, 0.0}, 1.0, null));
        assertStatus("CIRCLE_RADIUS", () -> new Circle(frame(2, null), new double[] {0.0, 0.0}, BAD, null));
        assertStatus(
                "CIRCLE_CIRCUM",
                () -> new Circle(frame(2, null), new double[] {0.0, 0.0}, new double[] {1.0, BAD}, null));
    }

    private static Ellipse ellipse(int naxes, double[] centre, double a, double b, double angle) {
        return new Ellipse(frame(naxes, null), 1, centre, new double[] {a, b}, new double[] {angle}, null);
    }

    private static double[] transform(Region r) {
        double[][] out = r.transform(new double[][] {{0.5}, {0.5}}, 1, true);
        return new double[] {out[0][0], out[1][0]};
    }

    @Test
    void ellipseConstructorErrorsMatchC() {
        assertStatus("ELL_NAXES", () -> ellipse(3, new double[] {0.0, 0.0, 0.0}, 2.0, 1.0, 0.3));
        assertStatus("ELL_CENTRE", () -> ellipse(2, new double[] {BAD, 0.0}, 2.0, 1.0, 0.3));
        assertStatus("ELL_AXIS", () -> ellipse(2, new double[] {0.0, 0.0}, 2.0, BAD, 0.3));
        assertStatus("ELL_ANGLE", () -> ellipse(2, new double[] {0.0, 0.0}, 2.0, 1.0, BAD));
    }

    @Test
    void ellipseWithANegativeAxisIsAnEllipse() {
        assertEquals(0, DATA.get("ELL_NEGATIVE").intValue());
        assertEquals(0, DATA.get("ELL_NEGATIVE_USE").intValue());
        Ellipse e = ellipse(2, new double[] {0.0, 0.0}, -2.0, 1.0, 0.3);
        JsonNode want = DATA.get("ELL_NEGATIVE_OUT");
        assertEquals(want.get(0).doubleValue(), transform(e)[0]);
        assertEquals(want.get(1).doubleValue(), transform(e)[1]);
    }

    @Test
    void ellipseThatIsNoEllipseFailsWhenUsed() {
        assertEquals(0, DATA.get("ELL_ZERO").intValue());
        Ellipse e = ellipse(2, new double[] {0.0, 0.0}, 0.0, 1.0, 0.3);
        assertStatus("ELL_ZERO_USE", () -> transform(e));
        assertEquals(0, DATA.get("ELL_DEGENERATE_LOAD").intValue());
        Region loaded =
                (Region) ChannelText.read(DATA.get("ELL_DEGENERATE_DUMP").textValue());
        AstException ex = assertThrows(AstException.class, () -> transform(loaded));
        assertEquals(DATA.get("ELL_DEGENERATE_USE").intValue(), ex.getStatus());
        assertEquals("astInitEllipse(Ellipse): The supplied points do not determine an ellipse.", ex.getMsgText());
    }

    @Test
    void setUncertaintyMatchesAnyFrameOfTheUncertainty() {
        assertEquals(0, DATA.get("SETUNC_OTHER_CURRENT").intValue());
        Circle target = new Circle(frame(2, "FOO"), new double[] {1.0, 0.5}, 0.1, null);
        Region unc = new Circle(frame(2, "FOO"), new double[] {1.0, 0.5}, 0.01, null)
                .mapRegionInto(new ZoomMap(2, 2.0), frame(2, "BAR"));
        target.setUnc(unc);
        assertEquals(DATA.get("SETUNC_OTHER_CURRENT_DUMP").textValue(), ChannelText.dump(target));
    }
}
