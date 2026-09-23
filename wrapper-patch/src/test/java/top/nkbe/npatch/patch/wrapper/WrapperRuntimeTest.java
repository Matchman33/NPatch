package top.nkbe.npatch.patch.wrapper;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import top.nkbe.npatch.share.WrapperConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WrapperRuntimeTest {
    @Test public void acceptsBothSupportedArchitectures() throws Exception {
        assertEquals(3, WrapperRuntime.read(baseArchive(false)).size());
    }

    @Test public void rejectsWrongElfAndEmptyPayload() throws Exception {
        assertThrows(IOException.class, () -> WrapperRuntime.read(baseArchive(true)));
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

    @Test public void acceptsListenAndColocatedScriptModes() throws Exception {
        assertEquals(5, WrapperRuntime.read(gadgetArchive(false, false)).size());
        assertEquals(6, WrapperRuntime.read(gadgetArchive(true, true)).size());
    }

    @Test public void treatsSoNamedScriptAsTextInsteadOfElf() throws Exception {
        assertEquals(6, WrapperRuntime.read(gadgetArchive(false, true)).size());
    }

    @Test public void rejectsScriptModeWithoutColocatedSoScript() throws Exception {
        assertThrows(IOException.class, () -> WrapperRuntime.read(gadgetArchive(true, false)));
    }

    @Test public void rejectsScriptOutsideGadgetDirectory() throws Exception {
        assertThrows(IOException.class, () -> WrapperRuntime.read(gadgetArchive(
                gadgetConfig("script", "../script.js"), true)));
    }

    @Test public void rejectsMalformedGadgetConfig() throws Exception {
        assertThrows(IOException.class, () -> WrapperRuntime.read(gadgetArchive(
                "{\"interaction\":\"listen\"}", false)));
    }

    private byte[] baseArchive(boolean wrongMachine) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            addBaseRuntime(zip, wrongMachine);
        }
        return output.toByteArray();
    }

    private byte[] gadgetArchive(boolean scriptMode, boolean includeScript) throws Exception {
        return gadgetArchive(gadgetConfig(scriptMode ? "script" : "listen",
                scriptMode ? WrapperConfig.GADGET_SCRIPT : null), includeScript);
    }

    private String gadgetConfig(String type, String path) {
        JsonObject interaction = new JsonObject();
        interaction.addProperty("type", type);
        if ("script".equals(type)) {
            interaction.addProperty("path", path);
            interaction.addProperty("on_change", "ignore");
        } else {
            interaction.addProperty("address", "127.0.0.1");
            interaction.addProperty("port", 27043);
            interaction.addProperty("on_load", "wait");
        }
        JsonObject root = new JsonObject();
        root.add("interaction", interaction);
        return root.toString();
    }

    private byte[] gadgetArchive(String config, boolean includeScript) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            addBaseRuntime(zip, false);
            String prefix = WrapperConfig.GADGET_PREFIX + "arm64-v8a/";
            add(zip, prefix + WrapperConfig.GADGET_LIBRARY, elf("arm64-v8a", false));
            add(zip, prefix + WrapperConfig.GADGET_CONFIG, config.getBytes(StandardCharsets.UTF_8));
            if (includeScript) {
                add(zip, prefix + WrapperConfig.GADGET_SCRIPT,
                        "rpc.exports = { init() {} };".getBytes(StandardCharsets.UTF_8));
            }
        }
        return output.toByteArray();
    }

    private void addBaseRuntime(ZipOutputStream zip, boolean wrongMachine) throws Exception {
        add(zip, "loader.bin", new byte[] {'d', 'e', 'x', 10, '0', '3', '5', 0});
        for (String abi : new String[] {"arm64-v8a", "x86_64"}) {
            add(zip, "so/" + abi + "/libnpatch.so", elf(abi, wrongMachine));
        }
    }

    private void add(ZipOutputStream zip, String name, byte[] contents) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(contents);
        zip.closeEntry();
    }

    private byte[] elf(String abi, boolean wrongMachine) {
        byte[] elf = new byte[20];
        elf[0] = 0x7f;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        elf[4] = 2;
        elf[5] = 1;
        elf[18] = (byte) (wrongMachine ? 0 : abi.startsWith("arm64") ? 183 : 62);
        return elf;
    }
}
