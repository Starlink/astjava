package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * The merges of a Region with its neighbours in a list of Mappings: the {@code MapMerge} method
 * that box.c, interval.c, nullregion.c and pointlist.c each define, and the functions they merge
 * two Regions in parallel with ({@code MergeBox}, {@code MergeInterval}, {@code MergeNullRegion}
 * and {@code MergePointList}), from Rust {@code regions/merge_util.rs} and {@code
 * mappings/simplify.rs} {@code region_map_merge}.
 *
 * <p>Two neighbouring Regions applied in parallel merge into one Region of their combined axes
 * when C's classes know how: two Boxes give a Box; a Box or an Interval with an Interval, a Box,
 * or a negated NullRegion (which covers everything) gives an Interval; a Box or Interval that is a
 * point with a PointList gives a PointList; two PointLists of one point each give a PointList; and
 * two NullRegions with the same Negated value give a NullRegion.
 */
final class RegionMerge {

    private RegionMerge() {}

    /** One class's function merging a Region with another in parallel. */
    @FunctionalInterface
    interface Merger {

        /**
         * The Region of the combined axes of the Region and {@code reg}, or null if the two cannot
         * be merged.
         *
         * @param reg the other Region
         * @param thisFirst whether the Region's axes come first in the result; otherwise those of
         *     {@code reg} do
         */
        Region merge(Region reg, boolean thisFirst);
    }

