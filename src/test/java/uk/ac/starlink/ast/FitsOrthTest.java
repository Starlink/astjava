package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/orth.rs} (C {@code
 * OrthVector} and {@code OrthVectorSet}). The expected sets were captured from C by the Rust
 * repository's {@code tools/oracle_fixtures/orth_vector_set.c}.
 */
class FitsOrthTest {

    private static void assertSet(double[][] expected, double[][] actual, String label) {
        if (expected == null) {
            assertNull(actual, label);
            return;
        }
        assertNotNull(actual, label);
        assertEquals(expected.length, actual.length, label + ": row count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i].length, actual[i].length, label + " row " + i);
            for (int j = 0; j < expected[i].length; j++) {
                assertEquals(expected[i][j], actual[i][j], 1e-12, label + " [" + i + "][" + j + "]");
            }
        }
    }

    @Test
    void twoOfThreeBasisE0E1() {
        double[][] r = FitsOrth.orthVectorSet(3, 2, new double[][] {{1, 0, 0}, {0, 1, 0}});
        assertSet(new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, r, "2_of_3_basis");
    }

    @Test
    void oneOfTwoBasisE0() {
        double[][] r = FitsOrth.orthVectorSet(2, 1, new double[][] {{1, 0}});
        assertSet(new double[][] {{1, 0}, {0, 1}}, r, "1_of_2_basis");
    }

    @Test
    void twoOfThreeSkewed() {
        double[][] r = FitsOrth.orthVectorSet(3, 2, new double[][] {{1, 1, 0}, {1, -1, 0}});
        assertSet(new double[][] {{1, 1, 0}, {1, -1, 0}, {0, 0, 1}}, r, "2_of_3_skewed");
    }

    @Test
    void twoOfThreeParallelReturnsNull() {
        double[][] r = FitsOrth.orthVectorSet(3, 2, new double[][] {{1, 2, 3}, {2, 4, 6}});
        assertSet(null, r, "parallel");
    }

    @Test
    void oneOfThreeNonUnitPreservesInput() {
        double[][] r = FitsOrth.orthVectorSet(3, 1, new double[][] {{3, 0, 4}});
        assertSet(new double[][] {{3, 0, 4}, {0.8, 0, -0.6}, {0, 1, 0}}, r, "nonunit");
    }

    @Test
    void threeOfFourBasisCompletesToE3() {
        double[][] r = FitsOrth.orthVectorSet(4, 3, new double[][] {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}});
        assertSet(new double[][] {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}}, r, "3_of_4_basis");
    }

    /**
     * With no vectors supplied, C's {@code OrthVector} solves an empty system ({@code palDmat} with
     * no equations is not singular) and sets the component of the largest column to 1; the columns
     * all have zero magnitude, so the bubble sort leaves them in order and the set is the unit
     * basis. (The Rust test expects no set.)
     */
    @Test
    void emptyInputGivesTheUnitBasis() {
        double[][] r = FitsOrth.orthVectorSet(3, 0, new double[0][]);
        assertSet(new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, r, "empty");
    }

    @Test
    void orthVectorOrthogonalToInputSet() {
        double[][] inputs = {{1, 2, 3}};
        double[] v = FitsOrth.orthVector(3, 1, inputs);
        assertNotNull(v);
        double dot = 0.0;
        double len = 0.0;
        for (int i = 0; i < 3; i++) {
            dot += v[i] * inputs[0][i];
            len += v[i] * v[i];
        }
        assertEquals(0.0, dot, 1e-12);
        assertEquals(1.0, Math.sqrt(len), 1e-12);
    }

    /** C's {@code OrthVector} returns no vector when a supplied vector is missing. */
    @Test
    void orthVectorWithAMissingVectorReturnsNull() {
        assertNull(FitsOrth.orthVector(3, 2, new double[][] {{1, 0, 0}, null}));
    }
}
