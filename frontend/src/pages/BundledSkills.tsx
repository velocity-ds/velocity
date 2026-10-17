import { useMutation, useQuery } from '@tanstack/react-query';
import { api, json } from '../api';
import { ErrorState, Loading } from '../components';

type Bundle = {
  id: string;
  version: number;
  name: string;
  routing_description: string;
  core_instructions: string;
  references: { id: string; name: string; content: string }[];
};
type Installation = {
  status: string;
  database_committed: boolean;
  skill?: { id: string };
};

export default function BundledSkills({ onInstalled }: { onInstalled: (id: string) => Promise<void> }) {
  const query = useQuery({
    queryKey: ['bundled-skills'],
    queryFn: () =>
      api<{ feature_enabled: boolean; bundles: Bundle[] }>('/api/operator/skills/bundles', json('POST', {})),
    retry: false,
  });
  if (query.isPending) return <Loading label="Loading built-in skills" />;
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />;
  return (
    <section className="stack" aria-label="Built-in document skills">
      <h2>Built-in document skills</h2>
      <p>
        Original Velocity workflows for PDF and Excel files. Install a copy to your skills, then start a new
        task to use it. Installing again preserves your edits and settings.
      </p>
      <p className="notice">
        File processing requires the configured Python document runtime. Spreadsheet formulas need Excel or
        another calculation engine to recalculate; OCR is not included.
      </p>
      <div className="grid-2">
        {query.data.bundles.map((bundle) => (
          <BundleCard
            key={bundle.id}
            bundle={bundle}
            enabled={query.data.feature_enabled}
            onInstalled={onInstalled}
          />
        ))}
      </div>
    </section>
  );
}

function BundleCard({
  bundle,
  enabled,
  onInstalled,
}: {
  bundle: Bundle;
  enabled: boolean;
  onInstalled: (id: string) => Promise<void>;
}) {
  const mutation = useMutation({
    mutationFn: () =>
      api<Installation>(
        '/api/operator/skills/install',
        json('POST', { bundle_id: bundle.id, request_id: crypto.randomUUID() }),
      ),
    onSuccess: async (result) => {
      if (result.database_committed && result.status === 'saved' && result.skill) {
        await onInstalled(result.skill.id);
      }
    },
    retry: false,
  });
  return (
    <article className="panel stack">
      <h3>{bundle.name}</h3>
      <p>{bundle.routing_description}</p>
      <small>Bundle version {bundle.version}</small>
      <details>
        <summary>Preview {bundle.id.toUpperCase()} instructions</summary>
        <pre className="content-json">{bundle.core_instructions}</pre>
        {bundle.references.map((reference) => (
          <details key={reference.id}>
            <summary>{reference.name}</summary>
            <pre className="content-json">{reference.content}</pre>
          </details>
        ))}
      </details>
      <button className="button" disabled={!enabled || mutation.isPending} onClick={() => mutation.mutate()}>
        {mutation.isPending ? 'Installing…' : `Install ${bundle.id.toUpperCase()} skill`}
      </button>
      {mutation.data && (
        <p role="status" className="notice">
          {mutation.data.database_committed && mutation.data.status === 'saved'
            ? 'Installed. Existing edits and settings are preserved.'
            : `No committed change: ${mutation.data.status}.`}
        </p>
      )}
      {mutation.error && <ErrorState error={mutation.error} />}
    </article>
  );
}
