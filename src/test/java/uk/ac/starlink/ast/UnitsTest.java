package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of the unit tests in Rust {@code src/units/} ({@code tests.rs}, {@code table.rs},
 * {@code parser.rs}, {@code simplify.rs} and {@code dim_anal.rs}), with Java-only tests of C
 * behaviour the Rust port does not share.
 *
 * <p>The MathMaps of conversions that need one are checked against the C probe {@code
 * probes/mathmap_simplify_probe.c}.
 */
class UnitsTest {

    /** The cases of the probe that pins the MathMaps of units conversions. */
    private static final Map<String, String> UNITS_PROBE = ProbeOutput.load("mathmap_simplify_probe");

    // ---------------------------------------------------------------- tests.rs

    /** The forward image of {@code x} under {@code map}. */
    private static double forwardOne(Mapping map, double x) {
        return map.tran1(1, new double[] {x}, true)[0];
    }

    /** Asserts {@code map} is a ZoomMap whose forward image of 1 is {@code expected}. */
    private static void assertZoomFactor(Mapping map, double expected) {
        assertInstanceOf(ZoomMap.class, map);
        double got = forwardOne(map, 1.0);
        double tol = Math.abs(expected) * 1e-12;
        assertTrue(Math.abs(got - expected) <= tol, "expected zoom factor " + expected + ", got " + got);
    }

    @Test
    void logHzToHzIsMathmap() {
        Mapping map = Units.unitMapper("log(Hz)", "Hz");
        assertInstanceOf(MathMap.class, map);
        assertEquals(1.0, forwardOne(map, 0.0), 1e-12);
        assertEquals(100.0, forwardOne(map, 2.0), 1e-9);
    }

    @Test
    void sqrtHzToHzIsMathmap() {
        Mapping map = Units.unitMapper("sqrt(Hz)", "Hz");
        assertInstanceOf(MathMap.class, map);
        assertEquals(4.0, forwardOne(map, 2.0), 1e-12);
    }

    @Test
    void logKmsToMsIsMathmap() {
        Mapping map = Units.unitMapper("log(km/s)", "m/s");
        assertInstanceOf(MathMap.class, map);
        assertEquals(1000.0, forwardOne(map, 0.0), 1e-9);
    }

    @Test
    void hzToLogHzIsMathmap() {
        Mapping map = Units.unitMapper("Hz", "log(Hz)");
        assertInstanceOf(MathMap.class, map);
        assertEquals(2.0, forwardOne(map, 100.0), 1e-12);
    }

    @Test
    void identityReturnsUnitmap() {
        assertInstanceOf(UnitMap.class, Units.unitMapper("Hz", "Hz"));
    }

    @Test
    void mhzToHzZoom1e6() {
        assertZoomFactor(Units.unitMapper("MHz", "Hz"), 1.0e6);
    }

    @Test
    void kmToMZoom1000() {
        assertZoomFactor(Units.unitMapper("km", "m"), 1.0e3);
    }

    @Test
    void arcsecToRad() {
        assertZoomFactor(Units.unitMapper("arcsec", "rad"), Math.PI / 648000.0);
    }

    @Test
    void evToJoule() {
        assertZoomFactor(Units.unitMapper("eV", "J"), 1.60217733e-19);
    }

    @Test
    void janskyDecomposed() {
        assertZoomFactor(Units.unitMapper("Jy", "W/m**2/Hz"), 1.0e-26);
    }

    @Test
    void auToMetre() {
        assertZoomFactor(Units.unitMapper("AU", "m"), 1.49598e11);
    }

    @Test
    void msToKms() {
        assertZoomFactor(Units.unitMapper("m/s", "km/s"), 1.0e-3);
    }

    @Test
    void kgPerM2ToGPerCm2() {
        assertZoomFactor(Units.unitMapper("kg/m**2", "g/cm**2"), 0.1);
    }

    @Test
    void unknownUnitReturnsNone() {
        assertNull(Units.unitMapper("xyzzy", "bar"));
    }

    @Test
    void incompatibleDimensionsReturnNone() {
        assertNull(Units.unitMapper("m", "s"));
    }

    @Test
    void aliasSecEqualsS() {
        assertInstanceOf(UnitMap.class, Units.unitMapper("sec", "s"));
    }

    @Test
    void aliasMeterEqualsM() {
        assertInstanceOf(UnitMap.class, Units.unitMapper("meter", "m"));
    }

