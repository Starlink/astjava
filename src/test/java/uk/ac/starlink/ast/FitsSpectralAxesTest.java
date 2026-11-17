package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/spectral_axes.rs} (C {@code
 * SpectralAxes}, fitschan.c), and FrameSets with a spectral axis written as C writes them, from
 * C's {@code SpectralAxes} and the SpecFrame parts of {@code MakeFitsFrameSet}: the cards each
 * write gives are those of C probe {@code spectral_axes_probe}, which writes the same FrameSets
 * with the pinned C library into a FitsChan holding the NAXIS cards.
 *
 * <p>The Rust tests' expectations are checked against the cards C writes for the same FrameSets.
 * {@code x2p_cascade_picks_freq_for_wavelength_axis} builds its axis with a MathMap; here the
 * frequency axis is set to wavelength through the FrameSet, as {@code testfitschan.c} does. Rust's
 * {@code try_grism_cascade_emits_gri_pv_and_chain} calls a helper this library does not have;
 * here the whole of {@code SpectralAxes} describes its axis, through a grism non-linear enough
 * that the linear, {@code -X2P} and {@code -LOG} algorithms fail first. The unit tests of Rust
 * {@code fitschan/fset_to_store/grism.rs} test {@link FitsSpectralAxes#extractGrismMap}.
 */
class FitsSpectralAxesTest {

    private static final double BAD = AstObject.AST__BAD;

    /** C's {@code AST__C}, the speed of light in m/s. */
    private static final double AST__C = 2.99792458E8;

    /** C's cards for freq_basic (C probe {@code spectral_axes_probe}, case {@code freq_basic}). */
    private static final String[] C_FREQ_BASIC = {
        "wrote 1",
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CRPIX1  =                 50.0 / Reference pixel on axis 1",
        "CRPIX2  =                 51.0 / Reference pixel on axis 2",
        "CRVAL1  =                 50.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                 51.0 / Value at ref. pixel on axis 2",
        "CTYPE1  = 'x1      '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'FREQ    '           / Type of co-ordinate on axis 2", // codespell:ignore
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CDELT2  =                  1.0 / Pixel size on axis 2",
        "CUNIT2  = 'GHz     '           / Units for axis 2",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "VELOSYS =   29336.889452278185 / [m/s] Topo. apparent velocity of rest frame",
    };
    /** C's cards for restwav (C probe {@code spectral_axes_probe}, case {@code restwav}). */
    private static final String[] C_RESTWAV = {
        "wrote 1",
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CRPIX1  =                 50.0 / Reference pixel on axis 1",
        "CRPIX2  =                 51.0 / Reference pixel on axis 2",
        "CRVAL1  =                 50.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                 51.0 / Value at ref. pixel on axis 2",
        "CTYPE1  = 'x1      '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'WAVE    '           / Type of co-ordinate on axis 2", // codespell:ignore
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CDELT2  =                  1.0 / Pixel size on axis 2",
        "CUNIT2  = 'Angstrom'           / Units for axis 2",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "VELOSYS =   29336.889452286428 / [m/s] Topo. apparent velocity of rest frame",
        "RESTWAV =  0.21112144929577464 / [m] Rest wavelength",
    };
    /** C's cards for obsgeo (C probe {@code spectral_axes_probe}, case {@code obsgeo}). */
    private static final String[] C_OBSGEO = {
        "wrote 1",
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CRPIX1  =                 50.0 / Reference pixel on axis 1",
        "CRPIX2  =                 51.0 / Reference pixel on axis 2",
        "CRVAL1  =                 50.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                 51.0 / Value at ref. pixel on axis 2",
        "CTYPE1  = 'x1      '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'FREQ    '           / Type of co-ordinate on axis 2", // codespell:ignore
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CDELT2  =                  1.0 / Pixel size on axis 2",
        "CUNIT2  = 'GHz     '           / Units for axis 2",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "VELOSYS =   29508.815578196482 / [m/s] Topo. apparent velocity of rest frame",
        "OBSGEO-X=   3980583.1459730188 / [m] Observatory geocentric X",
        "OBSGEO-Y=                  0.0 / [m] Observatory geocentric Y",
        "OBSGEO-Z=   4966822.9821469523 / [m] Observatory geocentric Z",
    };
    /** C's cards for label (C probe {@code spectral_axes_probe}, case {@code label}). */
    private static final String[] C_LABEL = {
        "wrote 1",
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CRPIX1  =                 50.0 / Reference pixel on axis 1",
        "CRPIX2  =                 51.0 / Reference pixel on axis 2",
        "CRVAL1  =                 50.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                 51.0 / Value at ref. pixel on axis 2",
        "CTYPE1  = 'x1      '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'FREQ    '           / MyFrequency",
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CDELT2  =                  1.0 / Pixel size on axis 2",
        "CUNIT2  = 'GHz     '           / Units for axis 2",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "VELOSYS =   29336.889452278185 / [m/s] Topo. apparent velocity of rest frame",
    };
    /** C's cards for sourcevel (C probe {@code spectral_axes_probe}, case {@code sourcevel}). */
    private static final String[] C_SOURCEVEL = {
        "wrote 1",
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CRPIX1  =                 50.0 / Reference pixel on axis 1",
        "CRPIX2  =                 51.0 / Reference pixel on axis 2",
        "CRVAL1  =                 50.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                 51.0 / Value at ref. pixel on axis 2",
        "CTYPE1  = 'x1      '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'FREQ    '           / Type of co-ordinate on axis 2", // codespell:ignore
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CDELT2  =                  1.0 / Pixel size on axis 2",
        "CUNIT2  = 'GHz     '           / Units for axis 2",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "SSYSSRC = 'HELIOCEN'           / Standard of rest for source redshift",
        "ZSOURCE = 0.0033412228058471438 / [] Redshift of source",
        "VELOSYS =   29336.889452278185 / [m/s] Topo. apparent velocity of rest frame",
    };
    /** C's cards for midpoint (C probe {@code spectral_axes_probe}, case {@code midpoint}). */
    private static final String[] C_MIDPOINT = {
        "wrote 1",
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  200",
        "CRPIX1  =                 50.0 / Reference pixel on axis 1",
        "CRPIX2  =                101.0 / Reference pixel on axis 2",
        "CRVAL1  =                 50.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                101.0 / Value at ref. pixel on axis 2",
        "CTYPE1  = 'x1      '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'FREQ    '           / Type of co-ordinate on axis 2", // codespell:ignore
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CDELT2  =                  1.0 / Pixel size on axis 2",
        "CUNIT2  = 'GHz     '           / Units for axis 2",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "VELOSYS =   29336.889452278185 / [m/s] Topo. apparent velocity of rest frame",
    };
    /** C's cards for mhz (C probe {@code spectral_axes_probe}, case {@code mhz}). */
    private static final String[] C_MHZ = {
        "wrote 1",
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CRPIX1  =                 50.0 / Reference pixel on axis 1",
        "CRPIX2  =                 51.0 / Reference pixel on axis 2",
        "CRVAL1  =                 50.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                 51.0 / Value at ref. pixel on axis 2",
        "CTYPE1  = 'x1      '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'FREQ    '           / Type of co-ordinate on axis 2", // codespell:ignore
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CDELT2  =                  1.0 / Pixel size on axis 2",
        "CUNIT2  = 'MHz     '           / Units for axis 2",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "VELOSYS =   29336.889452278185 / [m/s] Topo. apparent velocity of rest frame",
    };
    /** C's cards for nodims (C probe {@code spectral_axes_probe}, case {@code nodims}). */
    private static final String[] C_NODIMS = {
        "wrote 1",
        "NAXIS   =                    2",
        "CRPIX1  =                  1.0 / Reference pixel on axis 1",
        "CRPIX2  =                  1.0 / Reference pixel on axis 2",
        "CRVAL1  =                  1.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                  1.0 / Value at ref. pixel on axis 2",
        "CTYPE1  = 'x1      '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'FREQ    '           / Type of co-ordinate on axis 2", // codespell:ignore
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CDELT2  =                  1.0 / Pixel size on axis 2",
        "CUNIT2  = 'GHz     '           / Units for axis 2",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "VELOSYS =   29336.889452278185 / [m/s] Topo. apparent velocity of rest frame",
    };
    /** C's cards for wave_f2w (C probe {@code spectral_axes_probe}, case {@code wave_f2w}). */
    private static final String[] C_WAVE_F2W = {
        "wrote 1",
        "NAXIS   =                    1",
        "NAXIS1  =                  100",
        "CRPIX1  =                  2.0 / Reference pixel on axis 1",
        "CRVAL1  =           149.896229 / Value at ref. pixel on axis 1",
        "CTYPE1  = 'WAVE-F2W'           / Type of co-ordinate on axis 1", // codespell:ignore
        "CDELT1  =  -74.948114841600017 / Pixel size on axis 1",
        "CUNIT1  = 'm       '           / Units for axis 1",
        "SPECSYS = 'BARYCENT'           / Standard of rest for spectral axis",
        "VELOSYS =   29327.576999852943 / [m/s] Topo. apparent velocity of rest frame",
        "RESTWAV =  0.21106199521261618 / [m] Rest wavelength",
    };
    /** C's cards for refpos_1d (C probe {@code spectral_axes_probe}, case {@code refpos_1d}). */
    private static final String[] C_REFPOS_1D = {
        "wrote 1",
        "NAXIS   =                    1",
        "NAXIS1  =                  100",
        "WCSAXES =                    3 / Number of WCS axes",
        "CRPIX1  =                 51.0 / Reference pixel on axis 1",
        "CRPIX2  =                  1.0 / Reference pixel on axis 2",
        "CRPIX3  =                  1.0 / Reference pixel on axis 3",
        "CRVAL1  =           51000000.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                150.0 / Value at ref. pixel on axis 2",
        "CRVAL3  =                -30.0 / Value at ref. pixel on axis 3",
        "CTYPE1  = 'FREQ    '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'RA---TAN'           / Type of co-ordinate on axis 2", // codespell:ignore
        "CTYPE3  = 'DEC--TAN'           / Type of co-ordinate on axis 3", // codespell:ignore
        "CDELT1  =            1000000.0 / Pixel size on axis 1",
        "CDELT2  = -0.017453292519943295 / Pixel size on axis 2",
        "CDELT3  = 0.017453292519943295 / Pixel size on axis 3",
        "CUNIT1  = 'Hz      '           / Units for axis 1",
        "MJD-OBS =   53370.749257130272 / Modified Julian Date of observation",
        "DATE-OBS= '2004-12-31T17:58:55.816' / Date of observation",
        "RADESYS = 'FK5     '           / Reference frame for RA/DEC values",
        "EQUINOX =               2000.0 / [yr] Epoch of reference equinox",
        "SPECSYS = 'LSRK    '           / Standard of rest for spectral axis",
        "VELOSYS =  -8800.6985066334782 / [m/s] Topo. apparent velocity of rest frame",
        "RESTFRQ =         1420400000.0 / [Hz] Rest frequency",
    };
    /** C's cards for refpos_1d_norest (C probe {@code spectral_axes_probe}, case {@code refpos_1d_norest}). */
    private static final String[] C_REFPOS_1D_NOREST = {
        "wrote 1",
        "NAXIS   =                    1",
        "NAXIS1  =                  100",
        "CRPIX1  =                 51.0 / Reference pixel on axis 1",
        "CRVAL1  =           51000000.0 / Value at ref. pixel on axis 1",
        "CTYPE1  = 'FREQ    '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CDELT1  =            1000000.0 / Pixel size on axis 1",
        "CUNIT1  = 'Hz      '           / Units for axis 1",
        "SPECSYS = 'LSRK    '           / Standard of rest for spectral axis",
        "VELOSYS =  -14029.888048206089 / [m/s] Topo. apparent velocity of rest frame",
    };
    /** C's cards for refpos_sky (C probe {@code spectral_axes_probe}, case {@code refpos_sky}). */
    private static final String[] C_REFPOS_SKY = {
        "wrote 1",
        "NAXIS   =                    3",
        "NAXIS1  =                   10",
        "NAXIS2  =                   10",
        "CRPIX1  =                  5.0 / Reference pixel on axis 1",
        "CRPIX2  =                  5.0 / Reference pixel on axis 2",
        "CRPIX3  =                  1.0 / Reference pixel on axis 3",
        "CRVAL1  =                150.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =                -30.0 / Value at ref. pixel on axis 2",
        "CRVAL3  =         1391000000.0 / Value at ref. pixel on axis 3",
        "CTYPE1  = 'RA---TAN'           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'DEC--TAN'           / Type of co-ordinate on axis 2", // codespell:ignore
        "CTYPE3  = 'FREQ    '           / Type of co-ordinate on axis 3", // codespell:ignore
        "CDELT1  =             -10.0E-4 / Pixel size on axis 1",
        "CDELT2  =                0.001 / Pixel size on axis 2",
        "CDELT3  =            1000000.0 / Pixel size on axis 3",
        "CUNIT3  = 'Hz      '           / Units for axis 3",
        "MJD-OBS =              53000.0 / Modified Julian Date of observation",
        "DATE-OBS= '2003-12-27T00:00:00.000' / Date of observation",
        "RADESYS = 'FK5     '           / Reference frame for RA/DEC values",
        "EQUINOX =               2000.0 / [yr] Epoch of reference equinox",
        "SPECSYS = 'LSRK    '           / Standard of rest for spectral axis",
        "VELOSYS =  -9733.4645274796458 / [m/s] Topo. apparent velocity of rest frame",
        "RESTFRQ =         1420405752.0 / [Hz] Rest frequency",
    };
    /** C's cards for refpos_sky_gal (C probe {@code spectral_axes_probe}, case {@code refpos_sky_gal}). */
    private static final String[] C_REFPOS_SKY_GAL = {
        "wrote 1",
        "NAXIS   =                    3",
        "NAXIS1  =                   10",
        "NAXIS2  =                   10",
        "CRPIX1  =                  5.0 / Reference pixel on axis 1",
        "CRPIX2  =                  5.0 / Reference pixel on axis 2",
        "CRPIX3  =                  1.0 / Reference pixel on axis 3",
        "CRVAL1  =   264.20062324371702 / Value at ref. pixel on axis 1",
        "CRVAL2  =   19.693479497483363 / Value at ref. pixel on axis 2",
        "CRVAL3  =         1391000000.0 / Value at ref. pixel on axis 3",
        "CTYPE1  = 'GLON-TAN'           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'GLAT-TAN'           / Type of co-ordinate on axis 2", // codespell:ignore
        "CTYPE3  = 'FREQ    '           / Type of co-ordinate on axis 3", // codespell:ignore
        "CDELT1  =             -10.0E-4 / Pixel size on axis 1",
        "CDELT2  = 0.00099999999998380486 / Pixel size on axis 2",
        "CDELT3  =            1000000.0 / Pixel size on axis 3",
        "PC1_1   =  0.76588220346060187 / Transformation matrix element",
        "PC1_2   =  0.64298090984284539 / Transformation matrix element",
        "PC2_1   = -0.64298090987813616 / Transformation matrix element",
        "PC2_2   =  0.76588220343097413 / Transformation matrix element",
        "CUNIT3  = 'Hz      '           / Units for axis 3",
        "MJD-OBS =              53000.0 / Modified Julian Date of observation",
        "DATE-OBS= '2003-12-27T00:00:00.000' / Date of observation",
        "SPECSYS = 'LSRK    '           / Standard of rest for spectral axis",
        "VELOSYS =  -9733.4645274796458 / [m/s] Topo. apparent velocity of rest frame",
        "RESTFRQ =         1420405752.0 / [Hz] Rest frequency",
    };
    /** C's cards for dsb (C probe {@code spectral_axes_probe}, case {@code dsb}). */
    private static final String[] C_DSB = {
        "wrote 1",
        "NAXIS   =                    1",
        "NAXIS1  =                  100",
        "CRPIX1  =                 51.0 / Reference pixel on axis 1",
        "CRVAL1  =                0.051 / Value at ref. pixel on axis 1",
        "CTYPE1  = 'FREQ    '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CDELT1  =                0.001 / Pixel size on axis 1",
        "CUNIT1  = 'GHz     '           / Units for axis 1",
        "SPECSYS = 'LSRK    '           / Standard of rest for spectral axis",
        "VELOSYS =   29046.889455035784 / [m/s] Topo. apparent velocity of rest frame",
        "RESTFRQ =       230500000000.0 / [Hz] Rest frequency",
        "IMAGFREQ=   237501227871.66501 / [Hz] Image frequency",
    };
    /** C's cards for dsb_norest (C probe {@code spectral_axes_probe}, case {@code dsb_norest}). */
    private static final String[] C_DSB_NOREST = {
        "wrote 1",
        "NAXIS   =                    1",
        "NAXIS1  =                  100",
        "CRPIX1  =                 51.0 / Reference pixel on axis 1",
        "CRVAL1  =                0.051 / Value at ref. pixel on axis 1",
        "CTYPE1  = 'FREQ    '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CDELT1  =                0.001 / Pixel size on axis 1",
        "CUNIT1  = 'GHz     '           / Units for axis 1",
        "SPECSYS = 'LSRK    '           / Standard of rest for spectral axis",
        "VELOSYS =   29046.889455018489 / [m/s] Topo. apparent velocity of rest frame",
    };
    /** C's cards for source_sor (C probe {@code spectral_axes_probe}, case {@code source_sor}). */
    private static final String[] C_SOURCE_SOR = {
        "wrote 1",
        "NAXIS   =                    1",
        "NAXIS1  =                  100",
        "CRPIX1  =                 51.0 / Reference pixel on axis 1",
        "CRVAL1  =           51000000.0 / Value at ref. pixel on axis 1",
        "CTYPE1  = 'FREQ    '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CDELT1  =            1000000.0 / Pixel size on axis 1",
        "CUNIT1  = 'Hz      '           / Units for axis 1",
        "SPECSYS = 'SOURCE  '           / Standard of rest for spectral axis",
        "SSYSSRC = 'LSRK    '           / Standard of rest for source redshift",
        "ZSOURCE = 0.0033421933742774357 / [] Redshift of source",
        "VELOSYS =   1029336.5534594683 / [m/s] Topo. apparent velocity of rest frame",
        "RESTFRQ =         1420400000.0 / [Hz] Rest frequency",
    };
    /** C's cards for zsource_vrad (C probe {@code spectral_axes_probe}, case {@code zsource_vrad}). */
    private static final String[] C_ZSOURCE_VRAD = {
        "wrote 1",
        "NAXIS   =                    1",
        "NAXIS1  =                  100",
        "CRPIX1  =                 51.0 / Reference pixel on axis 1",
        "CRVAL1  =                 51.0 / Value at ref. pixel on axis 1",
        "CTYPE1  = 'VRAD    '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CUNIT1  = 'km/s    '           / Units for axis 1",
        "SPECSYS = 'BARYCENT'           / Standard of rest for spectral axis",
        "SSYSSRC = 'HELIOCEN'           / Standard of rest for source redshift",
        "ZSOURCE = -6.6690580876693772E-4 / [] Redshift of source",
        "VELOSYS =   29327.576999852943 / [m/s] Topo. apparent velocity of rest frame",
        "RESTFRQ =         1420400000.0 / [Hz] Rest frequency",
    };
    /** C's cards for quad_notab (C probe {@code spectral_axes_probe}, case {@code quad_notab}). */
    private static final String[] C_QUAD_NOTAB = {
        "wrote 0", "NAXIS   =                    1", "NAXIS1  =                  100",
    };
    /** C's cards for forcetab_notabok (C probe {@code spectral_axes_probe}, case {@code forcetab_notabok}). */
    private static final String[] C_FORCETAB_NOTABOK = {
        "wrote 1",
        "NAXIS   =                    1",
        "NAXIS1  =                  100",
        "CRPIX1  =                 50.0 / Reference pixel on axis 1",
        "CRVAL1  =                 0.05 / Value at ref. pixel on axis 1",
        "CTYPE1  = 'FREQ    '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CDELT1  =                0.001 / Pixel size on axis 1",
    };

    /** A FrameSet from a 2-D pixel Frame to a CmpFrame of a 1-D Frame and {@code spec}. */
    private static FrameSet twoAxis(Frame spec, Mapping map) {
        Frame pixel = new Frame(2);
        pixel.setDomain("GRID");
        FrameSet fs = new FrameSet(pixel);
        fs.addFrame(FrameSet.AST__BASE, map != null ? map : new UnitMap(2), new CmpFrame(new Frame(1), spec));
        return fs;
    }

    /** A FrameSet from a 1-D pixel Frame to {@code spec} through {@code map}. */
    private static FrameSet oneAxis(Frame spec, Mapping map) {
        Frame pixel = new Frame(1);
        pixel.setDomain("GRID");
        FrameSet fs = new FrameSet(pixel);
        fs.addFrame(FrameSet.AST__BASE, map, spec);
        return fs;
    }

    private static SpecFrame spec(String settings) {
        SpecFrame sf = new SpecFrame();
        sf.set(settings);
        return sf;
    }

    /**
     * What C's probe prints for a write of {@code fs} with FITS-WCS into a FitsChan with {@code
     * attrs} set and holding NAXIS (the number of pixel axes) and, unless zero, NAXIS1 and NAXIS2:
     * the value the write returns, then the cards.
     */
    private static List<String> written(FrameSet fs, String attrs, int n1, int n2) {
        FitsChan fc = new FitsChan();
        fc.setEncoding("FITS-WCS");
        if (!attrs.isEmpty()) {
            fc.set(attrs);
        }
        fc.putFits("NAXIS   = " + fs.getNin(), false);
        if (n1 != 0) {
            fc.putFits("NAXIS1  = " + n1, false);
        }
        if (n2 != 0) {
            fc.putFits("NAXIS2  = " + n2, false);
        }
        List<String> got = new ArrayList<>();
        got.add("wrote " + fc.write(fs));
        got.addAll(FitsHeaders.foundCards(fc));
        return got;
    }

    private static void assertWritesAsC(String[] want, FrameSet fs, String attrs, int n1, int n2) {
        assertWritesAsC(want, fs, attrs, n1, n2, Collections.<String, Double>emptyMap());
    }

    /**
     * {@link #assertWritesAsC(String[], FrameSet, String, int, int)}, the number on a card whose
     * keyword is a key of {@code atol} needing only to be within that absolute tolerance of C's.
     */
    private static void assertWritesAsC(
            String[] want, FrameSet fs, String attrs, int n1, int n2, Map<String, Double> atol) {
        List<String> got = written(fs, attrs, n1, n2);
        List<String> w = new ArrayList<>();
        w.add(want[0]);
        w.addAll(FitsHeaders.padded(Arrays.copyOfRange(want, 1, want.length)));
        assertTrue(FitsHeaders.cardsMatchC(got, w, Collections.<String>emptySet(), atol), String.join("\n", got));
    }

    /** {@link FitsSpectralAxes#spectralAxes} for {@code fs}, with the FITS axes in Frame order. */
    private static Mapping spectralAxes(
            FrameSet fs, double[] dim, FitsStore store, double[] crvals, boolean[] axisDone) {
        int nwcs = fs.getNout();
        int[] wperm = new int[nwcs];
        for (int i = 0; i < nwcs; i++) {
            wperm[i] = i;
        }
        return FitsSpectralAxes.spectralAxes(
                new FitsChan(), fs, dim, wperm, ' ', store, crvals, axisDone, "astWrite", "FitsChan");
    }

    /** The PolyMap of the Taylor series to degree 20 of {@code sr exp(slope p)}. */
    private static PolyMap exponential(double sr, double slope) {
        double[] coeffs = new double[21 * 3];
        double term = sr;
        for (int k = 0; k <= 20; k++) {
            coeffs[3 * k] = term;
            coeffs[3 * k + 1] = 1;
            coeffs[3 * k + 2] = k;
            term = term * slope / (k + 1);
        }
        PolyMap pm = new PolyMap(1, 1, 21, coeffs, 0, new double[0]);
        pm.set("IterInverse=1");
        return pm;
    }

    /** The PolyMap {@code c0 + c1 p + c2 p^2}. */
    private static PolyMap quadratic(double c0, double c1, double c2) {
        PolyMap pm = new PolyMap(1, 1, 3, new double[] {c0, 1, 0, c1, 1, 1, c2, 1, 2}, 0, new double[0]);
        pm.set("IterInverse=1");
        return pm;
    }

    // ------------------------------------------------------------------
    // spectral_axes.rs
    // ------------------------------------------------------------------

    @Test
    void noSpectralAxisReturnsUnitMap() {
        FrameSet fs = new FrameSet(new Frame(2));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), new Frame(2));
        FitsStore store = new FitsStore(2);
        boolean[] axisDone = new boolean[2];
        Mapping map = spectralAxes(fs, new double[] {100, 100}, store, null, axisDone);
        assertInstanceOf(UnitMap.class, map);
        assertEquals(2, map.getNin());
        assertFalse(axisDone[0] || axisDone[1]);
        assertNull(store.ctype[0]);
        assertNull(store.ctype[1]);
    }

    @Test
    void freqSpecAxisWritesKeywords() {
        FitsStore store = new FitsStore(2);
        boolean[] axisDone = new boolean[2];
        Mapping map = spectralAxes(
                twoAxis(spec("System=FREQ"), null),
                new double[] {100, 100},
                store,
                new double[] {BAD, 1.42e9},
                axisDone);
        assertFalse(axisDone[0]);
        assertTrue(axisDone[1]);
        assertEquals("FREQ", store.ctype[1]);
        assertEquals("GHz", store.cunit[1]);
        assertEquals(1.42e9, store.crval[1]);
        assertEquals("HELIOCEN", store.specsys);
        // C's VELOSYS for this SpecFrame (case freq_basic).
        assertEquals(29336.889452278185, store.velosys, 4 * Math.ulp(29336.889452278185));
        assertEquals(2, map.getNin());
        assertEquals(2, map.getNout());
        assertArrayEquals(new double[] {10, 0}, map.tranN(1, 2, new double[] {10, 1.42e9}, true, 2), 1e-3);
        assertWritesAsC(C_FREQ_BASIC, twoAxis(spec("System=FREQ"), null), "", 100, 100);
    }

    @Test
    void restwavEmittedForWavelengthSystems() {
        FitsStore store = new FitsStore(2);
        boolean[] axisDone = new boolean[2];
        spectralAxes(
                twoAxis(spec("System=WAVE,RestFreq=1.42"), null),
                new double[] {100, 100},
                store,
                new double[] {BAD, 0.21},
                axisDone);
        assertEquals(AST__C / 1.42e9, store.restwav, 1e-9);
        assertNull(store.restfrq);
        assertWritesAsC(C_RESTWAV, twoAxis(spec("System=WAVE,RestFreq=1.42"), null), "", 100, 100);
    }

    @Test
    void obsgeoEmittedWhenObserverSet() {
        String settings = "System=FREQ,ObsLon=E0:0:0,ObsLat=N51:28:40,ObsAlt=0";
        FitsStore store = new FitsStore(2);
        spectralAxes(
                twoAxis(spec(settings), null),
                new double[] {100, 100},
                store,
                new double[] {BAD, 1.42e9},
                new boolean[2]);
        double r = Math.sqrt(
                store.obsgeox * store.obsgeox + store.obsgeoy * store.obsgeoy + store.obsgeoz * store.obsgeoz);
        assertTrue(Math.abs(r - 6_371_000.0) < 50_000.0, Double.toString(r));
        assertWritesAsC(C_OBSGEO, twoAxis(spec(settings), null), "", 100, 100);
    }

    @Test
    void labelOverrideEmitsCtypeCommentAndCname() {
        String settings = "System=FREQ,Label(1)=MyFrequency";
        FitsStore store = new FitsStore(2);
        spectralAxes(
                twoAxis(spec(settings), null),
                new double[] {100, 100},
                store,
                new double[] {BAD, 1.42e9},
                new boolean[2]);
        assertEquals("MyFrequency", store.ctypeCom[1]);
        assertEquals("MyFrequency", store.cname[1]);
        // C writes the label as the CTYPE comment; FITS-WCS writes no CNAME for it.
        assertWritesAsC(C_LABEL, twoAxis(spec(settings), null), "", 100, 100);
    }

    @Test
    void sourceVelocityEmitsZsourceSsyssrc() {
        String settings = "System=FREQ,SourceVel=1000";
        FitsStore store = new FitsStore(2);
        spectralAxes(
                twoAxis(spec(settings), null),
                new double[] {100, 100},
                store,
                new double[] {BAD, 1.42e9},
                new boolean[2]);
        double expected = Math.sqrt((AST__C + 1.0e6) / (AST__C - 1.0e6)) - 1.0;
        assertEquals(expected, store.zsource, 1e-9);
        assertEquals("HELIOCEN", store.ssyssrc);
        assertWritesAsC(C_SOURCEVEL, twoAxis(spec(settings), null), "", 100, 100);
    }

    @Test
    void crvalDefaultUsesBoundsMidpoint() {
        FitsStore store = new FitsStore(2);
        spectralAxes(twoAxis(spec("System=FREQ"), null), new double[] {100, 200}, store, null, new boolean[2]);
        assertEquals(101.0, store.crval[1]);
        assertWritesAsC(C_MIDPOINT, twoAxis(spec("System=FREQ"), null), "", 100, 200);
    }

    @Test
    void crvalDefaultIsPixelOneWithoutTheSpectralSize() {
        assertWritesAsC(C_NODIMS, twoAxis(spec("System=FREQ"), null), "", 0, 0);
    }

    @Test
    void linearUnitMhzPreservesUserCunit() {
        FitsStore store = new FitsStore(2);
        spectralAxes(
                twoAxis(spec("System=FREQ,Unit(1)=MHz"), null), new double[] {100, 100}, store, null, new boolean[2]);
        assertEquals("MHz", store.cunit[1]);
        assertWritesAsC(C_MHZ, twoAxis(spec("System=FREQ,Unit(1)=MHz"), null), "", 100, 100);
    }

    @Test
    void nonlinearUnitIsCleared() {
        // The conversion from log(Hz) to the default Unit is not linear, so C clears the Unit:
        // CUNIT is the default, GHz, and the CRVAL of 9 (log10 of 1 GHz in Hz) is converted to it.
        // The axis, linear in log(Hz), is exponential in GHz, and C writes it with -LOG (C probe
        // fitslog_probe, case write_log_unit_unitmap, writes this FrameSet: C_LOG_UNIT).
        FitsStore store = new FitsStore(2);
        spectralAxes(
                twoAxis(spec("System=FREQ,Unit(1)=log(Hz)"), null),
                new double[] {100, 100},
                store,
                new double[] {BAD, 9.0},
                new boolean[2]);
        assertEquals("GHz", store.cunit[1]);
        assertEquals(1.0, store.crval[1], 1e-9);
        assertEquals("FREQ-LOG", store.ctype[1]);
        assertWritesAsC(C_LOG_UNIT, twoAxis(spec("System=FREQ,Unit(1)=log(Hz)"), null), "", 100, 100);
    }

    /**
     * C's cards for a FREQ axis in log(Hz) that is the second pixel axis (C probe {@code
     * fitslog_probe}, case {@code write_log_unit_unitmap}).
     */
    private static final String[] C_LOG_UNIT = {
        "wrote 1",
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CRPIX1  =                 50.0 / Reference pixel on axis 1",
        "CRPIX2  =                100.0 / Reference pixel on axis 2",
        "CRVAL1  =                 50.0 / Value at ref. pixel on axis 1",
        "CRVAL2  =              1.0E+91 / Value at ref. pixel on axis 2",
        "CTYPE1  = 'x1      '           / Type of co-ordinate on axis 1", // codespell:ignore
        "CTYPE2  = 'FREQ-LOG'           / Type of co-ordinate on axis 2", // codespell:ignore
        "CDELT1  =                  1.0 / Pixel size on axis 1",
        "CDELT2  = 2.3025850929940716E+91 / Pixel size on axis 2",
        "CUNIT2  = 'GHz     '           / Units for axis 2",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "VELOSYS =   29336.889452278185 / [m/s] Topo. apparent velocity of rest frame",
    };

    @Test
    void x2pCascadePicksFreqForWavelengthAxis() {
        // Pixel axis 2 linear in frequency, 1e9 + 1e6 p Hz, the SpecFrame then set to wavelength
        // through the FrameSet.
        Mapping map = new CmpMap(
                new UnitMap(1), new CmpMap(new ZoomMap(1, 1.0e6), new ShiftMap(new double[] {1.0e9}), true), false);
        FrameSet fs = twoAxis(spec("System=FREQ,Unit=Hz"), map);
        fs.setC("System(2)", "WAVE");
        FitsStore store = new FitsStore(2);
        boolean[] axisDone = new boolean[2];
        spectralAxes(fs, new double[] {100, 100}, store, new double[] {BAD, AST__C / 1.05e9}, axisDone);
        assertEquals("WAVE-F2W", store.ctype[1]);
        assertTrue(axisDone[1]);
    }

    // ------------------------------------------------------------------
    // Writes compared with C
    // ------------------------------------------------------------------

    @Test
    void waveF2wWritesAsC() {
        // testfitschan.c's non-linear WAVE-F2W FrameSet.
        FrameSet fs = oneAxis(spec("System=FREQ,Unit=Hz,StdOfRest=Bary,RestFreq=1.4204E9 Hz"), new ZoomMap(1, 1.0e6));
        fs.set("System=WAVE");
        fs.set("Unit(1)=m");
        assertWritesAsC(C_WAVE_F2W, fs, "", 100, 0);
    }

    @Test
    void referencePositionAddsCelestialAxes() {
        // MakeFitsFrameSet adds an FK5 SkyFrame for a 1-D SpecFrame with RefRA and RefDec set.
        FrameSet fs = oneAxis(
                spec("System=FREQ,Unit=Hz,StdOfRest=LSRK,RefRA=10:00:00,RefDec=-30:00:00,Epoch=2005.0,"
                        + "RestFreq=1.4204E9 Hz"),
                new ZoomMap(1, 1.0e6));
        assertWritesAsC(C_REFPOS_1D, fs, "", 100, 0);
        assertEquals(1, fs.getNout(), "the FrameSet written is not changed");
    }

    @Test
    void referenceRaAloneAddsNoAxes() {
        FrameSet fs = oneAxis(spec("System=FREQ,Unit=Hz,StdOfRest=LSRK,RefRA=10:00:00"), new ZoomMap(1, 1.0e6));
        assertWritesAsC(C_REFPOS_1D_NOREST, fs, "", 100, 0);
    }

    @Test
    void referencePositionIsTheCelestialReferencePoint() {
        // MakeFitsFrameSet sets RefRA and RefDec to the fiducial point of the celestial axes, so
        // VELOSYS is that of (150, -30), whatever the SpecFrame had; also with the celestial
        // axes galactic, which are set to FK5 J2000 to find it.
        FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.chan(
                "NAXIS   = 3",
                "NAXIS1  = 10",
                "NAXIS2  = 10",
                "NAXIS3  = 20",
                "CRPIX1  = 5",
                "CRPIX2  = 5",
                "CRPIX3  = 10",
                "CRVAL1  = 150",
                "CRVAL2  = -30",
                "CRVAL3  = 1.4E9",
                "CDELT1  = -0.001",
                "CDELT2  = 0.001",
                "CDELT3  = 1.0E6",
                "CTYPE1  = 'RA---TAN'",
                "CTYPE2  = 'DEC--TAN'",
                "CTYPE3  = 'FREQ'",
                "CUNIT3  = 'Hz'",
                "SPECSYS = 'LSRK'",
                "RESTFRQ = 1.420405752E9",
                "RADESYS = 'FK5'",
                "EQUINOX = 2000.0",
                "MJD-OBS = 53000.0"));
        fs.set("RefRA=0:0:0,RefDec=0:0:0");
        assertEquals("0:00:00.0", fs.getC("RefRA"));
        assertEquals("0:00:00", fs.getC("RefDec"));
        assertWritesAsC(C_REFPOS_SKY, fs, "", 10, 10);
        assertEquals("0:00:00.0", fs.getC("RefRA"), "the FrameSet written is not changed");
        fs.set("System(1)=GALACTIC");
        // C fits the galactic TAN projection (FitLine) to points transformed through SphMaps and
        // SlaMaps, whose sines and arctangents in java.lang.Math differ from C's libm by about an
        // ULP, by different amounts on different processors and Java runtimes. An ULP of the
        // galactic longitude (264 degrees, 6e-14) over the few pixels the fit spans is about 1e-11
        // of the 0.001 degree pixel size, so the fitted CDELT2 and PC elements need only agree
        // with C's to that relative tolerance.
        Map<String, Double> fitted = new HashMap<>();
        fitted.put("CDELT2", 1.0e-14);
        for (String pc : new String[] {"PC1_1", "PC1_2", "PC2_1", "PC2_2"}) {
            fitted.put(pc, 1.0e-11);
        }
        assertWritesAsC(C_REFPOS_SKY_GAL, fs, "", 10, 10, fitted);
    }

    @Test
    void dsbSpecFrameWritesImagfreq() {
        DSBSpecFrame dsb = new DSBSpecFrame();
        dsb.set("System=FREQ,Unit=GHz,StdOfRest=LSRK,RestFreq=230.5,DSBCentre=230,IF=4,SideBand=USB");
        assertWritesAsC(C_DSB, oneAxis(dsb, new ZoomMap(1, 0.001)), "", 100, 0);
    }

    @Test
    void dsbSpecFrameWithoutRestFreqWritesNoImagfreq() {
        DSBSpecFrame dsb = new DSBSpecFrame();
        dsb.set("System=FREQ,Unit=GHz,StdOfRest=LSRK,DSBCentre=230,IF=4,SideBand=USB");
        assertWritesAsC(C_DSB_NOREST, oneAxis(dsb, new ZoomMap(1, 0.001)), "", 100, 0);
    }

    @Test
    void sourceStandardOfRestWritesTheSourceVelocityAsVelosys() {
        assertWritesAsC(
                C_SOURCE_SOR,
                oneAxis(
                        spec("System=FREQ,Unit=Hz,StdOfRest=Source,SourceVel=1000,SourceVRF=LSRK,RestFreq=1.4204E9 Hz"),
                        new ZoomMap(1, 1.0e6)),
                "",
                100,
                0);
    }

    @Test
    void sourceVelocityKeepsItsRestFrame() {
        FrameSet fs = oneAxis(
                spec("System=VRAD,StdOfRest=Bary,SourceVel=-200,SourceVRF=Helio,RestFreq=1.4204E9 Hz"),
                new ZoomMap(1, 1.0));
        assertWritesAsC(C_ZSOURCE_VRAD, fs, "", 100, 0);
        SpecFrame sf = (SpecFrame) fs.getFrame(FrameSet.AST__CURRENT);
        assertEquals("Heliocentric", sf.getC("SourceVRF"));
        assertEquals(-200.0, sf.getSourceVel(), 1e-9);
    }

    /** C's cards for an exponential frequency axis (C probe {@code fitslog_probe}, case {@code write_freq_exp}). */
    private static final String[] C_LOG = {
        "wrote 1",
        "NAXIS   =                    1",
        "NAXIS1  =                  100",
        "CRPIX1  =                 52.0 / Reference pixel on axis 1",
        "CRVAL1  =   1.4747260395187105 / Value at ref. pixel on axis 1",
        "CTYPE1  = 'FREQ-LOG'           / Type of co-ordinate on axis 1", // codespell:ignore
        "CDELT1  = 0.0014747260395187112 / Pixel size on axis 1",
        "CUNIT1  = 'GHz     '           / Units for axis 1",
        "SPECSYS = 'HELIOCEN'           / Standard of rest for spectral axis",
        "VELOSYS =   29336.889452278185 / [m/s] Topo. apparent velocity of rest frame",
    };

    @Test
    void logarithmicAxisIsWrittenAsLog() {
        assertWritesAsC(C_LOG, oneAxis(spec("System=FREQ"), exponential(1.4, 0.001)), "", 100, 0);
    }

    @Test
    void tabulatedAxisNeedsFitsTable() {
        // C probe cases quad_tab and forcetab: C writes FREQ-TAB, with its table in a FitsTable.
        for (FrameSet fs : Arrays.asList(
                oneAxis(spec("System=FREQ"), quadratic(1.4, 0.001, 0.0001)),
                oneAxis(spec("System=FREQ"), new ZoomMap(1, 0.001)))) {
            String attrs = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT) instanceof ZoomMap
                    ? "TabOK=1,ForceTab=1"
                    : "TabOK=1";
            UnsupportedOperationException e =
                    assertThrows(UnsupportedOperationException.class, () -> written(fs, attrs, 100, 0));
            assertTrue(e.getMessage().endsWith("needs FitsTable"), e.getMessage());
        }
    }

    @Test
    void axisNoAlgorithmDescribesIsNotWritten() {
        // Without TabOK, a quadratic spectral axis is left to OtherAxes, whose linear description
        // MakeIntWorld rejects.
        assertWritesAsC(C_QUAD_NOTAB, oneAxis(spec("System=FREQ"), quadratic(1.4, 0.001, 0.0001)), "", 100, 0);
    }

    @Test
    void forceTabWithoutTabOkLeavesTheAxisToOtherAxes() {
        assertWritesAsC(C_FORCETAB_NOTABOK, oneAxis(spec("System=FREQ"), new ZoomMap(1, 0.001)), "ForceTab=1", 100, 0);
    }

    @Test
    void getFitsSorNamesEachStandardOfRest() {
        String[] ast = {
            "Topocentric",
            "Geocentric",
            "Barycentric",
            "Heliocentric",
            "LSRK",
            "LSRD",
            "Galactic",
            "Local_group",
            "Source"
        };
        String[] fits = {
            "TOPOCENT", "GEOCENTR", "BARYCENT", "HELIOCEN", "LSRK", "LSRD", "GALACTOC", "LOCALGRP", "SOURCE"
        };
        for (int i = 0; i < ast.length; i++) {
            assertEquals(fits[i], FitsSpectralAxes.getFitsSor(ast[i]));
        }
        assertNull(FitsSpectralAxes.getFitsSor("lsrk"));
    }

    // ------------------------------------------------------------------
    // spectral_axes.rs: try_grism_cascade_emits_gri_pv_and_chain
    // ------------------------------------------------------------------

    /** A GrismMap of a grating of 9e5 rulings per metre, first order, about 5e-7 m. */
    private static GrismMap grating() {
        GrismMap g = new GrismMap();
        g.set("GrismG=9.0E5,GrismM=1,GrismWaveR=5.0E-7");
        return g;
    }

    /**
     * A wavelength axis whose Mapping from pixels ends in a GrismMap is described with the {@code
     * -GRI} algorithm: CRVAL is the GrismMap's reference wavelength and PVi_0 and PVi_1 its
     * GrismG and GrismM, as C writes them for the same axis alone (probe {@code
     * probes/grismmap_probe.c}, case {@code write_shift_zoom_grism}).
     */
    @Test
    void tryGrismCascadeEmitsGriPvAndChain() {
        GrismMap g = grating();
        Mapping chain1d =
                new CmpMap(new CmpMap(new ShiftMap(new double[] {-50.0}), new ZoomMap(1, 0.002), true), g, true);
        Mapping chain2d = new CmpMap(new UnitMap(1), chain1d, false);
        FitsStore store = new FitsStore(2);
        boolean[] axisDone = new boolean[2];
        Mapping axmap = spectralAxes(
                twoAxis(spec("System=WAVE,Unit=m"), chain2d), new double[] {100, 100}, store, null, axisDone);
        assertTrue(axisDone[1]);
        assertEquals("WAVE-GRI", store.ctype[1]);
        assertEquals(2, axmap.getNin());
        assertEquals(2, axmap.getNout());
        assertEquals(g.getGrismWaveR(), store.crval[1], 1e-9);
        assertEquals(9.0e5, store.pv[1][0]);
        assertEquals(1.0, store.pv[1][1]);
        for (int m = 2; m < store.pv[1].length; m++) {
            assertNull(store.pv[1][m], "PV2_" + m + " is the default and is not stored");
        }
    }

    // ------------------------------------------------------------------
    // fitschan/fset_to_store/grism.rs: ExtractGrismMap
    // ------------------------------------------------------------------

    @Test
    void leafGrismExtracted() {
        GrismMap g = new GrismMap();
        Mapping[] replacement = new Mapping[1];
        GrismMap extracted = FitsSpectralAxes.extractGrismMap(g, 0, replacement);
        assertInstanceOf(UnitMap.class, replacement[0]);
        assertEquals(1, replacement[0].getNin());
        assertEquals(1, replacement[0].getNout());
        // C clones the GrismMap: the one extracted is the one given.
        assertSame(g, extracted, "the GrismMap is shared, as C clones it");
    }

    @Test
    void invertedGrismNotExtracted() {
        GrismMap g = new GrismMap();
        g.invert();
        Mapping[] replacement = new Mapping[1];
        assertNull(FitsSpectralAxes.extractGrismMap(g, 0, replacement));
        assertNull(replacement[0]);
    }

    @Test
    void shiftThenGrismInSeriesExtracts() {
        GrismMap g = new GrismMap();
        CmpMap chain = new CmpMap(new ShiftMap(new double[] {3.5}), g, true);
        Mapping[] replacement = new Mapping[1];
        GrismMap extracted = FitsSpectralAxes.extractGrismMap(chain, 0, replacement);
        assertSame(g, extracted, "the GrismMap at the end is extracted");
        assertEquals(1, replacement[0].getNin());
        assertEquals(1, replacement[0].getNout());
        assertEquals(4.5, replacement[0].tran1(1, new double[] {1.0}, true)[0]);
    }

    /** The output of a GrismMap followed by a ShiftMap is changed after it, so it is not extracted. */
    @Test
    void grismThenShiftInSeriesDoesNotExtract() {
        CmpMap chain = new CmpMap(new GrismMap(), new ShiftMap(new double[] {3.5}), true);
        assertNull(FitsSpectralAxes.extractGrismMap(chain, 0, new Mapping[1]));
    }

    @Test
    void parallelGrismOnAxisOneExtracts() {
        GrismMap g = new GrismMap();
        CmpMap chain = new CmpMap(new UnitMap(1), g, false);
        Mapping[] replacement = new Mapping[1];
        GrismMap extracted = FitsSpectralAxes.extractGrismMap(chain, 1, replacement);
        assertSame(g, extracted, "the GrismMap producing output 1 is extracted");
        assertEquals(2, replacement[0].getNin());
        assertEquals(2, replacement[0].getNout());
    }

    @Test
    void parallelGrismWrongAxisDoesNotExtract() {
        CmpMap chain = new CmpMap(new UnitMap(1), new GrismMap(), false);
        assertNull(FitsSpectralAxes.extractGrismMap(chain, 0, new Mapping[1]));
    }

    @Test
    void noGrismAnywhereReturnsNone() {
        CmpMap chain = new CmpMap(new ShiftMap(new double[] {1.0}), new UnitMap(1), true);
        assertNull(FitsSpectralAxes.extractGrismMap(chain, 0, new Mapping[1]));
    }

    /**
     * Each component is set, for the search, to the Invert value it had when the CmpMap was made,
     * as C sets it: a GrismMap inverted after it was combined is still found, and is left with the
     * Invert value it had.
     */
    @Test
    void aComponentIsSearchedAsItWasCombined() {
        GrismMap g = new GrismMap();
        CmpMap chain = new CmpMap(new ShiftMap(new double[] {1.0}), g, true);
        g.invert();
        assertSame(g, FitsSpectralAxes.extractGrismMap(chain, 0, new Mapping[1]));
        assertTrue(g.getInvert());
    }
}
