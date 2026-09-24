package top.nkbe.npatch.wrappermanager

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WrapperWorkspaceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun cleanupDeletesStaleSessionsAndLegacyWorkspaceButKeepsCurrentSession() {
        val cache = temporary.newFolder("cache")
        val legacy = temporary.newFolder("files").resolve("wrapper")
        val stale = cache.resolve("wrapper/session-old/output/result.apk").withContents()
        legacy.resolve("old/input/base.apk").withContents()
        val workspace = WrapperWorkspace(cache, legacy, "session-current")
        val current = workspace.createInputFile().withContents()

        workspace.cleanupStale()

        assertFalse(stale.exists())
        assertFalse(legacy.exists())
        assertTrue(current.exists())
    }

    @Test
    fun deletingOutputKeepsInputAndOtherOutput() {
        val cache = temporary.newFolder("cache")
        val workspace = WrapperWorkspace(cache, temporary.newFolder("legacy"), "session-current")
        val input = workspace.createInputFile().withContents()
        val first = workspace.createOutputFile(input, "first.apk").withContents()
        val second = workspace.createOutputFile(input, "second.apk").withContents()

        workspace.deleteOutput(first)

        assertFalse(first.exists())
        assertTrue(second.exists())
        assertTrue(input.exists())
    }

    @Test
    fun deletingSelectionRemovesItsInputAndOutputsOnly() {
        val cache = temporary.newFolder("cache")
        val workspace = WrapperWorkspace(cache, temporary.newFolder("legacy"), "session-current")
        val firstInput = workspace.createInputFile().withContents()
        workspace.createOutputFile(firstInput, "first.apk").withContents()
        val secondInput = workspace.createInputFile().withContents()

        workspace.deleteSelection(firstInput)

        assertFalse(firstInput.exists())
        assertTrue(secondInput.exists())
    }

    private fun File.withContents(): File {
        parentFile!!.mkdirs()
        writeText("fixture")
        return this
    }
}
