package net.velocity.agent.research;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import net.velocity.agent.config.UploadProperties;
import net.velocity.agent.state.UploadedFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadTypesTest {

    private static final UploadProperties LIMITS = new UploadProperties(true, 2, 1_024, 1_536);

    @Test
    void simpleFilenamesAreAccepted() {
        assertThat(UploadTypes.filename("report.pdf")).isEqualTo("report.pdf");
        assertThat(UploadTypes.filename("Q1_2026-notes.v2.md")).isEqualTo("Q1_2026-notes.v2.md");
        assertThat(UploadTypes.filename("a")).isEqualTo("a");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "../escape.txt", "a..b.txt", "trailing.", "-dash.txt", ".hidden",
            "sp ace.txt",
            "con.txt", "LPT1", "notes/report.txt", "ümläut.txt"})
    void unsafeFilenamesAreRejected(String filename) {
        assertThatThrownBy(() -> UploadTypes.filename(filename))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("simple non-reserved filename");
    }

    @Test
    void filenamesAreBoundedToOneHundredCharacters() {
        assertThat(UploadTypes.filename("a".repeat(100))).hasSize(100);
        assertThatThrownBy(() -> UploadTypes.filename("a".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mediaTypeComesFromSignaturesFirstThenTheExtension() {
        byte[] pdf = "%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII);
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
        assertThat(UploadTypes.mediaType("anything.txt", pdf)).isEqualTo("application/pdf");
        assertThat(UploadTypes.mediaType("photo.bin", png)).isEqualTo("image/png");
        assertThat(UploadTypes.mediaType("photo.txt", jpeg)).isEqualTo("image/jpeg");
        assertThat(UploadTypes.mediaType("Data.CSV", "a,b".getBytes())).isEqualTo("text/csv");
        assertThat(UploadTypes.mediaType("notes.md", "# x".getBytes())).isEqualTo("text/markdown");
        assertThat(UploadTypes.mediaType("book.docx", new byte[]{'P', 'K'}))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(UploadTypes.mediaType("unknown.bin", new byte[]{1, 2, 3}))
                .isEqualTo("application/octet-stream");
    }

    @Test
    void validationRejectsTheWholeBatchOnTheFirstProblem() {
        assertThatCode(() -> UploadTypes.validate(List.of(), new UploadProperties(false, 1, 1_024, 1_024)))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                () -> UploadTypes
                        .validate(List.of(file("a.txt", 10)), new UploadProperties(false, 1, 1_024, 1_024)))
                .hasMessage("File uploads are disabled");
        assertThatThrownBy(
                () -> UploadTypes
                        .validate(List.of(file("a.txt", 10), file("b.txt", 10), file("c.txt", 10)), LIMITS))
                .hasMessage("Attach at most 2 files");
        assertThatThrownBy(() -> UploadTypes.validate(List.of(file("a.txt", 10), file("A.TXT", 10)), LIMITS))
                .hasMessage("Duplicate upload filename: A.TXT");
        assertThatThrownBy(() -> UploadTypes.validate(List.of(file("a.exe", 10)), LIMITS))
                .hasMessageStartingWith("Unsupported file type for a.exe");
        assertThatThrownBy(() -> UploadTypes.validate(List.of(file("a.txt", 0)), LIMITS))
                .hasMessage("Empty file: a.txt");
        assertThatThrownBy(() -> UploadTypes.validate(List.of(file("a.txt", 1_025)), LIMITS))
                .hasMessage("a.txt exceeds the per-file limit of 1024 bytes");
        assertThatThrownBy(
                () -> UploadTypes.validate(List.of(file("a.txt", 1_000), file("b.txt", 1_000)), LIMITS))
                .hasMessage("Uploads exceed the total limit of 1536 bytes");
        assertThatCode(() -> UploadTypes.validate(List.of(file("a.txt", 1_000), file("b.txt", 500)), LIMITS))
                .doesNotThrowAnyException();
    }

    private static UploadedFile file(String name, int size) {
        return new UploadedFile(name, "text/plain", new byte[size]);
    }
}
