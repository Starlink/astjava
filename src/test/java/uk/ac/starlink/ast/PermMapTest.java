package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_permmap.rs}. Rust's {@code PermMap::new(inperm, outperm,
 * constants)} is jniast's constructor with the array lengths as the numbers of inputs and outputs,
 * and an empty constants array is C's NULL; {@code with_null_perms} is jniast's constructor given
 * a null permutation array.
 *
 * <p>Rust's {@code map_split} is jniast's {@code mapSplit}, with the outputs returned in an array. The
 * {@code perm_equal_*} tests test {@code astEqual}, which is outside the first phase. Rust's
 * {@code try_merge} of two PermMaps in parallel ({@code perm_parallel_merge}) is the simplification
 * of their parallel CmpMap, which C's {@code MapMerge} (permmap.c) merges. {@code
 * perm_map_identity_simplifies}, {@code perm_map_non_identity_not_simplified},
 * {@code perm_dimension_changing_merge_preserves_invertibility} and {@code
 * perm_map_keeping_leading_axes_simplifies_once} hold of a Mapping that is not restructured.
 *
 * <p>Rust's {@code null_permutation_constructor_checks_array_lengths} refuses an array longer than
 * the number of coordinates; jniast's constructor, like C's, reads only as many elements as there
 * are coordinates, so here only a short array is refused.
 */
class PermMapTest {

    private static final double BAD = AstObject.AST__BAD;

    /** Rust's {@code PermMap::new(inperm, outperm, constants)}. */
    private static PermMap perm(int[] inperm, int[] outperm, double... constants) {
        return new PermMap(inperm.length, inperm, outperm.length, outperm, constants.length == 0 ? null : constants);
    }

    @Test
    void permMapSwapAxesForward() {
        PermMap pm = perm(new int[] {2, 1}, new int[] {2, 1});
        assertEquals(2, pm.getNin());
        assertEquals(2, pm.getNout());
        assertArrayEquals(new double[] {7.0, 3.0}, point(transformRows(pm, true, new double[] {3.0, 7.0}), 0));
    }

    @Test
    void permMapConstantAxis() {
        PermMap pm = perm(new int[] {1}, new int[] {1, -1}, 42.0);
        assertEquals(1, pm.getNin());
        assertEquals(2, pm.getNout());
        assertArrayEquals(new double[] {5.0, 42.0}, point(transformRows(pm, true, new double[] {5.0}), 0));
    }

    @Test
    void permMapInverseUsesInperm() {
        PermMap pm = perm(new int[] {2, 1}, new int[] {2, 1});
        assertArrayEquals(new double[] {20.0, 10.0}, point(transformRows(pm, false, new double[] {10.0, 20.0}), 0));
    }

    @Test
    void permMapZeroEntryIsBad() {
        PermMap pm = perm(new int[] {1}, new int[] {1, 0});
        assertArrayEquals(new double[] {5.0, BAD}, point(transformRows(pm, true, new double[] {5.0}), 0));
    }

    @Test
    void permMapBadValuePropagation() {
        PermMap pm = perm(new int[] {2, 1}, new int[] {2, 1});
        assertArrayEquals(new double[] {7.0, BAD}, point(transformRows(pm, true, new double[] {BAD, 7.0}), 0));
    }

    @Test
    void permBadPerAxis() {
        // outperm [2, -1, 1]: out = (in2, constant 1, in1); inperm [3, 1, 0].
        PermMap pm = perm(new int[] {3, 1, 0}, new int[] {2, -1, 1}, 99.0);
        assertArrayEquals(
                new double[] {5.0, 99.0, BAD}, point(transformRows(pm, true, new double[] {BAD, 5.0, 7.0}), 0));
    }

    @Test
    void permMapIdentitySimplifies() {
        Mapping simplified = perm(new int[] {1, 2}, new int[] {1, 2}).simplify();
        assertArrayEquals(new double[] {4.0, 8.0}, point(transformRows(simplified, true, new double[] {4.0, 8.0}), 0));
    }

