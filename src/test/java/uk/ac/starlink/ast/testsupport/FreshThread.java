package uk.ac.starlink.ast.testsupport;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a check on a new thread. The library keeps some caches per thread, as C keeps them per
 * process (SkyFrame's tables of LAST values, for one), so a check whose expected values a C probe
 * made in a new process runs on a new thread, whose caches are empty, to see what the probe saw.
 */
public final class FreshThread {

    private FreshThread() {}

    /**
     * Runs {@code body} on a new thread, waits for it, and rethrows anything it throws: an Error
     * or RuntimeException as it is, anything else wrapped in an AssertionError.
     *
     * @param body the check
     * @throws InterruptedException if the wait is interrupted
     */
    public static void assertPasses(Runnable body) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(body, "fresh thread");
        t.setUncaughtExceptionHandler((th, e) -> failure.set(e));
        t.start();
        t.join();
        Throwable e = failure.get();
        if (e instanceof Error) {
            throw (Error) e;
        }
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        if (e != null) {
            throw new AssertionError(e);
        }
    }
}
