package top.nkbe.npatch.patch.wrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class WrapperRuntimeTest {
    @Test public void acceptsBothSupportedArchitectures() throws Exception {
        assertEquals(3, WrapperRuntime.read(archive(false)).size());
    }

    @Test public void rejectsWrongElfAndEmptyPayload() throws Exception {
        assertThrows(IOException.class, () -> WrapperRuntime.read(archive(true)));
        assertThrows(IOException.class, () -> WrapperRuntime.read(new byte[0]));
    }

    @Test public void rejectsUnexpectedPaths() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("../libnpatch.so"));
            zip.write(new byte[20]);
        }
        assertThrows(IOException.class, () -> WrapperRuntime.read(output.toByteArray()));
    }

    private byte[] archive(boolean wrongMachine) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("loader.bin"));
            zip.write(new byte[] {'d', 'e', 'x', '\n', '0', '3', '5', 0});
            zip.closeEntry();
            for (String abi : new String[] {"arm64-v8a", "x86_64"}) {
                byte[] elf = new byte[20];
                elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F'; elf[5] = 1;
                elf[4] = 2;
                elf[18] = (byte) (wrongMachine ? 0 : abi.startsWith("arm64") ? 183 : 62);
                zip.putNextEntry(new ZipEntry("so/" + abi + "/libnpatch.so"));
                zip.write(elf);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
