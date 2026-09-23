package top.nkbe.npatch.patch.wrapper;

import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;

final class WrapperRuntime {
    private WrapperRuntime() {}

    static Map<String, byte[]> read(byte[] archive) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        if (archive == null || archive.length > 64 * 1024 * 1024) throw new IOException("Missing or oversized NPatch runtime");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.equals("loader.bin") && !name.equals("so/arm64-v8a/libnpatch.so") && !name.equals("so/x86_64/libnpatch.so")) {
                    throw new IOException("Unsupported Hook runtime entry: " + name);
                }
                byte[] bytes = ByteStreams.toByteArray(ByteStreams.limit(zip, 16 * 1024 * 1024 + 1));
                int machine = name.contains("arm64") ? 183 : 62;
                if (name.equals("loader.bin")) {
                    if (bytes.length < 8 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x') {
                        throw new IOException("Invalid NPatch loader DEX");
                    }
                } else if (bytes.length < 20 || bytes.length > 16 * 1024 * 1024
                        || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F'
                        || bytes[4] != 2 || bytes[5] != 1 || (bytes[18] & 255) != machine || bytes[19] != 0) {
                    throw new IOException("Invalid Hook runtime ELF: " + name);
                }
                if (result.put(name, bytes) != null) throw new IOException("Duplicate Hook runtime entry: " + name);
            }
        }
        if (!result.containsKey("loader.bin") || !result.containsKey("so/arm64-v8a/libnpatch.so") || !result.containsKey("so/x86_64/libnpatch.so")) {
            throw new IOException("NPatch requires loader.bin, arm64-v8a and x86_64 libraries");
        }
        return result;
    }
}
