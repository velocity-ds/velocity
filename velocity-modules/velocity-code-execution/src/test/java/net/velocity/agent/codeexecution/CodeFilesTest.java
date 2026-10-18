package net.velocity.agent.codeexecution;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import net.velocity.util.Sha256;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeFilesTest {

    @Test
    void portableFilenamesAreAccepted() {
        assertThat(CodeFiles.filename("report.csv")).isEqualTo("report.csv");
        assertThat(CodeFiles.filename("Chart_2026-Q1.v2.png")).isEqualTo("Chart_2026-Q1.v2.png");
        assertThat(CodeFiles.filename("a".repeat(100))).hasSize(100);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "../x.csv", "a..b.csv", "x.", "-x.csv", ".hidden", "dir/x.csv", "CON.csv",
            "lpt0",
            "COM9.txt", "sp ace.csv", "é.csv"})
    void unsafeFilenamesAreRejected(String filename) {
        assertThatThrownBy(() -> CodeFiles.filename(filename))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Use a simple non-reserved filename of at most 100 ASCII characters");
    }

    @Test
    void destinationsMustBeUniqueIgnoringCase() {
        assertThatCode(() -> CodeFiles.unique(List.of("a.csv", "b.csv"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> CodeFiles.unique(List.of("a.csv", "A.CSV")))
                .hasMessage("Duplicate code file destination");
        assertThatThrownBy(() -> CodeFiles.unique(List.of("ok.csv", "../bad.csv")))
                .hasMessageContaining("simple non-reserved filename");
    }

    @Test
    void textFormatsAreTypedByExtension() {
        assertThat(CodeFiles.mediaType("data.csv", new byte[0])).isEqualTo("text/csv");
        assertThat(CodeFiles.mediaType("data.TSV", new byte[0])).isEqualTo("text/tab-separated-values");
        assertThat(CodeFiles.mediaType("run.log", new byte[0])).isEqualTo("text/plain");
        assertThat(CodeFiles.mediaType("notes.md", new byte[0])).isEqualTo("text/markdown");
        assertThat(CodeFiles.mediaType("out.json", new byte[0])).isEqualTo("application/json");
    }

    @Test
    void binaryFormatsMustCarryTheirSignature() {
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0};
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0};
        byte[] zip = new byte[]{'P', 'K', 3, 4, 0};
        byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
        assertThat(CodeFiles.mediaType("chart.png", png)).isEqualTo("image/png");
        assertThat(CodeFiles.mediaType("photo.jpeg", jpeg)).isEqualTo("image/jpeg");
        assertThat(CodeFiles.mediaType("photo.jpg", jpeg)).isEqualTo("image/jpeg");
        assertThat(CodeFiles.mediaType("book.xlsx", zip))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(CodeFiles.mediaType("doc.pdf", pdf)).isEqualTo("application/pdf");
        assertThatThrownBy(() -> CodeFiles.mediaType("chart.png", "not a png".getBytes()))
                .hasMessage("Invalid PNG output");
        assertThatThrownBy(() -> CodeFiles.mediaType("photo.jpg", png)).hasMessage("Invalid JPEG output");
        assertThatThrownBy(() -> CodeFiles.mediaType("book.xlsx", pdf))
                .hasMessage("Invalid spreadsheet output");
        assertThatThrownBy(() -> CodeFiles.mediaType("doc.pdf", zip)).hasMessage("Invalid PDF output");
        assertThatThrownBy(() -> CodeFiles.mediaType("tool.exe", zip))
                .hasMessage("Unsupported code output format");
        assertThatThrownBy(() -> CodeFiles.mediaType("noext", zip))
                .hasMessage("Unsupported code output format");
    }

    @Test
    void hashesAreSha256Hex() {
        byte[] bytes = "artifact".getBytes(StandardCharsets.UTF_8);
        assertThat(CodeFiles.hash(bytes)).isEqualTo(Sha256.hex(bytes)).matches("[0-9a-f]{64}");
    }
}
