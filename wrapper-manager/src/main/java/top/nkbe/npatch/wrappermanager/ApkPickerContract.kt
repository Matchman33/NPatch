package top.nkbe.npatch.wrappermanager

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

internal class ApkPickerContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        val colorOsPicker = ComponentName(COLOR_OS_FILE_MANAGER, COLOR_OS_PICKER_ACTIVITY)
        if (runCatching { context.packageManager.getActivityInfo(colorOsPicker, 0) }.isSuccess) {
            return apkGetContentIntent().setComponent(colorOsPicker)
        }

        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, APK_MIME_TYPES)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent?.data.takeIf { resultCode == Activity.RESULT_OK }
    }

    private fun apkGetContentIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, APK_MIME_TYPES)

    private companion object {
        const val COLOR_OS_FILE_MANAGER = "com.coloros.filemanager"
        const val COLOR_OS_PICKER_ACTIVITY =
            "com.oplus.filemanager.filechoose.ui.singlepicker.SinglePickerActivity"
        val APK_MIME_TYPES = arrayOf(
            "application/vnd.android.package-archive",
            "application/octet-stream",
        )
    }
}
