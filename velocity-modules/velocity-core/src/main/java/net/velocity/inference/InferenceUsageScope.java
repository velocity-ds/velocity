package net.velocity.inference;

import net.velocity.usage.UsageLedger;

/** Trusted caller metadata only; never populated from model output or HTTP request fields. */
public final class InferenceUsageScope implements AutoCloseable {

    private static final ThreadLocal<UsageLedger.Context> CURRENT = new ThreadLocal<>();
    private final UsageLedger.Context previous;

    private InferenceUsageScope(UsageLedger.Context context) {
        previous = CURRENT.get();
        CURRENT.set(java.util.Objects.requireNonNull(context));
    }

    /** Open on the actual dispatch thread, including inside executor callbacks. */
    public static InferenceUsageScope open(UsageLedger.Context context) {
        return new InferenceUsageScope(context);
    }

    static UsageLedger.Context current() {
        var context = CURRENT.get();
        return context == null
                ? new UsageLedger.Context(null, null, null, "unattributed", null, null)
                : context;
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
