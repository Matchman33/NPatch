package top.nkbe.npatch.wrappermanager

import java.io.File
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.UUID

internal class WrapperWorkspace(
    cacheDir: File,
    private val legacyRoot: File,
    sessionName: String = "session-${UUID.randomUUID()}",
    private val warn: (String) -> Unit = {},
) : Closeable {
    private val root = File(cacheDir, "wrapper")
    private val session = File(root, sessionName)
    private var leaseFile: RandomAccessFile? = null
    private var lease: FileLock? = null

    init {
        require(sessionName.matches(Regex("session-[A-Za-z0-9-]+")))
        synchronized(guard) {
            check(root.isDirectory || root.mkdirs()) { "Cannot create workspace" }
            withDirectoryLock {
                check(session.mkdir()) { "Session already exists" }
                val file = RandomAccessFile(File(session, ".active.lock"), "rw")
                try {
                    lease = file.channel.lock()
                    leaseFile = file
                } catch (error: Exception) { file.close(); throw error }
            }
        }
    }

    fun cleanupStale() = synchronized(guard) {
        withDirectoryLock {
            root.listFiles()?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) &&
                it != session && it.name.startsWith("session-") }
                ?.forEach { candidate ->
                    try {
                        RandomAccessFile(File(candidate, ".active.lock"), "rw").use { file ->
                            val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                            if (lock == null) return@forEach
                            lock.release()
                        }
                        remove(candidate)
                    } catch (error: Exception) { warn("Cannot clean ${candidate.name}: ${error.message}") }
                }
            if (legacyRoot.canonicalFile != root.canonicalFile) remove(legacyRoot)
        }
    }

    fun createInputFile(): File = File(session, "selection-${UUID.randomUUID()}/input/base.apk").also {
        check(it.parentFile!!.mkdirs()) { "Cannot create input snapshot" }
    }

    fun createOutputFile(input: File, filename: String): File =
        File(owned(selectionDirectory(input)), "output-${UUID.randomUUID()}/$filename")

    fun createOptionFile(category: String, filename: String): File =
        File(session, "options/$category-${UUID.randomUUID()}/$filename").also {
            check(it.parentFile!!.mkdirs()) { "Cannot create option snapshot" }
        }

    fun deleteOutput(output: File?) {
        output?.let { remove(owned(it.parentFile!!)) }
    }

    fun deleteSelection(input: File?) {
        input?.let { remove(owned(selectionDirectory(it))) }
    }

    private fun selectionDirectory(input: File): File = input.parentFile!!.parentFile!!

    private fun owned(directory: File): File {
        require(directory.canonicalFile.toPath().startsWith(session.canonicalFile.toPath()) &&
            directory.canonicalFile != session.canonicalFile) { "Path is outside this session" }
        return directory
    }

    private fun remove(directory: File) {
        if (!directory.exists()) return
        try {
            Files.walk(directory.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (error: Exception) { warn("Cannot clean ${directory.name}: ${error.message}") }
    }

    private fun <T> withDirectoryLock(action: () -> T): T =
        RandomAccessFile(File(root, ".cleanup.lock"), "rw").use { file ->
            file.channel.lock().use { action() }
        }

    override fun close() = synchronized(guard) {
        lease?.release()
        lease = null
        leaseFile?.close()
        leaseFile = null
    }

    private companion object { val guard = Any() }
}
