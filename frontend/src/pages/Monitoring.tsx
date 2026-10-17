import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { Plus, RefreshCw } from 'lucide-react';
import { api, ApiError, json } from '../api';
import {
  applicationZone,
  Badge,
  EmptyState,
  ErrorState,
  Field,
  formatDate,
  JsonView,
  Loading,
  Modal,
  PageHeader,
  SafeLink,
  SafeMarkdown,
} from '../components';

type Timing = { cron: string; timezone: string };
export type MonitoringJob = {
  id: string;
  revision: number;
  name: string;
  original_request: string;
  objective: string;
  criteria: string;
  exclusions: string;
  sources: string;
  timing: Timing;
  status: 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'NEEDS_INPUT' | 'CLOSED';
  health: string;
  notification_policy: 'changes_only' | 'digest';
  digest_timing?: Timing;
  notification_instructions: string;
  initial_summary: boolean;
  end_at?: string;
  completion_condition?: string;
  completion_rule?: unknown;
  last_success?: string;
  next_due?: string;
  working_summary?: string;
  questions?: string[];
  limitations?: string[];
  destination?: { transport: 'console' | 'slack'; channel_id?: string; thread_ts?: string };
  registration?: string;
  registration_failure?: string;
  schedule_id?: string;
};
type Page<T> = { items: T[]; has_more: boolean; next_offset?: number };
type JobPage = Omit<Page<MonitoringJob>, 'items'> & { jobs: MonitoringJob[] };
type HistoryEntry = Record<string, unknown>;
type FormState = {
  name: string;
  objective: string;
  criteria: string;
  exclusions: string;
  sources: string;
  cron: string;
  timezone: string;
  notification_policy: 'changes_only' | 'digest';
  digest_cron: string;
  digest_timezone: string;
  notification_instructions: string;
  initial_summary: boolean;
  end_at: string;
  completion_condition: string;
};
const defaults: FormState = {
  name: '',
  objective: '',
  criteria: '',
  exclusions: '',
  sources: '',
  cron: '0 0 9 * * *',
  timezone: 'UTC',
  notification_policy: 'changes_only',
  digest_cron: '0 0 17 * * *',
  digest_timezone: 'UTC',
  notification_instructions: '',
  initial_summary: false,
  end_at: '',
  completion_condition: '',
};

export function monitoringBody(form: FormState, job?: MonitoringJob): Record<string, unknown> {
  const timing = (cron: string, timezone: string) => {
    if (cron.trim().split(/\s+/).length !== 6) throw new Error('Use a six-field Spring cron expression.');
    if (!timezone.trim()) throw new Error('Enter an IANA timezone.');
    return { cron: cron.trim(), timezone: timezone.trim() };
  };
  if (form.end_at && !/T.*(?:Z|[+-]\d\d:\d\d)$/.test(form.end_at))
    throw new Error('Use an end timestamp with an explicit timezone offset or Z.');
  return {
    ...(job ? { expected_revision: job.revision } : { original_request: form.objective }),
    name: form.name,
    objective: form.objective,
    criteria: form.criteria,
    exclusions: form.exclusions,
    sources: form.sources,
    timing: timing(form.cron, form.timezone),
    notification_policy: form.notification_policy,
    ...(form.notification_policy === 'digest'
      ? { digest_timing: timing(form.digest_cron, form.digest_timezone) }
      : {}),
    notification_instructions: form.notification_instructions,
    initial_summary: form.initial_summary,
    end_at: form.end_at,
    completion_condition: form.completion_condition,
  };
}

