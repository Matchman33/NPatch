package top.nkbe.npatch.wrappermanager

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.rules.TemporaryFolder

class WrapperWorkspaceTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val workspaces = mutableListOf<WrapperWorkspace>()

    @After fun releaseSessions() { workspaces.forEach { it.close() } }

    @Test
    fun cannotReadLeaseDoesNotDeleteCandidateAndReportsFailure() {
        val cache = temporary.newFolder("cache")
        val candidate = cache.resolve("wrapper/session-unknown")
        candidate.resolve(".active.lock").mkdirs()
        val file = candidate.resolve("input/base.apk").withContents()
        val warnings = mutableListOf<String>()
        WrapperWorkspace(cache, temporary.newFolder("legacy"), warn = warnings::add).use {
            it.cleanupStale()
            assertTrue(file.exists())
            assertTrue(warnings.any { message -> message.contains("session-unknown") })
        }
    }

    @Test
    fun anotherWindowCannotDeleteAnActiveSelection() {
        val cache = temporary.newFolder("cache")
        val legacy = temporary.newFolder("files").resolve("wrapper")
        WrapperWorkspace(cache, legacy, "session-first").use { first ->
            val input = first.createInputFile().withContents()
            WrapperWorkspace(cache, legacy, "session-second").use { second ->
                second.cleanupStale()
                assertTrue(input.exists())
                first.close()
                second.cleanupStale()
                assertFalse(input.exists())
            }
        }
    }

    @Test
    fun refusesToDeleteFilesOutsideItsSession() {
        val cache = temporary.newFolder("cache")
        WrapperWorkspace(cache, temporary.newFolder("legacy")).use { workspace ->
            val foreign = temporary.newFolder("exports").resolve("input/base.apk").withContents()
            assertThrows(IllegalArgumentException::class.java) { workspace.deleteSelection(foreign) }
            assertThrows(IllegalArgumentException::class.java) { workspace.deleteOutput(foreign) }
            assertTrue(foreign.exists())
        }
    }

    @Test
    fun cleanupDeletesStaleSessionsAndLegacyWorkspaceButKeepsCurrentSession() {
        val cache = temporary.newFolder("cache")
        val legacy = temporary.newFolder("files").resolve("wrapper")
        val stale = cache.resolve("wrapper/session-old/output/result.apk").withContents()
        legacy.resolve("old/input/base.apk").withContents()
        val workspace = WrapperWorkspace(cache, legacy, "session-current").also(workspaces::add)
        val current = workspace.createInputFile().withContents()

        workspace.cleanupStale()

        assertFalse(stale.exists())
        assertFalse(legacy.exists())
        assertTrue(current.exists())
    }

    @Test
    fun deletingOutputKeepsInputAndOtherOutput() {
        val cache = temporary.newFolder("cache")
        val workspace = WrapperWorkspace(cache, temporary.newFolder("legacy"), "session-current").also(workspaces::add)
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
        val workspace = WrapperWorkspace(cache, temporary.newFolder("legacy"), "session-current").also(workspaces::add)
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
