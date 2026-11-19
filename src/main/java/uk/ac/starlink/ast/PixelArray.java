package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * An array of pixel values of one of jniast's data types, with the bad value of the call it is
 * given to: the Java form of the {@code Xtype}, {@code Xfloating}, {@code Xfloattype} and {@code
 * Xsigned} arguments of the macros that generate each of C's resampling and rebinning functions
 * once per type.
 *
 * <p>Values are passed as doubles. A type summed in {@code float} (C's {@code Xfloattype}: {@code
 * float} for F, S and B, {@code double} for D, L and I) has each step of its arithmetic computed
 * in double and rounded to float by {@link #sum}, which gives C's float arithmetic exactly for
 * +, -, * and /, since a double holds more than twice a float's precision.
 *
 * <p>Conversions to an integer type are those of C compiled for AArch64, where the C probes were
 * run: a conversion to {@code int} or {@code long} saturates, as Java's casts do, and one to
 * {@code short} or {@code byte} goes through {@code int}.
 *
 * <p>Ported from Rust {@code pixel.rs} ({@code PixelValue} and {@code RebinValue}) and C {@code mapping.c}.
 */
abstract class PixelArray {

    /** Whether the type is floating point (C's {@code Xfloating}). */
    private final boolean floating;

    /** Whether the type is summed in {@code float} (C's {@code Xfloattype}). */
    private final boolean sumInFloat;

    /**
     * For an integer type, the sum at or below which an interpolated value overflows the type when
     * rounded (C's {@code LO_<X>}); unused for a floating type.
     */
    private final double loLim;

    /**
     * For an integer type, the sum at or above which an interpolated value overflows the type when
     * rounded (C's {@code HI_<X>}); unused for a floating type.
     */
    private final double hiLim;

    private PixelArray(boolean floating, boolean floatSum, double loLim, double hiLim) {
        this.floating = floating;
        this.sumInFloat = floatSum;
        this.loLim = loLim;
        this.hiLim = hiLim;
    }

    /** The {@code byte} array {@code a}, with bad value {@code bad}. */
    static PixelArray of(byte[] a, byte bad) {
        return new Bytes(a, bad);
    }

    /** The {@code short} array {@code a}, with bad value {@code bad}. */
    static PixelArray of(short[] a, short bad) {
        return new Shorts(a, bad);
    }

    /** The {@code int} array {@code a}, with bad value {@code bad}. */
    static PixelArray of(int[] a, int bad) {
        return new Ints(a, bad);
    }

    /** The {@code long} array {@code a}, with bad value {@code bad}. */
    static PixelArray of(long[] a, long bad) {
        return new Longs(a, bad);
    }

    /** The {@code float} array {@code a}, with bad value {@code bad}. */
    static PixelArray of(float[] a, float bad) {
        return new Floats(a, bad);
    }

    /** The {@code double} array {@code a}, with bad value {@code bad}. */
    static PixelArray of(double[] a, double bad) {
        return new Doubles(a, bad);
    }

    /** The number of elements. */
    abstract int length();

    /** Element {@code i} as a double (C's {@code (double) in[i]}). */
    abstract double get(int i);

    /**
     * Whether element {@code i} equals the bad value, compared in the type (C's {@code in[i] ==
     * badval}): a NaN element is never bad, even when the bad value is NaN.
     */
    abstract boolean isBad(int i);

    /** Sets element {@code i} to the bad value. */
    abstract void setBad(int i);

    /** Sets element {@code i} to element {@code j} of {@code src}, an array of the same type. */
    abstract void copy(int i, PixelArray src, int j);

    /**
     * Sets element {@code i} to {@code v} converted to the type (C's {@code (Xtype) v}): rounded to
     * float, or truncated toward zero for an integer type.
     */
    abstract void set(int i, double v);

    /**
     * {@code v} converted to the type, as a double (C's {@code (Xtype) v}, which {@link #set}
     * stores).
     */
    abstract double fromC(double v);

    /**
     * Adds {@code c}, a value of the type, to element {@code i} in the type's arithmetic (C's
     * {@code a[i] += c} with both of the type): wrapping on overflow for an integer type, rounded
     * to float for {@code float}.
     */
    abstract void add(int i, double c);

    /** The underlying Java array. */
    abstract Object array();

    /** A new array of this type and bad value, of {@code n} elements each the bad value. */
    abstract PixelArray filled(int n);

    /**
     * Calls the {@code uinterp<X>} method of {@code calc} for this array's type, with this array
     * as {@code in}, the arrays of {@code inVar}, {@code out} and {@code outVar} (each of this
     * type, or null for a variance) and the bad value, returning what it returns.
     *
     * @throws Exception whatever the method throws
     */
    abstract int uinterp(
            UinterpCalculator calc,
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            PixelArray inVar,
            int npoint,
            int[] offset,
            double[][] coords,
            ResampleFlags flags,
            PixelArray out,
            PixelArray outVar)
            throws Exception;

    /** Whether the type is summed in {@code float} (C's {@code Xfloattype} is {@code float}). */
    final boolean floatSum() {
        return sumInFloat;
    }

    /** {@code v} rounded to the type's sum type (C's {@code (Xfloattype) v}). */
    final double sum(double v) {
        return sumInFloat ? (float) v : v;
    }

    /** Whether the type is signed (C's {@code Xsigned}), so that a negative variance is bad. */
    final boolean signed() {
        // Every jniast type is signed; C's unsigned types have no jniast counterpart.
        return true;
    }

    /**
     * Whether the interpolated value {@code v}, a value of the sum type, would overflow an integer
     * type when rounded (C's {@code v <= LO_<X> || v >= HI_<X>}, tested only for an integer type);
     * always false for a floating type. A NaN does not overflow.
     */
    final boolean overflows(double v) {
        return !floating && (v <= loLim || v >= hiLim);
    }

    /**
     * C's {@code CONV( IntType, v )} for this type: {@code v} rounded half away from zero through
     * {@code int} for an integer type, and {@code v} itself for a floating type.
     */
    final double conv(double v) {
        return floating ? v : (int) (v + (v > 0.0 ? 0.5 : -0.5));
    }

    /**
     * Sets element {@code i} to the interpolated value {@code v}, a value of the sum type, as C
     * assigns one: unchanged for a floating type, and for an integer type rounded half away from
     * zero by adding or subtracting a half in the sum type and converting as {@link #set} does.
     */
    final void setResampled(int i, double v) {
        if (floating) {
            set(i, v);
        } else {
            set(i, sum(v + (v >= 0.0 ? 0.5 : -0.5)));
        }
    }

    /** A {@code byte} array, summed in float. */
    private static final class Bytes extends PixelArray {
        private final byte[] a;
        private final byte bad;

        Bytes(byte[] a, byte bad) {
            super(false, true, -0.5f + Byte.MIN_VALUE, 0.5f + Byte.MAX_VALUE);
            this.a = a;
            this.bad = bad;
        }

        @Override
        int length() {
            return a.length;
        }

        @Override
        double get(int i) {
            return a[i];
        }

        @Override
        boolean isBad(int i) {
            return a[i] == bad;
        }

        @Override
        void setBad(int i) {
            a[i] = bad;
        }

        @Override
        void copy(int i, PixelArray src, int j) {
            a[i] = ((Bytes) src).a[j];
        }

        @Override
        void set(int i, double v) {
            a[i] = (byte) (int) v;
        }

        @Override
        double fromC(double v) {
            return (byte) (int) v;
        }

        @Override
        void add(int i, double c) {
            a[i] = (byte) (a[i] + (byte) (int) c);
        }

        @Override
        Object array() {
            return a;
        }

        @Override
        PixelArray filled(int n) {
            byte[] b = new byte[n];
            Arrays.fill(b, bad);
            return new Bytes(b, bad);
        }

        @Override
        int uinterp(
                UinterpCalculator calc,
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                PixelArray inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                PixelArray out,
                PixelArray outVar)
                throws Exception {
            return calc.uinterpB(
                    ndimIn,
                    lbndIn,
                    ubndIn,
                    a,
                    inVar == null ? null : ((Bytes) inVar).a,
                    npoint,
                    offset,
                    coords,
                    flags,
                    bad,
                    ((Bytes) out).a,
                    outVar == null ? null : ((Bytes) outVar).a);
        }
    }

    /** A {@code short} array, summed in float. */
    private static final class Shorts extends PixelArray {
        private final short[] a;
        private final short bad;

        Shorts(short[] a, short bad) {
            super(false, true, -0.5f + Short.MIN_VALUE, 0.5f + Short.MAX_VALUE);
            this.a = a;
            this.bad = bad;
        }

        @Override
        int length() {
            return a.length;
        }

        @Override
        double get(int i) {
            return a[i];
        }

        @Override
        boolean isBad(int i) {
            return a[i] == bad;
        }

        @Override
        void setBad(int i) {
            a[i] = bad;
        }

        @Override
        void copy(int i, PixelArray src, int j) {
            a[i] = ((Shorts) src).a[j];
        }

        @Override
        void set(int i, double v) {
            a[i] = (short) (int) v;
        }

        @Override
        double fromC(double v) {
            return (short) (int) v;
        }

        @Override
        void add(int i, double c) {
            a[i] = (short) (a[i] + (short) (int) c);
        }

        @Override
        Object array() {
            return a;
        }

        @Override
        PixelArray filled(int n) {
            short[] b = new short[n];
            Arrays.fill(b, bad);
            return new Shorts(b, bad);
        }

        @Override
        int uinterp(
                UinterpCalculator calc,
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                PixelArray inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                PixelArray out,
                PixelArray outVar)
                throws Exception {
            return calc.uinterpS(
                    ndimIn,
                    lbndIn,
                    ubndIn,
                    a,
                    inVar == null ? null : ((Shorts) inVar).a,
                    npoint,
                    offset,
                    coords,
                    flags,
                    bad,
                    ((Shorts) out).a,
                    outVar == null ? null : ((Shorts) outVar).a);
        }
    }

    /** An {@code int} array, summed in double. */
    private static final class Ints extends PixelArray {
        private final int[] a;
        private final int bad;

        Ints(int[] a, int bad) {
            super(false, false, -0.5 + Integer.MIN_VALUE, 0.5 + Integer.MAX_VALUE);
            this.a = a;
            this.bad = bad;
        }

        @Override
        int length() {
            return a.length;
        }

        @Override
        double get(int i) {
            return a[i];
        }

        @Override
        boolean isBad(int i) {
            return a[i] == bad;
        }

        @Override
        void setBad(int i) {
            a[i] = bad;
        }

        @Override
        void copy(int i, PixelArray src, int j) {
            a[i] = ((Ints) src).a[j];
        }

        @Override
        void set(int i, double v) {
            a[i] = (int) v;
        }

        @Override
        double fromC(double v) {
            return (int) v;
        }

        @Override
        void add(int i, double c) {
            a[i] += (int) c;
        }

        @Override
        Object array() {
            return a;
        }

        @Override
        PixelArray filled(int n) {
            int[] b = new int[n];
            Arrays.fill(b, bad);
            return new Ints(b, bad);
        }

        @Override
        int uinterp(
                UinterpCalculator calc,
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                PixelArray inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                PixelArray out,
                PixelArray outVar)
                throws Exception {
            return calc.uinterpI(
                    ndimIn,
                    lbndIn,
                    ubndIn,
                    a,
                    inVar == null ? null : ((Ints) inVar).a,
                    npoint,
                    offset,
                    coords,
                    flags,
                    bad,
                    ((Ints) out).a,
                    outVar == null ? null : ((Ints) outVar).a);
        }
    }

    /** A {@code long} array, summed in double. */
    private static final class Longs extends PixelArray {
        private final long[] a;
        private final long bad;

        Longs(long[] a, long bad) {
            super(false, false, -0.5 + Long.MIN_VALUE, 0.5 + Long.MAX_VALUE);
            this.a = a;
            this.bad = bad;
        }

        @Override
        int length() {
            return a.length;
        }

        @Override
        double get(int i) {
            return a[i];
        }

        @Override
        boolean isBad(int i) {
            return a[i] == bad;
        }

        @Override
        void setBad(int i) {
            a[i] = bad;
        }

        @Override
        void copy(int i, PixelArray src, int j) {
            a[i] = ((Longs) src).a[j];
        }

        @Override
        void set(int i, double v) {
            a[i] = (long) v;
        }

        @Override
        double fromC(double v) {
            return (long) v;
        }

        @Override
        void add(int i, double c) {
            a[i] += (long) c;
        }

        @Override
        Object array() {
            return a;
        }

        @Override
        PixelArray filled(int n) {
            long[] b = new long[n];
            Arrays.fill(b, bad);
            return new Longs(b, bad);
        }

        @Override
        int uinterp(
                UinterpCalculator calc,
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                PixelArray inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                PixelArray out,
                PixelArray outVar)
                throws Exception {
            return calc.uinterpL(
                    ndimIn,
                    lbndIn,
                    ubndIn,
                    a,
                    inVar == null ? null : ((Longs) inVar).a,
                    npoint,
                    offset,
                    coords,
                    flags,
                    bad,
                    ((Longs) out).a,
                    outVar == null ? null : ((Longs) outVar).a);
        }
    }

    /** A {@code float} array, summed in float. */
    private static final class Floats extends PixelArray {
        private final float[] a;
        private final float bad;

        Floats(float[] a, float bad) {
            super(true, true, 0.0, 0.0);
            this.a = a;
            this.bad = bad;
        }

        @Override
        int length() {
            return a.length;
        }

        @Override
        double get(int i) {
            return a[i];
        }

        @Override
        boolean isBad(int i) {
            return a[i] == bad;
        }

        @Override
        void setBad(int i) {
            a[i] = bad;
        }

        @Override
        void copy(int i, PixelArray src, int j) {
            a[i] = ((Floats) src).a[j];
        }

        @Override
        void set(int i, double v) {
            a[i] = (float) v;
        }

        @Override
        double fromC(double v) {
            return (float) v;
        }

        @Override
        void add(int i, double c) {
            a[i] += (float) c;
        }

        @Override
        Object array() {
            return a;
        }

        @Override
        PixelArray filled(int n) {
            float[] b = new float[n];
            Arrays.fill(b, bad);
            return new Floats(b, bad);
        }

        @Override
        int uinterp(
                UinterpCalculator calc,
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                PixelArray inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                PixelArray out,
                PixelArray outVar)
                throws Exception {
            return calc.uinterpF(
                    ndimIn,
                    lbndIn,
                    ubndIn,
                    a,
                    inVar == null ? null : ((Floats) inVar).a,
                    npoint,
                    offset,
                    coords,
                    flags,
                    bad,
                    ((Floats) out).a,
                    outVar == null ? null : ((Floats) outVar).a);
        }
    }

    /** A {@code double} array, summed in double. */
    private static final class Doubles extends PixelArray {
        private final double[] a;
        private final double bad;

        Doubles(double[] a, double bad) {
            super(true, false, 0.0, 0.0);
            this.a = a;
            this.bad = bad;
        }

        @Override
        int length() {
            return a.length;
        }

        @Override
        double get(int i) {
            return a[i];
        }

        @Override
        boolean isBad(int i) {
            return a[i] == bad;
        }

        @Override
        void setBad(int i) {
            a[i] = bad;
        }

        @Override
        void copy(int i, PixelArray src, int j) {
            a[i] = ((Doubles) src).a[j];
        }

        @Override
        void set(int i, double v) {
            a[i] = v;
        }

        @Override
        double fromC(double v) {
            return v;
        }

        @Override
        void add(int i, double c) {
            a[i] += c;
        }

        @Override
        Object array() {
            return a;
        }

        @Override
        PixelArray filled(int n) {
            double[] b = new double[n];
            Arrays.fill(b, bad);
            return new Doubles(b, bad);
        }

        @Override
        int uinterp(
                UinterpCalculator calc,
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                PixelArray inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                PixelArray out,
                PixelArray outVar)
                throws Exception {
            return calc.uinterpD(
                    ndimIn,
                    lbndIn,
                    ubndIn,
                    a,
                    inVar == null ? null : ((Doubles) inVar).a,
                    npoint,
                    offset,
                    coords,
                    flags,
                    bad,
                    ((Doubles) out).a,
                    outVar == null ? null : ((Doubles) outVar).a);
        }
    }
}
