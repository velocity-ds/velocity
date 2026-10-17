import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { KeyRound, Plus, RefreshCw, Search } from 'lucide-react';
import { Badge, EmptyState, Field, formatDate, Loading, Modal, PageHeader } from '../components';
import {
  SecretsError,
  secretsRequest,
  type CredentialMetadata,
  type SecretList,
  type SecretOperation,
  type SecretWrite,
} from '../secretsApi';

type Editor = { kind: 'add' } | { kind: 'edit'; credential: CredentialMetadata };
type Pending = { requestId: string; uncertain: boolean };
const pendingOperationKey = 'velocity.secrets.pendingOperation';
function retainedOperation(): Pending | undefined {
  try {
    const requestId = sessionStorage.getItem(pendingOperationKey);
    return requestId && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)
      ? { requestId, uncertain: true }
      : undefined;
  } catch {
    return undefined;
  }
}
function clearOperation(requestId: string) {
  try {
    if (sessionStorage.getItem(pendingOperationKey) === requestId) {
      sessionStorage.removeItem(pendingOperationKey);
      window.dispatchEvent(new CustomEvent('velocity-secrets-settled', { detail: requestId }));
    }
  } catch {
    /* A retained ID can safely be reconciled again. */
  }
}

/** Only metadata and opaque operation references outlive the credential form. */
export default function Secrets() {
  const [list, setList] = useState<SecretList>();
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [editor, setEditor] = useState<Editor>();
  const [deleting, setDeleting] = useState<CredentialMetadata>();
  const [pending, setPending] = useState<Pending | undefined>(retainedOperation);
  const [reconciling, setReconciling] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState<SecretsError>();
  const writing = useRef(false);
  const mounted = useRef(true);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const result = await secretsRequest<SecretList>();
      if (mounted.current) {
        setList(result);
        setError(undefined);
      }
    } catch {
      if (mounted.current) setError(new SecretsError('unavailable'));
    } finally {
      if (mounted.current) setLoading(false);
    }
  }, []);
  useEffect(() => {
    mounted.current = true;
    void reload();
    return () => {
      mounted.current = false;
    };
  }, [reload]);

  useEffect(() => {
    const settled = (event: Event) => {
      if (!writing.current && pending?.requestId === (event as CustomEvent<string>).detail) {
        setPending(undefined);
        setMessage(
          'The previous request has finished. Review the reloaded records before making another change.',
        );
        void reload();
      }
    };
    window.addEventListener('velocity-secrets-settled', settled);
    return () => window.removeEventListener('velocity-secrets-settled', settled);
  }, [pending, reload]);

  // Only an opaque operation UUID survives navigation. No form data or credential
  // metadata enters browser storage; navigating away destroys the credential inputs.
  useEffect(() => {
    if (!pending) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [pending]);

  async function write(suffix: string, method: string, body: object) {
    if (writing.current || pending) return;
    writing.current = true;
    const requestId = crypto.randomUUID();
    try {
      // Commit the reconciliation handle before dispatch, including for a tab reload.
      sessionStorage.setItem(pendingOperationKey, requestId);
    } catch {
      writing.current = false;
      setMessage(
        'Browser session storage is unavailable. Enable it before saving so an interrupted write can be reconciled safely.',
      );
      return;
    }
    setPending({ requestId, uncertain: false });
    setError(undefined);
    setMessage('');
    setEditor(undefined);
    setDeleting(undefined);
    try {
      const result = await secretsRequest<SecretWrite>(suffix, method, { ...body, request_id: requestId });
      if (result.database_committed !== true) {
        if (result.database_committed === false) {
          throw new SecretsError(result.status === 'revision_conflict' ? 'conflict' : 'invalid');
        }
        throw new SecretsError('unknown');
      }
      clearOperation(requestId);
      if (!mounted.current) return;
      setPending(undefined);
      setMessage(result.status === 'deleted' ? 'Credential deleted.' : 'Change saved.');
      await reload();
    } catch (failure) {
      const safe = failure instanceof SecretsError ? failure : new SecretsError('unknown');
      if (safe.kind !== 'unknown') clearOperation(requestId);
      if (!mounted.current) return;
      setError(safe);
      setPending(safe.kind === 'unknown' ? { requestId, uncertain: true } : undefined);
    } finally {
      writing.current = false;
    }
  }

  async function reconcile() {
    if (!pending || reconciling) return;
    setReconciling(true);
    try {
      const result = await secretsRequest<SecretOperation>(
        `/operations/${encodeURIComponent(pending.requestId)}`,
      );
      if (result.status === 'committed') {
        clearOperation(pending.requestId);
        if (!mounted.current) return;
        setPending(undefined);
        setMessage('The operation was saved. Current records have been reloaded.');
        await reload();
      } else {
        if (!mounted.current) return;
        setMessage(
          'No committed outcome is available yet. Keep this page open and check again; do not submit another copy.',
        );
      }
    } catch {
      if (!mounted.current) return;
      setMessage(
        'Reconciliation is unavailable. Keep this page open and check again when Secrets is available.',
      );
    } finally {
      if (mounted.current) setReconciling(false);
    }
  }

  const canWrite = list?.availability.available === true && !pending && !loading && !error;
  const filtered = (list?.credentials ?? []).filter(
    (record) =>
      !record.deleted &&
      `${record.service} ${record.account_label} ${record.credential_origins.join(' ')} ${(record.application_origins ?? []).join(' ')}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  return (
    <>
      <PageHeader
        title="Secrets"
        description="Save matching website username and password pairs for authorized browser tasks."
        actions={
          <>
            <button className="button" onClick={() => void reload()} disabled={loading || !!pending}>
              <RefreshCw size={16} />
              Reload records
            </button>
            <button
              className="button primary"
              disabled={!canWrite}
              onClick={() => setEditor({ kind: 'add' })}
            >
              <Plus size={16} />
              Add credential
            </button>
          </>
        }
      />
      <div className="notice">
        <KeyRound size={20} />
        <div>
          <strong>Use a label such as Work or Personal</strong>
          <p>
            Service names, account labels and permitted destinations are visible to the agent. Keep secrets
            out of those fields. Usernames and passwords are never displayed after saving. If multiple
            accounts match, the agent asks which label to use.
          </p>
        </div>
      </div>
      {list && (
        <p className="row">
          <Badge status={list.availability.available ? 'enabled' : 'disabled'}>
            {list.availability.available
              ? 'Secrets available'
              : list.availability.enabled
                ? 'Secrets unavailable'
                : 'Secrets disabled'}
          </Badge>
          {!list.availability.available && (
            <span>Check the registered local account, storage and configured encryption key.</span>
          )}
        </p>
      )}
      {error && (
        <div className="notice error" role="alert">
          <div>
            <strong>
              {error.kind === 'conflict'
                ? 'Revision conflict'
                : error.kind === 'unknown'
                  ? 'Uncertain save'
                  : 'Unable to complete this action'}
            </strong>
            <p>{error.message}</p>
          </div>
        </div>
      )}
      {message && (
        <p role="status" className="notice">
          {message}
        </p>
      )}
      {pending && (
        <div className="panel stack" aria-label="Pending credential operation">
          <strong>{pending.uncertain ? 'Reconcile before continuing' : 'Saving change…'}</strong>
          <p>
            Keep this page open until the operation is resolved. Velocity does not automatically retry
            credential writes.
          </p>
          <p className="mono">Operation: {pending.requestId}</p>
          {pending.uncertain && (
            <button className="button align-start" onClick={() => void reconcile()} disabled={reconciling}>
              {reconciling ? 'Checking saved outcome…' : 'Check saved outcome'}
            </button>
          )}
        </div>
      )}
      <section className="panel section-space" aria-label="Saved credentials">
        <div className="toolbar">
          <label className="search-field">
            <Search size={16} />
            <input
              aria-label="Search saved credentials"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search service, label or origin"
              maxLength={120}
              autoComplete="off"
            />
          </label>
        </div>
        {loading ? (
          <Loading />
        ) : filtered.length === 0 ? (
          <EmptyState
            title="No saved credentials"
            description={
              search
                ? 'No safe metadata matches this search.'
                : 'Add a credential here. Never paste a username or password into chat or Slack.'
            }
          />
        ) : (
          <div className="table-wrap">
            <table aria-label="Saved website accounts">
              <thead>
                <tr>
                  <th>Service / account</th>
                  <th>Credential destinations</th>
                  <th>State</th>
                  <th>Updated</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((record) => (
                  <tr key={record.credential_id}>
                    <td>
                      <strong>{record.service}</strong>
                      <span className="block">{record.account_label}</span>
                    </td>
                    <td>
                      {record.credential_origins.map((origin) => (
                        <span key={origin} className="block">
                          {origin}
                        </span>
                      ))}
                    </td>
                    <td>
                      <Badge status={record.enabled ? 'enabled' : 'disabled'} />
                    </td>
                    <td>{formatDate(record.updated_at)}</td>
                    <td>
                      <div className="row secrets-actions">
                        <button
                          className="button small"
                          disabled={!canWrite}
                          onClick={() => setEditor({ kind: 'edit', credential: record })}
                          aria-label={`Edit ${record.service} ${record.account_label}`}
                        >
                          Edit
                        </button>
                        <button
                          className="button small"
                          disabled={!canWrite}
                          onClick={() =>
                            void write(`/${encodeURIComponent(record.credential_id)}/state`, 'POST', {
                              expected_revision: record.revision,
                              enabled: !record.enabled,
                            })
                          }
                          aria-label={`${record.enabled ? 'Disable' : 'Enable'} ${record.service} ${record.account_label}`}
                        >
                          {record.enabled ? 'Disable' : 'Enable'}
                        </button>
                        <button
                          className="button small danger"
                          disabled={!canWrite}
                          onClick={() => setDeleting(record)}
                          aria-label={`Delete ${record.service} ${record.account_label}`}
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {editor && (
        <CredentialForm
          editor={editor}
          onClose={() => setEditor(undefined)}
          onSave={(body) => {
            void write(
              editor.kind === 'edit' ? `/${encodeURIComponent(editor.credential.credential_id)}` : '',
              editor.kind === 'edit' ? 'PUT' : 'POST',
              body,
            );
          }}
        />
      )}
      {deleting && (
        <Modal title="Delete credential" onClose={() => setDeleting(undefined)}>
          <div className="panel-body stack">
            <p>
              Delete {deleting.service} — {deleting.account_label}? The encrypted pair is removed and existing
              login references are revoked.
            </p>
            <div className="row">
              <button
                className="button danger"
                onClick={() =>
                  void write(`/${encodeURIComponent(deleting.credential_id)}`, 'DELETE', {
                    expected_revision: deleting.revision,
                  })
                }
              >
                Delete credential
              </button>
              <button className="button" onClick={() => setDeleting(undefined)}>
                Cancel
              </button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}

function origins(value: string, required: boolean, callback = false): string[] | undefined {
  const values = value
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean);
  if ((required && values.length === 0) || values.length > 16) return undefined;
  try {
    for (const value of values) {
      const parsed = new URL(value);
      if (
        parsed.protocol !== 'https:' ||
        parsed.username ||
        parsed.password ||
        (!callback && parsed.pathname !== '/') ||
        parsed.search ||
        parsed.hash ||
        parsed.hostname.includes('*') ||
        parsed.hostname.endsWith('.') ||
        (callback && (value.length > 2048 || /%(2e|2f|5c)/i.test(parsed.pathname)))
      )
        return undefined;
    }
    return [
      ...new Set(
        values.map((value) => {
          const parsed = new URL(value);
          return parsed.origin + (callback ? parsed.pathname : '');
        }),
      ),
    ];
  } catch {
    return undefined;
  }
}

function CredentialForm({
  editor,
  onClose,
  onSave,
}: {
  editor: Editor;
  onClose: () => void;
  onSave: (body: object) => void;
}) {
  const record = editor.kind === 'edit' ? editor.credential : undefined;
  const [service, setService] = useState(record?.service ?? '');
  const [label, setLabel] = useState(record?.account_label ?? '');
  const [destinations, setDestinations] = useState(record?.credential_origins.join('\n') ?? '');
  const [afterLogin, setAfterLogin] = useState(record?.post_login_origins.join('\n') ?? '');
  const [applications, setApplications] = useState(record?.application_origins?.join('\n') ?? '');
  const [callbacks, setCallbacks] = useState(record?.callback_urls?.join('\n') ?? '');
  const [resources, setResources] = useState(record?.resource_origins?.join('\n') ?? '');
  const [replace, setReplace] = useState(!record);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [validation, setValidation] = useState('');
  const clear = () => {
    setUsername('');
    setPassword('');
  };
  function close() {
    clear();
    onClose();
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    const credentialOrigins = origins(destinations, true);
    const postLoginOrigins = origins(afterLogin, false);
    const applicationOrigins = origins(applications, false);
    const callbackUrls = origins(callbacks, false, true);
    const resourceOrigins = origins(resources, false);
    if (
      !service.trim() ||
      service.length > 120 ||
      !label.trim() ||
      label.length > 120 ||
      !credentialOrigins ||
      !postLoginOrigins ||
      !applicationOrigins ||
      !callbackUrls ||
      !resourceOrigins ||
      (replace && (!username.length || username.length > 2048 || !password.length || password.length > 4096))
    ) {
      setValidation(
        'Enter a service and account label, exact HTTPS origins (up to 16 per list), callback URLs with an exact path and no query or fragment, and both credentials when adding or replacing.',
      );
      return;
    }
    onSave({
      service,
      account_label: label,
      credential_origins: credentialOrigins,
      post_login_origins: postLoginOrigins,
      application_origins: applicationOrigins,
      callback_urls: callbackUrls,
      resource_origins: resourceOrigins,
      ...(record ? { expected_revision: record.revision } : {}),
      ...(replace ? { username, password } : {}),
    });
    clear();
  }
  return (
    <Modal title={record ? 'Edit credential' : 'Add credential'} onClose={close}>
      <form className="panel-body stack" onSubmit={submit} autoComplete="off">
        <div className="form-grid">
          <Field label="Service name">
            <input
              required
              maxLength={120}
              value={service}
              onChange={(event) => setService(event.target.value)}
            />
          </Field>
          <Field label="Account label" hint="Visible to the agent; no secrets.">
            <input
              required
              maxLength={120}
              value={label}
              onChange={(event) => setLabel(event.target.value)}
            />
          </Field>
        </div>
        <Field
          label="Application origins"
          hint="Optional exact HTTPS origins of the service, one per line. Enables account discovery and business-page navigation. This does not authorize sending the saved username or password there."
        >
          <textarea
            rows={2}
            maxLength={4096}
            placeholder="https://app.example.com"
            value={applications}
            onChange={(event) => setApplications(event.target.value)}
          />
        </Field>
        <Field
          label="Permitted credential origins"
          hint="One exact HTTPS origin per line allowed to receive the saved username and password, including a hosted login provider when needed. A redirect never adds permission. No paths, wildcards or implicit subdomains."
        >
          <textarea
            required
            rows={3}
            maxLength={4096}
            placeholder="https://login.example.com"
            value={destinations}
            onChange={(event) => setDestinations(event.target.value)}
          />
        </Field>
        <Field
          label="Authentication callback URLs"
          hint="Optional HTTPS URLs with exact paths, one per line, such as https://app.example.com/auth/callback. The website supplies OAuth query values at runtime. Do not paste codes, tokens, query strings or fragments here. These URLs cannot receive the saved credential pair."
        >
          <textarea
            rows={2}
            maxLength={8192}
            value={callbacks}
            onChange={(event) => setCallbacks(event.target.value)}
          />
        </Field>
        <Field
          label="Ordinary resource origins"
          hint="Optional exact HTTPS origins for static scripts, styles, images, fonts and media, one per line. Include separate resource hosts needed by the login or callback page. This permits GET/HEAD resources, never sending the saved credential pair or arbitrary cross-origin API requests."
        >
          <textarea
            rows={2}
            maxLength={4096}
            value={resources}
            onChange={(event) => setResources(event.target.value)}
          />
        </Field>
        <Field
          label="Legacy root landing origins"
          hint="Existing restrictive permissions: root landing redirects only, without paths, queries or resources. Kept separately so saved permissions are never silently expanded. Use explicit application origins and callback URLs for a new hosted login setup."
        >
          <textarea
            rows={2}
            maxLength={4096}
            value={afterLogin}
            onChange={(event) => setAfterLogin(event.target.value)}
          />
        </Field>
        {record && (
          <label className="row">
            <input
              type="checkbox"
              checked={replace}
              onChange={(event) => {
                setReplace(event.target.checked);
                clear();
              }}
            />
            Replace username and password together
          </label>
        )}
        {replace && (
          <div className="form-grid">
            <Field label="Username" hint="Saved exactly as entered.">
              <input
                required
                maxLength={2048}
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                value={username}
                onChange={(event) => setUsername(event.target.value)}
              />
            </Field>
            <Field label="Password" hint="Saved exactly as entered, including spaces.">
              <input
                type="password"
                required
                maxLength={4096}
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </Field>
          </div>
        )}
        <p className="muted">
          Permitted websites receive and can process the credentials. Only allow websites you trust. MFA,
          CAPTCHA and unsupported login steps require human interaction.
        </p>
        {validation && (
          <p role="alert" className="notice error">
            {validation}
          </p>
        )}
        <div className="row">
          <button type="submit" className="button primary">
            {record ? 'Save changes' : 'Save credential'}
          </button>
          <button type="button" className="button" onClick={close}>
            Cancel
          </button>
        </div>
      </form>
    </Modal>
  );
}
