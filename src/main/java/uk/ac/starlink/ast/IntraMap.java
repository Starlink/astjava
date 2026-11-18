package uk.ac.starlink.ast;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * A Mapping which maps points using a private transformation function. The {@code IntraMap}
 * allows you to use a custom transformation function, as implemented by a {@link Transformer}
 * object, to perform the transformations used by a {@code Mapping}. This allows you to create
 * {@code Mapping}s that perform any conceivable coordinate transformation.
 *
 * <p>However, an {@code IntraMap} is intended for use within a given software environment. It is
 * written to a {@link Channel} with the name of its transformation function, not the function
 * itself, as C's IntraMap is, and can be read back only where that name stands for a Transformer:
 * in the same process while an IntraMap made with that Transformer is still in use, or through a
 * Channel with which a Transformer has been registered under the name.
 *
 * <p>The IntraMap holds its Transformer by reference; a {@link #copy} shares it. The name of its
 * transformation function (written to a dump as {@code Fname}) is the Transformer's class name
 * followed by {@code #} and its identity hash code in hexadecimal, and two IntraMaps with the same
 * name are taken to apply the same function. Making an IntraMap registers its Transformer under
 * that name, as jniast registers it with C's {@code astIntraReg}, together with the numbers of
 * coordinates, the flags (whether the forward and inverse transformations exist, and whether a
 * forward and inverse pair, or an inverse and forward pair, in series simplify away) and the
 * purpose, author and contact strings, all read from the Transformer then. The registration holds
 * the Transformer weakly, and lapses when no IntraMap uses it.
 *
 * <p>The IntraFlag attribute is a string which distinguishes this IntraMap from others using the
 * same function: a pair of IntraMaps that could cancel is simplified only if their IntraFlag
 * values are the same (an unset value reading as an empty string), and two IntraMaps are equal
 * only if both have it set to the same value. It is unset by default, and cannot be changed once
 * the IntraMap is held by another Object.
 *
 * <p>The Transformer's {@code tranP} method is called without any lock, and copies of an IntraMap
 * share its Transformer, so it may be called from several threads at once: the Transformer must
 * be thread-safe.
 *
 * <p>Ported from Rust {@code mappings/intramap.rs}, C {@code intramap.c} and jniast's {@code
 * IntraMap.java} and {@code jni/IntraMap.c}.
 */
public class IntraMap extends Mapping implements NeedsChannelizing {

    /** The Transformer which performs the transformations, shared with any copies. */
    private final Transformer trans;

    /** The name of the transformation function (C's {@code tran_data[ifun].name}). */
    private final String fname;

    /** C's flags for the function: {@link IntraRegistry#SIMPFI} and {@link IntraRegistry#SIMPIF}. */
    private final int flags;

    /** The purpose string of the function. */
    private final String purpose;

    /** The author of the function. */
    private final String author;

    /** The contact details of the author. */
    private final String contact;

    /** The IntraFlag attribute, null while unset. */
    private String intraFlag;

    /**
     * Constructs an {@code IntraMap} from a given {@code Transformer} object.
     *
     * @param trans the Transformer which will do the actual transformations
     * @param nin the number of input coordinates for the forward transformation (or output
     *     coordinates for the inverse transformation)
     * @param nout the number of output coordinates for the forward transformation (or input
     *     coordinates for the inverse transformation)
     * @throws IllegalArgumentException if {@code trans} is not able to transform from {@code nin}
     *     to {@code nout} coordinates
     * @throws NullPointerException if {@code trans} is null
     * @throws AstException with status {@code AST__MRITF} if another IntraMap in use was made with
     *     the same function name and different details: another Transformer, other numbers of
     *     coordinates, or other flags or strings
     */
    public IntraMap(Transformer trans, int nin, int nout) {
        this(register(trans, nin, nout), nin, nout);
    }

    /**
     * Constructs a two-dimensional {@code IntraMap} from a given {@code Transformer2} object.
     *
     * @param trans the {@code Transformer2} which will do the actual two-coordinate to
     *     two-coordinate transformations
     */
    public IntraMap(Transformer2 trans) {
        this(trans, 2, 2);
    }

    /**
     * Constructs a one-dimensional {@code IntraMap} from a given {@code Transformer1} object.
     *
     * @param trans the {@code Transformer1} which will do the actual one-coordinate to
     *     one-coordinate transformations
     */
    public IntraMap(Transformer1 trans) {
        this(trans, 1, 1);
    }

    /** Constructs an IntraMap of the function {@code entry} registers, as {@code astInitIntraMap} does. */
    private IntraMap(IntraRegistry.Entry entry, int nin, int nout) {
        super(nin, nout, (entry.flags & IntraRegistry.NOFWD) == 0, (entry.flags & IntraRegistry.NOINV) == 0);
        trans = entry.trans;
        fname = entry.name;
        flags = entry.flags;
        purpose = entry.purpose;
        author = entry.author;
        contact = entry.contact;
    }

    /**
     * jniast's {@code construct} (jni/IntraMap.c) up to its call of {@code astIntraReg}: the check
     * that {@code trans} can map {@code nin} to {@code nout} coordinates, then the registration of
     * {@code trans} under its function name in the process-wide table, which reads its flags and
     * strings. An exception the Transformer's methods throw is passed on, as jniast passes it on.
     */
    private static IntraRegistry.Entry register(Transformer trans, int nin, int nout) {
        Objects.requireNonNull(trans, "trans");
        if (!trans.canTransformCoords(nin, nout)) {
            throw new IllegalArgumentException("Transformer will not map " + nin + " -> " + nout + " coordinates");
        }
        String name = trans.getClass().getName() + "#" + Integer.toHexString(System.identityHashCode(trans));
        return IntraRegistry.registerMade(name, nin, nout, trans);
    }

    /**
     * Loads an IntraMap from its description, as C's {@code astLoadIntraMap} does: {@code Fname},
     * {@code Iflag} (the IntraFlag attribute), {@code Purp}, {@code Auth} and {@code Cntact}. The
     * Transformer is the one registered under the function name, with the Channel reading or, if
     * none is, by an IntraMap made in this process and still in use ({@link IntraRegistry#lookup}).
     * As in C, the IntraMap's numbers of coordinates as its Invert attribute leaves them must be
     * those registered, so an inverted IntraMap of a function with different numbers of inputs and
     * outputs cannot be read. No method of the Transformer is called.
     *
     * @throws AstException with status {@code AST__URITF} if no function is registered under the
     *     name, or {@code AST__BADNI} or {@code AST__BADNO} if the numbers of coordinates differ
     *     from those registered
     */
    // C's loader calls the Mapping class's GetNin and GetNout, which IntraMap does not override.
    @SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
    IntraMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("IntraMap");
        String chan = node.channelClass;
        fname = part.readString("Fname", "");
        // jniast also stored a Java-serialised Transformer here for an IntraMap at the top level of
        // an Object written; this library reads an IntraMap by its function name only.
        intraFlag = part.getStringIfSet("Iflag");
        String dumpPurpose = part.readString("Purp", "");
        String dumpAuthor = part.readString("Auth", "");
        String dumpContact = part.readString("Cntact", "");
        IntraRegistry.Entry entry = IntraRegistry.lookup(fname);
        if (entry == null) {
            throw Ast.err(
                    AstException.AST__URITF,
                    "astLoadIntraMap(" + chan + "): An IntraMap was read which uses an unknown transformation "
                            + "function.\nThis is a private extension to the AST library: to handle it, you must "
                            + "obtain the source code from its author.\nYou can then register it with AST in your "
                            + "software by calling astIntraReg (see SUN/211).\n \n   Function name:   \"" + fname
                            + "\".\n   Purpose:         \"" + dumpPurpose + "\".\n   Author:          \""
                            + dumpAuthor + "\".\n   Contact address: \"" + dumpContact + "\".\n ");
        }
        // C asks the parent class's GetNin and GetNout, which take account of the Invert attribute
        // already loaded (intramap.c astLoadIntraMap).
        int nin = getNin();
        int nout = getNout();
        if (nin != entry.nin && entry.nin != IntraRegistry.AST__ANY) {
            throw Ast.err(
                    AstException.AST__BADNI,
                    "astLoadIntraMap(" + chan + "): The number of input coordinates for the IntraMap read (" + nin
                            + ") does not match the number used by the registered \"" + entry.name
                            + "\" transformation function (" + entry.nin + ").");
        } else if (nout != entry.nout && entry.nout != IntraRegistry.AST__ANY) {
            throw Ast.err(
                    AstException.AST__BADNO,
                    "astLoadIntraMap(" + chan + "): The number of output coordinates for the IntraMap read (" + nout
                            + ") does not match the number used by the registered \"" + entry.name
                            + "\" transformation function (" + entry.nout + ").");
        }
        trans = entry.trans;
        flags = entry.flags;
        purpose = entry.purpose;
        author = entry.author;
        contact = entry.contact;
    }

    @Override
    String dumpComment() {
        return "Map points using a private transformation function";
    }

    /**
     * Writes {@code Fname}, {@code Iflag}, {@code Purp}, {@code Auth} and {@code Cntact}, as C's
     * {@code Dump} (intramap.c) does: {@code Iflag} is the IntraFlag value, written as a blank
     * string when unset.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeString("Fname", true, true, fname, "Name of transformation function");
        // jniast also stored a Java-serialised Transformer in IntraFlag for an IntraMap at the top
        // level of an Object written; this library writes and reads an IntraMap by its function
        // name only.
        node.writeString(
                "Iflag",
                intraFlag != null,
                false,
                intraFlag != null ? intraFlag : "",
                "IntraMap identification string");
        node.writeString("Purp", true, true, purpose, "Purpose of function");
        node.writeString("Auth", true, true, author, "Author's name");
        node.writeString("Cntact", true, true, contact, "Contact address");
    }

    // ------------------------------------------------------------------
    // Channelizing
    // ------------------------------------------------------------------

    /**
     * Does nothing. It is present for jniast's API, in which it stored the serialised Transformer
     * in the IntraFlag attribute before a write; an IntraMap is written with the name of its
     * transformation function only, and needs no preparation.
     *
     * @throws IOException never
     */
    @Override
    public void channelize() throws IOException {
        // An IntraMap is written by its function name, which needs no preparation.
    }

    /**
     * Does nothing. It is present for jniast's API, in which it recovered the serialised
     * Transformer from the IntraFlag attribute after a read; an IntraMap read finds its
     * Transformer by the name of its transformation function as it is loaded.
     *
     * @throws IOException never
     */
    @Override
    public void unChannelize() throws IOException {
        // The loader has found the Transformer by its function name.
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /** The name of the transformation function. */
    String getFname() {
        return fname;
    }

    /** The Transformer, shared with the IntraMap. */
    Transformer getTransformer() {
        return trans;
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * C's {@code SetIntraFlag}, an {@code astMAKE_SET1} attribute: a shared IntraMap refuses the
     * change; setting it discards the record that the IntraMap has been simplified.
     */
    private void storeIntraFlag(String value) {
        checkSet1("IntraFlag");
        clearIsSimple();
        intraFlag = value;
    }

    /** C's {@code ClearIntraFlag}, an {@code astMAKE_CLEAR1} attribute. */
    private void clearIntraFlag() {
        checkClear1("IntraFlag");
        clearIsSimple();
        intraFlag = null;
    }

    @Override
    String getAttrib(String attrib) {
        if ("intraflag".equals(attrib)) {
            return intraFlag;
        }
        return super.getAttrib(attrib);
    }

    @Override
    void setAttrib(String attrib, String value) {
        if ("intraflag".equals(attrib)) {
            storeIntraFlag(value);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        if ("intraflag".equals(attrib)) {
            return intraFlag != null;
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        if ("intraflag".equals(attrib)) {
            clearIntraFlag();
        } else {
            super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * intramap.c {@code Transform}: the Transformer is applied in the direction the Invert
     * attribute makes of {@code forward}, as jniast's {@code tranWrap} (jni/IntraMap.c) calls it,
     * with copies of the input coordinates. An exception from the Transformer, or a result that is
     * null or holds fewer than the needed arrays or values, is AST__ITFER, whose cause is that
     * exception or one describing the result.
     */
    @Override
    // Transformer.tranP is declared to throw any Exception; each one is the function's failure.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        int ncoordIn = forward ? getNin() : getNout();
        int ncoordOut = forward ? getNout() : getNin();
        double[][] jin = new double[ncoordIn][];
        for (int c = 0; c < ncoordIn; c++) {
            jin[c] = Arrays.copyOf(in[c], npoint);
        }
        double[][] result;
        try {
            result = trans.tranP(npoint, ncoordIn, jin, effectiveForward(forward), ncoordOut);
        } catch (Exception e) {
            throw functionError(e);
        }
        RuntimeException bad = checkResult(result, ncoordOut, npoint);
        if (bad != null) {
            throw functionError(bad);
        }
        for (int c = 0; c < ncoordOut; c++) {
            System.arraycopy(result[c], 0, out[c], 0, npoint);
        }
    }

    /**
     * The exception jniast's {@code jniastCheckArrayLength} throws for a result that is null or
     * holds fewer than {@code ncoordOut} arrays of at least {@code npoint} values, or null if the
     * result is good.
     */
    private static RuntimeException checkResult(double[][] result, int ncoordOut, int npoint) {
        if (result == null) {
            return new NullPointerException("Transformer returned null");
        }
        if (result.length < ncoordOut) {
            return new IllegalArgumentException(
                    "Transformer returned " + result.length + " coordinate arrays; " + ncoordOut + " are needed");
        }
        for (int c = 0; c < ncoordOut; c++) {
            if (result[c] == null) {
                return new NullPointerException("Transformer returned a null coordinate array");
            }
            if (result[c].length < npoint) {
                return new IllegalArgumentException("Transformer returned a coordinate array of " + result[c].length
                        + " values; " + npoint + " are needed");
            }
        }
        return null;
    }

    /** The error of intramap.c {@code Transform} for a failure of the function, with status AST__ITFER. */
    private AstException functionError(Exception cause) {
        AstException err = Ast.err(
                AstException.AST__ITFER,
                "astTransform(" + astClass() + "): Error signalled by \"" + fname + "\" transformation function.");
        err.initCause(cause);
        return err;
    }

    /**
     * NaN protocol: the Transformer is given bad input values as {@link #AST__BAD}, as C gives
     * them, and the bad values it returns become NaN.
     */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        double[][] bad = new double[in.length][];
        for (int c = 0; c < in.length; c++) {
            bad[c] = new double[npoint];
            for (int p = 0; p < npoint; p++) {
                double v = in[c][p];
                bad[c][p] = Double.isNaN(v) ? AST__BAD : v;
            }
        }
        super.transformIntoNan(bad, npoint, forward, out);
    }

    // ------------------------------------------------------------------
    // Equality and simplification
    // ------------------------------------------------------------------

    /**
     * intramap.c {@code Equal}: {@code that} is an IntraMap with the same numbers of inputs and
     * outputs, the same Invert attribute and the same function, and an IntraFlag set on both and
     * equal.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof IntraMap)) {
            return false;
        }
        IntraMap other = (IntraMap) that;
        return other.getNin() == getNin()
                && other.getNout() == getNout()
                && getInvert() == other.getInvert()
                && fname.equals(other.fname)
                && intraFlag != null
                && intraFlag.equals(other.intraFlag);
    }

    /**
     * intramap.c {@code MapMerge}: in series, this IntraMap and the next Mapping, if that is an
     * IntraMap of the same function with the same IntraFlag (an unset value reading as empty),
     * become a UnitMap when together they keep the number of coordinates and the function's flags
     * say that pair of directions is the identity: forward then inverse with SIMPFI, inverse then
     * forward with SIMPIF.
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        if (!series || where + 1 >= list.size() || !(list.map(where + 1) instanceof IntraMap)) {
            return -1;
        }
        IntraMap second = (IntraMap) list.map(where + 1);
        String flag1 = intraFlag != null ? intraFlag : "";
        String flag2 = second.intraFlag != null ? second.intraFlag : "";
        if (!fname.equals(second.fname) || !flag1.equals(flag2)) {
            return -1;
        }
        boolean invert1 = list.invert(where);
        boolean invert2 = list.invert(where + 1);
        int nin1 = invert1 ? createdNout() : createdNin();
        int nout2 = invert2 ? second.createdNin() : second.createdNout();
        if (nin1 != nout2) {
            return -1;
        }
        boolean simpler;
        if (!invert1 && invert2) {
            simpler = (flags & IntraRegistry.SIMPFI) != 0;
        } else if (invert1 && !invert2) {
            simpler = (flags & IntraRegistry.SIMPIF) != 0;
        } else {
            simpler = false;
        }
        if (!simpler) {
            return -1;
        }
        list.replacePairWith(where, new UnitMap(nin1), false);
        return where;
    }
}
