import { useQuery } from '@tanstack/react-query';
import { Download, RefreshCw, TerminalSquare } from 'lucide-react';
import { api } from '../api';
import { codeArtifactDownloadPath, codeBytes, codeRunsPath, type CodeRun } from '../codeExecution';
import { Badge, EmptyState, ErrorState, formatDate, Loading } from '../components';

const statuses: Record<string, { label: string; tone: string; explanation?: string }> = {
  admitted: { label: 'Queued', tone: 'waiting_for_user' },
  creating: { label: 'Preparing sandbox', tone: 'running' },
  starting: { label: 'Starting Python', tone: 'running' },
  running: { label: 'Running Python', tone: 'running' },
  collecting: { label: 'Saving outputs', tone: 'running' },
  succeeded: { label: 'Code completed', tone: 'enabled' },
  success: { label: 'Code completed', tone: 'enabled' },
  failed: {
    label: 'Code failed',
    tone: 'failed',
    explanation: 'Inspect the recorded exit code and error output.',
  },
  nonzero_exit: { label: 'Code failed', tone: 'failed', explanation: 'Python exited with a nonzero status.' },
  timed_out: {
    label: 'Time limit reached',
    tone: 'failed',
    explanation: 'The run exceeded its execution deadline.',
  },
  timeout: {
    label: 'Time limit reached',
    tone: 'failed',
    explanation: 'The run exceeded its execution deadline.',
  },
  cancelled: { label: 'Cancelled', tone: 'cancelled' },
  resource_limit: { label: 'Resource limit reached', tone: 'failed' },
  resource_limited: { label: 'Resource limit reached', tone: 'failed' },
  unavailable: { label: 'Runner unavailable', tone: 'disabled' },
  runner_unavailable: { label: 'Runner unavailable', tone: 'disabled' },
  unconfigured: { label: 'Runner unconfigured', tone: 'disabled' },
  disabled: { label: 'Code execution disabled', tone: 'disabled' },
  missing_outputs: {
    label: 'Requested outputs missing',
    tone: 'partial',
    explanation: 'Code exit alone does not establish that all requested files were saved.',
  },
  artifact_publication_failed: {
    label: 'Output publication incomplete',
    tone: 'partial',
    explanation: 'Some requested files could not be saved. Only committed files are listed below.',
  },
  superseded: { label: 'Superseded goal', tone: 'partial' },
  interrupted: { label: 'Run interrupted', tone: 'failed' },
  interrupted_before_dispatch: { label: 'Interrupted before launch', tone: 'failed' },
  budget_exhausted: { label: 'Task budget exhausted', tone: 'failed' },
  queue_deadline: { label: 'Queue deadline reached', tone: 'failed' },
  queue_full: { label: 'Sandbox queue full', tone: 'blocked' },
  cleanup_required: { label: 'Earlier sandbox cleanup required', tone: 'blocked' },
  uncertain: {
    label: 'Outcome uncertain',
    tone: 'blocked',
    explanation:
      'Velocity cannot confirm the outcome. Recovery must reconcile this run before its worker is settled.',
  },
  cleanup_pending: { label: 'Cleanup pending', tone: 'blocked' },
};

function Output({ title, text, truncated }: { title: string; text: string; truncated: boolean }) {
  // An independent display bound protects the console even if a stored receipt is oversized.
  const limit = 65536;
  return (
    <details className="code-output" open={text.length > 0}>
      <summary>
        {title}
        {truncated || text.length > limit ? ' · truncated' : ''}
      </summary>
      {text ? (
        <pre className="tool-preview">{text.slice(0, limit)}</pre>
      ) : (
        <p className="muted">No retained output.</p>
      )}
      {(truncated || text.length > limit) && (
        <p className="muted fine-print">Only the bounded retained preview is shown.</p>
      )}
    </details>
  );
}

