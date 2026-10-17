import { ArrowUpRight, Coins } from 'lucide-react';
import { Link } from 'react-router-dom';
import { ErrorState, Loading } from './components';
import {
  estimatedDollars,
  useUsageSummary,
  type UsageBreakdown,
  type UsageReport,
  type UsageTotals,
} from './usage';

const estimateLabels = {
  complete: 'Complete estimate',
  partial: 'Partial estimate',
  unpriced: 'Unpriced',
  unavailable: 'Estimate unavailable',
};

export function CostEstimate({
  value,
  loading = false,
  error = false,
}: {
  value?: UsageTotals;
  loading?: boolean;
  error?: boolean;
}) {
  if (error)
    return (
      <span className="cost-estimate muted">
        Estimate unavailable<small>Could not load usage</small>
      </span>
    );
  if (loading)
    return (
      <span className="cost-estimate muted" role="status">
        Loading estimate…
      </span>
    );
  if (!value) return <span className="cost-estimate muted">Estimate unavailable</span>;
  const amount = value.status === 'unpriced' || value.status === 'unavailable' ? null : value.estimatedUsd;
  return (
    <span className={`cost-estimate cost-${value.status}`}>
      <strong>{amount === null ? estimateLabels[value.status] : estimatedDollars(amount)}</strong>
      {amount !== null && <small>{estimateLabels[value.status]}</small>}
      {value.incompleteRequests > 0 && <small>Incomplete provider usage</small>}
    </span>
  );
}

