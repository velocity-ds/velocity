import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from './api';
import type { SystemStatus, Task, TaskActions } from './types';

export const terminal = (task: Pick<Task, 'status'>) =>
  task.status !== 'running' && task.status !== 'waiting_for_user';
export const newerTask = (old: Task | undefined, incoming: Task): Task =>
  old && old.revision > incoming.revision ? old : incoming;
export function mergeTasks(old: Task[] | undefined, incoming: Task[]) {
  const previous = new Map(old?.map((task) => [task.executionId, task]));
  return incoming.map((task) => newerTask(previous.get(task.executionId), task));
}
/** Compare persisted UTC instants without rounding away nanoseconds or misordering whole seconds. */
export function compareInstants(left: string, right: string): number {
  const pattern = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/;
  const a = pattern.exec(left);
  const b = pattern.exec(right);
  return a && b
    ? a[1].localeCompare(b[1]) || (a[2] ?? '').padEnd(9, '0').localeCompare((b[2] ?? '').padEnd(9, '0'))
    : new Date(left).getTime() - new Date(right).getTime();
}
export const currentActions = (
  task: Task | undefined,
  actions: TaskActions | undefined,
): TaskActions | undefined => (task && actions?.revision === task.revision ? actions : undefined);
export function useTasks() {
  return useQuery({
    queryKey: ['tasks'],
    queryFn: ({ signal }) =>
      persistedRead<Task[]>('/api/agent/executions', '/api/operator/tasks/saved', signal),
    refetchInterval: 3000,
    structuralSharing: (old, incoming) => mergeTasks(old as Task[] | undefined, incoming as Task[]),
  });
}
/** With a live event stream connected, polling becomes a slow safety net instead of the main feed. */
export function useTask(id: string, live = false) {
  return useQuery({
    queryKey: ['task', id],
    queryFn: ({ signal }) =>
      persistedRead<Task>(
        `/api/agent/executions/${encodeURIComponent(id)}`,
        `/api/operator/tasks/${encodeURIComponent(id)}/saved`,
        signal,
      ),
    refetchInterval: live ? 15000 : 2000,
    structuralSharing: (old, incoming) => newerTask(old as Task | undefined, incoming as Task),
  });
}
export type TaskStream = {
  /** The server-sent event stream is open; saved changes arrive without polling. */
  connected: boolean;
  /** Assistant text of the model turn in flight, retracted once the saved message carries it. */
  liveText: string;
};
/**
 * Subscribes to a task's live events. Snapshot events replace the cached task (newest revision wins), delta
 * events accumulate streamed assistant text, and the stream closes itself once the task is terminal. The
 * browser reconnects after a dropped connection; polling covers the gap.
 */
export function useTaskStream(id: string): TaskStream {
  const client = useQueryClient();
  const [connected, setConnected] = useState(false);
  const [liveText, setLiveText] = useState('');
  const turn = useRef<number | null>(null);
  useEffect(() => {
    if (!id || typeof EventSource === 'undefined') return;
    const source = new EventSource(`/api/agent/executions/${encodeURIComponent(id)}/events`);
    let closed = false;
    const close = () => {
      if (closed) return;
      closed = true;
      source.close();
      setConnected(false);
    };
    source.onopen = () => setConnected(true);
    source.onerror = () => setConnected(false);
    source.addEventListener('snapshot', (event) => {
      try {
        const incoming = JSON.parse((event as MessageEvent<string>).data) as Task;
        client.setQueryData<Task>(['task', id], (old) => newerTask(old, incoming));
        void client.invalidateQueries({ queryKey: ['task-actions', id] });
        void client.invalidateQueries({ queryKey: ['tasks'] });
        if (terminal(incoming)) {
          setLiveText('');
          close();
        }
      } catch {
        /* A malformed event is ignored; polling still refreshes the task. */
      }
    });
    source.addEventListener('delta', (event) => {
      try {
        const delta = JSON.parse((event as MessageEvent<string>).data) as { modelTurn: number; text: string };
        if (turn.current !== delta.modelTurn) {
          turn.current = delta.modelTurn;
          setLiveText(delta.text);
        } else {
          setLiveText((previous) => previous + delta.text);
        }
      } catch {
        /* Ignored; see above. */
      }
    });
    source.addEventListener('clear', () => {
      turn.current = null;
      setLiveText('');
    });
    source.addEventListener('deleted', () => {
      close();
      void client.invalidateQueries({ queryKey: ['tasks'] });
    });
    return () => {
      close();
      turn.current = null;
      setLiveText('');
    };
  }, [id, client]);
  return { connected, liveText };
}
async function persistedRead<T>(usual: string, saved: string, signal: AbortSignal): Promise<T> {
  try {
    return await api<T>(usual, { signal });
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 503) throw error;
    return api<T>(saved, { signal });
  }
}
export function useTaskActions(id: string) {
  return useQuery({
    queryKey: ['task-actions', id],
    queryFn: ({ signal }) =>
      api<TaskActions>(`/api/operator/tasks/${encodeURIComponent(id)}/actions`, { signal }),
    refetchInterval: 2000,
  });
}
export function useSystem() {
  return useQuery({
    queryKey: ['system'],
    queryFn: ({ signal }) => api<SystemStatus>('/api/operator/status', { signal }),
    refetchInterval: 30000,
  });
}
