package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Native dumps from the C test fixtures with one value made corrupt: a count or an index that the
 * rest of the dump does not support, or a number no {@code int} holds. Each read raises an {@link
 * AstException} with a status, never another exception, either when it is read or later when the
 * Object is used.
 *
 * <p>Where C's loader checks the value, the status and message are C's (checked against the pinned C
 * library). Where it does not, C reads the dump and then reads or writes beyond its arrays (the
 * pinned library crashes on {@code Nframe}, {@code Base} and SphMap {@code Nin}, and reports
 * corrupted memory for {@code Nod3}); this port reports AST__BADIN when the dump is read.
 */
class CorruptNativeTest {

    /**
     * The fixture at {@code rel} with line {@code lineNo} (from 1), which must name {@code key},
     * given the value {@code value}.
     */
    private static String corrupt(String rel, int lineNo, String key, String value) {
        List<String> lines = CValues.lines(Fixtures.read(rel));
        String line = lines.get(lineNo - 1);
        assertTrue(line.trim().startsWith(key + " ="), rel + " line " + lineNo + " is " + line);
        lines.set(lineNo - 1, line.substring(0, line.indexOf('=') + 1) + " " + value);
        return String.join("\n", lines) + "\n";
    }

    /** The error of reading {@code text} and using what is read, which must be an AstException. */
    // Any exception other than an AstException is caught to report its type, as one C cannot raise.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static AstException readError(String text) {
        try {
            AstObject obj = ChannelText.reader(text).read();
            use(obj);
        } catch (AstException e) {
            return e;
        } catch (IOException | RuntimeException e) {
            throw new AssertionError("the read threw " + e.getClass().getName() + ", which C cannot", e);
        }
        return fail("the corrupt dump was read and used without an error");
    }

    /** Transforms a point through {@code obj}, if it is a Mapping, and writes it. */
    private static void use(AstObject obj) {
        if (obj instanceof Mapping) {
            Mapping map = (Mapping) obj;
            int nin = map.getNin();
            double[][] in = new double[nin][1];
            for (int i = 0; i < nin; i++) {
                in[i][0] = 0.1 * (i + 1);
            }
            map.tranP(1, nin, in, true, map.getNout());
        }
        ChannelText.dump(obj);
    }

    private static void assertBadIn(AstException e, String message) {
        assertEquals(AstException.AST__BADIN, e.getStatus(), e.getMessage());
        assertTrue(e.getMsgText().contains(message), e.getMsgText());
    }

    @Test
    void aFrameCountBelowTheCurrentFrame() {
        AstException e = readError(corrupt("wcsconv/framesets/date-z-head.ast", 15, "Nframe", "1"));
        assertBadIn(
                e,
                "astLoadFrameSet(FrameSet): The current Frame index (2) is invalid - it should be in the range 1 to 1.");
    }

    @Test
    void aSystemInTheFramePartOfAFrameSet() {
        // The line is the commented System of the FrameSet's Frame part, made active; the pinned C
        // library crashes on it.
        String text = corrupt("wcsconv/framesets/pc-sparse-head.ast", 8, "#   System", "\"FK5\"")
                .replace("#   System = \"FK5\"", "    System = \"FK5\"");
        AstException e = readError(text);
        assertBadIn(e, "astLoadFrameSet(FrameSet): The Frame part of the description holds a value that needs");
    }

    @Test
    void anAxisPermutationOutOfRange() {
        // The pinned C library reads it, and its write then fails with AST__AXIIN.
        AstException e = readError(corrupt("serialisation/degen1.ast", 112, "Axp2", "-1"));
        assertBadIn(e, "The axis permutation is invalid - axis 2 is given axis -1, where each axis from 1 to");
    }

    @Test
    void aBaseFrameIndexNoIntHolds() {
        AstException e = readError(corrupt("wcsconv/framesets/dss-fits-dss.ast", 16, "Base", "99999999999"));
        assertBadIn(e, "astRead(Channel): The value \"base = 99999999999\" cannot be read as an integer.");
    }

    @Test
    void aFrameOnNodeZero() {
        AstException e = readError(corrupt("wcsconv/framesets/timj-ast.ast", 21, "Nod3", "0"));
        assertBadIn(
                e,
                "astLoadFrameSet(FrameSet): The node index of Frame 3 (0) is invalid - it should be in the range 1 to 5.");
    }

    @Test
    void aSphMapWithOneInput() {
        AstException e = readError(corrupt("wcsconv/framesets/ecliptic-tan-head.ast", 98, "Nin", "1"));
        assertBadIn(e, "astLoadSphMap(SphMap): The number of input coordinates (1) is invalid - it should be 3.");
    }

    @Test
    void aPolyMapPowerNoIntHolds() {
        AstException e = readError(corrupt("wcsconv/framesets/lsst-ast.ast", 197, "PF27", "-2147483649"));
        assertBadIn(e, "astRead(Channel): The value \"pf27 = -2147483649\" cannot be read as an integer.");
    }

    @Test
    void aSkyFrameWithOneAxis() {
        AstException e = readError(corrupt("wcsconv/framesets/projp-pv0only-head.ast", 40, "Naxes", "1"));
        // C's loader tests SkyRef on the second axis, which ValidateAxis rejects.
        assertEquals(AstException.AST__AXIIN, e.getStatus(), e.getMessage());
        assertTrue(
                e.getMsgText()
                        .startsWith(
                                "astTestSkyRef(SkyFrame): Axis index (2) invalid - it should be in the range 1 to 1."
                                        + "\nFailed to read the \"frm2\" Object value."),
                e.getMsgText());
    }

    @Test
    void anAxisCountNoIntHolds() {
        AstException e = readError(corrupt("wcsconv/framesets/timeplot-head.ast", 14, "Naxes", "99999999999"));
        assertBadIn(e, "astRead(Channel): The value \"naxes = 99999999999\" cannot be read as an integer.");
    }

    @Test
    void aMatrixMapInputCountNoIntHolds() {
        AstException e = readError(corrupt("wcsconv/framesets/skyref-alt-head.ast", 146, "Nin", "99999999999"));
        assertBadIn(e, "astRead(Channel): The value \"nin = 99999999999\" cannot be read as an integer.");
    }

    @Test
    void aFrameCountThatIsNotANumberInAFitsChan() {
        List<String> lines = CValues.lines(Fixtures.read("wcsconv/inputs/polco.head"));
        assertTrue(lines.get(5).startsWith("NFRAME_A="), lines.get(5));
        lines.set(5, "NFRAME_A= 'abc'");
        FitsChan fc = FitsHeaders.chanFromLines(String.join("\n", lines));
        AstException e = assertInstanceOf(AstException.class, readFitsError(fc));
        // As C reports it (checked against the pinned C library).
        assertBadIn(e, "astRead(FitsChan): The value \"nframe = abc\" cannot be read as an integer.");
        assertTrue(e.getMsgText().endsWith("Error while reading a FrameSet from a FitsChan."), e.getMsgText());
    }

    // Any exception other than an AstException is caught to report its type, as one C cannot raise.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static Throwable readFitsError(FitsChan fc) {
        try {
            use(fc.read());
        } catch (AstException e) {
            return e;
        } catch (RuntimeException e) {
            throw new AssertionError("the read threw " + e.getClass().getName() + ", which C cannot", e);
        }
        return fail("the corrupt header was read and used without an error");
    }
}
