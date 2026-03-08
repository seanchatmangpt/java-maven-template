package org.acme;

import java.util.function.Supplier;

/**
 * Joe Armstrong "let it crash" philosophy applied to Java.
 *
 * <p>"The key to building reliable systems is to design for failure, not to try to prevent it. If
 * you can crash and recover, you're reliable." — Joe Armstrong
 *
 * <p>Each attempt runs in an isolated virtual thread (no shared state between attempts), mirroring
 * Erlang's lightweight process model: crash the process, supervisor retries with a fresh one.
 */
public final class CrashRecovery {

    private CrashRecovery() {}

    /**
     * Attempt {@code supplier} up to {@code maxAttempts} times, each in an isolated virtual thread.
     * Returns the first {@code Success}, or a {@code Failure} containing the last exception if all
     * attempts are exhausted.
     *
     * <p>Armstrong principle: processes share nothing — each virtual thread is a fresh "process"
     * with no state carried over from a previous crash.
     */
    public static <T> Result<T, Exception> retry(int maxAttempts, Supplier<T> supplier) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Result<T, Exception> last = Result.failure(new IllegalStateException("no attempts made"));
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            last = runInVirtualThread(supplier);
            if (last.isSuccess()) {
                return last;
            }
        }
        return last;
    }

    private static <T> Result<T, Exception> runInVirtualThread(Supplier<T> supplier) {
        var ref =
                new Object() {
                    Result<T, Exception> result;
                };
        var thread = Thread.ofVirtual().start(() -> ref.result = Result.of(supplier::get));
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure(e);
        }
        return ref.result;
    }
}
