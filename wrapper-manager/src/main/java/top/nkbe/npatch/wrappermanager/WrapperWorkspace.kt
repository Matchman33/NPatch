package top.nkbe.npatch.wrappermanager

import java.io.File
import java.util.UUID

internal class WrapperWorkspace(
    cacheDir: File,
    private val legacyRoot: File,
    sessionName: String = "session-${UUID.randomUUID()}",
) {
    private val root = File(cacheDir, "wrapper")
    private val session = File(root, sessionName)

    fun cleanupStale() {
        root.listFiles()?.filter { it.absoluteFile != session.absoluteFile }?.forEach(File::deleteRecursively)
        if (legacyRoot.absoluteFile != root.absoluteFile) legacyRoot.deleteRecursively()
    }

    fun createInputFile(): File = File(session, "selection-${UUID.randomUUID()}/input/base.apk").also {
        check(it.parentFile!!.mkdirs()) { "Cannot create input snapshot" }
    }

    fun createOutputFile(input: File, filename: String): File =
        File(selectionDirectory(input), "output-${UUID.randomUUID()}/$filename")

    fun createOptionFile(category: String, filename: String): File =
        File(session, "options/$category-${UUID.randomUUID()}/$filename").also {
            check(it.parentFile!!.mkdirs()) { "Cannot create option snapshot" }
        }

    fun deleteOutput(output: File?) {
        output?.parentFile?.deleteRecursively()
    }

    fun deleteSelection(input: File?) {
        input?.let { selectionDirectory(it).deleteRecursively() }
    }

    private fun selectionDirectory(input: File): File = input.parentFile!!.parentFile!!
}
