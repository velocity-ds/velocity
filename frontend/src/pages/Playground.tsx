import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { FlaskConical, ImagePlus, Play, Square, Trash2 } from 'lucide-react';
import { api, ApiError, safeHref } from '../api';
import {
  Badge,
  EmptyState,
  ErrorState,
  Field,
  JsonView,
  Loading,
  PageHeader,
  SafeLink,
  SafeMarkdown,
} from '../components';
import { parseJsonObject } from './Knowledge';

export type InferenceCapabilities = {
  available: boolean;
  provider: string | null;
  model: string | null;
  capabilities: string[];
  presets: string[];
  connectivity: string;
  history: { maxSessions: number; maxBytes: number; ttlMinutes: number };
  computerActionsExecute: false;
};
type InferenceResult = {
  sessionId: string;
  revision: number;
  continuationAllowed: boolean;
  historyRetained: boolean;
  response: {
    id: string;
    model: string;
    text: string;
    content: Record<string, unknown>[];
    stopReason: string | null;
    usage: { inputTokens: number; outputTokens: number; totalTokens: number } | null;
  };
};
type InputImage = { name: string; base64: string; mediaType: string };
type Turn = { prompt: string; result: InferenceResult; structured: boolean };
export class SseDecoder {
  private buffer = '';
  constructor(private onEvent: (event: string, data: unknown) => void) {}
  push(chunk: string, final = false) {
    this.buffer += chunk;
    let match: RegExpExecArray | null;
    while ((match = /\r?\n\r?\n/.exec(this.buffer))) {
      this.parse(this.buffer.slice(0, match.index));
      this.buffer = this.buffer.slice(match.index + match[0].length);
    }
    if (final && this.buffer.trim()) {
      this.parse(this.buffer);
      this.buffer = '';
    }
  }
  private parse(block: string) {
    let name = 'message';
    const data: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) name = line.slice(6).trim();
      if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (data.length) this.onEvent(name, JSON.parse(data.join('\n')));
  }
}
const presetLabels: Record<string, string> = {
  STRUCTURED_EXTRACTION: 'Structured extraction',
  WEB_RESEARCH: 'Web research',
  SPECIFICATION_REVIEW: 'Specification review',
  COMPUTER_USE: 'Computer-use proposals',
};
const defaultSchema =
  '{\n  "type": "object",\n  "properties": {\n    "summary": { "type": "string" }\n  },\n  "required": ["summary"],\n  "additionalProperties": false\n}';