export function Coverage({ report }: { report: UsageReport }) {
  return (
    <div className="stack usage-coverage">
      <div className="usage-counts">
        <span>
          <strong>{report.requests.toLocaleString()}</strong> requests recorded
        </span>
        <span>
          <strong>{report.pricedRequests.toLocaleString()}</strong> priced
        </span>
        <span>
          <strong>{report.unpricedRequests.toLocaleString()}</strong> unpriced
        </span>
        <span>
          <strong>{report.incompleteRequests.toLocaleString()}</strong> incomplete
        </span>
      </div>
      {(report.status !== 'complete' || report.historicalIncomplete) && (
        <p className="notice warning">
          {report.status === 'unpriced'
            ? 'Recorded usage has no complete pricing. Unknown cost is not zero, including local inference.'
            : report.status === 'unavailable'
              ? 'A cost estimate is unavailable. Missing provider usage or historical accounting data cannot establish a cost.'
              : 'This is a partial estimate. Recorded spending may understate actual cost.'}
          {report.historicalIncomplete && ' Earlier usage is missing from the accounting records.'}
        </p>
      )}
      {report.excludedActivity.length > 0 && (
        <div className="muted fine-print">
          <strong>Coverage limitations</strong>
          <ul>
            {report.excludedActivity.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

export function SpendingOverview() {
  const query = useUsageSummary();
  return (
    <section className="panel usage-overview section-space" aria-label="Estimated AI spending">
      <div className="panel-heading">
        <div>
          <h2>
            <Coins size={18} /> Estimated AI spending
          </h2>
          <p>
            USD · {query.data?.timeZone ?? 'application time zone'} · Includes recorded background activity
          </p>
        </div>
        <Link to="/usage" className="text-link">
          View usage <ArrowUpRight size={14} />
        </Link>
      </div>
      {query.isPending ? (
        <Loading label="Loading spending estimates…" />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => query.refetch()} />
      ) : (
        query.data && (
          <>
            <div className="usage-summary-grid">
              {[
                {
                  label: 'Today',
                  report: query.data.today,
                },
                {
                  label: 'This month',
                  report: query.data.month,
                },
              ].map(({ label, report }) => (
                <div className="usage-summary" key={label}>
                  <h3>{label}</h3>
                  <CostEstimate value={report} />
                  <p className="muted fine-print">
                    {report.totalTokens.toLocaleString()} recorded tokens · {report.requests.toLocaleString()}{' '}
                    requests
                  </p>
                  {(report.status !== 'complete' || report.historicalIncomplete) && (
                    <p className="notice warning">
                      Accounting is incomplete. Recorded spending may understate actual cost.
                    </p>
                  )}
                </div>
              ))}
            </div>
            <div className="panel-footer usage-disclaimer">
              Estimates from configured prices, not provider invoices.
              {query.data.excludedActivity.length > 0 && <span>{query.data.excludedActivity.join(' ')}</span>}
            </div>
          </>
        )
      )}
    </section>
  );
}

export function UsageBreakdowns({ breakdown }: { breakdown: UsageBreakdown[] }) {
  if (!breakdown.length) return <p className="muted">No request breakdown is available.</p>;
  return (
    <div className="stack">
      {breakdown.map((item, index) => (
        <details
          className="usage-breakdown"
          key={`${item.provider}:${item.model}:${item.activity}:${item.rates?.version}:${index}`}
        >
          <summary>
            <span>
              <strong>
                {item.provider} / {item.model}
              </strong>
              <small>
                {activityLabel(item.activity)} · {item.requests.toLocaleString()} requests
              </small>
            </span>
            <CostEstimate value={item} />
          </summary>
          <div className="panel-body stack">
            <p className="muted fine-print">
              {item.rates
                ? `Pricing version: ${item.rates.version}. Saved rates in USD per million tokens.`
                : 'No pricing snapshot is available. Unknown cost is not zero.'}
            </p>
            <div className="table-wrap">
              <table className="data-table usage-token-table">
                <thead>
                  <tr>
                    <th>Token category</th>
                    <th>Recorded tokens</th>
                    <th>Saved rate / million</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Input (including cache)</td>
                    <td>{item.inputTokens.toLocaleString()}</td>
                    <td>See uncached and cache categories</td>
                  </tr>
                  <tr>
                    <td>Uncached input</td>
                    <td>
                      {tokenRemainder(item.inputTokens, item.cacheReadTokens, item.cacheCreationTokens)}
                    </td>
                    <td>{estimatedDollars(item.rates?.inputPerMillion)}</td>
                  </tr>
                  <tr>
                    <td>Output (including reasoning)</td>
                    <td>{item.outputTokens.toLocaleString()}</td>
                    <td>{estimatedDollars(item.rates?.outputPerMillion)}</td>
                  </tr>
                  <tr>
                    <td>Cache read</td>
                    <td>{item.cacheReadTokens.toLocaleString()}</td>
                    <td>{estimatedDollars(item.rates?.cacheReadPerMillion)}</td>
                  </tr>
                  <tr>
                    <td>Cache creation (all durations)</td>
                    <td>{item.cacheCreationTokens.toLocaleString()}</td>
                    <td>See duration categories</td>
                  </tr>
                  <tr>
                    <td>Cache creation · unspecified duration</td>
                    <td>
                      {tokenRemainder(
                        item.cacheCreationTokens,
                        item.cacheCreation5mTokens,
                        item.cacheCreation1hTokens,
                      )}
                    </td>
                    <td>{estimatedDollars(item.rates?.cacheCreationPerMillion)}</td>
                  </tr>
                  <tr>
                    <td>Cache creation · 5 minutes</td>
                    <td>{item.cacheCreation5mTokens.toLocaleString()}</td>
                    <td>{estimatedDollars(item.rates?.cacheCreation5mPerMillion)}</td>
                  </tr>
                  <tr>
                    <td>Cache creation · 1 hour</td>
                    <td>{item.cacheCreation1hTokens.toLocaleString()}</td>
                    <td>{estimatedDollars(item.rates?.cacheCreation1hPerMillion)}</td>
                  </tr>
                  <tr>
                    <td>Reasoning (included in output)</td>
                    <td>{item.reasoningTokens.toLocaleString()}</td>
                    <td>Included in output rate</td>
                  </tr>
                  <tr>
                    <th>Total</th>
                    <td>{item.totalTokens.toLocaleString()}</td>
                    <td>Cache and reasoning are not added twice</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p className="muted fine-print">
              Input rate applies to uncached input. Cache rates apply to their respective categories.
            </p>
            <p>
              {item.pricedRequests.toLocaleString()} priced · {item.unpricedRequests.toLocaleString()}{' '}
              unpriced · {item.incompleteRequests.toLocaleString()} incomplete requests
            </p>
            {item.unsupportedCategories.length > 0 && (
              <p className="notice warning">
                Unsupported pricing categories: {item.unsupportedCategories.join(', ')}. The estimate is
                partial.
              </p>
            )}
          </div>
        </details>
      ))}
    </div>
  );
}

export function activityLabel(value: string): string {
  return value.replaceAll('_', ' ').replace(/^\w/, (letter) => letter.toUpperCase());
}

function tokenRemainder(total: number, ...subsets: number[]): string {
  const remainder = subsets.reduce((value, subset) => value - subset, total);
  return [total, ...subsets, remainder].every((value) => Number.isSafeInteger(value) && value >= 0)
    ? remainder.toLocaleString()
    : 'Unavailable';
}
