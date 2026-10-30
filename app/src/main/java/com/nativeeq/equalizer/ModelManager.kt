package com.nativeeq.equalizer

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Catalog and downloader for on-device .litertlm models. Models live in
 * filesDir/models/ and are downloaded on demand — nothing is bundled.
 */
class ModelManager(private val context: Context) {

    data class ModelInfo(
        val id: String,
        val displayName: String,
        val url: String,
        val sizeBytes: Long,
        val fileName: String,
    )

    enum class DownloadState { IDLE, DOWNLOADING, DONE, FAILED }

    private val executor = Executors.newSingleThreadExecutor()
    private val modelDir = File(context.filesDir, "models")

    @Volatile var downloadState = DownloadState.IDLE
        private set
    @Volatile var downloadProgress = 0f
        private set
    @Volatile var downloadError: String? = null
        private set

    companion object {
        val CATALOG = listOf(
            ModelInfo(
                id = "gemma3-1b",
                displayName = "Gemma 3 1B-IT (int4, LiteRT-LM)",
                url = "https://huggingface.co/lotapa/gemma3-1b-it-int4.litertlm/resolve/main/gemma3-1b-it-int4.litertlm",
                sizeBytes = 584_417_280L,
                fileName = "gemma3-1b-it-int4.litertlm",
            ),
            ModelInfo(
                id = "functiongemma-270m",
                displayName = "FunctionGemma 270M (q8, LiteRT-LM)",
                url = "https://huggingface.co/JackJ1/functiongemma-270m-it-mobile-actions-litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm",
                sizeBytes = 284_426_240L,
                fileName = "functiongemma-270m-q8.litertlm",
            ),
            ModelInfo(
                id = "qwen3-0.6b",
                displayName = "Qwen3 0.6B (LiteRT-LM)",
                url = "https://huggingface.co/emmkei/Qwen3-0.6B.litertlm/resolve/main/Qwen3-0.6B.litertlm",
                sizeBytes = 614_236_160L,
                fileName = "qwen3-0.6b.litertlm",
            ),
        )
        val DEFAULT_MODEL = CATALOG[0]
    }

    fun modelFile(info: ModelInfo = DEFAULT_MODEL): File = File(modelDir, info.fileName)

    fun isModelPresent(info: ModelInfo = DEFAULT_MODEL): Boolean {
        val f = modelFile(info)
        return f.isFile && f.length() > 1_000_000L
    }

    fun anyModelPresent(): ModelInfo? = CATALOG.firstOrNull { isModelPresent(it) }

    fun statusText(): String {
        val present = anyModelPresent()
        return when {
            present != null ->
                "MODEL READY: ${present.displayName} (${modelFile(present).length() / 1_048_576} MB)"
            downloadState == DownloadState.DOWNLOADING ->
                "DOWNLOADING: ${(downloadProgress * 100).toInt()}%"
            downloadState == DownloadState.FAILED ->
                "MODEL FAILED: ${downloadError ?: "unknown"} — heuristic tuner still works"
            else -> "NO MODEL — ML Kit Nano or heuristic tuner will be used"
        }
    }

    fun download(info: ModelInfo = DEFAULT_MODEL, onDone: (Boolean) -> Unit = {}) {
        if (downloadState == DownloadState.DOWNLOADING) return
        downloadState = DownloadState.DOWNLOADING
        downloadProgress = 0f
        downloadError = null
        executor.execute {
            val target = modelFile(info)
            val tmp = File(modelDir, info.fileName + ".part")
            try {
                modelDir.mkdirs()
                val conn = URL(info.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode != 200) {
                    throw Exception("HTTP ${conn.responseCode}")
                }
                val total = max(conn.contentLengthLong, info.sizeBytes)
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var read: Int
                        var written = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            written += read
                            downloadProgress = (written.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
                if (tmp.length() < info.sizeBytes * 0.99f) {
                    throw Exception("truncated download (${tmp.length()} B)")
                }
                tmp.renameTo(target)
                downloadState = DownloadState.DONE
                onDone(true)
            } catch (e: Exception) {
                tmp.delete()
                downloadError = e.message ?: e.javaClass.simpleName
                downloadState = DownloadState.FAILED
                onDone(false)
            }
        }
    }

    fun release() = executor.shutdownNow()
}
