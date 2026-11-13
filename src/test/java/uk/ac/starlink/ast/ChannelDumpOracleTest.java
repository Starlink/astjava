package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.NativeClasses;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_channel_dump_oracle.rs}: Channel dumps against C's
 * (channel.c {@code Dump} and loader): every attribute unset, each set, and values the setters
 * change; each dump read back and dumped again; a hand-written dump holding values the setters
 * change; and each case written with Full = 1, which writes every unset value, less C's unset
 * {@code RefCnt} and {@code Nobj}, counts of pointers to the Object and of Objects of its class,
 * which this library does not have. The dumps are compared line for line with C's.
 *
 * <p>{@code oracle/channel_dump.json} comes from {@code tools/oracle_fixtures/channel_dump_probe.c}.
 * Its MocChan cases are out of scope and are not run: the {@code mocchan} cases ({@link
 * #MOCCHAN_CASES}) need MocChan, and its loads are recognised by the class they hold. For the same
 * reason {@code a_clone_shares_the_sink} and {@code a_channel_read_from_a_dump_has_no_source} are
 * deferred.
 */
class ChannelDumpOracleTest {

    /** The cases of the probe made with a Channel. */
    private static final List<String> CHANNEL_CASES =
            Arrays.asList("channel", "channel_set", "channel_set_defaults", "channel_clamped", "channel_full_negative");

    /** The cases of the probe made with a FitsChan. */
    private static final List<String> FITSCHAN_CASES = Arrays.asList(
            "fitschan",
            "fitschan_cards",
            "fitschan_attrs",
            "fitschan_attrs_default",
            "fitschan_guessed_encoding",
            "fitschan_used",
            "fitschan_source");

    /** The cards the source of the {@code fitschan_source} case gives. */
    private static final List<String> SOURCE = Arrays.asList("A       =                    1", "B       = 'two'");

    /** The cases of the probe made with a MocChan, which is out of scope. */
    private static final List<String> MOCCHAN_CASES =
            Arrays.asList("mocchan", "mocchan_string", "mocchan_json", "mocchan_ident");

    /** The FitsChan a FitsChan case of the probe makes. */
    private static FitsChan fitschan(String name) {
        FitsChan fc = new FitsChan();
        switch (name) {
            case "fitschan":
                break;
            case "fitschan_cards":
                for (String card : new String[] {
                    "INTKEY  =                   42 / an integer",
                    "FLTKEY  =                  1.5 / a float",
                    "STRKEY  = 'hello   '           / a string",
                    "LOGKEY  =                    T",
                }) {
                    fc.putFits(card, false);
                }
                fc.setFits("CPXF", 1.5, -2.25, "complex float", false);
                fc.setFits("CPXI", 3, 4, "", false);
                for (String card : new String[] {
                    "BIGINT  =         123456789012 / 64-bit",
                    "UNDEFKEY=                      / no value",
                    "COMMENT a comment card",
                    "HISTORY   history text   ",
                    "        blank keyword",
                    "LONGSTR = 'abc&'",
                    "CONTINUE  'def'",
                }) {
                    fc.putFits(card, false);
                }
                fc.setCard(3);
                break;
            case "fitschan_attrs":
                fc.set("Encoding=FITS-WCS, FitsDigits=10, FitsRounding=5, AltAxes=IDENT, DefB1950=0,"
                        + " IgnoreBadAlt=1, CDMatrix=1, CarLin=1, SipReplace=0, FitsTol=0.5, PolyTan=1,"
                        + " SipOK=0, Iwc=1, Clean=1, Warnings=BadKeyName, FitsAxisOrder=<copy>, Ident=fc,"
                        + " Skip=0, Full=0");
                break;
            case "fitschan_attrs_default":
                fc.set("Encoding=NATIVE, FitsDigits=17, AltAxes=ALL, DefB1950=1, IgnoreBadAlt=0, CDMatrix=0,"
                        + " CarLin=0, SipReplace=1, FitsTol=0.1, PolyTan=-1, SipOK=1, Iwc=0, Clean=0,"
                        + " Warnings=, Skip=1, Full=-1");
                break;
            case "fitschan_guessed_encoding":
                wcsHeader(fc);
                break;
            case "fitschan_used":
                wcsHeader(fc);
                fc.read();
                break;
            case "fitschan_source":
                return new FitsChan(SOURCE.iterator());
            default:
                throw new IllegalArgumentException("no case " + name);
        }
        return fc;
    }

    /**
     * Puts the header the {@code fitschan_guessed_encoding} and {@code fitschan_used} cases start
     * from: a FITS-WCS header whose CRVAL2 card is retained.
     */
    private static void wcsHeader(FitsChan fc) {
        for (String card : new String[] {
            "NAXIS   =                    2",
            "CTYPE1  = 'RA---TAN'",
            "CTYPE2  = 'DEC--TAN'",
            "CRVAL1  =                 10.0",
            "CRVAL2  =                 20.0",
            "CRPIX1  =                 50.0",
            "CRPIX2  =                 60.0",
            "CDELT1  =               -0.001",
            "CDELT2  =                0.001",
            "OTHER   =                    1",
        }) {
            fc.putFits(card, false);
        }
        fc.clear("Card");
        fc.findFits("CRVAL2", false);
        fc.retainFits();
        fc.clear("Card");
    }

    /** The Channel each Channel or FitsChan case of the probe makes. */
    private static Channel object(String name) {
        if (FITSCHAN_CASES.contains(name)) {
            return fitschan(name);
        }
        Channel ch = new Channel(null, null);
        if ("channel".equals(name)) {
            return ch;
        } else if ("channel_set".equals(name)) {
            ch.setComment(false);
            ch.setFull(1);
            ch.setIndent(5);
            ch.setReportLevel(2);
            ch.setSkip(true);
            ch.setStrict(true);
        } else if ("channel_set_defaults".equals(name)) {
            ch.setComment(true);
            ch.setFull(0);
            ch.setIndent(3);
            ch.setReportLevel(1);
            ch.setSkip(false);
            ch.setStrict(false);
        } else if ("channel_clamped".equals(name)) {
            ch.setComment(true);
            ch.setFull(5);
            ch.setIndent(-2);
        } else if ("channel_full_negative".equals(name)) {
            ch.setFull(-4);
        } else {
            throw new IllegalArgumentException("no case " + name);
        }
        return ch;
    }

    /** Whether a case of the probe needs a class that is out of scope. */
    private static boolean outOfScope(String name) {
        if (CHANNEL_CASES.contains(name) || FITSCHAN_CASES.contains(name)) {
            return false;
        }
        assertTrue(MOCCHAN_CASES.contains(name), "unknown case " + name);
        return true;
    }

    private static List<String> strings(JsonNode lines) {
        List<String> out = new ArrayList<>();
        for (JsonNode l : lines) {
            out.add(l.asText());
        }
        return out;
    }

    private static JsonNode consts() {
        return ProbeData.load("channel_dump");
    }

    @Test
    void dumpsMatchC() {
        int run = 0;
        for (JsonNode c : consts().get("DUMPS")) {
            String name = c.get(0).asText();
            if (outOfScope(name)) {
                continue;
            }
            run++;
            String text = ChannelText.dump(object(name));
            assertEquals(strings(c.get(1)), CValues.lines(text), name + ": dump");
            String again = ChannelText.dump(ChannelText.read(text));
            assertEquals(strings(c.get(2)), CValues.lines(again), name + ": dump read back");
        }
        assertEquals(CHANNEL_CASES.size() + FITSCHAN_CASES.size(), run, "cases run");
    }

    @Test
    void fullDumpsMatchC() throws IOException {
        int run = 0;
        for (JsonNode c : consts().get("FULL_DUMPS")) {
            String name = c.get(0).asText();
            if (outOfScope(name)) {
                continue;
            }
            run++;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Channel ch = new Channel(null, out);
            ch.setFull(1);
            ch.write(object(name));
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            assertEquals(strings(c.get(1)), CValues.lines(text), name);
        }
        assertEquals(CHANNEL_CASES.size() + FITSCHAN_CASES.size(), run, "cases run");
    }

    @Test
    void loadsMatchC() {
        int run = 0;
        for (JsonNode c : consts().get("LOADS")) {
            String name = c.get(0).asText();
            String text = String.join("\n", strings(c.get(1)));
            List<String> missing = NativeClasses.missing(text);
            if (missing.contains("MocChan")) {
                continue;
            }
            run++;
            List<String> dump = strings(c.get(2));
            int status = c.get(3).asInt();
            AstObject obj;
            try {
                obj = ChannelText.read(text);
            } catch (AstException e) {
                assertEquals(status, e.getStatus(), name + ": " + e.getMessage());
                assertTrue(dump.isEmpty(), name + ": C read an object");
                continue;
            }
            assertEquals(0, status, name + ": C reported an error");
            assertEquals(dump, CValues.lines(ChannelText.dump(obj)), name);
        }
        assertEquals(9, run, "cases run");
    }

    /** A dump reads the source, leaving its cards in the FitsChan, and a second dump is the same. */
    @Test
    void aDumpLeavesTheSourceCardsInTheFitschan() {
        FitsChan fc = new FitsChan(SOURCE.iterator());
        String first = ChannelText.dump(fc);
        assertEquals(first, ChannelText.dump(fc));
        assertEquals(2, fc.getNcard());
    }

    @Test
    void aKeymapHoldingAFitschanRoundTrips() {
        FitsChan fc = new FitsChan();
        fc.putFits("A       =                    1", false);
        KeyMap km = new KeyMap();
        km.mapPut0A("fc", fc, null);
        KeyMap back = (KeyMap) ChannelText.read(ChannelText.dump(km));
        FitsChan read = (FitsChan) back.mapGet0A("fc");
        read.clear("Card");
        assertEquals(1, (int) read.getFitsI("A"));
    }
}
