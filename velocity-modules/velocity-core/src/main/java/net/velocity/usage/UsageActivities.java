package net.velocity.usage;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import net.velocity.agent.spi.UsageActivityContributor;

/**
 * The set of valid usage activity names: the core names plus every {@link UsageActivityContributor}.
 *
 * <p>Shape is checked wherever a {@link UsageLedger.Context} is built ({@link #requireWellFormed}); membership is
 * checked when a request is begun, when a report range is requested and when a stored record is decoded
 * ({@link #require}, {@link #contains}), so a well-formed name nobody contributed is still rejected there with the
 * same message. Construction fails when a contribution is malformed, reuses a core name or repeats a name already
 * contributed.
 */
public final class UsageActivities {

    /** Activities recorded by the core application; reserved. */
    public static final Set<String> CORE = Set.of(
            "task",
            "delegated",
            "attachment",
            "monitoring",
            "scheduled",
            "learning",
            "playground",
            "unattributed");
    private static final Pattern SHAPE = Pattern.compile("[a-z][a-z_]{1,39}");
    private final Set<String> names;

    public UsageActivities(List<UsageActivityContributor> contributors) {
        var all = new TreeSet<>(CORE);
        for (UsageActivityContributor contributor : Objects.requireNonNull(contributors, "Contributors")) {
            for (String name : Objects.requireNonNull(contributor, "Contributor").activities()) {
                if (name == null || !SHAPE.matcher(name).matches()) {
                    throw new IllegalArgumentException("Invalid usage activity name: " + name);
                }
                if (!all.add(name)) {
                    throw new IllegalArgumentException("Duplicate or reserved usage activity name: " + name);
                }
            }
        }
        this.names = Set.copyOf(all);
    }

    /** Rejects a null (with a {@link NullPointerException}) or malformed name; any shape-valid name passes. */
    static void requireWellFormed(String activity) {
        Objects.requireNonNull(activity);
        if (!SHAPE.matcher(activity).matches()) {
            throw new IllegalArgumentException("Unknown usage activity");
        }
    }

    public boolean contains(String activity) {
        return activity != null && names.contains(activity);
    }

    /** Fails with the message every caller used before activities were contributed. */
    public void require(String activity) {
        Objects.requireNonNull(activity);
        if (!names.contains(activity)) {
            throw new IllegalArgumentException("Unknown usage activity");
        }
    }
}