export default function TaskCodeRuns({ taskId }: { taskId: string }) {
  const runs = useQuery({
    queryKey: ['task-code-runs', taskId],
    queryFn: ({ signal }) => api<CodeRun[]>(codeRunsPath(taskId), { signal }),
    refetchInterval: 3000,
    gcTime: 0,
  });
  return (
    <div className="stack task-code-runs">
      <div className="panel-heading">
        <h3>
          <TerminalSquare size={17} /> Code runs and files
        </h3>
        <button className="button ghost" onClick={() => runs.refetch()} disabled={runs.isFetching}>
          <RefreshCw size={15} /> Refresh code runs
        </button>
      </div>
      <p className="muted">
        Each run uses a fresh Python sandbox. Use the task’s Cancel task control to stop active work. Output
        and files are untrusted content.
      </p>
      {runs.isError && <ErrorState error={runs.error} />}
      {runs.isError ? null : !runs.data ? (
        runs.isPending && <Loading label="Loading saved code runs…" />
      ) : runs.data.length === 0 ? (
        <EmptyState
          title="No code runs"
          description="Python execution and generated files will appear here when this task uses code_run."
        />
      ) : (
        runs.data.map((run) => {
          const status = statuses[run.status.toLowerCase()] ?? {
            label: run.status.replaceAll('_', ' '),
            tone: 'unknown',
          };
          return (
            <article key={run.runId} className="tool-card code-run">
              <div className="tool-heading">
                <TerminalSquare size={17} />
                <strong>Python run</strong>
                <Badge status={status.tone}>{status.label}</Badge>
                {!run.currentGeneration && <Badge status="partial">Superseded goal</Badge>}
              </div>
              {status.explanation && <p className="notice warning">{status.explanation}</p>}
              {run.cleanupPending && (
                <p className="notice warning">
                  Sandbox cleanup remains pending. This record is retained for recovery.
                </p>
              )}
              {!run.currentGeneration && (
                <p className="muted">
                  These results belong to an earlier goal and are not the current task result.
                </p>
              )}
              <dl className="key-values">
                <dt>Exit code</dt>
                <dd>{run.exitCode ?? 'Not confirmed'}</dd>
                <dt>Duration</dt>
                <dd>
                  {run.durationMillis == null ? 'Not available' : `${run.durationMillis.toLocaleString()} ms`}
                </dd>
                <dt>Goal generation</dt>
                <dd>{run.goalGeneration}</dd>
                {run.failureCode && (
                  <>
                    <dt>Failure code</dt>
                    <dd>{run.failureCode}</dd>
                  </>
                )}
              </dl>
              <Output title="Standard output" text={run.stdout} truncated={run.stdoutTruncated} />
              <Output title="Standard error" text={run.stderr} truncated={run.stderrTruncated} />
              <div className="stack code-artifacts">
                <h4>Saved output files ({run.outputArtifacts.length})</h4>
                <p className="muted fine-print">
                  Only successfully committed files are listed. A zero exit code does not prove all requested
                  deliverables were saved.
                </p>
                {run.outputArtifacts.map((artifact) => (
                  <div key={artifact.artifactId} className="code-artifact">
                    <div>
                      <strong>{artifact.filename}</strong>
                      <p className="muted fine-print">
                        {codeBytes(artifact.byteSize)} · {artifact.mediaType}
                      </p>
                      <p className="muted fine-print">Saved {formatDate(artifact.createdAt)}</p>
                      <details>
                        <summary>File metadata</summary>
                        <dl className="key-values">
                          <dt>SHA-256</dt>
                          <dd className="mono">{artifact.checksum}</dd>
                          <dt>Artifact ID</dt>
                          <dd className="mono">{artifact.artifactId}</dd>
                        </dl>
                      </details>
                    </div>
                    <a
                      className="button"
                      download
                      href={codeArtifactDownloadPath(taskId, artifact.artifactId)}
                    >
                      <Download size={15} /> Download {artifact.filename}
                    </a>
                  </div>
                ))}
              </div>
              <details className="fine-print code-run-identity">
                <summary>Run identity</summary>
                <p className="mono">{run.runId}</p>
              </details>
            </article>
          );
        })
      )}
    </div>
  );
}
