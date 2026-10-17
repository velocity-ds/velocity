import { useQueries, useQuery } from '@tanstack/react-query';
import { api } from './api';
import { applicationZone } from './components';

export type UsageTotals = {
  estimatedUsd: string | null;
  status: 'complete' | 'partial' | 'unpriced' | 'unavailable';
  requests: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  pricedRequests: number;
  unpricedRequests: number;
  incompleteRequests: number;
  historicalIncomplete?: boolean;
};
export type UsageRates = {
  version: string;
  inputPerMillion: string;
  outputPerMillion: string;
  cacheReadPerMillion: string | null;
  cacheCreationPerMillion: string | null;
  cacheCreation5mPerMillion: string | null;
  cacheCreation1hPerMillion: string | null;
};
export type UsageBreakdown = UsageTotals & {
  provider: string;
  model: string;
  activity: string;
  rates: UsageRates | null;
  cacheReadTokens: number;
  cacheCreationTokens: number;
  cacheCreation5mTokens: number;
  cacheCreation1hTokens: number;
  reasoningTokens: number;
  unsupportedCategories: string[];
};
export type UsageReport = UsageTotals & {
  from: string | null;
  to: string | null;
  breakdown: UsageBreakdown[];
  excludedActivity: string[];
};
export type UsageSummary = {
  currency: 'USD';
  timeZone: string;
  today: UsageReport;
  month: UsageReport;
  excludedActivity: string[];
};
export type UsageFilters = { from: string; to: string; provider: string; model: string; activity: string };

/** Display the server's exact decimal amount; never calculate or round accounting in the browser. */
export function estimatedDollars(value: string | null | undefined): string {
  if (typeof value !== 'string' || !/^\d+(?:\.\d+)?$/.test(value)) return 'Unavailable';
  const [whole, fraction = ''] = value.split('.');
  const significant = fraction.replace(/0+$/, '').padEnd(2, '0');
  return `$${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${significant}`;
}

export function applicationDate(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: applicationZone(),
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  return ['year', 'month', 'day'].map((key) => parts.find((part) => part.type === key)!.value).join('-');
}

export function usageRangeError(from: string, to: string): string | undefined {
  const valid = (value: string) =>
    /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    !Number.isNaN(Date.parse(`${value}T00:00:00Z`)) &&
    new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
  if (!valid(from) || !valid(to)) return 'Enter valid start and end dates.';
  const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000 + 1;
  if (days < 1) return 'End date must be on or after start date.';
  if (days > 366) return 'Choose a date range of at most 366 days.';
}

export function useUsageSummary() {
  return useQuery({
    queryKey: ['usage-summary'],
    queryFn: ({ signal }) => api<UsageSummary>('/api/operator/usage/summary', { signal }),
    refetchInterval: 15000,
  });
}

export function useUsageReport(filters: UsageFilters) {
  const query = new URLSearchParams(Object.entries(filters).filter(([, value]) => value));
  return useQuery({
    queryKey: ['usage-report', filters],
    queryFn: ({ signal }) => api<UsageReport>(`/api/operator/usage?${query}`, { signal }),
    enabled: !usageRangeError(filters.from, filters.to),
    refetchInterval: 15000,
  });
}

export function useTaskUsage(id: string) {
  return useQuery({
    queryKey: ['task-usage', id],
    queryFn: ({ signal }) =>
      api<UsageReport>(`/api/operator/tasks/${encodeURIComponent(id)}/usage`, { signal }),
    enabled: !!id,
    refetchInterval: 5000,
  });
}

export function useTaskEstimates(taskIds: string[]) {
  const ids = [...new Set(taskIds)].sort();
  const chunks: string[][] = [];
  for (let index = 0; index < ids.length; index += 100) chunks.push(ids.slice(index, index + 100));
  return useQueries({
    queries: chunks.map((chunk) => ({
      queryKey: ['task-estimates', chunk],
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        api<{ tasks: Record<string, UsageReport> }>(
          `/api/operator/usage/tasks?${new URLSearchParams({ ids: chunk.join(',') })}`,
          { signal },
        ),
      refetchInterval: 15000,
    })),
  });
}
