package com.kai.voicelock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

object ModelDownloader {

    const val URL_EN = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val URL_HI = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"

    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val zip = File(ctx.cacheDir, "voice-model.zip")
            if (zip.exists()) zip.delete()

            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VoiceLock/1.0")
            }
            try {
                conn.connect()
                val code = conn.responseCode
                if (code !in 200..299) error("Download fail (HTTP $code)")

                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(zip).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            unzipIntoModel(zip, ctx.filesDir)
            zip.delete()
        }

    private fun unzipIntoModel(zip: File, filesDir: File) {
        val staging = File(filesDir, "model_staging")
        val target = File(filesDir, "model")
        staging.deleteRecursively()
        staging.mkdirs()

        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name.trimStart('/')
                // Strip the top-level folder from the archive.
                val parts = name.split('/', limit = 2)
                if (parts.size < 2 || parts[1].isBlank()) {
                    zin.closeEntry()
                    continue
                }
                val outFile = File(staging, parts[1])
                if (!outFile.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw SecurityException("Unsafe zip entry: $name")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zin.copyTo(fos) }
                }
                zin.closeEntry()
            }
        }

        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
    }
}
