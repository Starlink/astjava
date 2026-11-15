package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The known units and the multiplier prefixes that units expressions may use.
 *
 * <p>Both tables are built once, when the class is initialised, and never change. As in C, each
 * list is held in the order C walks its linked list, which is the reverse of the order the
 * entries are defined in, because C adds each entry to the head of its list. That order decides
 * which of two equally good matches the parser takes. The definition of each derived unit is
 * parsed when the unit is defined, against the units defined before it, as C's {@code
 * MakeKnownUnit} does.
 *
 * <p>Ported from Rust {@code units/table.rs} and C {@code unit.c} ({@code GetKnownUnits}, {@code
 * GetMultipliers}, {@code MakeKnownUnit} and {@code MakeUnitAlias}).
 */
final class UnitsTable {

    /** A multiplier prefix, such as "k" for kilo (C {@code Multiplier}). */
    static final class Multiplier {
        /** The prefix symbol. */
        final String sym;

        /** The prefix name. */
        final String label;

        /** The factor the prefix stands for. */
        final double scale;

        Multiplier(String sym, double scale, String label) {
            this.sym = sym;
            this.label = label;
            this.scale = scale;
        }
    }

    /** A known unit, such as "Hz" (C {@code KnownUnit}). */
    static final class KnownUnit {
        /** The unit symbol. */
        final String sym;

        /** The unit name. */
        final String label;

        /** The expression defining the unit in terms of other units, or null for a basic unit. */
        final String definition;

        /**
         * The tree of the definition, in which every unit is a basic unit, or null for a basic
         * unit or an alias.
         */
        final UnitsTree.Node head;

        /** The unit this one is an alias for, or null if it is not an alias. */
        final KnownUnit use;

        KnownUnit(String sym, String label, String definition, UnitsTree.Node head, KnownUnit use) {
            this.sym = sym;
            this.label = label;
            this.definition = definition;
            this.head = head;
            this.use = use;
        }
    }

    /** The multipliers, in C's list order (C {@code GetMultipliers}). */
    private static final List<Multiplier> MULTIPLIER_LIST = makeMultipliers();

    /** The basic quantities of dimensional analysis (C {@code quant_units}). */
    private static final List<KnownUnit> QUANT_UNIT_LIST;

    /** The known units, in C's list order (C {@code GetKnownUnits}). */
    private static final List<KnownUnit> KNOWN_UNIT_LIST;

    static {
        Builder b = new Builder();
        List<KnownUnit> quant = new ArrayList<>();

        // The IAU basic units. "g" is used rather than "kg", which would make a gram a
        // milli-kilogram.
        quant.add(b.make("g", "gram", null));
        quant.add(b.make("m", "metre", null));
        quant.add(b.make("s", "second", null));
        quant.add(b.make("rad", "radian", null));
        quant.add(b.make("K", "Kelvin", null));
        b.make("A", "Ampere", null);
        b.make("mol", "mole", null);
        b.make("cd", "candela", null);

        // The IAU derived units. A definition may only refer to units already defined.
        b.make("sr", "steradian", "rad rad");
        b.make("Hz", "Hertz", "1/s");
        b.make("N", "Newton", "kg m/s**2");
        b.make("J", "Joule", "N m");
        b.make("W", "Watt", "J/s");
        b.make("C", "Coulomb", "A s");
        b.make("V", "Volt", "J/C");
        b.make("Pa", "Pascal", "N/m**2");
        b.make("Ohm", "Ohm", "V/A");
        b.make("S", "Siemens", "A/V");
        b.make("F", "Farad", "C/V");
        b.make("Wb", "Weber", "V s");
        b.make("T", "Tesla", "Wb/m**2");
        b.make("H", "Henry", "Wb/A");
        b.make("lm", "lumen", "cd sr");
        b.make("lx", "lux", "lm/m**2");

        // The further derived and basic units listed in the FITS-WCS paper.
        b.make("deg", "degree", "pi/180 rad");
        b.make("arcmin", "arc-minute", "1/60 deg");
        b.make("arcsec", "arc-second", "1/3600 deg");
        b.make("mas", "milli-arcsecond", "1/3600000 deg");
        b.make("min", "minute", "60 s");
        b.make("h", "hour", "3600 s");
        b.make("d", "day", "86400 s");
        b.make("yr", "year", "31557600 s");
        b.make("a", "year", "31557600 s");
        b.make("eV", "electron-Volt", "1.60217733E-19 J");
        b.make("erg", "erg", "1.0E-7 J");
        b.make("Ry", "Rydberg", "13.605692 eV");
        b.make("solMass", "solar mass", "1.9891E30 kg");
        b.make("u", "unified atomic mass unit", "1.6605387E-27 kg");
        b.make("solLum", "solar luminosity", "3.8268E26 W");
        b.make("Angstrom", "Angstrom", "1.0E-10 m");
        b.make("micron", "micron", "1.0E-6 m");
        b.make("solRad", "solar radius", "6.9599E8 m");
        b.make("AU", "astronomical unit", "1.49598E11 m");
        b.make("lyr", "light year", "9.460730E15 m");
        b.make("pc", "parsec", "3.0867E16 m");
        quant.add(b.make("count", "count", null));
        quant.add(b.make("adu", "analogue-to-digital unit", null));
        quant.add(b.make("photon", "photon", null));
        b.make("Jy", "Jansky", "1.0E-26 W /m**2 /Hz");
        quant.add(b.make("mag", "magnitude", null));
        b.make("G", "Gauss", "1.0E-4 T");
        quant.add(b.make("pixel", "pixel", null));
        b.make("barn", "barn", "1.0E-28 m**2");
        b.make("D", "Debye", "(1.0E-29/3) C.m");

        // Aliases.
        b.alias("Angstrom", "Ang");
        b.alias("count", "ct");
        b.alias("photon", "ph");
        b.alias("Jy", "Jan");
        b.alias("pixel", "pix");
        b.alias("s", "sec");
        b.alias("m", "meter");

        QUANT_UNIT_LIST = Collections.unmodifiableList(quant);
        KNOWN_UNIT_LIST = Collections.unmodifiableList(b.units);
    }