    @Test
    void micronToMetre() {
        assertZoomFactor(Units.unitMapper("micron", "m"), 1.0e-6);
    }

    @Test
    void angstromToMetre() {
        assertZoomFactor(Units.unitMapper("Angstrom", "m"), 1.0e-10);
    }

    @Test
    void nmPrefixUnit() {
        assertZoomFactor(Units.unitMapper("nm", "m"), 1.0e-9);
    }

    @Test
    void parsecToMetre() {
        assertZoomFactor(Units.unitMapper("pc", "m"), 3.0867e16);
    }

    @Test
    void hzToInverseSIsUnitmap() {
        assertInstanceOf(UnitMap.class, Units.unitMapper("Hz", "1/s"));
    }

    @Test
    void angAliasToMetre() {
        assertZoomFactor(Units.unitMapper("Ang", "m"), 1.0e-10);
    }

    @Test
    void ghzToHz() {
        assertZoomFactor(Units.unitMapper("GHz", "Hz"), 1.0e9);
    }

    @Test
    void arcminToArcsec() {
        assertZoomFactor(Units.unitMapper("arcmin", "arcsec"), 60.0);
    }

    // ---------------------------------------------------------------- table.rs

    @Test
    void basicUnitsHaveNoDefinition() {
        for (String sym : new String[] {"g", "m", "s", "rad", "K", "A", "mol", "cd"}) {
            UnitsTable.KnownUnit u = UnitsTable.knownUnit(sym);
            assertNotNull(u, sym);
            assertNull(u.definition, sym + " should have no definition");
            assertNull(u.head, sym + " should have no definition tree");
        }
    }

    @Test
    void derivedUnitsHaveDefinitions() {
        assertEquals("1/s", UnitsTable.knownUnit("Hz").definition);
        assertEquals("N m", UnitsTable.knownUnit("J").definition);
        assertEquals("1.60217733E-19 J", UnitsTable.knownUnit("eV").definition);
        assertNotNull(UnitsTable.knownUnit("eV").head);
    }

    @Test
    void aliasesResolveToCanonical() {
        assertSame(UnitsTable.knownUnit("Angstrom"), UnitsTable.knownUnit("Ang").use);
        assertSame(UnitsTable.knownUnit("s"), UnitsTable.knownUnit("sec").use);
        assertSame(UnitsTable.knownUnit("m"), UnitsTable.knownUnit("meter").use);
        assertNull(UnitsTable.knownUnit("m").use);
    }

    @Test
    void unknownReturnsNone() {
        assertNull(UnitsTable.knownUnit("xyzzy"));
    }

    /**
     * C has no lookup by label; a label is matched, without regard to case, when a units string
     * names no unit symbol (unit.c {@code MakeTree}).
     */
    @Test
    void labelLookup() {
        Node t = UnitsParser.createTree("Hertz", false);
        assertEquals(Oper.LDVAR, t.opcode);
        assertEquals("Hz", t.unit.sym);
        t = UnitsParser.createTree("hertz", false);
        assertEquals("Hz", t.unit.sym);
    }

    @Test
    void multipliersPresent() {
        assertEquals(1.0e3, UnitsTable.multiplier("k").scale);
        assertEquals(1.0e6, UnitsTable.multiplier("M").scale);
        assertEquals(1.0e-6, UnitsTable.multiplier("u").scale);
        assertEquals(1.0e1, UnitsTable.multiplier("da").scale);
        assertNull(UnitsTable.multiplier("x"));
    }

    @Test
    void fullTableSizeMatchesC() {
        assertEquals(61, UnitsTable.knownUnits().size());
    }

    @Test
    void multipliersTableHas20Entries() {
        assertEquals(20, UnitsTable.multipliers().size());
    }

    // ---------------------------------------------------------------- parser.rs

    @Test
    void cleanExpStripsOuterWhitespace() {
        assertEquals("Hz", UnitsParser.cleanExp("  Hz  "));
    }

    @Test
    void cleanExpCollapsesInternalSpaces() {
        assertEquals("kg m", UnitsParser.cleanExp("kg   m"));
    }

    @Test
    void cleanExpDropsSpaceAroundOperators() {
        assertEquals("kg/m*s", UnitsParser.cleanExp("kg / m * s"));
    }

    @Test
    void cleanExpWordThenDigit() {
        assertEquals("cm^2", UnitsParser.cleanExp("cm2"));
    }

