/** This adapter never retains response bodies or server exception text in errors.
 * Credential writes deliberately bypass query/mutation caches and automatic retries. */
export class SecretsError extends Error {
  constructor(public readonly kind: 'conflict' | 'capacity' | 'invalid' | 'unavailable' | 'unknown') {
    super(
      {
        conflict: 'This record changed. Reload and review its latest version before editing again.',
        capacity: 'The vault has reached its configured record or operation limit. No change was saved.',
        invalid: 'The request was rejected. Check the field limits and exact HTTPS origins.',
        unavailable:
          'Secrets are unavailable. Check the registered local account, storage and configured encryption key.',
        unknown: 'The outcome is unknown. Reconcile this operation before making another change.',
      }[kind],
    );
  }
}

export type CredentialMetadata = {
  credential_id: string;
  revision: number;
  service: string;
  account_label: string;
  credential_origins: string[];
  post_login_origins: string[];
  application_origins?: string[];
  callback_urls?: string[];
  resource_origins?: string[];
  enabled: boolean;
  deleted: boolean;
  created_at: string;
  updated_at: string;
};
export type SecretList = {
  status: string;
  availability: { enabled: boolean; available: boolean };
  credentials: CredentialMetadata[];
};
export type SecretWrite = {
  status: string;
  credential?: CredentialMetadata;
  request_id: string;
  database_committed?: boolean | null;
};
export type SecretOperation = {
  status: 'committed' | 'not_found' | 'unavailable';
  credential_id?: string;
  revision?: number;
  outcome?: string;
};

export const secretsPath = '/api/operator/secrets';

export async function secretsRequest<T>(suffix = '', method = 'GET', payload?: object): Promise<T> {
  const mutation = method !== 'GET';
  let response: Response;
  try {
    response = await fetch(`${secretsPath}${suffix}`, {
      method,
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json', ...(mutation ? { 'Content-Type': 'application/json' } : {}) },
      ...(payload ? { body: JSON.stringify(payload) } : {}),
    });
  } catch {
    throw new SecretsError(mutation ? 'unknown' : 'unavailable');
  }
  if (!response.ok) {
    // Even a response that reflects submitted input must never appear in the UI or caches.
    if (response.status === 409) {
      let capacity = false;
      try {
        const status = (await response.json())?.status;
        capacity = status === 'capacity_exceeded' || status === 'receipt_capacity';
      } catch {
        /* Only the allowlisted status affects the message; raw content is discarded. */
      }
      throw new SecretsError(capacity ? 'capacity' : 'conflict');
    }
    if ([400, 403, 404, 413, 422].includes(response.status)) throw new SecretsError('invalid');
    throw new SecretsError(mutation ? 'unknown' : 'unavailable');
  }
  try {
    return (await response.json()) as T;
  } catch {
    throw new SecretsError(mutation ? 'unknown' : 'unavailable');
  }
}
