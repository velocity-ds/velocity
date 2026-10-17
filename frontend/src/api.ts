export class ApiError extends Error {
  constructor(
    public status: number,
    public body: unknown,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** All requests stay on the serving origin. Mutations are never automatically retried. */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!path.startsWith('/api/')) throw new Error('API requests must use a relative /api/ URL');
  const headers = new Headers(init.headers);
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  let response: Response;
  try {
    response = await fetch(path, { ...init, credentials: 'same-origin', headers });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError(
      0,
      null,
      init.method && init.method !== 'GET'
        ? 'Connection lost. The operation may have reached the server. Check current state before submitting again.'
        : 'Velocity is disconnected. Check that the local server is running.',
    );
  }
  const raw = await response.text();
  let body: unknown = raw;
  if (headers.get('Accept') !== 'text/plain') {
    try {
      body = raw ? JSON.parse(raw) : undefined;
    } catch {
      /* Safe fallback errors. */
    }
  }
  if (!response.ok) {
    const data = body as Record<string, unknown> | null;
    const message =
      data && typeof data === 'object' && typeof (data.error ?? data.detail ?? data.message) === 'string'
        ? String(data.error ?? data.detail ?? data.message)
        : response.status === 503
          ? 'This capability is unavailable. Check Integrations & System.'
          : response.status === 409
            ? 'State changed. Refresh and review the latest revision before submitting again.'
            : `Request failed (${response.status}).`;
    throw new ApiError(response.status, body, message);
  }
  return body as T;
}

export const json = (method: string, body?: unknown): RequestInit => ({
  method,
  ...(body === undefined ? {} : { body: JSON.stringify(body) }),
});

export function safeHref(value?: string | null): string | undefined {
  if (!value) return undefined;
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
      ? url.href
      : undefined;
  } catch {
    return undefined;
  }
}

/** Leave one overlapping UTF-16 unit so an HTTP-encoded split surrogate is reread intact. */
export function artifactChunk(raw: string, limit = 24576): { text: string; complete: boolean } {
  if (raw.length < limit) return { text: raw, complete: true };
  let end = raw.length - 1;
  const last = raw.charCodeAt(end - 1);
  if (last >= 0xd800 && last <= 0xdbff) end--;
  return { text: raw.slice(0, end), complete: false };
}