    @Test
    void cleanExpWordThenSignedDigit() {
        assertEquals("cm^-2", UnitsParser.cleanExp("cm-2"));
    }

    @Test
    void cleanExpMicronRewrite() {
        assertEquals("(m*1.0E-6)", UnitsParser.cleanExp("micron"));
        assertEquals("(Km*1.0E-6)", UnitsParser.cleanExp("Kmicron"));
    }

    @Test
    void cleanExpSterRewrite() {
        assertEquals("sr", UnitsParser.cleanExp("STER"));
    }

    @Test
    void cleanExpMjyRewrite() {
        assertEquals("MJy", UnitsParser.cleanExp("MJY"));
    }

    @Test
    void conStartSimple() {
        CScan.Num n = UnitsParser.conStart("1.5", 0);
        assertEquals(1.5, n.value);
        assertEquals(3, n.end);
        n = UnitsParser.conStart("1.0E-6m", 0);
        assertEquals(1.0e-6, n.value);
        assertEquals(6, n.end);
        assertNull(UnitsParser.conStart("nano", 0));
    }

    @Test
    void splitUnitWithMultiplier() {
        UnitsParser.Split s = UnitsParser.splitUnit("MHz", 0, 3, "Hz", true);
        assertNotNull(s);
        assertEquals("M", s.mult.sym);
    }

    @Test
    void splitUnitNoMultiplier() {
        UnitsParser.Split s = UnitsParser.splitUnit("Hz", 0, 2, "Hz", true);
        assertNotNull(s);
        assertNull(s.mult);
    }

    @Test
    void makeTreeSimpleUnit() {
        Node t = UnitsParser.createTree("Hz", false);
        assertEquals(Oper.LDVAR, t.opcode);
        assertEquals("Hz", t.unit.sym);
    }

    @Test
    void makeTreeMultipliedUnit() {
        Node t = UnitsParser.createTree("MHz", false);
        assertEquals(Oper.LDVAR, t.opcode);
        assertEquals("Hz", t.unit.sym);
        assertEquals("M", t.mult.sym);
    }

    @Test
    void makeTreeDivision() {
        assertEquals(Oper.DIV, UnitsParser.createTree("m/s", false).opcode);
    }

    @Test
    void makeTreePow() {
        assertEquals(Oper.POW, UnitsParser.createTree("m**2", false).opcode);
    }

    @Test
    void makeTreeBasicSubstitution() {
        Node t = UnitsParser.createTree("Hz", true);
        assertEquals(Oper.DIV, t.opcode);
        assertTrue(t.arg[0].isConst());
        assertEquals(Oper.LDVAR, t.arg[1].opcode);
        assertEquals("s", t.arg[1].name);
    }

    // ---------------------------------------------------------------- simplify.rs

    @Test
    void definitionCacheBuilt() {
        Node head = UnitsTable.knownUnit("Hz").head;
        assertEquals(Oper.DIV, head.opcode);
        assertTrue(head.arg[0].isConst());
        assertEquals(1.0, head.arg[0].con);
        assertEquals(Oper.LDVAR, head.arg[1].opcode);
        assertEquals("s", head.arg[1].name);
    }

    @Test
    void remakeTreeSubstitutesDerived() {
        assertEquals(Oper.DIV, UnitsParser.createTree("Hz", true).opcode);
    }

    @Test
    void remakeTreeHandlesMultiplier() {
        Node t = UnitsParser.createTree("MHz", true);
        assertEquals(Oper.MULT, t.opcode);
        assertTrue(t.arg[0].isConst());
        assertEquals(1.0e-6, t.arg[0].con, 1e-30);
    }

    /**
     * "1/60 deg": FixConstants folds the constant division and InvertConstants then takes the
     * reciprocal of the coefficient of the MULT node (unit.c {@code CreateTree}).
     */
    @Test
    void invertConstantsInDiv() {
        Node t = UnitsParser.createTree("1/60 deg", false);
        assertEquals(Oper.MULT, t.opcode);
        assertTrue(t.arg[0].isConst());
        assertEquals(60.0, t.arg[0].con, 1e-12);
        assertEquals("deg", t.arg[1].name);
    }

    // ---------------------------------------------------------------- dim_anal.rs

    private static int quantIndex(String sym) {
        return UnitsTable.quantUnits().indexOf(UnitsTable.knownUnit(sym));
    }

