import { useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  ArrowUpRight,
  Bot,
  Check,
  ChevronRight,
  Clock3,
  Coins,
  FileText,
  Globe2,
  MessageSquare,
  MoreHorizontal,
  Paperclip,
  Play,
  Plus,
  Search,
  Send,
  Square,
  TerminalSquare,
  Trash2,
  Wrench,
} from 'lucide-react';
import { api, ApiError, artifactChunk, json } from '../api';
import {
  Badge,
  EmptyState,
  ErrorState,
  Field,
  formatDate,
  JsonView,
  labels,
  Loading,
  Modal,
  PageHeader,
  relativeTime,
  SafeMarkdown,
} from '../components';
import {
  compareInstants,
  currentActions,
  newerTask,
  terminal,
  useSystem,
  useTask,
  useTaskActions,
  useTaskStream,
  useTasks,
} from '../queries';
import { awaitingApproval, type Task, type ToolCall } from '../types';
import { DeliveryAcknowledgement } from './DeliveryAcknowledgement';
import { LearnSkill } from './LearnSkill';
import { useTaskEstimates, useTaskUsage } from '../usage';
import { CostEstimate, Coverage, UsageBreakdowns } from '../usagePresentation';
import TaskCodeRuns from './TaskCodeRuns';

export function TaskTable({ tasks, compact = false }: { tasks: Task[]; compact?: boolean }) {
  const estimates = useTaskEstimates(tasks.map((task) => task.executionId));
  const taskIds = [...new Set(tasks.map((task) => task.executionId))].sort();
  const batchFor = (id: string) => estimates[Math.floor(taskIds.indexOf(id) / 100)];
  return (
    <div className="table-wrap">
      <table className="data-table task-table">
        <thead>
          <tr>
            <th>Task</th>
            <th>Status</th>
            <th>Origin</th>
            {!compact && <th>Tokens</th>}
            <th>Est. cost (USD)</th>
            <th>Updated</th>
            <th>
              <span className="sr-only">View</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((task) => (
            <tr key={task.executionId}>
              <td>
                <Link className="task-title" to={`/tasks/${task.executionId}`}>
                  <span className={`task-icon ${task.status === 'running' ? 'blue' : ''}`}>
                    <Bot size={16} />
                  </span>
                  <span>
                    <strong>{task.originalRequest}</strong>
                    <small>
                      {task.executionId.slice(0, 8)}
                      {task.conversationId ? ` · ${task.conversationId}` : ''}
                    </small>
                  </span>
                </Link>
              </td>
              <td>
                <Badge status={task.status} />
                {task.cancellationRequested && !terminal(task) && (
                  <small className="block muted">Cancellation pending</small>
                )}
              </td>
              <td>
                <span className="origin">{task.scope.origin}</span>
              </td>
              {!compact && (
                <td className="mono">
                  {task.totalTokens.toLocaleString()}
                  {task.usageMissing && <span title="Usage is incomplete">*</span>}
                </td>
              )}
              <td>
                <CostEstimate
                  value={batchFor(task.executionId)?.data?.tasks?.[task.executionId]}
                  loading={batchFor(task.executionId)?.isPending}
                  error={batchFor(task.executionId)?.isError}
                />
              </td>
              <td>
                <time dateTime={task.updatedAt} title={formatDate(task.updatedAt)}>
                  {relativeTime(task.updatedAt)}
                </time>
              </td>
              <td>
                <Link
                  className="icon-button"
                  to={`/tasks/${task.executionId}`}
                  aria-label={`Open task ${task.executionId}`}
                >
                  <ChevronRight size={16} />
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function Tasks() {
  const tasks = useTasks();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const status = params.get('status') ?? 'all';
  const search = params.get('q') ?? '';
  const origin = params.get('origin') ?? 'all';
  const sort = params.get('sort') ?? 'updated';
  const filtered =
    tasks.data
      ?.filter(
        (task) =>
          (status === 'all' || task.status === status) &&
          (origin === 'all' || task.scope.origin === origin) &&
          `${task.originalRequest} ${task.currentGoal} ${task.executionId} ${task.conversationId ?? ''}`
            .toLowerCase()
            .includes(search.toLowerCase()),
      )
      .sort((a, b) =>
        sort === 'created'
          ? compareInstants(b.createdAt, a.createdAt)
          : sort === 'oldest'
            ? compareInstants(a.createdAt, b.createdAt)
            : compareInstants(b.updatedAt, a.updatedAt),
      ) ?? [];
  function update(key: string, value: string) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        value && value !== 'all' ? next.set(key, value) : next.delete(key);
        return next;
      },
      { replace: true },
    );
  }
  return (
    <>
      <PageHeader
        title="Tasks"
        description="Create, follow, and guide durable agent work."
        actions={
          <button className="button primary" onClick={() => update('new', '1')}>
            <Plus size={16} />
            New task
          </button>
        }
      />
      <section className="panel">
        <div className="tabs" aria-label="Task status filters">
          {[
            'all',
            'running',
            'waiting_for_user',
            'succeeded',
            'partial',
            'blocked',
            'failed',
            'cancelled',
          ].map((item) => (
            <button
              key={item}
              className={status === item ? 'active' : ''}
              onClick={() => update('status', item)}
            >
              {item === 'all' ? 'All tasks' : labels[item]}
              {tasks.data && (
                <span className="count">
                  {tasks.data.filter((task) => item === 'all' || task.status === item).length}
                </span>
              )}
            </button>
          ))}
        </div>
        <div className="toolbar">
          <div className="search-field">
            <Search size={16} />
            <input
              aria-label="Search loaded tasks"
              placeholder="Search tasks…"
              value={search}
              onChange={(event) => update('q', event.target.value)}
            />
          </div>
          <select
            aria-label="Filter task origin"
            value={origin}
            onChange={(event) => update('origin', event.target.value)}
          >
            <option value="all">All origins</option>
            {[...new Set(tasks.data?.map((task) => task.scope.origin))].map((item) => (
              <option key={item}>{item}</option>
            ))}
          </select>
          <select
            aria-label="Sort tasks"
            value={sort}
            onChange={(event) => update('sort', event.target.value)}
          >
            <option value="updated">Recently updated</option>
            <option value="created">Newest created</option>
            <option value="oldest">Oldest created</option>
          </select>
        </div>
        {tasks.isPending ? (
          <Loading />
        ) : tasks.isError ? (
          <ErrorState error={tasks.error} retry={() => tasks.refetch()} />
        ) : filtered.length ? (
          <TaskTable tasks={filtered} />
        ) : (
          <EmptyState
            title={tasks.data?.length ? 'No matching tasks' : 'Your next task starts here'}
            description={
              tasks.data?.length
                ? 'Try another status or search.'
                : 'Give Velocity a goal and follow its responses and tool activity as they are saved.'
            }
            action={
              <button className="button primary" onClick={() => update('new', '1')}>
                <Plus size={16} />
                Create a task
              </button>
            }
          />
        )}
        <div className="panel-footer">
          {tasks.data ? `${filtered.length} of ${tasks.data.length} loaded tasks` : 'Task data unavailable'}
          <span>Filters apply to the current snapshots · Polling every 3 seconds</span>
        </div>
      </section>
      {params.get('new') === '1' && (
        <NewTaskModal
          initialRequest={(location.state as { request?: string } | null)?.request ?? ''}
          onClose={() => update('new', '')}
        />
      )}
    </>
  );
}

/** Mirrors the agent.uploads defaults; the server enforces its configured limits and names the rejected file. */
const UPLOAD_LIMITS = { files: 10, fileBytes: 10 * 1024 * 1024, totalBytes: 25 * 1024 * 1024 };
const UPLOAD_ACCEPT =
  '.txt,.log,.md,.csv,.tsv,.json,.xml,.yaml,.yml,.rtf,.html,.htm,.docx,.xlsx,.xls,.pdf,.png,.jpg,.jpeg';
type AttachedFile = { filename: string; mediaType: string; contentBase64: string; size: number };

export function NewTaskModal({
  onClose,
  initialRequest = '',
}: {
  onClose: () => void;
  initialRequest?: string;
}) {
  const [request, setRequest] = useState(initialRequest);
  const [conversationId, setConversationId] = useState('');
  const [files, setFiles] = useState<AttachedFile[]>([]);
  const [fileError, setFileError] = useState<string | null>(null);
  const [readingFiles, setReadingFiles] = useState(false);
  const system = useSystem();
  const navigate = useNavigate();
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: () =>
      api<Task>(
        '/api/agent/executions',
        json('POST', {
          request,
          ...(conversationId.trim() ? { conversationId } : {}),
          ...(files.length > 0
            ? {
                files: files.map(({ filename, mediaType, contentBase64 }) => ({
                  filename,
                  ...(mediaType ? { mediaType } : {}),
                  contentBase64,
                })),
              }
            : {}),
        }),
      ),
    onSuccess: (task) => {
      client.setQueryData(['task', task.executionId], task);
      client.invalidateQueries({ queryKey: ['tasks'] });
      onClose();
      navigate(`/tasks/${task.executionId}`);
    },
  });
  const disabled = system.data?.inference.available === false;
  async function addFiles(selected: FileList | null) {
    if (!selected) return;
    setReadingFiles(true);
    setFileError(null);
    try {
      if (files.length + selected.length > UPLOAD_LIMITS.files)
        throw new Error(`Attach at most ${UPLOAD_LIMITS.files} files per task.`);
      let total = files.reduce((sum, file) => sum + file.size, 0);
      const added: AttachedFile[] = [];
      for (const file of Array.from(selected)) {
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(file.name) || file.name.includes('..'))
          throw new Error(
            `Rename ${file.name}: use at most 100 ASCII letters, digits, dots, dashes or underscores.`,
          );
        if (files.some((existing) => existing.filename.toLowerCase() === file.name.toLowerCase()))
          throw new Error(`${file.name} is already attached.`);
        if (file.size === 0) throw new Error(`${file.name} is empty.`);
        if (file.size > UPLOAD_LIMITS.fileBytes)
          throw new Error(`${file.name} exceeds ${UPLOAD_LIMITS.fileBytes / 1024 / 1024} MiB.`);
        total += file.size;
        if (total > UPLOAD_LIMITS.totalBytes)
          throw new Error(`Attached files exceed ${UPLOAD_LIMITS.totalBytes / 1024 / 1024} MiB in total.`);
        const contentBase64 = await new Promise<string>((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(String(reader.result).split(',')[1] ?? '');
          reader.onerror = () => reject(new Error(`${file.name} could not be read.`));
          reader.readAsDataURL(file);
        });
        added.push({ filename: file.name, mediaType: file.type, contentBase64, size: file.size });
      }
      setFiles((previous) => [...previous, ...added]);
    } catch (error) {
      setFileError(error instanceof Error ? error.message : 'Files could not be attached.');
    } finally {
      setReadingFiles(false);
    }
  }
  return (
    <Modal title="Create a task" onClose={onClose}>
      <form
        className="stack"
        onSubmit={(event) => {
          event.preventDefault();
          mutation.mutate();
        }}
      >
        <p className="muted">
          Describe the outcome you need. Velocity will ask for missing information when needed.
        </p>
        {disabled && (
          <div className="notice warning">
            Inference is disabled. Configure a provider before creating a task.{' '}
            <Link to="/system">View system status</Link>
          </div>
        )}
        <Field label="Task request">
          <textarea
            autoFocus
            rows={7}
            required
            maxLength={200000}
            placeholder="What would you like Velocity to do?"
            value={request}
            onChange={(event) => setRequest(event.target.value)}
          />
        </Field>
        <Field
          label="Conversation label (optional)"
          hint="A label helps you organize tasks. It does not carry history into this task."
        >
          <input
            value={conversationId}
            onChange={(event) => setConversationId(event.target.value)}
            maxLength={300}
          />
        </Field>
        <Field
          label="Files (optional)"
          hint="Documents, spreadsheets, PDFs or images the task should work with. Contents are treated as data, not instructions; the model reads them only when it opens them."
        >
          <input
            type="file"
            multiple
            accept={UPLOAD_ACCEPT}
            disabled={readingFiles}
            onChange={(event) => {
              void addFiles(event.target.files);
              event.target.value = '';
            }}
          />
        </Field>
        {files.length > 0 && (
          <ul className="file-list">
            {files.map((file) => (
              <li key={file.filename}>
                <Paperclip size={14} />
                <span>{file.filename}</span>
                <span className="muted">{file.size.toLocaleString()} bytes</span>
                <button
                  type="button"
                  className="button ghost"
                  onClick={() => setFiles((previous) => previous.filter((item) => item !== file))}
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        )}
        {fileError && <div className="notice error">{fileError}</div>}
        {system.data?.browser.enabled && (
          <button
            className="button ghost align-start"
            type="button"
            onClick={() =>
              setRequest(
                'Use browser_navigate to open https://example.com, browser_state to read the page, the available browser tools only as needed for the requested goal, and browser_close when finished. Report what you observed.',
              )
            }
          >
            <Globe2 size={16} />
            Use a browser task starter
          </button>
        )}
        {mutation.isError && <ErrorState error={mutation.error} />}
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Close
          </button>
          <button
            className="button primary"
            disabled={disabled || mutation.isPending || readingFiles || !request.trim()}
          >
            <Send size={16} />
            {mutation.isPending ? 'Creating…' : 'Create task'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

export function TaskDetail() {
  const { id = '' } = useParams();
  const stream = useTaskStream(id);
  const taskQuery = useTask(id, stream.connected);
  const actionQuery = useTaskActions(id);
  const usageQuery = useTaskUsage(id);
  const client = useQueryClient();
  const navigate = useNavigate();
  const [deleting, setDeleting] = useState(false);
  const [continuing, setContinuing] = useState(false);
  const [tab, setTab] = useState('conversation');
  const [artifact, setArtifact] = useState<string>();
  const task = taskQuery.data;
  const taskDeadlineOnly = !task?.monitoring;
  const actions =
    !taskQuery.isError && !actionQuery.isError ? currentActions(task, actionQuery.data) : undefined;
  const owner = actionQuery.data?.owner === true;
  function refreshTask() {
    void client.invalidateQueries({ queryKey: ['task', id] });
    void client.invalidateQueries({ queryKey: ['task-actions', id] });
  }
  function save(incoming: Task) {
    client.setQueryData<Task>(['task', id], (old) => newerTask(old, incoming));
    client.invalidateQueries({ queryKey: ['tasks'] });
    client.invalidateQueries({ queryKey: ['task-actions', id] });
  }
  const cancel = useMutation({
    mutationFn: () => api<Task>(`/api/agent/executions/${encodeURIComponent(id)}/cancel`, json('POST')),
    onSuccess: save,
  });
  const remove = useMutation({
    mutationFn: (expectedUpdatedAt: string) =>
      api<void>(`/api/operator/tasks/${encodeURIComponent(id)}`, json('DELETE', { expectedUpdatedAt })),
    onSuccess: () => {
      client.removeQueries({ queryKey: ['task', id] });
      client.removeQueries({ queryKey: ['task-code-runs', id] });
      client.invalidateQueries({ queryKey: ['tasks'] });
      navigate('/tasks');
    },
  });
  if (!task)
    return (
      <>
        {taskQuery.isPending ? (
          <Loading />
        ) : (
          <ErrorState error={taskQuery.error} retry={() => taskQuery.refetch()} />
        )}
      </>
    );
  return (
    <>
      <Link className="back-link" to="/tasks">
        <ArrowLeft size={15} />
        All tasks
      </Link>
      <PageHeader
        className="task-page-header"
        eyebrow={`Task ${task.executionId.slice(0, 8)}`}
        title={task.currentGoal || task.originalRequest}
        description={
          <>
            <Badge status={task.status} />
            <span className="detail-subtitle">
              {task.scope.origin} · Created {formatDate(task.createdAt)}
            </span>
          </>
        }
        actions={
          <>
            <Link className="button" to="/tasks?new=1" state={{ request: task.originalRequest }}>
              <Plus size={15} />
              Run again as new task
            </Link>
            {!terminal(task) && (
              <button
                className="button"
                disabled={!actions?.cancel || cancel.isPending}
                onClick={() => cancel.mutate()}
              >
                <Square size={14} />
                {task.cancellationRequested ? 'Cancellation pending' : 'Cancel task'}
              </button>
            )}
            {actions?.continuation ? (
              <Link className="button" to={`/tasks/${encodeURIComponent(actions.continuation)}`}>
                <ArrowUpRight size={15} />
                Open continuation
              </Link>
            ) : (
              terminal(task) &&
              task.status !== 'succeeded' &&
              !task.monitoring && (
                <button
                  className="button primary"
                  disabled={!actions?.continueTask}
                  title={
                    !actions?.continueTask
                      ? 'Waiting for worker settlement and eligibility'
                      : 'Start a new task that picks up from this one'
                  }
                  onClick={() => setContinuing(true)}
                >
                  <Play size={15} />
                  Continue
                </button>
              )
            )}
            {terminal(task) && (
              <button
                className="button danger"
                disabled={!actions?.delete}
                title={
                  !actions?.delete
                    ? 'Waiting for worker settlement and eligibility'
                    : 'Delete this settled task'
                }
                onClick={() => setDeleting(true)}
              >
                <Trash2 size={15} />
                Delete
              </button>
            )}
          </>
        }
      />
      {taskQuery.isError && <ErrorState error={taskQuery.error} retry={() => taskQuery.refetch()} />}{' '}
      {actionQuery.isError && <ErrorState error={actionQuery.error} retry={() => actionQuery.refetch()} />}{' '}
      {cancel.isError && <ErrorState error={cancel.error} />}
      {task.cancellationRequested && (
        <div className="notice warning">
          Cancellation has been requested. Workers may still be settling. Remote effects are not undone.
        </div>
      )}
      {(task.failure || task.blockers.length > 0) && (
        <div className="notice error">
          <div>
            <strong>{task.failure || 'Task blockers'}</strong>
            {task.blockers.map((blocker, index) => (
              <p key={index}>{blocker}</p>
            ))}
          </div>
        </div>
      )}
      <div className="task-detail-grid">
        <section className="panel task-conversation">
          <div className="tabs">
            {[
              ['conversation', MessageSquare, 'Conversation'],
              ['tools', Wrench, `Tool activity (${task.calls.length})`],
              ['code', TerminalSquare, 'Code & files'],
              ['cost', Coins, 'Estimated cost'],
              ['inspector', MoreHorizontal, 'Inspector'],
            ].map(([key, Icon, label]) => {
              const I = Icon as typeof MessageSquare;
              return (
                <button
                  key={String(key)}
                  className={tab === key ? 'active' : ''}
                  onClick={() => setTab(String(key))}
                >
                  <I size={15} />
                  {String(label)}
                </button>
              );
            })}
          </div>
          <div className="panel-body">
            {tab === 'code' && <TaskCodeRuns taskId={task.executionId} />}
            {tab === 'conversation' && (
              <div className="conversation-stack">
                <article className="message user-message">
                  <div className="message-label">
                    <span className="avatar small">OP</span>
                    <strong>Original request</strong>
                  </div>
                  <SafeMarkdown text={task.originalRequest} />
                </article>
                {task.currentGoal !== task.originalRequest && (
                  <article className="message">
                    <div className="message-label">
                      <Bot size={16} />
                      <strong>Current goal</strong>
                    </div>
                    <SafeMarkdown text={task.currentGoal} />
                  </article>
                )}
                {task.corrections.map((correction) => (
                  <article className="message user-message" key={correction.id}>
                    <div className="message-label">
                      <MessageSquare size={16} />
                      <strong>Update</strong>
                      <time>{formatDate(correction.createdAt)}</time>
                    </div>
                    <SafeMarkdown text={correction.text} />
                  </article>
                ))}
                {task.messages
                  .filter(
                    (message, index) =>
                      message.role.toUpperCase() === 'ASSISTANT' &&
                      !(task.taskOutcome && index === task.messages.length - 1),
                  )
                  .flatMap((message, index) =>
                    message.content
                      .filter(
                        (content) =>
                          typeof content.text === 'string' &&
                          !('signature' in content) &&
                          content.text !== task.finalAnswer,
                      )
                      .map((content, block) => (
                        <article key={`${index}-${block}`} className="message assistant-message">
                          <div className="message-label">
                            <Bot size={16} />
                            <strong>Velocity</strong>
                          </div>
                          <SafeMarkdown text={String(content.text)} />
                        </article>
                      )),
                  )}
                {task.finalAnswer && (
                  <article className="message assistant-message">
                    <div className="message-label">
                      <span className="velocity-avatar">
                        <CircleMark />
                      </span>
                      <strong>Velocity response</strong>
                      <Badge status={task.status} />
                    </div>
                    <SafeMarkdown text={task.finalAnswer} />
                    {actions?.owner &&
                      actions.settled &&
                      task.status === 'succeeded' &&
                      !task.cancellationRequested &&
                      ['local', 'http', 'slack'].includes(task.scope.origin) && (
                        <>
                          <DeliveryAcknowledgement executionId={task.executionId} revision={task.revision} />
                          <LearnSkill executionId={task.executionId} />
                        </>
                      )}
                  </article>
                )}
                {!task.finalAnswer && task.status === 'running' && stream.liveText && (
                  <article className="message assistant-message live-message" aria-live="polite">
                    <div className="message-label">
                      <Bot size={16} />
                      <strong>Velocity</strong>
                      <Badge status="running">Writing</Badge>
                    </div>
                    <SafeMarkdown text={stream.liveText} />
                  </article>
                )}
                {!task.finalAnswer && task.status === 'running' && (
                  <div className="activity-notice">
                    <span className="pulse-dot" />
                    <div>
                      <strong>
                        {task.calls.find((call) => call.state === 'dispatched')?.progress ||
                          (stream.liveText ? 'Velocity is writing' : 'Velocity is working')}
                      </strong>
                      <small>
                        {stream.connected
                          ? 'Live: saved progress and model output arrive as they happen.'
                          : 'Saved progress is checked every 2 seconds.'}
                      </small>
                    </div>
                  </div>
                )}
                {task.calls.some(awaitingApproval) && (
                  <ApprovalPanel
                    key={`${task.executionId}:${task.calls
                      .filter(awaitingApproval)
                      .map((call) => call.id)
                      .join(':')}`}
                    task={task}
                    canDecide={owner && actions?.approve === true}
                    onSaved={save}
                    onConflict={refreshTask}
                  />
                )}
                {task.pendingQuestions.length > 0 && (
                  <div className="questions">
                    <h3>
                      <MessageSquare size={18} />
                      Velocity needs information
                    </h3>
                    {task.pendingQuestions.map((question) => (
                      <p key={question.id}>{question.text}</p>
                    ))}
                    {actions?.owner === false && (
                      <p className="muted">
                        This task belongs to another account. Answers require that account’s authenticated
                        conversation.
                      </p>
                    )}
                  </div>
                )}
                {owner && task.status === 'waiting_for_user' && task.pendingQuestions.length > 0 && (
                  <AnswerForm
                    key={`${task.executionId}:${task.pendingQuestions.map((question) => question.id).join(':')}`}
                    task={task}
                    canSubmit={actions?.answer === true}
                    onSaved={save}
                    onConflict={refreshTask}
                  />
                )}
                {owner && !terminal(task) && (
                  <SteerForm
                    key={task.executionId}
                    task={task}
                    canSubmit={actions?.steer === true}
                    onSaved={save}
                  />
                )}
                {actions && !actions.owner && !terminal(task) && (
                  <div className="notice">
                    This task belongs to another account. You can inspect and administratively cancel it here;
                    steering and answers require its owner.
                  </div>
                )}
                {task.status === 'succeeded' && (
                  <p className="muted fine-print">
                    <Check size={14} />
                    Response complete means the model finished its response. Correctness and remote delivery
                    require separate evidence.
                  </p>
                )}
              </div>
            )}
            {tab === 'tools' &&
              (task.calls.length ? (
                <div className="tool-list">
                  {task.calls.map((call, index) => (
                    <ToolActivity key={call.id} call={call} index={index} onArtifact={setArtifact} />
                  ))}
                </div>
              ) : (
                <EmptyState
                  title="No tool calls recorded"
                  description="Accepted tool calls appear here in their original order."
                />
              ))}
            {tab === 'cost' &&
              (usageQuery.isPending ? (
                <Loading label="Loading task accounting…" />
              ) : usageQuery.isError ? (
                <ErrorState error={usageQuery.error} retry={() => usageQuery.refetch()} />
              ) : (
                usageQuery.data && (
                  <div className="stack">
                    <h3>Task estimated cost · USD</h3>
                    <CostEstimate value={usageQuery.data} />
                    <p className="muted">
                      Cumulative across all providers, models and resumptions of this task. Includes recorded
                      root, delegated and attachment requests exactly once. Background learning appears
                      separately in account usage. Amounts are estimates, not provider invoices.
                    </p>
                    <Coverage report={usageQuery.data} />
                    <UsageBreakdowns breakdown={usageQuery.data.breakdown} />
                  </div>
                )
              ))}
            {tab === 'inspector' && (
              <div className="stack">
                <JsonView label="Execution snapshot, messages, and native blocks" value={task} />
                <h3>Source context</h3>
                <JsonView
                  label="Trusted scope and source turn"
                  value={{ scope: task.scope, sourceTurn: task.sourceTurn }}
                />
                {task.sourceMessages.length ? (
                  task.sourceMessages.map((message, i) => (
                    <article className="message" key={i}>
                      <strong>{message.authorId}</strong>
                      <SafeMarkdown text={message.text} />
                      <JsonView label="Source provenance" value={message.source} />
                    </article>
                  ))
                ) : (
                  <p className="muted">No external source messages are stored for this task.</p>
                )}
                <p className="muted">
                  Source text and native outputs are untrusted data. No direct tool dispatch is exposed here.
                </p>
              </div>
            )}
          </div>
        </section>
        <aside className="stack">
          {terminal(task) && (
            <section className="panel">
              <div className="panel-heading">
                <h3>Reported task outcome</h3>
              </div>
              <div className="panel-body stack markdown">
                <strong>
                  {task.taskOutcome
                    ? { completed: 'Completed', partial: 'Partial', blocked: 'Blocked', failed: 'Failed' }[
                        task.taskOutcome.status
                      ]
                    : 'Not reported'}
                </strong>
                <p className="muted fine-print">
                  {task.taskOutcome
                    ? 'The model reported this outcome. Its stated evidence is not independently verified by Velocity.'
                    : 'No structured outcome was reported. Response completion alone does not establish that the task was completed.'}
                </p>
                {task.taskOutcome &&
                  [
                    { label: 'Completion criteria', items: task.taskOutcome.criteria },
                    { label: 'Reported evidence', items: task.taskOutcome.evidence },
                    { label: 'Limitations', items: task.taskOutcome.limitations },
                  ].map(
                    ({ label, items }) =>
                      items.length > 0 && (
                        <div key={label}>
                          <strong>{label}</strong>
                          <ul>
                            {items.map((item, index) => (
                              <li key={index}>{item}</li>
                            ))}
                          </ul>
                        </div>
                      ),
                  )}
              </div>
            </section>
          )}
          <TaskFiles taskId={task.executionId} />
          <TaskReports taskId={task.executionId} />
          {task.calls.some((call) => call.name === 'code_run') && (
            <section className="panel panel-body">
              <h3>
                <TerminalSquare size={16} /> Python code execution
              </h3>
              <p className="muted">
                {task.calls.filter((call) => call.name === 'code_run').length} code calls recorded. Inspect
                run status, output and generated files.
              </p>
              <button className="button ghost" onClick={() => setTab('code')}>
                View code runs and files <ArrowUpRight size={14} />
              </button>
            </section>
          )}
          <section className="panel">
            <div className="panel-heading">
              <h3>Execution details</h3>
              <span className="mono muted">r{task.revision}</span>
            </div>
            <dl className="key-values">
              <dt>Origin</dt>
              <dd>{task.scope.origin}</dd>
              <dt>Updated</dt>
              <dd>{formatDate(task.updatedAt)}</dd>
              <dt>Worker settlement</dt>
              <dd>{actions ? (actions.settled ? 'Settled' : 'Pending') : 'Checking…'}</dd>
              <dt>Conversation</dt>
              <dd>{task.conversationId || 'No label'}</dd>
              {task.continuedFrom && (
                <>
                  <dt>Continued from</dt>
                  <dd>
                    <Link className="mono" to={`/tasks/${encodeURIComponent(task.continuedFrom)}`}>
                      {task.continuedFrom.slice(0, 8)}
                    </Link>
                  </dd>
                </>
              )}
              {actions?.continuation && (
                <>
                  <dt>Continued in</dt>
                  <dd>
                    <Link className="mono" to={`/tasks/${encodeURIComponent(actions.continuation)}`}>
                      {actions.continuation.slice(0, 8)}
                    </Link>
                  </dd>
                </>
              )}
              <dt>Task purpose</dt>
              <dd>General task</dd>
              <dt>Original inference profile</dt>
              <dd>{task.inference?.binding.profileId || 'Legacy or unavailable'}</dd>
              {task.inference?.binding && (
                <>
                  <dt>Provider / model</dt>
                  <dd>
                    {task.inference.binding.providerId} / {task.inference.binding.model}
                  </dd>
                  <dt>Admitted input allowance</dt>
                  <dd>{task.inference.binding.inputAllowance.toLocaleString()} estimated units</dd>
                  <dt>Configured output allowance</dt>
                  <dd>{task.inference.binding.maxOutputTokens.toLocaleString()} tokens</dd>
                </>
              )}
              <dt>Active inference stage</dt>
              <dd>{task.inference?.transition ? 'Cloud continuation' : 'Original provider'}</dd>
              {task.inference?.transition && task.inference.fallback && (
                <>
                  <dt>Cloud provider / model</dt>
                  <dd>
                    {task.inference.fallback.providerId} / {task.inference.fallback.model}
                  </dd>
                  <dt>Continuation reason</dt>
                  <dd>
                    Input allowance exceeded after {task.inference.transition.sourceModelTurns} model turns
                  </dd>
                  <dt>Cloud input allowance</dt>
                  <dd>{task.inference.fallback.inputAllowance.toLocaleString()} estimated units</dd>
                </>
              )}
              {task.partialReporting && (
                <>
                  <dt>Partial answer recovery</dt>
                  <dd>
                    {task.partialReporting.attempted ? 'Reporting turn attempted' : 'Reporting turn pending'}{' '}
                    ({task.partialReporting.reason})
                  </dd>
                </>
              )}
              <dt>Automatic resumption</dt>
              <dd>{task.automaticResumption ? 'Enabled' : 'Disabled'}</dd>
            </dl>
          </section>
          <section className="panel">
            <div className="panel-heading">
              <h3>Recorded usage</h3>
              <Clock3 size={16} />
            </div>
            <dl className="key-values">
              <dt>Input tokens</dt>
              <dd>{task.inputTokens.toLocaleString()}</dd>
              <dt>Output tokens</dt>
              <dd>{task.outputTokens.toLocaleString()}</dd>
              <dt>Total tokens</dt>
              <dd>
                {task.totalTokens.toLocaleString()}
                {!taskDeadlineOnly && ` / ${task.limits.totalTokens.toLocaleString()}`}
              </dd>
              <dt>Estimated cost (USD)</dt>
              <dd>
                <CostEstimate
                  value={usageQuery.data}
                  loading={usageQuery.isPending}
                  error={usageQuery.isError}
                />
              </dd>
              <dt>Active time</dt>
              <dd>
                {Math.round(task.activeMillis / 1000)}s / {Math.round(task.limits.activeMillis / 60000)}m
              </dd>
              <dt>Model turns</dt>
              <dd>
                {task.modelTurns}
                {!taskDeadlineOnly && ` / ${task.limits.modelTurns}`}
              </dd>
              <dt>Tool calls</dt>
              <dd>
                {task.toolCalls}
                {!taskDeadlineOnly && ` / ${task.limits.toolCalls}`}
              </dd>
              <dt>Tool deadline</dt>
              <dd>{taskDeadlineOnly ? 'Remaining task time' : `${task.limits.toolTimeoutMillis / 1000}s`}</dd>
            </dl>
            {task.usageMissing && (
              <div className="notice warning">
                Usage is incomplete. Missing provider usage is not counted as zero.
              </div>
            )}
            <div className="panel-body">
              <button className="button ghost" onClick={() => setTab('cost')}>
                View cost breakdown <ArrowUpRight size={14} />
              </button>
            </div>
          </section>
          {task.contextDiagnostics && Object.keys(task.contextDiagnostics).length > 0 && (
            <section className="panel">
              <div className="panel-heading">
                <h3>Last context admission</h3>
              </div>
              <dl className="key-values">
                <dt>Estimator</dt>
                <dd>{task.contextDiagnostics.estimator || 'Conservative estimate'}</dd>
                <dt>Estimated input</dt>
                <dd>{contextNumber(task.contextDiagnostics.estimated_input_tokens)}</dd>
                <dt>Effective input allowance</dt>
                <dd>{contextNumber(task.contextDiagnostics.effective_input_allowance)}</dd>
                <dt>Output reserve</dt>
                <dd>{contextNumber(task.contextDiagnostics.output_reserve)}</dd>
                <dt>Remaining cumulative tokens</dt>
                <dd>{contextNumber(task.contextDiagnostics.remaining_cumulative_tokens)}</dd>
                <dt>Messages / schemas</dt>
                <dd>
                  {contextNumber(task.contextDiagnostics.message_bytes)} /{' '}
                  {contextNumber(task.contextDiagnostics.schema_bytes)} bytes
                </dd>
                <dt>Instructions / envelope</dt>
                <dd>
                  {contextNumber(task.contextDiagnostics.instruction_bytes)} /{' '}
                  {contextNumber(task.contextDiagnostics.envelope_bytes)} bytes
                </dd>
              </dl>
              <p className="panel-body muted">
                Input sizes are conservative serialized-byte estimates, not provider token counts.
              </p>
            </section>
          )}
          <section className="panel panel-body">
            <h3>
              <Globe2 size={16} />
              Browser activity
            </h3>
            <p className="muted">
              {task.calls.filter((call) => call.name.startsWith('browser_')).length} browser calls recorded.
              Sessions belong to this execution.
            </p>
            <button className="button ghost" onClick={() => setTab('tools')}>
              Inspect observations <ArrowUpRight size={14} />
            </button>
          </section>
        </aside>
      </div>
      {artifact && (
        <Modal title="Execution artifact" onClose={() => setArtifact(undefined)}>
          <ArtifactReader key={`${id}:${artifact}`} taskId={id} artifact={artifact} />
        </Modal>
      )}
      {continuing && <ContinueTask task={task} onClose={() => setContinuing(false)} />}
      {deleting && (
        <Modal title="Delete task" onClose={() => setDeleting(false)}>
          <p>
            Delete this settled task and its execution artifacts? This removes local execution records. This
            action cannot be undone.
          </p>
          <p className="muted">
            Financial usage records are retained, so recorded spending totals will remain.
          </p>
          <p className="muted">Task {task.executionId}</p>
          {remove.isError && <ErrorState error={remove.error} />}
          <div className="modal-actions">
            <button className="button" onClick={() => setDeleting(false)}>
              Keep task
            </button>
            <button
              className="button danger"
              disabled={remove.isPending || !actions?.delete}
              onClick={() => remove.mutate(task.updatedAt)}
            >
              {remove.isPending ? 'Deleting…' : 'Delete task'}
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
function ContinueTask({ task, onClose }: { task: Task; onClose: () => void }) {
  const [text, setText] = useState('');
  const client = useQueryClient();
  const navigate = useNavigate();
  const mutation = useMutation({
    mutationFn: () =>
      api<Task>(
        `/api/agent/executions/${encodeURIComponent(task.executionId)}/continue`,
        json('POST', text.trim() ? { text } : {}),
      ),
    onSuccess: (result) => {
      client.invalidateQueries({ queryKey: ['tasks'] });
      client.invalidateQueries({ queryKey: ['task-actions', task.executionId] });
      navigate(`/tasks/${encodeURIComponent(result.executionId)}`);
    },
  });
  return (
    <Modal title="Continue task" onClose={onClose}>
      <form
        className="stack"
        onSubmit={(event) => {
          event.preventDefault();
          if (!mutation.isPending) mutation.mutate();
        }}
      >
        <p>
          Start a new task that picks up from this one. It receives the original request, your corrections,
          the saved answer and a summary of recent tool results, and gets a fresh time allowance.
        </p>
        <p className="muted">
          Earlier tool calls are not replayed. Check any action marked uncertain before repeating it.
        </p>
        <Field label="Additional instruction" hint="Optional. Leave empty to finish the original request.">
          <textarea
            rows={3}
            placeholder="For example: skip the vendors already checked and write the summary…"
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
        </Field>
        {mutation.isError && <ErrorState error={mutation.error} />}
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Cancel
          </button>
          <button className="button primary" disabled={mutation.isPending}>
            <Play size={15} />
            {mutation.isPending ? 'Starting…' : 'Continue task'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
function contextNumber(value: number | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString() : 'Unavailable';
}
function CircleMark() {
  return <Bot size={16} />;
}
type TaskFileView = {
  file: {
    id: string;
    filename: string;
    mediaType: string;
    byteSize: number;
    createdAt: string;
  };
  documentId?: string | null;
  extractionStatus?: string | null;
};
function TaskFiles({ taskId }: { taskId: string }) {
  const files = useQuery({
    queryKey: ['task-files', taskId],
    queryFn: ({ signal }) =>
      api<TaskFileView[]>(`/api/operator/tasks/${encodeURIComponent(taskId)}/files`, { signal }),
    refetchInterval: 5000,
  });
  if (files.isPending || (files.isSuccess && files.data.length === 0)) return null;
  return (
    <section className="panel panel-body">
      <h3>
        <Paperclip size={16} /> Attached files
      </h3>
      {files.isError ? (
        <ErrorState error={files.error} />
      ) : (
        files.data.map((item) => (
          <article key={item.file.id} className="message">
            <strong>{item.file.filename}</strong>
            <p className="muted">
              {item.file.mediaType} · {item.file.byteSize.toLocaleString()} bytes ·{' '}
              {item.documentId
                ? `opened by the model (${(item.extractionStatus ?? 'unknown').replaceAll('_', ' ')})`
                : 'not opened by the model yet'}
            </p>
            <a
              className="button"
              download
              href={`/api/operator/tasks/${encodeURIComponent(taskId)}/files/${encodeURIComponent(item.file.id)}/download`}
            >
              Download original
            </a>
          </article>
        ))
      )}
    </section>
  );
}
type ReportView = {
  report: {
    id: string;
    filename: string;
    mediaType: string;
    byteSize: number;
    checksum: string;
    createdAt: string;
    goalGeneration: number;
    coverage: string;
  };
  staged: boolean;
  currentGeneration: boolean;
  deliveryStatus: string;
  deliveryFailure?: string;
};

function TaskReports({ taskId }: { taskId: string }) {
  const reports = useQuery({
    queryKey: ['task-reports', taskId],
    queryFn: ({ signal }) =>
      api<ReportView[]>(`/api/operator/tasks/${encodeURIComponent(taskId)}/reports`, { signal }),
    refetchInterval: 3000,
  });
  return (
    <section className="panel panel-body">
      <h3>
        <FileText size={16} /> Saved reports and files
      </h3>
      {reports.isError ? (
        <ErrorState error={reports.error} />
      ) : reports.isPending ? (
        <Loading />
      ) : reports.data.length === 0 ? (
        <p className="muted">No reports or files created for this task.</p>
      ) : (
        reports.data.map((item) => (
          <article key={item.report.id} className="message">
            <strong>{item.report.filename}</strong>
            <p className="muted">
              {item.report.byteSize.toLocaleString()} bytes · goal {item.report.goalGeneration}
              {item.currentGeneration ? '' : ' · superseded'} · {item.staged ? 'staged' : 'saved'}
            </p>
            <p>{item.report.coverage}</p>
            <p>Delivery: {item.deliveryStatus.replaceAll('_', ' ')}</p>
            {item.deliveryFailure && <p className="notice warning">{item.deliveryFailure}</p>}
            <a
              className="button"
              download
              href={`/api/operator/tasks/${encodeURIComponent(taskId)}/reports/${encodeURIComponent(item.report.id)}/download`}
            >
              Download
            </a>
          </article>
        ))
      )}
    </section>
  );
}
function AnswerForm({
  task,
  canSubmit,
  onSaved,
  onConflict,
}: {
  task: Task;
  canSubmit: boolean;
  onSaved: (task: Task) => void;
  onConflict: () => void;
}) {
  const [answers, setAnswers] = useState(task.pendingQuestions.map(() => ''));
  const [revision, setRevision] = useState(task.revision);
  const mutation = useMutation({
    mutationFn: () =>
      api<Task>(
        `/api/agent/executions/${encodeURIComponent(task.executionId)}/answers`,
        json('POST', { answers, expectedRevision: revision }),
      ),
    onSuccess: onSaved,
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) onConflict();
    },
  });
  return (
    <form
      className="stack answer-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (
          canSubmit &&
          !mutation.isPending &&
          !(mutation.error instanceof ApiError && mutation.error.status === 409)
        )
          mutation.mutate();
      }}
    >
      <h3>Answer questions</h3>
      {task.pendingQuestions.map((question, index) => (
        <Field key={question.id} label={question.text}>
          <textarea
            required
            rows={2}
            value={answers[index] ?? ''}
            onChange={(event) =>
              setAnswers((previous) => previous.map((value, i) => (i === index ? event.target.value : value)))
            }
          />
        </Field>
      ))}
      {mutation.isError && <ErrorState error={mutation.error} />}{' '}
      {mutation.error instanceof ApiError && mutation.error.status === 409 && (
        <button
          className="button"
          type="button"
          disabled={!canSubmit || task.revision === revision}
          onClick={() => {
            setRevision(task.revision);
            mutation.reset();
          }}
        >
          I reviewed the latest questions · use revision {task.revision}
        </button>
      )}
      <div className="row-end">
        <small className="muted">Answers supply information · revision {revision}</small>
        <button
          className="button primary"
          disabled={
            !canSubmit ||
            mutation.isPending ||
            answers.some((answer) => !answer.trim()) ||
            (mutation.error instanceof ApiError && mutation.error.status === 409)
          }
        >
          <Send size={15} />
          Send answers
        </button>
      </div>
    </form>
  );
}
function ApprovalPanel({
  task,
  canDecide,
  onSaved,
  onConflict,
}: {
  task: Task;
  canDecide: boolean;
  onSaved: (task: Task) => void;
  onConflict: () => void;
}) {
  const pending = task.calls.filter(awaitingApproval);
  const mutation = useMutation({
    mutationFn: ({ callId, approved }: { callId: string; approved: boolean }) =>
      api<Task>(
        `/api/agent/executions/${encodeURIComponent(task.executionId)}/approvals`,
        json('POST', { callId, approved, expectedRevision: task.revision }),
      ),
    onSuccess: onSaved,
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) onConflict();
    },
  });
  return (
    <div className="questions approvals" role="region" aria-label="Approval required">
      <h3>
        <Check size={18} />
        Approval required
      </h3>
      <p className="muted">
        Velocity wants to run {pending.length === 1 ? 'an action' : `${pending.length} actions`} that can
        change something outside this system or your saved data. Nothing runs until you decide. Declining is
        final for this task.
      </p>
      {pending.map((call) => (
        <article className="approval-card" key={call.id}>
          <div className="tool-heading">
            <Wrench size={16} />
            <strong>{call.name}</strong>
            <Badge status="waiting_for_user">Pending</Badge>
            {call.approval?.requestedAt && (
              <small className="muted">{relativeTime(call.approval.requestedAt)}</small>
            )}
          </div>
          {call.approval?.summary && (
            <pre className="approval-summary" aria-label="What this action will do">
              {call.approval.summary}
            </pre>
          )}
          <JsonView value={call.input} label="Arguments the tool will receive" />
          <div className="row-end">
            <button
              type="button"
              className="button"
              disabled={!canDecide || mutation.isPending}
              onClick={() => mutation.mutate({ callId: call.id, approved: false })}
            >
              Decline
            </button>
            <button
              type="button"
              className="button primary"
              disabled={!canDecide || mutation.isPending}
              onClick={() => mutation.mutate({ callId: call.id, approved: true })}
            >
              <Check size={15} />
              Approve and run
            </button>
          </div>
        </article>
      ))}
      {mutation.isError && <ErrorState error={mutation.error} />}
      {!canDecide && (
        <p className="muted fine-print">Only the owning account can decide, once the task is settled.</p>
      )}
    </div>
  );
}
function SteerForm({
  task,
  canSubmit,
  onSaved,
}: {
  task: Task;
  canSubmit: boolean;
  onSaved: (task: Task) => void;
}) {
  const [text, setText] = useState('');
  const mutation = useMutation({
    mutationFn: () =>
      api<Task>(
        `/api/agent/executions/${encodeURIComponent(task.executionId)}/updates`,
        json('POST', { text }),
      ),
    onSuccess: (result) => {
      onSaved(result);
      setText('');
    },
  });
  return (
    <form
      className="steer-form stack"
      onSubmit={(event) => {
        event.preventDefault();
        if (canSubmit && !mutation.isPending) mutation.mutate();
      }}
    >
      <Field
        label="Guide this task"
        hint="An update can change direction while the task is running or waiting."
      >
        <textarea
          rows={3}
          placeholder="Add context or correct the goal…"
          value={text}
          onChange={(event) => setText(event.target.value)}
          required
        />
      </Field>
      {mutation.isError && <ErrorState error={mutation.error} />}
      <button className="button align-end" disabled={!canSubmit || mutation.isPending || !text.trim()}>
        <Send size={15} />
        Send update
      </button>
    </form>
  );
}
function ToolActivity({
  call,
  index,
  onArtifact,
}: {
  call: ToolCall;
  index: number;
  onArtifact: (name: string) => void;
}) {
  const browser = call.name.startsWith('browser_');
  let observation: Record<string, unknown> | undefined;
  if (browser && call.settlement?.preview) {
    try {
      const value = JSON.parse(call.settlement.preview);
      if (value && typeof value === 'object') observation = value;
    } catch {
      /* A truncated observation remains visible as plain text. */
    }
  }
  return (
    <article className="tool-card">
      <div className="tool-heading">
        <span className="tool-order">{index + 1}</span>
        {browser ? <Globe2 size={17} /> : <Wrench size={17} />}
        <strong>{call.name}</strong>
        <Badge status={call.settlement?.error ? 'failed' : call.state} />
        {call.approval && (
          <Badge
            status={
              call.approval.state === 'approved'
                ? 'succeeded'
                : call.approval.state === 'declined'
                  ? 'blocked'
                  : 'waiting_for_user'
            }
          >
            {call.approval.state === 'pending'
              ? 'Awaiting approval'
              : call.approval.state === 'approved'
                ? 'Approved'
                : 'Declined'}
          </Badge>
        )}
      </div>
      {call.progress && <p className="tool-progress">{call.progress}</p>}
      {call.inputError && <p className="notice error">{call.inputError}</p>}
      <JsonView value={call.input} label="Input" />
      {call.settlement && (
        <>
          <div className="tool-outcome">
            <strong>Outcome</strong>
            <span>{call.settlement.outcome}</span>
            <small>{formatDate(call.settlement.confirmedAt)}</small>
          </div>
          {observation ? (
            <BrowserObservation value={observation} />
          ) : (
            call.settlement.preview && <pre className="tool-preview">{call.settlement.preview}</pre>
          )}
          {call.settlement.artifact && (
            <button className="button" onClick={() => onArtifact(call.settlement!.artifact!)}>
              <FileText size={15} />
              Read full artifact
            </button>
          )}
        </>
      )}
    </article>
  );
}
function BrowserObservation({ value }: { value: Record<string, unknown> }) {
  const content = Array.isArray(value.content)
    ? value.content.filter(
        (item): item is { type: 'text'; text: string } =>
          !!item && typeof item === 'object' && item.type === 'text' && typeof item.text === 'string',
      )
    : [];
  return (
    <div className="browser-observation">
      <div className="browser-bar">
        <Globe2 size={16} />
        <strong>Browser result</strong>
      </div>
      {typeof value.text === 'string' && <pre>{value.text}</pre>}
      {content.map((item, index) => (
        <pre key={index}>{item.text}</pre>
      ))}
      <JsonView value={value} label="Complete available browser result" />
    </div>
  );
}
export function ArtifactReader({ taskId, artifact }: { taskId: string; artifact: string }) {
  const [chunks, setChunks] = useState<string[]>([]);
  const [complete, setComplete] = useState(false);
  const offset = chunks.reduce((total, chunk) => total + chunk.length, 0);
  const mutation = useMutation({
    mutationFn: () =>
      api<string>(
        `/api/agent/executions/${encodeURIComponent(taskId)}/artifacts/${encodeURIComponent(artifact)}?offset=${offset}&limit=24576`,
        { headers: { Accept: 'text/plain' } },
      ),
    onSuccess: (chunk) => {
      const next = artifactChunk(chunk);
      setChunks((previous) => [...previous, next.text]);
      setComplete(next.complete);
    },
  });
  return (
    <div className="stack">
      <p className="muted">
        {artifact} · {offset.toLocaleString()} UTF-16 characters loaded. Content is displayed as text.
      </p>
      {chunks.length > 0 && <pre className="artifact-content">{chunks.join('')}</pre>}
      {mutation.isError && <ErrorState error={mutation.error} />}
      <button className="button" disabled={complete || mutation.isPending} onClick={() => mutation.mutate()}>
        {complete
          ? 'End of artifact'
          : mutation.isPending
            ? 'Reading…'
            : offset === 0
              ? 'Load first chunk'
              : 'Load next chunk (up to 24,576 characters)'}
      </button>
    </div>
  );
}
