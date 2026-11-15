package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Translation of the unit tests of Rust {@code fitschan/fits_match.rs} (fitschan.c {@code Match}). */
class FitsMatchTest {

    @Test
    void literalTemplateMatchesExactly() {
        assertEquals(Collections.emptyList(), FitsMatch.matchKeyword("EQUINOX", "EQUINOX", 0));
        assertNull(FitsMatch.matchKeyword("EQUINOXA", "EQUINOX", 0));
        assertNull(FitsMatch.matchKeyword("EQUINO", "EQUINOX", 0));
    }

    @Test
    void literalMatchIgnoresCase() {
        assertNotNull(FitsMatch.matchKeyword("equinox", "EQUINOX", 0));
    }

    @Test
    void singleDigitFieldReturnsIndex() {
        assertEquals(Arrays.asList(1), FitsMatch.matchKeyword("CRVAL1", "CRVAL%d", 1));
        assertEquals(Arrays.asList(12), FitsMatch.matchKeyword("CRVAL12", "CRVAL%d", 1));
        assertNull(FitsMatch.matchKeyword("CRVAL1A", "CRVAL%d", 1));
        assertNull(FitsMatch.matchKeyword("CRVAL", "CRVAL%d", 1));
    }

    @Test
    void versionLetterFieldNeedsUpperCase() {
        assertEquals(Arrays.asList(1), FitsMatch.matchKeyword("CRVAL1A", "CRVAL%d%1c", 1));
        assertEquals(Arrays.asList(12), FitsMatch.matchKeyword("CRVAL12Z", "CRVAL%d%1c", 1));
        assertNull(FitsMatch.matchKeyword("CRVAL1a", "CRVAL%d%1c", 1));
        assertNull(FitsMatch.matchKeyword("CRVAL1AB", "CRVAL%d%1c", 1));
    }

    @Test
    void twoDigitFieldsComeBackInTemplateOrder() {
        assertEquals(Arrays.asList(1, 2), FitsMatch.matchKeyword("PC1_2", "PC%d_%d", 2));
        assertEquals(Arrays.asList(12, 34), FitsMatch.matchKeyword("PC12_34", "PC%d_%d", 2));
        assertEquals(Arrays.asList(1, 2), FitsMatch.matchKeyword("PC1_2A", "PC%d_%d%1c", 2));
        assertNull(FitsMatch.matchKeyword("PC1_2", "PC%d_%d%1c", 2));
        assertNull(FitsMatch.matchKeyword("PC001001", "PC%d_%d", 2));
    }

    @Test
    void similarPrefixesStayDistinct() {
        assertNull(FitsMatch.matchKeyword("SREFP1", "SREF%d", 1));
        assertNull(FitsMatch.matchKeyword("SREFIS", "SREF%d", 1));
        assertEquals(Arrays.asList(1), FitsMatch.matchKeyword("SREFP1", "SREFP%d", 1));
        assertNull(FitsMatch.matchKeyword("AP_1_2", "A_%d_%d", 2));
        assertEquals(Arrays.asList(1, 2), FitsMatch.matchKeyword("AP_1_2", "AP_%d_%d", 2));
    }

    @Test
    void zeroWidthFieldMatchesNothingOrMore() {
        assertNotNull(FitsMatch.matchKeyword("CD", "CD%0d", 1));
        assertNotNull(FitsMatch.matchKeyword("CD12", "CD%0d", 1));
        assertNull(FitsMatch.matchKeyword("CDX", "CD%0d", 1));
    }

    @Test
    void fixedWidthFieldMatchesExactCount() {
        assertEquals(Arrays.asList(1, 2), FitsMatch.matchKeyword("CD001002", "CD%3d%3d", 2));
        assertNull(FitsMatch.matchKeyword("CD1002", "CD%3d%3d", 2));
    }

    @Test
    void fitsTypeAcceptsHyphenAndUnderscore() {
        assertNotNull(FitsMatch.matchKeyword("OBSGEO-X", "OBSGEO%1f%1c", 0));
        assertNotNull(FitsMatch.matchKeyword("A_1", "A%1f%1d", 1));
    }
}
