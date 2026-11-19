package uk.ac.starlink.ast;

/**
 * The generic overlap test of two Regions: region.c {@code OverlapX}, which a Region's {@link
 * Region#overlapX} uses unless its class has its own, and region.c {@code BndBaseMesh}, the
 * boundary mesh of the part of a Region inside a box, which {@code OverlapX} uses for a Region
 * with no finite boundary.
 *
 * <p>C's {@code astOverlap( this, that )} is two per-class methods: {@code Overlap}, dispatched
 * on the class of {@code this} ({@link Region#overlapWith}), whose default calls {@code OverlapX(
 * that, this )}, dispatched on the class of {@code that} ({@link Region#overlapX}), whose default
 * is {@link #overlap} here.
 *
 * <p>Ported from C {@code region.c} ({@code OverlapX}, {@code BndBaseMesh}, {@code GetSubMesh}),
 * with Rust {@code regions/overlap.rs}.
 */
final class RegionOverlap {

    private RegionOverlap() {}

    /**
     * C's generic {@code OverlapX( b, a )}: how {@code a} and {@code b} overlap, one of the {@code
     * Region.OVERLAP_*} values, which is what {@code astOverlap( a, b )} returns.
     *
     * <p>Equal Regions give 5, and a Region equal to the other once negated gives 6; {@code a} is
     * negated in place for that test and negated back, as C does, which leaves its Negated
     * attribute set. Regions whose Frames cannot be converted give 0. Otherwise the boundary mesh
     * of one Region is taken into the other's Frame: if every mesh point is on the other's
     * boundary, to within their uncertainties, the Regions are the same or each other's negation;
     * otherwise the mesh points off the boundary are tested against the other Region, and the
     * other's boundary mesh against the first Region.
     *
     * @throws AstException with status {@code AST__INTER} if neither Region has a finite boundary,
     *     or the first Region tried has none and no part of its boundary can be meshed
     */
    static int overlap(Region a, Region b) {
        Region thisRegion = a;
        Region that = b;

        if (thisRegion.astEqual(that)) {
            return Region.OVERLAP_SAME;
        }
        boolean negEqual;
        thisRegion.negate();
        try {
            negEqual = thisRegion.astEqual(that);
        } finally {
            thisRegion.negate();
        }
        if (negEqual) {
            return Region.OVERLAP_NEGATE;
        }

        // The Mapping from the current Frame of "that" to the current Frame of "this".
        FrameSet fs0 = that.convert(thisRegion, "");
        if (fs0 == null) {
            return Region.OVERLAP_UNKNOWN;
        }
        Mapping cmap = fs0.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);

