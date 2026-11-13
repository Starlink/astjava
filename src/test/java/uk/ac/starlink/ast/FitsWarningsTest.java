package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Translation of the unit tests of Rust {@code fitschan/warning.rs} (fitschan.c {@code GoodWarns}). */
class FitsWarningsTest {

    @Test
    void defaultListNamesItsConditionsOnly() {
        for (String c :
                new String[] {"badkeyname", "badkeyvalue", "tnx", "zpx", "badpv", "badcel", "badmat", "badctype"}) {
            assertTrue(FitsWarnings.reports(FitsWarnings.DEFAULT_WARNINGS, c), c);
        }
        for (String c : new String[] {"badalt", "badval", "distortion", "noctype", "badlat"}) {
            assertFalse(FitsWarnings.reports(FitsWarnings.DEFAULT_WARNINGS, c), c);
        }
    }

    @Test
    void reportsSplitsAtSpacesOnly() {
        assertTrue(FitsWarnings.reports("  BADVAL  ", "badval"));
        assertFalse(FitsWarnings.reports("badval\tbadlat", "badval"));
        assertFalse(FitsWarnings.reports("badv", "badval"));
    }

    @Test
    void goodWarnsAcceptsAnyCaseAndWhiteSpace() {
        FitsWarnings.goodWarns("");
        FitsWarnings.goodWarns(" BadVal\tnoCTYPE\n");
        AstException e = assertThrows(AstException.class, () -> FitsWarnings.goodWarns("badval bogus"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertTrue(e.getMessage().contains("'bogus'"));
    }
}
