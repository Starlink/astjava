package uk.ac.starlink.ast;

/**
 * C's {@code WorldAxes} (fitschan.c): the pixel axis associated with each world axis of a pixel to
 * world Mapping, the one most nearly parallel to it.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/world_axes.rs}, following fitschan.c.
 */
final class FitsWorldAxes {

    private static final double AST__BAD = AstObject.AST__BAD;

    private FitsWorldAxes() {}

    /**
     * Finds the pixel axis associated with each output of {@code cmap}. For a Mapping defined in
     * both directions, a small step along each world axis from a position with good world
     * coordinates is transformed back to pixel coordinates, and pixel axes are assigned to world
     * axes in order of the smallest angle between them, ties going to the longest projection;
     * world axes left over take the unused pixel axes in turn, numbering past the pixel axes when
     * they run out. A Mapping with only a forward transformation is analysed when the FitsChan's
     * TabOK is positive: each output takes the input that alone feeds it, and an input feeding
     * several outputs keeps one (the output of the same index if it is one of them, else the
     * first), the others taking unused inputs.
     *
     * @param fc the FitsChan, whose TabOK attribute is used
     * @param cmap the pixel to world Mapping, which is simplified
     * @param dim the number of pixels along each pixel axis, {@code AST__BAD} where unknown
     * @param perm receives, for each output, the zero-based index of the associated pixel axis
     * @return whether the axes were associated
     */
    static boolean worldAxes(FitsChan fc, Mapping cmap, double[] dim, int[] perm) {
        boolean ret = false;
        Mapping map = cmap.simplify();
        int nin = map.getNin();
        int nout = map.getNout();
        for (int i = 0; i < nout; i++) {
            perm[i] = i;
        }

        if (map.getTranForward() && map.getTranInverse()) {
            double[][] ptrg = new double[nin][nin + 1];
            double[][] ptrw = new double[nout][nin + 1];
            if (!FitsWcsDecompose.findBasisVectors(map, nin, nout, dim, ptrg, ptrw)) {
                return ret;
            }
            double[] g0 = new double[nin];
            for (int j = 0; j < nin; j++) {
                g0[j] = ptrg[j][0];
            }

            // The root position and a position 1 pixel away along every pixel axis, in world
            // coordinates, and a small increment along each world axis.
            double[][] ptr1 = new double[nin][2];
            for (int j = 0; j < nin; j++) {
                ptr1[j][0] = g0[j];
                ptr1[j][1] = g0[j] + 1.0;
            }
            double[][] ptr2 = map.transform(ptr1, 2, true);
            double[] w0 = new double[nout];
            double[] dw = new double[nout];
            for (int i = 0; i < nout; i++) {
                w0[i] = ptr2[i][0];
                if (w0[i] != AST__BAD && ptr2[i][1] != AST__BAD) {
                    dw[i] = Math.abs(0.1 * (ptr2[i][1] - w0[i]));
                    if (dw[i] <= Math.abs(0.001 * w0[i])) {
                        if (w0[i] != 0.0) {
                            dw[i] = Math.abs(0.001 * w0[i]);
                        } else {
                            dw[i] = 1.0;
                        }
                    }
                } else {
                    dw[i] = AST__BAD;
                }
            }

            // The inverse transformation need not be a true inverse (constants fed to degenerate
            // axes by a PermMap), so the root position is the world position transformed back.
            ptr1 = map.transform(ptr2, 2, false);
            for (int j = 0; j < nin; j++) {
                g0[j] = ptr1[j][0];
            }

            // The tan of the angle between each world axis and each pixel axis, and the length of
            // the projection onto the pixel axis.
            double[] tn = new double[nout * nin];
            double[] wt = new double[nout * nin];
            for (int i = 0; i < nout; i++) {
                int ntn = i * nin;
                for (int j = 0; j < nin; j++) {
                    tn[ntn + j] = AST__BAD;
                }
                perm[i] = -1;
                if (dw[i] != AST__BAD) {
                    ptr2[i][0] += dw[i];
                    ptr1 = map.transform(ptr2, 2, false);
                    ptr2[i][0] = w0[i];
                    for (int j = 0; j < nin; j++) {
                        double s = 0.0;
                        double sj = 0.0;
                        for (int j2 = 0; j2 < nin; j2++) {
                            if (ptr1[j2][0] != AST__BAD) {
                                double dg = ptr1[j2][0] - g0[j2];
                                if (j2 != j) {
                                    s += dg * dg;
                                } else {
                                    sj = Math.abs(dg);
                                }
                            } else {
                                s = AST__BAD;
                                break;
                            }
                        }
                        if (s != AST__BAD && sj != 0.0) {
                            tn[ntn + j] = Math.sqrt(s) / sj;
                            wt[ntn + j] = sj;
                        }
                    }
                }
            }

            // Assign pixel axes to world axes, smallest tan first, equal tans going to the
            // highest weight, until no tan is left.
            while (true) {
                double tnmin = AST__BAD;
                double wtmax = AST__BAD;
                int imin = 0;
                int jmin = 0;
                int k = 0;
                for (int i = 0; i < nout; i++) {
                    for (int j = 0; j < nin; j++) {
                        if (tn[k] != AST__BAD) {
                            if (tnmin == AST__BAD || tn[k] < tnmin) {
                                tnmin = tn[k];
                                wtmax = wt[k];
                                imin = i;
                                jmin = j;
                            } else if (MappingHelpers.astEqual(tn[k], tnmin) && wt[k] > wtmax) {
                                wtmax = wt[k];
                                imin = i;
                                jmin = j;
                            }
                        }
                        k++;
                    }
                }
                if (tnmin == AST__BAD) {
                    break;
                }
                perm[imin] = jmin;
                k = 0;
                for (int i = 0; i < nout; i++) {
                    for (int j = 0; j < nin; j++) {
                        if (i == imin || j == jmin) {
                            tn[k] = AST__BAD;
                        }
                        k++;
                    }
                }
            }

            // World axes with no pixel axis take the unused pixel axes, lowest first.
            int j;
            for (j = 0; j < nin; j++) {
                if (!isUsed(perm, nout, j)) {
                    break;
                }
            }
            for (int i = 0; i < nout; i++) {
                if (perm[i] == -1) {
                    perm[i] = j++;
                    for (; j < nin; j++) {
                        if (!isUsed(perm, nout, j)) {
                            break;
                        }
                    }
                }
            }
            ret = true;

        } else if (map.getTranForward() && fc.getTabOK() > 0) {
            ret = true;
            for (int i = 0; i < nout; i++) {
                perm[i] = -1;
            }

            // The output associated with each input.
            for (int j = 0; j < nin; j++) {
                MapSplit split = map.mapSplit(new int[] {j});
                if (split != null) {
                    int nouts = split.map.getNout();
                    for (int i = 0; i < nouts; i++) {
                        if (perm[split.outputs[i]] == -1) {
                            perm[split.outputs[i]] = j;
                        } else {
                            ret = false;
                        }
                    }
                }
            }
            for (int i = 0; i < nout && ret; i++) {
                if (perm[i] == -1) {
                    ret = false;
                }
            }

            // An input feeding several outputs keeps the output of the same index if that is one
            // of them, else the first; the others take the next unused input. C reads perm[j]
            // for every input, past the end of the array when there are more inputs than
            // outputs; such an input has no output of the same index.
            if (ret) {
                for (int j = 0; j < nin; j++) {
                    int nused = 0;
                    for (int i = 0; i < nout; i++) {
                        if (perm[i] == j) {
                            nused++;
                        }
                    }
                    if (nused > 1) {
                        int retain = 0;
                        if (j < nout && perm[j] == j) {
                            retain = j;
                        } else {
                            for (int i = 0; i < nout; i++) {
                                if (perm[i] == j) {
                                    retain = i;
                                    break;
                                }
                            }
                        }
                        for (int i = 0; i < nout && ret; i++) {
                            if (perm[i] == j && i != retain) {
                                boolean used = true;
                                for (int j2 = 0; j2 < nin; j2++) {
                                    used = isUsed(perm, nout, j2);
                                    if (!used) {
                                        perm[i] = j2;
                                        break;
                                    }
                                }
                                if (used) {
                                    ret = false;
                                }
                            }
                        }
                    }
                }
            }
        }
        return ret;
    }

    /** Whether pixel axis {@code j} is assigned to one of the {@code nout} world axes. */
    private static boolean isUsed(int[] perm, int nout, int j) {
        for (int i = 0; i < nout; i++) {
            if (perm[i] == j) {
                return true;
            }
        }
        return false;
    }
}