    @Test
    void dimAnalBasic() {
        UnitsDimAnal.Result r = UnitsDimAnal.dimAnal(UnitsParser.createTree("m", true));
        assertNotNull(r);
        assertEquals(1.0, r.powers[quantIndex("m")]);
        assertEquals(1.0, r.scale);
    }

    @Test
    void dimAnalMhz() {
        UnitsDimAnal.Result r = UnitsDimAnal.dimAnal(UnitsParser.createTree("MHz", true));
        assertNotNull(r);
        assertEquals(-1.0, r.powers[quantIndex("s")]);
        assertEquals(1.0e6, r.scale, 1.0);
    }

    @Test
    void dimAnalLogReturnsNone() {
        assertNull(UnitsDimAnal.dimAnal(UnitsParser.createTree("log(Hz)", true)));
    }

    @Test
    void locateUnitsDedups() {
        List<Node> units = new ArrayList<>();
        UnitsDimAnal.locateUnits(UnitsParser.createTree("m/s", true), units);
        List<String> names = new ArrayList<>();
        for (Node n : units) {
            names.add(n.name);
        }
        assertTrue(names.contains("m"));
        assertTrue(names.contains("s"));
        assertEquals(2, names.size());
    }

    @Test
    void quantIndexLookupWorks() {
        assertEquals(0, quantIndex("g"));
        // A is not one of the quantities C's DimAnal tracks.
        assertEquals(-1, quantIndex("A"));
        assertEquals(10, UnitsTable.quantUnits().size());
    }

    // ---------------------------------------------------------------- Java-only

    /**
     * The conversions C makes with a MathMap (unit.c {@code MakeMapping}) give C's MathMap, with
     * its functions, SimpFI and SimpIF set, and the label C gives for "Frequency"; the MathMap
     * transforms the probe's values forward and back as C's does, to within the last bits of the
     * maths library.
     */
    @Test
    void conversionsNeedingMathMapGiveCsMathMap() {
        String[][] cases = {
            {"units_log_hz_to_hz", "log(Hz)", "Hz", "v1"},
            {"units_sqrt_hz_to_hz", "sqrt(Hz)", "Hz", "v2"},
            {"units_log_kms_to_ms", "log(km/s)", "m/s", "v1"},
            {"units_hz_to_log_hz", "Hz", "log(Hz)", "v2"},
            {"units_hz_to_sqrt_hz", "Hz", "sqrt(Hz)", "v2"},
            {"units_ln_hz_to_mhz", "ln(Hz)", "MHz", "v1"},
            {"units_exp_hz_to_hz", "exp(Hz)", "Hz", "v2"},
            {"units_log_mhz_to_log_hz", "log(MHz)", "log(Hz)", "v1"},
            {"units_hz_to_per_hz", "Hz", "1/Hz", "v2"},
            {"units_hz15_to_hz", "Hz**1.5", "Hz", "v2"},
            {"units_m2_to_cm", "m**2", "cm", "v2"},
            {"units_log_jy_to_w", "log(Jy)", "W/m**2/Hz", "v1"},
        };
        double[] v1 = {0.0, 2.0, -1.5};
        double[] v2 = {2.0, 0.25, 100.0};
        for (String[] c : cases) {
            String want = UNITS_PROBE.get(c[0]);
            Units.LabelledMapping lm = Units.unitMapperLabel(c[1], c[2], "Frequency");
            assertNotNull(lm, c[0]);
            assertInstanceOf(MathMap.class, lm.mapping, c[0]);
            String[] lines = want.split("\n", -1);
            assertEquals(lines[0], "label " + lm.label, c[0]);
            String dump = ChannelText.dump(lm.mapping);
            int ndump = dump.split("\n", -1).length - 1;
            StringBuilder wantDump = new StringBuilder();
            for (int i = 1; i <= ndump; i++) {
                wantDump.append(lines[i]).append('\n');
            }
            assertEquals(wantDump.toString(), dump, c[0]);
            double[] vals = "v1".equals(c[3]) ? v1 : v2;
            double[] fwd = lm.mapping.tran1(vals.length, vals, true);
            double[] inv = lm.mapping.tran1(vals.length, fwd, false);
            for (int i = 0; i < vals.length; i++) {
                String[] parts = lines[ndump + 1 + i].split(" -> ", -1);
                assertClose(Double.parseDouble(parts[1]), fwd[i], c[0] + " forward " + vals[i]);
                assertClose(Double.parseDouble(parts[2]), inv[i], c[0] + " inverse " + vals[i]);
            }
        }
    }

