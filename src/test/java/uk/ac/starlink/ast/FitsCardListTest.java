package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/card_list.rs}: the ordered list of cards
 * with its current card, and the {@code ASTWARN} blocks C's {@code Warn} stores.
 */
class FitsCardListTest {

    private static final String SAMPLE = "SIMPLE  =                    T / Fits standard\n"
            + "BITPIX  =                  -32 / Bits per pixel\n"
            + "NAXIS   =                    2 / Number of axes\n"
            + "NAXIS1  =                  100 / Length of axis 1\n"
            + "NAXIS2  =                  200 / Length of axis 2\n"
            + "CRVAL1  =     266.416666666667 / Reference value\n"
            + "CTYPE1  = 'RA---TAN'           / Axis type\n"
            + "END";

    private static FitsCardList sampleList() {
        return FitsCardList.fromHeader(SAMPLE);
    }

    private static List<String> keywords(FitsCardList list) {
        List<String> out = new ArrayList<>();
        for (FitsCard c : list.cards()) {
            out.add(c.keyword);
        }
        return out;
    }

    private static List<String> astwarnValues(FitsCardList list) {
        List<String> out = new ArrayList<>();
        for (FitsCard c : list.cards()) {
            if ("ASTWARN".equals(c.keyword)) {
                assertEquals(FitsCard.Kind.STRING, c.value.kind);
                out.add(c.value.s);
            }
        }
        return out;
    }

    private static FitsCard intCard(String keyword, long value) {
        return new FitsCard(keyword, FitsCard.Value.ofInt(value), null);
    }

    @Test
    void fromHeaderParsesCards() {
        FitsCardList list = sampleList();
        assertEquals(8, list.len());
        assertFalse(list.isEmpty());
    }

    @Test
    void findLocatesKeywordCaseInsensitive() {
        FitsCardList list = sampleList();
        FitsCard c = list.find("naxis");
        assertEquals("NAXIS", c.keyword);
        assertEquals(FitsCard.Value.ofInt(2), c.value);
        assertEquals(FitsCard.Value.ofString("RA---TAN"), list.find("CTYPE1").value);
    }

    @Test
    void findReturnsNoneForMissing() {
        assertNull(sampleList().find("MISSING"));
    }

    @Test
    void putWithOverwriteReplaces() {
        FitsCardList list = sampleList();
        int len = list.len();
        int naxis = keywords(list).indexOf("NAXIS");
        list.setPosition(naxis);
        list.put(new FitsCard("NAXIS", FitsCard.Value.ofInt(3), "Changed"), true);
        assertEquals(len, list.len());
        assertEquals(naxis + 1, list.position());
        FitsCard c = list.find("NAXIS");
        assertEquals(FitsCard.Value.ofInt(3), c.value);
        assertEquals("Changed", c.comment);
    }

    @Test
    void putWithoutOverwriteInserts() {
        FitsCardList list = sampleList();
        int len = list.len();
        list.setPosition(2);
        list.put(new FitsCard("NEWKEY", FitsCard.Value.ofInt(99), null), false);
        assertEquals(len + 1, list.len());
        assertEquals("NEWKEY", list.cards().get(2).keyword);
    }

    @Test
    void deleteRemovesCard() {
        FitsCardList list = sampleList();
        int len = list.len();
        assertTrue(list.delete("NAXIS1"));
        assertEquals(len - 1, list.len());
        assertNull(list.find("NAXIS1"));
    }

    @Test
    void deleteReturnsFalseForMissing() {
        assertFalse(sampleList().delete("MISSING"));
    }

    @Test
    void getIntExtractsValue() {
        FitsCardList list = sampleList();
        assertEquals(2L, (long) list.getInt("NAXIS"));
        assertEquals(100L, (long) list.getInt("NAXIS1"));
        assertNull(list.getInt("MISSING"));
    }

    @Test
    void getFloatExtractsValue() {
        FitsCardList list = sampleList();
        assertTrue(Math.abs(list.getFloat("CRVAL1") - 266.416666666667) < 1e-10);
        assertEquals(2.0, (double) list.getFloat("NAXIS"));
    }

    @Test
    void getStringExtractsValue() {
        FitsCardList list = sampleList();
        assertEquals("RA---TAN", list.getString("CTYPE1"));
        assertNull(list.getString("NAXIS"));
    }

    @Test
    void getLogicalExtractsValue() {
        FitsCardList list = sampleList();
        assertTrue(list.getLogical("SIMPLE"));
        assertNull(list.getLogical("NAXIS"));
    }