        // Mesh the boundary of reg2 and take it into the base Frame of reg1; if reg2 has no
        // mesh, or none of its points survive, try once more with the Regions swapped.
        Region reg1 = thisRegion;
        Region reg2 = that;
        boolean reg2IsThat = true;
        boolean first = true;
        double[][] reg2Mesh = null;
        double[][] ps1 = null;
        Mapping mapReg1 = null;
        Mapping map = null;
        double[] lbnd = null;
        double[] ubnd = null;
        while (true) {
            double[][] mesh;
            try {
                mesh = reg2.regMesh();
            } catch (AstException e) {
                // C suppresses the error, which means no mesh.
                mesh = null;
            }
            boolean good = false;
            if (mesh != null) {
                mapReg1 = reg1.getRegFS().getMapping(FrameSet.AST__CURRENT, FrameSet.AST__BASE);
                map = new CmpMap(cmap, mapReg1, true);
                ps1 = map.transform(mesh, mesh[0].length, true);
                int nc = ps1.length;
                lbnd = new double[nc];
                ubnd = new double[nc];
                for (int i = 0; i < nc; i++) {
                    lbnd[i] = Double.MAX_VALUE;
                    ubnd[i] = -Double.MAX_VALUE;
                    for (double v : ps1[i]) {
                        if (v != AstObject.AST__BAD) {
                            good = true;
                            if (v > ubnd[i]) {
                                ubnd[i] = v;
                            }
                            if (v < lbnd[i]) {
                                lbnd[i] = v;
                            }
                        }
                    }
                }
            }
            if (good) {
                reg2Mesh = mesh;
                break;
            }
            if (!first) {
                break;
            }
            reg1 = that;
            reg2 = thisRegion;
            reg2IsThat = false;
            cmap = (Mapping) cmap.copy();
            cmap.invert();
            first = false;
        }
        if (reg2Mesh == null) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astOverlap(Region): Neither of the two supplied Regions (classes " + thisRegion.astClass()
                            + " and " + that.astClass() + ") has a finite boundary.\nThe current implementation of"
                            + " astOverlap cannot determine the overlap between two Regions unless at least one"
                            + " of them has a finite boundary.");
        }
        int nc = ps1.length;
        int np = ps1[0].length;

        // The uncertainty of reg2, in the base Frame of reg1.
        Region unc = reg2.getUncFrm(FrameSet.AST__CURRENT);
        Region unc1 = unc.mapRegionInto(map, reg1.getRegFS().getFrame(FrameSet.AST__BASE));

        boolean[] mask = new boolean[np];
        if (reg1.regPins(ps1, unc1, mask)) {
            // The boundaries are equivalent, so the Regions are the same or each other's
            // negation: the mean of the pin positions is inside both or outside both if the same.
            double[][] ps4 = new double[nc][1];
            for (int i = 0; i < nc; i++) {
                int ngood = 0;
                double axsum = 0.0;
                for (int j = 0; j < np; j++) {
                    if (ps1[i][j] != AstObject.AST__BAD) {
                        ngood++;
                        axsum += ps1[i][j];
                    }
                }
                ps4[i][0] = ngood > 0 ? axsum / ngood : AstObject.AST__BAD;
            }
            double[][] ps3 = mapReg1.transform(ps4, 1, false);
            double[][] ps5 = reg1.transform(ps3, 1, false);
            boolean inside1 = ps5[0][0] != AstObject.AST__BAD;
            ps3 = map.transform(ps4, 1, false);
            ps5 = reg2.transform(ps3, 1, false);
            boolean inside2 = ps5[0][0] != AstObject.AST__BAD;
            return inside1 == inside2 ? Region.OVERLAP_SAME : Region.OVERLAP_NEGATE;
        }

        // The points of the reg2 mesh that are not on the boundary of reg1, in the current Frame
        // of reg1, tested against reg1.
        double[][] submesh = subMesh(mask, reg2Mesh);
        int nsub = submesh[0].length;
        double[][] ps2 = reg1.transform(cmap.transform(submesh, nsub, true), nsub, true);
        int nc2 = ps2.length;
        boolean touch = reg2Mesh[0].length != nsub;
        boolean allbad = true;
        boolean allgood = true;
        for (int iax = 0; iax < nc2; iax++) {
            for (int ip = 0; ip < nsub; ip++) {
                if (ps2[iax][ip] == AstObject.AST__BAD) {
                    allgood = false;
                } else {
                    allbad = false;
                }
            }
        }

        int result;
        if (!allgood && !allbad) {
            result = Region.OVERLAP_PARTIAL;
        } else {
            // Which side of reg2 the boundary of reg1 is on tells the remaining cases apart.
            double[][] ps3;
            try {
                ps3 = reg1.baseMesh();
            } catch (AstException e) {
                // C suppresses the error, which means no mesh.
                ps3 = null;
            }
            if (ps3 == null) {
                // A mesh of the part of the boundary of reg1 inside a box twice the size of the
                // bounding box of the reg2 mesh. C doubles as many bounds as reg1 has current
                // Frame axes, in arrays with one for each base Frame axis.
                if (nc2 > lbnd.length) {
                    throw Ast.err(
                            AstException.AST__INTER,
                            "astOverlap(" + thisRegion.astClass() + "): The " + reg1.astClass() + " has more"
                                    + " current Frame axes (" + nc2 + ") than base Frame axes (" + lbnd.length
                                    + "), so its bounds cannot be widened (internal AST programming error).");
                }
                for (int i = 0; i < nc2; i++) {
                    lbnd[i] *= 2.0;
                    ubnd[i] *= 2.0;
                }
                try {
                    ps3 = reg1.bndBaseMesh(lbnd, ubnd);
                } catch (AstException e) {
                    // C suppresses the error, which means no mesh.
                    ps3 = null;
                }
                if (ps3 == null) {
                    throw Ast.err(
                            AstException.AST__INTER,
                            "astOverlap(" + thisRegion.astClass() + "): The current implementation of astOverlap"
                                    + " cannot determine the overlap between the supplied Regions (a "
                                    + thisRegion.astClass() + " and a " + that.astClass() + ").");
                }
            }
            int np3 = ps3[0].length;
            double[][] ps5 = reg2.transform(map.transform(ps3, np3, false), np3, true);
            int ninside = 0;
            for (int ip = 0; ip < np3; ip++) {
                if (ps5[0][ip] != AstObject.AST__BAD) {
                    ninside++;
                }
            }
            // Some boundary points may be common to both Regions, so the side most of them are
            // on decides.
            boolean inside1 = ninside > np3 / 2;
            if (allgood) {
                if (inside1) {
                    result = Region.OVERLAP_PARTIAL;
                } else {
                    result = reg2IsThat ? Region.OVERLAP_OUTSIDE : Region.OVERLAP_INSIDE;
                }
            } else if (inside1) {
                result = reg2IsThat ? Region.OVERLAP_INSIDE : Region.OVERLAP_OUTSIDE;
            } else {
                result = Region.OVERLAP_NONE;
            }
        }

        // Regions that touch but do not otherwise overlap intersect if either is closed.
        if (touch && result == Region.OVERLAP_NONE && (thisRegion.getClosed() || that.getClosed())) {
            result = Region.OVERLAP_PARTIAL;
        }
        return result;
    }

    /**
     * C's generic {@code BndBaseMesh}: the boundary mesh, in the base Frame of {@code reg}, of the
     * part of {@code reg} inside the box from {@code lbnd} to {@code ubnd} in its current Frame, or
     * a single point with every coordinate bad if the boundary does not cross the box.
     *
     * @throws AstException with status {@code AST__INTER} if the boundary crosses the box: the mesh
     *     is that of a CmpRegion, which is not yet ported
     */
    static double[][] bndBaseMesh(Region reg, double[] lbnd, double[] ubnd) {
        Box box = new Box(reg, 1, lbnd, ubnd, null);
        if (reg.overlap(box) > Region.OVERLAP_OUTSIDE) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astBndBaseMesh(" + reg.astClass() + "): The mesh of the intersection of a " + reg.astClass()
                            + " and a Box needs a CmpRegion, which is not yet ported (internal AST programming"
                            + " error).");
        }
        int nc = reg.getRegFS().getNin();
        double[][] result = new double[nc][1];
        for (int ic = 0; ic < nc; ic++) {
            result[ic][0] = AstObject.AST__BAD;
        }
        return result;
    }

    /**
     * C's {@code GetSubMesh}: the points of {@code in} whose {@code mask} element is false.
     *
     * @throws AstException with status {@code AST__NPTIN} if there are none, which C's PointSet
     *     cannot hold
     */
    private static double[][] subMesh(boolean[] mask, double[][] in) {
        int nc = in.length;
        int np = in[0].length;
        int nout = 0;
        for (int ip = 0; ip < np; ip++) {
            if (!mask[ip]) {
                nout++;
            }
        }
        if (nout < 1) {
            throw Ast.err(
                    AstException.AST__NPTIN,
                    "astInitPointSet(PointSet): Number of points (" + nout + ") is not valid.");
        }
        double[][] out = new double[nc][nout];
        int k = 0;
        for (int ip = 0; ip < np; ip++) {
            if (!mask[ip]) {
                for (int ic = 0; ic < nc; ic++) {
                    out[ic][k] = in[ic][ip];
                }
                k++;
            }
        }
        return out;
    }
}