    @Test
    void permMapNonIdentityNotSimplified() {
        Mapping simplified = perm(new int[] {2, 1}, new int[] {2, 1}).simplify();
        assertArrayEquals(new double[] {2.0, 1.0}, point(transformRows(simplified, true, new double[] {1.0, 2.0}), 0));
    }

    @Test
    void permParallelMerge() {
        PermMap p1 = perm(new int[] {2, 1}, new int[] {2, 1});
        PermMap p2 = perm(new int[] {1}, new int[] {1});
        Mapping m = new CmpMap(p1, p2, false).simplify();
        assertInstanceOf(PermMap.class, m);
        assertEquals(3, m.getNin());
        assertEquals(3, m.getNout());
        assertArrayEquals(new double[] {2.0, 1.0, 3.0}, point(transformRows(m, true, new double[] {1.0, 2.0, 3.0}), 0));
    }

    /**
     * A PermMap is a UnitMap only if both its permutations are null; one whose forward permutation
     * is given as the null one but whose inverse swaps the axes is left as it is (permmap.c {@code
     * MapMerge}: {@code NullPerm} finds the given forward permutation already null).
     */
    @Test
    void permSimplifiedChecksBothPerms() {
        PermMap pm = perm(new int[] {2, 1}, new int[] {1, 2});
        assertFalse(pm.simplify() instanceof UnitMap);

        PermMap pm2 = perm(new int[] {1, 2}, new int[] {1, 2});
        assertInstanceOf(UnitMap.class, pm2.simplify());
    }

    /**
     * A null inverse permutation whose surplus input takes the first constant composes, in
     * permmap.c {@code MapMerge}, to the array it means; C then compares that with the held array
     * through a null pointer (permmap.c:1389), and the port reads the null array as {@code PERMVAL}
     * does, so the PermMap is found already simple and returned as it is.
     */
    @Test
    void aNullInversePermutationTakingAConstantIsLeftAsItIs() {
        PermMap pm = new PermMap(2, null, 1, new int[] {-1}, new double[] {9.0});
        assertSame(pm, pm.simplify());
    }

    /**
     * An inverted PermMap of three inputs and two outputs, applied forward within an inverted
     * CmpMap, has its forward permutation checked by {@code NullPerm} (permmap.c:1772-1774) over
     * more elements than it holds; the element beyond is taken as bad, as C's {@code SAFE_PERMVAL}
     * takes it, and simplifying does not change what the Mapping computes.
     */
    @Test
    void nullPermCheckOfAnInvertedPermMapStaysWithinItsArray() {
        PermMap pm = perm(new int[] {1, 2, 0}, new int[] {1, 2});
        pm.invert();
        CmpMap cmp = new CmpMap(pm, new ZoomMap(3, 2.0), true);
        cmp.invert();
        Mapping simp = cmp.simplify();
        double[] in = {1.0, 2.0, 3.0};
        assertArrayEquals(point(transformRows(cmp, true, in), 0), point(transformRows(simp, true, in), 0));
        double[] back = {4.0, 6.0};
        assertArrayEquals(point(transformRows(cmp, false, back), 0), point(transformRows(simp, false, back), 0));
    }

    // ------------------------------------------------------------------
    // Out-of-range entries: C's astInitPermMap (permmap.c) does not check the values, and
    // Transform gives AST__BAD for a negative entry with no constant and for a positive one past
    // the number of coordinates.
    // ------------------------------------------------------------------

    @Test
    void permNegativeEntryWithoutConstantIsBadNotRejected() {
        PermMap pm = perm(new int[] {-1}, new int[] {-1});
        assertEquals(BAD, transformRows(pm, true, new double[] {99.0})[0][0]);
        assertEquals(BAD, transformRows(pm, false, new double[] {99.0})[0][0]);
    }

    @Test
    void permNegativeEntryPastConstantsPoolIsBad() {
        PermMap pm = perm(new int[] {-2}, new int[] {-2}, 5.0);
        assertEquals(BAD, transformRows(pm, true, new double[] {99.0})[0][0]);
    }

