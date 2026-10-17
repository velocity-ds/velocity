import { useEffect, useId, useRef, type ReactNode } from 'react';
import { AlertCircle, ArrowUpRight, Inbox, LoaderCircle, X } from 'lucide-react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ApiError, safeHref } from './api';

export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
  className,
}: {
  eyebrow?: string;
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <header className={className ? `page-header ${className}` : 'page-header'}>
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      <div className="header-actions">{actions}</div>
    </header>
  );
}
export const labels: Record<string, string> = {
  succeeded: 'Response complete',
  waiting_for_user: 'Needs input',
  running: 'Running',
  partial: 'Partial',
  blocked: 'Blocked',
  failed: 'Failed',
  cancelled: 'Cancelled',
  dispatched: 'In progress',
  settled: 'Settled',
};
export function Badge({ status = 'unknown', children }: { status?: string; children?: ReactNode }) {
  return (
    <span className={`badge status-${status.toLowerCase()}`}>
      <i aria-hidden="true" />
      {children ?? labels[status] ?? status.replaceAll('_', ' ')}
    </span>
  );
}
export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon">
        <Inbox size={25} />
      </span>
      <h3>{title}</h3>
      {description && <p>{description}</p>}
      {action}
    </div>
  );
}
export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  return (
    <div className="notice error" role="alert">
      <AlertCircle size={18} />
      <div>
        <strong>
          {error instanceof ApiError && error.status === 409
            ? 'Revision conflict'
            : error instanceof ApiError && error.status === 0
              ? 'Disconnected'
              : 'Unable to load or complete this action'}
        </strong>
        <p>{error instanceof Error ? error.message : String(error)}</p>
        {retry && (
          <button type="button" className="button" onClick={retry}>
            Refresh data
          </button>
        )}
      </div>
    </div>
  );
}
export function Loading({ label = 'Loading current data…' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <LoaderCircle size={18} className="spin" />
      {label}
    </div>
  );
}
export function JsonView({ value, label = 'View JSON' }: { value: unknown; label?: string }) {
  return (
    <details className="json-view">
      <summary>{label}</summary>
      <pre>{JSON.stringify(value, null, 2)}</pre>
    </details>
  );
}
export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}
export function SafeLink({ href, children }: { href?: string | null; children: ReactNode }) {
  const url = safeHref(href);
  return url ? (
    <a href={url} target="_blank" rel="noopener noreferrer">
      {children}
      <ArrowUpRight size={12} aria-hidden="true" />
    </a>
  ) : (
    <span>{children}</span>
  );
}
export function SafeMarkdown({ text }: { text?: string | null }) {
  return (
    <div className="markdown">
      <Markdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        components={{
          a: ({ href, children }) => <SafeLink href={href}>{children}</SafeLink>,
          img: ({ alt }) => <span className="muted">[Image: {alt || 'external image'}]</span>,
        }}
      >
        {text ?? ''}
      </Markdown>
    </div>
  );
}
export function Modal({
  title,
  children,
  onClose,
  variant = 'dialog',
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  variant?: 'dialog' | 'drawer';
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const node = ref.current;
    node?.showModal();
    return () => node?.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className={`modal ${variant === 'drawer' ? 'drawer' : ''}`}
      aria-labelledby={titleId}
      onCancel={onClose}
      onClick={(event) => {
        if (event.target === ref.current) onClose();
      }}
    >
      <div className="modal-header">
        <h2 id={titleId}>{title}</h2>
        <button type="button" className="icon-button" aria-label="Close dialog" onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      {children}
    </dialog>
  );
}
let applicationTimeZone: string | undefined;
/** The server's zone from /api/operator/status. Until it is known, dates render in the browser's zone. */
export function setApplicationTimeZone(zone: string | undefined) {
  applicationTimeZone = zone;
}
export function applicationZone(): string | undefined {
  return applicationTimeZone;
}
export function formatDate(value?: string | null): string {
  if (!value) return 'Unavailable';
  const options: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };
  try {
    return new Intl.DateTimeFormat('en-US', { ...options, timeZone: applicationTimeZone }).format(
      new Date(value),
    );
  } catch {
    return new Intl.DateTimeFormat('en-US', options).format(new Date(value));
  }
}
export function relativeTime(value?: string | null): string {
  if (!value) return 'Unknown';
  const minutes = Math.round((Date.now() - new Date(value).getTime()) / 60000);
  return minutes < 1
    ? 'Just now'
    : minutes < 60
      ? `${minutes}m ago`
      : minutes < 1440
        ? `${Math.floor(minutes / 60)}h ago`
        : `${Math.floor(minutes / 1440)}d ago`;
}
