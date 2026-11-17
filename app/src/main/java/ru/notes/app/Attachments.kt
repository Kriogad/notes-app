package ru.notes.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Файлы вложений. Обычные лежат как есть в filesDir/attachments.
 * Секретные — зашифрованы ключом секретного раздела, имя файла без настоящего названия (uuid.enc).
 */
object Attachments {
    fun dir(ctx: Context) = File(ctx.filesDir, "attachments").apply { mkdirs() }
    fun file(ctx: Context, a: Attachment) = File(dir(ctx), a.file)
    private fun viewDir(ctx: Context) = File(ctx.cacheDir, "vault_view")

    /** Копирует выбранный файл внутрь приложения (для секретной заметки — шифрует). */
    suspend fun import(ctx: Context, uri: Uri, secret: Boolean): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "файл"
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val id = UUID.randomUUID().toString().take(12)
            val stored = if (secret) "$id.enc" else id + "_" + name.replace(Regex("[^\\w.\\-]"), "_")
            val target = File(dir(ctx), stored)
            cr.openInputStream(uri)!!.use { input ->
                if (secret) check(Vault.encryptTo(input, target))
                else target.outputStream().use { input.copyTo(it) }
            }
            Attachment(stored, name, mime)
        }.getOrNull()
    }

    fun delete(ctx: Context, a: Attachment) {
        file(ctx, a).delete()
    }

    /** Расшифрованные копии для просмотра удаляются, когда секретный раздел закрывается. */
    fun clearViews(ctx: Context) {
        viewDir(ctx).deleteRecursively()
    }

    fun open(ctx: Context, a: Attachment) {
        val f = if (!a.secret) file(ctx, a) else {
            val bytes = Vault.decrypt(file(ctx, a)) ?: return
            File(viewDir(ctx).apply { mkdirs() }, a.name.replace(Regex("[/\\\\]"), "_")).apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_LONG).show()
        }
    }

    fun thumbnail(ctx: Context, a: Attachment, px: Int): ImageBitmap? = runCatching {
        val bytes = if (a.secret) Vault.decrypt(file(ctx, a)) ?: return null else file(ctx, a).readBytes()
        Images.decode(bytes, px)?.asImageBitmap()
    }.getOrNull()
}

/** Уменьшение картинок с учётом поворота фото (EXIF), чтобы не грузить в память целиком. */
object Images {
    fun decode(bytes: ByteArray, maxPx: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxPx)
        else {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
            var sample = 1
            while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

    @androidx.annotation.RequiresApi(28)
    fun decode(source: ImageDecoder.Source, maxPx: Int): Bitmap = ImageDecoder.decodeBitmap(source) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val big = maxOf(info.size.width, info.size.height)
        if (big > maxPx) {
            val r = maxPx.toFloat() / big
            d.setTargetSize((info.size.width * r).toInt().coerceAtLeast(1), (info.size.height * r).toInt().coerceAtLeast(1))
        }
    }
}

/** Свой фон главного экрана. */
object Background {
    val image = MutableStateFlow<ImageBitmap?>(null)
    private var loaded = false
    private fun file(ctx: Context) = File(ctx.filesDir, "background.jpg")

    suspend fun load(ctx: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        loaded = true
        val f = file(ctx)
        if (f.exists()) image.value = Images.decode(f.readBytes(), 2000)?.asImageBitmap()
    }

    suspend fun set(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val bmp = Images.decode(bytes, 2000)!!
            file(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            image.value = bmp.asImageBitmap()
            loaded = true
        }.isSuccess
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        image.value = null
    }
}

/** Просмотр фото внутри приложения: чёрный экран, щипок — зум, крестик или «назад» — закрыть. */
@Composable
fun PhotoViewer(a: Attachment, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bmp by produceState<ImageBitmap?>(null, a.file) {
                value = withContext(Dispatchers.IO) {
                    val bytes = if (a.secret) Vault.decrypt(Attachments.file(ctx, a))
                    else runCatching { Attachments.file(ctx, a).readBytes() }.getOrNull()
                    bytes?.let { Images.decode(it, 2000)?.asImageBitmap() }
                }
            }
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            bmp?.let {
                Image(
                    it, a.name,
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        },
                    contentScale = ContentScale.Fit,
                )
            }
            androidx.compose.material3.IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Close, "Закрыть",
                    tint = Color.White,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Thumb(a: Attachment, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, a.file) {
        value = withContext(Dispatchers.IO) { Attachments.thumbnail(ctx, a, 400) }
    }
    val m = modifier.size(size).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .let {
            if (onClick != null || onLongClick != null)
                it.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
            else it
        }
    Box(m) {
        bmp?.let { Image(it, a.name, Modifier.size(size), contentScale = ContentScale.Crop) }
    }
}
