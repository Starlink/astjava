package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.OracleFile;
import uk.ac.starlink.ast.testsupport.OracleSection;

/**
 * Translation of Rust {@code tests/test_skyref_offset_mapping.rs}: {@code
 * wcsconv/inputs/skyref.head} describes an offset SkyFrame ({@code SREFIS = 'ORIGIN'}), and C's
 * {@code TidyOffsets} sets SkyRefIs through the FrameSet, so the pixel-to-sky Mapping read rotates
 * into the offset sky system. The Mapping from the base to the current Frame transforms the inputs
 * of the forward {@code skyref.head} section of {@code oracle/headers.oracle} to C's outputs.
 *
 * <p>The expected values are read from the oracle file rather than copied, so they are those of the
 * C library this port follows; the tolerance is the Rust test's.
 */
class SkyrefOffsetMappingTest {

    private static final String RELPATH = "wcsconv/inputs/skyref.head";

    private static final double TOL = 1e-9;

    @Test
    void skyrefOffsetMappingMatchesOracle() {
        OracleSection fwd = null;
        List<OracleSection> sections = OracleFile.parse(Fixtures.root().resolve("oracle/headers.oracle"));
        for (OracleSection s : sections) {
            if (RELPATH.equals(s.path) && s.forward) {
                fwd = s;
            }
        }
        assertNotNull(fwd, "the oracle has a forward section for " + RELPATH);
        FitsChan fc = FitsChan.fromHeader(Fixtures.read(RELPATH));
        FrameSet fs = assertInstanceOf(FrameSet.class, fc.read(), "reads a FrameSet");
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        double[][] in = fwd.inputs();
        double[][] want = fwd.outputs();
        double[][] got = map.tranP(fwd.npoint(), fwd.nin, in, true, fwd.nout);
        for (int row = 0; row < fwd.npoint(); row++) {
            for (int axis = 0; axis < fwd.nout; axis++) {
                double w = want[axis][row];
                double g = got[axis][row];
                String where =
                        "pixel (" + in[0][row] + ", " + in[1][row] + ") axis " + axis + ": got " + g + ", C " + w;
                if (w == CValues.BAD) {
                    assertEquals(CValues.BAD, g, where);
                } else {
                    assertTrue(Math.abs(g - w) < TOL, where);
                }
            }
        }
    }
}
