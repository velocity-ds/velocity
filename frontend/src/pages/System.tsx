import { useMutation } from '@tanstack/react-query';
import {
  Activity,
  Bot,
  CalendarClock,
  Database,
  Globe2,
  KeyRound,
  MessageSquare,
  Plug,
  RefreshCw,
  Settings2,
  ShieldCheck,
} from 'lucide-react';
import { api } from '../api';
import { Badge, ErrorState, formatDate, JsonView, Loading, PageHeader } from '../components';
import { useSystem } from '../queries';
import type { Limits } from '../types';
import CodeExecutionStatus from './CodeExecutionStatus';

export default function System() {
  const system = useSystem();
  const mongo = useMutation({
    mutationFn: () =>
      api<{ configured: boolean; connectivity: string; checkedAt: string }>('/api/operator/status/mongo'),
  });
  if (!system.data)
    return system.isPending ? (
      <Loading />
    ) : (
      <ErrorState error={system.error} retry={() => system.refetch()} />
    );
  const s = system.data;
  const limits = s.executionLimits as Limits;
  return (
    <>
      <PageHeader
        title="Integrations & System"
        description="Effective configuration and observable health. Settings are managed on the server."
        actions={
          <button className="button" onClick={() => system.refetch()} disabled={system.isFetching}>
            <RefreshCw size={16} />
            Refresh status
          </button>
        }
      />
      {system.isError && <ErrorState error={system.error} />}
      <div className="notice">
        <ShieldCheck size={20} />
        <div>
          <strong>Local operator boundary</strong>
          <p>
            Same-origin loopback access. Identity is assigned by Velocity. Configuration shown here excludes
            credentials.
          </p>
        </div>
      </div>
      <div className="grid-2 integration-grid">
        <CodeExecutionStatus status={s.codeExecution} />
        <section className="panel">
          <div className="panel-heading">
            <h2>
              <KeyRound size={19} />
              Secrets
            </h2>
            <Badge status={s.secrets?.available ? 'enabled' : 'disabled'}>
              {s.secrets?.available ? 'Available' : s.secrets?.enabled ? 'Unavailable' : 'Disabled'}
            </Badge>
          </div>
          <dl className="key-values">
            <dt>Enabled</dt>
            <dd>{s.secrets?.enabled ? 'Yes' : 'No'}</dd>
            <dt>Encryption keys available</dt>
            <dd>{s.secrets?.available ? 'Yes' : 'No'}</dd>
          </dl>
          <p className="panel-body muted">
            Account credentials are managed in Secrets. Encryption keys, key IDs and saved account metadata
            are excluded from system status.
          </p>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>
              <Bot size={19} />
              Inference
            </h2>
            <Badge status={s.inference.available ? 'enabled' : 'disabled'}>
              {s.inference.available ? 'Available to application' : 'Disabled'}
            </Badge>
          </div>
          <dl className="key-values">
            <dt>Default provider</dt>
            <dd>{s.inference.provider || 'None selected'}</dd>
            <dt>Default model</dt>
            <dd>{s.inference.model || 'Not configured'}</dd>
            <dt>Remote connectivity</dt>
            <dd>{s.inference.connectivity?.replaceAll('_', ' ') || 'Not checked'}</dd>
          </dl>
          <div className="panel-body">
            <h3>Capabilities</h3>
            <div className="capability-list">
              {(s.inference.capabilities ?? []).map((capability) => (
                <Badge key={capability} status="enabled">
                  {capability.toLowerCase().replaceAll('_', ' ')}
                </Badge>
              ))}
            </div>
            <p className="muted">
              New tasks and the playground use the configured default provider. There is no automatic
              fallback.
            </p>
          </div>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>
              <MessageSquare size={19} />
              Slack
            </h2>
            <Badge status={s.slack.connection} />
          </div>
          <dl className="key-values">
            <dt>Enabled</dt>
            <dd>{s.slack.enabled ? 'Yes' : 'No'}</dd>
            <dt>Tokens configured</dt>
            <dd>{s.slack.configured ? 'Yes' : 'No'}</dd>
            <dt>Socket lifecycle</dt>
            <dd>{s.slack.lifecycleRunning ? 'Running' : 'Not running'}</dd>
            <dt>Connection</dt>
            <dd>{s.slack.connection}</dd>
          </dl>
          <p className="panel-body muted">
            Socket lifecycle is observable; live connection is not verified here. Response completion is
            separate from Slack delivery. Use the originating authorized Slack conversation for channel
            messages and task answers.
          </p>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>
              <Globe2 size={19} />
              Browser automation
            </h2>
            <Badge status={s.browser.availability} />
          </div>
          <dl className="key-values">
            <dt>Enabled</dt>
            <dd>{s.browser.enabled ? 'Yes' : 'No'}</dd>
            <dt>Tools registered</dt>
            <dd>{s.browser.registered ? 'Yes' : 'No'}</dd>
            <dt>Browser integration</dt>
            <dd>
              {s.browser.engine ?? 'Browser Use'}
              {s.browser.version && ` ${s.browser.version}`}
            </dd>
            <dt>Mode</dt>
            <dd>{s.browser.headless ? 'Headless' : 'Headed isolated browser'}</dd>
            <dt>Supported browser tools</dt>
            <dd>{s.browser.supportedTools?.length ?? 0}</dd>
          </dl>
          <p className="panel-body muted">
            Browser startup is checked on first use. Each task uses an isolated session. Browser results and
            actions appear in task activity.
          </p>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>
              <CalendarClock size={19} />
              Scheduling
            </h2>
            <Badge status={s.scheduling.enabled ? 'enabled' : 'disabled'} />
          </div>
          <dl className="key-values">
            <dt>Agent scheduling</dt>
            <dd>{s.scheduling.enabled ? 'Enabled' : 'Disabled'}</dd>
            <dt>JobRunr worker</dt>
            <dd>{s.scheduling.workerEnabled ? 'Enabled' : 'Disabled'}</dd>
            <dt>Worker readiness</dt>
            <dd>{String(s.scheduling.workerState ?? 'not_checked').replaceAll('_', ' ')}</dd>
            <dt>Maximum lateness</dt>
            <dd>{String(s.scheduling.maximumLateness)}</dd>
            <dt>Application timezone</dt>
            <dd>{s.timezone}</dd>
          </dl>
          <p className="panel-body muted">
            Agent scheduling and the JobRunr worker are independent switches. Pausing a schedule changes
            future occurrences; admitted tasks continue.
          </p>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>
              <Globe2 size={19} />
              Web search
            </h2>
            <Badge status={s.webSearch?.enabled ? 'enabled' : 'disabled'} />
          </div>
          <dl className="key-values">
            <dt>Tool</dt>
            <dd>{s.webSearch?.enabled ? 'web_search registered' : 'Not registered'}</dd>
            <dt>Provider</dt>
            <dd>{s.webSearch?.provider || 'None'}</dd>
            <dt>API key configured</dt>
            <dd>{s.webSearch?.configured ? 'Yes' : 'No'}</dd>
            <dt>Default results</dt>
            <dd>{s.webSearch?.maxResults ?? 0}</dd>
          </dl>
          <p className="panel-body muted">
            Search results are untrusted excerpts; tasks open a result with the browser or research tools
            before relying on it. Enable with WEB_SEARCH_ENABLED and a TAVILY_API_KEY.
          </p>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>
              <Plug size={19} />
              MCP servers
            </h2>
            <Badge status={s.mcp?.enabled ? 'enabled' : 'disabled'} />
          </div>
          <dl className="key-values">
            <dt>Registered tools</dt>
            <dd>{s.mcp?.registeredTools ?? 0}</dd>
            <dt>Writes confirmed</dt>
            <dd>{s.mcp?.confirmWrites ? 'Yes' : 'No'}</dd>
            <dt>Servers</dt>
            <dd>
              {s.mcp?.servers?.length
                ? s.mcp.servers
                    .map(
                      (server) =>
                        server.name +
                        ' (' +
                        server.transport +
                        '): ' +
                        server.state +
                        ', ' +
                        server.registeredTools +
                        ' tools',
                    )
                    .join('; ')
                : 'None configured'}
            </dd>
          </dl>
          <p className="panel-body muted">
            Tools of configured Model Context Protocol servers are registered as
            mcp_&lt;server&gt;_&lt;tool&gt;; everything they return is untrusted data. Enable with MCP_ENABLED
            and mcp.servers entries in .env.
          </p>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>
              <Database size={19} />
              MongoDB
            </h2>
            <Badge status={mongo.data?.connectivity ?? s.mongo.connectivity} />
          </div>
          <dl className="key-values">
            <dt>Client configured</dt>
            <dd>{s.mongo.configured ? 'Yes' : 'No'}</dd>
            <dt>Readiness</dt>
            <dd>{(mongo.data?.connectivity ?? s.mongo.connectivity).replaceAll('_', ' ')}</dd>
            <dt>Last explicit check</dt>
            <dd>{mongo.data ? formatDate(mongo.data.checkedAt) : 'Not checked'}</dd>
          </dl>
          <div className="panel-body stack">
            <button className="button align-start" disabled={mongo.isPending} onClick={() => mongo.mutate()}>
              <Activity size={16} />
              {mongo.isPending ? 'Checking MongoDB…' : 'Check MongoDB readiness'}
            </button>
            {mongo.isError && <ErrorState error={mongo.error} />}
            <p className="muted">
              This performs an actual database ping. HTTP liveness alone does not establish storage readiness.
            </p>
          </div>
        </section>
      </div>
      <section className="panel section-space">
        <div className="panel-heading">
          <h2>
            <Settings2 size={19} />
            Task deadline and output
          </h2>
          <Badge status={s.automaticResumption ? 'enabled' : 'disabled'}>
            Automatic resumption {s.automaticResumption ? 'enabled' : 'disabled'}
          </Badge>
        </div>
        <div className="limit-grid">
          {limits && (
            <>
              <div>
                <small>Active task time</small>
                <strong>{(limits.activeMillis / 60000).toLocaleString()} minutes</strong>
              </div>
              <div>
                <small>Tool result preview</small>
                <strong>{limits.outputPreviewChars.toLocaleString()} characters</strong>
              </div>
            </>
          )}
        </div>
        <div className="panel-body">
          <JsonView label="Safe effective status DTO" value={s} />
        </div>
      </section>
      <p className="muted fine-print">
        Status generated {formatDate(s.generatedAt)}. Unchecked connections remain unknown until a real
        operation checks them.
      </p>
    </>
  );
}
