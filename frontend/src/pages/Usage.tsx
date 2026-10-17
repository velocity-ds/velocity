import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { applicationZone, ErrorState, Field, Loading, PageHeader } from '../components';
import { applicationDate, usageRangeError, useUsageReport, type UsageFilters } from '../usage';
import { activityLabel, CostEstimate, Coverage, UsageBreakdowns } from '../usagePresentation';

const activities = [
  'task',
  'delegated',
  'attachment',
  'monitoring',
  'scheduled',
  'learning',
  'playground',
  'unattributed',
];

export default function Usage() {
  const [params, setParams] = useSearchParams();
  const today = applicationDate();
  const applied: UsageFilters = {
    from: params.get('from') ?? `${today.slice(0, 7)}-01`,
    to: params.get('to') ?? today,
    provider: params.get('provider') ?? '',
    model: params.get('model') ?? '',
    activity: params.get('activity') ?? '',
  };
  const [draft, setDraft] = useState(applied);
  useEffect(
    () =>
      setDraft({
        from: applied.from,
        to: applied.to,
        provider: applied.provider,
        model: applied.model,
        activity: applied.activity,
      }),
    [applied.from, applied.to, applied.provider, applied.model, applied.activity],
  );
  const query = useUsageReport(applied);
  const rangeError = usageRangeError(draft.from, draft.to);
  const appliedError = usageRangeError(applied.from, applied.to);
  function update(key: keyof UsageFilters, value: string) {
    setDraft((previous) => ({ ...previous, [key]: value }));
  }
  return (
    <>
      <PageHeader
        title="AI usage & estimates"
        description="Recorded AI spending in USD. Estimates use saved server pricing, not provider invoices."
        actions={
          <button
            className="button"
            disabled={query.isFetching || !!appliedError}
            onClick={() => query.refetch()}
          >
            <RefreshCw size={16} /> Refresh usage
          </button>
        }
      />
      <section className="panel">
        <form
          className="toolbar usage-filters"
          onSubmit={(event) => {
            event.preventDefault();
            if (!rangeError)
              setParams(
                Object.fromEntries(
                  Object.entries(draft)
                    .map(([key, value]) => [key, value.trim()])
                    .filter(([, value]) => value),
                ),
              );
          }}
        >
          <Field label="From date">
            <input
              type="date"
              required
              value={draft.from}
              onChange={(event) => update('from', event.target.value)}
            />
          </Field>
          <Field label="Through date">
            <input
              type="date"
              required
              value={draft.to}
              onChange={(event) => update('to', event.target.value)}
            />
          </Field>
          <Field label="Provider">
            <input
              placeholder="All providers"
              maxLength={128}
              value={draft.provider}
              onChange={(event) => update('provider', event.target.value)}
            />
          </Field>
          <Field label="Model">
            <input
              placeholder="All models"
              maxLength={256}
              value={draft.model}
              onChange={(event) => update('model', event.target.value)}
            />
          </Field>
          <Field label="Activity">
            <select value={draft.activity} onChange={(event) => update('activity', event.target.value)}>
              <option value="">All activity</option>
              {activities.map((item) => (
                <option key={item} value={item}>
                  {activityLabel(item)}
                </option>
              ))}
            </select>
          </Field>
          <button className="button primary" disabled={!!rangeError}>
            Apply filters
          </button>
        </form>
        {rangeError && (
          <p className="notice warning" role="alert">
            {rangeError}
          </p>
        )}
        <p className="panel-footer">
          Inclusive dates in {applicationZone() ?? 'the application time zone'}, up to 366 days. Usage belongs
          to the request start date. Provider and model filters match exact names.
        </p>
      </section>
      <section className="panel section-space" aria-label="Usage report">
        {appliedError ? (
          <p className="notice warning">{appliedError}</p>
        ) : query.isPending ? (
          <Loading label="Loading usage report…" />
        ) : query.isError ? (
          <ErrorState error={query.error} retry={() => query.refetch()} />
        ) : (
          query.data && (
            <>
              <div className="panel-heading">
                <div>
                  <h2>Estimated spending</h2>
                  <p>
                    {query.data.from} through {query.data.to} · USD
                  </p>
                </div>
                <CostEstimate value={query.data} />
              </div>
              <div className="panel-body stack">
                <p>
                  {query.data.inputTokens.toLocaleString()} input · {query.data.outputTokens.toLocaleString()}{' '}
                  output · <strong>{query.data.totalTokens.toLocaleString()} total recorded tokens</strong>
                </p>
                <Coverage report={query.data} />
                <h3>Provider, model, activity & pricing</h3>
                <p className="muted">
                  Open a row to inspect token categories and the immutable pricing used. Background learning
                  is separate from task totals; both contribute to account spending.
                </p>
                <UsageBreakdowns breakdown={query.data.breakdown} />
              </div>
              <div className="panel-footer usage-disclaimer">
                Financial usage records remain after tasks are deleted. Missing usage or prices can understate
                spending.
              </div>
            </>
          )
        )}
      </section>
    </>
  );
}