    @Test
    void findNextAdvancesPosition() {
        FitsCardList list = sampleList();
        list.rewind();
        assertEquals(FitsCard.Value.ofInt(2), list.findNext("NAXIS").value);
        assertEquals(3, list.position());
        assertNull(list.findNext("NAXIS"));
    }

    @Test
    void rewindResetsPosition() {
        FitsCardList list = sampleList();
        list.setPosition(5);
        assertEquals(5, list.position());
        list.rewind();
        assertEquals(0, list.position());
    }

    @Test
    void atEof() {
        FitsCardList list = sampleList();
        assertFalse(list.atEof());
        list.setPosition(list.len());
        assertTrue(list.atEof());
    }

    @Test
    void deleteAtPosition() {
        FitsCardList list = sampleList();
        int len = list.len();
        list.setPosition(0);
        list.deleteAtPosition();
        assertEquals(len - 1, list.len());
        assertEquals("BITPIX", list.cards().get(0).keyword);
    }

    @Test
    void iterYieldsAllCards() {
        assertEquals(
                Arrays.asList("SIMPLE", "BITPIX", "NAXIS", "NAXIS1", "NAXIS2", "CRVAL1", "CTYPE1", "END"),
                keywords(sampleList()));
    }

    /** An empty line is a blank card, as C's FitsChan stores one read through a source. */
    @Test
    void fromHeaderKeepsEmptyLinesAsBlankCards() {
        FitsCardList list = FitsCardList.fromHeader("NAXIS   =                    2 / Number of axes\n\n\nEND\n");
        assertEquals(Arrays.asList("NAXIS", "", "", "END"), keywords(list));
    }

    /** C's {@code Warn} inserts each card in front of the current card, which stays current. */
    @Test
    void pushAstwarnInsertsBeforeTheCurrentCard() {
        FitsCardList list = new FitsCardList();
        list.push(intCard("NAXIS1", 512));
        list.push(intCard("NAXIS2", 512));
        list.setPosition(1);
        list.pushAstwarn("short warning");
        assertEquals(Arrays.asList("NAXIS1", "ASTWARN", "ASTWARN", "ASTWARN", "NAXIS2"), keywords(list));
        assertEquals(4, list.position());
    }

    @Test
    void consecutivePushAstwarnCallsChainBeforeTheSameCard() {
        FitsCardList list = new FitsCardList();
        list.push(intCard("NAXIS1", 512));
        list.push(intCard("NAXIS2", 512));
        list.setPosition(1);
        list.pushAstwarn("first warning");
        list.pushAstwarn("second warning");
        assertEquals(Arrays.asList(" ", "first warning", " ", " ", "second warning", " "), astwarnValues(list));
        List<String> kw = keywords(list);
        assertEquals("NAXIS2", kw.get(kw.size() - 1));
        assertEquals(7, list.position());
    }

    /** An identical block of adjacent ASTWARN cards is not repeated (fitschan.c:35217-35247). */
    @Test
    void pushAstwarnSkipsAnIdenticalExistingBlock() {
        String text = "The FITS keyword name ('sys.') contains an illegal character ('.').";
        FitsCardList list = new FitsCardList();
        list.push(intCard("NAXIS1", 512));
        list.setPosition(0);
        list.pushAstwarn(text);
        List<String> first = astwarnValues(list);
        list.pushAstwarn(text);
        assertEquals(first, astwarnValues(list));
    }

    @Test
    void pushAstwarnStoresBlocksThatShareATrailingLine() {
        String first = "Projection parameter PV2_1 found, but is not used by -TAN projections.";
        String second = "Projection parameter PV2_2 found, but is not used by -TAN projections.";
        FitsCardList list = new FitsCardList();
        list.push(intCard("NAXIS2", 512));
        list.setPosition(0);
        list.pushAstwarn(first);
        list.pushAstwarn(second);
        List<String> v = astwarnValues(list);
        assertEquals(8, v.size(), v.toString());
        assertTrue(v.get(1).contains("PV2_1") && v.get(5).contains("PV2_2"), v.toString());
        assertEquals(v.get(2), v.get(6));
    }

    @Test
    void pushAstwarnDedupProbeDoesNotMoveThePosition() {
        FitsCardList list = new FitsCardList();
        list.push(intCard("NAXIS1", 512));
        list.push(intCard("NAXIS2", 512));
        list.setPosition(0);
        list.pushAstwarn("a repeated warning");
        int pos = list.position();
        list.pushAstwarn("a repeated warning");
        assertEquals(pos, list.position());
    }
}
