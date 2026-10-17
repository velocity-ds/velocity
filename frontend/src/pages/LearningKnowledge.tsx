import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, json } from '../api';
import BundledSkills from './BundledSkills';
import {
  Badge,
  EmptyState,
  ErrorState,
  Field,
  JsonView,
  Loading,
  PageHeader,
  formatDate,
} from '../components';

type FactOperation = { action: 'add' | 'replace' | 'remove'; entry_id?: string; content?: string };
type FactEntry = {
  id: string;
  text: string;
  evidence_kind: string;
  source_execution_id: string;
  source_message_id: string;
  updated_at: string;
};
type Proposal = {
  id: string;
  expected_revision: number;
  operations: FactOperation[];
  source_execution_id: string;
  source_message_id: string;
  review_id: string;
  created_at: string;
};
export type FactStore = {
  target: 'user' | 'environment' | 'profile';
  revision: number;
  enabled: boolean;
  feature_enabled: boolean;
  entries: FactEntry[];
  proposals: Proposal[];
  used_chars: number;
  max_chars: number;
  remaining_chars: number;
  counting: string;
};
type Skill = {
  id: string;
  name: string;
  routing_description: string;
  revision: number;
  author_kind: string;
  enabled: boolean;
  pinned: boolean;
  managed_by_agent: boolean;
  automatic_edit_allowed: boolean;
  source_execution_id: string;
  updated_at: string;
  load_count: number;
  patch_count: number;
  last_loaded_at: string | null;
  last_patched_at: string | null;
  reused_after_patch: boolean;
};
type SkillProposal = {
  id: string;
  skill_id: string;
  action: string;
  reason: string;
  review_id: string;
  expected_revision: number;
  created_at: string;
};
type JourneyEvent = {
  kind: 'fact' | 'profile' | 'skill_created' | 'skill_updated' | 'review';
  at: string;
  target?: string;
  entry_id?: string;
  text?: string;
  evidence_kind?: string;
  skill_id?: string;
  name?: string;
  revision?: number;
  author_kind?: string;
  enabled?: boolean;
  load_count?: number;
  reused_after_patch?: boolean;
  review_id?: string;
  mode?: string;
  outcome?: string;
  tool_calls?: number;
  total_tokens?: number;
  source_execution_id?: string | null;
  source_message_id?: string;
};
type SkillLoad = {
  skill: Skill;
  loaded_revision: number;
  core_instructions: string;
  references: { id: string; name: string; revision: number; chars: number }[];
  versions: { revision: number; updated_at: string; source_execution_id: string; author_kind: string }[];
};
type MutationResult = { status: string; database_committed: boolean; message?: string };

