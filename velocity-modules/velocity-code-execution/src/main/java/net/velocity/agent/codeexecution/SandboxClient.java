package net.velocity.agent.codeexecution;

import java.util.List;

import net.velocity.agent.CancellationHandle;

/** Trusted sandbox lifecycle. No implementation may execute generated code on the Velocity host. */
public interface SandboxClient {

    int MAX_SOURCE_BYTES = 65_536;
    int MAX_STREAM_BYTES = 65_536;
    int MAX_INPUT_FILES = 10;
    int MAX_INPUT_BYTES = 25 * 1024 * 1024;
    int MAX_OUTPUT_FILES = 10;
    int MAX_FILE_BYTES = 10 * 1024 * 1024;
    int MAX_OUTPUT_BYTES = 25 * 1024 * 1024;
    String PROFILE = "Python 3.12; pandas 2.3.3; NumPy 2.3.4; matplotlib 3.10.7; openpyxl 3.1.5; "
            + "pypdf 6.10.0; pdfplumber 0.11.9; reportlab 4.4.9; pypdfium2 5.13.0";

    Availability availability();

    /** Caller must durably save the exact intended resource identity before invoking this. */
    void createAndStart(RunSpec spec) throws Exception;

    /** Waits for trusted supervisor evidence and all untrusted processes/I/O to settle. */
    Result await(RunSpec spec, CancellationHandle cancellation) throws Exception;

    /** Exact resource inspection only; this never starts or terminates code. */
    Result inspect(RunSpec spec) throws Exception;

    /** Must confirm the entire untrusted process tree stopped, or return uncertain evidence. */
    Result terminate(RunSpec spec) throws Exception;

    /** Refuses resources without matching installation/run labels. */
    boolean remove(RunSpec spec) throws Exception;

    record Availability(boolean available, String reason, String profile) {}

    record InputFile(String filename, byte[] bytes) {

        public InputFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record OutputFile(String filename, byte[] bytes, String mediaType, String sha256) {

        public OutputFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record RunSpec(
            String resourceName,
            String runId,
            String code,
            List<InputFile> inputs,
            List<String> outputFiles,
            long executionMillis,
            long operationDeadlineNanos,
            String runtimeImage) {

        public RunSpec {
            inputs = List.copyOf(inputs);
            outputFiles = List.copyOf(outputFiles);
            runtimeImage = runtimeImage == null ? "" : runtimeImage;
        }
    }

    /** status: running, succeeded, failed, timed_out, cancelled, resource_limited, missing_outputs, uncertain. */
    record Result(
            String status,
            Integer exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            long durationMillis,
            List<OutputFile> outputFiles,
            String failureCode,
            boolean terminationConfirmed) {

        public Result {
            outputFiles = List.copyOf(outputFiles);
        }

        public static Result uncertain(String code) {
            return new Result("uncertain", null, "", "", false, false, 0, List.of(), code, false);
        }

        public static Result running() {
            return new Result("running", null, "", "", false, false, 0, List.of(), null, false);
        }
    }
}
