package net.velocity.agent;

import java.util.function.LongSupplier;

/**
 * Read-only inputs to {@link RunPhase#next} that are not stored in the snapshot itself. Each value is
 * supplied lazily so a phase decision reads one only when the original run loop would have reached the
 * condition that needs it (a child run reads its values from the root document).
 *
 * @param budget the budget regime of the execution
 * @param remainingMillis active time left for this run
 * @param modelRequests model requests charged to this run, including delegated ones
 * @param chargedTokens tokens charged to this run, including reservations
 * @param taskChargedTokens tokens charged to the root execution, which the task token ceiling bounds
 */
record RunFacts(
        BudgetPolicy budget,
        LongSupplier remainingMillis,
        LongSupplier modelRequests,
        LongSupplier chargedTokens,
        LongSupplier taskChargedTokens) {}