function useLearningMutation(refresh: () => Promise<unknown>) {
  const [result, setResult] = useState<MutationResult | null>(null);
  const mutation = useMutation({
    mutationFn: ({ path, body }: { path: string; body: Record<string, unknown> }) =>
      api<MutationResult>(path, json('POST', { ...body, request_id: crypto.randomUUID() })),
    onSuccess: async (value) => {
      setResult(value);
      await refresh();
    },
    retry: false,
  });
  async function reload() {
    await refresh();
    mutation.reset();
    setResult(null);
  }
  return { ...mutation, result, reload };
}
function MutationStatus({ mutation }: { mutation: ReturnType<typeof useLearningMutation> }) {
  return (
    <>
      {mutation.result && (
        <p role="status" className="notice">
          {mutation.result.database_committed
            ? 'Saved.'
            : mutation.result.status === 'unchanged'
              ? 'No change was needed.'
              : `No committed change: ${mutation.result.status}.`}
        </p>
      )}
      {mutation.error && (
        <div className="stack">
          <ErrorState error={mutation.error} />
          <p className="notice warning">
            Your draft is preserved. Reload and review the current saved revision before another write. A lost
            connection may mean the write committed.
          </p>
          <button className="button" type="button" onClick={() => void mutation.reload()}>
            Reload current state
          </button>
        </div>
      )}
    </>
  );
}
export function FactsView() {
  const query = useQuery({
    queryKey: ['learning-facts'],
    queryFn: () => api<{ stores: FactStore[] }>('/api/operator/memory/inspect', json('POST', {})),
    retry: false,
  });
  return (
    <div className="stack">
      <PageHeader
        title="Facts"
        description="Compact preferences and stable environment facts for future tasks."
      />
      <p className="notice">
        Facts belong to this local operator scope. Disabling a store stops new writes and loading into new
        tasks. Existing tasks may still contain their earlier frozen context.
      </p>
      {query.isPending ? (
        <Loading label="Loading facts" />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        query.data.stores.map((store) => (
          <FactStoreCard key={store.target} store={store} refresh={() => query.refetch()} />
        ))
      )}
      <LearningReviews />
    </div>
  );
}
function FactStoreCard({ store, refresh }: { store: FactStore; refresh: () => Promise<unknown> }) {
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const mutation = useLearningMutation(refresh);
  const blocked = mutation.isPending || !!mutation.error;
  function update(operations: FactOperation[]) {
    mutation.mutate(
      {
        path: '/api/operator/memory/update',
        body: { target: store.target, expected_revision: store.revision, operations },
      },
      {
        onSuccess: (value) => {
          if (value.database_committed || value.status === 'unchanged') {
            setEditing(null);
            setDraft('');
          }
        },
      },
    );
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    update([
      { action: editing ? 'replace' : 'add', ...(editing ? { entry_id: editing } : {}), content: draft },
    ]);
  }
  return (
    <section className="panel stack">
      <div className="toolbar">
        <h2>
          {store.target === 'user'
            ? 'User preferences'
            : store.target === 'environment'
              ? 'Environment facts'
              : 'Derived user profile'}
        </h2>
        <Badge>{store.enabled && store.feature_enabled ? 'Enabled' : 'Disabled'}</Badge>
        <button
          className="button"
          disabled={blocked || !store.feature_enabled}
          onClick={() =>
            mutation.mutate({
              path: '/api/operator/memory/control',
              body: { target: store.target, expected_revision: store.revision, enabled: !store.enabled },
            })
          }
        >
          {store.enabled ? 'Disable' : 'Enable'} {store.target} facts
        </button>
      </div>
      <p>
        {store.used_chars.toLocaleString()} / {store.max_chars.toLocaleString()} characters used ·{' '}
        {store.remaining_chars.toLocaleString()} remaining
      </p>
      <progress aria-label={`${store.target} fact capacity`} value={store.used_chars} max={store.max_chars} />
      <p className="muted">
        Capacity counts UTF-16 code units, including entry delimiters. Facts are never silently evicted.
      </p>
      {!store.feature_enabled && (
        <p className="notice">Fact loading and writes are disabled in server configuration.</p>
      )}
      {store.entries.length === 0 ? (
        <EmptyState
          title="No facts saved"
          description="Save supported preferences or stable facts, without credentials or temporary progress notes."
        />
      ) : (
        store.entries.map((entry) => (
          <article className="panel stack" key={entry.id}>
            <p>{entry.text}</p>
            <div className="toolbar">
              <button
                className="button"
                disabled={blocked || !store.enabled || !store.feature_enabled}
                onClick={() => {
                  setEditing(entry.id);
                  setDraft(entry.text);
                }}
              >
                Correct
              </button>
              <button
                className="button"
                disabled={blocked || !store.enabled || !store.feature_enabled}
                onClick={() => update([{ action: 'remove', entry_id: entry.id }])}
              >
                Remove
              </button>
            </div>
            <JsonView
              label="Fact provenance"
              value={{
                evidence: entry.evidence_kind,
                execution: entry.source_execution_id,
                message: entry.source_message_id,
                updated: entry.updated_at,
                id: entry.id,
              }}
            />
          </article>
        ))
      )}
      <form className="stack" onSubmit={submit}>
        <Field label={editing ? 'Correct this fact' : 'New fact'}>
          <textarea
            required
            value={draft}
            maxLength={store.max_chars}
            rows={3}
            onChange={(event) => setDraft(event.target.value)}
          />
        </Field>
        <div className="toolbar">
          <button
            className="button primary"
            disabled={blocked || !draft.trim() || !store.enabled || !store.feature_enabled}
          >
            {editing ? 'Save correction' : 'Save fact'}
          </button>
          {editing && (
            <button
              className="button"
              type="button"
              onClick={() => {
                setEditing(null);
                setDraft('');
              }}
            >
              Cancel correction
            </button>
          )}
        </div>
      </form>
      <MutationStatus mutation={mutation} />
      {store.proposals.length > 0 && (
        <section className="stack">
          <h3>Pending changes</h3>
          <p>Background proposals have not changed your facts. Applying rechecks the original revision.</p>
          {store.proposals.map((proposal) => (
            <article key={proposal.id} className="panel stack">
              {proposal.operations.map((operation, index) => (
                <p key={index}>
                  {operation.action}:{' '}
                  {operation.content ??
                    store.entries.find((entry) => entry.id === operation.entry_id)?.text ??
                    operation.entry_id}
                </p>
              ))}
              <JsonView
                label="Proposal provenance"
                value={{
                  revision: proposal.expected_revision,
                  execution: proposal.source_execution_id,
                  message: proposal.source_message_id,
                  review: proposal.review_id,
                  created_at: proposal.created_at,
                }}
              />
              <div className="toolbar">
                {[true, false].map((apply) => (
                  <button
                    key={String(apply)}
                    className="button"
                    disabled={blocked || (apply && (!store.enabled || !store.feature_enabled))}
                    onClick={() =>
                      mutation.mutate({
                        path: '/api/operator/memory/proposal',
                        body: { target: store.target, proposal_id: proposal.id, apply },
                      })
                    }
                  >
                    {apply ? 'Apply proposal' : 'Reject proposal'}
                  </button>
                ))}
              </div>
            </article>
          ))}
        </section>
      )}
    </section>
  );
}

