package net.velocity.agent.io;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErasingByteArrayOutputStreamTest {

    /** Exposes the protected backing array so erasure can be observed. */
    private static final class Inspectable extends ErasingByteArrayOutputStream {

        byte[] backing() {
            return buf;
        }
    }

    @Test
    void writesAccumulateAcrossBufferGrowth() {
        var stream = new Inspectable();
        byte[] first = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        stream.writeBytes(first);
        stream.write('!');
        stream.write(first, 10, 5);
        assertThat(stream.size()).isEqualTo(first.length + 1 + 5);
        assertThat(stream.toString(StandardCharsets.US_ASCII))
                .isEqualTo("0123456789abcdefghijklmnopqrstuvwxyz!abcde");
        assertThat(stream.backing().length).isGreaterThanOrEqualTo(stream.size());
    }

    @Test
    void resetAndCloseEraseTheBuffer() {
        var stream = new Inspectable();
        stream.writeBytes("secret material".getBytes(StandardCharsets.US_ASCII));
        stream.reset();
        assertThat(stream.size()).isZero();
        assertThat(stream.toByteArray()).isEmpty();
        assertThat(stream.backing()).containsOnly((byte) 0);

        stream.writeBytes("more".getBytes(StandardCharsets.US_ASCII));
        stream.close();
        assertThat(stream.size()).isZero();
        assertThat(stream.backing()).containsOnly((byte) 0);
    }

    @Test
    void rangeWritesAreBoundsChecked() {
        var stream = new ErasingByteArrayOutputStream();
        byte[] bytes = new byte[4];
        assertThatThrownBy(() -> stream.write(bytes, 2, 3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> stream.write(bytes, -1, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(stream.size()).isZero();
    }
}
