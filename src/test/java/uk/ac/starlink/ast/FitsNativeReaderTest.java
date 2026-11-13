package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of the read-side unit tests of Rust {@code fitschan/enc_native.rs}: the data items a
 * FitsChan gives its Channel reader from cards holding the NATIVE encoding (fitschan.c {@code
 * GetNextData}).
 *
 * <p>The Rust port turns the cards into native text and parses that, so its tests check the text;
 * here the cards give the data items directly, as in C, so the same headers are checked through
 * the items. The keyword classification C makes is tested through {@link
 * FitsNativeReader#isMarker} and {@link FitsNativeReader#isDataKeyword}: a data item's name is
 * the keyword without its sequence characters in lower case, where Rust capitalises it.
 */
class FitsNativeReaderTest {

    private static FitsNativeReader reader(String header) {
        return new FitsNativeReader(FitsCardList.fromHeader(header), 10);
    }

    /**
     * The data items of the first Object, as {@code name=val}: the first read skips to its {@code
     * Begin}, and the reads stop at its {@code End}, as a Channel reads them.
     */
    private static List<String> items(String header) {
        FitsNativeReader r = reader(header);
        List<String> out = new ArrayList<>();
        int depth = 0;
        NativeStream.Datum d = r.next(true, new ArrayList<>());
        while (d != null) {
            out.add(d.name + "=" + d.val);
            if ("begin".equals(d.name)) {
                depth++;
            } else if ("end".equals(d.name) && --depth == 0) {
                break;
            }
            d = r.next(false, new ArrayList<>());
        }
        return out;
    }

    @Test
    void detectsKeywordNativeWhenBegastPresent() {
        NativeStream.Datum d = reader("BEGAST_A= 'FrameSet'           / Set of inter-related coordinate systems\n"
                        + "NFRAME_A=                    2 / Number of Frames in FrameSet\n"
                        + "ENDAST_A= 'FrameSet'           / End of object definition\n"
                        + "END\n")
                .next(true, new ArrayList<>());
        assertNotNull(d);
        assertEquals("begin", d.name);
        assertEquals("FrameSet", d.val);
    }

    @Test
    void rejectsPureWcsHeader() {
        assertNull(reader("SIMPLE  =                    T / file conforms to FITS standard\n"
                        + "NAXIS   =                    2 / number of data axes\n"
                        + "CTYPE1  = 'RA---TAN'           / coord type\n"
                        + "CRPIX1  =                100.0 / reference pixel\n"
                        + "CRVAL1  =                  0.0 / reference value\n"
                        + "END\n")
                .next(true, new ArrayList<>()));
    }

    @Test
    void seqCharsRecogniseUnderscoreAndLetters() {
        assertTrue(FitsNativeReader.isSeqChar('_'));
        assertTrue(FitsNativeReader.isSeqChar('A'));
        assertTrue(FitsNativeReader.isSeqChar('Z'));
        assertFalse(FitsNativeReader.isSeqChar('a'));
        assertFalse(FitsNativeReader.isSeqChar('1'));
        assertFalse(FitsNativeReader.isSeqChar('0'));
    }

    @Test
    void twoCharSeqSuffixExamples() {
        assertTrue(FitsNativeReader.isMarker("ISA_A", "ISA"));
        assertTrue(FitsNativeReader.isMarker("ISAAB", "ISA"));
        assertTrue(FitsNativeReader.isMarker("ISA__", "ISA"));
        assertTrue(FitsNativeReader.isMarker("ISAZZ", "ISA"));
        assertFalse(FitsNativeReader.isMarker("ISAA", "ISA"));
        assertFalse(FitsNativeReader.isMarker("ISAABC", "ISA"));
        assertFalse(FitsNativeReader.isMarker("ISAa_", "ISA"));
        assertFalse(FitsNativeReader.isMarker("ISA0_", "ISA"));
    }

    @Test
    void classifyRecognisesBegast() {
        assertTrue(FitsNativeReader.isMarker("BEGAST_A", "BEGAST"));
        assertTrue(FitsNativeReader.isMarker("BEGAST_Z", "BEGAST"));
        assertTrue(FitsNativeReader.isMarker("BEGASTAA", "BEGAST"));
    }

    @Test
    void classifyRecognisesEndastAndIsa() {
        assertTrue(FitsNativeReader.isMarker("ENDAST_A", "ENDAST"));
        assertTrue(FitsNativeReader.isMarker("ISA_A", "ISA"));
        assertTrue(FitsNativeReader.isMarker("ISAAB", "ISA"));
    }

    @Test
    void classifyRecognisesDataCard() {
        assertEquals(
                "[begin=FrameSet, nframe=2, domain=SKY, framea=null, begin=Frame, end=Frame, end=FrameSet]",
                items("BEGAST_A= 'FrameSet'\n"
                                + "NFRAME_A=                    2\n"
                                + "DOMAIN_B= 'SKY     '\n"
                                + "FRAMEA_A= '        '\n"
                                + "BEGAST_B= 'Frame   '\n"
                                + "ENDAST_A= 'Frame   '\n"
                                + "ENDAST_B= 'FrameSet'\n")
                        .toString());
    }

    @Test
    void classifyRejectsNonNativeKeywords() {
        assertFalse(FitsNativeReader.isDataKeyword("CTYPE1"));
        assertFalse(FitsNativeReader.isDataKeyword(""));
        assertFalse(FitsNativeReader.isDataKeyword("AB"));
        // Standard keywords whose last two characters are sequence characters pass the keyword
        // test; C then accepts the card by its type, so a logical SIMPLE or a commentary card is
        // not a data item, while an integer NAXIS is the item named by its first three letters.
        assertTrue(FitsNativeReader.isDataKeyword("SIMPLE"));
        assertTrue(FitsNativeReader.isDataKeyword("NAXIS"));
        assertTrue(FitsNativeReader.isDataKeyword("COMMENT"));
        assertTrue(FitsNativeReader.isDataKeyword("CONTINUE"));
        FitsNativeReader r = reader("NAXIS   =                    2\nCTYPE1  = 'RA---TAN'\nSIMPLE  = T\n");
        NativeStream.Datum d = r.next(false, new ArrayList<>());
        assertEquals("nax", d.name); // codespell:ignore nax
        assertEquals("2", d.val);
        AstException e = assertThrows(AstException.class, () -> r.next(false, new ArrayList<>()));
        assertEquals(AstException.AST__BADIN, e.getStatus());
        assertTrue(
                e.getMessage()
                        .startsWith("astRead(FitsChan): Cannot interpret the input data given by FITS keyword "
                                + "\"CTYPE1\"."),
                e.getMessage());
        e = assertThrows(AstException.class, () -> r.next(false, new ArrayList<>()));
        assertTrue(e.getMessage().contains("\"SIMPLE\""), e.getMessage());
    }

    @Test
    void formatValueStringNonEmpty() {
        NativeStream.Datum d = reader("TITLE_A = 'FrameSet'\n").next(false, new ArrayList<>());
        assertEquals("FrameSet", d.val);
        assertEquals(NativeNode.Value.ofString("FrameSet"), d.value);
    }

    @Test
    void formatValueStringEmptyIsObjectMarker() {
        NativeStream.Datum d = reader("FRAMEA_A= '        '\n").next(false, new ArrayList<>());
        assertEquals("framea", d.name);
        assertNull(d.val);
        assertNull(d.value);
    }

    @Test
    void formatValueInt() {
        NativeStream.Datum d = reader("NIN_A   =                   42\n").next(false, new ArrayList<>());
        assertEquals("42", d.val);
        assertEquals(NativeNode.Value.ofInt(42), d.value);
    }

    @Test
    void formatValueFloatBasic() {
        NativeStream.Datum d = reader("ZOOM_A  =                  1.5\n").next(false, new ArrayList<>());
        assertEquals("1.5", d.val);
        assertEquals(NativeNode.Value.ofDouble(1.5), d.value);
    }

    @Test
    void formatValueStringWithEmbeddedQuotesRoundtrips() {
        NativeStream.Datum d = reader("TITLE_A = 'say \"hi\"'\n").next(false, new ArrayList<>());
        assertEquals("say \"hi\"", d.val);
    }

    @Test
    void formatValueStringWithBackslashPassesThrough() {
        NativeStream.Datum d = reader("TITLE_A = 'a\\b'\n").next(false, new ArrayList<>());
        assertEquals("a\\b", d.val);
    }

    @Test
    void unPreQuoteStripsOneOuterPair() {
        assertEquals(",1)     ", FitsNativeReader.unPreQuote("\",1)     \""));
        assertEquals("plain", FitsNativeReader.unPreQuote("plain"));
        assertEquals("a\"b", FitsNativeReader.unPreQuote("\"a\"b\""));
        assertEquals("\"", FitsNativeReader.unPreQuote("\""));
        assertEquals("", FitsNativeReader.unPreQuote(""));
    }

    @Test
    void continuedValueUnPreQuotesEachContinueSegment() {
        assertEquals(
                "[begin=Frame, title=first pixel at (1,1)     , end=Frame]",
                items("BEGAST_A= 'Frame   '\n"
                                + "TITLE_A = 'first pixel at (1&'\n"
                                + "CONTINUE  '\",1)     \"'\n"
                                + "ENDAST_A= 'Frame   '\n")
                        .toString());
    }

    @Test
    void cardsToNativeTextRoundTripsAMinimalFrameset() {
        assertEquals(
                "[begin=FrameSet, nframe=2, end=FrameSet]",
                items("BEGAST_A= 'FrameSet'           / Set of inter-related coordinate systems\n"
                                + "NFRAME_A=                    2 / Number of Frames in FrameSet\n"
                                + "ENDAST_A= 'FrameSet'           / End of object definition\n"
                                + "END\n")
                        .toString());
    }

    @Test
    void cardsToNativeTextEmitsObjectMarkerForEmptyString() {
        List<String> got = items("BEGAST_A= 'FrameSet'\n"
                + "FRAMEA_A= '        '           / First component Frame\n"
                + "BEGAST_B= 'Frame   '\n"
                + "ENDAST_A= 'Frame   '\n"
                + "ENDAST_B= 'FrameSet'\n");
        assertEquals("framea=null", got.get(1));
        assertEquals("begin=Frame", got.get(2));
    }

    @Test
    void cardsToNativeTextJoinsContinueCards() {
        assertEquals(
                "title=first part    second part",
                items("BEGAST_A= 'Frame   '\n"
                                + "TITLE_A = 'first part &'        / first part\n"
                                + "CONTINUE  '   second part'      / continued\n"
                                + "ENDAST_A= 'Frame   '\n")
                        .get(1));
    }

    @Test
    void cardsToNativeTextSkipsNonScalarDataCards() {
        assertEquals(
                "[begin=FrameSet, nframe=2, end=FrameSet]",
                items("SIMPLE  =                    T / not a NATIVE data card despite name suffix\n"
                                + "BEGAST_A= 'FrameSet'\n"
                                + "NFRAME_A=                    2\n"
                                + "ENDAST_A= 'FrameSet'\n")
                        .toString());
    }

    /** A continued string not followed by a CONTINUE card is an error (fitschan.c:18133). */
    @Test
    void missingContinueCardIsAnError() {
        FitsNativeReader r = reader("TITLE_A = 'first part &'\nNIN_A   =                    1\n");
        AstException e = assertThrows(AstException.class, () -> r.next(false, new ArrayList<>()));
        assertEquals(AstException.AST__BADIN, e.getStatus());
        assertTrue(e.getMessage().contains("CONTINUE"), e.getMessage());
    }
}
