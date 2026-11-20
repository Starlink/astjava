package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_intramap.rs} (intramap.c), less the three tests that
 * compare with the C probe, which {@link IntraMapOracleTest} translates, and of jniast's IntraMap
 * tests (AstTest {@code testMapping}, ThreadTest {@code testIntraMap}), with the Java API: the
 * Transformer classes; IntraMaps written by name and read back with the Transformer of an IntraMap
 * made in the same process, or one registered with the Channel, through a Channel and a FitsChan,
 * at the top level and inside a CmpMap, and AST__URITF where the name stands for no Transformer;
 * the numbers of coordinates C's loader checks (probe {@code intramap_load_probe.c}); the errors of
 * a Transformer that throws or returns arrays of the wrong shape; the registration of functions,
 * C's {@code astIntraReg}; and simplification of IntraMaps in series (intramap.c {@code MapMerge}).
 */
class IntraMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final Map<String, String> LOAD_PROBE = ProbeOutput.load("intramap_load_probe");

    /** A doubling transformation, as the probe registers "double": forward 2x, inverse x/2. */
    static final class Doubler extends Transformer1 {
        @Override
        public boolean simpFI() {
            return true;
        }

        @Override
        public boolean simpIF() {
            return true;
        }

        @Override
        public double[] tran1(int npoint, double[] xin, boolean forward) {
            double[] out = new double[npoint];
            for (int i = 0; i < npoint; i++) {
                double x = xin[i];
                out[i] = x == BAD ? BAD : forward ? 2.0 * x : 0.5 * x;
            }
            return out;
        }

        @Override
        public String getPurpose() {
            return "Double the value";
        }

        @Override
        public String getAuthor() {
            return "AST test suite";
        }

        @Override
        public String getContact() {
            return "starlink-ast";
        }
    }

    /** The probe's "nofwd": the doubling transformation without a forward direction. */
    static final class NoFwd extends Transformer1 {
        @Override
        public boolean hasForward() {
            return false;
        }

        @Override
        public double[] tran1(int npoint, double[] xin, boolean forward) {
            double[] out = new double[npoint];
            for (int i = 0; i < npoint; i++) {
                out[i] = xin[i] == BAD ? BAD : 0.5 * xin[i];
            }
            return out;
        }

        @Override
        public String getPurpose() {
            return "No forward transformation";
        }

        @Override
        public String getAuthor() {
            return "AST test suite";
        }

        @Override
        public String getContact() {
            return "starlink-ast";
        }
    }

    /** jniast's {@code TestTransformer2} (AstTest.java): a two-dimensional stretch, Serializable as there. */
    static final class Stretch extends Transformer2 implements Serializable {
        private static final long serialVersionUID = 1L;
        private final double xfactor;
        private final double yfactor;

        Stretch(double x, double y) {
            xfactor = x;
            yfactor = y;
        }

        @Override
        public double[][] tran2(int npoint, double[] xin, double[] yin, boolean forward) {
            double[] xout = new double[npoint];
            double[] yout = new double[npoint];
            double xf = forward ? xfactor : 1.0 / xfactor;
            double yf = forward ? yfactor : 1.0 / yfactor;
            for (int i = 0; i < npoint; i++) {
                xout[i] = xf * xin[i];
                yout[i] = yf * yin[i];
            }
            return new double[][] {xout, yout};
        }

        @Override
        public boolean simpFI() {
            return true;
        }

        @Override
        public boolean simpIF() {
            return true;
        }

        @Override
        public boolean hasInverse() {
            return xfactor != 0 && yfactor != 0;
        }

        @Override
        public String getAuthor() {
            return "Mark Taylor (Starlink)";
        }

        @Override
        public String getContact() {
            return "Desolation Row";
        }

        @Override
        public String getPurpose() {
            return "Two-dimensional stretch";
        }
    }

    /** A Transformer of any numbers of coordinates whose transformation is {@code body}. */
    private static class Failing extends Transformer {
        private final Supplier<double[][]> body;
        private final Exception thrown;

        Failing(Supplier<double[][]> body) {
            this.body = body;
            this.thrown = null;
        }

        Failing(Exception thrown) {
            this.body = null;
            this.thrown = thrown;
        }

        @Override
        public boolean canTransformCoords(int nin, int nout) {
            return true;
        }

        @Override
        public double[][] tranP(int npoint, int ncoordIn, double[][] in, boolean forward, int ncoordOut)
                throws Exception {
            if (thrown != null) {
                throw thrown;
            }
            return body.get();
        }

        @Override
        public String getPurpose() {
            return "";
        }

        @Override
        public String getAuthor() {
            return "";
        }

        @Override
        public String getContact() {
            return "";
        }
    }

    /**
     * C's {@code Sum2} of {@code probes/intramap_load_probe.c}: two inputs and one output; forward
     * the sum of the inputs, inverse half the input on each output.
     */
    static final class Sum2 extends Transformer {
        @Override
        public boolean canTransformCoords(int nin, int nout) {
            return nin == 2 && nout == 1;
        }

        @Override
        public double[][] tranP(int npoint, int ncoordIn, double[][] in, boolean forward, int ncoordOut) {
            double[][] out = new double[ncoordOut][npoint];
            for (int i = 0; i < npoint; i++) {
                if (forward) {
                    out[0][i] = in[0][i] + in[1][i];
                } else {
                    out[0][i] = 0.5 * in[0][i];
                    out[1][i] = 0.5 * in[0][i];
                }
            }
            return out;
        }

        @Override
        public String getPurpose() {
            return "Sum of two inputs";
        }

        @Override
        public String getAuthor() {
            return "AST test suite";
        }

        @Override
        public String getContact() {
            return "starlink-ast";
        }
    }

    /** The text a default Channel writes for {@code obj}. */
    private static String dump(AstObject obj) {
        return ChannelText.dump(obj);
    }

    private static Channel reader(String text) {
        return new Channel(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), null);
    }

    private static AstObject readBack(String text) throws IOException {
        return reader(text).read();
    }

    /**
     * {@code text} with the function name of {@code map} replaced by {@code name}, as a dump of
     * {@code map} reads in another process, where its name stands for no Transformer.
     */
    private static String renamed(String text, IntraMap map, String name) {
        return text.replace("Fname = \"" + map.getFname() + "\"", "Fname = \"" + name + "\"");
    }

    // ------------------------------------------------------------------
    // Translations of test_intramap.rs
    // ------------------------------------------------------------------

    /** NOFWD and NOINV decide which directions are defined, relative to the Invert flag. */
    @Test
    void flagsDecideTheDefinedDirections() {
        IntraMap m = new IntraMap(new NoFwd());
        assertFalse(m.getTranForward());
        assertTrue(m.getTranInverse());
        AstException e = assertThrows(AstException.class, () -> m.tran1(1, new double[] {1.0}, true));
        assertEquals(AstException.AST__TRNND, e.getStatus());
        m.invert();
        assertTrue(m.getTranForward());
        assertFalse(m.getTranInverse());
        IntraMap noinv = new IntraMap(new Stretch(0.0, 2.0));
        assertTrue(noinv.getTranForward());
        assertFalse(noinv.getTranInverse());
    }

    /**
     * jniast's construction checks (jni/IntraMap.c {@code construct}): a Transformer that cannot
     * map the numbers of coordinates given is an IllegalArgumentException, and the registration of
     * one Transformer with other numbers of coordinates than an IntraMap in use made with it is
     * AST__MRITF, as C's {@code astIntraReg} reports it. C's {@code astInitIntraMap} checks
     * (AST__BADNI, AST__BADNO) cannot fail, since the function is registered with the IntraMap's
     * numbers.
     */
    @Test
    void constructionChecksAsJniastDoes() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IntraMap(new Doubler(), 2, 1));
        assertEquals("Transformer will not map 2 -> 1 coordinates", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new IntraMap(new Stretch(1, 1), 1, 1));
        assertThrows(NullPointerException.class, () -> new IntraMap((Transformer) null, 1, 1));
        Failing any = new Failing(() -> null);
        IntraMap m = new IntraMap(any, 3, 2);
        assertEquals(3, m.getNin());
        assertEquals(2, m.getNout());
        IntraMap again = new IntraMap(any, 3, 2);
        assertEquals(m.getFname(), again.getFname());
        AstException mritf = assertThrows(AstException.class, () -> new IntraMap(any, 2, 2));
        assertEquals(AstException.AST__MRITF, mritf.getStatus());
    }

    /**
     * intramap.c {@code IntraReg} and {@code CleanName}, as a Channel registers a function: a blank
     * name is AST__ITFNI, negative numbers of coordinates other than AST__ANY are AST__BADNI and
     * AST__BADNO, a name already registered is AST__MRITF unless the registration is the same, and
     * white space in a name is ignored. The process-wide table refuses a name held by another
     * Transformer in use in the same way.
     */
    @Test
    void registryChecksAsCDoes() {
        Channel ch = reader("");
        Doubler t = new Doubler();
        AstException e = assertThrows(AstException.class, () -> ch.registerIntra(" \t ", 1, 1, t));
        assertEquals(AstException.AST__ITFNI, e.getStatus());
        e = assertThrows(AstException.class, () -> ch.registerIntra("double", -2, 1, t));
        assertEquals(AstException.AST__BADNI, e.getStatus());
        e = assertThrows(AstException.class, () -> ch.registerIntra("double", 1, -3, t));
        assertEquals(AstException.AST__BADNO, e.getStatus());
        ch.registerIntra("any", IntraRegistry.AST__ANY, IntraRegistry.AST__ANY, t);
        ch.registerIntra("double", 1, 1, t);
        ch.registerIntra("double", 1, 1, t);
        e = assertThrows(AstException.class, () -> ch.registerIntra("double", 1, 1, new Doubler()));
        assertEquals(AstException.AST__MRITF, e.getStatus());
        e = assertThrows(AstException.class, () -> ch.registerIntra("double", 1, 2, t));
        assertEquals(AstException.AST__MRITF, e.getStatus());
        e = assertThrows(AstException.class, () -> ch.registerIntra(" dou ble ", 1, 1, new NoFwd()));
        assertEquals(AstException.AST__MRITF, e.getStatus());

        String name = IntraMapTest.class.getName() + "#collision";
        assertEquals(name, IntraRegistry.registerMade(name, 1, 1, t).name);
        assertEquals(name, IntraRegistry.registerMade(name, 1, 1, t).name);
        e = assertThrows(AstException.class, () -> IntraRegistry.registerMade(name, 2, 2, t));
        assertEquals(AstException.AST__MRITF, e.getStatus());
    }

    /**
     * An IntraMap is written as C writes it, by name, and reads back in the same process with the
     * Transformer of the IntraMap that was written, while that is in use.
     */
    @Test
    void aSameProcessReadFindsTheTransformer() throws IOException {
        Doubler t = new Doubler();
        IntraMap dbl = new IntraMap(t);
        dbl.setC("IntraFlag", "v1");
        String text = dump(dbl);
        assertTrue(text.contains("Iflag = \"v1\""), text);
        IntraMap back = assertInstanceOf(IntraMap.class, readBack(text));
        assertSame(t, back.getTransformer());
        assertTrue(back.astEqual(dbl));
        assertEquals(text, dump(back));
        assertArrayEquals(new double[] {3.0}, back.tran1(1, new double[] {1.5}, true));
    }

    /**
     * An IntraMap read where its function name stands for no Transformer, as in another process
     * (here a name no IntraMap was made with), is AST__URITF, as C's loader reports an
     * unregistered function, unless the Channel reading has a Transformer registered under it.
     */
    @Test
    void aReadInANewProcessNeedsTheTransformationRegistered() throws IOException {
        Doubler t = new Doubler();
        IntraMap dbl = new IntraMap(t);
        String gone = "org.example.Gone#1f2e3d4c";
        String text = renamed(dump(dbl), dbl, gone);
        AstException e = assertThrows(AstException.class, () -> readBack(text));
        assertEquals(AstException.AST__URITF, e.getStatus());
        assertEquals(
                "astLoadIntraMap(Channel): An IntraMap was read which uses an unknown transformation function.\n"
                        + "This is a private extension to the AST library: to handle it, you must obtain the source "
                        + "code from its author.\n"
                        + "You can then register it with AST in your software by calling astIntraReg (see SUN/211).\n"
                        + " \n"
                        + "   Function name:   \"" + gone + "\".\n"
                        + "   Purpose:         \"Double the value\".\n"
                        + "   Author:          \"AST test suite\".\n"
                        + "   Contact address: \"starlink-ast\".\n"
                        + " \n"
                        + "Error while reading a IntraMap from a Channel.",
                e.getMsgText());

        Channel ch = reader(text);
        ch.registerIntra(gone, 1, 1, t);
        IntraMap back = assertInstanceOf(IntraMap.class, ch.read());
        assertEquals(gone, back.getFname());
        assertEquals(text, dump(back));
    }

    /**
     * A registration made with a Channel serves IntraMaps nested inside other Mappings, and one
     * without the function needed is AST__URITF.
     */
    @Test
    void aNestedIntraMapReadsByName() throws IOException {
        Doubler t = new Doubler();
        IntraMap im = new IntraMap(t);
        CmpMap cmp = new CmpMap(new ZoomMap(1, 3.0), im, true);
        String text = renamed(dump(cmp), im, "double");

        Channel ch = reader(text);
        ch.registerIntra("nofwd", 1, 1, new NoFwd());
        AstException e = assertThrows(AstException.class, ch::read);
        assertEquals(AstException.AST__URITF, e.getStatus());

        ch = reader(text);
        ch.registerIntra("double", 1, 1, t);
        Mapping back = (Mapping) ch.read();
        assertArrayEquals(new double[] {9.0}, back.tran1(1, new double[] {1.5}, true));
        assertEquals(text, dump(back));
    }

    /**
     * intramap.c {@code Equal}: the same function and an IntraFlag set on both and equal, so two
     * IntraMaps without one are not equal.
     */
    @Test
    void equalityNeedsAnIntraFlagOnBoth() {
        IntraMap a = new IntraMap(new Doubler());
        IntraMap b = (IntraMap) a.copy();
        assertFalse(a.astEqual(b));
        a.setC("IntraFlag", "x");
        assertFalse(a.astEqual(b));
        b.setC("IntraFlag", "x");
        assertTrue(a.astEqual(b));
        b.invert();
        assertFalse(a.astEqual(b));
        IntraMap c = new IntraMap(new Doubler());
        c.setC("IntraFlag", "x");
        assertFalse(a.astEqual(c));
    }

    /** IntraFlag get, set, test and clear (intramap.c); a shared IntraMap refuses a change. */
    @Test
    void intraFlagSetTestClear() {
        IntraMap m = new IntraMap(new Doubler());
        assertFalse(m.test("IntraFlag"));
        assertEquals("", m.getC("IntraFlag"));
        m.set("IntraFlag=abc");
        assertTrue(m.test("IntraFlag"));
        assertEquals("abc", m.getC("IntraFlag"));
        m.clear("IntraFlag");
        assertFalse(m.test("IntraFlag"));
        new CmpMap(m, new UnitMap(1), true);
        AstException e = assertThrows(AstException.class, () -> m.setC("IntraFlag", "x"));
        assertEquals(AstException.AST__IMMUT, e.getStatus());
        e = assertThrows(AstException.class, () -> m.clear("IntraFlag"));
        assertEquals(AstException.AST__IMMUT, e.getStatus());
    }

    /**
     * An error from the transformation is reported as one signalled by the named function, with
     * jniast's status AST__ITFER (jni/IntraMap.c {@code tranWrap}) and the Transformer's exception
     * as its cause.
     */
    @Test
    void transformationErrorsNameTheFunction() {
        IllegalStateException failure = new IllegalStateException("it failed");
        IntraMap m = new IntraMap(new Failing(failure), 2, 2);
        AstException e =
                assertThrows(AstException.class, () -> m.tran2(1, new double[] {1.0}, new double[] {2.0}, true));
        assertEquals(AstException.AST__ITFER, e.getStatus());
        assertEquals(
                "astTransform(IntraMap): Error signalled by \"" + m.getFname() + "\" transformation function.",
                e.getMsgText());
        assertSame(failure, e.getCause());
    }

    // ------------------------------------------------------------------
    // Review Focus 3: a Transformer that fails
    // ------------------------------------------------------------------

    /**
     * A Transformer that throws, or returns null, too few arrays, a null array or an array too
     * short, gives AST__ITFER, whether the IntraMap is applied alone or inside a CmpMap; arrays
     * longer than needed are accepted, as jniast's {@code jniastCheckArrayLength} accepts them.
     */
    @Test
    void failingTransformersGiveItfer() {
        Transformer[] failing = {
            new Failing(new IllegalArgumentException("bad")),
            new Failing(new Exception("checked")),
            new Failing(new IOException("io")),
            new Failing(() -> null),
            new Failing(() -> new double[][] {{1, 2}}),
            new Failing(() -> new double[][] {{1, 2}, null}),
            new Failing(() -> new double[][] {{1, 2}, {3}}),
        };
        double[] x = {1, 2};
        double[] y = {3, 4};
        for (Transformer t : failing) {
            IntraMap m = new IntraMap(t, 2, 2);
            AstException e = assertThrows(AstException.class, () -> m.tran2(2, x, y, true));
            assertEquals(AstException.AST__ITFER, e.getStatus());
            assertNotNull(e.getCause());
            CmpMap cmp = new CmpMap(new ShiftMap(new double[] {1, 1}), m, true);
            e = assertThrows(AstException.class, () -> cmp.tran2(2, x, y, true));
            assertEquals(AstException.AST__ITFER, e.getStatus());
        }
        IntraMap longer = new IntraMap(new Failing(() -> new double[][] {{5, 6, 7}, {8, 9, 10}, {0}}), 2, 2);
        double[][] out = longer.tran2(2, x, y, true);
        assertArrayEquals(new double[] {5, 6}, out[0]);
        assertArrayEquals(new double[] {8, 9}, out[1]);
    }

    /**
     * A read calls no method of the Transformer: its flags and strings are those registered, so a
     * Transformer whose methods throw once the IntraMap is made does not make the read throw.
     */
    @Test
    void aReadCallsNoTransformerMethod() throws IOException {
        int[] calls = new int[1];
        Failing touchy = new Failing(() -> null) {
            @Override
            public boolean hasForward() {
                if (calls[0]++ > 0) {
                    throw new IllegalStateException("called again");
                }
                return true;
            }

            @Override
            public boolean canTransformCoords(int nin, int nout) {
                if (calls[0] > 0) {
                    throw new IllegalStateException("called again");
                }
                return true;
            }
        };
        IntraMap m = new IntraMap(touchy, 1, 1);
        Mapping back = (Mapping) readBack(dump(new CmpMap(new UnitMap(1), m, true)));
        assertEquals(1, back.getNin());
        assertEquals(1, calls[0]);
    }

    /**
     * Under the NaN protocol a bad input reaches the Transformer as AST__BAD, as C passes it, never
     * as NaN.
     */
    @Test
    void badInputsReachTheTransformerAsBad() {
        Failing marksBad = new Failing(() -> null) {
            @Override
            public double[][] tranP(int npoint, int ncoordIn, double[][] in, boolean forward, int ncoordOut) {
                double[] out = new double[npoint];
                for (int i = 0; i < npoint; i++) {
                    out[i] = in[0][i] == BAD ? 7.0 : in[0][i];
                }
                return new double[][] {out};
            }
        };
        IntraMap m = new IntraMap(marksBad, 1, 1);
        CmpMap cmp = new CmpMap(new ZoomMap(1, 1.0), m, true);
        double[][] out = cmp.transformNanProtocol(new double[][] {{BAD, 2.0}}, 2, true);
        assertArrayEquals(new double[] {7.0, 2.0}, out[0]);
    }

    /** Adds 1 to each coordinate, giving NaN above 5 and positive infinity below -5. */
    private static final class NanAboveFive extends Failing {
        NanAboveFive() {
            super(() -> null);
        }

        @Override
        public double[][] tranP(int npoint, int ncoordIn, double[][] in, boolean forward, int ncoordOut) {
            double[][] out = new double[ncoordOut][npoint];
            for (int c = 0; c < ncoordOut; c++) {
                for (int i = 0; i < npoint; i++) {
                    double x = in[c][i];
                    out[c][i] = x > 5.0 ? Double.NaN : x < -5.0 ? Double.POSITIVE_INFINITY : x + 1.0;
                }
            }
            return out;
        }
    }

    /**
     * A NaN the Transformer returns becomes AST__BAD, as the Mapping class requires of every
     * transformation, through each public transformation method and the package's own; an
     * infinite value is returned as it is, as other Mappings return one.
     */
    @Test
    void nanResultsBecomeBad() {
        double inf = Double.POSITIVE_INFINITY;
        IntraMap m1 = new IntraMap(new NanAboveFive(), 1, 1);
        assertArrayEquals(new double[] {2.0, BAD, inf}, m1.tran1(3, new double[] {1.0, 6.0, -6.0}, true));
        assertArrayEquals(new double[] {BAD, 3.0}, m1.tran1(2, new double[] {7.0, 2.0}, false));
        assertArrayEquals(new double[] {BAD, 2.0}, m1.transform(new double[][] {{9.0, 1.0}}, 2, true)[0]);

        IntraMap m2 = new IntraMap(new NanAboveFive(), 2, 2);
        assertArrayEquals(
                new double[] {2.0, BAD, BAD, 4.0}, m2.tranN(2, 2, new double[] {1.0, 8.0, 6.0, 3.0}, true, 2));
        double[][] p = m2.tranP(2, 2, new double[][] {{1.0, 8.0}, {6.0, 3.0}}, true, 2);
        assertArrayEquals(new double[] {2.0, BAD}, p[0]);
        assertArrayEquals(new double[] {BAD, 4.0}, p[1]);
        double[][] t2 = m2.tran2(2, new double[] {1.0, 8.0}, new double[] {6.0, 3.0}, true);
        assertArrayEquals(new double[] {2.0, BAD}, t2[0]);
        assertArrayEquals(new double[] {BAD, 4.0}, t2[1]);

        // Within the NaN protocol a bad result is NaN, and the protocol returns it as AST__BAD.
        double[][] nan = new double[2][2];
        m2.transformIntoNan(new double[][] {{1.0, 8.0}, {6.0, 3.0}}, 2, true, nan);
        assertArrayEquals(new double[] {2.0, Double.NaN}, nan[0]);
        assertArrayEquals(new double[] {Double.NaN, 4.0}, nan[1]);
        double[][] protocol = m2.transformNanProtocol(new double[][] {{1.0, 8.0}, {6.0, 3.0}}, 2, true);
        assertArrayEquals(new double[] {2.0, BAD}, protocol[0]);
        assertArrayEquals(new double[] {BAD, 4.0}, protocol[1]);
    }

    // ------------------------------------------------------------------
    // jniast's tests
    // ------------------------------------------------------------------

    /**
     * jniast's AstTest {@code testMapping} (AstTest.java:595-626), less the XmlChan part, a class
     * this library lacks: an IntraMap written to a Channel and to a FitsChan reads back and
     * transforms as before, and so does the original.
     */
    @Test
    void jniastRoundTrip() throws IOException {
        Mapping imap = new IntraMap(new Stretch(2.0, 4.0));
        double[] xina = {1, 2, 3};
        double[] yina = {100, 200, 300};
        double[][] resulta = imap.tran2(3, xina, yina, true);
        assertArrayEquals(new double[] {2, 4, 6}, resulta[0]);
        assertArrayEquals(new double[] {400, 800, 1200}, resulta[1]);

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        assertEquals(1, new Channel(null, bout).write(imap));
        Mapping jmap = (Mapping) readBack(new String(bout.toByteArray(), StandardCharsets.UTF_8));
        double[][] resultb = jmap.tran2(3, xina, yina, true);
        assertArrayEquals(resulta[0], resultb[0]);
        assertArrayEquals(resulta[1], resultb[1]);

        FitsChan fc = new FitsChan();
        assertEquals(1, fc.write(jmap));
        fc.setCard(1);
        Mapping kmap = (Mapping) fc.read();
        double[][] resultc = kmap.tran2(3, xina, yina, true);
        assertArrayEquals(resulta[0], resultc[0]);
        assertArrayEquals(resulta[1], resultc[1]);

        double[][] resulte = imap.tran2(3, xina, yina, true);
        assertArrayEquals(resulta[0], resulte[0]);
        assertArrayEquals(resulta[1], resulte[1]);
    }

    /**
     * jniast's ThreadTest {@code testIntraMap} (ThreadTest.java:29-48): an IntraMap made on one
     * thread transforms on another.
     */
    @Test
    void jniastIntraMapOnAnotherThread() throws InterruptedException {
        double z = 3.0;
        IntraMap map = new IntraMap(new Stretch(z, z));
        int np = 2;
        double[] xin = {1, 4};
        double[] yin = {8, 2};
        double[] xout = new double[np];
        double[] yout = new double[np];
        Thread thread = new Thread(
                () -> {
                    double[][] out = map.tran2(np, xin, yin, true);
                    System.arraycopy(out[0], 0, xout, 0, np);
                    System.arraycopy(out[1], 0, yout, 0, np);
                },
                "IntraThread");
        thread.start();
        thread.join();
        assertArrayEquals(new double[] {xin[0] * z, xin[1] * z}, xout);
        assertArrayEquals(new double[] {yin[0] * z, yin[1] * z}, yout);
    }

    // ------------------------------------------------------------------
    // Writing and reading by name
    // ------------------------------------------------------------------

    /**
     * An IntraMap inside a CmpMap reads back in the same process, and two IntraMaps of one
     * Transformer still simplify to a UnitMap after the round trip.
     */
    @Test
    void aRoundTripInsideACmpMap() throws IOException {
        IntraMap im = new IntraMap(new Stretch(2.0, 4.0));
        IntraMap inv = (IntraMap) im.copy();
        inv.invert();
        CmpMap cmp = new CmpMap(new CmpMap(new ZoomMap(2, 3.0), im, true), inv, true);
        String text = dump(cmp);
        assertFalse(text.contains("Iflag"), text);
        Mapping back = (Mapping) readBack(text);
        double[][] out = back.tran2(1, new double[] {1}, new double[] {2}, true);
        assertArrayEquals(new double[] {3}, out[0]);
        assertArrayEquals(new double[] {6}, out[1]);
        assertEquals(text, dump(back));
        assertInstanceOf(ZoomMap.class, back.simplify());
    }

    /**
     * Channel and FitsChan writes call {@code channelize} and {@code unChannelize}, which do
     * nothing for an IntraMap, so an IntraMap whose Transformer is not Serializable is written.
     */
    @Test
    void writingNeedsNoSerializableTransformer() throws IOException {
        IntraMap m = new IntraMap(new Doubler());
        String before = dump(m);
        m.channelize();
        m.unChannelize();
        assertEquals(before, dump(m));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        assertEquals(1, new Channel(null, bout).write(m));
        assertEquals(before, new String(bout.toByteArray(), StandardCharsets.UTF_8));
        FitsChan fc = new FitsChan();
        assertEquals(1, fc.write(m));
        fc.setCard(1);
        assertArrayEquals(new double[] {3.0}, ((Mapping) fc.read()).tran1(1, new double[] {1.5}, true));
    }

    /**
     * An IntraFlag value is read as the string it is, never as a serialised object: a dump that
     * jniast wrote with a serialised Transformer in IntraFlag reads back, by name, with that text
     * as its IntraFlag, or is AST__URITF where the name stands for no Transformer.
     */
    @Test
    void anIntraFlagIsOnlyAString() throws IOException {
        Doubler t = new Doubler();
        IntraMap m = new IntraMap(t);
        String blob = "s2OAFAAAAAAAAAA+/AAA";
        m.setC("IntraFlag", blob);
        String text = dump(m);
        IntraMap back = assertInstanceOf(IntraMap.class, readBack(text));
        assertEquals(blob, back.getC("IntraFlag"));
        assertSame(t, back.getTransformer());
        AstException e = assertThrows(AstException.class, () -> readBack(renamed(text, m, "unknown")));
        assertEquals(AstException.AST__URITF, e.getStatus());
    }

    /**
     * C's {@code astLoadIntraMap} compares the numbers of coordinates of the IntraMap read, as its
     * Invert attribute leaves them, with those registered (probe {@code intramap_load_probe.c}): an
     * IntraMap of a function with two inputs and one output reads back as made, and inverted is
     * AST__BADNI, whether the function is registered with the Channel or by the IntraMap made in
     * this process.
     */
    @Test
    void anInvertedNonSquareIntraMapCannotBeRead() throws IOException {
        Sum2 t = new Sum2();
        IntraMap m = new IntraMap(t, 2, 1);
        for (String name : new String[] {"as_made", "inverted"}) {
            String[] parts = LOAD_PROBE.get(name).split("-- read\n", -1);
            String cdump = parts[0];
            assertEquals(cdump, renamed(dump(m), m, "sum2"), name);
            Channel ch = reader(cdump);
            ch.registerIntra("sum2", 2, 1, t);
            String result;
            try {
                Mapping back = (Mapping) ch.read();
                double[] out = back.tranP(1, 2, new double[][] {{1.5}, {2.25}}, true, 1)[0];
                result =
                        "Nin " + back.getNin() + " forward of (1.5, 2.25): " + CFormat.format("%.17g", out[0]) + " 0\n";
            } catch (AstException e) {
                StringBuilder sb = new StringBuilder();
                for (String line : ProbeOutput.trimLines(e.getMsgText()).split("\n", -1)) {
                    sb.append("!  ").append(line).append('\n');
                }
                result = sb + "status " + e.getStatus() + "\n";
            }
            StringBuilder want = new StringBuilder();
            for (String line : parts[1].split("\n", -1)) {
                if (!line.isEmpty() && !line.startsWith("!! ")) {
                    want.append(line).append('\n');
                }
            }
            assertEquals(want.toString(), result, name);
            AstException same = null;
            try {
                readBack(dump(m));
            } catch (AstException e) {
                same = e;
            }
            assertEquals("inverted".equals(name), same != null, name);
            if (same != null) {
                assertEquals(AstException.AST__BADNI, same.getStatus());
            }
            m.invert();
        }
    }

    /**
     * When the name made from a Transformer's identity hash code is held by another Transformer in
     * use, as two Transformers' hash codes may coincide, the IntraMap takes the next free name,
     * with {@code #1}, {@code #2}, ... added, and is written and read back under it.
     */
    @Test
    void aSharedIdentityHashGivesAnotherName() throws IOException {
        Doubler t = new Doubler();
        String base = Doubler.class.getName() + "#" + Integer.toHexString(System.identityHashCode(t));
        Doubler other1 = new Doubler();
        Doubler other2 = new Doubler();
        IntraRegistry.registerMade(base, 1, 1, other1);
        IntraRegistry.registerMade(base + "#1", 1, 1, other2);
        IntraMap m = new IntraMap(t);
        assertEquals(base + "#2", m.getFname());
        IntraMap again = new IntraMap(t);
        assertEquals(base + "#2", again.getFname());
        IntraMap back = assertInstanceOf(IntraMap.class, readBack(dump(m)));
        assertSame(t, back.getTransformer());
        assertEquals(base + "#2", back.getFname());
        assertSame(other1, IntraRegistry.lookup(base).trans);
        assertSame(other2, IntraRegistry.lookup(base + "#1").trans);
    }

    /**
     * Every IntraMap of one Transformer has one name: after a collision has given it {@code #1},
     * and the Transformer holding the base name has been garbage-collected, a new IntraMap of it
     * still takes {@code #1}, not the free base name.
     */
    @Test
    void oneTransformerKeepsItsNameAfterACollisionLapses() {
        Doubler t = new Doubler();
        String base = Doubler.class.getName() + "#" + Integer.toHexString(System.identityHashCode(t));
        Doubler other = new Doubler();
        IntraRegistry.registerMade(base, 1, 1, other);
        IntraMap m = new IntraMap(t);
        assertEquals(base + "#1", m.getFname());
        IntraRegistry.lapse(base);
        assertNull(IntraRegistry.lookup(base));
        IntraMap again = new IntraMap(t);
        assertEquals(base + "#1", again.getFname());
        assertSame(t, IntraRegistry.lookup(base + "#1").trans);
    }

    /** jniast's {@code copy}: a copy shares the Transformer and the function name. */
    @Test
    void aCopySharesTheTransformer() {
        IntraMap m = new IntraMap(new Stretch(2.0, 4.0));
        AstObject c = m.copy();
        IntraMap copy = assertInstanceOf(IntraMap.class, c);
        assertNotSame(m, copy);
        assertSame(m.getTransformer(), copy.getTransformer());
        assertEquals(m.getFname(), copy.getFname());
        String fname = Stretch.class.getName() + "#" + Integer.toHexString(System.identityHashCode(m.getTransformer()));
        assertEquals(fname, m.getFname());
    }

    // ------------------------------------------------------------------
    // Simplification (intramap.c MapMerge)
    // ------------------------------------------------------------------

    /**
     * IntraMaps of one function in series cancel to a UnitMap when they keep the number of
     * coordinates and the function's SIMPFI (forward then inverse) or SIMPIF (inverse then
     * forward) flag is set and their IntraFlag values agree (unset reading as empty); not in
     * parallel, not in the same direction, not for different functions.
     */
    @Test
    void intraMapsInSeriesMergeAsCDoes() {
        Doubler t = new Doubler();
        IntraMap a = new IntraMap(t);
        IntraMap b = new IntraMap(t);
        b.invert();
        assertInstanceOf(UnitMap.class, new CmpMap(a, b, true).simplify());
        assertInstanceOf(UnitMap.class, new CmpMap(b, a, true).simplify());
        assertInstanceOf(CmpMap.class, new CmpMap(a, a, true).simplify());
        assertInstanceOf(CmpMap.class, new CmpMap(a, b, false).simplify());

        IntraMap other = new IntraMap(new Doubler());
        other.invert();
        assertInstanceOf(CmpMap.class, new CmpMap(a, other, true).simplify());

        IntraMap flagged = new IntraMap(t);
        flagged.invert();
        flagged.setC("IntraFlag", "f");
        assertInstanceOf(CmpMap.class, new CmpMap(a, flagged, true).simplify());
        IntraMap flagged2 = new IntraMap(t);
        flagged2.setC("IntraFlag", "f");
        assertInstanceOf(UnitMap.class, new CmpMap(flagged2, flagged, true).simplify());

        Failing none = new Failing(() -> null);
        IntraMap n1 = new IntraMap(none, 1, 1);
        IntraMap n2 = new IntraMap(none, 1, 1);
        n2.invert();
        assertInstanceOf(CmpMap.class, new CmpMap(n1, n2, true).simplify());
    }
}
