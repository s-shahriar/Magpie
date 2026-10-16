package com.syed.magpie.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Installs an [ApkBundle] the way SAI does: every chosen split goes into one
 * [PackageInstaller] session, so the system sees a single app, not a pile of
 * APKs. The system still asks the user to confirm; Magpie only gets it there.
 */
object ApkInstaller {

    sealed interface Outcome {
        data class Installed(val packageName: String?, val obbNote: String?) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Streams the splits into a session and commits it. [onProgress] gets
     * 0..1 while copying; the suspend returns once the system has decided,
     * or abandons the session if the coroutine is cancelled first.
     */
    suspend fun install(
        context: Context,
        bundle: ApkBundle,
        splits: List<ApkBundle.Split>,
        onProgress: (Float) -> Unit,
    ): Outcome {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val total = splits.sumOf { it.sizeBytes }.coerceAtLeast(1)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
            setSize(total)
            bundle.packageName?.let(::setAppPackageName)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            withContext(Dispatchers.IO) {
                var done = 0L
                var reported = 0f
                splits.forEachIndexed { i, split ->
                    // Session entry names must be unique and plain.
                    val name = "$i-${split.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                    session.openWrite(name, 0, split.sizeBytes.takeIf { it > 0 } ?: -1).use { out ->
                        bundle.open(split).use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val p = (done.toFloat() / total).coerceIn(0f, 1f)
                                if (p - reported >= 0.01f) {
                                    reported = p
                                    session.setStagingProgress(p)
                                    onProgress(p)
                                }
                            }
                        }
                        session.fsync(out)
                    }
                }
                // The throttle can swallow the last step; say "written" outright.
                onProgress(1f)
            }
            val outcome = commit(app, session, id)
            if (outcome is Outcome.Installed && bundle.obbs.isNotEmpty()) {
                val pkg = outcome.packageName ?: bundle.packageName
                return outcome.copy(obbNote = withContext(Dispatchers.IO) { copyObbs(bundle, pkg) })
            }
            return outcome
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            throw t
        } finally {
            runCatching { session.close() }
        }
    }

    private suspend fun commit(context: Context, session: PackageInstaller.Session, id: Int): Outcome =
        suspendCancellableCoroutine { cont ->
            val action = "${context.packageName}.INSTALL_RESULT.$id"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        // The system's "Do you want to install this app?" sheet.
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                            ?.let { confirm ->
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { c.startActivity(confirm) }
                            }
                        return
                    }
                    runCatching { context.unregisterReceiver(this) }
                    val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                    val outcome = if (status == PackageInstaller.STATUS_SUCCESS) {
                        Outcome.Installed(pkg, obbNote = null)
                    } else {
                        Outcome.Failed(explain(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
                    }
                    if (cont.isActive) cont.resume(outcome)
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { context.packageManager.packageInstaller.abandonSession(id) }
            }
            val callback = PendingIntent.getBroadcast(
                context,
                id,
                Intent(action).setPackage(context.packageName),
                // Mutable: the installer writes the status into the extras.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }

    /** The installer's terse codes, in words that say what to do next. */
    private fun explain(status: Int, message: String?): String {
        val detail = message.orEmpty()
        return when {
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Cancelled."
            detail.contains("UPDATE_INCOMPATIBLE") || detail.contains("signatures do not match", true) ->
                "A copy signed by someone else is already installed. Uninstall it first, then try again."
            detail.contains("VERSION_DOWNGRADE") ->
                "A newer version is already installed. Android won't downgrade an app in place; " +
                    "uninstall it first."
            detail.contains("NO_MATCHING_ABIS") ->
                "None of the native libraries in this bundle run on this phone's processor."
            detail.contains("MISSING_SPLIT") ->
                "A split this app needs isn't selected or isn't in the bundle."
            detail.contains("OLDER_SDK") || detail.contains("DEPRECATED_SDK_VERSION") ->
                "This app was built for an Android version this phone doesn't support."
            status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free space to install."
            status == PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked on this phone."
            status == PackageInstaller.STATUS_FAILURE_CONFLICT -> "It conflicts with an app already installed. $detail".trim()
            detail.isNotBlank() -> detail
            else -> "Installation failed."
        }
    }

    /**
     * APKPure's game data goes to `Android/obb/<package>/`. Android 11+ lets
     * an app holding REQUEST_INSTALL_PACKAGES write there; if this phone
     * doesn't, the note says where the files should go instead.
     */
    private fun copyObbs(bundle: ApkBundle, pkg: String?): String {
        if (pkg == null) return "This bundle carries game data, but its package is unknown, so it was not copied."
        val dir = File(Environment.getExternalStorageDirectory(), "Android/obb/$pkg")
        return runCatching {
            dir.mkdirs()
            bundle.obbs.forEach { obb ->
                File(dir, obb.name).outputStream().use { out -> bundle.open(obb).use { it.copyTo(out) } }
            }
            "Game data copied to Android/obb/$pkg."
        }.getOrElse {
            "The app is installed, but its game data couldn't be copied (${it.message}). " +
                "Copy the .obb files out of the bundle into Android/obb/$pkg/ by hand."
        }
    }
}
