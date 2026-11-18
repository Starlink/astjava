package uk.ac.starlink.ast;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The random-number generator context of a MathMap, C's {@code Rcontext} (mathmap.h), with C's
 * {@code Rand}, {@code Gauss}, {@code Poisson}, {@code LogGamma} and {@code DefaultSeed}
 * (mathmap.c).
 *
 * <p>Each MathMap has its own context, which holds the Seed attribute and the state of a pair of
 * combined linear congruential generators with a Bays-Durham shuffle, so that the sequences of
 * different MathMaps are independent and each is set by its own Seed. A copy of a MathMap takes a
 * copy of its context, as C's copy does, and so continues the same sequence. C's {@code long} is
 * 64 bits on the platforms the library is built for, which Java's {@code long} matches; the
 * generator's arithmetic depends on it for a Seed below 1.
 *
 * <p>Ported from Rust {@code mappings/mathmap/rng.rs} and C {@code mathmap.c}.
 */
final class MathMapRandom implements Cloneable {

    /** The size of the shuffle table, {@code AST_MATHMAP_RAND_CONTEXT_NTAB_}. */
    private static final int NTAB = 32;

    /** The number of warm-up iterations before the shuffle table is filled. */
    private static final int NWARM = 8;

    private static final long A1 = 40014L;
    private static final long A2 = 40692L;
    private static final long M1 = 2147483563L;
    private static final long M2 = 2147483399L;
    private static final long Q1 = 53668L;
    private static final long Q2 = 52774L;
    private static final long R1 = 12211L;
    private static final long R2 = 3791L;

    /** The number of random integers combined into each value, C's {@code nrand}. */
    private static final int NRAND;

    /** The scale decrement for successive integers, C's {@code scale0}. */
    private static final double SCALE0;

    /** The normalisation constant, C's {@code norm}. */
    private static final double NORM;

    static {
        // C's local initialisation of Rand: the bits the generator produces (from m1), the bits of
        // a double's mantissa, and hence how many integers make a double.
        int lbits = Math.getExponent((double) M1) + 1;
        int dbits = (int) Math.ceil(53.0 * Math.log(2.0) / Math.log(2.0));
        NRAND = (dbits + lbits - 1) / lbits;
        SCALE0 = 1.0 / (M1 - 1L);
        double sum = 0.0;
        double scale = 1.0;
        for (int irand = 0; irand < NRAND; irand++) {
            scale *= SCALE0;
            sum += scale;
        }
        NORM = 1.0 / (sum * (M1 - 2L));
    }

    /** The constants of the quick generator of {@link #defaultSeed}. */
    private static final long SEED_A = 8121L;

    private static final long SEED_C = 28411L;
    private static final long SEED_M = 134456L;

    /**
     * The quick generator {@link #defaultSeed} mixes into each seed, C's {@code static long rand}
     * of {@code DefaultSeed}, which C starts from the time and the processor time on first use and
     * warms up with five iterations. It is shared by every MathMap, as C's is.
     */
    private static final AtomicLong SEED_RAND = new AtomicLong(initialSeedRand());

    /** C's first-use initialisation of {@code DefaultSeed}'s generator. */
    private static long initialSeedRand() {
        long bits = (System.currentTimeMillis() / 1000L) ^ clock();
        long rand = Long.remainderUnsigned(bits, SEED_M);
        for (int iwarm = 0; iwarm < 5; iwarm++) {
            rand = (rand * SEED_A + SEED_C) % SEED_M;
        }
        return rand;
    }

    /**
     * The stand-in for C's {@code clock()}, the processor time used: the JVM's high-resolution
     * time. Each serves only as bits that are hard to predict.
     */
    private static long clock() {
        return System.nanoTime();
    }

    /** The state of the first generator. */
    private long rand1;

    /** The state of the second generator. */
    private long rand2;

    /** The last random integer produced. */
    private long randomInt;

    /** The shuffle table. */
    private long[] table = new long[NTAB];

    /** Whether the generator has been initialised from the seed. */
    private boolean active;

    /** The seed used when the generator is initialised. */
    private int seed;

    /** Whether the seed was set through the Seed attribute. */
    private boolean seedSet;

    /**
     * Whether {@link #gauss} has a second value saved from its last pair. C keeps this, and the
     * value, in variables of {@code Gauss} shared by every MathMap; here each context keeps its
     * own.
     */
    private boolean ysaved;

    /** The second value of {@link #gauss}'s last pair, used when {@link #ysaved} is true. */
    private double ysave;

    /**
     * A context with an unpredictable default seed, as {@code astInitMathMap} makes it: inactive,
     * with no last random integer and the Seed unset.
     */
    MathMapRandom() {
        this(false, 0);
    }

