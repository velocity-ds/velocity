import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, json } from '../api';
import { ErrorState, Field } from '../components';

type LearnResult = { status: string; database_committed: boolean; review_id?: string };

const outcomes: Record<string, string> = {
  queued:
    'Queued. A background review will read this task and save a user-owned skill; see Knowledge → Journey.',
  already_queued: 'A skill review of this task is already queued.',
  ineligible: 'This task is not eligible: only settled, successful interactive tasks can be learned from.',
  disabled: 'Background learning is disabled in server configuration.',
  skills_disabled: 'Skills are disabled in server configuration.',
  journal_capacity_exceeded: 'The learning journal is full of live reviews; try again later.',
  delivery_conflict: 'The task changed or was deleted; reload it and try again.',
};

/** Rendered only for a settled, successful task owned by the linked account. */
export function LearnSkill({ executionId }: { executionId: string }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [focus, setFocus] = useState('');
  const client = useQueryClient();
  const request = useMutation({
    mutationFn: () =>
      api<LearnResult>(
        `/api/operator/tasks/${encodeURIComponent(executionId)}/learn`,
        json('POST', {
          ...(name.trim() ? { name: name.trim() } : {}),
          ...(focus.trim() ? { focus: focus.trim() } : {}),
        }),
      ),
    retry: false,
    onSettled: () => void client.invalidateQueries({ queryKey: ['learning-reviews'] }),
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    request.mutate();
  }
  // A 403/409 carries the same {status} body as a success; show its outcome instead of a generic error.
  const rejected =
    request.error instanceof ApiError &&
    request.error.body &&
    typeof request.error.body === 'object' &&
    typeof (request.error.body as { status?: unknown }).status === 'string'
      ? (request.error.body as LearnResult)
      : null;
  const result = request.data ?? rejected;
  return (
    <div className="stack">
      {!open && !request.data ? (
        <button className="button" type="button" onClick={() => setOpen(true)}>
          Learn a skill from this task
        </button>
      ) : null}
      {open && !request.data ? (
        <form className="stack" onSubmit={submit}>
          <Field label="Skill name (optional)" hint="A class of task, not this one task.">
            <input maxLength={100} value={name} onChange={(event) => setName(event.target.value)} />
          </Field>
          <Field label="Guidance (optional)" hint="What the skill should capture or leave out.">
            <textarea
              rows={2}
              maxLength={300}
              value={focus}
              onChange={(event) => setFocus(event.target.value)}
            />
          </Field>
          <div className="toolbar">
            <button className="button primary" type="submit" disabled={request.isPending}>
              {request.isPending ? 'Queuing…' : 'Queue skill review'}
            </button>
            <button className="button" type="button" onClick={() => setOpen(false)}>
              Cancel
            </button>
          </div>
        </form>
      ) : null}
      {result && (
        <p role="status" className="notice">
          {outcomes[result.status] ?? `Not queued: ${result.status}.`}
        </p>
      )}
      {request.error && !rejected && <ErrorState error={request.error} />}
    </div>
  );
}
