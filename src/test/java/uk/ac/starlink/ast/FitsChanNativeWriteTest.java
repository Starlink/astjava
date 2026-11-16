package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of the write-side unit tests of Rust {@code fitschan/enc_native.rs}: the keyword-card
 * form of the NATIVE encoding a FitsChan writes (fitschan.c {@code CreateKeyword}, {@code
 * MakeBanner}, {@code PreQuote}, {@code WriteString} and the other Channel {@code Write}
 * overrides), with tests of {@link FitsChan#write} itself whose expected cards come from the C
 * library.
 *
 * <p>The Rust port builds the cards in a list and inserts them into the FitsChan at the end; here
 * they are stored in the FitsChan one at a time, as C stores them, so the tests look at the
 * FitsChan's cards.
 */
class FitsChanNativeWriteTest {

    /** The cards of {@code fc} a read has not consumed, formatted, as C's {@code astFindFits} gives them. */
    private static List<String> cards(FitsChan fc) {
        int icard = fc.getCard();
        fc.clear("Card");
        List<String> out = new ArrayList<>();
        String card;
        while ((card = fc.findFits("%f", true)) != null) {
            out.add(card);
        }
        fc.setCard(icard);
        return out;
    }

    /** {@code text} padded with blanks to a card's 80 characters. */
    private static String pad(String text) {
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < FitsCard.FITS_CARD_LEN) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static List<String> padded(String... lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            out.add(pad(l));
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Keyword-card writer (CreateKeyword, MakeBanner, PreQuote, WriteString)
    // -----------------------------------------------------------------

    @Test
    void createKeywordSequencesPerBase() {
        FitsNativeWriter seq = new FitsNativeWriter(new FitsChan());
        // First use of each base starts at suffix `_A` (seq = 1).
        assertEquals("BEGAST_A", seq.createKeyword("BEGAST"));
        assertEquals("BEGAST_B", seq.createKeyword("BEGAST"));
        assertEquals("BEGAST_C", seq.createKeyword("BEGAST"));
        // A different base keeps its own counter.
        assertEquals("ISA_A", seq.createKeyword("ISA"));
        assertEquals("BEGAST_D", seq.createKeyword("BEGAST"));
        // Long names are truncated to FITSNAMLEN - 2 = 6 characters.
        assertEquals("MATRIX_A", seq.createKeyword("MatrixMap"));
    }

    @Test
    void createKeywordWrapsAfterFirstBlock() {
        FitsNativeWriter seq = new FitsNativeWriter(new FitsChan());
        // 26 second characters after '_': seq 1..=26 map to _A.._Z, then the high character
        // advances (seq 27 -> "A_").
        String last = "";
        for (int i = 0; i < 27; i++) {
            last = seq.createKeyword("X");
        }
        assertEquals("XA_", last);
    }

    @Test
    void makeBannerCentresTextWithAstTokens() {
        String b = FitsNativeWriter.makeBanner("Beginning of AST data for ", "FrameSet", " object");
        // 72 columns in all, with the AST token at each end.
        assertEquals(72, b.length());
        assertTrue(b.startsWith("AST"));
        assertTrue(b.endsWith("AST"));
        assertTrue(b.contains("Beginning of AST data for FrameSet object"));
    }

    /** Write a small object tree to keyword cards and read it straight back. */
    @Test
    void writeKeywordNativeRoundTrips() {
        NativeNode inner = new NativeNode("UnitMap", "Unit (null) Mapping");
        inner.writeInt("Nin", true, true, 2, "Number of input coordinates");
        inner.writeIsA("Mapping", "Mapping between coordinate systems");

        NativeNode outer = new NativeNode("CmpMap", "Compound Mapping");
        outer.writeInt("Nin", true, true, 2, "Number of input coordinates");
        outer.writeDouble("Zoom", true, true, 5.5, "Zoom factor");
        outer.writeIsA("Mapping", "Mapping between coordinate systems");
        outer.writeObject("MapA", true, true, inner, "First component Mapping");

        FitsChan fc = new FitsChan();
        fc.setFull(-1);
        new FitsNativeWriter(fc).writeObject(outer, -1, false);

        // The first card opens the outer object; the last closes it.
        List<FitsCard> block = fc.cards().cards();
        assertEquals("BEGAST_A", block.get(0).keyword);
        assertEquals("CmpMap", block.get(0).value.s);
        assertTrue(block.get(block.size() - 1).keyword.startsWith("ENDAST"));

        // Read the cards back, from the first, as the data items of a Channel read.
        fc.clear("Card");
        FitsNativeReader r = new FitsNativeReader(fc.cards(), 10);
        List<String> items = new ArrayList<>();
        NativeStream.Datum d = r.next(true, new ArrayList<>());
        while (d != null) {
            items.add(d.name + "=" + d.val);
            d = r.next(false, new ArrayList<>());
        }
        assertEquals(
                Arrays.asList(
                        "begin=CmpMap",
                        "nin=2",
                        "zoom=5.5",
                        "isa=Mapping",
                        "mapa=null",
                        "begin=UnitMap",
                        "nin=2",
                        "isa=Mapping",
                        "end=UnitMap",
                        "end=CmpMap"),
                items);
    }

    @Test
    void preQuoteEmptyValueIsTwoDoubleQuotes() {
        // C PreQuote initialises dquotes = 2 and the loop never runs, so a zero-length value is
        // given as "".
        assertEquals("\"\"", FitsNativeWriter.preQuote(""));
    }

    @Test
    void preQuoteTruncatesAtTheFitsValueLimit() {
        // The accommodation test is nc + 1 + dq + sq > AST__FITSCHAN_FITSCARDLEN - FITSNAMLEN - 4,
        // i.e. > 68 for the standard 80/8 card layout.
        assertEquals(repeat('x', 68), FitsNativeWriter.preQuote(repeat('x', 80)));
        // Each single quote will be doubled by the card formatter, so it costs two of the 68
        // columns.
        assertEquals(repeat('\'', 34), FitsNativeWriter.preQuote(repeat('\'', 80)));
    }

    @Test
    void preQuoteUsesTheLastAccommodatedCharacter() {
        // Trailing white space that is truncated away does not force quotes, because dquotes
        // tracks the last accepted character.
        assertEquals(repeat('y', 68), FitsNativeWriter.preQuote(repeat('y', 68) + "   "));
        // Trailing white space that survives does force them.
        assertEquals("\"z  \"", FitsNativeWriter.preQuote("z  "));
    }

    @Test
    void preQuoteUnPreQuoteRoundTrip() {
        for (String v : Arrays.asList("", "z  ", "\"lead", "plain", "a\"b")) {
            String q = FitsNativeWriter.preQuote(v);
            assertEquals(v, FitsNativeReader.unPreQuote(q), "round trip failed for " + v);
        }
    }

    /**
     * A long string value is split across CONTINUE cards with a trailing ampersand on each card
     * but the last, as C's {@code WriteString} does.
     */
    @Test
    void writeStringValueSplitsLongStrings() {
        FitsChan fc = new FitsChan();
        String text = "Pixel coordinates; first pixel at (-128.5,-127.5) plus padding";
        new FitsNativeWriter(fc).writeString("Title", true, text, "Title");
        List<FitsCard> block = fc.cards().cards();
        assertTrue(block.size() >= 2, "a long value is split");
        // The first card holds the keyword and ends with an ampersand.
        assertEquals("TITLE_A", block.get(0).keyword);
        assertEquals(FitsCard.Kind.STRING, block.get(0).value.kind);
        assertTrue(block.get(0).value.s.endsWith("&"), block.get(0).value.s);
        // The cards after it are CONTINUE cards.
        assertEquals("CONTINUE", block.get(1).keyword);
    }

    /** C's {@code WriteString} loops while characters remain, so an empty value writes no card. */
    @Test
    void writeStringValueWritesNothingForAnEmptyValue() {
        FitsChan fc = new FitsChan();
        new FitsNativeWriter(fc).writeString("Title", true, "", "Title");
        assertEquals(0, fc.cards().len());
    }

    // -----------------------------------------------------------------
    // FitsChan.write with the NATIVE encoding; the expected cards are those the C library writes
    // (oracle/fitschan_cursor.json, from tools/oracle_fixtures/fitschan_cursor_probe.c)
    // -----------------------------------------------------------------

    /** The cards C writes for {@code astZoomMap( 2, 2.0, " " )} with keyword suffix {@code zoom}. */
    private static List<String> zoomMapCards(String zoom) {
        return padded(
                "",
                "COMMENT AST ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ AST",
                "COMMENT AST             Beginning of AST data for ZoomMap object             AST",
                "COMMENT AST ................................................................ AST",
                "BEGAST_A= 'ZoomMap '           / Zoom about the origin",
                "NIN_A   =                    2 / Number of input coordinates",
                "ISA_A   = 'Mapping '           / Mapping between coordinate systems",
                "ZOOM_" + zoom + "  =                  2.0 / Zoom factor",
                "ENDAST_A= 'ZoomMap '           / End of object definition",
                "COMMENT AST ................................................................ AST",
                "COMMENT AST                End of AST data for ZoomMap object                AST",
                "COMMENT AST ---------------------------------------------------------------- AST");
    }

    /**
     * C's {@code CreateKeyword} passes over a keyword a card already has: with a {@code ZOOM_A}
     * card present the ZoomMap's zoom factor is {@code ZOOM_B} (case {@code
     * native_write_after_duplicate_delete}, where the card left after deleting one of two {@code
     * ZOOM_A} cards still holds the keyword).
     */
    @Test
    void createKeywordPassesOverKeywordsPresent() {
        FitsChan fc = new FitsChan();
        fc.setEncoding("NATIVE");
        fc.putFits("ZOOM_A  = 1", false);
        fc.putFits("ZOOM_A  = 2", false);
        fc.clear("Card");
        fc.delFits();
        fc.setCard(2);
        assertEquals(1, fc.write(new ZoomMap(2, 2.0)));
        List<String> want = new ArrayList<>();
        want.add(pad("ZOOM_A  =                    2"));
        want.addAll(zoomMapCards("B"));
        assertEquals(want, cards(fc));
        assertEquals(14, fc.getCard());
    }

    /**
     * The cards go in front of the current card, which stays current (case {@code
     * write_inserts_before_current}).
     */
    @Test
    void writeInsertsBeforeTheCurrentCard() {
        FitsChan fc = new FitsChan();
        fc.putFits("A       = 1", false);
        fc.putFits("B       = 2", false);
        fc.putFits("C       = 3", false);
        fc.setCard(2);
        assertEquals(1, fc.write(new ZoomMap(2, 2.0)));
        List<String> want = new ArrayList<>();
        want.add(pad("A       =                    1"));
        want.addAll(zoomMapCards("A"));
        want.add(pad("B       =                    2"));
        want.add(pad("C       =                    3"));
        assertEquals(want, cards(fc));
        assertEquals(14, fc.getCard());
        assertEquals("B", fc.getC("CardName"));
    }

    /**
     * The FitsChan keeps the last sequence number of each keyword from one write to the next
     * (C's {@code keyseq}), so a second Object's keywords follow the first's (case {@code
     * native_two_objects}).
     */
    @Test
    void sequenceNumbersPersistFromOneWriteToTheNext() {
        FitsChan fc = new FitsChan();
        fc.setEncoding("NATIVE");
        fc.write(new ZoomMap(2, 2.0));
        fc.write(new ZoomMap(2, 2.0));
        List<String> got = cards(fc);
        assertTrue(got.contains(pad("BEGAST_B= 'ZoomMap '           / Zoom about the origin")), got.toString());
        assertTrue(got.contains(pad("ZOOM_B  =                  2.0 / Zoom factor")), got.toString());
        assertTrue(got.contains(pad("ENDAST_B= 'ZoomMap '           / End of object definition")), got.toString());
        // A copy keeps the sequence numbers, as C's astCopy copies keyseq.
        FitsChan copy = (FitsChan) fc.copy();
        copy.write(new ZoomMap(2, 2.0));
        assertTrue(cards(copy).contains(pad("ZOOM_C  =                  2.0 / Zoom factor")));
    }

    /**
     * With Comment clear there are no banners and no comments, but the first card of a string still
     * leaves room for its comment, and a nested Object's card is a blank string (case {@code
     * native_long_title_no_comments}).
     */
    @Test
    void noCommentsStillReservesRoomForTheComment() {
        FitsChan fc = new FitsChan();
        fc.setComment(false);
        Frame fr = new Frame(2);
        fr.setTitle(repeat('t', 100));
        assertEquals(1, fc.write(fr));
        assertEquals(
                padded(
                        "BEGAST_A= 'Frame   '",
                        "TITLE_A = 'tttttttttttttttttttttttttttttttttttt&'",
                        "CONTINUE  'tttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttt'",
                        "NAXES_A =                    2",
                        "AX1_A   = '        '",
                        "BEGAST_B= 'Axis    '",
                        "ENDAST_A= 'Axis    '",
                        "AX2_A   = '        '",
                        "BEGAST_C= 'Axis    '",
                        "ENDAST_B= 'Axis    '",
                        "ENDAST_C= 'Frame   '"),
                cards(fc));
        AstObject back = readBack(fc);
        assertEquals(repeat('t', 100), ((Frame) back).getTitle());
    }

    /**
     * With Full 0 nested Objects and class boundaries are marked by indented comment cards with a
     * blank keyword (case {@code native_full_0_nested}).
     */
    @Test
    void fullZeroMarksNestedObjects() {
        FitsChan fc = new FitsChan();
        fc.setFull(0);
        assertEquals(1, fc.write(new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 3.0), true)));
        assertEquals(
                padded(
                        "",
                        "COMMENT AST ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ AST",
                        "COMMENT AST             Beginning of AST data for CmpMap object              AST",
                        "COMMENT AST ................................................................ AST",
                        "BEGAST_A= 'CmpMap  '           / Compound Mapping",
                        "NIN_A   =                    2 / Number of input coordinates",
                        "ISA_A   = 'Mapping '           / Mapping between coordinate systems",
                        "        ...                    / Class boundary",
                        "MAPA_A  = '        '           / First component Mapping",
                        "        ++++++                 / Beginning of ZoomMap",
                        "BEGAST_B= 'ZoomMap '           / Zoom about the origin",
                        "NIN_B   =                    2 / Number of input coordinates",
                        "ISA_B   = 'Mapping '           / Mapping between coordinate systems",
                        "        ......                 / Class boundary",
                        "ZOOM_A  =                  2.0 / Zoom factor",
                        "ENDAST_A= 'ZoomMap '           / End of object definition",
                        "        ------                 / End of ZoomMap",
                        "MAPB_A  = '        '           / Second component Mapping",
                        "        ++++++                 / Beginning of ZoomMap",
                        "BEGAST_C= 'ZoomMap '           / Zoom about the origin",
                        "NIN_C   =                    2 / Number of input coordinates",
                        "ISA_C   = 'Mapping '           / Mapping between coordinate systems",
                        "        ......                 / Class boundary",
                        "ZOOM_B  =                  3.0 / Zoom factor",
                        "ENDAST_B= 'ZoomMap '           / End of object definition",
                        "        ------                 / End of ZoomMap",
                        "ENDAST_C= 'CmpMap  '           / End of object definition",
                        "COMMENT AST ................................................................ AST",
                        "COMMENT AST                End of AST data for CmpMap object                 AST",
                        "COMMENT AST ---------------------------------------------------------------- AST"),
                cards(fc));
        assertEquals("CmpMap", readBack(fc).astClass());
    }

    /**
     * An unset value written because Full is positive becomes a COMMENT card holding the text of
     * the card (C's {@code MakeIntoComment}); the lines are from case {@code native_full_1}.
     */
    @Test
    void unsetValuesBecomeCommentCards() {
        FitsChan fc = new FitsChan();
        fc.setFull(1);
        assertEquals(1, fc.write(new Frame(2)));
        List<String> got = cards(fc);
        for (String want : padded(
                "COMMENT TITLE_A = '2-d coordinate system' / Title of coordinate system",
                "NAXES_A =                    2 / Number of coordinate axes",
                "COMMENT BOT1_A  = -1.7976931348623157E+308 / Lowest legal axis value",
                "COMMENT LABEL_A = 'Coordinate axis'    / Axis Label",
                "        ++++++                 / Beginning of Axis")) {
            assertTrue(got.contains(want), want + " in " + got);
        }
    }

    // -----------------------------------------------------------------
    // The write entry
    // -----------------------------------------------------------------

    /**
     * An encoding other than NATIVE describes only a FrameSet: C's {@code Write} writes nothing for
     * any other Object, returns 0 and leaves the cards and the current card as they were.
     */
    @Test
    void foreignEncodingWritesNoOtherObject() {
        FitsChan fc = new FitsChan();
        fc.putFits("A       = 1", false);
        fc.putFits("B       = 2", false);
        fc.setCard(2);
        fc.setEncoding("FITS-WCS");
        assertEquals(0, fc.write(new ZoomMap(2, 2.0)));
        assertEquals(padded("A       =                    1", "B       =                    2"), cards(fc));
        assertEquals(2, fc.getCard());
    }

    /**
     * A write the encoding cannot make returns 0 and leaves the cards and the current card as they
     * were (C's {@code FixNew}), whatever the hook for that encoding did to them.
     */
    @Test
    void failedForeignWriteRollsBackItsCards() {
        FitsChan fc = new FitsChan() {
            @Override
            boolean writeForeign(FrameSet fs, FitsChan.Encoding enc) {
                setFits("NEW1", 1, null, false);
                cards().setPosition(0);
                setFits("NEW2", 2, null, false);
                return false;
            }
        };
        fc.putFits("A       = 1", false);
        fc.putFits("B       = 2", false);
        fc.setCard(2);
        fc.setEncoding("FITS-WCS");
        assertEquals(0, fc.write(new FrameSet(new Frame(2))));
        assertEquals(padded("A       =                    1", "B       =                    2"), cards(fc));
        assertEquals(2, fc.getCard());
    }

    /** A successful foreign write leaves the FitsChan at end-of-file, as C's {@code Write} does. */
    @Test
    void successfulForeignWriteLeavesEndOfFile() {
        FitsChan fc = new FitsChan() {
            @Override
            boolean writeForeign(FrameSet fs, FitsChan.Encoding enc) {
                setFits("NEW1", 1, null, false);
                return true;
            }
        };
        fc.putFits("A       = 1", false);
        fc.putFits("B       = 2", false);
        fc.clear("Card");
        fc.setEncoding("FITS-WCS");
        assertEquals(1, fc.write(new FrameSet(new Frame(2))));
        assertEquals(
                padded(
                        "NEW1    =                    1",
                        "A       =                    1",
                        "B       =                    2"),
                cards(fc));
        assertEquals(4, fc.getCard());
    }

    /** A write that throws leaves the cards and the current card as they were. */
    @Test
    void writeThatThrowsRollsBackItsCards() {
        FitsChan fc = new FitsChan() {
            @Override
            boolean writeForeign(FrameSet fs, FitsChan.Encoding enc) {
                setFits("NEW1", 1, null, false);
                throw Ast.err(AstException.AST__INTER, "test");
            }
        };
        fc.putFits("A       = 1", false);
        fc.setCard(1);
        fc.setEncoding("FITS-WCS");
        assertEquals(
                AstException.AST__INTER,
                org.junit.jupiter.api.Assertions.assertThrows(
                                AstException.class, () -> fc.write(new FrameSet(new Frame(2))))
                        .getStatus());
        assertEquals(padded("A       =                    1"), cards(fc));
        assertEquals(1, fc.getCard());
    }

    /** Until the foreign encodings are written, the hook gives C's failure. */
    @Test
    void foreignFrameSetWriteFailsForNow() {
        FitsChan fc = new FitsChan();
        fc.setEncoding("FITS-WCS");
        assertEquals(0, fc.write(new FrameSet(new Frame(2))));
        assertEquals(0, fc.getNcard());
    }

    private static AstObject readBack(FitsChan fc) {
        fc.clear("Card");
        AstObject obj = fc.read();
        assertNotNull(obj);
        return obj;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
