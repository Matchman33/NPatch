package top.nkbe.npatch.patch.wrapper;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PackControlTest {
    @Test public void cancellationBeforeTrackingClosesTheSuppliedStream() {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var stream = new ByteArrayInputStream(new byte[1]) {
            @Override public void close() { closed.set(true); }
        };
        var control = new PackControl((stage, done, total) -> {});
        control.cancel();
        assertThrows(CancellationException.class, () -> control.track(stream, PackControl.Stage.COPYING, 1));
        assertTrue(closed.get());
    }
    @Test public void reportsActualBytesAndRejectsFurtherReadsAfterCancellation() throws Exception {
        var counts = new ArrayList<Long>();
        var control = new PackControl((stage, done, total) -> counts.add(done));
        try (var input = control.track(new ByteArrayInputStream(new byte[20]), PackControl.Stage.COPYING, 20)) {
            assertEquals(20, input.readAllBytes().length);
            assertEquals(Long.valueOf(20), counts.get(counts.size() - 1));
            control.cancel();
            assertThrows(CancellationException.class, () -> input.read());
        }
    }
}
