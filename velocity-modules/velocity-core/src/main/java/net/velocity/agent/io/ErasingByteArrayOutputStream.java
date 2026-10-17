package net.velocity.agent.io;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;

/** Owned temporary bytes; erase replaced backing arrays as well as the current buffer on close. */
public class ErasingByteArrayOutputStream extends ByteArrayOutputStream {

    @Override
    public synchronized void write(int value) {
        grow(1);
        buf[count++] = (byte) value;
    }

    @Override
    public synchronized void write(byte[] value, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, value.length);
        grow(length);
        System.arraycopy(value, offset, buf, count, length);
        count += length;
    }

    @Override
    public void writeBytes(byte[] value) {
        write(value, 0, value.length);
    }

    private void grow(int length) {
        int needed = Math.addExact(count, length);
        if (needed > buf.length) {
            byte[] previous = buf;
            buf = Arrays.copyOf(previous, Math.max(needed, Math.multiplyExact(previous.length, 2)));
            Arrays.fill(previous, (byte) 0);
        }
    }

    @Override
    public synchronized void reset() {
        Arrays.fill(buf, (byte) 0);
        super.reset();
    }

    @Override
    public void close() {
        reset();
    }
}