export function SkillsView() {
  const client = useQueryClient();
  const [selected, setSelected] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [browsingBundles, setBrowsingBundles] = useState(false);
  const [offset, setOffset] = useState(0);
  const query = useQuery({
    queryKey: ['learning-skills', offset],
    queryFn: () =>
      api<{
        skills: Skill[];
        feature_enabled: boolean;
        has_more: boolean;
        next_offset: number;
        proposals: SkillProposal[];
      }>('/api/operator/skills/list', json('POST', { offset })),
    retry: false,
  });
  async function refresh() {
    await client.invalidateQueries({ queryKey: ['learning-skills'] });
  }
  const decision = useLearningMutation(refresh);
  return (
    <div className="stack">
      <PageHeader
        title="Skills"
        description="Reusable procedures with complete core instructions and inspectable revisions."
        actions={
          <div className="toolbar">
            <button
              className="button"
              aria-expanded={browsingBundles}
              onClick={() => setBrowsingBundles(!browsingBundles)}
            >
              Browse built-in skills
            </button>
            <button
              className="button primary"
              disabled={!query.data?.feature_enabled}
              onClick={() => {
                setCreating(true);
                setSelected(null);
              }}
            >
              Create skill
            </button>
          </div>
        }
      />
      <p className="notice">
        Skills are contextual data. They do not add tools or grant permissions. User-authored skills are
        protected from automatic edits until explicitly opted in.
      </p>
      {browsingBundles && (
        <BundledSkills
          onInstalled={async (id) => {
            await refresh();
            await client.invalidateQueries({ queryKey: ['learning-skill', id] });
            setSelected(id);
            setCreating(false);
          }}
        />
      )}
      {query.isPending ? (
        <Loading label="Loading skills" />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <>
          {!query.data.feature_enabled && (
            <p className="notice">
              Skill loading and writes are disabled in server configuration. Saved versions remain
              inspectable.
            </p>
          )}
          {query.data.skills.length ? (
            <div className="grid-2">
              {query.data.skills.map((skill) => (
                <article className="panel stack" key={skill.id}>
                  <button
                    className="text-button"
                    onClick={() => {
                      setSelected(skill.id);
                      setCreating(false);
                    }}
                  >
                    {skill.name}
                  </button>
                  <p>{skill.routing_description}</p>
                  <div className="toolbar">
                    <Badge>{skill.enabled ? 'Enabled' : 'Disabled'}</Badge>
                    <Badge>{skill.automatic_edit_allowed ? 'Agent managed' : 'Protected'}</Badge>
                    {skill.pinned && <Badge>Pinned</Badge>}
                  </div>
                  <small>
                    Revision {skill.revision} · {formatDate(skill.updated_at)}
                  </small>
                  <small className="muted">
                    Loaded by tasks {skill.load_count} times · patched {skill.patch_count} times
                    {skill.reused_after_patch ? ' · reused since last patch' : ''}
                  </small>
                </article>
              ))}
            </div>
          ) : (
            <EmptyState
              title="No skills saved"
              description="Use a task to learn a workflow, or create a reusable procedure here."
            />
          )}
          {query.data.proposals.length > 0 && (
            <section className="panel stack">
              <h2>Retirement proposals</h2>
              <p className="muted">
                The weekly curator proposed retiring these agent-managed skills. Applying disables the skill;
                its versions stay inspectable and it can be re-enabled later.
              </p>
              {query.data.proposals.map((proposal) => {
                const skill = query.data.skills.find((entry) => entry.id === proposal.skill_id);
                return (
                  <article key={proposal.id} className="panel stack">
                    <strong>{skill ? skill.name : proposal.skill_id}</strong>
                    <p>{proposal.reason}</p>
                    <small className="muted">Proposed {formatDate(proposal.created_at)}</small>
                    <div className="toolbar">
                      {[true, false].map((apply) => (
                        <button
                          key={String(apply)}
                          className={`button ${apply ? 'primary' : ''}`}
                          disabled={decision.isPending || !!decision.error}
                          onClick={() =>
                            decision.mutate({
                              path: '/api/operator/skills/proposal',
                              body: { proposal_id: proposal.id, apply },
                            })
                          }
                        >
                          {apply ? 'Retire skill' : 'Keep skill'}
                        </button>
                      ))}
                    </div>
                  </article>
                );
              })}
              <MutationStatus mutation={decision} />
            </section>
          )}
          {(offset > 0 || query.data.has_more) && (
            <div className="toolbar">
              <button className="button" disabled={offset === 0} onClick={() => setOffset(0)}>
                First skill page
              </button>
              <button
                className="button"
                disabled={!query.data.has_more}
                onClick={() => setOffset(query.data.next_offset)}
              >
                Next skill page
              </button>
            </div>
          )}
          {creating && (
            <SkillEditor
              featureEnabled={query.data.feature_enabled}
              refresh={refresh}
              onClose={() => setCreating(false)}
            />
          )}
          {selected && (
            <SkillDetail
              key={selected}
              id={selected}
              featureEnabled={query.data.feature_enabled}
              refresh={refresh}
            />
          )}
        </>
      )}
      <LearningReviews />
    </div>
  );
}