    @Test
    void permPositiveEntryPastAxisCountIsBad() {
        PermMap pm = perm(new int[] {1}, new int[] {2});
        assertEquals(BAD, transformRows(pm, true, new double[] {7.0})[0][0]);
    }

    @Test
    void permCDumpWithNegativeEntryNoNconstRoundtrips() {
        String text = ChannelText.dump(perm(new int[] {-1}, new int[] {-1}));
        PermMap reread = assertInstanceOf(PermMap.class, ChannelText.read(text));
        assertEquals(BAD, transformRows(reread, true, new double[] {3.0})[0][0]);
    }

    @Test
    void permDimensionChangingMergePreservesInvertibility() {
        // 2 -> 3: out = (in1, in2, in2); the inverse takes in2 from out3.
        PermMap pm = perm(new int[] {1, 3}, new int[] {1, 2, 2});
        CmpMap z01 = new CmpMap(new ZoomMap(1, 1.0e-26), new ZoomMap(1, 2.0), false);
        CmpMap scale = new CmpMap(z01, new ZoomMap(1, 3.0), false);
        Mapping simp = new CmpMap(pm, scale, true).simplify();
        double[][] fwd = transformRows(simp, true, new double[] {5.0, 7.0});
        double[][] back = simp.transform(fwd, 1, false);
        assertEquals(5.0, back[0][0], 1.0e-12);
        assertEquals(7.0, back[1][0], 1.0e-12);
    }

    // ------------------------------------------------------------------
    // PermSplit
    // ------------------------------------------------------------------

    @Test
    void permSplitDefaultIsNormalMethod() {
        PermMap pm = perm(new int[] {2, 1, -1}, new int[] {2, 1}, 99.0);
        assertFalse(pm.getPermSplit());
        assertFalse(pm.testPermSplit());
    }

    @Test
    void permSplitRoundtripsThroughNativeDump() {
        PermMap pm = perm(new int[] {2, 1, -1}, new int[] {2, 1}, 99.0);
        pm.setPermSplit(true);
        String text = ChannelText.dump(pm);
        assertTrue(text.contains("pmsplt"), "dump must carry pmsplt: " + text);
        PermMap reread = assertInstanceOf(PermMap.class, ChannelText.read(text));
        assertTrue(reread.getPermSplit());
        assertEquals(text, ChannelText.dump(reread));
        // It still drives the alternative split after a round trip.
        int[] outs = new int[2];
        Mapping sub = reread.mapSplit(new int[] {1, 2}, outs);
        assertInstanceOf(UnitMap.class, sub, "split after reload");
        assertArrayEquals(new int[] {2, 1}, outs);
    }

    @Test
    void permSplitAlternativeUsesInperm() {
        PermMap pm = perm(new int[] {2, 1, -1}, new int[] {2, 1}, 99.0);
        pm.setPermSplit(true);
        assertTrue(pm.getPermSplit());

        int[] outs = new int[2];
        Mapping sub = pm.mapSplit(new int[] {1, 2}, outs);
        assertInstanceOf(UnitMap.class, sub);
        assertEquals(2, sub.getNin());
        assertEquals(2, sub.getNout());
        assertArrayEquals(new int[] {2, 1}, outs);

        outs = new int[2];
        sub = pm.mapSplit(new int[] {1}, outs);
        assertInstanceOf(UnitMap.class, sub);
        assertEquals(1, sub.getNin());
        assertEquals(2, outs[0]);

        sub = pm.mapSplit(new int[] {2}, outs);
        assertInstanceOf(UnitMap.class, sub);
        assertEquals(1, outs[0]);
    }

    @Test
    void permSplitAlternativeRejectsConstantFedInput() {
        PermMap pm = perm(new int[] {2, 1, -1}, new int[] {2, 1}, 99.0);
        pm.setPermSplit(true);
        assertNull(pm.mapSplit(new int[] {3}, new int[2]));
    }

