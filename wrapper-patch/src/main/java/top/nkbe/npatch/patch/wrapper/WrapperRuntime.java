package top.nkbe.npatch.patch.wrapper;

import com.google.common.io.ByteStreams;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;
import top.nkbe.npatch.share.WrapperConfig;

final class WrapperRuntime {
    private static final int MAX_ARCHIVE_SIZE = 256 * 1024 * 1024;
    private static final int MAX_DEX_SIZE = 16 * 1024 * 1024;
    private static final int MAX_NATIVE_SIZE = 128 * 1024 * 1024;
    private static final int MAX_TEXT_SIZE = 16 * 1024 * 1024;
    private static final String[] SUPPORTED_ABIS = {"arm64-v8a", "x86_64"};

    private WrapperRuntime() {}

    static Map<String, byte[]> read(byte[] archive) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_SIZE) {
            throw new IOException("Missing or oversized NPatch runtime");
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!supportedPath(name)) throw new IOException("Unsupported Hook runtime entry: " + name);
                int limit = entryLimit(name);
                byte[] bytes = ByteStreams.toByteArray(ByteStreams.limit(zip, limit + 1L));
                if (bytes.length == 0 || bytes.length > limit) {
                    throw new IOException("Empty or oversized Hook runtime entry: " + name);
                }
                if (name.equals("loader.bin")) {
                    if (bytes.length < 8 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x') {
                        throw new IOException("Invalid NPatch loader DEX");
                    }
                } else if (name.endsWith("libnpatch.so") || name.endsWith(WrapperConfig.GADGET_LIBRARY)) {
                    validateElf(name, bytes);
                } else {
                    decodeUtf8(name, bytes);
                }
                if (result.put(name, bytes) != null) throw new IOException("Duplicate Hook runtime entry: " + name);
            }
        }
        if (!result.containsKey("loader.bin") || !result.containsKey("so/arm64-v8a/libnpatch.so")
                || !result.containsKey("so/x86_64/libnpatch.so")) {
            throw new IOException("NPatch requires loader.bin, arm64-v8a and x86_64 libraries");
        }
        for (String abi : SUPPORTED_ABIS) validateGadgetBundle(result, abi);
        return result;
    }

    private static int entryLimit(String name) {
        if (name.equals("loader.bin")) return MAX_DEX_SIZE;
        if (name.endsWith(WrapperConfig.GADGET_CONFIG) || name.endsWith(WrapperConfig.GADGET_SCRIPT)) {
            return MAX_TEXT_SIZE;
        }
        return MAX_NATIVE_SIZE;
    }

    private static boolean supportedPath(String name) {
        if (name.equals("loader.bin") || name.equals("so/arm64-v8a/libnpatch.so")
                || name.equals("so/x86_64/libnpatch.so")) return true;
        for (String abi : SUPPORTED_ABIS) {
            String prefix = WrapperConfig.GADGET_PREFIX + abi + "/";
            if (name.equals(prefix + WrapperConfig.GADGET_LIBRARY)
                    || name.equals(prefix + WrapperConfig.GADGET_CONFIG)
                    || name.equals(prefix + WrapperConfig.GADGET_SCRIPT)) return true;
        }
        return false;
    }

    private static void validateElf(String name, byte[] bytes) throws IOException {
        int machine = name.contains("arm64-v8a") ? 183 : 62;
        if (bytes.length < 20 || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F'
                || bytes[4] != 2 || bytes[5] != 1 || (bytes[18] & 255) != machine || bytes[19] != 0) {
            throw new IOException("Invalid Hook runtime ELF: " + name);
        }
    }

    private static String decodeUtf8(String name, byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Hook runtime text entry is not UTF-8: " + name, error);
        }
    }

    private static void validateGadgetBundle(Map<String, byte[]> runtime, String abi) throws IOException {
        String prefix = WrapperConfig.GADGET_PREFIX + abi + "/";
        String library = prefix + WrapperConfig.GADGET_LIBRARY;
        String config = prefix + WrapperConfig.GADGET_CONFIG;
        String script = prefix + WrapperConfig.GADGET_SCRIPT;
        boolean hasLibrary = runtime.containsKey(library);
        boolean hasConfig = runtime.containsKey(config);
        boolean hasScript = runtime.containsKey(script);
        if (!hasLibrary && !hasConfig && !hasScript) return;
        if (!hasLibrary || !hasConfig) {
            throw new IOException("Frida Gadget requires both library and config for " + abi);
        }

        JsonObject interaction;
        String type;
        try {
            JsonObject root = JsonParser.parseString(
                    decodeUtf8(config, runtime.get(config))).getAsJsonObject();
            interaction = root.getAsJsonObject("interaction");
            if (interaction == null || !interaction.has("type")) {
                throw new IllegalArgumentException("Missing interaction.type");
            }
            type = interaction.get("type").getAsString();
        } catch (RuntimeException error) {
            throw new IOException("Invalid Frida Gadget config for " + abi, error);
        }
        if ("listen".equals(type)) return;
        if (!"script".equals(type)) {
            throw new IOException("Only Frida Gadget listen and script modes are supported");
        }
        String path;
        try {
            path = interaction.has("path") ? interaction.get("path").getAsString() : null;
        } catch (RuntimeException error) {
            throw new IOException("Invalid Frida Gadget script path for " + abi, error);
        }
        if (!WrapperConfig.GADGET_SCRIPT.equals(path)) {
            throw new IOException("Frida Gadget script must be a colocated " + WrapperConfig.GADGET_SCRIPT);
        }
        if (!hasScript) throw new IOException("Frida Gadget script asset is missing for " + abi);
    }
}
