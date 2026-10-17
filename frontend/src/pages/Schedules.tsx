import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { CalendarClock, Pause, Play, Plus, RefreshCw, X } from 'lucide-react';
import { api, ApiError, json } from '../api';
import {
  Badge,
  EmptyState,
  ErrorState,
  Field,
  JsonView,
  Loading,
  Modal,
  PageHeader,
  applicationZone,
  formatDate,
} from '../components';

type Occurrence = {
  id: string;
  revision: number;
  due_at: string;
  outcome: string;
  execution_id?: string;
  execution_status?: string;
  receipt?: unknown;
  failure?: string;
  completed_at?: string;
};
export type Schedule = {
  id: string;
  revision: number;
  name: string;
  status: 'active' | 'paused' | 'cancelled' | 'completed';
  action_type: 'execution' | 'message';
  timing: { mode: 'at' | 'cron'; timezone: string; at?: string; cron?: string };
  timezone: string;
  next_occurrence: string;
  next_occurrence_local?: string;
  registration: 'registered' | 'pending';
  registration_failure?: string;
  missed_run_policy: string;
  overlap_policy: string;
  prompt?: string;
  message?: string;
  destination?: unknown;
  occurrences?: Occurrence[];
  monitoring_job_id?: string;
  monitoring_revision?: number;
};
type ScheduleStatus = {
  scheduling: { enabled: boolean; workerEnabled: boolean; workerState: string };
  inference: { available: boolean };
};
type FormState = {
  name: string;
  prompt: string;
  mode: 'at' | 'delay' | 'cron';
  at: string;
  delay: string;
  cron: string;
  timezone: string;
  retime: boolean;
};
const newForm: FormState = {
  name: '',
  prompt: '',
  mode: 'delay',
  at: '',
  delay: '3600',
  cron: '0 0 9 * * MON-FRI',
  timezone: 'UTC',
  retime: true,
};
export function scheduleTiming(
  form: Pick<FormState, 'mode' | 'at' | 'delay' | 'cron' | 'timezone'>,
): Record<string, string | number> {
  if (!form.timezone.trim()) throw new Error('Enter an IANA timezone.');
  if (form.mode === 'delay') {
    const delay = Number(form.delay);
    if (!Number.isSafeInteger(delay) || delay <= 0)
      throw new Error('Delay must be a positive whole number of seconds.');
    return { delay_seconds: delay, timezone: form.timezone };
  }
  if (form.mode === 'cron') {
    if (form.cron.trim().split(/\s+/).length !== 6)
      throw new Error('Use Spring cron with six fields: second minute hour day-of-month month day-of-week.');
    return { cron: form.cron.trim(), timezone: form.timezone };
  }
  if (!form.at.trim()) throw new Error('Enter an ISO timestamp for the occurrence.');
  return { at: form.at.trim(), timezone: form.timezone };
}
export function timingSummary(schedule: Schedule): string {
  if (schedule.timing.mode === 'cron') {
    const cron = schedule.timing.cron ?? '';
    const match = /^0 (\d{1,2}) (\d{1,2}) \* \* (\*|MON-FRI|1-5)$/.exec(cron);
    if (match)
      return `${match[3] === '*' ? 'Daily' : 'Weekdays'} at ${match[2].padStart(2, '0')}:${match[1].padStart(2, '0')} · ${schedule.timezone}`;
    return `Recurring · ${cron} · ${schedule.timezone}`;
  }
  return `Once · ${schedule.timing.at ? new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short', timeZone: schedule.timezone }).format(new Date(schedule.timing.at)) : 'Time unavailable'} · ${schedule.timezone}`;
}