    @Test
    void permSplitAlternativeRejectsDuplicateOutput() {
        PermMap pm = perm(new int[] {1, 1}, new int[] {1});
        pm.setPermSplit(true);
        assertNull(pm.mapSplit(new int[] {1, 2}, new int[1]));
        int[] outs = new int[1];
        Mapping sub = pm.mapSplit(new int[] {1}, outs);
        assertInstanceOf(UnitMap.class, sub);
        assertEquals(1, outs[0]);
    }

    // ------------------------------------------------------------------
    // Rate
    // ------------------------------------------------------------------

    /** Analytical rate for PermMap: 1 if the output takes the input, else 0 (permmap.c {@code Rate}). */
    @Test
    void permRateAnalytical() {
        PermMap pm = perm(new int[] {2, 1}, new int[] {2, 1});
        double[] at = {0.0, 0.0};
        assertEquals(0.0, pm.rate(at, 1, 1));
        assertEquals(1.0, pm.rate(at, 1, 2));
        assertEquals(1.0, pm.rate(at, 2, 1));
        assertEquals(0.0, pm.rate(at, 2, 2));
    }

    /** An inverted PermMap's rate uses the inverse permutation. */
    @Test
    void permRateAnalyticalInverted() {
        PermMap pm = perm(new int[] {2, 1}, new int[] {2, 1});
        pm.invert();
        double[] at = {0.0, 0.0};
        assertEquals(0.0, pm.rate(at, 1, 1));
        assertEquals(1.0, pm.rate(at, 1, 2));
        assertEquals(1.0, pm.rate(at, 2, 1));
        assertEquals(0.0, pm.rate(at, 2, 2));
    }

