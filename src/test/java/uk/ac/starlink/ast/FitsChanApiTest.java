package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests of the FitsChan API that has no Rust counterpart: the Iterator constructor and {@link
 * FitsChan#iterator}, {@link FitsChan#close} and the sink, and attribute values the C macros store
 * as given, each checked against the C source cited.
 */
class FitsChanApiTest {

    private static final List<String> HEADER = Arrays.asList(
            "NAXIS   =                    2", "NAXIS1  =                  100", "NAXIS2  =                  200");

    /** A FitsChan that collects the cards its sink is given. */
    private static final class Collecting extends FitsChan {
        final List<String> out = new ArrayList<>();

        Collecting(Iterator<String> cards) {
            super(cards);
        }

        @Override
        protected void sink(String line) {
            out.add(line);
        }
    }

    @Test
    void theIteratorConstructorReadsTheCardsOnFirstUse() {
        FitsChan fc = new FitsChan(HEADER.iterator());
        assertEquals(3, fc.getNcard());
        assertEquals(1, fc.getCard());
        assertEquals(200, (int) fc.getFitsI("NAXIS2"));
    }

    @Test
    void iteratorGivesEachCardAndLeavesTheCurrentCard() {
        FitsChan fc = new FitsChan(HEADER.iterator());
        fc.setCard(2);
        List<String> got = new ArrayList<>();
        Iterator<String> it = fc.iterator();
        while (it.hasNext()) {
            got.add(it.next());
        }
        assertEquals(3, got.size());
        assertTrue(got.get(1).startsWith("NAXIS1  =                  100"));
        assertEquals(80, got.get(0).length());
        assertEquals(2, fc.getCard());
    }

    @Test
    void iteratorRemoveDeletesTheCardLastReturned() {
        FitsChan fc = new FitsChan(HEADER.iterator());
        fc.setCard(3);
        Iterator<String> it = fc.iterator();
        it.next();
        it.remove();
        assertThrows(IllegalStateException.class, it::remove);
        assertTrue(it.next().startsWith("NAXIS1"));
        assertEquals(2, fc.getNcard());
        assertEquals("NAXIS2", fc.getCardName());
    }

    /** C's {@code Delete} writes the cards to the sink, then {@code EmptyFits} deletes them. */
    @Test
    void closeWritesTheCardsToTheSinkAndDeletesThem() {
        Collecting fc = new Collecting(HEADER.iterator());
        fc.close();
        assertEquals(3, fc.out.size());
        assertTrue(fc.out.get(2).startsWith("NAXIS2  =                  200"));
        assertEquals(0, fc.getNcard());
        fc.close();
        assertEquals(3, fc.out.size());
    }

    /** A copy has no sink: C's {@code Copy} sets the sink function to NULL. */
    @Test
    void aCopyDoesNotWriteToTheSink() {
        Collecting fc = new Collecting(HEADER.iterator());
        FitsChan copy = (FitsChan) fc.copy();
        assertEquals(3, copy.getNcard());
        copy.close();
        assertTrue(fc.out.isEmpty());
    }

    /**
     * A FitsChan with no sink does not format its cards when closed, as C's {@code WriteToSink}
     * does nothing without a sink function (fitschan.c:41751), so a card that cannot be formatted
     * is no error.
     */
    @Test
    void closeWithoutASinkIgnoresUnformattableCards() {
        FitsChan fc = new FitsChan();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 69; i++) {
            s.append('a');
        }
        fc.setFits("S69", s.toString(), "", false);
        fc.close();
        assertEquals(0, fc.getNcard());
    }

    /**
     * IgnoreBadAlt and SipOK are stored as given (astMAKE_SET with {@code value}, fitschan.c:43022
     * and 43241), while CarLin is stored as 0 or 1 (fitschan.c:43070).
     */
    @Test
    void integerFlagsKeepTheValueGiven() {
        FitsChan fc = new FitsChan();
        fc.setI("IgnoreBadAlt", 5);
        fc.setI("SipOK", -2);
        fc.setI("CarLin", 5);
        assertEquals(5, fc.getI("IgnoreBadAlt"));
        assertEquals(-2, fc.getI("SipOK"));
        assertEquals(1, fc.getI("CarLin"));
    }

    /** CheckFitsName names the method that called it in the error (fitschan.c:6185). */
    @Test
    void anEqualsSignInAKeywordNameNamesTheMethod() {
        FitsChan fc = new FitsChan();
        fc.putFits("A       = 1", false);
        AstException e = assertThrows(AstException.class, () -> fc.getFitsF("A=B"));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertTrue(e.getMessage().startsWith("astGetFitsF(FitsChan): An equals sign"), e.getMessage());
        e = assertThrows(AstException.class, () -> fc.putFits("A=B     = 1", false));
        assertTrue(e.getMessage().startsWith("astPutFits(FitsChan): An equals sign"), e.getMessage());
    }

    /** The Card attribute is 0 for an empty FitsChan (fitschan.c GetCard, line 17444). */
    @Test
    void cardIsZeroWhenEmpty() {
        FitsChan fc = new FitsChan();
        assertEquals(0, fc.getCard());
        assertFalse(fc.test("Card"));
    }

    /** {@code write} is out of scope. */
    @Test
    void writeIsUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> new FitsChan().write(new UnitMap(1)));
    }
}
