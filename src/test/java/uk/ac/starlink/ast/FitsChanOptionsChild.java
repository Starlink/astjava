package uk.ac.starlink.ast;

/**
 * The program {@code FitsChanTest.fitschanOptionsAppliesToEveryNewFitschan} runs in a child JVM
 * with {@code FITSCHAN_OPTIONS} set: it checks the FitsChans it makes for the mode given as its
 * argument, prints "passed" and exits with status 0, or prints the failure and exits with status
 * 1.
 */
final class FitsChanOptionsChild {

    private FitsChanOptionsChild() {}

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException(what);
        }
    }

    /**
     * Runs the checks for one mode.
     *
     * @param args the mode: {@code good}, {@code bad} or {@code none}
     */
    // The parent process reads the result from standard output, and any failure is reported there.
    @SuppressWarnings({"PMD.SystemPrintln", "PMD.AvoidCatchingGenericException"})
    public static void main(String[] args) {
        try {
            run(args[0]);
            System.out.println("passed");
        } catch (RuntimeException e) {
            System.out.println("failed: " + e);
            System.exit(1);
        }
    }

    private static void run(String mode) {
        if ("good".equals(mode)) {
            for (int i = 0; i < 2; i++) {
                FitsChan fc = new FitsChan();
                check("FITS-WCS".equals(fc.getEncoding()), "Encoding " + fc.getEncoding());
                check(fc.test("Encoding"), "Encoding not set");
                check(fc.getFitsDigits() == 8, "FitsDigits " + fc.getFitsDigits());
                check("badval".equals(fc.getWarnings()), "Warnings " + fc.getWarnings());
            }
            FitsChan fc = FitsChan.fromHeader("NAXIS   =                    2");
            check(fc.getFitsDigits() == 8, "FitsDigits from a header " + fc.getFitsDigits());
        } else if ("bad".equals(mode)) {
            try {
                new FitsChan();
            } catch (AstException e) {
                check(e.getStatus() == AstException.AST__BADAT, "status " + e.getStatusName());
                check(e.getMessage().contains("\"bogus=1\""), e.getMessage());
                return;
            }
            throw new IllegalStateException("no error");
        } else if ("none".equals(mode)) {
            check(new FitsChan().getFitsDigits() == 17, "FitsDigits");
        } else {
            throw new IllegalArgumentException("unknown mode " + mode);
        }
    }
}
