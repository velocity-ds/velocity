package net.velocity.agent.state;

import java.util.Set;

/**
 * Immutable inference identity of one root execution. The binding is pinned at admission. The fallback is
 * admitted explicitly for new roots only, so later configuration changes cannot grant it. The transition is
 * the one-way cloud boundary and the compact view is the latest committed request prefix; original native
 * messages and metadata remain authoritative. Cross-group boundary rules stay in the execution codec.
 *
 * @param binding the pinned native conversation identity
 * @param fallback the admitted cloud continuation policy, or null
 * @param transition the committed cloud handoff, or null before the transition
 * @param compact the latest committed compact request view, or null
 */
public record InferenceState(
        InferenceBinding binding,
        InferenceBinding fallback,
        InferenceTransition transition,
        CompactRequestView compact) {

    public InferenceState {
        if (binding == null) {
            throw new IllegalArgumentException("Invalid inference state");
        }
        if (fallback != null && (!"cloud-fallback".equals(fallback.profileId())
                || !Set.of("openai", "claude").contains(fallback.providerId()))) {
            throw new IllegalArgumentException("Invalid admitted cloud profile");
        }
    }

    /** A new run, or a child view: the binding alone, with no fallback, transition or compact view. */
    public static InferenceState pinned(InferenceBinding binding) {
        return new InferenceState(binding, null, null, null);
    }

    /** The binding that serves the next request: the fallback only after the cloud transition. */
    public InferenceBinding activeBinding() {
        return transition == null ? binding : fallback;
    }

    public InferenceState withTransition(InferenceTransition committed) {
        return new InferenceState(binding, fallback, committed, compact);
    }

    public InferenceState withCompact(CompactRequestView committed) {
        return new InferenceState(binding, fallback, transition, committed);
    }
}
