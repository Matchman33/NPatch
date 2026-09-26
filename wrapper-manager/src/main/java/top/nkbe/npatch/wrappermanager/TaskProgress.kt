package top.nkbe.npatch.wrappermanager

import top.nkbe.npatch.patch.wrapper.PackControl

internal fun PackControl.Stage.labelResource(): Int = when (this) {
    PackControl.Stage.PREPARING -> R.string.task_preparing
    PackControl.Stage.COPYING -> R.string.task_copying
    PackControl.Stage.HASHING -> R.string.task_hashing
    PackControl.Stage.EMBEDDING -> R.string.task_embedding
    PackControl.Stage.SIGNING -> R.string.task_signing
    PackControl.Stage.VERIFYING -> R.string.task_verifying
    PackControl.Stage.EXPORTING -> R.string.task_exporting
}

internal fun WrapperState.progressPercent(): Int =
    if (totalBytes > 0) (completedBytes.toDouble() / totalBytes * 100).toInt().coerceIn(0, 100) else 0
