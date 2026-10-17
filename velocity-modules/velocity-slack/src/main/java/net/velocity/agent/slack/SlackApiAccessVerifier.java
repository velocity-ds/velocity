package net.velocity.agent.slack;

import java.util.Objects;

import com.slack.api.methods.MethodsClient;

import net.velocity.agent.state.TrustedScope;

/** Bounded membership inspection; missing scopes, inaccessible history, or incomplete evidence deny access. */
public final class SlackApiAccessVerifier implements SlackAccessVerifier {

    private final MethodsClient client;

    public SlackApiAccessVerifier(MethodsClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public void verify(TrustedScope scope) {
        if (!"slack".equals(scope.origin())) {
            throw new SecurityException("Not a Slack scope");
        }
        try {
            var auth = client.authTest(request -> request);
            if (!auth.isOk() || !scope.workspaceId().equals(auth.getTeamId())
                    || !scope.botId().equals(auth.getUserId())) {
                throw new SecurityException("Slack app identity mismatch");
            }
            var user = client.usersInfo(request -> request.user(scope.requesterId()));
            if (!user.isOk() || user.getUser() == null || user.getUser().isDeleted()
                    || user.getUser().isBot()) {
                throw new SecurityException("Slack requester is unavailable");
            }
            var info = client.conversationsInfo(request -> request.channel(scope.channelId()));
            if (!info.isOk() || info.getChannel() == null || info.getChannel().isArchived()) {
                throw new SecurityException("Slack channel is unavailable");
            }
            boolean requester = false;
            boolean bot = false;
            String cursor = null;
            for (int page = 0; page < 10; page++) {
                String current = cursor;
                var members = client.conversationsMembers(
                        request -> request.channel(scope.channelId()).limit(200).cursor(current));
                if (!members.isOk() || members.getMembers() == null) {
                    throw new SecurityException("Cannot verify Slack membership");
                }
                requester |= members.getMembers().contains(scope.requesterId());
                bot |= members.getMembers().contains(scope.botId());
                if (requester && bot) {
                    return;
                }
                cursor = members.getResponseMetadata() == null
                        ? null
                        : members.getResponseMetadata().getNextCursor();
                if (cursor == null || cursor.isBlank()) {
                    break;
                }
            }
            throw new SecurityException("Current requester and app channel access were not verified");
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Cannot verify current Slack access");
        }
    }
}
