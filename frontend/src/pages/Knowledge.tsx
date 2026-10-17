import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { Database, Plus, RefreshCw, Search } from 'lucide-react';
import { api, ApiError, json } from '../api';
import { FactsView, JourneyView, SkillsView } from './LearningKnowledge';
import {
  Badge,
  EmptyState,
  ErrorState,
  Field,
  JsonView,
  Loading,
  Modal,
  PageHeader,
  formatDate,
} from '../components';

export type KnowledgeRecord = {
  id: string;
  dataset: string;
  key: string;
  title: string;
  description: string;
  tags: string[];
  location: string;
  revision: number;
  created_at: string;
  updated_at: string;
  created_by_execution_id: string;
  updated_by_execution_id: string;
  observed_fields: string[];
  observed_fields_complete: boolean;
  schema_status: string;
  selected_fields?: Record<string, unknown>;
  selected_fields_omitted?: boolean;
};
type CatalogPage = {
  status: string;
  records?: KnowledgeRecord[];
  datasets?: { dataset: string; location: string; sample_record?: KnowledgeRecord }[];
  has_more: boolean;
  next_offset?: number;
  next_after?: string;
  pagination_limit_reached?: boolean;
};
export type KnowledgeChunk = {
  status: string;
  record: KnowledgeRecord;
  content_chunk: string;
  offset: number;
  next_offset: number;
  total_chars: number;
  complete: boolean;
};
type KnowledgeSave = {
  status: 'saved';
  record: KnowledgeRecord;
  database_committed: true;
};
type Draft = {
  dataset: string;
  key: string;
  title: string;
  description: string;
  tags: string;
  content: string;
  revision: number;
};
const blankDraft: Draft = {
  dataset: '',
  key: '',
  title: '',
  description: '',
  tags: '',
  content: '{\n  \n}',
  revision: 0,
};
export function parseJsonObject(text: string): Record<string, unknown> {
  const value: unknown = JSON.parse(text);
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Enter a JSON object, enclosed in { }.');
  return value as Record<string, unknown>;
}
export function knowledgeSaveBody(draft: Draft): string {
  parseJsonObject(draft.content);
  if (new TextEncoder().encode(draft.content).length > 262144)
    throw new Error('JSON content must be at most 262,144 UTF-8 bytes.');
  const tags = draft.tags
    .split(',')
    .map((tag) => tag.trim())
    .filter(Boolean);
  if (tags.length > 20 || tags.some((tag) => tag.length > 64))
    throw new Error('Use at most 20 tags, each at most 64 characters.');
  // Keep the edited JSON as text until the server parses it: JS numbers can lose Decimal128 precision.
  return (
    JSON.stringify({
      dataset: draft.dataset,
      key: draft.key,
      title: draft.title,
      description: draft.description,
      tags,
      expected_revision: draft.revision,
    }).slice(0, -1) +
    ',"content":' +
    draft.content +
    '}'
  );
}
export async function readKnowledge(id: string): Promise<{ record: KnowledgeRecord; content: string }> {
  let chunk = await api<KnowledgeChunk>(
    '/api/operator/knowledge/get',
    json('POST', { id, offset: 0, limit: 4000 }),
  );
  const record = chunk.record;
  let content = chunk.content_chunk;
  while (!chunk.complete) {
    const offset = chunk.next_offset;
    if (offset <= chunk.offset) throw new Error('The server returned a non-advancing content cursor.');
    chunk = await api<KnowledgeChunk>(
      '/api/operator/knowledge/get',
      json('POST', { id, offset, limit: 4000, expected_revision: record.revision }),
    );
    if (chunk.record.revision !== record.revision || chunk.offset !== offset)
      throw new Error('Record changed during retrieval. Restart the read.');
    content += chunk.content_chunk;
  }
  return { record, content };
}

