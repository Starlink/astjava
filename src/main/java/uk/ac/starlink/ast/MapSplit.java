package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * The result of splitting a Mapping (C's {@code astMapSplit}): a Mapping from a chosen set of
 * inputs to the outputs they alone feed, and the number of the output of the original Mapping that
 * each of its outputs is. Also the base class's split, which each class's {@link
 * Mapping#splitOff} starts from or replaces.
 *
 * <p>Ported from C {@code mapping.c} ({@code MapSplit}) and Rust {@code mappings/helpers/mod.rs}
 * ({@code base_map_split}).
 */
final class MapSplit {

    /** For each output of {@link #map}, the output of the original Mapping it is, zero-based. */
    final int[] outputs;

    /** The split Mapping. */
    final Mapping map;

    /**
     * @param outputs for each output of {@code map}, the output of the original Mapping it is,
     *     zero-based
     * @param map the split Mapping
     */
    MapSplit(int[] outputs, Mapping map) {
        this.outputs = outputs;
        this.map = map;
    }

    /** Whether every index in {@code in} is an input, as each class's C {@code MapSplit} checks. */
    static boolean allInputs(int[] in, int nin) {
        for (int i : in) {
            if (i < 0 || i >= nin) {
                return false;
            }
        }
        return true;
    }

    /**
     * C's base-class {@code MapSplit} (mapping.c), with zero-based indices: a basic Mapping can be
     * split only by choosing every input, each once. The result is the Mapping itself if the inputs
     * are in order, and otherwise, after a PermMap putting them in order, the two simplified.
     *
     * @param map the Mapping to split
     * @param in the inputs chosen
     * @return the split, or null if there is none
     * @throws AstException with status {@code AST__AXIIN} for an input that does not exist
     */
    static MapSplit baseSplit(Mapping map, int[] in) {
        int nin = in.length;
        int mapnin = map.getNin();
        for (int iin = 0; iin < nin; iin++) {
            if (in[iin] < 0 || in[iin] >= mapnin) {
                throw Ast.err(
                        AstException.AST__AXIIN,
                        "astMapSplit(" + map.astClass() + "): One of the supplied Mapping input indices has value "
                                + (in[iin] + 1) + " which is invalid; it should be in the range 1 to " + mapnin
                                + ".");
            }
        }
        // C allocates the returned indices with astMalloc, which gives none for no elements.
        if (nin != mapnin || nin == 0) {
            return null;
        }
        // The inputs as chosen are the inverse permutation of a PermMap putting them in order;
        // the forward permutation must take each input once.
        int[] outperm = new int[nin];
        Arrays.fill(outperm, -1);
        for (int iin = 0; iin < nin; iin++) {
            int iout = in[iin];
            if (outperm[iout] != -1) {
                return null;
            }
            outperm[iout] = iin;
        }
        boolean perm = false;
        for (int iout = 0; iout < nin; iout++) {
            if (outperm[iout] != iout) {
                perm = true;
            }
        }
        int nout = map.getNout();
        int[] result = new int[nout];
        for (int iout = 0; iout < nout; iout++) {
            result[iout] = iout;
        }
        if (!perm) {
            return new MapSplit(result, map);
        }
        PermMap pm = PermMap.fromInternal(nin, in.clone(), nin, outperm, null);
        // C annuls its CmpMap once simplified, so the Mapping is not left shared.
        return new MapSplit(result, CmpMap.transientCmpMap(pm, map, true).simplify());
    }
}
