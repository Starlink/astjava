package uk.ac.starlink.ast;

/**
 * A Mapping which transforms between 2-dimensional pixel coordinates and an equatorial sky
 * coordinate system (right ascension and declination) using a Digitised Sky Survey (DSS)
 * astrometric plate solution.
 *
 * <p>The input coordinates are pixel numbers along the first and second dimensions of an image,
 * where the centre of the first pixel is located at (1,1) and the spacing between pixel centres is
 * unity. The output coordinates are right ascension and declination in radians. The celestial
 * coordinate system used (FK4, FK5, etc.) is unspecified, and will usually be indicated by
 * appropriate keywords in a FITS header.
 *
 * <p>The DssMap class has no constructor. A DssMap is made only by reading one from a Channel, or
 * by simplifying a CmpMap in which a DssMap absorbs a neighbouring WinMap. Reading a FITS header
 * with the DSS encoding gives a FrameSet whose Mapping holds a TPN projection, not a DssMap.
 *
 * <p>The DssMap class defines no attributes beyond those of every Mapping.
 *
 * <p>Ported from Rust {@code mappings/dssmap.rs} and C {@code dssmap.c}. The plate solution is
 * computed by C's ports of the SAOimage routines {@code platepos} and {@code platepix} (Doug Mink,
 * Smithsonian Astrophysical Observatory).
 */
public class DssMap extends Mapping {

    /** C's {@code AST__DD2R}: radians per degree. */
    private static final double AST__DD2R = 0.017453292519943295;

    /** C's {@code AST__DR2D}: degrees per radian. */
    private static final double AST__DR2D = 57.29577951308232;

    /** Radians per degree as {@code platepos} and {@code platepix} write it ({@code cond2r}). */
    private static final double COND2R = 1.745329252e-2;

    /** Arcseconds per radian as {@code platepos} and {@code platepix} write it ({@code cons2r}). */
    private static final double CONS2R = 206264.8062470964;

    /** Two pi as {@code platepos} writes it ({@code twopi}), a little more than the double nearest to two pi. */
    private static final double TWOPI = 6.28318530717959;

    /** The convergence tolerance (mm) of the Newton iteration in {@code platepix}. */
    private static final double TOLERANCE = 0.0000005;

    /** The most iterations {@code platepix} makes. */
    private static final int MAX_ITERATIONS = 50;

    /** The number of orientation coefficients, PPO1 to PPO6. */
    private static final int NORIENT = 6;

    /** The number of plate solution coefficients on each axis, AMDX1 to AMDX19 and AMDY1 to AMDY19. */
    private static final int NCOEFF = 19;

    /** The number of plate solution coefficients on each axis that must be given. */
    private static final int NCOEFF_REQUIRED = 13;

    // The plate solution, the members of C's WorldCoor structure that platepos and platepix use.

    /** Right ascension of the plate centre, radians ({@code plate_ra}). */
    private double plateRa;

    /** Declination of the plate centre, radians ({@code plate_dec}). */
    private double plateDec;

    /** Plate scale, arcseconds per millimetre ({@code plate_scale}). */
    private double plateScale;

    /** Pixel offset of the image corner on the first axis ({@code x_pixel_offset}). */
    private double xPixelOffset;

    /** Pixel offset of the image corner on the second axis ({@code y_pixel_offset}). */
    private double yPixelOffset;

    /** Pixel size on the first axis, microns ({@code x_pixel_size}). */
    private double xPixelSize;

    /** Pixel size on the second axis, microns ({@code y_pixel_size}). */
    private double yPixelSize;

    /** Orientation coefficients PPO1 to PPO6 ({@code ppo_coeff}). */
    private double[] ppoCoeff = new double[NORIENT];

    /** Plate solution coefficients AMDX1 to AMDX19 ({@code amd_x_coeff}). */
    private double[] amdXCoeff = new double[NCOEFF];

