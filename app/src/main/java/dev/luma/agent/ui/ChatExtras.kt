package dev.luma.agent.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** «ты» or «вы» in interface texts, from the tone setting. */
val LocalFormal = compositionLocalOf { false }
/** Chat text size factor from the settings. */
val LocalChatTextScale = compositionLocalOf { 1f }
/** Whether short vibrations are on (settings → Вибрация). */
val LocalHaptics = compositionLocalOf { true }
@Composable fun tone(friendly: String, formal: String): String = if (LocalFormal.current) formal else friendly
/** A text in the chosen tone: «Выберите» becomes «Выбери» for the friendly one and back. */
@Composable fun t(text: String): String = Tone.adapt(text, LocalFormal.current)

/** Short haptic ticks; silent when the setting is off. */
enum class Haptic { Send, Confirm, Finish, Error }
fun View.haptic(kind: Haptic, enabled: Boolean) {
    if (!enabled) return
    val code = when (kind) {
        Haptic.Send -> HapticFeedbackConstants.KEYBOARD_TAP
        Haptic.Confirm -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.Finish -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.LONG_PRESS
        Haptic.Error -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(code)
}

/** What a message menu can do; null entries are hidden. */
class MessageActions(val copy: () -> Unit, val share: () -> Unit, val delete: () -> Unit,
    val repeat: (() -> Unit)? = null, val regenerate: (() -> Unit)? = null)

@Composable fun MessageMenu(expanded: Boolean, dismiss: () -> Unit, actions: MessageActions, user: Boolean, select: (() -> Unit)? = null) {
    DropdownMenu(expanded, dismiss, shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("message-menu")) {
        DropdownMenuItem(text = { Text("Копировать") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { dismiss(); actions.copy() })
        select?.let { DropdownMenuItem(text = { Text("Выделить текст") }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) }, onClick = { dismiss(); it() }) }
        DropdownMenuItem(text = { Text("Поделиться") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { dismiss(); actions.share() })
        actions.repeat?.let { DropdownMenuItem(text = { Text(if (user) "Спросить ещё раз" else "Повторить") }, leadingIcon = { Icon(Icons.Outlined.Replay, null) }, onClick = { dismiss(); it() }) }
        actions.regenerate?.let { DropdownMenuItem(text = { Text("Перегенерировать") }, leadingIcon = { Icon(Icons.Outlined.Autorenew, null) }, onClick = { dismiss(); it() }) }
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { dismiss(); actions.delete() })
    }
}

/** An error reply: what went wrong and a way to send the question again. */
@Composable fun FailedReply(message: ChatMessage, canRetry: Boolean, retry: () -> Unit, delete: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f), modifier = Modifier.fillMaxWidth().testTag("failed-${message.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                Text(t(message.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRetry) FilledTonalButton(onClick = retry, Modifier.testTag("retry-${message.id}"), contentPadding = PaddingValues(start = 14.dp, end = 18.dp)) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Переотправить") }
                TextButton(onClick = delete) { Text("Убрать", maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** Pictures the model drew, full width of the answer; tap opens the viewer. */
@Composable fun GeneratedImages(images: List<Attachment>, store: AttachmentStore) {
    var open by remember { mutableStateOf<Attachment?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (image in images) key(image.id) {
            val bitmap by produceState<ImageBitmap?>(null, image.id) { value = withContext(Dispatchers.IO) { decodeScaled(store.file(image), 1280) } }
            val shape = RoundedCornerShape(18.dp)
            Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { open = image }.testTag("generated-image")) {
                val b = bitmap
                if (b == null) Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
                else Image(b, image.name, Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height.coerceAtLeast(1)), contentScale = ContentScale.Fit)
            }
        }
    }
    open?.let { ImageViewer(it, store) { open = null } }
}

/** Full-screen picture with pinch zoom, save to the gallery and share. */
@Composable fun ImageViewer(image: Attachment, store: AttachmentStore, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<ImageBitmap?>(null, image.id) { value = withContext(Dispatchers.IO) { decodeScaled(store.file(image), 2400) } }
    var scale by remember { mutableFloatStateOf(1f) }; var offsetX by remember { mutableFloatStateOf(0f) }; var offsetY by remember { mutableFloatStateOf(0f) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) { if (note != null) { kotlinx.coroutines.delay(2500); note = null } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .97f)).testTag("image-viewer")) {
            bitmap?.let { b ->
                Image(b, image.name, Modifier.fillMaxSize().pointerInput(image.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) { offsetX += pan.x; offsetY += pan.y } else { offsetX = 0f; offsetY = 0f }
                    }
                }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY }, contentScale = ContentScale.Fit)
            }
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { scope.launch { note = saveToGallery(context, store, image) } }) { Icon(Icons.Outlined.Download, "Сохранить в галерею", tint = Color.White) }
                IconButton(onClick = { shareFile(context, store, image) }) { Icon(Icons.Outlined.Share, "Поделиться", tint = Color.White) }
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Закрыть", tint = Color.White) }
            }
            note?.let { Text(t(it), Modifier.align(Alignment.BottomCenter).padding(24.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun decodeScaled(file: File, maxSide: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** Pictures go to Pictures/Luma through MediaStore; no storage permission is needed on Android 10+. */
private suspend fun saveToGallery(context: Context, store: AttachmentStore, image: Attachment): String = withContext(Dispatchers.IO) {
    runCatching {
        val name = "Luma-${System.currentTimeMillis()}." + image.name.substringAfterLast(".", "png")
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, image.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Luma")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
            context.contentResolver.openOutputStream(uri)?.use { out -> store.file(image).inputStream().use { it.copyTo(out) } } ?: error("no stream")
            "Сохранено в «Изображения/Luma»"
        } else {
            shareFile(context, store, image); "Выберите, куда сохранить"
        }
    }.getOrElse { "Не удалось сохранить изображение" }
}

/** Shares an attachment through a temporary copy that FileProvider may expose. */
fun shareFile(context: Context, store: AttachmentStore, item: Attachment) {
    runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val copy = File(dir, item.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "file" })
        store.file(item).copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.model-files", copy)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
fun shareText(context: Context, text: String) {
    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Expandable list of the task's steps with their time and speed. */
@Composable fun RunLog(log: List<StepLog>) {
    if (log.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "log chevron")
    Column {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 4.dp).testTag("run-log"), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FormatListBulleted, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("Журнал · ${log.size} ${steps(log.size)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp).graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (entry in log.takeLast(60)) Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (entry.ok) Icons.Outlined.CheckCircleOutline else Icons.Outlined.ErrorOutline, null, Modifier.size(15.dp).padding(top = 2.dp),
                        tint = if (entry.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                    Text("${entry.number}. ${entry.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(formatSeconds(entry.millis), entry.outputPerSecond?.let { "↓ ${formatRate(it)} т/с" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
private fun steps(n: Int) = when { n % 10 == 1 && n % 100 != 11 -> "шаг"; n % 10 in 2..4 && n % 100 !in 12..14 -> "шага"; else -> "шагов" }

/** The whole message as plain selectable text, for picking out a part of it. */
@Composable fun SelectTextDialog(text: String, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text("Готово") } },
        title = { Text(t("Выделите нужное")) },
        text = { androidx.compose.foundation.text.selection.SelectionContainer {
            Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
        } })
}
