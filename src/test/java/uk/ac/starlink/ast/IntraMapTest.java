package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_intramap.rs} (intramap.c), less the three tests that
 * compare with the C probe, which {@link IntraMapOracleTest} translates, with jniast's behaviour
 * for the Java API: the Transformer classes, serialisation of a Transformer in jniast's printable
 * encoding through a Channel and a FitsChan, at the top level and inside a CmpMap, and the failure
 * of a write of a Transformer that is not Serializable; the errors of a Transformer that throws or
 * returns arrays of the wrong shape; the registration of transformation functions with a Channel,
 * C's {@code astIntraReg}; and simplification of IntraMaps in series (intramap.c {@code MapMerge}).
 */
class IntraMapTest {

    private static final double BAD = AstObject.AST__BAD;

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

    /** jniast's {@code TestTransformer2} (AstTest.java): a Serializable two-dimensional stretch. */
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
     * The text a default Channel writes for {@code obj}, without the {@code channelize} call of
     * {@link Channel#write}, which refuses an IntraMap whose Transformer is not Serializable.
     */
    private static String dump(AstObject obj) {
        return Channel.text(obj, true, 0);
    }

    private static Channel reader(String text) {
        return new Channel(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), null);
    }

    private static AstObject readBack(String text) throws IOException {
        return reader(text).read();
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
     * jniast's construction check (jni/IntraMap.c {@code construct}): a Transformer that cannot map
     * the numbers of coordinates given is an IllegalArgumentException. C's {@code astInitIntraMap}
     * checks (AST__BADNI, AST__BADNO) are made by the Transformer here.
     */
    @Test
    void constructionChecksAsJniastDoes() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IntraMap(new Doubler(), 2, 1));
        assertEquals("Transformer will not map 2 -> 1 coordinates", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new IntraMap(new Stretch(1, 1), 1, 1));
        assertThrows(NullPointerException.class, () -> new IntraMap((Transformer) null, 1, 1));
        IntraMap m = new IntraMap(new Failing(() -> null), 3, 2);
        assertEquals(3, m.getNin());
        assertEquals(2, m.getNout());
    }

    /**
     * intramap.c {@code IntraReg} and {@code CleanName}, as a Channel registers a function: a blank
     * name is AST__ITFNI, negative numbers of coordinates other than AST__ANY are AST__BADNI and
     * AST__BADNO, a name already registered is AST__MRITF unless the registration is the same, and
     * white space in a name is ignored.
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
    }

    /**
     * An IntraMap whose Transformer is not Serializable is written as C writes it, by name, and
     * reads back only through a Channel that has the function registered under that name;
     * otherwise the read is AST__URITF, as C's loader reports an unregistered function.
     */
    @Test
    void readingByNameNeedsTheTransformationRegistered() throws IOException {
        Doubler t = new Doubler();
        IntraMap dbl = new IntraMap(t);
        dbl.setC("IntraFlag", "v1");
        String text = dump(dbl);
        AstException e = assertThrows(AstException.class, () -> readBack(text));
        assertEquals(AstException.AST__URITF, e.getStatus());
        assertTrue(e.getMessage().contains("Function name:   \"" + dbl.getFname() + "\"."), e.getMessage());

        Channel ch = reader(text);
        ch.registerIntra(dbl.getFname(), 1, 1, t);
        IntraMap back = assertInstanceOf(IntraMap.class, ch.read());
        assertTrue(back.astEqual(dbl));
        assertEquals(text, dump(back));
        assertArrayEquals(new double[] {3.0}, back.tran1(1, new double[] {1.5}, true));
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
        String text = dump(cmp);

        Channel ch = reader(text);
        ch.registerIntra("nofwd", 1, 1, new NoFwd());
        AstException e = assertThrows(AstException.class, ch::read);
        assertEquals(AstException.AST__URITF, e.getStatus());

        ch = reader(text);
        ch.registerIntra(im.getFname(), 1, 1, t);
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

    // ------------------------------------------------------------------
    // Serialisation of the Transformer
    // ------------------------------------------------------------------

    /**
     * jniast's AstTest {@code testMapping}: an IntraMap of a Serializable Transformer written to a
     * Channel and to a FitsChan reads back and transforms as before, and so does the original.
     */
    @Test
    void aSerializableTransformerRoundTrips() throws IOException {
        IntraMap imap = new IntraMap(new Stretch(2.0, 4.0));
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
        assertEquals(imap.getFname(), ((IntraMap) jmap).getFname());

        FitsChan fc = new FitsChan();
        assertEquals(1, fc.write(jmap));
        fc.setCard(1);
        Mapping kmap = (Mapping) fc.read();
        double[][] resultc = kmap.tran2(3, xina, yina, true);
        assertArrayEquals(resulta[0], resultc[0]);
        assertArrayEquals(resulta[1], resultc[1]);
        assertArrayEquals(xina, kmap.tran2(3, resultc[0], resultc[1], false)[0], 0.0);

        double[][] resulte = imap.tran2(3, xina, yina, true);
        assertArrayEquals(resulta[0], resulte[0]);
        assertArrayEquals(resulta[1], resulte[1]);
        assertEquals(dump(imap), dump(jmap));
    }

    /**
     * An IntraMap of a Serializable Transformer inside a CmpMap reads back through a Channel
     * without any registration, with its Transformer decoded from the IntraFlag value, and two
     * IntraMaps of one Transformer still simplify to a UnitMap after the round trip.
     */
    @Test
    void aSerializableTransformerRoundTripsInsideACmpMap() throws IOException {
        IntraMap im = new IntraMap(new Stretch(2.0, 4.0));
        IntraMap inv = (IntraMap) im.copy();
        inv.invert();
        CmpMap cmp = new CmpMap(new CmpMap(new ZoomMap(2, 3.0), im, true), inv, true);
        String text = dump(cmp);
        assertTrue(text.contains("Iflag = \""), text);
        Mapping back = (Mapping) readBack(text);
        double[][] out = back.tran2(1, new double[] {1}, new double[] {2}, true);
        assertArrayEquals(new double[] {3}, out[0]);
        assertArrayEquals(new double[] {6}, out[1]);
        assertEquals(text, dump(back));
        Mapping s = back.simplify();
        assertInstanceOf(ZoomMap.class, s);
    }

    /**
     * jniast's Channel and FitsChan writes call {@code channelize}, which throws
     * NotSerializableException for an IntraMap whose Transformer is not Serializable, and write
     * nothing.
     */
    @Test
    void writingANonSerializableTransformerFails() {
        IntraMap m = new IntraMap(new Doubler());
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        NotSerializableException e =
                assertThrows(NotSerializableException.class, () -> new Channel(null, bout).write(m));
        assertEquals(Doubler.class.getName(), e.getMessage());
        assertEquals(0, bout.size());
        assertThrows(NotSerializableException.class, m::channelize);

        FitsChan fc = new FitsChan();
        assertThrows(NotSerializableException.class, () -> fc.write(m));
        assertEquals(0, fc.getNcard());
    }

    /**
     * Inside another Object, an IntraMap whose Transformer is not Serializable is written as C
     * writes it, as jniast channelizes only the Object written, and reads back as AST__URITF
     * unless its function is registered.
     */
    @Test
    void aNestedNonSerializableTransformerIsWrittenByName() throws IOException {
        IntraMap m = new IntraMap(new Doubler());
        String text = dump(new CmpMap(new UnitMap(1), m, true));
        assertFalse(text.contains("Iflag"), text);
        AstException e = assertThrows(AstException.class, () -> readBack(text));
        assertEquals(AstException.AST__URITF, e.getStatus());
    }

    /**
     * jniast's printable encoding (IntraMap.java {@code encodePrintable}): six bits at a time from
     * the lowest bits of each byte, in the alphabet A-Z, a-z, 0-9, + and /. The Java serialisation
     * magic number 0xACED and a zero byte give "s2OA". The decoding ignores the zero characters
     * jniast's encoding leaves at the end of the IntraFlag value.
     */
    @Test
    void thePrintableEncodingIsJniasts() throws IOException {
        assertEquals("s2OA", IntraMap.encodePrintable(new byte[] {(byte) 0xAC, (byte) 0xED, 0}));
        byte[] plain = {(byte) 0xAC, (byte) 0xED, 0, 5, 0x7f, (byte) 0x80, 1};
        byte[] back = IntraMap.decodePrintable(IntraMap.encodePrintable(plain));
        for (int i = 0; i < plain.length; i++) {
            assertEquals(plain[i], back[i]);
        }

        IntraMap im = new IntraMap(new Stretch(2.0, 4.0));
        String text = dump(im);
        int at = text.indexOf("Iflag = \"") + "Iflag = \"".length();
        int end = text.indexOf('"', at);
        String padded = text.substring(0, end) + "\u0000\u0000\u0000\u0000" + text.substring(end);
        Mapping back2 = (Mapping) readBack(padded);
        assertArrayEquals(new double[] {4}, back2.tran2(1, new double[] {2}, new double[] {1}, true)[0]);
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
        String fname = Stretch.class.getName() + "#"
                + Integer.toHexString(m.getTransformer().hashCode());
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
