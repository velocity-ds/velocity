import { useEffect } from 'react';
import { api, ApiError, json } from '../api';

type Attempt = {
  viewers: number;
  complete: boolean;
  controller: AbortController;
  timer?: ReturnType<typeof setTimeout>;
  wake?: () => void;
};
type Descriptor = {
  execution_revision: number;
  delivery_turn: number;
  response_digest: string;
  learning_enabled?: boolean;
};
const acknowledgements = new Map<string, Attempt>();
const retryDelays = [500, 1000, 2000, 4000, 8000, 16000];

async function acknowledge(key: string, executionId: string, revision: number, attempt: Attempt) {
  const path = `/api/operator/tasks/${encodeURIComponent(executionId)}`;
  for (let retry = 0; attempt.viewers > 0; retry++) {
    try {
      const descriptor = await api<Descriptor>(`${path}/delivery`, { signal: attempt.controller.signal });
      if (attempt.viewers === 0) return;
      if (
        descriptor.learning_enabled === false ||
        descriptor.execution_revision !== revision ||
        !descriptor.response_digest
      ) {
        attempt.complete = true;
        return;
      }
      await api(`${path}/acknowledge`, {
        ...json('POST', {
          delivery_turn: descriptor.delivery_turn,
          response_digest: descriptor.response_digest,
        }),
        signal: attempt.controller.signal,
      });
      attempt.complete = true;
      return;
    } catch (error) {
      // Worker settlement can lag the displayed final revision. Only this exact idempotent
      // acknowledgement is retried; ordinary operator writes retain their no-retry policy.
      const retryable =
        error instanceof ApiError && (error.status === 0 || error.status === 409 || error.status >= 500);
      if (!retryable || retry === retryDelays.length || attempt.viewers === 0) break;
      await new Promise<void>((resolve) => {
        attempt.wake = resolve;
        attempt.timer = setTimeout(resolve, retryDelays[retry]);
      });
      attempt.timer = undefined;
      attempt.wake = undefined;
    }
  }
  if (acknowledgements.get(key) === attempt) acknowledgements.delete(key);
}

/** Mounted only alongside a displayed, eligible answer owned by the linked account. Polling alone cannot acknowledge. */
export function DeliveryAcknowledgement({
  executionId,
  revision,
}: {
  executionId: string;
  revision: number;
}) {
  useEffect(() => {
    const key = `${executionId}:${revision}`;
    let attempt = acknowledgements.get(key);
    if (attempt?.complete) return;
    if (attempt) attempt.viewers++;
    else {
      attempt = { viewers: 1, complete: false, controller: new AbortController() };
      acknowledgements.set(key, attempt);
      const current = attempt;
      // Coalesce Strict Mode's setup/cleanup/setup before issuing a request.
      void Promise.resolve().then(() => acknowledge(key, executionId, revision, current));
      if (acknowledgements.size > 256) {
        const oldestComplete = [...acknowledgements].find(([, value]) => value.complete)?.[0];
        if (oldestComplete) acknowledgements.delete(oldestComplete);
      }
    }
    const current = attempt;
    return () => {
      current.viewers--;
      if (current.viewers > 0 || current.complete) return;
      current.controller.abort();
      if (current.timer) clearTimeout(current.timer);
      current.wake?.();
      if (acknowledgements.get(key) === current) acknowledgements.delete(key);
    };
  }, [executionId, revision]);
  return null;
}
