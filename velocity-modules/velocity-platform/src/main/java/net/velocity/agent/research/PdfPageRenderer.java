package net.velocity.agent.research;

import net.velocity.agent.CancellationHandle;

/**
 * Trusted bounded text-layer extraction and rasterization only; page interpretation belongs to the
 * configured inference adapter.
 */
public interface PdfPageRenderer {

    Document open(byte[] bytes, CancellationHandle cancellation, long deadlineNanos) throws Exception;

    interface Document extends AutoCloseable {

        int pageCount();

        boolean encrypted();

        byte[] render(int page) throws Exception;

        /**
         * The text layer of the first pages up to the unit limit, pages separated by form feeds and bounded to
         * {@code maxChars}. A scanned PDF yields little or no text; it is never OCR.
         */
        String text(int maxChars) throws Exception;

        @Override
        void close();
    }

    final class PdfRenderException extends RuntimeException {

        private final String status;

        public PdfRenderException(String status, String message) {
            super(message);
            if (!java.util.Set.of("encrypted", "unavailable", "partial").contains(status)) {
                throw new IllegalArgumentException("Unsupported PDF rendering outcome");
            }
            this.status = status;
        }

        public String status() {
            return status;
        }
    }
}