export default function Playground() {
  const capabilities = useQuery({
    queryKey: ['inference-capabilities'],
    queryFn: () => api<InferenceCapabilities>('/api/operator/inference'),
    staleTime: 30000,
  });
  const [prompt, setPrompt] = useState('');
  const [system, setSystem] = useState('');
  const [preset, setPreset] = useState('');
  const [images, setImages] = useState<InputImage[]>([]);
  const [streaming, setStreaming] = useState(true);
  const [structured, setStructured] = useState(false);
  const [schema, setSchema] = useState(defaultSchema);
  const [webSearch, setWebSearch] = useState(false);
  const [thinking, setThinking] = useState(false);
  const [computer, setComputer] = useState(false);
  const [running, setRunning] = useState(false);
  const [readingImages, setReadingImages] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [draftText, setDraftText] = useState('');
  const [draftThinking, setDraftThinking] = useState('');
  const [citations, setCitations] = useState<unknown[]>([]);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [session, setSession] = useState<InferenceResult | null>(null);
  const [mustReset, setMustReset] = useState(false);
  const abort = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      abort.current?.abort();
    };
  }, []);
  const has = (capability: string) => capabilities.data?.capabilities.includes(capability) ?? false;
  async function addImages(files: FileList | null) {
    if (!files) return;
    setReadingImages(true);
    setError(null);
    try {
      if (images.length + files.length > 4) throw new Error('Attach at most four images per request.');
      const added: InputImage[] = [];
      for (const file of Array.from(files)) {
        if (!['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(file.type))
          throw new Error('Supported image formats: PNG, JPEG, GIF, and WebP.');
        if (file.size > 675000)
          throw new Error(
            'Each image must fit in 900,000 base64 characters (about 675 KB). Resize the image before attaching it.',
          );
        const base64 = await new Promise<string>((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(String(reader.result).split(',')[1]);
          reader.onerror = () => reject(new Error('Image could not be read.'));
          reader.readAsDataURL(file);
        });
        added.push({ name: file.name, base64, mediaType: file.type });
      }
      setImages((previous) => [...previous, ...added]);
    } catch (failure) {
      setError(failure as Error);
    } finally {
      setReadingImages(false);
    }
  }
  async function reset() {
    if (running) return;
    if (session?.historyRetained) {
      try {
        await api(`/api/operator/inference/sessions/${encodeURIComponent(session.sessionId)}`, {
          method: 'DELETE',
        });
      } catch (failure) {
        setError(failure as Error);
        return;
      }
    }
    setTurns([]);
    setSession(null);
    setMustReset(false);
    setError(null);
    setDraftText('');
    setDraftThinking('');
    setCitations([]);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (running || mustReset || !capabilities.data?.available) return;
    setError(null);
    let body: string;
    try {
      const outputSchema = structured && has('STRUCTURED_OUTPUT') ? parseJsonObject(schema) : undefined;
      if (outputSchema && (!Object.keys(outputSchema).length || schema.length > 65536))
        throw new Error('Schema must be a nonempty JSON object up to 65,536 characters.');
      const request = {
        prompt,
        ...(session?.continuationAllowed
          ? { sessionId: session.sessionId, expectedRevision: session.revision }
          : {}),
        ...(preset ? { preset } : system.trim() ? { systemPrompt: system } : {}),
        ...(has('VISION') && images.length
          ? { images: images.map(({ base64, mediaType }) => ({ base64, mediaType })) }
          : {}),
        ...(outputSchema ? { outputSchema } : {}),
        webSearch: has('WEB_SEARCH') && webSearch,
        adaptiveThinking: has('ADAPTIVE_THINKING') && thinking,
        computerUse: has('COMPUTER_USE') && computer,
      };
      body = JSON.stringify(request);
      if (new TextEncoder().encode(body).length > 1048576)
        throw new Error(
          'Combined prompt, options, and images exceed the 1 MiB operator request limit. Use fewer or smaller images.',
        );
    } catch (failure) {
      setError(failure as Error);
      return;
    }
    const controller = new AbortController();
    abort.current = controller;
    setRunning(true);
    setDraftText('');
    setDraftThinking('');
    setCitations([]);
    let result: InferenceResult | undefined;
    try {
      if (streaming && has('STREAMING')) {
        const response = await fetch('/api/operator/inference/stream', {
          method: 'POST',
          body,
          signal: controller.signal,
          credentials: 'same-origin',
          headers: { Accept: 'text/event-stream', 'Content-Type': 'application/json' },
        });
        if (!response.ok) {
          const text = await response.text();
          let value: unknown = text;
          try {
            value = JSON.parse(text);
          } catch {
            /* Keep a safe generic fallback. */
          }
          const message =
            value && typeof value === 'object' && 'error' in value
              ? String(value.error)
              : `Inference request failed (${response.status}).`;
          throw new ApiError(response.status, value, message);
        }
        if (!response.body) throw new Error('The browser did not provide a readable inference stream.');
        const decoder = new TextDecoder();
        const events = new SseDecoder((eventName, data) => {
          const value = data as Record<string, unknown>;
          if (eventName === 'text_delta' && typeof value.delta === 'string')
            setDraftText((previous) => previous + value.delta);
          if (eventName === 'thinking_delta' && typeof value.delta === 'string')
            setDraftThinking((previous) => previous + value.delta);
          if (eventName === 'citation') setCitations((previous) => [...previous, value.citation]);
          if (eventName === 'complete') result = data as InferenceResult;
          if (eventName === 'error')
            throw new Error(typeof value.error === 'string' ? value.error : 'Inference stream failed.');
        });
        const reader = response.body.getReader();
        try {
          while (true) {
            const chunk = await reader.read();
            if (chunk.done) break;
            events.push(decoder.decode(chunk.value, { stream: true }));
          }
          events.push(decoder.decode(), true);
        } finally {
          await reader.cancel().catch(() => undefined);
          reader.releaseLock();
        }
        if (!result)
          throw new Error(
            'Connection ended before a completion result. The server discards incomplete history. Start a new session before another request.',
          );
      } else
        result = await api<InferenceResult>('/api/operator/inference', {
          method: 'POST',
          body,
          signal: controller.signal,
        });
      if (mounted.current) {
        setSession(result);
        setTurns((previous) => [...previous, { prompt, result: result!, structured }]);
        setPrompt('');
        setImages([]);
        setDraftText('');
        setDraftThinking('');
        setCitations([]);
        if (!result.continuationAllowed) setMustReset(true);
      }
    } catch (failure) {
      if (mounted.current) {
        setError(
          failure instanceof DOMException && failure.name === 'AbortError'
            ? new Error(
                streaming && has('STREAMING')
                  ? 'Request stopped. The stream is closed and incomplete server history is discarded. Start a new session to continue.'
                  : 'Stopped waiting for the response. The server may still finish this request; it was not replayed. Start a new session before another request.',
              )
            : failure instanceof Error
              ? failure
              : new Error('Inference request failed.'),
        );
        // Never replay a request after disconnect, stale history, or provider failure.
        if (!(failure instanceof ApiError && [400, 503].includes(failure.status))) {
          setMustReset(true);
          setSession(null);
        }
      }
    } finally {
      if (mounted.current) setRunning(false);
      abort.current = null;
    }
  }
  function choosePreset(value: string) {
    setPreset(value);
    if (value) setSystem('');
  }
  return (
    <div className="stack">
      <PageHeader
        title="Inference playground"
        description="Explore the configured model directly, with a temporary conversation kept on the server."
        actions={
          <button
            className="button"
            onClick={() => void reset()}
            disabled={running || (!session && !turns.length && !mustReset)}
          >
            <Trash2 size={16} />
            New session
          </button>
        }
      />
      {capabilities.isPending ? (
        <Loading label="Discovering inference capabilities" />
      ) : capabilities.isError ? (
        <ErrorState error={capabilities.error} retry={() => void capabilities.refetch()} />
      ) : (
        <>
          <div className="toolbar">
            <Badge status={capabilities.data.available ? 'active' : 'disabled'}>
              {capabilities.data.available ? capabilities.data.provider : 'Inference disabled'}
            </Badge>
            <strong>{capabilities.data.model || 'No configured model'}</strong>
            <span className="muted">Provider connectivity has not been checked</span>
          </div>
          {!capabilities.data.available && (
            <div className="notice warning">
              Inference is disabled or unavailable. Configure one provider on the server and restart Velocity.{' '}
              <Link to="/system">View system status</Link>
            </div>
          )}
          <div className="notice">
            Playground requests are separate from durable tasks. Native provider history stays on the server
            for up to {capabilities.data.history.ttlMinutes} minutes of inactivity and{' '}
            {Math.round(capabilities.data.history.maxBytes / 1048576)} MiB. There is no provider switching or
            automatic fallback.
          </div>
          <div className="split-layout playground-layout">
            <form className="panel stack" onSubmit={submit} aria-label="Inference request">
              <fieldset disabled={running || !capabilities.data.available}>
                <div className="stack">
                  <Field
                    label="Prompt preset"
                    hint="Presets supply task instructions. Feature options below determine provider capabilities."
                  >
                    <select value={preset} onChange={(event) => choosePreset(event.target.value)}>
                      <option value="">Custom / no preset</option>
                      {capabilities.data.presets.map((value) => (
                        <option key={value} value={value}>
                          {presetLabels[value] ?? value}
                        </option>
                      ))}
                    </select>
                  </Field>
                  {!preset && (
                    <Field label="Custom system instructions (optional)">
                      <textarea
                        rows={3}
                        maxLength={30000}
                        value={system}
                        onChange={(event) => setSystem(event.target.value)}
                        placeholder="Describe the role, constraints, or response style."
                      />
                    </Field>
                  )}
                  <div className="option-list">
                    <CapabilityOption
                      label="Stream response"
                      capability="STREAMING"
                      supported={has('STREAMING')}
                      checked={streaming && has('STREAMING')}
                      onChange={setStreaming}
                    />
                    <CapabilityOption
                      label="Structured JSON output"
                      capability="STRUCTURED_OUTPUT"
                      supported={has('STRUCTURED_OUTPUT')}
                      checked={structured && has('STRUCTURED_OUTPUT')}
                      onChange={setStructured}
                    />
                    <CapabilityOption
                      label="Web search and citations"
                      capability="WEB_SEARCH"
                      supported={has('WEB_SEARCH')}
                      checked={webSearch && has('WEB_SEARCH')}
                      onChange={setWebSearch}
                    />
                    <CapabilityOption
                      label="Adaptive thinking / reasoning"
                      capability="ADAPTIVE_THINKING"
                      supported={has('ADAPTIVE_THINKING')}
                      checked={thinking && has('ADAPTIVE_THINKING')}
                      onChange={setThinking}
                    />
                    <CapabilityOption
                      label="Computer-use action proposals"
                      capability="COMPUTER_USE"
                      supported={has('COMPUTER_USE')}
                      checked={computer && has('COMPUTER_USE')}
                      onChange={setComputer}
                    />
                  </div>
                  {structured && has('STRUCTURED_OUTPUT') && (
                    <Field
                      label="JSON schema"
                      hint="Nonempty JSON Schema object; provider-specific schema restrictions are validated by the provider."
                    >
                      <textarea
                        className="mono"
                        rows={10}
                        value={schema}
                        onChange={(event) => setSchema(event.target.value)}
                        maxLength={65536}
                        spellCheck={false}
                      />
                    </Field>
                  )}
                  {computer && has('COMPUTER_USE') && (
                    <div className="notice warning">
                      The model can propose actions. These proposals do not execute desktop or browser
                      actions. A pending action ends this temporary session; use a durable task for supported
                      browser automation.
                    </div>
                  )}
                  <Field label={session?.continuationAllowed ? 'Follow-up message' : 'Your message'}>
                    <textarea
                      required
                      maxLength={100000}
                      rows={6}
                      value={prompt}
                      onChange={(event) => setPrompt(event.target.value)}
                      placeholder="Enter a request for the configured model…"
                    />
                  </Field>
                  <Field
                    label="Image inputs"
                    hint={
                      has('VISION')
                        ? 'PNG, JPEG, GIF, or WebP. Up to four images; the complete encoded request must fit in 1 MiB.'
                        : 'The configured provider does not support image inputs.'
                    }
                  >
                    <input
                      type="file"
                      multiple
                      accept="image/png,image/jpeg,image/gif,image/webp"
                      disabled={!has('VISION') || readingImages}
                      onChange={(event) => {
                        void addImages(event.target.files);
                        event.target.value = '';
                      }}
                    />
                  </Field>
                  {images.length > 0 && (
                    <div className="image-inputs">
                      {images.map((image, index) => (
                        <div className="panel" key={`${image.name}-${index}`}>
                          <img
                            src={`data:${image.mediaType};base64,${image.base64}`}
                            alt={`Attached input: ${image.name}`}
                          />
                          <span>{image.name}</span>
                          <button
                            type="button"
                            className="button ghost"
                            onClick={() => setImages((previous) => previous.filter((_, i) => i !== index))}
                          >
                            <ImagePlus size={14} />
                            Remove image
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </fieldset>
              {error && <ErrorState error={error} />}
              {mustReset && (
                <div className="notice warning">
                  {session && !session.continuationAllowed
                    ? 'This response cannot continue because native history was not retained or an action proposal needs handling.'
                    : 'This temporary session cannot safely continue.'}{' '}
                  Start a new session before sending another request.
                </div>
              )}
              <div className="toolbar">
                <button
                  className="button primary"
                  type="submit"
                  disabled={
                    running || readingImages || mustReset || !capabilities.data.available || !prompt.trim()
                  }
                >
                  <Play size={15} />
                  {running
                    ? 'Generating…'
                    : session?.continuationAllowed
                      ? 'Send follow-up'
                      : 'Run inference'}
                </button>
                {running && (
                  <button className="button danger" type="button" onClick={() => abort.current?.abort()}>
                    <Square size={14} />
                    Stop request
                  </button>
                )}
              </div>
            </form>
            <section className="stack" aria-label="Inference conversation">
              {!turns.length && !running && !draftText && (
                <div className="panel">
                  <EmptyState
                    title="A place to test an idea"
                    description="Write a prompt, select supported options, and inspect the actual response and usage."
                    action={<FlaskConical size={24} />}
                  />
                </div>
              )}
              {turns.map((turn, index) => (
                <article className="panel stack" key={`${turn.result.sessionId}-${turn.result.revision}`}>
                  <div className="eyebrow">Turn {index + 1} · You</div>
                  <pre className="prompt-text">{turn.prompt}</pre>
                  <div className="toolbar">
                    <h3>{turn.result.response.model}</h3>
                    <Badge status={responseOutcome(turn.result.response).status}>
                      {responseOutcome(turn.result.response).label}
                    </Badge>
                  </div>
                  <SafeMarkdown text={turn.result.response.text} />
                  {turn.structured && <StructuredResult text={turn.result.response.text} />}
                  <CitationList value={turn.result.response.content} />
                  {turn.result.response.content.some(
                    (block) =>
                      block.type === 'tool_call' ||
                      (block.type === 'provider_block' &&
                        ['computer_call', 'function_call', 'tool_use'].includes(String(block.blockType))),
                  ) && (
                    <div className="notice warning">
                      Action proposals are shown in response blocks below. No actions were executed by the
                      playground.
                    </div>
                  )}
                  <div className="grid-3">
                    {(['inputTokens', 'outputTokens', 'totalTokens'] as const).map((key) => (
                      <div className="stat" key={key}>
                        <span>{key.replace('Tokens', ' tokens')}</span>
                        <strong>
                          {turn.result.response.usage?.[key]?.toLocaleString() ?? 'Unavailable'}
                        </strong>
                      </div>
                    ))}
                  </div>
                  <dl className="detail-grid">
                    <div>
                      <dt>Stop reason</dt>
                      <dd>{turn.result.response.stopReason || 'Not supplied'}</dd>
                    </div>
                    <div>
                      <dt>History</dt>
                      <dd>
                        {turn.result.historyRetained
                          ? `Retained · revision ${turn.result.revision}`
                          : 'Not retained'}
                      </dd>
                    </div>
                  </dl>
                  <JsonView
                    label="Response blocks, reasoning, citations, and metadata"
                    value={turn.result.response.content}
                  />
                </article>
              ))}
              {(running || draftText || draftThinking) && (
                <article className="panel stack" aria-label="Current response">
                  {running && (
                    <Loading
                      label={
                        streaming && has('STREAMING')
                          ? 'Receiving model output'
                          : 'Waiting for model response'
                      }
                    />
                  )}
                  {draftText && <SafeMarkdown text={draftText} />}
                  {draftThinking && (
                    <details>
                      <summary>Reasoning received</summary>
                      <pre className="content-json">{draftThinking}</pre>
                    </details>
                  )}
                  <CitationList value={citations} />
                  {!running && (
                    <p className="notice warning">Incomplete output; no completed response was received.</p>
                  )}
                </article>
              )}
            </section>
          </div>
        </>
      )}
    </div>
  );
}
export function responseOutcome(response: InferenceResult['response']): { status: string; label: string } {
  const proposed = response.content.some(
    (block) =>
      block.type === 'tool_call' ||
      (block.type === 'provider_block' &&
        ['computer_call', 'function_call', 'tool_use'].includes(String(block.blockType))),
  );
  if (proposed || response.stopReason === 'tool_use') return { status: 'partial', label: 'Action proposed' };
  if (['end_turn', 'stop_sequence', 'completed', 'stop'].includes(response.stopReason ?? ''))
    return { status: 'succeeded', label: 'Response complete' };
  if (
    [
      'max_tokens',
      'max_output_tokens',
      'length',
      'content_filter',
      'refusal',
      'incomplete',
      'pause_turn',
      'failed',
      'cancelled',
    ].includes(response.stopReason ?? '')
  )
    return { status: 'partial', label: 'Partial response' };
  return { status: 'unknown', label: 'Response received' };
}
function CapabilityOption({
  label,
  capability,
  supported,
  checked,
  onChange,
}: {
  label: string;
  capability: string;
  supported: boolean;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="capability-option">
      <input
        type="checkbox"
        disabled={!supported}
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
      <span>
        {label}
        {!supported && (
          <small className="muted">
            Unavailable: provider does not support {capability.toLowerCase().replaceAll('_', ' ')}.
          </small>
        )}
      </span>
    </label>
  );
}
function StructuredResult({ text }: { text: string }) {
  try {
    return <JsonView label="Structured output" value={JSON.parse(text)} />;
  } catch {
    return (
      <p className="notice warning">
        The returned text is not valid JSON. Inspect the response and stop reason; do not assume the schema
        was satisfied.
      </p>
    );
  }
}
function CitationList({ value }: { value: unknown }) {
  const links = new Map<string, string>();
  function visit(item: unknown, depth: number) {
    if (depth > 12 || links.size >= 40 || !item || typeof item !== 'object') return;
    if (Array.isArray(item)) {
      item.forEach((child) => visit(child, depth + 1));
      return;
    }
    const object = item as Record<string, unknown>;
    const candidate =
      typeof object.url === 'string' ? object.url : typeof object.href === 'string' ? object.href : undefined;
    const url = safeHref(candidate);
    if (url)
      links.set(
        url,
        typeof object.title === 'string'
          ? object.title
          : typeof object.label === 'string'
            ? object.label
            : new URL(url).hostname,
      );
    Object.values(object).forEach((child) => visit(child, depth + 1));
  }
  visit(value, 0);
  if (!links.size) return null;
  return (
    <div className="stack">
      <h4>Source references</h4>
      {Array.from(links).map(([url, label]) => (
        <SafeLink key={url} href={url}>
          {label}
        </SafeLink>
      ))}
    </div>
  );
}
