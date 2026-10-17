import { Link } from 'react-router-dom';
import { useQueries, useQuery } from '@tanstack/react-query';
import { ArrowRight, Plus } from 'lucide-react';
import { api } from '../api';
import { Badge, EmptyState, ErrorState, formatDate, Loading, PageHeader } from '../components';
import { compareInstants, useSystem, useTasks } from '../queries';
import { TaskTable } from './Tasks';
import { SpendingOverview } from '../usagePresentation';

type Schedule = {
  id: string;
  name: string;
  status: string;
  next_occurrence?: string;
  registration: string;
  registration_failure?: string;
  occurrences?: { id: string; outcome: string; failure?: string; due_at: string; execution_id?: string }[];
};
export default function Overview() {
  const tasks = useTasks();
  const system = useSystem();
  const schedules = useQuery({
    queryKey: ['overview-schedules'],
    queryFn: ({ signal }) => api<{ schedules: Schedule[] }>('/api/operator/schedules', { signal }),
    refetchInterval: 15000,
  });
  const scheduleDetails = useQueries({
    queries: (schedules.data?.schedules.slice(0, 8) ?? []).map((schedule) => ({
      queryKey: ['overview-schedule', schedule.id],
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        api<Schedule>(`/api/operator/schedules/${encodeURIComponent(schedule.id)}`, { signal }),
      refetchInterval: 15000,
    })),
  });
  const failures = scheduleDetails.flatMap((detail) =>
    (detail.data?.occurrences ?? [])
      .filter((item) => ['failed', 'uncertain'].includes(item.outcome))
      .map((item) => ({ ...item, schedule: detail.data! })),
  );
  const running = tasks.data?.filter((task) => task.status === 'running');
  const waiting = tasks.data?.filter((task) => task.status === 'waiting_for_user');
  const recent = tasks.data
    ?.slice()
    .sort((a, b) => compareInstants(b.updatedAt, a.updatedAt))
    .slice(0, 6);
  const upcoming = schedules.data?.schedules
    .filter((schedule) => schedule.status === 'active' && schedule.next_occurrence)
    .sort((a, b) => compareInstants(a.next_occurrence!, b.next_occurrence!))
    .slice(0, 4);
  const attention = tasks.data
    ?.filter((task) => ['failed', 'blocked', 'partial'].includes(task.status))
    .sort((a, b) => compareInstants(b.updatedAt, a.updatedAt))
    .slice(0, 3);
  const metrics = [
    {
      label: 'Running tasks',
      value: tasks.isError ? undefined : running?.length,
      to: '/tasks?status=running',
    },
    {
      label: 'Needs input',
      value: tasks.isError ? undefined : waiting?.length,
      to: '/tasks?status=waiting_for_user',
    },
    {
      label: 'Active schedules',
      value: schedules.isError
        ? undefined
        : schedules.data?.schedules.filter((s) => s.status === 'active').length,
      to: '/schedules',
    },
  ];
  return (
    <>
      <PageHeader
        title="Overview"
        actions={
          <Link className="button primary" to="/tasks?new=1">
            <Plus size={16} />
            New task
          </Link>
        }
      />
      <div className="metric-grid">
        {metrics.map(({ label, value, to }) => (
          <Link to={to} className="metric-card" key={label}>
            <div className="metric-top">{label}</div>
            <strong className="metric-value">{value === undefined ? '—' : value.toLocaleString()}</strong>
          </Link>
        ))}
      </div>
      {waiting && waiting.length > 0 && (
        <Link className="attention-banner" to="/tasks?status=waiting_for_user">
          <span>
            <strong>
              {waiting.length} {waiting.length === 1 ? 'task needs' : 'tasks need'} information
            </strong>
            <span className="muted">Review questions and reply from the task’s authorized origin.</span>
          </span>
          <ArrowRight size={18} />
        </Link>
      )}
      <div className="overview-grid">
        <div className="stack">
          <section className="panel">
            <div className="panel-heading">
              <h2>Recent tasks</h2>
              <Link className="text-link" to="/tasks">
                View all <ArrowRight size={14} />
              </Link>
            </div>
            {tasks.isPending ? (
              <Loading />
            ) : tasks.isError ? (
              <ErrorState error={tasks.error} retry={() => tasks.refetch()} />
            ) : recent?.length ? (
              <TaskTable tasks={recent} compact />
            ) : (
              <EmptyState
                title="Ready for your first task"
                description="Start with a question, a research request, or a goal."
                action={
                  <Link className="button" to="/tasks?new=1">
                    <Plus size={15} />
                    Create a task
                  </Link>
                }
              />
            )}
          </section>
          {attention && attention.length > 0 && (
            <section className="panel">
              <div className="panel-heading">
                <h2>Outcomes to review</h2>
              </div>
              {attention.map((task) => (
                <Link className="list-row" key={task.executionId} to={`/tasks/${task.executionId}`}>
                  <div>
                    <strong>{task.originalRequest}</strong>
                    <small>
                      {task.failure ||
                        task.blockers[0] ||
                        task.finalAnswer?.slice(0, 150) ||
                        'Inspect the saved outcome and evidence.'}
                    </small>
                  </div>
                  <Badge status={task.status} />
                </Link>
              ))}
            </section>
          )}
        </div>
        <aside className="stack">
          <section className="panel">
            <div className="panel-heading">
              <h2>Coming up</h2>
              <Link to="/schedules" className="text-link">
                Schedules
              </Link>
            </div>
            {schedules.isPending ? (
              <Loading />
            ) : schedules.isError ? (
              <ErrorState error={schedules.error} />
            ) : upcoming?.length ? (
              upcoming.map((schedule) => (
                <Link
                  className="schedule-row"
                  key={schedule.id}
                  to={`/schedules?id=${encodeURIComponent(schedule.id)}`}
                >
                  <strong>{schedule.name}</strong>
                  <small>{formatDate(schedule.next_occurrence)}</small>
                  {schedule.registration !== 'registered' && (
                    <Badge status="pending">Registration pending</Badge>
                  )}
                </Link>
              ))
            ) : (
              <EmptyState
                title="Nothing scheduled"
                description="Schedule a prompt for later or recurring work."
                action={
                  <Link to="/schedules?new=1" className="text-link">
                    Create schedule <Plus size={14} />
                  </Link>
                }
              />
            )}
          </section>
          <section className="panel">
            <div className="panel-heading">
              <h2>System</h2>
              <Link to="/system" className="text-link">
                Details
              </Link>
            </div>
            {system.isPending ? (
              <Loading />
            ) : system.isError ? (
              <ErrorState error={system.error} />
            ) : (
              system.data && (
                <div className="system-summary">
                  <div>
                    <span>Inference</span>
                    <Badge status={system.data.inference.available ? 'enabled' : 'disabled'}>
                      {system.data.inference.available ? system.data.inference.provider : 'Disabled'}
                    </Badge>
                  </div>
                  <div>
                    <span>Browser tools</span>
                    <Badge status={system.data.browser.enabled ? 'enabled' : 'disabled'}>
                      {system.data.browser.enabled ? 'Enabled' : 'Disabled'}
                    </Badge>
                  </div>
                  <div>
                    <span>Slack</span>
                    <Badge status={system.data.slack.connection} />
                  </div>
                  <div>
                    <span>JobRunr worker</span>
                    <Badge status={system.data.scheduling.workerEnabled ? 'enabled' : 'disabled'}>
                      {system.data.scheduling.workerEnabled ? 'Enabled' : 'Disabled'}
                    </Badge>
                  </div>
                  <p className="muted fine-print">
                    Enabled describes configuration. Connection checks and details are on the System page.
                  </p>
                </div>
              )
            )}
          </section>
        </aside>
      </div>
      <SpendingOverview />
      {(failures.length > 0 ||
        schedules.data?.schedules.some((schedule) => schedule.registration_failure)) && (
        <section className="panel section-space">
          <div className="panel-heading">
            <div>
              <h2>Schedule issues</h2>
              <p>Available history from up to 8 schedules, 20 occurrences per schedule</p>
            </div>
          </div>
          {schedules.data?.schedules
            .filter((s) => s.registration_failure)
            .map((s) => (
              <Link className="list-row" to={`/schedules?id=${s.id}`} key={s.id}>
                <strong>{s.name}</strong>
                <span>{s.registration_failure}</span>
              </Link>
            ))}
          {failures.map((item) => (
            <Link key={item.id} className="list-row" to={`/schedules?id=${item.schedule.id}`}>
              <div>
                <strong>{item.schedule.name}</strong>
                <small>{item.failure || `Occurrence at ${formatDate(item.due_at)}`}</small>
              </div>
              <Badge status={item.outcome} />
            </Link>
          ))}
        </section>
      )}
    </>
  );
}