    /** Asserts that {@code got} is {@code want} to within the last bits of the maths library. */
    private static void assertClose(double want, double got, String what) {
        double tol = 1e-14 * Math.max(1.0, Math.abs(want));
        assertTrue(Math.abs(got - want) <= tol, what + ": expected " + want + ", got " + got);
    }

    /**
     * A conversion and its reverse in series do not cancel, as their functions are not each
     * other's inverse functions as text; a conversion and its inverse do.
     */
    @Test
    void unitsMathMapsSimplifyAsC() {
        Mapping m1 = Units.unitMapper("log(Hz)", "Hz");
        Mapping m2 = Units.unitMapper("Hz", "log(Hz)");
        Mapping s = new CmpMap(m1, m2, true).simplify();
        assertEquals(
                UNITS_PROBE.get("units_there_and_back"),
                s.astClass() + " " + s.getNin() + " " + s.getNout() + "\n" + ChannelText.dump(s));
        Mapping m3 = (Mapping) m1.copy();
        m3.invert();
        s = new CmpMap(m1, m3, true).simplify();
        assertEquals(
                UNITS_PROBE.get("units_with_inverse"),
                s.astClass() + " " + s.getNin() + " " + s.getNout() + "\n" + ChannelText.dump(s));
    }

    /**
     * NormUnit of a one-axis Frame in C, which is {@code astNormUnit} of its Unit (frame.c {@code
     * GetNormUnit}), from the table {@code C_ORACLE} in Rust {@code tests/test_frame.rs}. Task 15
     * translates that test through Frame.
     */
    @Test
    void normUnitMatchesTheCLibrary() {
        String[][] table = {
            {"", ""},
            {"s*(m/s)", "m"},
            {"km", "km"},
            {"0.01 m", "cm"},
            {"2*m", "2*m"},
            {"1000 m", "km"},
            {"100*m", "hm"},
            {"m/100", "cm"},
            {"1/m", "m**-1"},
            {"m/2", "0.5*m"},
            {"10/m", "1/dm"},
            {"1000/m", "1/mm"},
            {"2/m", "2/m"},
            {"0.5/m", "0.5/m"},
            {"3/s", "3/s"},
            {"2/km", "2/km"},
            {"1/(2*m)", "0.5/m"},
            {"Angstrom", "Angstrom"},
            {"log(Hz)", "ln(Hz**2.3025850929940459)"},
            {"m**0.5", "sqrt(m)"},
            {"sqrt(m)", "sqrt(m)"},
            {"Jy/beam", "Jy/beam"},
            {"ddd:mm:ss", "ddd:mm:ss"},
            {"hh mm ss", "hh*mm*s"},
            {"degrees", "deg"},
            {"deg", "deg"},
            {"arcsec", "arcsec"},
            {"km/s", "km/s"},
            {"erg/s/cm**2", "erg*cm**-2/s"},
            {"0.5", ""},
            {"2", ""},
            {"m*m", "m**2"},
            {"mJy", "mJy"},
            {"1000*Jy", "kJy"},
            {"MHz*1000", "GHz"},
            {"ln(m)", "ln(m)"},
            {"exp(m)", "exp(m)"},
            {"10**(m)", "10**(m)"},
            {"m**2*s**-1", "m**2/s"},
            {"(m/s)**2", "(m**2)*s**-2"},
            {"s**-1", "s**-1"},
            {"cm/(s*K)", "cm/K/s"},
            {"furlong", "furlong"},
            {"1.5 furlong", "1.5*furlong"},
            {"nano m", "N*m"},
            {"micron", "um"},
        };
        List<String> failures = new ArrayList<>();
        for (String[] row : table) {
            String got = Units.normUnit(row[0]);
            if (!row[1].equals(got)) {
                failures.add("Unit \"" + row[0] + "\": got \"" + got + "\", C gives \"" + row[1] + "\"");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** A units string that cannot be parsed is an error in C (unit.c {@code MakeTree}). */
    @Test
    void parseErrorsAreReported() {
        AstException e = assertThrows(AstException.class, () -> Units.unitMapper("m((", "m"));
        assertEquals(AstException.AST__BADUN, e.getStatus());
        assertTrue(e.getMessage().contains("Error parsing input units string 'm(('"), e.getMessage());
        e = assertThrows(AstException.class, () -> Units.unitMapper("m", "2m3x"));
        assertEquals(AstException.AST__BADUN, e.getStatus());
        assertTrue(e.getMessage().contains("Error parsing output units string"), e.getMessage());
        e = assertThrows(AstException.class, () -> Units.unitNormaliser("m:s"));
        assertEquals(AstException.AST__BADUN, e.getStatus());
        assertTrue(e.getMessage().contains("Illegal character ':' found."), e.getMessage());
        // astUnitNormaliser reports a blank string as one it cannot parse, which astNormUnit recovers from.
        assertThrows(AstException.class, () -> Units.unitNormaliser(" "));
        assertEquals(" ", Units.normUnit(" "));
    }

    /** Blank strings on both sides convert with a UnitMap; one blank side does not convert. */
    @Test
    void blankUnits() {
        assertInstanceOf(UnitMap.class, Units.unitMapper(" ", ""));
        assertNull(Units.unitMapper("", "m"));
        assertNull(Units.unitMapper("m", " "));
    }

    /**
     * C walks its known units most recently defined first (unit.c {@code MakeKnownUnit} prepends
     * each), so the label "year", shared by "yr" and "a", matches "a", defined after "yr".
     */
    @Test
    void sharedLabelMatchesTheLaterUnit() {
        Node t = UnitsParser.createTree("years", false);
        assertEquals("a", t.name);
        assertEquals("a", Units.normUnit("year"));
    }

    /** The label astUnitMapper writes for a change of units (unit.c {@code astUnitMapper}). */
    @Test
    void labelsFollowTheUnits() {
        Units.LabelledMapping lm = Units.unitMapperLabel("m", "km", "Dist");
        assertInstanceOf(ZoomMap.class, lm.mapping);
        assertEquals("Dist", lm.label);
        lm = Units.unitMapperLabel("m", "m", " Dist ");
        assertInstanceOf(UnitMap.class, lm.mapping);
        assertEquals(" Dist ", lm.label);
        lm = Units.unitMapperLabel("Hz", "1/s", "Freq");
        assertInstanceOf(UnitMap.class, lm.mapping);
        assertEquals("Freq", lm.label);
        lm = Units.unitMapperLabel("m**2", "cm**2", "Area");
        assertEquals("Area", lm.label);
        assertNull(Units.unitMapperLabel("m", "s", "Dist"));
    }

    /**
     * Constants compare with C's {@code astEQUAL} (unit.c {@code CmpTree}), which separates values
     * differing by 1e-10 relative, since its tolerance is 1e5 * (|a| + |b|) * DBL_EPSILON.
     */
    @Test
    void constantsCompareWithAstEqual() {
        assertEquals(0, UnitsCmp.cmpTree(UnitsTree.ldcon(1.0), UnitsTree.ldcon(1.0 + 1e-12), true));
        assertEquals(-1, UnitsCmp.cmpTree(UnitsTree.ldcon(1.0), UnitsTree.ldcon(1.0 + 1e-10), true));
        assertEquals(1, UnitsCmp.cmpTree(UnitsTree.ldcon(1.0 + 1e-10), UnitsTree.ldcon(1.0), true));
    }

    private static Node mult(String a, String b) {
        Node n = UnitsTree.newNode(Oper.MULT);
        n.arg[0] = UnitsTree.ldvar(a);
        n.arg[1] = UnitsTree.ldvar(b);
        return n;
    }

    /**
     * When two products differ, C compares them again with the arguments of the second swapped and
     * returns that comparison (unit.c {@code CmpTree}), which orders the factors {@code
     * CombineFactors} sorts.
     */
    @Test
    void productsThatDifferCompareSwapped() {
        // b*a against a*c: "b" > "a" in order, but "b" < "c" swapped.
        assertTrue(UnitsCmp.cmpTree(mult("b", "a"), mult("a", "c"), true) > 0);
        assertTrue(UnitsCmp.cmpTree(mult("b", "a"), mult("a", "c"), false) < 0);
        assertEquals(0, UnitsCmp.cmpTree(mult("b", "a"), mult("a", "b"), false));
    }

    @Test
    void unitLabelGivesTheLabelOfASymbol() {
        assertEquals("Hertz", Units.unitLabel("Hz"));
        assertEquals("Angstrom", Units.unitLabel("Ang"));
        assertNull(Units.unitLabel("hz"));
        assertFalse(Units.normUnit("km/h").isEmpty());
    }
}
