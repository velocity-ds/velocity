import { TerminalSquare } from 'lucide-react';
import { codeBytes, type CodeExecutionStatus as Status } from '../codeExecution';
import { Badge } from '../components';

const labels = {
  disabled: 'Disabled',
  unconfigured: 'Unconfigured',
  available: 'Available',
  unavailable: 'Unavailable',
};

export default function CodeExecutionStatus({ status }: { status?: Status }) {
  return (
    <section className="panel">
      <div className="panel-heading">
        <h2>
          <TerminalSquare size={19} /> Python code execution
        </h2>
        <Badge status={status?.state === 'available' ? 'enabled' : 'disabled'}>
          {status ? (labels[status.state] ?? 'Unavailable') : 'Status unavailable'}
        </Badge>
      </div>
      {!status ? (
        <p className="panel-body muted">Code execution status was not supplied by the server.</p>
      ) : (
        <>
          {status.reason && (
            <p
              className={`panel-body ${status.state === 'unavailable' || status.state === 'unconfigured' ? 'notice warning' : 'muted'}`}
            >
              {status.reason.slice(0, 1024)}
            </p>
          )}
          <dl className="key-values">
            <dt>Enabled</dt>
            <dd>{status.enabled ? 'Yes' : 'No'}</dd>
            <dt>Runtime profile</dt>
            <dd>{status.runtimeProfile || 'Not configured'}</dd>
            {Object.entries(status.libraries).map(([name, version]) => (
              <div className="key-value-pair" key={name}>
                <dt>{name}</dt>
                <dd>{version}</dd>
              </div>
            ))}
            <dt>Execution deadline</dt>
            <dd>Remaining task time</dd>
            <dt>Concurrent sandboxes</dt>
            <dd>{status.limits.concurrency}</dd>
            <dt>Admission queue</dt>
            <dd>{status.limits.queueCapacity} runs</dd>
            <dt>CPU</dt>
            <dd>{status.limits.cpu}</dd>
            <dt>Memory</dt>
            <dd>{status.limits.memoryMiB.toLocaleString()} MiB</dd>
            <dt>Processes</dt>
            <dd>{status.limits.processes}</dd>
            <dt>Writable storage</dt>
            <dd>{status.limits.storageMiB.toLocaleString()} MiB</dd>
          </dl>
          <div className="panel-body stack">
            <details>
              <summary>Source, input and output limits</summary>
              <dl className="key-values">
                <dt>Python source</dt>
                <dd>{codeBytes(status.limits.sourceBytes)}</dd>
                <dt>Retained stdout / stderr</dt>
                <dd>
                  {codeBytes(status.limits.stdoutBytes)} / {codeBytes(status.limits.stderrBytes)}
                </dd>
                <dt>Input files</dt>
                <dd>
                  {status.limits.inputFiles} files · {codeBytes(status.limits.inputBytes)} total
                </dd>
                <dt>Output files</dt>
                <dd>
                  {status.limits.outputFiles} files · {codeBytes(status.limits.outputFileBytes)} each ·{' '}
                  {codeBytes(status.limits.outputTotalBytes)} total
                </dd>
              </dl>
            </details>
            <p className="muted">
              Python runs in a fresh Linux container with networking disabled. Queueing, staging and execution
              share the task deadline. See Code &amp; files in a task for run results and downloads.
            </p>
            {status.state === 'disabled' && (
              <p className="muted">
                Disabled code execution does not contact Docker or start background work.
              </p>
            )}
          </div>
        </>
      )}
    </section>
  );
}
