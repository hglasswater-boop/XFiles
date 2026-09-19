package app.local1st.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.media.VideoRemuxer
import app.local1st.files.core.ops.BackgroundJobs
import app.local1st.files.core.ops.OpsService
import app.local1st.files.di.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Save-as flow for rebuilding a video's MP4 container without re-encoding its media samples. */
class RemuxActivity : ComponentActivity() {
    private lateinit var source: XEntry

    private val createOutput = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { outputUri ->
        if (outputUri == null) {
            finish()
            return@registerForActivityResult
        }
        startRemux(outputUri)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = sourceFromIntent(intent) ?: run {
            Toast.makeText(this, "再構築する動画を開けません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (savedInstanceState == null) {
            createOutput.launch(defaultOutputName(source.name))
        }
    }

    private fun startRemux(outputUri: Uri) {
        val sourceEntry = source
        val jobId = "video-remux:${sourceEntry.id}"
        val job = BackgroundJobs.start(
            id = jobId,
            title = "MP4再構築",
            message = "${sourceEntry.name} を再muxしています",
        ) ?: run {
            Toast.makeText(this, "この動画はすでに再構築中です", Toast.LENGTH_SHORT).show()
            return
        }

        OpsService.start(applicationContext)
        val work = Graph.appScope.launch(Dispatchers.IO) {
            try {
                VideoRemuxer.remuxToMp4(
                    context = Graph.appContext,
                    source = sourceEntry,
                    outputUri = outputUri,
                    smbConnections = Graph.smbConnections,
                    onProgress = job::bytes,
                    isCancelled = job::isCancelled,
                )
                BackgroundJobs.messages.tryEmit("MP4再構築が完了しました: ${sourceEntry.name}")
            } catch (_: CancellationException) {
                BackgroundJobs.messages.tryEmit("MP4再構築をキャンセルしました: ${sourceEntry.name}")
            } catch (error: Throwable) {
                val detail = generateSequence(error) { it.cause }
                    .mapNotNull { it.message }
                    .firstOrNull()
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.take(180)
                    ?: "不明なエラー"
                BackgroundJobs.messages.tryEmit("MP4再構築に失敗しました: $detail")
            } finally {
                BackgroundJobs.finish(job)
            }
        }
        job.attach(work)
    }

    companion object {
        private const val EXTRA_ID = "app.local1st.files.remux.ID"
        private const val EXTRA_NAME = "app.local1st.files.remux.NAME"
        private const val EXTRA_SIZE = "app.local1st.files.remux.SIZE"
        private const val EXTRA_MTIME = "app.local1st.files.remux.MTIME"
        private const val EXTRA_MIME = "app.local1st.files.remux.MIME"
        private const val EXTRA_LOCAL_PATH = "app.local1st.files.remux.LOCAL_PATH"

        fun createIntent(context: Context, entry: XEntry): Intent =
            Intent(context, RemuxActivity::class.java)
                .putExtra(EXTRA_ID, entry.id)
                .putExtra(EXTRA_NAME, entry.name)
                .putExtra(EXTRA_SIZE, entry.size)
                .putExtra(EXTRA_MTIME, entry.mtime)
                .putExtra(EXTRA_MIME, entry.mime)
                .putExtra(EXTRA_LOCAL_PATH, entry.localPath)

        private fun sourceFromIntent(intent: Intent): XEntry? {
            val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return null
            val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: return null
            return XEntry(
                id = id,
                name = name,
                isDir = false,
                size = intent.getLongExtra(EXTRA_SIZE, -1L),
                mtime = intent.getLongExtra(EXTRA_MTIME, 0L),
                mime = intent.getStringExtra(EXTRA_MIME),
                localPath = intent.getStringExtra(EXTRA_LOCAL_PATH),
            )
        }

        private fun defaultOutputName(inputName: String): String {
            val stem = inputName.substringBeforeLast('.', inputName).ifBlank { "video" }
            return "$stem-remux.mp4"
        }
    }
}
