/** Protected server receipts; none of these fields grant execution or artifact access. */
export type CodeArtifact = {
  artifactId: string;
  filename: string;
  mediaType: string;
  byteSize: number;
  checksum: string;
  createdAt: string;
};

export type CodeRun = {
  runId: string;
  status: string;
  goalGeneration: number;
  currentGeneration: boolean;
  exitCode: number | null;
  stdout: string;
  stderr: string;
  stdoutTruncated: boolean;
  stderrTruncated: boolean;
  durationMillis: number | null;
  failureCode?: string | null;
  cleanupPending: boolean;
  outputArtifacts: CodeArtifact[];
};

export type CodeExecutionStatus = {
  enabled: boolean;
  state: 'disabled' | 'unconfigured' | 'available' | 'unavailable';
  reason?: string | null;
  runtimeProfile: string;
  libraries: Record<string, string>;
  limits: {
    concurrency: number;
    queueCapacity: number;
    cpu: number;
    memoryMiB: number;
    processes: number;
    storageMiB: number;
    sourceBytes: number;
    stdoutBytes: number;
    stderrBytes: number;
    inputFiles: number;
    inputBytes: number;
    outputFiles: number;
    outputFileBytes: number;
    outputTotalBytes: number;
  };
};

export const codeRunsPath = (taskId: string) => `/api/operator/tasks/${encodeURIComponent(taskId)}/code-runs`;

/** Build download destinations locally; never navigate to a guest-supplied URL. */
export const codeArtifactDownloadPath = (taskId: string, artifactId: string) =>
  `/api/operator/tasks/${encodeURIComponent(taskId)}/artifacts/${encodeURIComponent(artifactId)}/download`;

export const codeBytes = (value: number) => `${value.toLocaleString()} bytes`;