export default function Knowledge() {
  const [params, setParams] = useSearchParams();
  const view = params.get('view') ?? 'datasets';
  return (
    <div className="stack">
      <nav className="toolbar" aria-label="Knowledge views">
        {(['datasets', 'facts', 'skills', 'journey'] as const).map((entry) => (
          <button
            key={entry}
            className={`button ${view === entry ? 'primary' : ''}`}
            aria-pressed={view === entry}
            onClick={() => setParams({ view: entry })}
          >
            {entry === 'datasets'
              ? 'General knowledge'
              : entry === 'facts'
                ? 'Facts'
                : entry === 'skills'
                  ? 'Skills'
                  : 'Journey'}
          </button>
        ))}
      </nav>
      {view === 'facts' ? (
        <FactsView />
      ) : view === 'skills' ? (
        <SkillsView />
      ) : view === 'journey' ? (
        <JourneyView />
      ) : (
        <DatasetsView />
      )}
    </div>
  );
}

function DatasetsView() {
  const [params, setParams] = useSearchParams();
  const mode = params.get('mode') === 'datasets' ? 'datasets' : 'records';
  const dataset = params.get('dataset') ?? '';
  const search = params.get('search') ?? '';
  const offset = Math.min(1000000, Math.max(0, Math.floor(Number(params.get('offset')) || 0)));
  const after = params.get('after') ?? '';
  const [advanced, setAdvanced] = useState(false);
  const [filterDraft, setFilterDraft] = useState('{}');
  const [fieldDraft, setFieldDraft] = useState('');
  const [sort, setSort] = useState('updated_at');
  const [descending, setDescending] = useState(true);
  const [querySettings, setQuerySettings] = useState<{
    filters: Record<string, unknown>;
    fields: string[];
    sort_by: string;
    descending: boolean;
  } | null>(null);
  const [validation, setValidation] = useState<Error | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(() =>
    params.get('new') === '1' ? { ...blankDraft, dataset } : null,
  );
  const [saved, setSaved] = useState<KnowledgeSave | null>(null);
  const client = useQueryClient();
  const body =
    mode === 'datasets'
      ? { mode, limit: 20, ...(after ? { after } : {}), ...(search ? { search } : {}) }
      : querySettings
        ? { dataset, ...querySettings, offset, limit: 20 }
        : { mode, offset, limit: 20, ...(dataset ? { dataset } : {}), ...(search ? { search } : {}) };
  const query = useQuery({
    queryKey: ['knowledge', body],
    queryFn: () =>
      api<CatalogPage>(
        `/api/operator/knowledge/${querySettings && mode === 'records' ? 'query' : 'catalog'}`,
        json('POST', body),
      ),
  });
  function change(next: Record<string, string>) {
    const values = { mode, dataset, search, offset: String(offset), after, ...next };
    setParams(Object.fromEntries(Object.entries(values).filter(([, value]) => value !== '')));
  }
  function applyQuery(event: FormEvent) {
    event.preventDefault();
    try {
      if (!dataset) throw new Error('Select or enter a dataset before querying fields.');
      const filters = parseJsonObject(filterDraft);
      if (Object.keys(filters).length > 10) throw new Error('Use at most 10 field filters.');
      const fields = fieldDraft
        .split(',')
        .map((field) => field.trim())
        .filter(Boolean);
      if (fields.length > 20) throw new Error('Select at most 20 fields.');
      setQuerySettings({ filters, fields, sort_by: sort, descending });
      setValidation(null);
      change({ offset: '0', after: '' });
    } catch (error) {
      setValidation(error as Error);
    }
  }
  return (
    <div className="stack">
      <PageHeader
        title="Knowledge"
        description="Discover and maintain records in your configured local operator scope."
        actions={
          <button className="button primary" onClick={() => setDraft({ ...blankDraft, dataset })}>
            <Plus size={16} />
            Save knowledge
          </button>
        }
      />
      {saved && (
        <div className="notice success" role="status">
          <strong>Record saved · revision {saved.record.revision}</strong>
        </div>
      )}
      <section className="panel stack">
        <div className="toolbar">
          <div className="tabs" aria-label="Knowledge view">
            <button
              className={mode === 'records' ? 'active' : ''}
              aria-pressed={mode === 'records'}
              onClick={() => change({ mode: 'records', offset: '0', after: '' })}
            >
              Records
            </button>
            <button
              className={mode === 'datasets' ? 'active' : ''}
              aria-pressed={mode === 'datasets'}
              onClick={() => change({ mode: 'datasets', offset: '0', after: '' })}
            >
              Datasets
            </button>
          </div>
          <button className="button ghost" onClick={() => void query.refetch()} disabled={query.isFetching}>
            <RefreshCw size={15} />
            Refresh
          </button>
        </div>
        <form
          className="toolbar"
          onSubmit={(event) => {
            event.preventDefault();
            const values = new FormData(event.currentTarget);
            setQuerySettings(null);
            change({
              dataset: String(values.get('dataset') ?? ''),
              search: String(values.get('search') ?? ''),
              offset: '0',
              after: '',
            });
          }}
        >
          {mode === 'records' && (
            <Field label="Dataset">
              <input
                key={dataset}
                name="dataset"
                defaultValue={dataset}
                placeholder="All datasets"
                pattern="[A-Za-z0-9][A-Za-z0-9_-]{0,63}"
              />
            </Field>
          )}
          <Field label="Search discovery metadata">
            <input
              key={search}
              name="search"
              defaultValue={search}
              placeholder="Title, description, key, tags…"
              maxLength={100}
            />
          </Field>
          <button className="button" type="submit">
            <Search size={15} />
            Search
          </button>
        </form>
        {mode === 'records' && (
          <>
            <button className="text-button" aria-expanded={advanced} onClick={() => setAdvanced(!advanced)}>
              Advanced field query {querySettings ? '(active)' : ''}
            </button>
            {advanced && (
              <form className="stack" onSubmit={applyQuery}>
                <div className="notice">
                  Select a dataset above. Field queries replace metadata search. Supported paths: dataset,
                  key, title, description, tags, revision, created_at, updated_at and content.field. Combine
                  up to 10 filters with eq, gt, gte, lt, lte, in, exists, or contains (tags only).
                </div>
                <Field
                  label="Filters (JSON object)"
                  hint={'Example: {"tags":{"contains":"planning"},"content.priority":{"gte":2}}'}
                >
                  <textarea
                    className="mono"
                    rows={4}
                    value={filterDraft}
                    onChange={(event) => setFilterDraft(event.target.value)}
                  />
                </Field>
                <Field
                  label="Selected fields (comma separated)"
                  hint="Optional; at most 20 paths. Large selected values may be omitted by the server."
                >
                  <input
                    value={fieldDraft}
                    onChange={(event) => setFieldDraft(event.target.value)}
                    placeholder="content.project, content.status"
                  />
                </Field>
                <div className="toolbar">
                  <Field label="Sort by">
                    <select value={sort} onChange={(event) => setSort(event.target.value)}>
                      {['dataset', 'key', 'title', 'created_at', 'updated_at', 'revision'].map((field) => (
                        <option key={field}>{field}</option>
                      ))}
                    </select>
                  </Field>
                  <label>
                    <input
                      type="checkbox"
                      checked={descending}
                      onChange={(event) => setDescending(event.target.checked)}
                    />{' '}
                    Descending
                  </label>
                  <button className="button primary">Apply field query</button>
                  {querySettings && (
                    <button
                      type="button"
                      className="button"
                      onClick={() => {
                        setQuerySettings(null);
                        change({ offset: '0' });
                      }}
                    >
                      Clear field query
                    </button>
                  )}
                </div>
                {validation && <ErrorState error={validation} />}
              </form>
            )}
          </>
        )}
        {query.isPending ? (
          <Loading label="Discovering knowledge" />
        ) : query.isError ? (
          <ErrorState error={query.error} retry={() => void query.refetch()} />
        ) : (
          <>
            {mode === 'datasets' ? (
              query.data.datasets?.length ? (
                <div className="grid-2">
                  {query.data.datasets.map((entry) => (
                    <button
                      className="dataset-card panel"
                      key={entry.dataset}
                      onClick={() => {
                        setQuerySettings(null);
                        change({
                          mode: 'records',
                          dataset: entry.dataset,
                          offset: '0',
                          after: '',
                          search: '',
                        });
                      }}
                    >
                      <Database size={20} />
                      <span className="dataset-info">
                        <strong>{entry.dataset}</strong>
                        <span className="muted">{entry.sample_record?.title || 'No sample record'}</span>
                        <small>Observed fields only · no guaranteed schema</small>
                      </span>
                    </button>
                  ))}
                </div>
              ) : (
                <EmptyState
                  title="No datasets found"
                  description="Save a record to create a dataset within your local operator scope."
                />
              )
            ) : query.data.records?.length ? (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Record</th>
                      <th>Dataset / key</th>
                      <th>Tags</th>
                      <th>Updated</th>
                    </tr>
                  </thead>
                  <tbody>
                    {query.data.records.map((record) => (
                      <tr key={record.id}>
                        <td>
                          <button className="text-button" onClick={() => setSelected(record.id)}>
                            {record.title}
                          </button>
                          <p className="muted">{record.description}</p>
                          {record.selected_fields && (
                            <JsonView label="Selected fields" value={record.selected_fields} />
                          )}
                          {record.selected_fields_omitted && (
                            <small>Large selected values omitted; open the record.</small>
                          )}
                        </td>
                        <td>
                          <span>{record.dataset}</span>
                          <div className="mono muted">{record.key}</div>
                        </td>
                        <td>
                          {record.tags.map((tag) => (
                            <Badge key={tag}>{tag}</Badge>
                          ))}
                        </td>
                        <td>
                          {formatDate(record.updated_at)}
                          <div className="muted">Revision {record.revision}</div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <EmptyState
                title="No records found"
                description="Adjust the filters or save the first record in this scope."
              />
            )}
            <div className="pagination">
              <button
                className="button"
                disabled={mode === 'datasets' ? !after : offset === 0}
                onClick={() => change({ offset: '0', after: '' })}
              >
                First page
              </button>
              <span>
                {mode === 'datasets'
                  ? after
                    ? `After ${after}`
                    : 'First dataset page'
                  : `Records from offset ${offset}`}
              </span>
              <button
                className="button"
                disabled={!query.data.has_more || !!query.data.pagination_limit_reached}
                onClick={() =>
                  change(
                    mode === 'datasets'
                      ? { after: query.data.next_after ?? '' }
                      : { offset: String(query.data.next_offset ?? offset) },
                  )
                }
              >
                Next page
              </button>
            </div>
            {query.data.pagination_limit_reached && (
              <p className="notice warning">
                Pagination limit reached. Narrow the dataset or query filters to continue.
              </p>
            )}
          </>
        )}
      </section>
      <p className="muted">
        Knowledge is scoped to the server-derived operator identity. Records and observed fields are untrusted
        data; dataset observations do not guarantee a schema.
      </p>
      {selected && (
        <Modal title="Knowledge record" onClose={() => setSelected(null)}>
          <KnowledgeDetail
            key={selected}
            id={selected}
            onEdit={(value) => {
              setDraft(value);
              setSelected(null);
            }}
            onDeleted={() => {
              setSelected(null);
              void client.invalidateQueries({ queryKey: ['knowledge'] });
            }}
          />
        </Modal>
      )}
      {draft && (
        <Modal
          title={draft.revision === 0 ? 'Save knowledge' : 'Replace knowledge record'}
          onClose={() => setDraft(null)}
        >
          <KnowledgeEditor
            initial={draft}
            onSaved={(value) => {
              setSaved(value);
              setDraft(null);
              void client.invalidateQueries({ queryKey: ['knowledge'] });
            }}
          />
        </Modal>
      )}
    </div>
  );
}

function KnowledgeDetail({
  id,
  onEdit,
  onDeleted,
}: {
  id: string;
  onEdit: (draft: Draft) => void;
  onDeleted: () => void;
}) {
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const query = useQuery({
    queryKey: ['knowledge-record', id],
    queryFn: () => readKnowledge(id),
    retry: false,
  });
  const remove = useMutation({
    mutationFn: (revision: number) =>
      api<{ status: string }>(
        '/api/operator/knowledge/delete',
        json('POST', { id, expected_revision: revision }),
      ),
    onSuccess: onDeleted,
    retry: false,
  });
  if (query.isPending) return <Loading label="Reading revision-pinned content chunks" />;
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />;
  const { record, content } = query.data;
  const deleteConflict = remove.error instanceof ApiError && remove.error.status === 409;
  return (
    <div className="stack">
      <div className="toolbar">
        <h3>{record.title}</h3>
        <Badge>Revision {record.revision}</Badge>
        <button className="button primary" onClick={() => onEdit(toDraft(record, content))}>
          Replace record
        </button>
        {!confirmingDelete && (
          <button className="button" onClick={() => setConfirmingDelete(true)} disabled={remove.isPending}>
            Delete record
          </button>
        )}
      </div>
      {confirmingDelete && (
        <div className="notice" role="alert">
          <p>
            Delete “{record.title}” from {record.dataset} / {record.key} at revision {record.revision}? This
            cannot be undone.
          </p>
          <div className="row-end">
            <button className="button" onClick={() => setConfirmingDelete(false)} disabled={remove.isPending}>
              Keep it
            </button>
            <button
              className="button primary"
              onClick={() => remove.mutate(record.revision)}
              disabled={remove.isPending}
            >
              Delete permanently
            </button>
          </div>
          {deleteConflict && (
            <p className="muted">
              The record changed since it was read. Close and reopen it, then try again.
            </p>
          )}
          {remove.isError && !deleteConflict && <ErrorState error={remove.error} />}
        </div>
      )}
      <p>{record.description}</p>
      <dl className="detail-grid">
        <div>
          <dt>Dataset / key</dt>
          <dd>
            {record.dataset} / {record.key}
          </dd>
        </div>
        <div>
          <dt>Record ID</dt>
          <dd className="mono">{record.id}</dd>
        </div>
        <div>
          <dt>Created</dt>
          <dd>{formatDate(record.created_at)}</dd>
        </div>
        <div>
          <dt>Updated</dt>
          <dd>{formatDate(record.updated_at)}</dd>
        </div>
        <div>
          <dt>Created by</dt>
          <dd>{record.created_by_execution_id}</dd>
        </div>
        <div>
          <dt>Updated by</dt>
          <dd>{record.updated_by_execution_id}</dd>
        </div>
        <div>
          <dt>Logical location</dt>
          <dd className="mono">{record.location}</dd>
        </div>
      </dl>
      <div className="toolbar">
        {record.tags.map((tag) => (
          <Badge key={tag}>{tag}</Badge>
        ))}
      </div>
      <h3>JSON content</h3>
      <pre className="content-json">{content}</pre>
      <JsonView label="Observed fields (bounded sample, not a schema)" value={record.observed_fields} />
      <p className="muted">
        All chunks loaded in order at revision {record.revision}. Provenance “local-operator” identifies a
        direct save, not a task.
      </p>
    </div>
  );
}
function toDraft(record: KnowledgeRecord, content: string): Draft {
  return {
    dataset: record.dataset,
    key: record.key,
    title: record.title,
    description: record.description,
    tags: record.tags.join(', '),
    revision: record.revision,
    content,
  };
}
function KnowledgeEditor({ initial, onSaved }: { initial: Draft; onSaved: (result: KnowledgeSave) => void }) {
  const [draft, setDraft] = useState(initial);
  const [comparison, setComparison] = useState<{ record: KnowledgeRecord; content: string } | null>(null);
  const [validation, setValidation] = useState<Error | null>(null);
  const save = useMutation({
    mutationFn: () =>
      api<KnowledgeSave>('/api/operator/knowledge/save', { method: 'POST', body: knowledgeSaveBody(draft) }),
    onSuccess: onSaved,
    retry: false,
  });
  const current = useMutation({
    mutationFn: async () => {
      const value = await api<KnowledgeChunk>(
        '/api/operator/knowledge/get',
        json('POST', { dataset: draft.dataset, key: draft.key, offset: 0, limit: 4000 }),
      );
      return readKnowledge(value.record.id);
    },
    onSuccess: setComparison,
    retry: false,
  });
  function field<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((previous) => ({ ...previous, [key]: value }));
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    try {
      knowledgeSaveBody(draft);
      setValidation(null);
      save.mutate();
    } catch (error) {
      setValidation(error as Error);
    }
  }
  const conflict = save.error instanceof ApiError && save.error.status === 409;
  const uncertain = save.error instanceof ApiError && (save.error.status === 0 || save.error.status >= 500);
  return (
    <form className="stack" onSubmit={submit}>
      <div className="form-grid">
        <Field label="Dataset">
          <input
            required
            pattern="[A-Za-z0-9][A-Za-z0-9_-]{0,63}"
            maxLength={64}
            value={draft.dataset}
            readOnly={draft.revision > 0}
            onChange={(event) => field('dataset', event.target.value)}
          />
        </Field>
        <Field label="Key">
          <input
            required
            pattern="[A-Za-z0-9][A-Za-z0-9_-]{0,127}"
            maxLength={128}
            value={draft.key}
            readOnly={draft.revision > 0}
            onChange={(event) => field('key', event.target.value)}
          />
        </Field>
      </div>
      <Field label="Title">
        <input
          required
          maxLength={200}
          value={draft.title}
          onChange={(event) => field('title', event.target.value)}
        />
      </Field>
      <Field label="Description">
        <textarea
          rows={2}
          maxLength={1000}
          value={draft.description}
          onChange={(event) => field('description', event.target.value)}
        />
      </Field>
      <Field label="Tags" hint="Comma separated; up to 20 tags, each at most 64 characters.">
        <input value={draft.tags} onChange={(event) => field('tags', event.target.value)} />
      </Field>
      <Field
        label="JSON content"
        hint="Object only; at most 262,144 UTF-8 bytes and 16 nesting levels. Keys cannot contain dots, NUL, or start with $. Server validates numeric precision."
      >
        <textarea
          required
          className="mono"
          rows={12}
          value={draft.content}
          onChange={(event) => field('content', event.target.value)}
          spellCheck={false}
        />
      </Field>
      <p className="muted">
        {draft.revision === 0
          ? 'Revision zero creates a record. An existing key causes a conflict.'
          : `This fully replaces the record at revision ${draft.revision}.`}
      </p>
      {validation && <ErrorState error={validation} />}
      {save.error && <ErrorState error={save.error} />}
      {(conflict || uncertain) && (
        <div className="notice warning">
          <p>
            {conflict
              ? 'The saved record changed. Load it alongside your draft, reconcile the differences, then explicitly adopt its revision.'
              : 'The write may have committed. Inspect the current record before making another write.'}
          </p>
          <button
            className="button"
            type="button"
            disabled={current.isPending}
            onClick={() => current.mutate()}
          >
            Load current record for comparison
          </button>
        </div>
      )}
      {current.error && <ErrorState error={current.error} />}
      {current.isPending && <Loading label="Loading current revision" />}
      {comparison && (
        <section className="panel stack">
          <h3>Current saved revision {comparison.record.revision}</h3>
          <JsonView label="Current metadata" value={comparison.record} />
          <pre className="content-json">{comparison.content}</pre>
          <div className="toolbar">
            <button
              className="button"
              type="button"
              onClick={() => {
                setDraft(toDraft(comparison.record, comparison.content));
                setComparison(null);
                save.reset();
              }}
            >
              Use saved record as draft
            </button>
            <button
              className="button"
              type="button"
              onClick={() => {
                field('revision', comparison.record.revision);
                setComparison(null);
                save.reset();
              }}
            >
              I reconciled my draft; use revision {comparison.record.revision}
            </button>
          </div>
        </section>
      )}
      <button className="button primary" type="submit" disabled={save.isPending || conflict || uncertain}>
        {save.isPending
          ? 'Saving…'
          : draft.revision === 0
            ? 'Create record'
            : `Replace revision ${draft.revision}`}
      </button>
    </form>
  );
}
