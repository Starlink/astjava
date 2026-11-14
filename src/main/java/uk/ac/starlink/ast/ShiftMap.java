package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * A linear Mapping that shifts each axis by a constant: the forward transformation adds the shift
 * for each axis to its coordinate, and the inverse subtracts it. An axis whose shift is bad gives
 * bad output values.
 *
 * <p>Ported from Rust {@code mappings/shift.rs} and C {@code shiftmap.c}.
 */
public class ShiftMap extends Mapping {

    /** The shift for each axis. */
    private double[] shift;

    /**
     * Creates a ShiftMap.
     *
     * @param shift the value to add to each input coordinate; its length is the number of input
     *     and output coordinates
     */
    public ShiftMap(double[] shift) {
        super(shift.length, shift.length, true, true);
        this.shift = shift.clone();
    }

    /**
     * Loads a ShiftMap from its description, as C's {@code astLoadShiftMap} does: the shift for
     * axis {@code i} is item {@code Sft<i>}, zero if absent.
     */
    ShiftMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("ShiftMap");
        // C reads astGetNin, which is the same here, since a ShiftMap has as many outputs as inputs.
        int ncoord = createdNin();
        shift = new double[ncoord];
        for (int axis = 0; axis < ncoord; axis++) {
            shift[axis] = part.readDouble("Sft" + (axis + 1), 0.0);
        }
    }

    @Override
    String dumpComment() {
        return "Shift each coordinate axis";
    }

    /** Writes each shift, set when it is not zero, as C's {@code Dump} (shiftmap.c) does. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        for (int axis = 0; axis < shift.length; axis++) {
            node.writeDouble(
                    "Sft" + (axis + 1), shift[axis] != 0.0, false, shift[axis], "Shift for axis " + (axis + 1));
        }
    }

    /** Gives the copy its own shifts, as C's {@code Copy} (shiftmap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        shift = shift.clone();
    }

    @Override
    boolean isLinear() {
        return true;
    }

    /**
     * A copy of the shifts the ShiftMap applies forward in its current orientation, as C's {@code
     * astGetShifts} (shiftmap.c {@code GetShifts}) gives them: negated if it is inverted, a bad
     * shift included.
     */
    double[] getShifts() {
        double[] result = shift.clone();
        if (getInvert()) {
            for (int i = 0; i < result.length; i++) {
                result[i] *= -1;
            }
        }
        return result;
    }

    /**
     * Whether {@code that} is a ShiftMap equivalent to this one, as C's {@code Equal} (shiftmap.c)
     * decides it: the same numbers of inputs and outputs, and, if their Invert attributes are the
     * same, equal shifts, or, if not, shifts equal and opposite, each to within {@code astEQUAL}.
     */
    boolean equalTo(Mapping that) {
        if (!(that instanceof ShiftMap)) {
            return false;
        }
        ShiftMap other = (ShiftMap) that;
        int nin = getNin();
        if (other.getNin() != nin || other.getNout() != getNout()) {
            return false;
        }
        boolean same = getInvert() == other.getInvert();
        for (int i = 0; i < nin; i++) {
            double want = same ? other.shift[i] : -other.shift[i];
            if (!MappingHelpers.astEqual(shift[i], want)) {
                return false;
            }
        }
        return true;
    }

    /**
     * shiftmap.c {@code MapMerge}: the ShiftMap is replaced in the list by the equivalent WinMap,
     * applied forward, whose own merge ({@link WinMapMerge#winMapMerge}) then decides what changes.
     * If that changes nothing, or only turns the WinMap back into a ShiftMap equivalent to this one,
     * the ShiftMap is put back: shared and unchanged if the list applied it forward, or, if the list
     * applied it inverted, as a copy with its shifts negated, applied forward.
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        int nin = getNin();
        boolean oldInv = list.invert(where);
        double[] aa = new double[nin];
        double[] bb = new double[nin];
        for (int i = 0; i < nin; i++) {
            bb[i] = 1.0;
            aa[i] = oldInv ? -shift[i] : shift[i];
        }
        WinMap w1 = new WinMap(aa, bb);
        list.set(where, w1, false);
        int oldNmap = list.size();
        int result = Simplifier.dispatchMapMerge(w1, list, where, series);
        boolean revert = result == where && list.size() == oldNmap && sameAsThis(list.map(where));
        if (revert || result == -1) {
            if (oldInv) {
                ShiftMap sm = (ShiftMap) copy();
                for (int i = 0; i < nin; i++) {
                    sm.shift[i] *= -1.0;
                }
                list.set(where, sm, false);
                result = where;
            } else {
                list.set(where, this, false);
                result = -1;
            }
        }
        return result;
    }

    /**
     * C's {@code astEqual(map, this)}: the same object, or, through the {@code Equal} of {@code
     * map}'s class, which finds no Mapping of another class equal to a ShiftMap, an equivalent
     * ShiftMap.
     */
    // C compares the pointers first (object.c astEqual_): the same object is equal.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private boolean sameAsThis(Mapping map) {
        return map == this || (map instanceof ShiftMap && ((ShiftMap) map).equalTo(this));
    }

    /** The rate of change: one where the output is the input, zero elsewhere ({@code Rate}, shiftmap.c). */
    @Override
    double rateAt(double[] at, int ax1, int ax2) {
        return ax1 == ax2 ? 1.0 : 0.0;
    }

    /**
     * Splits off a ShiftMap of the chosen inputs' shifts, inverted if this one is, as C's {@code
     * MapSplit} (shiftmap.c) does; an input that does not exist gives no split.
     */
    @Override
    MapSplit splitOff(int[] in) {
        if (in.length == 0 || !MapSplit.allInputs(in, getNin())) {
            return null;
        }
        double[] shifts = new double[in.length];
        for (int i = 0; i < in.length; i++) {
            shifts[i] = shift[in[i]];
        }
        ShiftMap map = new ShiftMap(shifts);
        if (getInvert()) {
            map.invert();
        }
        return new MapSplit(in.clone(), map);
    }

    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        double sign = effectiveForward(forward) ? 1.0 : -1.0;
        for (int c = 0; c < in.length; c++) {
            double[] a = in[c];
            double[] o = out[c];
            if (shift[c] == AST__BAD) {
                // A bad shift makes its axis bad (shiftmap.c Transform).
                Arrays.fill(o, 0, npoint, AST__BAD);
                continue;
            }
            double d = sign * shift[c];
            for (int p = 0; p < npoint; p++) {
                double v = a[p];
                o[p] = isBad(v) ? AST__BAD : v + d;
            }
        }
    }

    /** NaN protocol: a plain addition, through which NaN propagates; a bad shift gives NaN. */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        double sign = effectiveForward(forward) ? 1.0 : -1.0;
        for (int c = 0; c < in.length; c++) {
            double[] a = in[c];
            double[] o = out[c];
            if (shift[c] == AST__BAD) {
                Arrays.fill(o, 0, npoint, Double.NaN);
                continue;
            }
            double d = sign * shift[c];
            for (int p = 0; p < npoint; p++) {
                o[p] = a[p] + d;
            }
        }
    }
}
