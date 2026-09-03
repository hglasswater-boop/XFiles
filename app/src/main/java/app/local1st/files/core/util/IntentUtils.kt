package app.local1st.files.core.util

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import app.local1st.files.core.fs.XEntry
import java.io.File

object IntentUtils {

    private fun uriFor(context: Context, entry: XEntry): Uri = when {
        entry.localPath != null -> FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            File(entry.localPath),
        )
        RemoteFileProvider.canServe(entry) -> RemoteFileProvider.uriFor(context, entry)
        else -> throw IllegalArgumentException("Entry cannot be exposed to another app: ${entry.id}")
    }

    /** Whether this file can be handed to another Android app as a readable content:// URI. */
    fun canExternalRead(entry: XEntry): Boolean =
        !entry.isDir && (entry.localPath != null || RemoteFileProvider.canServe(entry))

    /**
     * Builds an Activity result containing readable content URIs for [entries]. The caller receives
     * a temporary read grant through ClipData, including SMB-backed entries served by
     * [RemoteFileProvider], without exposing XFiles credentials or internal filesystem objects.
     */
    fun pickerResult(context: Context, entries: List<XEntry>): Intent {
        require(entries.isNotEmpty()) { "At least one entry is required" }
        require(entries.all(::canExternalRead)) { "Every selected entry must be externally readable" }
        val uris = entries.map { uriFor(context, it) }
        val clips = ClipData.newUri(context.contentResolver, entries.first().name, uris.first())
        uris.drop(1).forEach { clips.addItem(ClipData.Item(it)) }
        return Intent().apply {
            data = uris.first()
            clipData = clips
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * These are launched from the application context (see [app.local1st.files.di.Graph.appContext]),
     * which is not an Activity, so every started intent — including the chooser wrapper — must
     * carry FLAG_ACTIVITY_NEW_TASK or startActivity throws.
     */
    private fun Context.launch(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) {
        false
    }

    /** Open a local or supported remote file with an external app chooser. */
    fun openWith(context: Context, entry: XEntry): Boolean = try {
        if (!canExternalRead(entry)) return false
        val mime = entry.mime ?: FileTypes.mimeOf(entry.name) ?: "*/*"
        val uri = uriFor(context, entry)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(context.contentResolver, entry.name, uri)
        context.launch(Intent.createChooser(intent, entry.name))
    } catch (_: Throwable) {
        false
    }

    /** Shares local files and supported remote files through the system chooser. */
    fun share(context: Context, entries: List<XEntry>): Boolean {
        return try {
            if (entries.isEmpty() || entries.any { !canExternalRead(it) }) return false
            val uris = entries.map { uriFor(context, it) }
            val clipData = ClipData.newUri(context.contentResolver, entries.first().name, uris.first())
            uris.drop(1).forEach { clipData.addItem(ClipData.Item(it)) }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND)
                    .setType(entries.first().mime ?: FileTypes.mimeOf(entries.first().name) ?: "*/*")
                    .putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.clipData = clipData
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.launch(Intent.createChooser(intent, null))
        } catch (_: Throwable) {
            false
        }
    }

    fun uninstall(context: Context, packageName: String) {
        context.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    fun appInfo(context: Context, packageName: String) {
        context.launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    fun launchApp(context: Context, packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { context.launch(it) }
    }

    /**
     * Starts one specific activity component. Returns false when it can't be launched — most
     * often because the activity isn't exported, which the system blocks from another app.
     */
    fun launchActivity(context: Context, packageName: String, className: String): Boolean = try {
        context.startActivity(
            componentLaunchIntent(packageName, className).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Asks the launcher to pin a home-screen shortcut that opens [className] directly. Returns
     * false if the launcher doesn't support pin requests. The shortcut only works at tap time for
     * an exported activity (the same cross-app rule as [launchActivity]).
     */
    fun createActivityShortcut(
        context: Context,
        packageName: String,
        className: String,
        label: String,
    ): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val builder = ShortcutInfoCompat.Builder(context, "$packageName/$className")
            .setShortLabel(label.ifBlank { className.substringAfterLast('.') })
            .setIntent(componentLaunchIntent(packageName, className))
        activityIcon(context, packageName, className)?.let { builder.setIcon(it) }
        return try {
            ShortcutManagerCompat.requestPinShortcut(context, builder.build(), null)
        } catch (_: Exception) {
            false
        }
    }

    /** ACTION_MAIN intent targeting a single activity (used for both launch and shortcuts). */
    private fun componentLaunchIntent(packageName: String, className: String): Intent =
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName(packageName, className))

    /** The activity's icon (falling back to the app icon) as an [IconCompat], or null. */
    private fun activityIcon(context: Context, packageName: String, className: String): IconCompat? =
        runCatching {
            val drawable = context.packageManager
                .getActivityIcon(ComponentName(packageName, className))
            IconCompat.createWithBitmap(drawable.toBitmap(width = ICON_PX, height = ICON_PX))
        }.getOrNull()

    private const val ICON_PX = 192
}
