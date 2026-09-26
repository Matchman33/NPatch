package top.nkbe.npatch.wrappermanager

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.nkbe.npatch.patch.wrapper.WrapperManifest
import top.nkbe.npatch.patch.wrapper.PackControl
import top.nkbe.npatch.patch.wrapper.WrapperGadget
import top.nkbe.npatch.patch.wrapper.WrapperPacker
import top.nkbe.npatch.patch.wrapper.WrapperSigning
import top.nkbe.npatch.share.WrapperConfig

data class SelectedApk(val file: File, val packageName: String, val label: String, val icon: Bitmap, val sourceUri: Uri?)
data class InstalledApp(val packageName: String, val label: String)
data class SelectedAsset(val file: File, val displayName: String, val detail: String? = null)
enum class GadgetMode { LISTEN, SCRIPT }
data class WrapperState(
    val selected: SelectedApk? = null,
    val packageName: String = "",
    val filename: String = "",
    val signatureCompat: Boolean = false,
    val gadgetEnabled: Boolean = false,
    val gadget: SelectedAsset? = null,
    val gadgetMode: GadgetMode = GadgetMode.LISTEN,
    val gadgetAddress: String = "127.0.0.1",
    val gadgetPort: String = "27043",
    val gadgetWaitForClient: Boolean = true,
    val gadgetScript: SelectedAsset? = null,
    val busy: Boolean = false,
    val cancelling: Boolean = false,
    val stage: PackControl.Stage = PackControl.Stage.PREPARING,
    val completedBytes: Long = 0,
    val totalBytes: Long = -1,
    val appsLoading: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val output: File? = null,
    val error: String? = null,
    val notice: String? = null,
    val logs: List<String> = emptyList(),
)

class WrapperViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val mutable = MutableStateFlow(WrapperState())
    private val workspace = WrapperWorkspace(app.cacheDir, File(app.filesDir, "wrapper"), warn = { message ->
        Log.w("WrapperWorkspace", message)
        mutable.update { it.copy(logs = (it.logs + message).takeLast(200)) }
    })
    private val cleanupJob = viewModelScope.launch(Dispatchers.IO) {
        try { workspace.cleanupStale() } catch (error: Exception) { Log.w("WrapperWorkspace", "Cleanup failed", error) }
    }
    private var activeControl: PackControl? = null
    val state = mutable.asStateFlow()

    fun packageName(value: String) {
        invalidateOutput {
            it.copy(packageName = value, output = null, error = null,
                notice = if (it.selected != null && value != it.selected.packageName) app.getString(R.string.renamed_package_warning) else null)
        }
    }
    fun filename(value: String) = invalidateOutput { it.copy(filename = value, error = null, notice = null) }
    fun signatureCompat(value: Boolean) = invalidateOutput { it.copy(signatureCompat = value, error = null, notice = null) }
    fun gadgetEnabled(value: Boolean) = invalidateOutput { it.copy(gadgetEnabled = value, error = null, notice = null) }
    fun gadgetMode(value: GadgetMode) = invalidateOutput { it.copy(gadgetMode = value, error = null, notice = null) }
    fun gadgetAddress(value: String) = invalidateOutput { it.copy(gadgetAddress = value, error = null, notice = null) }
    fun gadgetPort(value: String) {
        if (value.all(Char::isDigit)) invalidateOutput { it.copy(gadgetPort = value, error = null, notice = null) }
    }
    fun gadgetWaitForClient(value: Boolean) = invalidateOutput { it.copy(gadgetWaitForClient = value, error = null, notice = null) }
    fun error(error: Throwable) { mutable.update { it.copy(error = error.message ?: error.javaClass.simpleName) } }
    fun notice(message: String) { mutable.update { it.copy(notice = message) } }

    fun loadApps() {
        if (mutable.value.appsLoading) return
        mutable.update { it.copy(appsLoading = true) }
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    app.packageManager.getInstalledApplications(0)
                        .filter { it.packageName != app.packageName &&
                            (it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || app.packageManager.getLaunchIntentForPackage(it.packageName) != null) }
                        .map { InstalledApp(it.packageName, app.packageManager.getApplicationLabel(it).toString()) }
                        .sortedBy { it.label.lowercase() }
                }
                mutable.update { it.copy(apps = apps) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error(e)
            } finally { mutable.update { it.copy(appsLoading = false) } }
        }
    }

    fun selectUri(uri: Uri) = work {
        val filename = displayName(uri, "application.apk")
        val input = inputFile()
        try {
            app.contentResolver.openInputStream(uri)?.use { source -> input.outputStream().use {
                copyStream(source, it, documentSize(uri), PackControl.Stage.COPYING, input.parentFile)
            } }
                ?: throw IOException(app.getString(R.string.input_unreadable))
            select(input, safeFilename(filename), uri)
        } catch (e: Exception) { workspace.deleteSelection(input); throw e }
    }

    fun selectGadget(uri: Uri) = work {
        val file = optionFile("gadget", "libnpatch-gadget.so")
        try {
            copyUri(uri, file, MAX_GADGET_SIZE)
            val header = ByteArray(20)
            DataInputStream(file.inputStream()).use { it.readFully(header) }
            val abi = WrapperGadget.detectAbi(header)
            val selected = SelectedAsset(file, displayName(uri, "libnpatch-gadget.so"), abi)
            val previous = mutable.value.gadget
            val previousOutput = mutable.value.output
            mutable.update { it.copy(gadget = selected, output = null, error = null, notice = null) }
            previous?.file?.parentFile?.deleteRecursively()
            workspace.deleteOutput(previousOutput)
        } catch (error: Exception) {
            file.parentFile?.deleteRecursively()
            throw error
        }
    }

    fun selectGadgetScript(uri: Uri) = work {
        val file = optionFile("script", "libscript.so")
        try {
            copyUri(uri, file, MAX_SCRIPT_SIZE)
            val selected = SelectedAsset(file, displayName(uri, "libscript.so"))
            val previous = mutable.value.gadgetScript
            val previousOutput = mutable.value.output
            mutable.update { it.copy(gadgetScript = selected, output = null, error = null, notice = null) }
            previous?.file?.parentFile?.deleteRecursively()
            workspace.deleteOutput(previousOutput)
        } catch (error: Exception) {
            file.parentFile?.deleteRecursively()
            throw error
        }
    }

    fun selectInstalled(packageName: String) = work {
        val info = app.packageManager.getApplicationInfo(packageName, 0)
        if (!info.splitSourceDirs.isNullOrEmpty()) throw IOException(app.getString(R.string.splits_unsupported))
        val input = inputFile()
        try {
            val before = app.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            val installedApk = File(info.sourceDir)
            installedApk.inputStream().use { source -> input.outputStream().use {
                copyStream(source, it, installedApk.length(), PackControl.Stage.COPYING, input.parentFile)
            } }
            if (app.packageManager.getPackageInfo(packageName, 0).lastUpdateTime != before) {
                throw IOException(app.getString(R.string.source_updated))
            }
            select(input, safeFilename(app.packageManager.getApplicationLabel(info).toString() + ".apk"), null)
        } catch (e: Exception) { workspace.deleteSelection(input); throw e }
    }

    private fun inputFile(): File = workspace.createInputFile()

    private fun select(input: File, filename: String, uri: Uri?) {
        val manifest = WrapperPacker.inspect(input)
        val archive = app.packageManager.getPackageArchiveInfo(input.path, PackageManager.GET_META_DATA)
            ?: throw IOException(app.getString(R.string.input_unreadable))
        val info = archive.applicationInfo ?: throw IOException(app.getString(R.string.input_unreadable))
        info.sourceDir = input.path
        info.publicSourceDir = input.path
        val selected = SelectedApk(input, manifest.packageName,
            app.packageManager.getApplicationLabel(info).toString(),
            app.packageManager.getApplicationIcon(info).toBitmap(96, 96), uri)
        val previous = mutable.value.selected
        mutable.update { it.copy(selected = selected, packageName = manifest.packageName,
            filename = filename, output = null, error = null, notice = null, logs = emptyList()) }
        workspace.deleteSelection(previous?.file)
    }

    fun generate() = work {
        val current = mutable.value
        val selected = current.selected ?: return@work
        if (android.os.Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" || it == "x86_64" }) {
            throw IOException(app.getString(R.string.hook_unsupported))
        }
        mutable.update { it.copy(output = null, logs = emptyList()) }
        workspace.deleteOutput(current.output)
        WrapperManifest.validatePackage(current.packageName)
        if (current.filename != safeFilename(current.filename) || current.filename.length > 180) {
            throw IOException(app.getString(R.string.invalid_filename))
        }
        val signer = signer()
        val gadget = if (current.gadgetEnabled) {
            val selected = current.gadget ?: throw IOException(app.getString(R.string.gadget_required))
            val library = selected.file.readBytes()
            when (current.gadgetMode) {
                GadgetMode.LISTEN -> {
                    val port = current.gadgetPort.toIntOrNull()
                        ?: throw IOException(app.getString(R.string.gadget_port_invalid))
                    WrapperGadget.listen(library, current.gadgetAddress, port, current.gadgetWaitForClient)
                }
                GadgetMode.SCRIPT -> {
                    val script = current.gadgetScript ?: throw IOException(app.getString(R.string.gadget_script_required))
                    WrapperGadget.script(library, script.file.readBytes())
                }
            }
        } else null
        val installNotice = try {
            checkInstalledTarget(current.packageName, selected.packageName, signer)
            null
        } catch (e: IOException) {
            if (current.packageName != selected.packageName) throw e
            e.message
        }
        val output = workspace.createOutputFile(selected.file, current.filename)
        val loader = app.assets.open("wrapper/loader.dex").use { it.readBytes() }
        val runtime = app.assets.open("wrapper/runtime.zip").use { it.readBytes() }
        try {
            WrapperPacker.pack(selected.file, output, current.packageName, loader, signer, runtime,
                current.signatureCompat, gadget, { log ->
                mutable.update { it.copy(logs = (it.logs + log).takeLast(200)) }
            }, activeControl!!)
        } catch (error: Exception) {
            workspace.deleteOutput(output)
            throw error
        }
        mutable.update { it.copy(output = output, notice = installNotice ?:
            if (current.packageName != selected.packageName) app.getString(R.string.renamed_package_warning) else null) }
    }

    fun checkInstall() {
        val current = mutable.value
        checkInstalledTarget(current.packageName, current.selected!!.packageName, signer())
    }

    fun checkOpen() {
        checkInstall()
        val current = mutable.value
        val installed = app.packageManager.getPackageInfo(current.packageName, PackageManager.GET_META_DATA)
        if (installed.applicationInfo?.metaData?.getInt(WrapperConfig.MARKER) != WrapperConfig.FORMAT_VERSION) {
            throw IOException(app.getString(R.string.not_installed))
        }
    }

    private fun signer() = app.assets.open("npatch.key").use { WrapperSigning.builtin(it) }

    private fun displayName(uri: Uri, fallback: String): String =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: fallback

    private fun optionFile(category: String, filename: String): File = workspace.createOptionFile(category, filename)

    private fun copyUri(uri: Uri, destination: File, maximumSize: Long) {
        app.contentResolver.openInputStream(uri)?.use { source ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    activeControl!!.check()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumSize) throw IOException(app.getString(R.string.gadget_file_too_large))
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException(app.getString(R.string.gadget_file_empty))
            }
        } ?: throw IOException(app.getString(R.string.input_unreadable))
    }

    private fun checkInstalledTarget(target: String, original: String, signer: KeyStore.PrivateKeyEntry) {
        val installed = try {
            app.packageManager.getPackageInfo(target, PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) { return }
        val metadata = installed.applicationInfo?.metaData
        val signatures = installed.signingInfo?.apkContentsSigners.orEmpty()
        val sameSigner = signatures.size == 1 && signatures[0].toByteArray().contentEquals(signer.certificate.encoded)
        if (target == original) {
            if (!sameSigner) throw IOException(app.getString(R.string.same_package_signature))
            return
        }
        if (metadata?.getInt(WrapperConfig.MARKER) != WrapperConfig.FORMAT_VERSION ||
            metadata.getString(WrapperConfig.ORIGINAL_PACKAGE_MARKER) != original ||
            !sameSigner) {
            throw IOException(app.getString(R.string.package_conflict))
        }
    }

    fun export(uri: Uri) = work {
        val current = mutable.value
        val output = current.output ?: return@work
        val original = current.selected?.sourceUri
        if (original != null && sameDocument(original, uri)) throw IOException(app.getString(R.string.input_overwrite))
        copyOutput(output, uri, "wt")
        mutable.update { it.copy(notice = app.getString(R.string.exported)) }
    }

    fun exportToDownloads() = work {
        val current = mutable.value
        val output = current.output ?: return@work
        val filename = safeFilename(current.filename)
        val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToMediaStore(output, filename)
        } else {
            exportToAppDownloads(output, filename)
        }
        mutable.update { it.copy(notice = app.getString(R.string.exported_to, destination)) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportToMediaStore(output: File, filename: String): String {
        val directory = "${Environment.DIRECTORY_DOWNLOADS}/ApkLoom"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException(app.getString(R.string.output_unwritable))
        try {
            copyOutput(output, uri, "w")
            val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, complete, null, null) != 1) {
                throw IOException(app.getString(R.string.output_unwritable))
            }
            val exportedName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: filename
            return "$directory/$exportedName"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun exportToAppDownloads(output: File, filename: String): String {
        val root = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException(app.getString(R.string.output_unwritable))
        val directory = File(root, "ApkLoom")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException(app.getString(R.string.output_unwritable))
        }
        val destination = uniqueFile(directory, filename)
        try {
            output.inputStream().use { source -> destination.outputStream().use {
                copyStream(source, it, output.length(), PackControl.Stage.EXPORTING, directory)
            } }
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
        return destination.absolutePath
    }

    private fun uniqueFile(directory: File, filename: String): File {
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "")
        val base = if (extension.isEmpty()) filename else filename.dropLast(extension.length + 1)
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else " ($index)"
            val candidate = File(directory, if (extension.isEmpty()) "$base$suffix" else "$base$suffix.$extension")
            if (candidate.createNewFile()) return candidate
            index++
        }
    }

    private fun copyOutput(output: File, uri: Uri, mode: String) {
        app.contentResolver.openOutputStream(uri, mode)?.use { destination ->
            output.inputStream().use { copyStream(it, destination, output.length(), PackControl.Stage.EXPORTING) }
        } ?: throw IOException(app.getString(R.string.output_unwritable))
    }

    private fun sameDocument(first: Uri, second: Uri): Boolean {
        if (first == second) return true
        return first.authority == second.authority &&
            DocumentsContract.isDocumentUri(app, first) && DocumentsContract.isDocumentUri(app, second) &&
            DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second)
    }

    private fun work(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val control = PackControl { stage, completed, total ->
            mutable.update { it.copy(stage = stage, completedBytes = completed, totalBytes = total) }
        }
        activeControl = control
        mutable.update { it.copy(busy = true, cancelling = false, stage = PackControl.Stage.PREPARING,
            completedBytes = 0, totalBytes = -1, error = null, notice = null) }
        viewModelScope.launch {
            try {
                ContextCompat.startForegroundService(app, Intent(app, PackagingService::class.java))
                withContext(Dispatchers.IO) { cleanupJob.join(); control.check(); block() }
            }
            catch (e: Exception) {
                if (e is CancellationException) notice(app.getString(R.string.task_cancelled)) else error(e)
            } finally {
                activeControl = null
                mutable.update { it.copy(busy = false, cancelling = false) }
                app.stopService(Intent(app, PackagingService::class.java))
            }
        }
    }

    fun cancelWork() {
        activeControl?.let { control ->
            mutable.update { it.copy(cancelling = true) }
            control.cancel()
        }
    }

    private fun documentSize(uri: Uri): Long = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun copyStream(source: InputStream, output: OutputStream, size: Long,
                           stage: PackControl.Stage, directory: File? = null) {
        val control = activeControl!!
        val reserve = 32L * 1024 * 1024
        if (directory != null) WrapperPacker.requireSpace(directory, Math.addExact(size.coerceAtLeast(0), reserve))
        control.track(source, stage, size).use { input ->
            val buffer = ByteArray(65536)
            var sinceCheck = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                sinceCheck += count
                if (directory != null && sinceCheck >= 8L * 1024 * 1024) {
                    WrapperPacker.requireSpace(directory, reserve)
                    sinceCheck = 0
                }
            }
        }
    }

    private fun invalidateOutput(update: (WrapperState) -> WrapperState) {
        if (mutable.value.busy) return
        val previous = mutable.value.output
        mutable.update { update(it).copy(output = null) }
        if (previous != null) viewModelScope.launch(Dispatchers.IO) {
            cleanupJob.join()
            workspace.deleteOutput(previous)
        }
    }

    companion object {
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val MAX_GADGET_SIZE = 128L * 1024 * 1024
        private const val MAX_SCRIPT_SIZE = 16L * 1024 * 1024

        fun safeFilename(value: String): String {
            val clean = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.')
                .ifEmpty { "application.apk" }
            return if (clean.endsWith(".apk", ignoreCase = true)) clean else "$clean.apk"
        }
    }
}