    private UnitsTable() {}

    /** Builds the list of known units as C's {@code MakeKnownUnit} and {@code MakeUnitAlias} do. */
    private static final class Builder {
        /** The units defined so far, most recent first. */
        final List<KnownUnit> units = new ArrayList<>();

        /**
         * Defines a unit, parsing its definition against the units defined so far, and returns
         * it (C {@code MakeKnownUnit}).
         */
        KnownUnit make(String sym, String label, String exp) {
            UnitsTree.Node head = exp != null ? UnitsParser.createTree(exp, true, units) : null;
            KnownUnit unit = new KnownUnit(sym, label, exp, head, null);
            units.add(0, unit);
            return unit;
        }

        /** Defines {@code alias} as another symbol for the unit {@code sym} (C {@code MakeUnitAlias}). */
        void alias(String sym, String alias) {
            for (KnownUnit unit : units) {
                if (sym.equals(unit.sym)) {
                    units.add(0, new KnownUnit(alias, unit.label, null, null, unit));
                    return;
                }
            }
            throw Ast.err(
                    AstException.AST__INTER,
                    "MakeUnitAlias(Unit): Cannot find existing units \"" + sym + "\" to associate with the alias \""
                            + alias + "\" (AST internal programming error).");
        }
    }

    /** The multipliers of the FITS WCS paper I, in C's list order. */
    private static List<Multiplier> makeMultipliers() {
        Multiplier[] declared = {
            new Multiplier("d", 1.0E-1, "deci"),
            new Multiplier("c", 1.0E-2, "centi"),
            new Multiplier("m", 1.0E-3, "milli"),
            new Multiplier("u", 1.0E-6, "micro"),
            new Multiplier("n", 1.0E-9, "nano"),
            new Multiplier("p", 1.0E-12, "pico"),
            new Multiplier("f", 1.0E-15, "femto"),
            new Multiplier("a", 1.0E-18, "atto"),
            new Multiplier("z", 1.0E-21, "zepto"),
            new Multiplier("y", 1.0E-24, "yocto"),
            new Multiplier("da", 1.0E1, "deca"),
            new Multiplier("h", 1.0E2, "hecto"),
            new Multiplier("k", 1.0E3, "kilo"),
            new Multiplier("M", 1.0E6, "mega"),
            new Multiplier("G", 1.0E9, "giga"),
            new Multiplier("T", 1.0E12, "tera"),
            new Multiplier("P", 1.0E15, "peta"),
            new Multiplier("E", 1.0E18, "exa"),
            new Multiplier("Z", 1.0E21, "zetta"),
            new Multiplier("Y", 1.0E24, "yotta"),
        };
        List<Multiplier> list = new ArrayList<>();
        for (Multiplier m : declared) {
            list.add(0, m);
        }
        return Collections.unmodifiableList(list);
    }

    /** The known units, in the order C searches them. */
    static List<KnownUnit> knownUnits() {
        return KNOWN_UNIT_LIST;
    }

    /** The multipliers, in the order C searches them. */
    static List<Multiplier> multipliers() {
        return MULTIPLIER_LIST;
    }

    /** The units whose powers dimensional analysis counts (C {@code quant_units}). */
    static List<KnownUnit> quantUnits() {
        return QUANT_UNIT_LIST;
    }

    /** The first known unit, in C's order, with the symbol {@code sym}, or null. */
    static KnownUnit knownUnit(String sym) {
        for (KnownUnit unit : KNOWN_UNIT_LIST) {
            if (sym.equals(unit.sym)) {
                return unit;
            }
        }
        return null;
    }

    /** The multiplier with the symbol {@code sym}, or null. */
    static Multiplier multiplier(String sym) {
        for (Multiplier m : MULTIPLIER_LIST) {
            if (sym.equals(m.sym)) {
                return m;
            }
        }
        return null;
    }
}
