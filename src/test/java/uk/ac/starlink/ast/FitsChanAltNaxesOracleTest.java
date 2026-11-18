package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_alt_naxes_oracle.rs}: how a FITS-WCS write
 * describes axis descriptions with different numbers of axes, against C. {@code WcsFromStore}
 * (fitschan.c) bounds its per-axis loops, for each version, by WCSAXES if the version has one, and
 * otherwise by the highest CRPIX index stored for it.
 *
 * <p>Each FrameSet has a 3-D GRID Frame, a primary Frame and an alternate Frame (Ident {@code A}),
 * joined to the GRID Frame by PermMaps that drop or add axes, and is written with {@code NAXIS =
 * 3}. Compared with C: the value the write returns and the cards written. The cases are those of
 * {@code oracle/fitschan_alt_naxes.json}, made by {@code
 * tools/oracle_fixtures/fitschan_alt_naxes_probe.c}.
 */
class FitsChanAltNaxesOracleTest {

    /**
     * The probe's Mapping from 3 pixel axes to {@code nout} axes: a PermMap passing the axes they
     * share and giving any extra output the value 5, then a ShiftMap adding {@code shift + i} to
     * output {@code i}.
     */
    private static Mapping mapTo(int nout, double shift) {
        int[] inperm = new int[3];
        for (int i = 0; i < 3; i++) {
            inperm[i] = i < nout ? i + 1 : -1;
        }
        int[] outperm = new int[nout];
        double[] shifts = new double[nout];
        for (int i = 0; i < nout; i++) {
            outperm[i] = i < 3 ? i + 1 : -1;
            shifts[i] = shift + i;
        }
        PermMap perm = new PermMap(3, inperm, nout, outperm, new double[] {5.0});
        return new CmpMap(perm, new ShiftMap(shifts), true);
    }

    /** The value the write returns and the cards written. */
    private static List<String> written(int nprim, int nalt) {
        Frame grid = new Frame(3);
        grid.setDomain("GRID");
        Frame prim = new Frame(nprim);
        prim.setDomain("PRI");
        Frame alt = new Frame(nalt);
        alt.setDomain("ALT");
        alt.setIdent("A");
        FrameSet fs = new FrameSet(grid);
        fs.addFrame(FrameSet.AST__BASE, mapTo(nprim, 10.0), prim);
        fs.addFrame(FrameSet.AST__BASE, mapTo(nalt, 100.0), alt);
        fs.setCurrent(2);
        FitsChan fc = new FitsChan();
        fc.setEncoding("FITS-WCS");
        fc.putFits("NAXIS   = 3", false);
        List<String> out = new ArrayList<>();
        out.add("wrote " + fc.write(fs));
        out.addAll(FitsHeaders.foundCards(fc));
        return out;
    }

    @Test
    void versionsMatchC() {
        List<String> failed = new ArrayList<>();
        for (JsonNode c : ProbeData.load("fitschan_alt_naxes").get("CASES")) {
            String name = c.get(0).asText();
            List<String> want = new ArrayList<>();
            want.add("wrote " + c.get(3).asInt());
            want.addAll(FitsHeaders.strings(c.get(4)));
            List<String> got = written(c.get(1).asInt(), c.get(2).asInt());
            if (!FitsHeaders.cardsMatchC(got, want)) {
                failed.add(name + ":\n--- Java\n" + String.join("\n", got) + "\n--- C\n" + String.join("\n", want));
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }
}