    /** Plate solution coefficients AMDY1 to AMDY19 ({@code amd_y_coeff}). */
    private double[] amdYCoeff = new double[NCOEFF];

    /**
     * C's {@code astDssMap} (dssmap.c): a DssMap with the plate solution the DSS cards of {@code
     * fits} describe, read as C's {@code BuildWcs} reads them.
     *
     * @throws AstException with status {@code AST__BDFTS} if a card the plate solution needs is
     *     missing
     */
    DssMap(FitsChan fits) {
        super(2, 2, true, true);
        buildWcs(fits, "astInitDssMap", "DssMap");
    }

    /**
     * Loads a DssMap from its description, as C's {@code astLoadDssMap} does: the plate
     * parameters, PPO3, PPO6 and the first 13 plate solution coefficients on each axis must be
     * present; the other coefficients default to zero.
     *
     * @throws AstException with status {@code AST__RDERR} if a required item is missing
     */
    DssMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("DssMap");
        plateRa = readRequired(part, "PltRA", "Plate centre RA");
        plateDec = readRequired(part, "PltDec", "Plate centre Dec");
        plateScale = readRequired(part, "PltScl", "Plate scale");
        xPixelOffset = readRequired(part, "CNPix1", "X pixel offset");
        yPixelOffset = readRequired(part, "CNPix2", "Y pixel offset");
        xPixelSize = readRequired(part, "XPixSz", "X pixel size");
        yPixelSize = readRequired(part, "YPixSz", "Y pixel size");
        for (int i = 0; i < NORIENT; i++) {
            String key = "PPO" + (i + 1);
            if (i == 2 || i == 5) {
                ppoCoeff[i] = readRequired(part, key, "orientation coefficient " + (i + 1));
            } else {
                ppoCoeff[i] = readOptional(part, key);
            }
        }
        readCoefficients(part, "AMDX", "X", amdXCoeff);
        readCoefficients(part, "AMDY", "Y", amdYCoeff);
    }

    /**
     * The coefficients {@code <prefix>1} to {@code <prefix>19} into {@code coeff}, the first 13
     * required, as C's {@code astLoadDssMap} reads them.
     */
    private static void readCoefficients(NativeNode part, String prefix, String axis, double[] coeff) {
        for (int i = 0; i < NCOEFF; i++) {
            String key = prefix + (i + 1);
            if (i < NCOEFF_REQUIRED) {
                coeff[i] = readRequired(part, key, "plate solution " + axis + " coefficient " + (i + 1));
            } else {
                coeff[i] = readOptional(part, key);
            }
        }
    }

    /** The value of a required item, or C's error if it is missing (its value read as bad). */
    private static double readRequired(NativeNode part, String key, String what) {
        double value = part.readDouble(key, AST__BAD);
        if (value == AST__BAD) {
            throw Ast.err(
                    AstException.AST__RDERR,
                    "astRead(DssMap): '" + key + "' object (" + what + ") missing from input.");
        }
        return value;
    }

    /** The value of an item that defaults to zero when it is missing (its value read as bad). */
    private static double readOptional(NativeNode part, String key) {
        double value = part.readDouble(key, AST__BAD);
        return value == AST__BAD ? 0.0 : value;
    }

    /** Gives the copy its own coefficients, as C's {@code Copy} copies the WorldCoor structure. */
    @Override
    void deepCopy() {
        super.deepCopy();
        ppoCoeff = ppoCoeff.clone();
        amdXCoeff = amdXCoeff.clone();
        amdYCoeff = amdYCoeff.clone();
    }

    @Override
    String dumpComment() {
        return "DSS plate fit mapping";
    }

    /** Writes the plate solution, every value set, as C's {@code Dump} (dssmap.c) does. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeDouble("PltRA", true, true, plateRa, "Plate centre RA (radians)");
        node.writeDouble("PltDec", true, true, plateDec, "Plate centre Dec (radians)");
        node.writeDouble("PltScl", true, true, plateScale, "Plate scale (arcsec/mm)");
        node.writeDouble("CNPix1", true, true, xPixelOffset, "X Pixel offset (pixels)");
        node.writeDouble("CNPix2", true, true, yPixelOffset, "Y Pixel offset (pixels)");
        node.writeDouble("XPixSz", true, true, xPixelSize, "X Pixel size (microns)");
        node.writeDouble("YPixSz", true, true, yPixelSize, "Y Pixel size (microns)");
        for (int i = 0; i < NORIENT; i++) {
            node.writeDouble("PPO" + (i + 1), true, true, ppoCoeff[i], "Orientation coefficients");
        }
        for (int i = 0; i < NCOEFF; i++) {
            node.writeDouble("AMDX" + (i + 1), true, true, amdXCoeff[i], "Plate solution X coefficients");
        }
        for (int i = 0; i < NCOEFF; i++) {
            node.writeDouble("AMDY" + (i + 1), true, true, amdYCoeff[i], "Plate solution Y coefficients");
        }
    }

    // ------------------------------------------------------------------
    // The FITS cards of the plate solution
    // ------------------------------------------------------------------

    /** C's {@code NINT} (dssmap.c): the nearest integer, halves rounded away from zero. */
    private static int nint(double x) {
        return (int) (x + (x > 0.0 ? 0.5 : -0.5));
    }

    /** The integral part of {@code x}, as C's {@code modf} gives it. */
    private static double intPart(double x) {
        return x < 0.0 ? Math.ceil(x) : Math.floor(x);
    }

    /**
     * C's {@code BuildWcs} (dssmap.c): reads the plate solution from the DSS cards of {@code
     * fits}, after SAOimage's {@code wcsinit}. The plate centre is read as hours or degrees,
     * minutes and seconds, the declination negative when PLTDECSN starts with a minus sign; PPO3,
     * PPO6 and the first 13 coefficients on each axis must be present, the others defaulting to
     * zero.
     *
     * @throws AstException with status {@code AST__BDFTS} if a required card is missing
     */
    private void buildWcs(FitsChan fits, String method, String cls) {
        double rah = requiredCard(fits, "PLTRAH", method, cls);
        double ram = requiredCard(fits, "PLTRAM", method, cls);
        double ras = requiredCard(fits, "PLTRAS", method, cls);
        double raHours = rah + (ram / 60.0) + (ras / 3600.0);
        plateRa = AST__DD2R * 15.0 * raHours;

        String sign = fits.getFitsS("PLTDECSN");
        double dsign = sign != null && sign.startsWith("-") ? -1.0 : 1.0;
        double decd = requiredCard(fits, "PLTDECD", method, cls);
        double decm = requiredCard(fits, "PLTDECM", method, cls);
        double decs = requiredCard(fits, "PLTDECS", method, cls);
        double decDeg = dsign * (decd + (decm / 60.0) + (decs / 3600.0));
        plateDec = AST__DD2R * decDeg;

        plateScale = requiredCard(fits, "PLTSCALE", method, cls);
        xPixelOffset = requiredCard(fits, "CNPIX1", method, cls);
        yPixelOffset = requiredCard(fits, "CNPIX2", method, cls);
        xPixelSize = requiredCard(fits, "XPIXELSZ", method, cls);
        yPixelSize = requiredCard(fits, "YPIXELSZ", method, cls);

        for (int i = 0; i < NORIENT; i++) {
            String name = "PPO" + (i + 1);
            if (i == 2 || i == 5) {
                ppoCoeff[i] = requiredCard(fits, name, method, cls);
            } else {
                Double v = fits.getFitsF(name);
                ppoCoeff[i] = v == null ? 0.0 : v;
            }
        }
        readCardCoefficients(fits, "AMDX", amdXCoeff, method, cls);
        readCardCoefficients(fits, "AMDY", amdYCoeff, method, cls);
    }

    /** The cards {@code <prefix>1} to {@code <prefix>19} into {@code coeff}, the first 13 required. */
    private static void readCardCoefficients(FitsChan fits, String prefix, double[] coeff, String method, String cls) {
        for (int i = 0; i < NCOEFF; i++) {
            String name = prefix + (i + 1);
            if (i < NCOEFF_REQUIRED) {
                coeff[i] = requiredCard(fits, name, method, cls);
            } else {
                Double v = fits.getFitsF(name);
                coeff[i] = v == null ? 0.0 : v;
            }
        }
    }

    /** The floating value of card {@code name}, or C's {@code BuildWcs} error if there is none. */
    private static double requiredCard(FitsChan fits, String name, String method, String cls) {
        Double v = fits.getFitsF(name);
        if (v == null) {
            throw Ast.err(
                    AstException.AST__BDFTS,
                    method + "(" + cls + "): No value has been supplied for the FITS keyword '" + name + "'.");
        }
        return v;
    }

    /**
     * C's {@code astDssFits} (dssmap.c {@code DssFits}): a FitsChan with the DSS encoding holding
     * the cards that describe this DssMap's plate solution. The plate centre is written as whole
     * hours (or a sign and whole degrees) and minutes, each rounded by {@code NINT}, and seconds;
     * the pixel offsets are rounded to whole pixels.
     */
    FitsChan dssFits() {
        FitsChan ret = new FitsChan();
        ret.setEncoding("DSS");

        double ra = plateRa / (AST__DD2R * 15.0);
        double rah = intPart(ra);
        ra -= rah;
        double ram = intPart(60.0 * ra);
        ra = 60.0 * ra - ram;
        double ras = 60.0 * ra;
        ret.setFits("PLTRAH", nint(rah), "Plate centre RA", false);
        ret.setFits("PLTRAM", nint(ram), " ", false);
        ret.setFits("PLTRAS", ras, " ", false);

        double dec = plateDec / AST__DD2R;
        if (dec < 0.0) {
            dec = -dec;
            ret.setFits("PLTDECSN", "-", "Plate centre DEC", false);
        } else {
            ret.setFits("PLTDECSN", "+", "Plate centre DEC", false);
        }
        double decd = intPart(dec);
        dec -= decd;
        double decm = intPart(60.0 * dec);
        dec = 60.0 * dec - decm;
        double decs = 60.0 * dec;
        ret.setFits("PLTDECD", nint(decd), " ", false);
        ret.setFits("PLTDECM", nint(decm), " ", false);
        ret.setFits("PLTDECS", decs, " ", false);

        ret.setFits("PLTSCALE", plateScale, "Plate Scale arcsec per mm", false);
        ret.setFits("CNPIX1", nint(xPixelOffset), "X corner  (pixels)", false);
        ret.setFits("CNPIX2", nint(yPixelOffset), "Y corner", false);
        ret.setFits("XPIXELSZ", xPixelSize, "X pixel size (microns)", false);
        ret.setFits("YPIXELSZ", yPixelSize, "Y pixel size (microns)", false);

        String comm = "Orientation Coefficients";
        for (int i = 0; i < NORIENT; i++) {
            ret.setFits("PPO" + (i + 1), ppoCoeff[i], comm, false);
            comm = " ";
        }
        comm = "Plate solution x coefficients";
        for (int i = 0; i < NCOEFF; i++) {
            ret.setFits("AMDX" + (i + 1), amdXCoeff[i], comm, false);
            comm = " ";
        }
        comm = "Plate solution y coefficients";
        for (int i = 0; i < NCOEFF; i++) {
            ret.setFits("AMDY" + (i + 1), amdYCoeff[i], comm, false);
            comm = " ";
        }
        return ret;
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * C's {@code Transform} (dssmap.c): the forward transformation gives the right ascension and
     * declination ({@code platepos}, in degrees, converted to radians) of each pixel position, and
     * the inverse the pixel position ({@code platepix}) of each sky position (converted to
     * degrees). A point with a bad coordinate gives bad values on both axes; a coordinate that is
     * not finite is bad (C tests only for {@link #AST__BAD}). {@code platepos} and {@code
     * platepix} always report success, so no other point is bad.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        double[] in0 = in[0];
        double[] in1 = in[1];
        double[] out0 = out[0];
        double[] out1 = out[1];
        double[] pos = new double[2];
        boolean fwd = effectiveForward(forward);
        for (int p = 0; p < npoint; p++) {
            if (isBad(in0[p]) || isBad(in1[p])) {
                out0[p] = AST__BAD;
                out1[p] = AST__BAD;
            } else if (fwd) {
                platepos(in0[p], in1[p], pos);
                out0[p] = pos[0] * AST__DD2R;
                out1[p] = pos[1] * AST__DD2R;
            } else {
                platepix(AST__DR2D * in0[p], AST__DR2D * in1[p], pos);
                out0[p] = pos[0];
                out1[p] = pos[1];
            }
        }
    }

    /**
     * SAOimage's {@code platepos} as C has it (dssmap.c): the right ascension and declination, in
     * degrees, of image pixel ({@code xpix}, {@code ypix}), into {@code pos}. The magnitude and
     * colour terms of the plate model (coefficients 14 to 19) are left out, and a right ascension
     * below zero has two pi added.
     */
    private void platepos(double xpix, double ypix, double[] pos) {
        // Convert from image pixels to plate pixels.
        double x = xpix + xPixelOffset - 1.0 + 0.5;
        double y = ypix + yPixelOffset - 1.0 + 0.5;

        // Convert from pixels to millimetres.
        double xmm = (ppoCoeff[2] - x * xPixelSize) / 1000.0;
        double ymm = (y * yPixelSize - ppoCoeff[5]) / 1000.0;
        double xmm2 = xmm * xmm;
        double ymm2 = ymm * ymm;
        double xmm3 = xmm * xmm2;
        double ymm3 = ymm * ymm2;
        double x2y2 = xmm2 + ymm2;

        // Compute coordinates from x, y and the plate model.
        double[] ax = amdXCoeff;
        double xi = ax[0] * xmm
                + ax[1] * ymm
                + ax[2]
                + ax[3] * xmm2
                + ax[4] * xmm * ymm
                + ax[5] * ymm2
                + ax[6] * x2y2
                + ax[7] * xmm3
                + ax[8] * xmm2 * ymm
                + ax[9] * xmm * ymm2
                + ax[10] * ymm3
                + ax[11] * xmm * x2y2
                + ax[12] * xmm * x2y2 * x2y2;

        double[] ay = amdYCoeff;
        double eta = ay[0] * ymm
                + ay[1] * xmm
                + ay[2]
                + ay[3] * ymm2
                + ay[4] * xmm * ymm
                + ay[5] * xmm2
                + ay[6] * x2y2
                + ay[7] * ymm3
                + ay[8] * ymm2 * xmm
                + ay[9] * ymm * xmm2
                + ay[10] * xmm3
                + ay[11] * ymm * x2y2
                + ay[12] * ymm * x2y2 * x2y2;

        // Convert to radians.
        double xir = xi / CONS2R;
        double etar = eta / CONS2R;

        // Convert to RA and Dec.
        double ctan = Math.tan(plateDec);
        double ccos = Math.cos(plateDec);
        double raoff = Math.atan2(xir / ccos, 1.0 - etar * ctan);
        double ra = raoff + plateRa;
        if (ra < 0.0) {
            ra = ra + TWOPI;
        }
        pos[0] = ra / COND2R;

        double dec = Math.atan(Math.cos(raoff) / ((1.0 - (etar * ctan)) / (etar + ctan)));
        pos[1] = dec / COND2R;
    }

    /**
     * SAOimage's {@code platepix} as C has it (dssmap.c): the image pixel position of the sky
     * position ({@code xpos}, {@code ypos}), in degrees, into {@code pos}, by Newton's method on
     * the plate model from the position the plate scale alone gives. The magnitude and colour
     * terms are left out, and the position is not checked against the image's bounds.
     */
    private void platepix(double xpos, double ypos, double[] pos) {
        // Convert RA and Dec to standard coordinates on the plate.
        double xr = xpos * COND2R;
        double yr = ypos * COND2R;
        double sypos = Math.sin(yr);
        double cypos = Math.cos(yr);
        double syplate = Math.sin(plateDec);
        double cyplate = Math.cos(plateDec);
        double sxdiff = Math.sin(xr - plateRa);
        double cxdiff = Math.cos(xr - plateRa);
        double div = (sypos * syplate) + (cypos * cyplate * cxdiff);
        double xi = cypos * sxdiff * CONS2R / div;
        double eta = ((sypos * cyplate) - (cypos * syplate * cxdiff)) * CONS2R / div;

        // Set the initial values of x and y.
        double xmm = xi / plateScale;
        double ymm = eta / plateScale;

        double[] ax = amdXCoeff;
        double[] ay = amdYCoeff;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            // X plate model.
            double xy = xmm * ymm;
            double x2 = xmm * xmm;
            double y2 = ymm * ymm;
            double x2y = x2 * ymm;
            double y2x = y2 * xmm;
            double x2y2 = x2 + y2;
            double cjunk = x2y2 * x2y2;
            double x3 = x2 * xmm;
            double y3 = y2 * ymm;
            double x4 = x2 * x2;
            double y4 = y2 * y2;
            double f = ax[0] * xmm
                    + ax[1] * ymm
                    + ax[2]
                    + ax[3] * x2
                    + ax[4] * xy
                    + ax[5] * y2
                    + ax[6] * x2y2
                    + ax[7] * x3
                    + ax[8] * x2y
                    + ax[9] * y2x
                    + ax[10] * y3
                    + ax[11] * xmm * x2y2
                    + ax[12] * xmm * cjunk;

            // Derivative of the X model with respect to x.
            double fx = ax[0]
                    + ax[3] * 2.0 * xmm
                    + ax[4] * ymm
                    + ax[6] * 2.0 * xmm
                    + ax[7] * 3.0 * x2
                    + ax[8] * 2.0 * xy
                    + ax[9] * y2
                    + ax[11] * (3.0 * x2 + y2)
                    + ax[12] * (5.0 * x4 + 6.0 * x2 * y2 + y4);

            // Derivative of the X model with respect to y.
            double fy = ax[1]
                    + ax[4] * xmm
                    + ax[5] * 2.0 * ymm
                    + ax[6] * 2.0 * ymm
                    + ax[8] * x2
                    + ax[9] * 2.0 * xy
                    + ax[10] * 3.0 * y2
                    + ax[11] * 2.0 * xy
                    + ax[12] * 4.0 * xy * x2y2;

            // Y plate model.
            double g = ay[0] * ymm
                    + ay[1] * xmm
                    + ay[2]
                    + ay[3] * y2
                    + ay[4] * xy
                    + ay[5] * x2
                    + ay[6] * x2y2
                    + ay[7] * y3
                    + ay[8] * y2x
                    + ay[9] * x2y
                    + ay[10] * x3
                    + ay[11] * ymm * x2y2
                    + ay[12] * ymm * cjunk;

            // Derivative of the Y model with respect to x.
            double gx = ay[1]
                    + ay[4] * ymm
                    + ay[5] * 2.0 * xmm
                    + ay[6] * 2.0 * xmm
                    + ay[8] * y2
                    + ay[9] * 2.0 * xy
                    + ay[10] * 3.0 * x2
                    + ay[11] * 2.0 * xy
                    + ay[12] * 4.0 * xy * x2y2;

            // Derivative of the Y model with respect to y.
            double gy = ay[0]
                    + ay[3] * 2.0 * ymm
                    + ay[4] * xmm
                    + ay[6] * 2.0 * ymm
                    + ay[7] * 3.0 * y2
                    + ay[8] * 2.0 * xy
                    + ay[9] * x2
                    + ay[11] * (x2 + 3.0 * y2)
                    + ay[12] * (5.0 * y4 + 6.0 * x2 * y2 + x4);

            f = f - xi;
            g = g - eta;
            double dx = ((-f * gy) + (g * fy)) / ((fx * gy) - (fy * gx));
            double dy = ((-g * fx) + (f * gx)) / ((fx * gy) - (fy * gx));
            xmm = xmm + dx;
            ymm = ymm + dy;
            if ((Math.abs(dx) < TOLERANCE) && (Math.abs(dy) < TOLERANCE)) {
                break;
            }
        }

        // Convert mm from the plate centre to plate pixels.
        double x = (ppoCoeff[2] - xmm * 1000.0) / xPixelSize;
        double y = (ppoCoeff[5] + ymm * 1000.0) / yPixelSize;

        // Convert from plate pixels to image pixels.
        pos[0] = x - xPixelOffset + 1.0 - 0.5;
        pos[1] = y - yPixelOffset + 1.0 - 0.5;
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    /**
     * dssmap.c {@code Equal}: {@code that} is a DssMap with the same numbers of inputs and outputs
     * and the same Invert attribute, whose pixel offsets and sizes, PPO3, PPO6, plate centre and
     * first 13 coefficients on each axis are, to within {@code astEQUAL}, this one's. The plate
     * scale and the other coefficients are not compared.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof DssMap)) {
            return false;
        }
        DssMap other = (DssMap) that;
        if (other.getNin() != getNin() || other.getNout() != getNout() || getInvert() != other.getInvert()) {
            return false;
        }
        if (!(MappingHelpers.astEqual(xPixelOffset, other.xPixelOffset)
                && MappingHelpers.astEqual(yPixelOffset, other.yPixelOffset)
                && MappingHelpers.astEqual(ppoCoeff[2], other.ppoCoeff[2])
                && MappingHelpers.astEqual(ppoCoeff[5], other.ppoCoeff[5])
                && MappingHelpers.astEqual(xPixelSize, other.xPixelSize)
                && MappingHelpers.astEqual(yPixelSize, other.yPixelSize)
                && MappingHelpers.astEqual(plateDec, other.plateDec)
                && MappingHelpers.astEqual(plateRa, other.plateRa))) {
            return false;
        }
        for (int i = 0; i < NCOEFF_REQUIRED; i++) {
            if (!MappingHelpers.astEqual(amdXCoeff[i], other.amdXCoeff[i])
                    || !MappingHelpers.astEqual(amdYCoeff[i], other.amdYCoeff[i])) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * dssmap.c {@code MapMerge}: in series, a WinMap (exactly that class) applied to the pixel
     * coordinates, before the DssMap if the list applies the DssMap forward and after it
     * otherwise, folds into the pixel offsets and sizes, when its shift and scale terms on both
     * axes are good and its scales non-zero, and the new pixel offsets are whole to within 0.01 of
     * a pixel. The merged DssMap is made, as C makes it, from the FITS cards {@link #dssFits}
     * writes with those four cards replaced, so the plate centre passes through its sexagesimal
     * cards; it is applied with the Invert value of the DssMap it replaces.
     *
     * <p>As in C, the WinMap's Invert attribute is set to the value the list applies it with while
     * its terms are read, and restored only when the two do not merge: when they merge, the WinMap,
     * which may be shared with the Mapping being simplified, is left with the list's value
     * (docs/rust-c-differences.md, "A DssMap merge leaves the WinMap's Invert attribute changed").
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        if (!series) {
            return -1;
        }
        int wmi = list.invert(where) ? where + 1 : where - 1;
        if (wmi < 0 || wmi >= list.size() || list.map(wmi).getClass() != WinMap.class) {
            return -1;
        }
        WinMap wm = (WinMap) list.map(wmi);
        int oldWinv = wm.swapInvert(list.invert(wmi));
        boolean merged = false;
        try {
            merged = mergeWinMap(list, where, wmi, wm.winTerms());
        } finally {
            // C annuls its pointer to the WinMap after a merge, which skips the restore.
            if (!merged) {
                wm.restoreInvert(oldWinv);
            }
        }
        return merged ? Math.min(wmi, where) : -1;
    }

    /**
     * The part of {@link #mapMerge} after the WinMap's terms {@code terms} (shifts, scales) are
     * read: merges the WinMap at {@code wmi} into the DssMap at {@code where} if C would, and
     * says whether it did.
     */
    private static boolean mergeWinMap(MergeList list, int where, int wmi, double[][] terms) {
        double[] a = terms[0];
        double[] b = terms[1];
        if (a[0] == AST__BAD
                || b[0] == AST__BAD
                || b[0] == 0.0
                || a[1] == AST__BAD
                || b[1] == AST__BAD
                || b[1] == 0.0) {
            return false;
        }

        DssMap dm = (DssMap) list.map(where);
        double cnpix1 = dm.xPixelOffset;
        double cnpix2 = dm.yPixelOffset;
        double xpixelsz = dm.xPixelSize;
        double ypixelsz = dm.yPixelSize;
        if (wmi == where - 1) {
            xpixelsz *= b[0];
            ypixelsz *= b[1];
            cnpix1 = 0.5 + (cnpix1 + a[0] - 0.5) / b[0];
            cnpix2 = 0.5 + (cnpix2 + a[1] - 0.5) / b[1];
        } else {
            xpixelsz /= b[0];
            ypixelsz /= b[1];
            cnpix1 = b[0] * (cnpix1 - 0.5) - a[0] + 0.5;
            cnpix2 = b[1] * (cnpix2 - 0.5) - a[1] + 0.5;
        }

        // The CNPIX1 and CNPIX2 cards are integers, so the merge is made only when the new
        // values are whole to within one hundredth of a pixel.
        if (Math.abs(cnpix1 - nint(cnpix1)) >= 0.01 || Math.abs(cnpix2 - nint(cnpix2)) >= 0.01) {
            return false;
        }

        FitsChan fits = dm.dssFits();
        boolean ok = replaceCard(fits, "CNPIX1", nint(cnpix1));
        ok = replaceCard(fits, "CNPIX2", nint(cnpix2)) && ok;
        ok = replaceCard(fits, "XPIXELSZ", xpixelsz) && ok;
        ok = replaceCard(fits, "YPIXELSZ", ypixelsz) && ok;
        if (!ok) {
            return false;
        }
        list.set(where, new DssMap(fits), list.invert(where));
        list.removeAt(wmi);
        return true;
    }

    /**
     * Replaces the value of card {@code name}, searching from the first card, as C's {@code
     * MapMerge} does with {@code astClearCard}, {@code astFindFits} and {@code astSetFitsI}; false
     * if there is no such card.
     */
    private static boolean replaceCard(FitsChan fits, String name, int value) {
        fits.clear("Card");
        if (fits.findFits(name, false) == null) {
            return false;
        }
        fits.setFits(name, value, null, true);
        return true;
    }

    /** {@link #replaceCard(FitsChan, String, int)} for a floating value ({@code astSetFitsF}). */
    private static boolean replaceCard(FitsChan fits, String name, double value) {
        fits.clear("Card");
        if (fits.findFits(name, false) == null) {
            return false;
        }
        fits.setFits(name, value, null, true);
        return true;
    }
}
