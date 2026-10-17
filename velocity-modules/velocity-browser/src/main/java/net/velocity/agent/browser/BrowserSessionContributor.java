package net.velocity.agent.browser;

import java.util.Objects;

import net.velocity.agent.spi.ContextContributor;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.agent.state.TrustedScope;

/** Keeps sessions through question pauses and releases them after terminal execution. */
public final class BrowserSessionContributor implements ContextContributor {

    private final BrowserService browsers;

    public BrowserSessionContributor(BrowserService browsers) {
        this.browsers = Objects.requireNonNull(browsers);
    }

    @Override
    public String supplementalInstructions(TrustedScope scope) {
        return BrowserPrompts.BROWSER_INSTRUCTIONS;
    }

    @Override
    public void completed(ExecutionSnapshot snapshot) {
        if (snapshot.status.terminal()) {
            browsers.releaseExecution(snapshot.executionId);
        }
    }

    @Override
    public void deleted(String executionId) {
        browsers.releaseExecution(executionId);
    }
}
