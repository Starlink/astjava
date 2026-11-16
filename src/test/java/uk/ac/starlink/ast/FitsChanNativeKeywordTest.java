package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of Rust {@code tests/test_fitschan_native_keyword.rs}: reading Objects held in the
 * keyword-card form of the NATIVE encoding ({@code BEGAST_xx}, {@code ENDAST_xx} and data-keyword
 * cards).
 *
 * <p>{@code polco.head} and {@code polco2.head} hold a MathMap, a class out of scope, so {@code
 * polco_loads} and {@code polco2_loads} are listed in {@code deferred/rust-tests.txt}, and {@code
 * native_read_consumes_its_cards} is translated for {@code specflux.head} and {@code
 * timeplot.head}.
 */
class FitsChanNativeKeywordTest {

    private static FitsChan header(String name) {
        return FitsChan.fromHeader(Fixtures.read("wcsconv/inputs/" + name));
    }

    @Test
    void specfluxLoads() {
        AstObject obj = header("specflux.head").read();
        assertNotNull(obj, "specflux.head: read returned null");
    }

    @Test
    void timeplotLoads() {
        AstObject obj = header("timeplot.head").read();
        assertNotNull(obj, "timeplot.head: read returned null");
        assertEquals("CmpFrame", obj.astClass());
    }

    /**
     * A successful NATIVE read consumes the cards it read, as C's {@code Read} does by calling
     * {@code FixUsed} with {@code remove} set for the NATIVE encoding (fitschan.c:26144), and the AST
     * banner comment blocks go with them ({@code ComBlock}). The header has one blank line, a card
     * the read does not use, which C leaves (Ncard 1 after the read, from a probe of the C library).
     */
    @Test
    void nativeReadConsumesItsCards() {
        for (String name : new String[] {"specflux.head", "timeplot.head"}) {
            FitsChan fc = header(name);
            assertTrue(fc.getNcard() > 0, name + ": no cards to start with");
            assertNotNull(fc.read(), name + ": read returned null");
            assertEquals(1, fc.getNcard(), name + ": cards left after a NATIVE read");
        }
    }
}