export default function Monitoring() {
  const [params, setParams] = useSearchParams();
  const selected = params.get('id');
  const [offset, setOffset] = useState(0);
  const [editor, setEditor] = useState<MonitoringJob | 'new' | null>(
    params.get('new') === '1' ? 'new' : null,
  );
  const [saved, setSaved] = useState<MonitoringJob | null>(null);
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ['monitoring', offset],
    queryFn: () => api<JobPage>(`/api/operator/monitoring?offset=${offset}&limit=20`),
    refetchInterval: 15000,
  });
  return (
    <div className="stack">
      <PageHeader
        title="Monitoring"
        description="Describe an ongoing objective. Each check preserves findings and selects updates against your saved criteria."
        actions={
          <button className="button primary" onClick={() => setEditor('new')}>
            <Plus size={16} />
            Create monitoring job
          </button>
        }
      />
      {saved && (
        <div className="notice success" role="status">
          <strong>Monitoring job saved · {saved.id}</strong>
          <p>{saved.objective}</p>
          <p>Criteria: {saved.criteria || 'Use the saved objective.'}</p>
          <p>
            Checks: {saved.timing.cron} · {saved.timing.timezone}.{' '}
            {saved.notification_policy === 'digest'
              ? `Digest: ${saved.digest_timing?.cron} · ${saved.digest_timing?.timezone}.`
              : 'Changes-only updates.'}
          </p>
          <p>
            First check establishes a baseline
            {saved.initial_summary ? ' and may publish an initial summary' : ' without an initial summary'}.
            Next due: {formatDate(saved.next_due)}.
          </p>
          <p>
            {saved.destination?.transport === 'slack'
              ? 'Updates use the saved authorized Slack destination.'
              : 'Updates are available in this console; no external delivery destination.'}
          </p>
          {saved.registration !== 'registered' && (
            <p>
              Schedule registration: {saved.registration || 'pending'}. Inspect the saved job before creating
              another copy.
            </p>
          )}
          {saved.limitations?.map((item, index) => (
            <p key={index}>{item}</p>
          ))}
        </div>
      )}
      <section className="panel stack">
        <div className="toolbar">
          <h2>Saved objectives</h2>
          <button className="button ghost" onClick={() => void query.refetch()} disabled={query.isFetching}>
            <RefreshCw size={15} />
            Refresh
          </button>
        </div>
        {query.isPending ? (
          <Loading label="Loading monitoring jobs" />
        ) : query.isError ? (
          <ErrorState error={query.error} retry={() => void query.refetch()} />
        ) : !query.data.jobs.length ? (
          <EmptyState
            title="No monitoring jobs on this page"
            description="Describe what matters, how often to check, and when you want an update."
          />
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Objective</th>
                  <th>Lifecycle and health</th>
                  <th>Last successful check</th>
                  <th>Next due</th>
                </tr>
              </thead>
              <tbody>
                {query.data.jobs.map((job) => (
                  <tr key={job.id}>
                    <td>
                      <button className="text-button" onClick={() => setParams({ id: job.id })}>
                        {job.name}
                      </button>
                      <div className="muted">{job.objective}</div>
                    </td>
                    <td>
                      <Badge status={job.status}>{job.status.replaceAll('_', ' ')}</Badge>
                      <div>{job.health}</div>
                    </td>
                    <td>{formatDate(job.last_success)}</td>
                    <td>{formatDate(job.next_due)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pagination
          offset={offset}
          more={query.data?.has_more ?? false}
          busy={query.isFetching}
          setOffset={setOffset}
        />
      </section>
      <div className="notice">
        <p>
          Default: check daily at 09:00 ({applicationZone() ?? 'application time zone'}), notify on relevant
          changes, and establish a baseline without an initial summary. Source access depends on configured
          tools and credentials.
        </p>
      </div>
      {selected && (
        <Modal title="Monitoring details" onClose={() => setParams({})}>
          <MonitoringDetail
            id={selected}
            onEdit={(job) => {
              setParams({});
              setEditor(job);
            }}
          />
        </Modal>
      )}
      {editor && (
        <Modal
          title={editor === 'new' ? 'Create monitoring job' : 'Edit monitoring job'}
          onClose={() => setEditor(null)}
        >
          <MonitoringEditor
            job={editor === 'new' ? undefined : editor}
            onSaved={(job) => {
              setSaved(job);
              setEditor(null);
              void client.invalidateQueries({ queryKey: ['monitoring'] });
              void client.invalidateQueries({ queryKey: ['monitoring-job', job.id] });
            }}
          />
        </Modal>
      )}
    </div>
  );
}

function MonitoringDetail({ id, onEdit }: { id: string; onEdit: (job: MonitoringJob) => void }) {
  const client = useQueryClient();
  const [confirmClose, setConfirmClose] = useState(false);
  const query = useQuery({
    queryKey: ['monitoring-job', id],
    queryFn: () => api<MonitoringJob>(`/api/operator/monitoring/${encodeURIComponent(id)}`),
    refetchInterval: 15000,
  });
  const mutation = useMutation({
    mutationFn: (action: string) =>
      api<MonitoringJob>(
        `/api/operator/monitoring/${encodeURIComponent(id)}/${action}`,
        json('POST', { expected_revision: query.data!.revision }),
      ),
    retry: false,
    onSuccess: () => {
      setConfirmClose(false);
      void client.invalidateQueries({ queryKey: ['monitoring'] });
      void client.invalidateQueries({ queryKey: ['monitoring-job', id] });
      void client.invalidateQueries({ queryKey: ['monitoring-history', id] });
    },
  });
  if (query.isPending) return <Loading />;
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />;
  const job = query.data;
  const conflict = mutation.error instanceof ApiError && mutation.error.status === 409;
  const uncertain =
    mutation.error instanceof ApiError && (mutation.error.status === 0 || mutation.error.status >= 500);
  return (
    <div className="stack">
      <div className="toolbar">
        <Badge status={job.status} />
        <span>
          Health: {job.health} · Revision {job.revision}
        </span>
      </div>
      <h3>{job.name}</h3>
      <SafeMarkdown text={job.objective} />
      <dl className="detail-grid">
        <dt>Job ID</dt>
        <dd>{job.id}</dd>
        <dt>Check cadence</dt>
        <dd>
          {job.timing.cron} · {job.timing.timezone}
        </dd>
        <dt>Notification policy</dt>
        <dd>
          {job.notification_policy === 'digest'
            ? `Digest: ${job.digest_timing?.cron} · ${job.digest_timing?.timezone}`
            : 'Relevant changes only'}
        </dd>
        <dt>Destination</dt>
        <dd>
          {job.destination?.transport === 'slack'
            ? 'Authorized Slack destination (see saved definition)'
            : 'Available in this console · no Slack delivery'}
        </dd>
        <dt>Last successful check</dt>
        <dd>{formatDate(job.last_success)}</dd>
        <dt>Next due</dt>
        <dd>{formatDate(job.next_due)}</dd>
        <dt>Criteria</dt>
        <dd>{job.criteria || 'Use objective'}</dd>
        <dt>Exclusions</dt>
        <dd>{job.exclusions || 'None'}</dd>
        <dt>Source preferences</dt>
        <dd>{job.sources || 'Discover with authorized tools'}</dd>
        <dt>Notification instructions</dt>
        <dd>{job.notification_instructions || 'Follow saved policy'}</dd>
        <dt>End date</dt>
        <dd>
          {job.end_at ? formatDate(job.end_at) : 'Until closed or an explicit completion rule is verified'}
        </dd>
        <dt>Completion condition</dt>
        <dd>{job.completion_condition || 'None'}</dd>
      </dl>
      {!!job.questions?.length && (
        <div className="notice warning">
          <strong>Information needed</strong>
          {job.questions.map((question, index) => (
            <p key={index}>{question}</p>
          ))}
          <p>Edit the objective or criteria with the answer, then resume monitoring.</p>
        </div>
      )}
      {!!job.limitations?.length && (
        <div className="notice warning">
          <strong>Known limitations</strong>
          {job.limitations.map((item, index) => (
            <p key={index}>{item}</p>
          ))}
        </div>
      )}
      {job.registration && job.registration !== 'registered' && (
        <div className="notice warning">
          Schedule registration: {job.registration}. {job.registration_failure}
        </div>
      )}
      {job.working_summary && (
        <section>
          <h3>Working summary</h3>
          <SafeMarkdown text={job.working_summary} />
        </section>
      )}
      {mutation.isError && (
        <ErrorState
          error={mutation.error}
          retry={
            conflict || uncertain
              ? () => {
                  void query.refetch().then((result) => {
                    if (result.isSuccess) mutation.reset();
                  });
                }
              : undefined
          }
        />
      )}
      {job.status !== 'CLOSED' && (
        <div className="toolbar">
          <button
            className="button"
            disabled={mutation.isPending || conflict || uncertain}
            onClick={() => onEdit(job)}
          >
            Edit definition
          </button>
          {job.status === 'ACTIVE' ? (
            <>
              <button
                className="button"
                disabled={mutation.isPending || conflict || uncertain}
                onClick={() => mutation.mutate('pause')}
              >
                Pause
              </button>
              <button
                className="button"
                disabled={mutation.isPending || conflict || uncertain}
                onClick={() => mutation.mutate('run-now')}
              >
                Run now
              </button>
            </>
          ) : (
            <button
              className="button"
              disabled={mutation.isPending || conflict || uncertain}
              onClick={() => mutation.mutate('resume')}
            >
              Resume
            </button>
          )}
          <button
            className="button danger"
            disabled={mutation.isPending || conflict || uncertain}
            onClick={() => setConfirmClose(true)}
          >
            Close monitoring
          </button>
        </div>
      )}
      {confirmClose && job.status !== 'CLOSED' && (
        <div className="notice warning">
          <p>
            Close this ongoing objective permanently? Future checks and fresh notification dispatch stop.
            In-flight messages may still settle.
          </p>
          <button
            className="button danger"
            disabled={mutation.isPending || conflict || uncertain}
            onClick={() => mutation.mutate('close')}
          >
            Confirm close
          </button>
        </div>
      )}
      <MonitoringHistory id={id} />
      <JsonView value={job} label="Saved definition and destination" />
    </div>
  );
}

function MonitoringHistory({ id }: { id: string }) {
  const [kind, setKind] = useState('updates');
  const [offset, setOffset] = useState(0);
  const query = useQuery({
    queryKey: ['monitoring-history', id, kind, offset],
    queryFn: () =>
      api<Page<HistoryEntry>>(
        `/api/operator/monitoring/${encodeURIComponent(id)}/${kind}?offset=${offset}&limit=20`,
      ),
    refetchInterval: 15000,
  });
  return (
    <section className="stack">
      <div className="tabs" aria-label="Monitoring history">
        {['updates', 'observations', 'runs'].map((value) => (
          <button
            key={value}
            className={kind === value ? 'active' : ''}
            aria-pressed={kind === value}
            onClick={() => {
              setKind(value);
              setOffset(0);
            }}
          >
            {value[0].toUpperCase() + value.slice(1)}
          </button>
        ))}
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : !query.data.items.length ? (
        <EmptyState
          title={`No ${kind} on this page`}
          description={
            kind === 'updates'
              ? 'Successful no-change checks and baseline runs may intentionally produce no update.'
              : undefined
          }
        />
      ) : (
        query.data.items.map((item, index) => (
          <HistoryCard key={String(item.id ?? index)} item={item} kind={kind} />
        ))
      )}
      <Pagination
        offset={offset}
        more={query.data?.has_more ?? false}
        busy={query.isFetching}
        setOffset={setOffset}
      />
    </section>
  );
}

function HistoryCard({ item, kind }: { item: HistoryEntry; kind: string }) {
  const value = (...keys: string[]) => {
    for (const key of keys) if (typeof item[key] === 'string') return item[key] as string;
    return '';
  };
  const status = value('delivery_status', 'outcome', 'state', 'status');
  const evidence = item.evidence;
  return (
    <article className="panel stack">
      <div className="toolbar">
        <strong>{value('id', 'item_id') || kind}</strong>
        {status && <Badge status={status} />}
      </div>
      <SafeMarkdown text={value('summary', 'content', 'explanation', 'relevance')} />
      {value('observed_at', 'started_at', 'created_at', 'due_at') && (
        <span className="muted">
          {formatDate(value('observed_at', 'started_at', 'created_at', 'due_at'))}
        </span>
      )}
      {kind === 'updates' && (
        <p>
          {['available', 'in_app', 'available_in_app'].includes(status)
            ? 'Available in the console. This is not a Slack delivery receipt.'
            : status === 'uncertain'
              ? 'External delivery is uncertain. Inspect the destination before any replacement; this update is not automatically resent.'
              : status === 'delivered'
                ? 'External delivery acknowledged; see saved receipt.'
                : `Delivery: ${status || 'See saved update record'}`}
        </p>
      )}
      {value('url', 'reference') && <SafeLink href={value('url', 'reference')}>Source evidence</SafeLink>}
      {Array.isArray(evidence) &&
        evidence.map((entry, index) => {
          const reference =
            typeof entry === 'string'
              ? entry
              : typeof entry === 'object' && entry !== null
                ? String(
                    (entry as Record<string, unknown>).reference ??
                      (entry as Record<string, unknown>).url ??
                      '',
                  )
                : '';
          return (
            <SafeLink key={index} href={reference}>
              {reference || 'Evidence reference'}
            </SafeLink>
          );
        })}
      {value('failure') && <p className="notice warning">{value('failure')}</p>}
      <JsonView value={item} label="Evidence, coverage and saved record" />
    </article>
  );
}

function Pagination({
  offset,
  more,
  busy,
  setOffset,
}: {
  offset: number;
  more: boolean;
  busy: boolean;
  setOffset: (offset: number) => void;
}) {
  return (
    <div className="toolbar">
      <button
        className="button ghost"
        disabled={offset === 0 || busy}
        onClick={() => setOffset(Math.max(0, offset - 20))}
      >
        Previous page
      </button>
      <span className="muted">Page {Math.floor(offset / 20) + 1}</span>
      <button className="button ghost" disabled={!more || busy} onClick={() => setOffset(offset + 20)}>
        Next page
      </button>
    </div>
  );
}

function MonitoringEditor({ job, onSaved }: { job?: MonitoringJob; onSaved: (job: MonitoringJob) => void }) {
  const zone = applicationZone() ?? defaults.timezone;
  const [form, setForm] = useState<FormState>(
    job
      ? {
          name: job.name,
          objective: job.objective,
          criteria: job.criteria,
          exclusions: job.exclusions,
          sources: job.sources,
          cron: job.timing.cron,
          timezone: job.timing.timezone,
          notification_policy: job.notification_policy,
          digest_cron: job.digest_timing?.cron ?? defaults.digest_cron,
          digest_timezone: job.digest_timing?.timezone ?? job.timing.timezone,
          notification_instructions: job.notification_instructions,
          initial_summary: job.initial_summary,
          end_at: job.end_at ?? '',
          completion_condition: job.completion_condition ?? '',
        }
      : { ...defaults, timezone: zone, digest_timezone: zone },
  );
  const [validation, setValidation] = useState<Error | null>(null);
  const mutation = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      api<MonitoringJob>(
        job ? `/api/operator/monitoring/${encodeURIComponent(job.id)}` : '/api/operator/monitoring',
        json(job ? 'PATCH' : 'POST', body),
      ),
    retry: false,
    onSuccess: onSaved,
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    setValidation(null);
    try {
      mutation.mutate(monitoringBody(form, job));
    } catch (error) {
      setValidation(error as Error);
    }
  }
  const set = <K extends keyof FormState>(key: K, value: FormState[K]) =>
    setForm((current) => ({ ...current, [key]: value }));
  const conflict = mutation.error instanceof ApiError && mutation.error.status === 409;
  const uncertain =
    mutation.error instanceof ApiError && (mutation.error.status === 0 || mutation.error.status >= 500);
  return (
    <form className="stack" onSubmit={submit}>
      <Field label="Name">
        <input
          required
          maxLength={200}
          value={form.name}
          onChange={(event) => set('name', event.target.value)}
        />
      </Field>
      <Field
        label="What should Velocity monitor?"
        hint="Describe the ongoing objective and what should be checked. Sources can be discovered using available tools."
      >
        <textarea
          required
          maxLength={16000}
          rows={5}
          value={form.objective}
          onChange={(event) => set('objective', event.target.value)}
        />
      </Field>
      <Field label="Relevance criteria">
        <textarea
          maxLength={16000}
          rows={3}
          value={form.criteria}
          onChange={(event) => set('criteria', event.target.value)}
        />
      </Field>
      <Field label="Exclusions">
        <textarea
          maxLength={16000}
          rows={2}
          value={form.exclusions}
          onChange={(event) => set('exclusions', event.target.value)}
        />
      </Field>
      <Field
        label="Source preferences (optional)"
        hint="Names, references, or URLs guide research; they do not grant access."
      >
        <textarea
          maxLength={16000}
          rows={2}
          value={form.sources}
          onChange={(event) => set('sources', event.target.value)}
        />
      </Field>
      <div className="form-grid">
        <Field
          label="Check cadence"
          hint="Six fields: second minute hour day-of-month month day-of-week. Default: daily at 09:00."
        >
          <input required value={form.cron} onChange={(event) => set('cron', event.target.value)} />
        </Field>
        <Field label="Check timezone">
          <input required value={form.timezone} onChange={(event) => set('timezone', event.target.value)} />
        </Field>
      </div>
      <Field label="Notification policy">
        <select
          value={form.notification_policy}
          onChange={(event) =>
            set('notification_policy', event.target.value as FormState['notification_policy'])
          }
        >
          <option value="changes_only">Relevant changes only</option>
          <option value="digest">Periodic digest</option>
        </select>
      </Field>
      {form.notification_policy === 'digest' && (
        <div className="form-grid">
          <Field
            label="Digest cadence"
            hint="Independent of check cadence. Includes saved relevant updates when due."
          >
            <input
              required
              value={form.digest_cron}
              onChange={(event) => set('digest_cron', event.target.value)}
            />
          </Field>
          <Field label="Digest timezone">
            <input
              required
              value={form.digest_timezone}
              onChange={(event) => set('digest_timezone', event.target.value)}
            />
          </Field>
        </div>
      )}
      <Field label="When should an update matter?">
        <textarea
          maxLength={16000}
          rows={3}
          value={form.notification_instructions}
          onChange={(event) => set('notification_instructions', event.target.value)}
        />
      </Field>
      <label className="checkbox">
        <input
          type="checkbox"
          checked={form.initial_summary}
          onChange={(event) => set('initial_summary', event.target.checked)}
        />
        Include an initial summary when establishing the baseline
      </label>
      <Field
        label="End date (optional)"
        hint="ISO timestamp with offset, for example 2027-01-31T17:00:00-05:00. Empty means no end date."
      >
        <input value={form.end_at} onChange={(event) => set('end_at', event.target.value)} />
      </Field>
      <Field
        label="Completion condition (optional)"
        hint="Text conditions require owner verification unless a structured completion rule is configured."
      >
        <textarea
          maxLength={16000}
          value={form.completion_condition}
          onChange={(event) => set('completion_condition', event.target.value)}
        />
      </Field>
      <div className="notice">
        The first due check establishes a baseline. Updates are saved in the console. An external Slack
        destination must be authorized from a Slack conversation.
      </div>
      {(validation || mutation.isError) && <ErrorState error={validation || mutation.error} />}
      {conflict && (
        <p>
          Close this editor and reopen the current definition before applying your changes. Nothing was
          automatically retried.
        </p>
      )}
      {uncertain && (
        <p>
          The save outcome is uncertain. Close this editor and inspect the saved jobs before submitting
          another change.
        </p>
      )}
      <button className="button primary" type="submit" disabled={mutation.isPending || conflict || uncertain}>
        {mutation.isPending ? 'Saving…' : job ? 'Save monitoring changes' : 'Create monitoring job'}
      </button>
    </form>
  );
}