export default function Schedules() {
  const [params, setParams] = useSearchParams();
  const filter = params.get('status') ?? 'all';
  const selected = params.get('id');
  const [editor, setEditor] = useState<Schedule | 'new' | null>(() =>
    params.get('new') === '1' ? 'new' : null,
  );
  const [notice, setNotice] = useState<Schedule | null>(null);
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ['schedules'],
    queryFn: () => api<{ status: string; schedules: Schedule[] }>('/api/operator/schedules'),
    refetchInterval: 15000,
  });
  const status = useQuery({
    queryKey: ['status'],
    queryFn: () => api<ScheduleStatus>('/api/operator/status'),
    staleTime: 30000,
  });
  const canCreate =
    status.data?.scheduling.enabled &&
    status.data?.scheduling.workerEnabled &&
    status.data?.inference.available;
  function change(values: Record<string, string>) {
    setParams(
      Object.fromEntries(
        Object.entries({ status: filter, id: selected ?? '', ...values }).filter(([, value]) => value !== ''),
      ),
    );
  }
  const list =
    query.data?.schedules.filter((schedule) => filter === 'all' || schedule.status === filter) ?? [];
  return (
    <div className="stack">
      <PageHeader
        title="Schedules"
        description="Durable agent prompts with explicit timing, revisions, and occurrence evidence."
        actions={
          <button
            className="button primary"
            onClick={() => setEditor('new')}
            disabled={status.isSuccess && !canCreate}
          >
            <Plus size={16} />
            Create schedule
          </button>
        }
      />
      {status.isSuccess && (
        <div className="toolbar">
          <Badge status={status.data.scheduling.enabled ? 'active' : 'disabled'}>
            Agent scheduling {status.data.scheduling.enabled ? 'enabled' : 'disabled'}
          </Badge>
          <Badge status={status.data.scheduling.workerEnabled ? 'active' : 'disabled'}>
            JobRunr worker{' '}
            {status.data.scheduling.workerEnabled ? 'enabled · runtime not checked' : 'disabled'}
          </Badge>
          <Link to="/system">Integration details</Link>
        </div>
      )}
      {status.isError && <ErrorState error={status.error} retry={() => void status.refetch()} />}
      {status.isSuccess && !canCreate && (
        <div className="notice warning">
          Creating and resuming agent schedules requires available inference and scheduling services. Agent
          scheduling and the JobRunr worker are separate configuration switches.
        </div>
      )}
      {notice && (
        <div className={`notice ${notice.registration === 'pending' ? 'warning' : 'success'}`} role="status">
          <strong>Schedule saved · revision {notice.revision}</strong>
          <p>
            {notice.registration === 'pending'
              ? 'The definition committed, but job registration is pending. Reconciliation will retry registration; do not create another copy.'
              : 'The saved definition is available in the list.'}
          </p>
        </div>
      )}
      <section className="panel stack">
        <div className="toolbar">
          <div className="tabs" aria-label="Schedule status">
            {['all', 'active', 'paused', 'completed', 'cancelled'].map((value) => (
              <button
                key={value}
                aria-pressed={filter === value}
                className={filter === value ? 'active' : ''}
                onClick={() => change({ status: value })}
              >
                {value[0].toUpperCase() + value.slice(1)}
              </button>
            ))}
          </div>
          <button className="button ghost" onClick={() => void query.refetch()} disabled={query.isFetching}>
            <RefreshCw size={15} />
            Refresh
          </button>
        </div>
        {query.isPending ? (
          <Loading label="Loading saved schedules" />
        ) : query.isError ? (
          <ErrorState error={query.error} retry={() => void query.refetch()} />
        ) : !list.length ? (
          <EmptyState
            title="No matching schedules"
            description="Create a scheduled prompt to arrange future work in your operator scope."
          />
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Schedule</th>
                  <th>Timing</th>
                  <th>Status</th>
                  <th>Next occurrence</th>
                </tr>
              </thead>
              <tbody>
                {list.map((schedule) => (
                  <tr key={schedule.id}>
                    <td>
                      <button className="text-button" onClick={() => change({ id: schedule.id })}>
                        {schedule.name}
                      </button>
                      <div className="muted">
                        {schedule.monitoring_job_id
                          ? 'Monitoring check'
                          : schedule.action_type === 'execution'
                            ? 'Agent prompt'
                            : 'Slack message'}{' '}
                        · revision {schedule.revision}
                      </div>
                    </td>
                    <td>{timingSummary(schedule)}</td>
                    <td>
                      <Badge status={schedule.status}>{schedule.status}</Badge>
                      {schedule.registration === 'pending' && (
                        <div>
                          <Badge status="partial">Registration pending</Badge>
                        </div>
                      )}
                    </td>
                    <td>
                      {schedule.next_occurrence
                        ? formatDate(schedule.next_occurrence)
                        : 'No future occurrence'}
                      <div className="muted">{schedule.next_occurrence_local}</div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <div className="notice">
        <strong>Slack message scheduling</strong>
        <p>
          Static Slack messages and delivery destinations require authorization from a Slack conversation. Use
          schedule_message or schedule_execution in an authorized Slack conversation; local forms cannot grant
          channel access.
        </p>
      </div>
      {selected && (
        <Modal title="Schedule details" onClose={() => change({ id: '' })}>
          <ScheduleDetail
            id={selected}
            canResume={!!canCreate}
            onEdit={(schedule) => {
              setEditor(schedule);
              change({ id: '' });
            }}
          />
        </Modal>
      )}
      {editor && (
        <Modal title={editor === 'new' ? 'Create schedule' : 'Edit schedule'} onClose={() => setEditor(null)}>
          <ScheduleEditor
            schedule={editor === 'new' ? undefined : editor}
            admissionAvailable={status.isSuccess ? !!canCreate : undefined}
            onSaved={(schedule) => {
              setNotice(schedule);
              setEditor(null);
              void client.invalidateQueries({ queryKey: ['schedules'] });
              void client.invalidateQueries({ queryKey: ['schedule', schedule.id] });
            }}
          />
        </Modal>
      )}
    </div>
  );
}
function ScheduleDetail({
  id,
  canResume,
  onEdit,
}: {
  id: string;
  canResume: boolean;
  onEdit: (schedule: Schedule) => void;
}) {
  const client = useQueryClient();
  const [confirmCancel, setConfirmCancel] = useState(false);
  const query = useQuery({
    queryKey: ['schedule', id],
    queryFn: () => api<Schedule>(`/api/operator/schedules/${encodeURIComponent(id)}`),
    refetchInterval: 10000,
  });
  const action = useMutation({
    mutationFn: ({ name, revision }: { name: string; revision: number }) =>
      api<Schedule>(
        `/api/operator/schedules/${encodeURIComponent(id)}/${name}`,
        json('POST', { expected_revision: revision }),
      ),
    retry: false,
    onSuccess: () => {
      setConfirmCancel(false);
      void client.invalidateQueries({ queryKey: ['schedules'] });
      void query.refetch();
    },
  });
  if (query.isPending) return <Loading />;
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />;
  const schedule = query.data;
  const terminal = ['completed', 'cancelled'].includes(schedule.status);
  const uncertain =
    action.error instanceof ApiError && (action.error.status === 0 || action.error.status >= 500);
  const conflict = action.error instanceof ApiError && action.error.status === 409;
  return (
    <div className="stack">
      <div className="toolbar">
        <h3>{schedule.name}</h3>
        <Badge status={schedule.status}>{schedule.status}</Badge>
        <Badge>Revision {schedule.revision}</Badge>
      </div>
      <p>{timingSummary(schedule)}</p>
      <div className="notice">
        Pausing or cancelling changes future occurrences. It does not cancel an execution already admitted,
        undo remote effects, or retry uncertain delivery.
      </div>
      {schedule.monitoring_job_id && (
        <div className="notice">
          This schedule belongs to an ongoing monitoring job.{' '}
          <Link to={`/monitoring?id=${encodeURIComponent(schedule.monitoring_job_id)}`}>
            Open monitoring definition and controls
          </Link>
        </div>
      )}
      {!terminal && !schedule.monitoring_job_id && (
        <div className="toolbar">
          <button
            className="button"
            disabled={action.isPending || uncertain || conflict || schedule.action_type !== 'execution'}
            onClick={() => onEdit(schedule)}
          >
            Edit definition
          </button>
          {schedule.status === 'active' ? (
            <button
              className="button"
              disabled={action.isPending || uncertain || conflict}
              onClick={() => action.mutate({ name: 'pause', revision: schedule.revision })}
            >
              <Pause size={15} />
              Pause
            </button>
          ) : (
            <button
              className="button"
              disabled={action.isPending || uncertain || conflict || !canResume}
              onClick={() => action.mutate({ name: 'resume', revision: schedule.revision })}
            >
              <Play size={15} />
              Resume
            </button>
          )}
          <button
            className="button danger"
            disabled={action.isPending || uncertain || conflict}
            onClick={() => setConfirmCancel(true)}
          >
            <X size={15} />
            Cancel schedule
          </button>
        </div>
      )}
      {confirmCancel && (
        <div className="notice warning">
          <p>
            Cancel “{schedule.name}”? This definition cannot be resumed afterwards. Already admitted
            executions continue.
          </p>
          <div className="toolbar">
            <button
              className="button danger"
              disabled={action.isPending}
              onClick={() => action.mutate({ name: 'cancel', revision: schedule.revision })}
            >
              Cancel future occurrences
            </button>
            <button className="button" onClick={() => setConfirmCancel(false)}>
              Keep schedule
            </button>
          </div>
        </div>
      )}
      {action.error && <ErrorState error={action.error} />}
      {(uncertain || conflict) && (
        <button
          className="button"
          onClick={async () => {
            const refreshed = await query.refetch();
            if (refreshed.isSuccess) {
              action.reset();
              setConfirmCancel(false);
            }
          }}
        >
          Reload definition and review current state
        </button>
      )}
      {schedule.registration_failure && <div className="notice warning">{schedule.registration_failure}</div>}
      <dl className="detail-grid">
        <div>
          <dt>Next occurrence</dt>
          <dd>{schedule.next_occurrence ? formatDate(schedule.next_occurrence) : 'None planned'}</dd>
        </div>
        <div>
          <dt>In selected timezone</dt>
          <dd>{schedule.next_occurrence_local || 'None planned'}</dd>
        </div>
        <div>
          <dt>Job registration</dt>
          <dd>{schedule.registration}</dd>
        </div>
        <div>
          <dt>Missed-run policy</dt>
          <dd>{schedule.missed_run_policy.replaceAll('_', ' ')}</dd>
        </div>
        <div>
          <dt>Overlap policy</dt>
          <dd>{schedule.overlap_policy}</dd>
        </div>
        <div>
          <dt>Action</dt>
          <dd>{schedule.action_type}</dd>
        </div>
      </dl>
      <h3>{schedule.action_type === 'execution' ? 'Agent prompt' : 'Message'}</h3>
      <pre className="content-json">{schedule.prompt ?? schedule.message}</pre>
      {!!schedule.destination && (
        <JsonView label="Existing delivery destination" value={schedule.destination} />
      )}
      <h3>Recent occurrences</h3>
      <p className="muted">
        The backend returns up to 20 recent occurrences, including pending and superseded slots. Registration
        is not proof of delivery.
      </p>
      {!schedule.occurrences?.length ? (
        <EmptyState title="No occurrence evidence available" />
      ) : (
        <div className="stack">
          {schedule.occurrences.map((occurrence) => (
            <article className="panel stack" key={occurrence.id}>
              <div className="toolbar">
                <CalendarClock size={16} />
                <strong>{formatDate(occurrence.due_at)}</strong>
                <Badge status={occurrence.outcome}>
                  {occurrence.outcome === 'succeeded' ? 'Occurrence complete' : occurrence.outcome}
                </Badge>
              </div>
              <div className="muted">
                Definition revision {occurrence.revision}
                {occurrence.completed_at && ` · Settled ${formatDate(occurrence.completed_at)}`}
              </div>
              {occurrence.execution_id && (
                <Link to={`/tasks/${encodeURIComponent(occurrence.execution_id)}`}>
                  View execution
                  {occurrence.execution_status
                    ? ` · ${occurrence.execution_status === 'succeeded' ? 'Response complete' : occurrence.execution_status}`
                    : ''}
                </Link>
              )}
              {occurrence.failure && <p className="notice warning">{occurrence.failure}</p>}
              {!!occurrence.receipt && (
                <JsonView label="Delivery receipt evidence" value={occurrence.receipt} />
              )}
              <small className="mono">{occurrence.id}</small>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
function ScheduleEditor({
  schedule,
  admissionAvailable,
  onSaved,
}: {
  schedule?: Schedule;
  admissionAvailable?: boolean;
  onSaved: (schedule: Schedule) => void;
}) {
  const [form, setForm] = useState<FormState>(() =>
    schedule
      ? {
          ...newForm,
          name: schedule.name,
          prompt: schedule.prompt ?? '',
          mode: schedule.timing.mode,
          at: schedule.timing.at ?? '',
          cron: schedule.timing.cron ?? newForm.cron,
          timezone: schedule.timezone,
          retime: false,
        }
      : { ...newForm, timezone: applicationZone() ?? newForm.timezone },
  );
  const [revision, setRevision] = useState(schedule?.revision ?? 0);
  const [comparison, setComparison] = useState<Schedule | null>(null);
  const [validation, setValidation] = useState<Error | null>(null);
  const mutation = useMutation({
    mutationFn: () =>
      api<Schedule>(
        schedule ? `/api/operator/schedules/${encodeURIComponent(schedule.id)}` : '/api/operator/schedules',
        json(schedule ? 'PATCH' : 'POST', {
          name: form.name,
          prompt: form.prompt,
          ...(schedule ? { expected_revision: revision } : {}),
          ...(!schedule || form.retime ? { timing: scheduleTiming(form) } : {}),
        }),
      ),
    retry: false,
    onSuccess: onSaved,
  });
  const refresh = useMutation({
    mutationFn: () => api<Schedule>(`/api/operator/schedules/${encodeURIComponent(schedule!.id)}`),
    onSuccess: setComparison,
    retry: false,
  });
  const conflict = mutation.error instanceof ApiError && mutation.error.status === 409;
  const uncertain =
    mutation.error instanceof ApiError && (mutation.error.status === 0 || mutation.error.status >= 500);
  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((previous) => ({ ...previous, [key]: value }));
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    try {
      if (!schedule || form.retime) scheduleTiming(form);
      setValidation(null);
      mutation.mutate();
    } catch (error) {
      setValidation(error as Error);
    }
  }
  return (
    <form className="stack" onSubmit={submit}>
      {admissionAvailable === false && (
        <div className="notice warning">
          Saving agent schedule definitions requires available inference, agent scheduling, and the JobRunr
          worker. Review Integrations &amp; System to configure these independent capabilities.
        </div>
      )}
      <Field label="Schedule name">
        <input
          required
          maxLength={200}
          value={form.name}
          onChange={(event) => set('name', event.target.value)}
        />
      </Field>
      <Field label="Agent prompt">
        <textarea
          required
          rows={5}
          maxLength={100000}
          value={form.prompt}
          onChange={(event) => set('prompt', event.target.value)}
        />
      </Field>
      {schedule && (
        <label>
          <input
            type="checkbox"
            checked={form.retime}
            onChange={(event) => set('retime', event.target.checked)}
          />{' '}
          Change future timing
        </label>
      )}
      {(!schedule || form.retime) && (
        <>
          <div className="form-grid">
            <Field label="Timing">
              <select
                value={form.mode}
                onChange={(event) => set('mode', event.target.value as FormState['mode'])}
              >
                <option value="delay">Delay from admission</option>
                <option value="at">One-time timestamp</option>
                <option value="cron">Recurring cron</option>
              </select>
            </Field>
            <Field
              label="Timezone"
              hint={`IANA timezone; the application default is ${applicationZone() ?? 'the server zone'}.`}
            >
              <input
                required
                list="schedule-timezones"
                value={form.timezone}
                onChange={(event) => set('timezone', event.target.value)}
              />
              <datalist id="schedule-timezones">
                {[
                  'America/New_York',
                  'America/Chicago',
                  'America/Denver',
                  'America/Los_Angeles',
                  'Europe/London',
                  'Europe/Paris',
                  'Asia/Kolkata',
                  'Asia/Tokyo',
                  'UTC',
                ].map((zone) => (
                  <option key={zone} value={zone} />
                ))}
              </datalist>
            </Field>
          </div>
          {form.mode === 'delay' ? (
            <Field
              label="Delay in seconds"
              hint="Resolved once by the server into an absolute instant when created or retimed."
            >
              <input
                type="number"
                min={1}
                step={1}
                required
                value={form.delay}
                onChange={(event) => set('delay', event.target.value)}
              />
            </Field>
          ) : form.mode === 'at' ? (
            <Field
              label="ISO timestamp"
              hint="Use a future timestamp, e.g. 2026-11-05T09:00:00-05:00, or an explicit UTC timestamp ending Z. A local timestamp uses the selected timezone."
            >
              <input
                required
                className="mono"
                value={form.at}
                onChange={(event) => set('at', event.target.value)}
                placeholder="YYYY-MM-DDTHH:mm:ss-04:00"
              />
            </Field>
          ) : (
            <Field
              label="Spring cron expression"
              hint="Six fields: second minute hour day-of-month month day-of-week. Example: 0 0 9 * * MON-FRI for 9 AM weekdays."
            >
              <input
                required
                className="mono"
                value={form.cron}
                onChange={(event) => set('cron', event.target.value)}
              />
            </Field>
          )}
          <div className="notice">
            One-time local times in a DST gap are rejected; ambiguous times require an explicit valid offset.
            Recurrences skip missing local times and run overlapping local times once, at the earlier offset.
            Missed recurring runs and overlaps are skipped; one-time work can run once after recovery.
          </div>
        </>
      )}
      {validation && <ErrorState error={validation} />}
      {mutation.error && <ErrorState error={mutation.error} />}
      {(conflict || uncertain) && (
        <div className="notice warning">
          <p>
            {uncertain
              ? 'The operation may have committed. Inspect saved schedules before any new submission.'
              : 'The definition changed or its lifecycle no longer permits this edit. Load its current state and reconcile.'}
          </p>
          {schedule && (
            <button
              className="button"
              type="button"
              disabled={refresh.isPending}
              onClick={() => refresh.mutate()}
            >
              Load current definition
            </button>
          )}
        </div>
      )}
      {refresh.error && <ErrorState error={refresh.error} />}
      {comparison && (
        <section className="panel stack">
          <h3>
            Saved revision {comparison.revision} · {comparison.status}
          </h3>
          <p>{comparison.name}</p>
          <p>{timingSummary(comparison)}</p>
          <pre className="content-json">{comparison.prompt}</pre>
          {!['completed', 'cancelled'].includes(comparison.status) && (
            <button
              type="button"
              className="button"
              onClick={() => {
                setRevision(comparison.revision);
                setComparison(null);
                mutation.reset();
              }}
            >
              I reconciled my draft; use this revision
            </button>
          )}
        </section>
      )}
      <button
        className="button primary"
        disabled={mutation.isPending || conflict || uncertain || admissionAvailable === false}
      >
        {mutation.isPending ? 'Saving…' : schedule ? `Save revision ${revision}` : 'Create schedule'}
      </button>
    </form>
  );
}