    /**
     * The {@code MapMerge} method of box.c, interval.c, nullregion.c and pointlist.c, which differ
     * only in the function merging two Regions: the Region at {@code where} is replaced by its
     * simplification if that is a different object (C compares the pointers); failing that, and
     * only in parallel, it is merged with the Region before it, its axes last, or failing that
     * with the Region after it, its axes first, the two entries being replaced by the result.
     *
     * @return the index of the first entry changed, or -1 if the list is unchanged
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    static int mapMerge(Region self, MergeList list, int where, boolean series, Merger merger) {
        Mapping simple = self.simplify();
        if (simple != self) {
            list.set(where, simple, false);
            return where;
        }
        if (series) {
            return -1;
        }
        Region merged = null;
        int i1 = 0;
        if (where > 0) {
            i1 = where - 1;
            Mapping map = list.map(where - 1);
            if (map instanceof Region) {
                merged = merger.merge((Region) map, false);
            }
        }
        if (merged == null && where < list.size() - 1) {
            i1 = where;
            Mapping map = list.map(where + 1);
            if (map instanceof Region) {
                merged = merger.merge((Region) map, true);
            }
        }
        if (merged == null) {
            return -1;
        }
        list.replacePairWith(i1, merged, false);
        return i1;
    }

    // ------------------------------------------------------------------
    // The merges of each class
    // ------------------------------------------------------------------

    /**
     * C's {@code MergeBox} (box.c): a Box and another Region in parallel. Neither may be negated;
     * another Box gives a Box; an Interval gives an Interval; a negated NullRegion gives an
     * Interval with no limits on its axes; and a PointList gives a PointList if the Box is a
     * point, its half-width on each axis being no more than a quarter of the width of its
     * uncertainty there.
     */
    static Region mergeBox(Box self, Region reg, boolean boxFirst) {
        if (!compatible(self, reg)) {
            return null;
        }
        boolean negThis = self.getNegated();
        boolean negReg = reg.getNegated();
        int naxReg = reg.getNaxes();
        int naxThis = self.getNaxes();
        int naxNew = naxReg + naxThis;
        Frame bfrm = combined(self.baseFrame(), reg.baseFrame(), boxFirst);
        Region nw = null;
        if (reg instanceof Box && !negThis && !negReg) {
            double[] centre = new double[naxNew];
            double[] corner = new double[naxNew];
            if (boxFirst) {
                boxPoints(self, centre, corner, 0);
                boxPoints(reg, centre, corner, naxThis);
            } else {
                boxPoints(reg, centre, corner, 0);
                boxPoints(self, centre, corner, naxReg);
            }
            nw = new Box(bfrm, 0, centre, corner, null);
        } else if (reg instanceof Interval && !negThis && !negReg) {
            double[] lbnd = new double[naxNew];
            double[] ubnd = new double[naxNew];
            int off = boxFirst ? 0 : naxReg;
            boxBounds(self, lbnd, ubnd, off);
            intervalPoints(reg, lbnd, ubnd, boxFirst ? naxThis : 0);
            nw = new Interval(bfrm, lbnd, ubnd, null);
        } else if (reg instanceof NullRegion && !negThis && negReg) {
            double[] lbnd = new double[naxNew];
            double[] ubnd = new double[naxNew];
            int off = boxFirst ? 0 : naxReg;
            boxBounds(self, lbnd, ubnd, off);
            int from = boxFirst ? naxThis : 0;
            int to = boxFirst ? naxNew : naxReg;
            for (int i = from; i < to; i++) {
                lbnd[i] = AstObject.AST__BAD;
                ubnd[i] = AstObject.AST__BAD;
            }
            nw = new Interval(bfrm, lbnd, ubnd, null);
        } else if (reg instanceof PointList && !negThis && !negReg) {
            double[] centre = new double[naxThis];
            double[] corner = new double[naxThis];
            boxPoints(self, centre, corner, 0);
            double[] lbnd = new double[naxThis];
            double[] ubnd = new double[naxThis];
            self.getUncFrm(FrameSet.AST__BASE).regionBounds(lbnd, ubnd);
            boolean ok = true;
            for (int i = 0; i < naxThis; i++) {
                if (Math.abs(centre[i] - corner[i]) > 0.25 * Math.abs(ubnd[i] - lbnd[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                nw = pointListWith(bfrm, (PointList) reg, centre, naxThis, boxFirst);
            }
        }
        return nw == null ? null : finish(self, reg, nw, boxFirst, false);
    }

    /**
     * C's {@code MergeInterval} (interval.c): an Interval and another Region in parallel. Neither
     * may be negated; another Interval or a Box gives an Interval; a negated NullRegion gives an
     * Interval with no limits on its axes; and a PointList gives a PointList if the Interval is a
     * point, its half-width on each axis being no more than a quarter of the width of its
     * uncertainty there, the PointList's points taking the Interval's centre.
     */
    static Region mergeInterval(Interval self, Region reg, boolean intFirst) {
        if (!compatible(self, reg)) {
            return null;
        }
        boolean negThis = self.getNegated();
        boolean negReg = reg.getNegated();
        int naxReg = reg.getNaxes();
        int naxThis = self.getNaxes();
        int naxNew = naxReg + naxThis;
        Frame bfrm = combined(self.baseFrame(), reg.baseFrame(), intFirst);
        Region nw = null;
        if (reg instanceof Interval && !negThis && !negReg) {
            double[] lbnd = new double[naxNew];
            double[] ubnd = new double[naxNew];
            if (intFirst) {
                intervalPoints(self, lbnd, ubnd, 0);
                intervalPoints(reg, lbnd, ubnd, naxThis);
            } else {
                intervalPoints(reg, lbnd, ubnd, 0);
                intervalPoints(self, lbnd, ubnd, naxReg);
            }
            nw = new Interval(bfrm, lbnd, ubnd, null);
        } else if (reg instanceof Box && !negThis && !negReg) {
            double[] lbnd = new double[naxNew];
            double[] ubnd = new double[naxNew];
            intervalPoints(self, lbnd, ubnd, intFirst ? 0 : naxReg);
            boxBounds(reg, lbnd, ubnd, intFirst ? naxThis : 0);
            nw = new Interval(bfrm, lbnd, ubnd, null);
        } else if (reg instanceof NullRegion && !negThis && negReg) {
            double[] lbnd = new double[naxNew];
            double[] ubnd = new double[naxNew];
            if (intFirst) {
                intervalPoints(self, lbnd, ubnd, 0);
                for (int i = naxThis; i < naxNew; i++) {
                    lbnd[i] = AstObject.AST__BAD;
                    ubnd[i] = AstObject.AST__BAD;
                }
            } else {
                for (int i = 0; i < naxReg; i++) {
                    lbnd[i] = AstObject.AST__BAD;
                    ubnd[i] = AstObject.AST__BAD;
                }
                intervalPoints(self, lbnd, ubnd, naxReg);
            }
            nw = new Interval(bfrm, lbnd, ubnd, null);
        } else if (reg instanceof PointList && !negThis && !negReg) {
            double[] lbnd = new double[naxThis];
            double[] ubnd = new double[naxThis];
            self.regBaseBox(lbnd, ubnd);
            double[] lbndUnc = new double[naxThis];
            double[] ubndUnc = new double[naxThis];
            self.getUncFrm(FrameSet.AST__BASE).regionBounds(lbndUnc, ubndUnc);
            boolean ok = true;
            for (int i = 0; i < naxThis; i++) {
                if (0.5 * Math.abs(ubnd[i] - lbnd[i]) > 0.25 * Math.abs(ubndUnc[i] - lbndUnc[i])) {
                    ok = false;
                    break;
                } else {
                    lbnd[i] = 0.5 * (lbnd[i] + ubnd[i]);
                }
            }
            if (ok) {
                nw = pointListWith(bfrm, (PointList) reg, lbnd, naxThis, intFirst);
            }
        }
        return nw == null ? null : finish(self, reg, nw, intFirst, false);
    }

    /**
     * C's {@code MergeNullRegion} (nullregion.c): two NullRegions in parallel with the same
     * Negated value give a NullRegion with that value.
     */
    static Region mergeNullRegion(NullRegion self, Region reg, boolean thisFirst) {
        if (!compatible(self, reg)) {
            return null;
        }
        boolean negThis = self.getNegated();
        Frame bfrm = combined(self.baseFrame(), reg.baseFrame(), thisFirst);
        if (!(reg instanceof NullRegion) || negThis != reg.getNegated()) {
            return null;
        }
        return finish(self, reg, new NullRegion(bfrm, null), thisFirst, negThis);
    }

    /**
     * C's {@code MergePointList} (pointlist.c): two PointLists in parallel, each of one point and
     * with the same Negated value, give a PointList of one point with that value.
     */
    static Region mergePointList(PointList self, Region reg, boolean thisFirst) {
        if (!compatible(self, reg)) {
            return null;
        }
        boolean negThis = self.getNegated();
        boolean negReg = reg.getNegated();
        if (!(reg instanceof PointList)
                || negThis != negReg
                || self.getListSize() != 1
                || ((PointList) reg).getListSize() != 1) {
            return null;
        }
        int naxReg = reg.getNaxes();
        int naxThis = self.getNaxes();
        int naxNew = naxReg + naxThis;
        Frame bfrm = combined(self.baseFrame(), reg.baseFrame(), thisFirst);
        double[][] ptrThis = self.getPoints();
        double[][] ptrReg = reg.getPoints();
        double[][] ptrNew = new double[naxNew][1];
        if (thisFirst) {
            for (int i = 0; i < naxThis; i++) {
                ptrNew[i][0] = ptrThis[i][0];
            }
            for (int i = naxThis; i < naxNew; i++) {
                ptrNew[i][0] = ptrReg[i - naxThis][0];
            }
        } else {
            for (int i = 0; i < naxReg; i++) {
                ptrNew[i][0] = ptrReg[i][0];
            }
            for (int i = naxReg; i < naxNew; i++) {
                ptrNew[i][0] = ptrThis[i - naxReg][0];
            }
        }
        return finish(self, reg, new PointList(bfrm, ptrNew, null), thisFirst, negThis);
    }

    // ------------------------------------------------------------------
    // What the merges share
    // ------------------------------------------------------------------

    /**
     * Whether two Regions may be merged at all: they must have the same Closed value, and either
     * both or neither must have an uncertainty set.
     */
    private static boolean compatible(Region self, Region reg) {
        return self.getClosed() == reg.getClosed() && self.testUnc() == reg.testUnc();
    }

    /** A CmpFrame of the two Frames, those of the Region merging first if {@code thisFirst}. */
    private static Frame combined(Frame frmThis, Frame frmReg, boolean thisFirst) {
        return thisFirst ? new CmpFrame(frmThis, frmReg) : new CmpFrame(frmReg, frmThis);
    }

    /** C's {@code astBoxPoints}: the centre and corner of a Box, stored from index {@code off}. */
    private static void boxPoints(Region box, double[] centre, double[] corner, int off) {
        double[][] ptr = box.getPoints();
        for (int i = 0; i < ptr.length; i++) {
            centre[off + i] = ptr[i][0];
            corner[off + i] = ptr[i][1];
        }
    }

    /**
     * The limits of a Box on each axis, stored from index {@code off}: its centre and corner
     * ({@link #boxPoints}) turned into the lower and upper bounds, as C's merges do.
     */
    // The swap of two limits looks like a copy.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    private static void boxBounds(Region box, double[] lbnd, double[] ubnd, int off) {
        boxPoints(box, lbnd, ubnd, off);
        int n = box.getNaxes();
        for (int i = off; i < off + n; i++) {
            lbnd[i] = 2 * lbnd[i] - ubnd[i];
            if (lbnd[i] > ubnd[i]) {
                double temp = lbnd[i];
                lbnd[i] = ubnd[i];
                ubnd[i] = temp;
            }
        }
    }

    /** C's {@code astIntervalPoints}: the limits of an Interval, stored from index {@code off}. */
    private static void intervalPoints(Region interval, double[] lbnd, double[] ubnd, int off) {
        double[][] ptr = interval.getPoints();
        for (int i = 0; i < ptr.length; i++) {
            lbnd[off + i] = ptr[i][0];
            ubnd[off + i] = ptr[i][1];
        }
    }

    /**
     * The PointList of the points of {@code reg} with the {@code naxThis} values {@code values}
     * attached to each, on the axes coming first if {@code thisFirst} and last otherwise.
     */
    private static PointList pointListWith(Frame bfrm, PointList reg, double[] values, int naxThis, boolean thisFirst) {
        double[][] ptrReg = reg.getPoints();
        int naxReg = ptrReg.length;
        int npnt = ptrReg[0].length;
        double[][] ptrNew = new double[naxReg + naxThis][npnt];
        for (int j = 0; j < naxReg; j++) {
            System.arraycopy(ptrReg[j], 0, ptrNew[thisFirst ? naxThis + j : j], 0, npnt);
        }
        for (int j = 0; j < naxThis; j++) {
            Arrays.fill(ptrNew[thisFirst ? j : naxReg + j], values[j]);
        }
        return new PointList(bfrm, ptrNew, null);
    }

    /**
     * The tail C's merges share: the merged Region {@code nw}, made in the combined base Frame,
     * takes the Negated, Closed, RegionFS, Adaptive, MeshSize and FillFactor values of {@code
     * self} ({@link Region#regOverlay}); Negated is then set if {@code negated} and cleared
     * otherwise; if both Regions have an uncertainty set, it gets the simplification of the Prism
     * of the two in the base Frame; and it is mapped into the combined current Frame by the two
     * Regions' Mappings in parallel. The result's FillFactor is the product of the two Regions'
     * if either is set, and its MeshSize, if either Region's is set, is its default scaled by the
     * product of the ratios of each Region's MeshSize to its default, truncated to an integer as
     * C's conversion to {@code int} does.
     */
    private static Region finish(Region self, Region reg, Region nw, boolean thisFirst, boolean negated) {
        nw.regOverlay(self, true);
        if (negated) {
            nw.setNegatedValue(true);
        } else {
            nw.clearNegated();
        }
        if (self.testUnc() && reg.testUnc()) {
            Region uncThis = self.getUncFrm(FrameSet.AST__BASE);
            Region uncReg = reg.getUncFrm(FrameSet.AST__BASE);
            Prism bunc = thisFirst ? new Prism(uncThis, uncReg) : new Prism(uncReg, uncThis);
            nw.setUnc((Region) bunc.simplify());
        }
        Frame cfrm = combined(self.regFrame(), reg.regFrame(), thisFirst);
        Mapping mapThis = self.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Mapping mapReg = reg.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Mapping bcmap = thisFirst ? new CmpMap(mapThis, mapReg, false) : new CmpMap(mapReg, mapThis, false);
        Region result = nw.mapRegionInto(bcmap, cfrm);
        if (reg.testFillFactor() || self.testFillFactor()) {
            result.setFillFactorValue(reg.getFillFactor() * self.getFillFactor());
        }
        boolean mszThisSet = self.testMeshSize();
        boolean mszRegSet = reg.testMeshSize();
        if (mszThisSet || mszRegSet) {
            int mszThis = self.getMeshSize();
            int mszReg = reg.getMeshSize();
            self.clearMeshSize();
            reg.clearMeshSize();
            double facThis = (double) mszThis / self.getMeshSize();
            double facReg = (double) mszReg / reg.getMeshSize();
            result.setMeshSizeValue((int) (facThis * facReg * result.getMeshSize()));
            if (mszThisSet) {
                self.setMeshSizeValue(mszThis);
            }
            if (mszRegSet) {
                reg.setMeshSizeValue(mszReg);
            }
        }
        return result;
    }
}
