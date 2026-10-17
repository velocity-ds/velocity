export type TaskStatus =
  | 'running'
  | 'waiting_for_user'
  | 'partial'
  | 'blocked'
  | 'succeeded'
  | 'failed'
  | 'cancelled';
export type Scope = {
  ownerAccountId?: string | null;
  workspaceId: string;
  requesterId: string;
  channelId: string;
  botId: string;
  origin: string;
};
export type Limits = {
  totalTokens: number;
  activeMillis: number;
  modelTurns: number;
  toolCalls: number;
  toolTimeoutMillis: number;
  outputPreviewChars: number;
};
export type CallApproval = {
  state: 'pending' | 'approved' | 'declined';
  requestedAt?: string | null;
  decidedAt?: string | null;
  decidedVia?: string | null;
  /** Trusted plain-language description of the held call, rendered by the tool from its arguments. */
  summary?: string | null;
};
export type ToolCall = {
  id: string;
  name: string;
  toolset?: string;
  input: Record<string, unknown>;
  state: string;
  progress?: string;
  inputError?: string;
  dispatchedAt?: string;
  approval?: CallApproval | null;
  settlement?: { error: boolean; outcome: string; preview?: string; artifact?: string; confirmedAt?: string };
};
export const awaitingApproval = (call: ToolCall) =>
  call.state === 'accepted' && call.approval?.state === 'pending';
export type InferenceBinding = {
  profileId: string;
  profileVersion: string;
  providerId: string;
  model: string;
  inputAllowance: number;
  maxOutputTokens: number;
  safetyMargin: number;
};
export type Task = {
  executionId: string;
  purpose?: 'generic';
  monitoring?: Record<string, unknown> | null;
  inference?: {
    binding: InferenceBinding;
    fallback?: InferenceBinding | null;
    transition?: {
      reason: string;
      sourceMessageCount: number;
      sourceModelTurns: number;
      sourceHistoryGeneration: number;
    } | null;
  } | null;
  partialReporting?: { reason: string; startedModelTurn: number; attempted: boolean } | null;
  contextDiagnostics?: {
    message_bytes?: number;
    schema_bytes?: number;
    instruction_bytes?: number;
    envelope_bytes?: number;
    estimated_input_tokens?: number;
    configured_input_allowance?: number;
    effective_input_allowance?: number;
    output_reserve?: number;
    remaining_cumulative_tokens?: number;
    estimator?: string;
    [key: string]: unknown;
  } | null;
  conversationId?: string;
  continuedFrom?: string | null;
  originalRequest: string;
  currentGoal: string;
  finalAnswer?: string;
  taskOutcome?: {
    status: 'completed' | 'partial' | 'blocked' | 'failed';
    criteria: string[];
    evidence: string[];
    limitations: string[];
    basis: 'model_report';
    goalGeneration: number;
    reportedAt: string;
  } | null;
  failure?: string;
  scope: Scope;
  status: TaskStatus;
  revision: number;
  createdAt: string;
  updatedAt: string;
  cancellationRequested: boolean;
  calls: ToolCall[];
  corrections: { id: string; text: string; createdAt: string }[];
  pendingQuestions: { id: string; text: string }[];
  blockers: string[];
  limits: Limits;
  automaticResumption: boolean;
  totalTokens: number;
  inputTokens: number;
  outputTokens: number;
  usageMissing: boolean;
  activeMillis: number;
  modelTurns: number;
  toolCalls: number;
  sourceTurn?: Record<string, string>;
  sourceMessages: { authorId: string; text: string; source: Record<string, string> }[];
  messages: { role: string; content: Record<string, unknown>[] }[];
};
export type TaskActions = {
  revision: number;
  owner: boolean;
  settled: boolean;
  steer: boolean;
  answer: boolean;
  approve: boolean;
  cancel: boolean;
  delete: boolean;
  continueTask: boolean;
  continuation: string | null;
};
export type SystemStatus = {
  codeExecution?: import('./codeExecution').CodeExecutionStatus;
  generatedAt: string;
  timezone: string;
  localScope: Scope;
  inference: {
    available: boolean;
    provider?: string;
    model?: string;
    capabilities: string[];
    connectivity?: string;
  };
  slack: { enabled: boolean; configured: boolean; lifecycleRunning?: boolean; connection: string };
  browser: {
    enabled: boolean;
    registered?: boolean;
    availability: string;
    headless: boolean;
    engine?: string;
    version?: string;
    supportedTools?: string[];
    [key: string]: unknown;
  };
  scheduling: { enabled: boolean; workerEnabled: boolean; [key: string]: unknown };
  secrets?: { enabled: boolean; available: boolean };
  webSearch?: { enabled: boolean; provider: string; configured: boolean; maxResults: number };
  mcp?: {
    enabled: boolean;
    confirmWrites: boolean;
    registeredTools: number;
    servers: {
      name: string;
      transport: string;
      state: string;
      registeredTools: number;
      skippedTools: number;
      missingTools: number;
    }[];
  };
  mongo: { configured: boolean; connectivity: string };
  agent?: { limits: Limits; automaticResumption: boolean };
  [key: string]: unknown;
};
