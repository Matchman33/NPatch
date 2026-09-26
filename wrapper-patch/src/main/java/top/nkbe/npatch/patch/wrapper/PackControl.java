package top.nkbe.npatch.patch.wrapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation checkpoints and byte progress shared by the manager and packer. */
public final class PackControl {
    public enum Stage { PREPARING, COPYING, HASHING, EMBEDDING, SIGNING, VERIFYING, EXPORTING }
    public interface Listener { void progress(Stage stage, long completed, long total); }
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Listener listener;

    public PackControl(Listener listener) { this.listener = listener; }
    public void cancel() { cancelled.set(true); }
    public void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Operation cancelled");
        }
    }
    public void report(Stage stage, long completed, long total) {
        check();
        listener.progress(stage, completed, total);
    }
    public InputStream track(InputStream input, Stage stage, long total) {
        try { report(stage, 0, total); }
        catch (RuntimeException error) {
            try { input.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
        return new FilterInputStream(input) {
            private long count;
            private long lastReported;
            private long lastTime = System.nanoTime();
            private void advance(int length) {
                if (length > 0) count += length;
                long now = System.nanoTime();
                if (length < 0 || (count - lastReported >= 1024 * 1024 && now - lastTime >= 100_000_000L)) {
                    report(stage, count, total);
                    lastReported = count;
                    lastTime = now;
                }
            }
            @Override public int read() throws IOException {
                check();
                int value = in.read();
                advance(value < 0 ? -1 : 1);
                return value;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                check();
                int value = in.read(buffer, offset, length);
                advance(value);
                return value;
            }
        };
    }
}
