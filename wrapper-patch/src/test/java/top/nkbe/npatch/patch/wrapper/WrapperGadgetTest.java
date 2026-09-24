package top.nkbe.npatch.patch.wrapper;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;
import top.nkbe.npatch.share.WrapperConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WrapperGadgetTest {
    @Test public void createsListenConfigurationWithFixedNames() throws Exception {
        WrapperGadget gadget = WrapperGadget.listen(elf(183), "127.0.0.1", 27043, false);
        assertEquals("arm64-v8a", gadget.abi());
        assertEquals("listen", gadget.mode());
        Map<String, byte[]> entries = gadget.entries();
        String prefix = WrapperConfig.GADGET_PREFIX + "arm64-v8a/";
        var interaction = JsonParser.parseString(new String(
                entries.get(prefix + WrapperConfig.GADGET_CONFIG), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("interaction");
        assertEquals("127.0.0.1", interaction.get("address").getAsString());
        assertEquals(27043, interaction.get("port").getAsInt());
        assertEquals("resume", interaction.get("on_load").getAsString());
    }

    @Test public void createsScriptConfigurationWithFixedInternalPath() throws Exception {
        byte[] script = "rpc.exports = {};".getBytes(StandardCharsets.UTF_8);
        WrapperGadget gadget = WrapperGadget.script(elf(62), script);
        assertEquals("x86_64", gadget.abi());
        String prefix = WrapperConfig.GADGET_PREFIX + "x86_64/";
        Map<String, byte[]> entries = gadget.entries();
        var interaction = JsonParser.parseString(new String(
                entries.get(prefix + WrapperConfig.GADGET_CONFIG), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("interaction");
        assertEquals(WrapperConfig.GADGET_SCRIPT, interaction.get("path").getAsString());
        assertEquals("ignore", interaction.get("on_change").getAsString());
        assertEquals(new String(script, StandardCharsets.UTF_8),
                new String(entries.get(prefix + WrapperConfig.GADGET_SCRIPT), StandardCharsets.UTF_8));
    }

    @Test public void rejectsUnsupportedElfInvalidPortAndNonUtf8Script() {
        assertThrows(IOException.class, () -> WrapperGadget.detectAbi(elf(3)));
        assertThrows(IOException.class, () -> WrapperGadget.listen(elf(183), "127.0.0.1", 0, true));
        assertThrows(IOException.class, () -> WrapperGadget.script(elf(183), new byte[] {(byte) 0xc3, 0x28}));
    }

    private byte[] elf(int machine) {
        byte[] elf = new byte[20];
        elf[0] = 0x7f;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        elf[4] = 2;
        elf[5] = 1;
        elf[18] = (byte) machine;
        elf[19] = (byte) (machine >>> 8);
        return elf;
    }
}
