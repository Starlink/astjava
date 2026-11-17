package uk.ac.starlink.ast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A Mapping which maps points using a private transformation function. The {@code IntraMap}
 * allows you to use a custom transformation function, as implemented by a {@link Transformer}
 * object, to perform the transformations used by a {@code Mapping}. This allows you to create
 * {@code Mapping}s that perform any conceivable coordinate transformation.
 *
 * <p>However, an {@code IntraMap} is intended for use within a given software environment, and if
 * they are to be communicated via {@link Channel}s can only be used where class libraries
 * containing the appropriate {@code Transformer} classes are available (and only if those classes
 * are {@code Serializable}).
 *
 * <p>The IntraMap holds its Transformer by reference; a {@link #copy} shares it. The name of its
 * transformation function (written to a dump as {@code Fname}) is the Transformer's class name
 * followed by {@code #} and its hash code in hexadecimal, and two IntraMaps with the same name
 * are taken to apply the same function. The flags C registers with the function (whether the
 * forward and inverse transformations exist, and whether a forward and inverse pair, or an
 * inverse and forward pair, in series simplify away) and the purpose, author and contact strings
 * are read from the Transformer when the IntraMap is made.
 *
 * <p>The IntraFlag attribute is a string which distinguishes this IntraMap from others using the
 * same function: a pair of IntraMaps that could cancel is simplified only if their IntraFlag
 * values are the same (an unset value reading as an empty string), and two IntraMaps are equal
 * only if both have it set to the same value. It is unset by default, and cannot be changed once
 * the IntraMap is held by another Object.
 *
 * <p>When it is written to a Channel, an IntraMap whose Transformer is {@code Serializable} has
 * the serialised Transformer written as its IntraFlag value, in jniast's printable encoding,
 * wherever the IntraMap is in the Object written; reading it back recovers the Transformer. An
 * IntraMap whose Transformer is not Serializable is written as C writes it, with its IntraFlag
 * value, and can be read back only through a Channel with which its function has been registered
 * under its name.
 *
 * <p>Ported from Rust {@code mappings/intramap.rs}, C {@code intramap.c} and jniast's {@code
 * IntraMap.java} and {@code jni/IntraMap.c}.
 */
public class IntraMap extends Mapping implements NeedsChannelizing {

    /** Printable characters used for encoding and decoding (jniast's {@code Chrs64}). */
    private static final String CHRS64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

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
     */
    public IntraMap(Transformer trans, int nin, int nout) {
        super(checkCounts(trans, nin, nout), nout, trans.hasForward(), trans.hasInverse());
        this.trans = trans;
        this.fname = trans.getClass().getName() + "#" + Integer.toHexString(trans.hashCode());
        this.flags = IntraRegistry.flagsOf(trans);
        this.purpose = IntraRegistry.text(trans.getPurpose());
        this.contact = IntraRegistry.text(trans.getContact());
        this.author = IntraRegistry.text(trans.getAuthor());
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

    /**
     * {@code nin}, after jniast's check (jni/IntraMap.c {@code construct}) that {@code trans} can
     * map {@code nin} to {@code nout} coordinates.
     */
    private static int checkCounts(Transformer trans, int nin, int nout) {
        Objects.requireNonNull(trans, "trans");
        if (!trans.canTransformCoords(nin, nout)) {
            throw new IllegalArgumentException("Transformer will not map " + nin + " -> " + nout + " coordinates");
        }
        return nin;
    }

    /**
     * Loads an IntraMap from its description, as C's {@code astLoadIntraMap} does: {@code Fname},
     * {@code Iflag}, {@code Purp}, {@code Auth} and {@code Cntact}. The Transformer is the one the
     * IntraFlag value holds in jniast's printable encoding; when it holds none, it is the one
     * registered under the function name with the Channel reading ({@link IntraRegistry}), whose
     * numbers of coordinates must match the IntraMap's.
     *
     * @throws AstException with status {@code AST__URITF} if the IntraFlag holds no Transformer and
     *     no function is registered under the name, {@code AST__BADNI} or {@code AST__BADNO} if the
     *     Transformer cannot transform the IntraMap's numbers of coordinates
     */
    IntraMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("IntraMap");
        String chan = node.channelClass;
        fname = part.readString("Fname", "");
        intraFlag = part.getStringIfSet("Iflag");
        String dumpPurpose = part.readString("Purp", "");
        String dumpAuthor = part.readString("Auth", "");
        String dumpContact = part.readString("Cntact", "");
        int nin = createdNin();
        int nout = createdNout();
        Exception[] failure = new Exception[1];
        Transformer decoded = decodeTransformer(intraFlag, failure);
        if (decoded != null) {
            if (!decoded.canTransformCoords(nin, nout)) {
                throw Ast.err(
                        AstException.AST__BADNI,
                        "astLoadIntraMap(" + chan + "): The Transformer read will not map " + nin + " -> " + nout
                                + " coordinates.");
            }
            trans = decoded;
            flags = IntraRegistry.flagsOf(decoded);
            purpose = IntraRegistry.text(decoded.getPurpose());
            author = IntraRegistry.text(decoded.getAuthor());
            contact = IntraRegistry.text(decoded.getContact());
            return;
        }
        IntraRegistry.Entry entry = IntraRegistry.lookup(fname);
        if (entry == null) {
            AstException err = Ast.err(
                    AstException.AST__URITF,
                    "astLoadIntraMap(" + chan + "): An IntraMap was read which uses an unknown transformation "
                            + "function.\nThis is a private extension to the AST library: to handle it, you must "
                            + "obtain the source code from its author.\nYou can then register it with AST in your "
                            + "software by calling astIntraReg (see SUN/211).\n \n   Function name:   \"" + fname
                            + "\".\n   Purpose:         \"" + dumpPurpose + "\".\n   Author:          \""
                            + dumpAuthor + "\".\n   Contact address: \"" + dumpContact + "\".\n ");
            if (failure[0] != null) {
                err.initCause(failure[0]);
            }
            throw err;
        }
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
     * {@code Dump} (intramap.c) does. When the Transformer can be serialised, {@code Iflag} holds
     * it in jniast's printable encoding; otherwise it is the IntraFlag value, written as a blank
     * string when unset.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeString("Fname", true, true, fname, "Name of transformation function");
        String encoded = encodedTransformer();
        if (encoded != null) {
            node.writeString("Iflag", true, false, encoded, "IntraMap identification string");
        } else {
            node.writeString(
                    "Iflag",
                    intraFlag != null,
                    false,
                    intraFlag != null ? intraFlag : "",
                    "IntraMap identification string");
        }
        node.writeString("Purp", true, true, purpose, "Purpose of function");
        node.writeString("Auth", true, true, author, "Author's name");
        node.writeString("Cntact", true, true, contact, "Contact address");
    }

    // ------------------------------------------------------------------
    // Channelizing and the printable encoding
    // ------------------------------------------------------------------

    /**
     * Checks that this IntraMap's Transformer can be serialised, as it must be to be written to a
     * Channel, which calls this method before it writes the IntraMap. The serialised Transformer is
     * written as the IntraMap's IntraFlag value; this method does not change the IntraMap.
     *
     * @throws NotSerializableException if the {@code Transformer} used by this mapping cannot be
     *     serialised
     * @throws IOException if there was some error in I/O
     */
    @Override
    public void channelize() throws IOException {
        serialised();
    }

    /**
     * Does nothing, since {@link #channelize} does not change the IntraMap and the Transformer of
     * an IntraMap read from a Channel is recovered from its IntraFlag value as it is read. A
     * Channel calls this method after reading or writing an IntraMap.
     *
     * @throws IOException never
     */
    @Override
    public void unChannelize() throws IOException {
        // The loader has already recovered the Transformer, wherever the IntraMap was.
    }

    /**
     * The Transformer serialised, as jniast's {@code channelize} serialises it.
     *
     * @throws NotSerializableException if the Transformer is not Serializable
     */
    private byte[] serialised() throws IOException {
        if (!(trans instanceof Serializable)) {
            throw new NotSerializableException(trans.getClass().getName());
        }
        ByteArrayOutputStream bstream = new ByteArrayOutputStream();
        try (ObjectOutputStream ostream = new ObjectOutputStream(bstream)) {
            ostream.writeObject(trans);
            ostream.flush();
        }
        return bstream.toByteArray();
    }

    /** The serialised Transformer in printable form, or null if it cannot be serialised. */
    private String encodedTransformer() {
        if (!(trans instanceof Serializable)) {
            return null;
        }
        try {
            return encodePrintable(serialised());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The Transformer that {@code iflag} holds in printable form, or null if it holds none; the
     * reason it holds none, if it is an exception, is put in {@code failure[0]}.
     */
    // A Transformer's readObject may throw anything; any failure means the value holds none.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static Transformer decodeTransformer(String iflag, Exception[] failure) {
        if (iflag == null) {
            return null;
        }
        byte[] bytes = decodePrintable(iflag);
        if (bytes == null) {
            return null;
        }
        try (ObjectInputStream ostream = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object obj = ostream.readObject();
            return obj instanceof Transformer ? (Transformer) obj : null;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            failure[0] = e;
            return null;
        }
    }

    /**
     * Encodes an arbitrary string of bytes into printable form, as jniast's {@code
     * encodePrintable} (IntraMap.java) does: each group of three bytes, the last padded with zero
     * bytes, gives four characters of {@link #CHRS64}, six bits at a time from the lowest bits of
     * the first byte. jniast's result also has four or eight zero characters after the useful
     * part, which are not written here.
     */
    static String encodePrintable(byte[] plain) {
        int leng = plain.length;
        StringBuilder coded = new StringBuilder((leng / 3 + 1) * 4);
        int ip = 0;
        while (ip < leng) {
            byte b0 = ip < leng ? plain[ip++] : 0;
            byte b1 = ip < leng ? plain[ip++] : 0;
            byte b2 = ip < leng ? plain[ip++] : 0;
            int ix0 = b0 & 0x3f;
            int ix1 = ((b0 & 0xc0) >>> 6) | ((b1 & 0x0f) << 2);
            int ix2 = ((b1 & 0xf0) >>> 4) | ((b2 & 0x03) << 4);
            int ix3 = (b2 & 0xfc) >>> 2;
            coded.append(CHRS64.charAt(ix0))
                    .append(CHRS64.charAt(ix1))
                    .append(CHRS64.charAt(ix2))
                    .append(CHRS64.charAt(ix3));
        }
        return coded.toString();
    }

    /**
     * Decodes a string of printable characters, the reverse of {@link #encodePrintable}, as
     * jniast's {@code decodePrintable} (IntraMap.java) decodes the UTF-8 bytes of the IntraFlag
     * value: a byte that is not one of {@link #CHRS64}, such as the zero characters jniast's
     * encoding ends with, counts as zero, and zero bytes may follow the useful part of the result.
     *
     * @return the bytes, or null if a character is not ASCII, which jniast cannot decode
     */
    static byte[] decodePrintable(String text) {
        byte[] coded = text.getBytes(StandardCharsets.UTF_8);
        int leng = coded.length;
        byte[] plain = new byte[(leng / 4 + 2) * 3];
        int ic = 0;
        int ip = 0;
        while (ic < leng) {
            int b0 = ic < leng ? inv64(coded[ic++]) : 0;
            int b1 = ic < leng ? inv64(coded[ic++]) : 0;
            int b2 = ic < leng ? inv64(coded[ic++]) : 0;
            int b3 = ic < leng ? inv64(coded[ic++]) : 0;
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
                return null;
            }
            plain[ip++] = (byte) (b0 | (b1 << 6));
            plain[ip++] = (byte) ((b1 >>> 2) | (b2 << 4));
            plain[ip++] = (byte) ((b2 >>> 4) | (b3 << 2));
        }
        return plain;
    }

    /**
     * jniast's {@code Inv64} for byte {@code b}: its index in {@link #CHRS64}, 0 for another ASCII
     * byte, or -1 for a byte that is not ASCII, which jniast's table cannot index.
     */
    private static int inv64(byte b) {
        if (b < 0) {
            return -1;
        }
        int i = CHRS64.indexOf((char) b);
        return i < 0 ? 0 : i;
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
