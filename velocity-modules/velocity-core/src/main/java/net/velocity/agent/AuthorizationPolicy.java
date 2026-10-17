package net.velocity.agent;

import java.util.*;

import net.velocity.agent.state.*;

public interface AuthorizationPolicy {

    enum Decision {
        allow, deny
    }

    Decision authorize(TrustedScope scope, ToolRegistry.Definition tool, AcceptedCall call);

    static AuthorizationPolicy strict() {
        return (scope, tool, call) -> scope.ownerAccountId() != null && tool.trusted()
                ? Decision.allow
                : Decision.deny;
    }

    /** Includes accepted call identity, normalized input and every trusted scope component. */
    static String binding(TrustedScope scope, String callId, String tool, Map<String, Object> input) {
        return CallBindings.binding(scope, callId, tool, input);
    }
}