    /** A null forward permutation gives a rate of one where the output and input numbers agree. */
    @Test
    void aNullPermutationRateIsUnitOnTheDiagonal() {
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, null, null);
        double[] at = {0.0, 0.0};
        assertEquals(1.0, pm.rate(at, 1, 1));
        assertEquals(0.0, pm.rate(at, 1, 2));
        pm.invert();
        assertEquals(0.0, pm.rate(at, 1, 1));
        assertEquals(1.0, pm.rate(at, 1, 2));
    }

    /**
     * A PermMap that keeps the first two of three axes is already simple, as C finds it, so
     * simplifying it again changes nothing and both arrays are kept as given.
     */
    @Test
    void permMapKeepingLeadingAxesSimplifiesOnce() {
        PermMap pm = perm(new int[] {1, 2, 0}, new int[] {1, 2});
        Mapping once = pm.simplify();
        Mapping twice = once.simplify();
        String text = ChannelText.dump(once);
        assertTrue(text.contains("Out2 = 2") && text.contains("In3 = 0") && !text.contains("Cpy"), text);
        assertEquals(text, ChannelText.dump(twice));
    }

    /**
     * A PermMap built with a null inverse or forward array dumps it as {@code InCpy} or {@code
     * OutCpy}, byte for byte as C's {@code astPermMap} with a NULL array does, transforms as the
     * null permutation, and reads back the same.
     */
    @Test
    void nullPermutationDumpsAsCpyLikeC() {
        String cOneNull = ChannelText.text(
                " Begin PermMap \t# Coordinate permutation",
                "    Nin = 2 \t# Number of input coordinates",
                "    Nout = 3 \t# Number of output coordinates",
                " IsA Mapping \t# Mapping between coordinate systems",
                "    Out1 = 2 \t# Output coordinate 1 = input coordinate 2",
                "    Out2 = 1 \t# Output coordinate 2 = input coordinate 1",
                "    Out3 = -1 \t# Output coordinate 3 = constant no. 1",
                "    InCpy = 1 \t# Input coordinates = output coordinates",
                "    Nconst = 1 \t# Number of constants",
                "    Con1 = 7.5 \t# Constant number 1",
                " End PermMap");
        String cBothNull = ChannelText.text(
                " Begin PermMap \t# Coordinate permutation",
                "    Nin = 3 \t# Number of input coordinates",
                "    Nout = 2 \t# Number of output coordinates",
                " IsA Mapping \t# Mapping between coordinate systems",
                "    OutCpy = 1 \t# Output coordinates = input coordinates",
                "    InCpy = 1 \t# Input coordinates = output coordinates",
                " End PermMap");

        PermMap p1 = new PermMap(2, null, 3, new int[] {2, 1, -1}, new double[] {7.5});
        assertEquals(2, p1.getNin());
        assertEquals(3, p1.getNout());
        // C probe: forward (1, 2) -> (2, 1, 7.5); inverse (5, 6, 7) -> (5, 6).
        assertArrayEquals(new double[] {2.0, 1.0, 7.5}, point(transformRows(p1, true, new double[] {1.0, 2.0}), 0));
        assertArrayEquals(new double[] {5.0, 6.0}, point(transformRows(p1, false, new double[] {5.0, 6.0, 7.0}), 0));
        assertEquals(cOneNull, ChannelText.dump(p1));

        PermMap p2 = new PermMap(3, null, 2, null, null);
        assertArrayEquals(new double[] {1.0, 2.0}, point(transformRows(p2, true, new double[] {1.0, 2.0, 3.0}), 0));
        assertArrayEquals(new double[] {4.0, 5.0, BAD}, point(transformRows(p2, false, new double[] {4.0, 5.0}), 0));
        assertEquals(cBothNull, ChannelText.dump(p2));

        for (String text : new String[] {cOneNull, cBothNull}) {
            assertEquals(text, ChannelText.dump(ChannelText.read(text)));
        }
    }

    /**
     * A null forward permutation with more outputs than inputs gives the surplus outputs the
     * first constant, when there are constants: C's {@code PERMVAL} (permmap.c) gives -1 for them,
     * which {@code Transform} reads as constant 1.
     */
    @Test
    void aNullForwardPermutationGivesSurplusOutputsTheFirstConstant() {
        PermMap pm = new PermMap(1, new int[] {-1}, 2, null, new double[] {9.0});
        assertArrayEquals(new double[] {5.0, 9.0}, point(transformRows(pm, true, new double[] {5.0}), 0));
        PermMap noConstants = new PermMap(1, null, 2, null, null);
        assertArrayEquals(new double[] {5.0, BAD}, point(transformRows(noConstants, true, new double[] {5.0}), 0));
    }

    /** A supplied array must have an entry for each coordinate it describes. */
    @Test
    void nullPermutationConstructorChecksArrayLengths() {
        assertEquals(
                AstException.AST__BADNI,
                assertThrows(AstException.class, () -> new PermMap(2, new int[] {1}, 2, null, null))
                        .getStatus());
        assertEquals(
                AstException.AST__BADNO,
                assertThrows(AstException.class, () -> new PermMap(2, null, 2, new int[] {1}, null))
                        .getStatus());
    }

    // ------------------------------------------------------------------
    // Equality (permmap.c Equal compares the constants each permutation selects)
    // ------------------------------------------------------------------

    @Test
    void permEqualSameConstantIndexDifferingValues() {
        assertFalse(perm(new int[] {-1}, new int[] {1}, 5.0).astEqual(perm(new int[] {-1}, new int[] {1}, 7.0)));
    }

    @Test
    void permEqualSameConstantIndexSameValue() {
        assertTrue(perm(new int[] {-1}, new int[] {1}, 5.0).astEqual(perm(new int[] {-1}, new int[] {1}, 5.0)));
    }

    @Test
    void permEqualDifferentIndexSameReferencedValue() {
        assertTrue(
                perm(new int[] {-1}, new int[] {1}, 9.0, 0.0).astEqual(perm(new int[] {-2}, new int[] {1}, 0.0, 9.0)));
    }

    @Test
    void permEqualDifferentIndexDifferentReferencedValue() {
        assertFalse(
                perm(new int[] {-1}, new int[] {1}, 9.0, 0.0).astEqual(perm(new int[] {-2}, new int[] {1}, 0.0, 4.0)));
    }

    @Test
    void permEqualIgnoresUnreferencedSurplusConstants() {
        assertTrue(perm(new int[] {1}, new int[] {1}, 1.0).astEqual(perm(new int[] {1}, new int[] {1}, 2.0)));
    }
}
