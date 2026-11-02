package com.usaginoyami.cloud.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.usaginoyami.cloud.rclone.CloudFile
import com.usaginoyami.cloud.rclone.RcloneService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class FileKind { IMAGE, VIDEO, OTHER }

/** Carrega miniaturas lendo o arquivo pelo servidor local do rclone (--rc-serve). */
object Thumbs {
    private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    private const val TARGET_PX = 320

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val gate = Semaphore(3)
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val videoExt = setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v")

    fun kind(name: String): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in imageExt -> FileKind.IMAGE
            in videoExt -> FileKind.VIDEO
            else -> FileKind.OTHER
        }
    }

    private fun key(f: CloudFile) = "${f.remote}:${f.path}:${f.modTime}"

    fun cached(f: CloudFile): Bitmap? = cache.get(key(f))

    private fun url(f: CloudFile) =
        "http://${RcloneService.HOST}:${RcloneService.PORT}/[${f.remote}:]/${Uri.encode(f.path, "/")}"

    private val auth get() = Credentials.basic(RcloneService.USER, RcloneService.PASS)

    suspend fun load(f: CloudFile): Bitmap? {
        val k = key(f)
        cache.get(k)?.let { return it }
        if (k in failed) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(k) ?: try {
                    when (kind(f.name)) {
                        FileKind.IMAGE -> if (f.size in 1..MAX_IMAGE_BYTES) image(f) else null
                        FileKind.VIDEO -> video(f)
                        FileKind.OTHER -> null
                    }?.also { cache.put(k, it) } ?: run { failed.add(k); null }
                } catch (_: Throwable) {
                    failed.add(k)
                    null
                }
            }
        }
    }

    private fun image(f: CloudFile): Bitmap? {
        val req = Request.Builder().url(url(f)).header("Authorization", auth).build()
        val bytes = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val rotation = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        return if (rotation == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    }

    private fun video(f: CloudFile): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(url(f), mapOf("Authorization" to auth))
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = TARGET_PX.toFloat() / maxOf(frame.width, frame.height)
            if (scale >= 1f) frame
            else Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }
}

/** Quadrado com a miniatura (ou ícone) do arquivo. */
@Composable
fun Thumb(f: CloudFile, modifier: Modifier = Modifier, iconSize: Int = 40) {
    val kind = remember(f.name) { Thumbs.kind(f.name) }
    var bmp by remember(f.remote, f.path, f.modTime) { mutableStateOf(Thumbs.cached(f)) }
    if (bmp == null && !f.isDir && kind != FileKind.OTHER) {
        LaunchedEffect(f.remote, f.path, f.modTime) { bmp = Thumbs.load(f) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                if (f.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                null,
                Modifier.size(iconSize.dp),
                tint = if (f.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (kind == FileKind.VIDEO && !f.isDir) {
            Icon(
                Icons.Default.PlayArrow, null,
                Modifier.size(28.dp).background(Color(0x99000000), androidx.compose.foundation.shape.CircleShape),
                tint = Color.White,
            )
        }
    }
}