    /**
     * A context as C's {@code astInitMathMap} or {@code astLoadMathMap} makes it: inactive, with no
     * last random integer, and with the seed given if {@code seeded} and an unpredictable default
     * otherwise.
     */
    MathMapRandom(boolean seeded, int value) {
        active = false;
        randomInt = 0L;
        seedSet = seeded;
        seed = seeded ? value : defaultSeed();
    }

    /** A copy of this context, as C's copy of a MathMap copies its context. */
    MathMapRandom copy() {
        try {
            MathMapRandom c = (MathMapRandom) super.clone();
            c.table = table.clone();
            return c;
        } catch (CloneNotSupportedException e) {
            AstException err = Ast.err(AstException.AST__INTER, "astCopy(MathMap): cannot clone the random context.");
            err.initCause(e);
            throw err;
        }
    }

    // ------------------------------------------------------------------
    // The Seed attribute
    // ------------------------------------------------------------------

    /** The seed: the one set, or the unpredictable default chosen when it was last unset. */
    int getSeed() {
        return seed;
    }

    /** Whether the seed was set through the Seed attribute. */
    boolean testSeed() {
        return seedSet;
    }

    /** Sets the seed and marks the generator to be initialised from it when next used. */
    void setSeed(int value) {
        seedSet = true;
        active = false;
        seed = value;
    }

    /**
     * Clears the seed, giving it a new unpredictable default value, and marks the generator to be
     * initialised from it when next used.
     */
    void clearSeed() {
        seedSet = false;
        active = false;
        seed = defaultSeed();
    }

    // ------------------------------------------------------------------
    // DefaultSeed
    // ------------------------------------------------------------------

    /**
     * An unpredictable seed, as C's {@code DefaultSeed} makes one: the bits of the time and the
     * processor time ({@link #clock}), of the context's identity, of the last random integer the
     * context produced, and of the next value of a quick generator shared by every MathMap, so
     * that two seeds made in quick succession differ. C uses the context's address as its
     * identity; Java has no addresses, so {@link System#identityHashCode} stands in for it.
     */
    private int defaultSeed() {
        long rand = SEED_RAND.updateAndGet(r -> (r * SEED_A + SEED_C) % SEED_M);
        long bits = System.currentTimeMillis() / 1000L;
        bits ^= clock();
        bits ^= System.identityHashCode(this);
        bits ^= randomInt;
        double scaled = ((double) rand / (SEED_M - 1L)) * (0x1.0p64 * (1.0 - Math.ulp(1.0)));
        bits ^= MathMapEvaluator.toUnsignedLong(scaled);
        return (int) bits;
    }

    // ------------------------------------------------------------------
    // Rand
    // ------------------------------------------------------------------

