package ru.notes.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object Attachments {
    fun dir(ctx: Context) = File(ctx.filesDir, "attachments").apply { mkdirs() }
    fun file(ctx: Context, a: Attachment) = File(dir(ctx), a.file)

    /** Копирует выбранный файл внутрь приложения. */
    suspend fun import(ctx: Context, uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "файл"
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val stored = UUID.randomUUID().toString().take(8) + "_" + name.replace(Regex("[^\\w.\\-]"), "_")
            cr.openInputStream(uri)!!.use { input -> File(dir(ctx), stored).outputStream().use { input.copyTo(it) } }
            Attachment(stored, name, mime)
        }.getOrNull()
    }

    fun delete(ctx: Context, a: Attachment) {
        file(ctx, a).delete()
    }

    fun open(ctx: Context, a: Attachment) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file(ctx, a))
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_LONG).show()
        }
    }

    /** Уменьшенная картинка, чтобы не грузить в память фото целиком. */
    fun thumbnail(f: File, px: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

@Composable
fun Thumb(a: Attachment, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, a.file) {
        value = withContext(Dispatchers.IO) { Attachments.thumbnail(Attachments.file(ctx, a), 400) }
    }
    val m = modifier.size(size).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Box(m) {
        bmp?.let { Image(it, a.name, Modifier.size(size), contentScale = ContentScale.Crop) }
    }
}
