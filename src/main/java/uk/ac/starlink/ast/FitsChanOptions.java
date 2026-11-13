package uk.ac.starlink.ast;

/**
 * The attribute settings a new FitsChan takes from the {@code FITSCHAN_OPTIONS} environment
 * variable, as C's {@code astFitsChan} applies them with {@code astEnvSet} before its own options.
 *
 * <p>C reads the variable when the first FitsChan of a thread is made and keeps the value for every
 * later one in that thread (it is held in the thread's FitsChan class data). A variable set to
 * {@code None} is ignored, as C's {@code astEnvSet} stores that string for an unset variable and
 * skips any value equal to it.
 *
 * <p>C passes the value to {@code astSet} as its format string, so {@code VSet} expands any format
 * specifiers in it ({@code %%} becoming {@code %}, and any other being undefined behaviour); here
 * the value is taken as written, without that expansion.
 *
 * <p>Ported from Rust {@code fitschan/options.rs}.
 */
final class FitsChanOptions {

    /** The value of {@code FITSCHAN_OPTIONS} when this thread made its first FitsChan, or null. */
    private static final ThreadLocal<String[]> ENV_OPTIONS = ThreadLocal.withInitial(() -> {
        String v = System.getenv("FITSCHAN_OPTIONS");
        return new String[] {"None".equals(v) ? null : v};
    });

    private FitsChanOptions() {}

    /** The {@code FITSCHAN_OPTIONS} settings for a new FitsChan in this thread, or null. */
    static String envOptions() {
        return ENV_OPTIONS.get()[0];
    }

    /**
     * Applies {@code settings}, attribute settings separated by commas as {@code astSet} takes them,
     * to {@code fc}, stopping at the first that fails.
     *
     * @throws AstException the error of the setting that fails
     */
    static void apply(FitsChan fc, String settings) {
        fc.set(settings);
    }
}