    /**
     * A random value uniformly distributed between 0 and 1 inclusive, as C's {@code Rand} gives
     * it. The generator is first initialised from the seed if it is not active: a seed below 1 has
     * {@code LONG_MAX} added, and is replaced by {@code LONG_MAX} if still below 1.
     */
    double rand() {
        if (!active) {
            long s = seed;
            if (s < 1) {
                s = s + Long.MAX_VALUE;
            }
            if (s < 1) {
                s = Long.MAX_VALUE;
            }
            rand1 = s;
            rand2 = s;
            for (int itab = NTAB + NWARM - 1; itab >= 0; itab--) {
                long tmp = rand1 / Q1;
                rand1 = A1 * (rand1 - tmp * Q1) - tmp * R1;
                if (rand1 < 0L) {
                    rand1 += M1;
                }
                if (itab < NTAB) {
                    table[itab] = rand1;
                }
            }
            randomInt = table[0];
            active = true;
        }
        double result = 0.0;
        double scale = NORM;
        for (int irand = 0; irand < NRAND; irand++) {
            long tmp = rand1 / Q1;
            rand1 = A1 * (rand1 - tmp * Q1) - tmp * R1;
            if (rand1 < 0L) {
                rand1 += M1;
            }
            tmp = rand2 / Q2;
            rand2 = A2 * (rand2 - tmp * Q2) - tmp * R2;
            if (rand2 < 0L) {
                rand2 += M2;
            }
            // C converts the quotient to int, keeping its low 32 bits, and then folds a negative
            // index into the table's range.
            int itab = (int) (randomInt / (1L + (M1 - 1L) / NTAB));
            if (itab < 0) {
                itab = -itab;
            }
            itab = itab % NTAB;
            randomInt = table[itab];
            table[itab] = rand1;
            randomInt -= rand2;
            if (randomInt < 1L) {
                randomInt += M1 - 1L;
            }
            scale *= SCALE0;
            result += scale * (randomInt - 1L);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Gauss
    // ------------------------------------------------------------------

    /**
     * A random value from a standard Gaussian distribution, as C's {@code Gauss} gives it: values
     * come in pairs from the Box-Muller transformation of a point drawn uniformly inside the unit
     * circle, the second being kept for the next call. A context that is not active discards a
     * kept value.
     */
    double gauss() {
        if (!active) {
            ysaved = false;
        }
        double x;
        if (ysaved) {
            x = ysave;
            ysaved = false;
        } else {
            double y;
            while (true) {
                double rsq;
                do {
                    x = 2.0 * rand() - 1.0;
                    y = 2.0 * rand() - 1.0;
                    rsq = x * x + y * y;
                } while (rsq >= 1.0 || rsq == 0.0);
                double s = -2.0 * Math.log(rsq);
                if (Double.MAX_VALUE * rsq >= s) {
                    s = Math.sqrt(s / rsq);
                    x *= s;
                    y *= s;
                    break;
                }
            }
            ysave = y;
            ysaved = true;
        }
        return x;
    }

    // ------------------------------------------------------------------
    // Poisson and LogGamma
    // ------------------------------------------------------------------

    /**
     * A random integer value from a Poisson distribution with mean {@code mean}, as C's {@code
     * Poisson} gives it: {@link Mapping#AST__BAD} for a negative mean; zero for a zero mean; for a
     * mean up to 9.3, the number of uniform values whose product stays above {@code exp(-mean)};
     * for a larger mean, a value drawn from a Lorentzian distribution and accepted with the ratio
     * of the two distributions; and for a mean so large that the deviation is negligible, the mean
     * itself. C keeps the constants it derives from the mean in static variables for the next
     * call with the same mean; they are computed here for each call, giving the same values.
     */
    double poisson(double mean) {
        final double small = 9.3;
        double result;
        double pi = Math.acos(-1.0);
        double sqrtPoint9 = Math.sqrt(0.9);
        double huge = Math.pow(1.0e3 / Math.ulp(1.0), 2.0);
        double ranmax = (Math.sqrt(Double.MAX_VALUE - 0.9) / Math.sqrt(0.9)) * (1.0 - 4.0 * Math.ulp(1.0));
        if (mean < 0.0) {
            result = Mapping.AST__BAD;
        } else if (mean == 0.0) {
            result = 0.0;
        } else if (mean <= small) {
            double thresh = Math.exp(-mean);
            double product = 1.0;
            result = -1.0;
            do {
                product *= rand();
                result += 1.0;
            } while (product > thresh);
        } else if (mean <= huge) {
            double logMean = Math.log(mean);
            double root2mean = Math.sqrt(2.0 * mean);
            double beta = mean * logMean - logGamma(mean + 1.0);
            double ran;
            double pfract;
            do {
                do {
                    // C also retries a tangent that overflows, which a finite argument does not.
                    do {
                        ran = Math.tan(pi * rand());
                    } while (ran > ranmax);
                    result = ran * root2mean + mean;
                } while (result < 0.0);
                result = Math.floor(result);
                ran *= sqrtPoint9;
                pfract = (0.9 + ran * ran) * Math.exp(result * logMean - logGamma(result + 1.0) - beta);
            } while (rand() > pfract);
        } else {
            result = mean;
        }
        return result;
    }

    /**
     * The natural logarithm of the gamma function, as C's {@code LogGamma} computes it with the
     * six-term series of Lanczos; {@link Mapping#AST__BAD} for an argument not above zero.
     */
    // The coefficients are C's literals, one of which has more digits than a double holds.
    @SuppressWarnings("FloatingPointLiteralPrecision")
    static double logGamma(double x) {
        final double c0 = 1.000000000190015;
        final double c1 = 76.18009172947146;
        final double c2 = -86.50532032941677;
        final double c3 = 24.01409824083091;
        final double c4 = -1.231739572450155;
        final double c5 = 0.1208650973866179e-2;
        final double c6 = -0.5395239384953e-5;
        final double g = 5.0;
        if (x <= 0.0) {
            return Mapping.AST__BAD;
        }
        double rootTwopi = Math.sqrt(2.0 * Math.acos(-1.0));
        double xx = x;
        double sum = c0;
        sum += c1 / ++xx;
        sum += c2 / ++xx;
        sum += c3 / ++xx;
        sum += c4 / ++xx;
        sum += c5 / ++xx;
        sum += c6 / ++xx;
        double result = x + g + 0.5;
        result -= (x + 0.5) * Math.log(result);
        return Math.log(rootTwopi * sum / x) - result;
    }
}