export function JourneyView() {
  const query = useQuery({
    queryKey: ['learning-journey'],
    queryFn: () =>
      api<{ events: JourneyEvent[]; truncated: boolean; learning_enabled: boolean }>(
        '/api/operator/learning/journey',
      ),
    retry: false,
  });
  return (
    <div className="stack">
      <PageHeader
        title="Learning journey"
        description="What the agent has learned, newest first: saved facts, skill versions and the reviews that produced them."
        actions={
          <button className="button" onClick={() => void query.refetch()} disabled={query.isFetching}>
            Refresh
          </button>
        }
      />
      <p className="notice">
        Entries are contextual data the agent may use in future tasks. Correct or remove them under Facts and
        Skills; this view never changes anything.
      </p>
      {query.isPending ? (
        <Loading label="Loading learning journey" />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : query.data.events.length === 0 ? (
        <EmptyState
          title="Nothing learned yet"
          description="Facts and skills saved by tasks, the console or background reviews appear here."
        />
      ) : (
        <section className="panel stack">
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>When</th>
                  <th>Event</th>
                  <th>Detail</th>
                  <th>Provenance</th>
                </tr>
              </thead>
              <tbody>
                {query.data.events.map((event, index) => (
                  <tr key={`${event.kind}-${event.entry_id ?? event.skill_id ?? event.review_id}-${index}`}>
                    <td>{formatDate(event.at)}</td>
                    <td>
                      {event.kind === 'fact'
                        ? `Fact · ${event.target}`
                        : event.kind === 'profile'
                          ? 'Derived profile'
                          : event.kind === 'skill_created'
                            ? `Skill created · revision ${event.revision}`
                            : event.kind === 'skill_updated'
                              ? `Skill updated · revision ${event.revision}`
                              : `Review · ${event.mode}`}
                    </td>
                    <td>
                      {event.kind === 'fact' || event.kind === 'profile' ? (
                        event.text
                      ) : event.kind === 'review' ? (
                        <>
                          {event.outcome} · {event.tool_calls} calls ·{' '}
                          {(event.total_tokens ?? 0).toLocaleString()} tokens
                        </>
                      ) : (
                        <>
                          {event.name}
                          <div className="muted">
                            {event.enabled ? 'Enabled' : 'Disabled'} · loaded by tasks {event.load_count}{' '}
                            times
                            {event.reused_after_patch ? ' · reused since last patch' : ''}
                          </div>
                        </>
                      )}
                    </td>
                    <td className="muted">
                      {event.author_kind ? `${event.author_kind} · ` : ''}
                      {event.evidence_kind ? `${event.evidence_kind} · ` : ''}
                      <span className="mono">{event.source_execution_id ?? 'unknown execution'}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {query.data.truncated && <p className="muted">Showing the 200 most recent events.</p>}
        </section>
      )}
    </div>
  );
}

function LearningReviews() {
  const query = useQuery({
    queryKey: ['learning-reviews'],
    queryFn: () =>
      api<{
        enabled: boolean;
        user_turns?: number;
        tool_iterations?: number;
        reviewed_user_turns?: number;
        reviewed_tool_iterations?: number;
        reviews: {
          id: string;
          mode: string;
          status: string;
          outcome: string;
          failure_detail: string;
          advanced_watermark: boolean;
          created_at: string;
          completed_at: string;
          total_tokens: number;
          model_turns: number;
          tool_calls: number;
          usage_missing: boolean;
        }[];
      }>('/api/operator/learning'),
    retry: false,
  });
  return (
    <section className="panel stack">
      <h2>Background learning</h2>
      {query.isPending ? (
        <Loading label="Loading review status" />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : !query.data.enabled ? (
        <p>
          Background reflection is disabled. Explicit fact and skill changes remain controlled by their own
          feature settings.
        </p>
      ) : (
        <>
          <p>
            {query.data.user_turns ?? 0} acknowledged user turns · {query.data.tool_iterations ?? 0} completed
            tool batches
          </p>
          <p className="muted">
            Reviews use successfully acknowledged interactions. A completed response alone does not prove a
            procedure succeeded.
          </p>
          {query.data.reviews.length === 0 ? (
            <p>No reviews queued.</p>
          ) : (
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Review</th>
                    <th>Status</th>
                    <th>Outcome</th>
                    <th>Usage</th>
                  </tr>
                </thead>
                <tbody>
                  {query.data.reviews.map((review) => (
                    <tr key={review.id}>
                      <td>
                        {review.mode}
                        <div className="muted">{formatDate(review.created_at)}</div>
                      </td>
                      <td>{review.status}</td>
                      <td>
                        {review.outcome || 'Pending'}
                        {review.failure_detail ? <div className="muted">{review.failure_detail}</div> : null}
                        {review.advanced_watermark ? (
                          <div className="muted">
                            Cadence advanced past these inputs after repeated failures.
                          </div>
                        ) : null}
                      </td>
                      <td>
                        {review.total_tokens.toLocaleString()} tokens
                        {review.usage_missing ? ' (incomplete)' : ''} · {review.model_turns} requests ·{' '}
                        {review.tool_calls} calls
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <button className="button" onClick={() => void query.refetch()}>
            Refresh review status
          </button>
        </>
      )}
    </section>
  );
}
function SkillDetail({
  id,
  featureEnabled,
  refresh,
}: {
  id: string;
  featureEnabled: boolean;
  refresh: () => Promise<unknown>;
}) {
  const [revision, setRevision] = useState<number | undefined>();
  const [editing, setEditing] = useState(false);
  const query = useQuery({
    queryKey: ['learning-skill', id, revision],
    queryFn: () =>
      api<SkillLoad>('/api/operator/skills/load', json('POST', { id, ...(revision ? { revision } : {}) })),
    retry: false,
  });
  async function reload() {
    await query.refetch();
    await refresh();
  }
  const mutation = useLearningMutation(reload);
  if (query.isPending) return <Loading label="Loading complete skill instructions" />;
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />;
  const data = query.data,
    skill = data.skill;
  function control(body: Record<string, unknown>) {
    mutation.mutate({
      path: '/api/operator/skills/control',
      body: { id, expected_revision: skill.revision, ...body },
    });
  }
  return (
    <section className="panel stack">
      <h2>{skill.name}</h2>
      <Field label="Inspect version">
        <select
          value={revision ?? ''}
          onChange={(event) => {
            setRevision(event.target.value ? Number(event.target.value) : undefined);
            setEditing(false);
          }}
        >
          <option value="">Current revision {skill.revision}</option>
          {data.versions
            .filter((version) => version.revision !== skill.revision)
            .map((version) => (
              <option key={version.revision} value={version.revision}>
                Revision {version.revision} · {formatDate(version.updated_at)}
              </option>
            ))}
        </select>
      </Field>
      <pre className="content-json">{data.core_instructions}</pre>
      <p className="muted">
        Complete core loaded at revision {data.loaded_revision}. Author: {skill.author_kind}.{' '}
        {skill.automatic_edit_allowed
          ? 'Automatic edits are allowed after a fresh read.'
          : 'Automatic edits are blocked by the current protection settings.'}
      </p>
      {data.references.map((reference) => (
        <SkillReference
          key={`${data.loaded_revision}:${reference.id}`}
          skillId={id}
          revision={data.loaded_revision}
          reference={reference}
        />
      ))}
      <JsonView
        label="Skill provenance and versions"
        value={{ source_execution_id: skill.source_execution_id, versions: data.versions }}
      />
      {!revision && (
        <div className="toolbar">
          <button
            className="button"
            disabled={!featureEnabled || !skill.enabled}
            onClick={() => setEditing(!editing)}
          >
            Edit core instructions
          </button>
          <button
            className="button"
            disabled={!featureEnabled || mutation.isPending || !!mutation.error}
            onClick={() => control({ enabled: !skill.enabled })}
          >
            {skill.enabled ? 'Disable skill' : 'Enable skill'}
          </button>
          <button
            className="button"
            disabled={!featureEnabled || mutation.isPending || !!mutation.error}
            onClick={() => control({ pinned: !skill.pinned })}
          >
            {skill.pinned ? 'Unpin skill' : 'Pin skill'}
          </button>
          <button
            className="button"
            disabled={!featureEnabled || mutation.isPending || !!mutation.error}
            onClick={() => control({ managed_by_agent: !skill.managed_by_agent })}
          >
            {skill.managed_by_agent ? 'Stop automatic management' : 'Opt in to automatic management'}
          </button>
        </div>
      )}
      <MutationStatus mutation={mutation} />
      {editing && (
        <SkillEditor
          initial={data}
          featureEnabled={featureEnabled}
          refresh={reload}
          onClose={() => setEditing(false)}
        />
      )}
    </section>
  );
}
function SkillReference({
  skillId,
  revision,
  reference,
}: {
  skillId: string;
  revision: number;
  reference: SkillLoad['references'][number];
}) {
  const [open, setOpen] = useState(false);
  const query = useQuery({
    queryKey: ['skill-reference', skillId, revision, reference.id],
    queryFn: () =>
      api<{ content: string }>(
        '/api/operator/skills/reference',
        json('POST', { id: skillId, revision, reference_id: reference.id }),
      ),
    enabled: open,
    retry: false,
  });
  return (
    <div className="stack">
      <button className="text-button" aria-expanded={open} onClick={() => setOpen(!open)}>
        {reference.name} · reference revision {reference.revision}
      </button>
      {open &&
        (query.isPending ? (
          <Loading label="Loading reference" />
        ) : query.isError ? (
          <ErrorState error={query.error} />
        ) : (
          <pre className="content-json">{query.data.content}</pre>
        ))}
    </div>
  );
}
function SkillEditor({
  initial,
  featureEnabled,
  refresh,
  onClose,
}: {
  initial?: SkillLoad;
  featureEnabled: boolean;
  refresh: () => Promise<unknown>;
  onClose: () => void;
}) {
  const [name, setName] = useState(initial?.skill.name ?? '');
  const [routing, setRouting] = useState(initial?.skill.routing_description ?? '');
  const [core, setCore] = useState(initial?.core_instructions ?? '');
  const mutation = useLearningMutation(refresh);
  function submit(event: FormEvent) {
    event.preventDefault();
    mutation.mutate(
      {
        path: `/api/operator/skills/${initial ? 'patch' : 'create'}`,
        body: {
          name,
          routing_description: routing,
          core_instructions: core,
          ...(initial ? { id: initial.skill.id, expected_revision: initial.skill.revision } : {}),
        },
      },
      {
        onSuccess: (value) => {
          if (value.database_committed) onClose();
        },
      },
    );
  }
  return (
    <form className="panel stack" onSubmit={submit}>
      <h3>{initial ? 'Update skill' : 'Create skill'}</h3>
      <Field label="Skill name">
        <input required value={name} maxLength={100} onChange={(event) => setName(event.target.value)} />
      </Field>
      <Field label="When to use this skill">
        <textarea
          required
          rows={2}
          value={routing}
          maxLength={300}
          onChange={(event) => setRouting(event.target.value)}
        />
      </Field>
      <Field
        label="Core instructions"
        hint="Include triggers, prerequisites, procedure, decision points, pitfalls and verification. Keep temporary incidents in task history."
      >
        <textarea
          required
          rows={12}
          value={core}
          maxLength={6000}
          onChange={(event) => setCore(event.target.value)}
        />
      </Field>
      <p className="muted">
        {core.length.toLocaleString()} / 6,000 UTF-16 characters. Creation is user-owned and protected from
        automatic edits.
      </p>
      <MutationStatus mutation={mutation} />
      <div className="toolbar">
        <button
          className="button primary"
          disabled={!featureEnabled || mutation.isPending || !!mutation.error}
        >
          Save skill
        </button>
        <button className="button" type="button" onClick={onClose}>
          Close editor
        </button>
      </div>
    </form>
  );
}
